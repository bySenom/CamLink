using System.Net;
using System.Net.NetworkInformation;
using System.Diagnostics;
using System.Text.Json;

namespace CamLink.Desktop;

internal sealed class MainForm : Form
{
    private const int HubPort = 6020;
    private const int RtspPort = 8554;
    private readonly HubServer _hub = new();
    private readonly RtspServer _rtsp;
    private readonly LanDiscoveryService _lanDiscovery = new(HubPort);
    private readonly Label _connectionStatus = new() { AutoSize = true, Text = "Hub stopped", ForeColor = Color.DarkRed };
    private readonly Label _healthStatus = new() { AutoSize = true, Text = "FPS: – | Akku: – | Temperatur: – | Thermik: Nicht verfügbar", ForeColor = Color.DimGray, MaximumSize = new Size(760, 0) };
    private readonly Label _protectionStatus = new() { AutoSize = true, Text = "Protection: waiting for phone configuration", ForeColor = Color.DimGray };
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
    private readonly Button _editProtection = new() { Text = "Edit protection…", AutoSize = true };
    private readonly ToolTip _toolTip = new();
    private PhoneCapabilities? _capabilities;
    private ProtectionConfiguration? _protectionConfiguration;
    private bool _suppressEvents;

    public MainForm()
    {
        _rtsp = new RtspServer(_hub.Relay);
        Text = "CamLink – Android camera control";
        MinimumSize = new Size(600, 700);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10);

        var startHub = new Button { Text = "Start hub", AutoSize = true };
        startHub.Click += async (_, _) => await StartHubAsync();
        _start.Click += async (_, _) => await StartStreamAsync();
        _stop.Click += async (_, _) => await SendAsync("stop");
        _startVirtualCamera.Click += (_, _) => StartWindowsCamera();
        _checkUpdates.Click += async (_, _) => await CheckForUpdatesAsync(interactive: true);
        _editProtection.Click += async (_, _) => await EditProtectionAsync();

        _camera.SelectedIndexChanged += async (_, _) => await OnCameraChangedAsync();
        _profile.SelectedIndexChanged += async (_, _) => await OnProfileChangedAsync();
        _whiteBalance.SelectedIndexChanged += async (_, _) => await OnWhiteBalanceChangedAsync();
        _focusMode.SelectedIndexChanged += async (_, _) => await OnFocusModeChangedAsync();
        _zoom.ValueChanged += async (_, _) => await OnZoomChangedAsync();
        _exposure.ValueChanged += async (_, _) => await OnExposureChangedAsync();
        _torch.CheckedChanged += async (_, _) => await OnTorchChangedAsync();

        var connectionPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, FlowDirection = FlowDirection.LeftToRight, Padding = new Padding(14, 14, 14, 6) };
        connectionPanel.Controls.AddRange([startHub, _connectionStatus]);

        var healthPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(14, 4, 14, 4) };
        healthPanel.Controls.Add(_healthStatus);
        _toolTip.SetToolTip(_healthStatus, "Temperatur means battery temperature only. Thermik is Android's public thermal status. Thermal Headroom is shown in the detail tooltip only when Android makes it available.");

        var protectionBox = new GroupBox { Text = "Phone protection", AutoSize = true, Dock = DockStyle.Top, Padding = new Padding(12) };
        var protectionPanel = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        protectionPanel.Controls.Add(_protectionStatus);
        protectionPanel.Controls.Add(new Label { AutoSize = true, MaximumSize = new Size(520, 0), Text = "The Android client is authoritative: it persists and applies protection locally even if this Hub disconnects. Edit opens the complete versioned configuration and the phone confirms accepted values." });
        protectionPanel.Controls.Add(_editProtection);
        protectionBox.Controls.Add(protectionPanel);

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
        root.Controls.Add(protectionBox);
        root.Controls.Add(actions);
        root.Controls.Add(controls);
        root.Controls.Add(healthPanel);
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
        _hub.HealthReceived += health => Ui(() => ShowHealth(health));
        _hub.ProtectionConfigurationReceived += configuration => Ui(() => ShowProtectionConfiguration(configuration, confirmed: true));
        _hub.ProtectionConfigurationAcknowledged += acknowledgement => Ui(() => ShowProtectionAcknowledgement(acknowledgement));
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

    private async Task EditProtectionAsync()
    {
        if (_protectionConfiguration is null)
        {
            await SendAsync("getProtectionConfig");
            _protectionStatus.Text = "Protection: requested configuration from phone; open this editor again after it arrives.";
            _protectionStatus.ForeColor = Color.DarkBlue;
            return;
        }
        var initial = _protectionConfiguration.Json;
        using var dialog = new Form
        {
            Text = "CamLink phone protection configuration",
            StartPosition = FormStartPosition.CenterParent,
            Size = new Size(720, 700),
            MinimizeBox = false,
            MaximizeBox = false
        };
        var explanation = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(660, 0),
            Text = "This is the complete versioned configuration stored on the phone. Values are checked locally before sending and validated again by Android. Battery temperature limits are device-dependent user thresholds, not universal safety values."
        };
        var editor = new TextBox
        {
            Multiline = true,
            AcceptsReturn = true,
            AcceptsTab = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = false,
            Dock = DockStyle.Fill,
            Font = new Font(FontFamily.GenericMonospace, 9),
            Text = initial
        };
        var apply = new Button { Text = "Send validated configuration", DialogResult = DialogResult.OK, AutoSize = true };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Bottom, FlowDirection = FlowDirection.RightToLeft };
        buttons.Controls.AddRange([cancel, apply]);
        var root = new Panel { Dock = DockStyle.Fill, Padding = new Padding(14) };
        root.Controls.Add(editor);
        root.Controls.Add(buttons);
        root.Controls.Add(explanation);
        explanation.Dock = DockStyle.Top;
        dialog.Controls.Add(root);

        if (dialog.ShowDialog(this) != DialogResult.OK)
        {
            return;
        }
        if (!ProtectionConfiguration.TryValidate(editor.Text, out var error))
        {
            MessageBox.Show(this, error, "Invalid protection configuration", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        try
        {
            using var document = JsonDocument.Parse(editor.Text);
            await _hub.SendCommandAsync("setProtectionConfig", document.RootElement.Clone());
            _protectionStatus.Text = "Protection: waiting for Android confirmation…";
            _protectionStatus.ForeColor = Color.DarkBlue;
        }
        catch (Exception exception)
        {
            ShowPhoneStatus(new DeviceStatus($"Could not send protection configuration: {exception.Message}", true));
        }
    }

    private void ShowHealth(DeviceHealth health)
    {
        var fps = health.ActualFps is { } actual ? actual.ToString("0.00") : "–";
        var battery = health.BatteryLevelPercent is { } level ? $"{level} %" : "–";
        var temperature = health.BatteryTemperatureCelsius is { } temperatureCelsius
            ? $"Akku: {temperatureCelsius:0.0} °C"
            : "Temperatur: –";
        var power = health.IsCharging ? $" | Laden: {health.ChargingSource}" : string.Empty;
        var drops = health.DroppedFrames is { } dropped && dropped > 0 ? $" | Drops: {dropped}" : string.Empty;
        var action = string.IsNullOrWhiteSpace(health.ActiveProtectionAction) ? string.Empty : $" | Schutz: {health.ActiveProtectionAction}";
        _healthStatus.Text = $"FPS: {fps} | Akku: {battery} | {temperature} | Thermik: {health.ThermalStatusLabel}{power}{drops}{action}";
        _healthStatus.ForeColor = health.ThermalStatus switch
        {
            >= 4 => Color.Crimson,
            3 => Color.Red,
            2 => Color.DarkOrange,
            1 => Color.Goldenrod,
            _ => Color.DarkGreen
        };
        var headroom = health.ThermalHeadroom is { } value ? $" Thermal Headroom: {value:0.00}." : " Thermal Headroom is unavailable on this Android version/device.";
        _toolTip.SetToolTip(_healthStatus, $"Temperature is the battery temperature only; it is not a whole-device sensor. Android thermal status: {health.ThermalStatusLabel}.{headroom}");
    }

    private void ShowProtectionConfiguration(ProtectionConfiguration configuration, bool confirmed)
    {
        _protectionConfiguration = configuration;
        var profile = "CUSTOM";
        try
        {
            using var document = JsonDocument.Parse(configuration.Json);
            if (document.RootElement.TryGetProperty("profile", out var value)) profile = value.GetString() ?? profile;
        }
        catch (JsonException)
        {
            // Keep the raw configuration available for diagnostics; Android owns validation.
        }
        _protectionStatus.Text = $"Protection: {profile} {(confirmed ? "confirmed by phone" : "pending")}";
        _protectionStatus.ForeColor = Color.DarkGreen;
    }

    private void ShowProtectionAcknowledgement(ProtectionConfigurationAck acknowledgement)
    {
        if (acknowledgement.Accepted && acknowledgement.Configuration is { } configuration)
        {
            ShowProtectionConfiguration(configuration, confirmed: true);
            return;
        }
        _protectionStatus.Text = $"Protection configuration rejected: {acknowledgement.Error ?? "unknown reason"}";
        _protectionStatus.ForeColor = Color.DarkRed;
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
