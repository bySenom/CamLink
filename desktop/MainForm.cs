using System.Net;
using System.Net.NetworkInformation;
using System.Diagnostics;

namespace CamLink.Desktop;

internal sealed class MainForm : Form
{
    private const int HubPort = 6020;
    private const int RtspPort = 8554;
    private readonly HubServer _hub = new();
    private readonly RtspServer _rtsp;
    private readonly LanDiscoveryService _lanDiscovery = new(HubPort);
    private readonly Label _connectionStatus = new() { AutoSize = true, Text = "Hub stopped", ForeColor = Color.DarkRed };
    private readonly Label _virtualCameraStatus = new() { AutoSize = true, Text = "Windows camera: OBS Virtual Camera (waiting for the phone stream)" };
    private readonly ComboBox _camera = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly ComboBox _profile = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly ComboBox _whiteBalance = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly ComboBox _focusMode = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly TrackBar _zoom = new() { Minimum = 10, Maximum = 10, Value = 10, TickFrequency = 10, Width = 270 };
    private readonly NumericUpDown _exposure = new() { Minimum = -12, Maximum = 12, Width = 100 };
    private readonly CheckBox _torch = new() { Text = "Torch / fill light", AutoSize = true };
    private readonly Button _start = new() { Text = "Start stream", AutoSize = true };
    private readonly Button _stop = new() { Text = "Stop stream", AutoSize = true };
    private readonly Button _startVirtualCamera = new() { Text = "Start Windows camera", AutoSize = true };
    private readonly Button _checkUpdates = new() { Text = "Check for updates", AutoSize = true };
    private PhoneCapabilities? _capabilities;
    private bool _suppressEvents;

    public MainForm()
    {
        _rtsp = new RtspServer(_hub.Relay);
        Text = "CamLink – Android camera control";
        MinimumSize = new Size(600, 620);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10);

        var startHub = new Button { Text = "Start hub", AutoSize = true };
        startHub.Click += async (_, _) => await StartHubAsync();
        _start.Click += async (_, _) => await StartStreamAsync();
        _stop.Click += async (_, _) => await SendAsync("stop");
        _startVirtualCamera.Click += (_, _) => StartWindowsCamera();
        _checkUpdates.Click += async (_, _) => await CheckForUpdatesAsync(interactive: true);

        _camera.SelectedIndexChanged += async (_, _) => await OnCameraChangedAsync();
        _profile.SelectedIndexChanged += async (_, _) => await OnProfileChangedAsync();
        _whiteBalance.SelectedIndexChanged += async (_, _) => await OnWhiteBalanceChangedAsync();
        _focusMode.SelectedIndexChanged += async (_, _) => await OnFocusModeChangedAsync();
        _zoom.ValueChanged += async (_, _) => await OnZoomChangedAsync();
        _exposure.ValueChanged += async (_, _) => await OnExposureChangedAsync();
        _torch.CheckedChanged += async (_, _) => await OnTorchChangedAsync();

        var connectionPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, FlowDirection = FlowDirection.LeftToRight, Padding = new Padding(14, 14, 14, 6) };
        connectionPanel.Controls.AddRange([startHub, _connectionStatus]);

        var controls = new TableLayoutPanel
        {
            AutoSize = true,
            ColumnCount = 2,
            Dock = DockStyle.Top,
            Padding = new Padding(14, 8, 14, 8)
        };
        controls.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        controls.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        AddRow(controls, "Camera", _camera);
        AddRow(controls, "Profile", _profile);
        AddRow(controls, "White balance", _whiteBalance);
        AddRow(controls, "Focus", _focusMode);
        AddRow(controls, "Zoom", _zoom);
        AddRow(controls, "Exposure EV steps", _exposure);
        AddRow(controls, "Light", _torch);

        var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, Padding = new Padding(14, 6, 14, 6) };
        actions.Controls.AddRange([_start, _stop, _checkUpdates]);

        var virtualCameraBox = new GroupBox { Text = "Windows virtual camera", AutoSize = true, Dock = DockStyle.Top, Padding = new Padding(12) };
        var virtualCameraPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        virtualCameraPanel.Controls.Add(_virtualCameraStatus);
        virtualCameraPanel.Controls.Add(new Label { AutoSize = true, MaximumSize = new Size(520, 0), Text = "Select “OBS Virtual Camera” as a Video Capture Device in OBS, Discord, Teams, Zoom, or another Windows app. CamLink uses a dedicated OBS collection internally; no browser source is required in the target application." });
        virtualCameraPanel.Controls.Add(_startVirtualCamera);
        virtualCameraBox.Controls.Add(virtualCameraPanel);

        var root = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
        root.Controls.Add(virtualCameraBox);
        root.Controls.Add(actions);
        root.Controls.Add(controls);
        root.Controls.Add(connectionPanel);
        Controls.Add(root);

        _hub.DeviceConnected += name => Ui(() =>
        {
            _connectionStatus.Text = $"Phone connected: {name}";
            _connectionStatus.ForeColor = Color.DarkGreen;
        });
        _hub.DeviceDisconnected += () => Ui(() =>
        {
            _connectionStatus.Text = "Phone disconnected";
            _connectionStatus.ForeColor = Color.DarkRed;
        });
        _hub.CapabilitiesReceived += capabilities => Ui(() => LoadCapabilities(capabilities));
        _hub.StatusReceived += status => Ui(() => ShowPhoneStatus(status));
        Shown += async (_, _) =>
        {
            await StartHubAsync();
            await CheckForUpdatesAsync(interactive: false);
        };
        FormClosing += async (_, _) =>
        {
            await _lanDiscovery.DisposeAsync();
            await _rtsp.DisposeAsync();
            await _hub.DisposeAsync();
        };
    }

    private static void AddRow(TableLayoutPanel panel, string label, Control control)
    {
        var row = panel.RowCount++;
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Padding = new Padding(0, 7, 8, 0) }, 0, row);
        panel.Controls.Add(control, 1, row);
    }

    private async Task StartHubAsync()
    {
        try
        {
            await _hub.StartAsync(HubPort);
            await _rtsp.StartAsync(RtspPort);
            await _lanDiscovery.StartAsync();
            var usbBridge = await AdbUsbBridge.ConfigureReverseAsync(HubPort);
            var usbStatus = usbBridge.Success ? "USB forwarding ready." : $"USB setup: {usbBridge.Message}";
            _connectionStatus.Text = $"Waiting for phone on TCP {HubPort} — Wi-Fi address: {FindLanAddress()} — LAN discovery ready — {usbStatus}";
            _connectionStatus.ForeColor = Color.DarkBlue;
        }
        catch (Exception exception)
        {
            MessageBox.Show(this, exception.Message, "CamLink hub", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void StartWindowsCamera()
    {
        const string obsExecutable = @"C:\Program Files\obs-studio\bin\64bit\obs64.exe";
        if (!File.Exists(obsExecutable))
        {
            _virtualCameraStatus.Text = "OBS Studio was not found. Install OBS Studio to provide the Windows virtual camera.";
            _virtualCameraStatus.ForeColor = Color.DarkRed;
            return;
        }
        if (Process.GetProcessesByName("obs64").Length > 0)
        {
            _virtualCameraStatus.Text = "OBS is already running. The CamLink profile is prepared; start the “OBS Virtual Camera” output from the dedicated CamLink OBS session.";
            _virtualCameraStatus.ForeColor = Color.DarkBlue;
            return;
        }

        Process.Start(new ProcessStartInfo
        {
            FileName = obsExecutable,
            Arguments = "--collection \"CamLink Virtual Camera\" --profile \"CamLink Virtual Camera\" --scene \"CamLink Camera\" --startvirtualcam --minimize-to-tray",
            UseShellExecute = true
        });
        _virtualCameraStatus.Text = "Starting OBS Virtual Camera. It will appear as a Windows video device shortly.";
        _virtualCameraStatus.ForeColor = Color.DarkBlue;
    }

    private async Task CheckForUpdatesAsync(bool interactive)
    {
        _checkUpdates.Enabled = false;
        try
        {
            var update = await UpdateService.CheckForUpdateAsync();
            if (update is null)
            {
                if (interactive)
                {
                    MessageBox.Show(this, "CamLink Hub is up to date.", "CamLink updates", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                return;
            }

            var choice = MessageBox.Show(
                this,
                $"CamLink Hub {update.Version} is available. Download it now and restart the Hub after the current stream closes?",
                "CamLink update available",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Information);
            if (choice != DialogResult.Yes)
            {
                return;
            }

            _connectionStatus.Text = $"Downloading CamLink Hub {update.Version}…";
            _connectionStatus.ForeColor = Color.DarkBlue;
            await UpdateService.DownloadAndRestartAsync(update, Environment.ProcessId);
            Close();
        }
        catch (Exception exception)
        {
            if (interactive)
            {
                MessageBox.Show(this, $"Update check failed: {exception.Message}", "CamLink updates", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
        finally
        {
            if (!IsDisposed) _checkUpdates.Enabled = true;
        }
    }

    private async Task StartStreamAsync()
    {
        if (_camera.SelectedItem is not PhoneCamera camera || _profile.SelectedItem is not CameraProfile profile)
        {
            MessageBox.Show(this, "Connect the phone and select a camera profile first.", "CamLink", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        await SendAsync("start", new
        {
            cameraId = camera.Id,
            width = profile.Width,
            height = profile.Height,
            fps = profile.Fps,
            highSpeed = profile.HighSpeed,
            codec = profile.Codec
        });
    }

    private async Task OnCameraChangedAsync()
    {
        if (_suppressEvents || _camera.SelectedItem is not PhoneCamera camera)
        {
            return;
        }
        _suppressEvents = true;
        _profile.DataSource = camera.Profiles;
        _whiteBalance.DataSource = camera.WhiteBalanceModes;
        _whiteBalance.SelectedIndex = Math.Max(0, camera.WhiteBalanceModes.ToList().IndexOf("Auto"));
        _focusMode.DataSource = camera.FocusModes;
        _focusMode.SelectedIndex = Math.Max(0, camera.FocusModes.ToList().FindIndex(mode => mode.Value == 0));
        _exposure.Minimum = camera.ExposureMin;
        _exposure.Maximum = camera.ExposureMax;
        _exposure.Value = Math.Clamp(0, camera.ExposureMin, camera.ExposureMax);
        _zoom.Maximum = Math.Max(10, (int)Math.Ceiling(camera.MaxZoom * 10));
        _zoom.Value = 10;
        _torch.Enabled = camera.HasFlash;
        if (!camera.HasFlash)
        {
            _torch.Checked = false;
        }
        _suppressEvents = false;
        await SendAsync("selectCamera", camera.Id);
    }

    private Task OnProfileChangedAsync() => _suppressEvents || _profile.SelectedItem is not CameraProfile profile
        ? Task.CompletedTask
        : SendAsync("selectProfile", new { width = profile.Width, height = profile.Height, fps = profile.Fps, highSpeed = profile.HighSpeed, codec = profile.Codec });

    private Task OnWhiteBalanceChangedAsync() => _suppressEvents || _whiteBalance.SelectedItem is not string mode
        ? Task.CompletedTask
        : SendAsync("setWhiteBalance", mode);

    private Task OnFocusModeChangedAsync() => _suppressEvents || _focusMode.SelectedItem is not FocusModeOption mode
        ? Task.CompletedTask
        : SendAsync("setFocusMode", mode.Value);

    private Task OnZoomChangedAsync() => _suppressEvents
        ? Task.CompletedTask
        : SendAsync("setZoom", _zoom.Value / 10f);

    private Task OnExposureChangedAsync() => _suppressEvents
        ? Task.CompletedTask
        : SendAsync("setExposure", decimal.ToInt32(_exposure.Value));

    private Task OnTorchChangedAsync() => _suppressEvents
        ? Task.CompletedTask
        : SendAsync("setTorch", _torch.Checked);

    private async Task SendAsync(string name, object? value = null)
    {
        try
        {
            await _hub.SendCommandAsync(name, value);
        }
        catch (InvalidOperationException)
        {
            // Controls remain useful while waiting for the phone; no noisy dialog for each slider tick.
        }
        catch (Exception exception)
        {
            ShowPhoneStatus(new DeviceStatus(exception.Message, true));
        }
    }

    private void LoadCapabilities(PhoneCapabilities capabilities)
    {
        _capabilities = capabilities;
        _suppressEvents = true;
        _camera.DataSource = capabilities.Cameras;
        _suppressEvents = false;
        if (_camera.Items.Count > 0)
        {
            _camera.SelectedIndex = -1;
            _camera.SelectedIndex = 0;
        }
        _connectionStatus.Text = $"Phone ready: {capabilities.DeviceName}. Profiles shown are reported by Android Camera2.";
        _connectionStatus.ForeColor = Color.DarkGreen;
    }

    private void ShowPhoneStatus(DeviceStatus status)
    {
        _connectionStatus.Text = status.Message;
        _connectionStatus.ForeColor = status.IsError ? Color.DarkRed : Color.DarkBlue;
        if (!status.IsError && status.Message.Contains("streaming", StringComparison.OrdinalIgnoreCase))
        {
            _virtualCameraStatus.Text = "Windows camera is live: select OBS Virtual Camera in your target application.";
            _virtualCameraStatus.ForeColor = Color.DarkGreen;
        }
    }

    private void Ui(Action action)
    {
        if (IsDisposed)
        {
            return;
        }
        if (InvokeRequired)
        {
            BeginInvoke(action);
        }
        else
        {
            action();
        }
    }

    private static string FindLanAddress()
    {
        var address = NetworkInterface.GetAllNetworkInterfaces()
            .Where(network => network.OperationalStatus == OperationalStatus.Up && network.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel)
            .SelectMany(network => network.GetIPProperties().UnicastAddresses)
            .Select(unicast => unicast.Address)
            .FirstOrDefault(ip => ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip));
        return address?.ToString() ?? "not found";
    }
}
