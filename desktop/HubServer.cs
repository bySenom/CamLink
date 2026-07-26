using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace CamLink.Desktop;

internal sealed class HubServer : IAsyncDisposable
{
    private readonly H264Relay _relay = new();
    private readonly CancellationTokenSource _shutdown = new();
    private readonly object _connectionLock = new();
    private TcpListener? _listener;
    private ControlConnection? _control;

    public event Action<PhoneCapabilities>? CapabilitiesReceived;
    public event Action<DeviceStatus>? StatusReceived;
    public event Action<string>? DeviceConnected;
    public event Action? DeviceDisconnected;

    public bool IsRunning => _listener is not null;
    public H264Relay Relay => _relay;

    public Task StartAsync(int port)
    {
        if (_listener is not null)
        {
            return Task.CompletedTask;
        }

        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        _ = Task.Run(AcceptLoopAsync);
        return Task.CompletedTask;
    }

    public async Task SendCommandAsync(string name, object? value = null, CancellationToken cancellationToken = default)
    {
        ControlConnection? control;
        lock (_connectionLock)
        {
            control = _control;
        }

        if (control is null)
        {
            throw new InvalidOperationException("No phone is connected.");
        }

        var payload = JsonSerializer.Serialize(new { type = "command", id = Guid.NewGuid(), name, value });
        await control.SendLineAsync(payload, cancellationToken);
    }

    private async Task AcceptLoopAsync()
    {
        try
        {
            while (!_shutdown.IsCancellationRequested && _listener is not null)
            {
                var client = await _listener.AcceptTcpClientAsync(_shutdown.Token);
                _ = Task.Run(() => HandleClientAsync(client));
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        using var ownedClient = client;
        var stream = client.GetStream();
        try
        {
            var hello = await Wire.ReadLineAsync(stream, _shutdown.Token);
            using var document = JsonDocument.Parse(hello);
            var root = document.RootElement;
            if (!root.TryGetProperty("type", out var type) || type.GetString() != "hello")
            {
                return;
            }

            var channel = root.GetProperty("channel").GetString();
            var deviceName = root.TryGetProperty("deviceName", out var name) ? name.GetString() ?? "Android phone" : "Android phone";
            await Wire.WriteLineAsync(stream, "{\"type\":\"accepted\",\"protocol\":1}", _shutdown.Token);

            if (channel == "video")
            {
                await _relay.ReadVideoAsync(stream, _shutdown.Token);
                return;
            }

            if (channel != "control")
            {
                return;
            }

            var connection = new ControlConnection(stream);
            ControlConnection? replaced;
            lock (_connectionLock)
            {
                replaced = _control;
                _control = connection;
            }
            replaced?.Dispose();
            DeviceConnected?.Invoke(deviceName);

            while (!_shutdown.IsCancellationRequested)
            {
                var line = await Wire.ReadLineAsync(stream, _shutdown.Token);
                using var message = JsonDocument.Parse(line);
                await ProcessControlMessageAsync(message.RootElement);
            }
        }
        catch (EndOfStreamException)
        {
            // Phone disconnected.
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
        catch (Exception exception)
        {
            StatusReceived?.Invoke(new DeviceStatus($"Connection error: {exception.Message}", true));
        }
        finally
        {
            lock (_connectionLock)
            {
                if (_control?.Stream == stream)
                {
                    _control.Dispose();
                    _control = null;
                    DeviceDisconnected?.Invoke();
                }
            }
        }
    }

    private Task ProcessControlMessageAsync(JsonElement root)
    {
        var type = root.TryGetProperty("type", out var typeElement) ? typeElement.GetString() : null;
        switch (type)
        {
            case "capabilities":
                CapabilitiesReceived?.Invoke(PhoneCapabilities.FromJson(root));
                break;
            case "status":
                StatusReceived?.Invoke(DeviceStatus.FromJson(root));
                break;
            case "videoConfig":
                _relay.SetConfig(
                    root.TryGetProperty("codec", out var codec) ? codec.GetString() ?? "h264" : "h264",
                    root.GetProperty("sps").GetString() ?? string.Empty,
                    root.GetProperty("pps").GetString() ?? string.Empty,
                    root.TryGetProperty("vps", out var vps) ? vps.GetString() : null,
                    root.TryGetProperty("fps", out var fps) ? fps.GetInt32() : 30);
                break;
        }
        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        _shutdown.Cancel();
        _listener?.Stop();
        lock (_connectionLock)
        {
            _control?.Dispose();
            _control = null;
        }
        _relay.Dispose();
        await Task.CompletedTask;
        _shutdown.Dispose();
    }

    private sealed class ControlConnection : IDisposable
    {
        private readonly SemaphoreSlim _writeLock = new(1, 1);
        public NetworkStream Stream { get; }

        public ControlConnection(NetworkStream stream) => Stream = stream;

        public async Task SendLineAsync(string value, CancellationToken cancellationToken)
        {
            await _writeLock.WaitAsync(cancellationToken);
            try
            {
                await Wire.WriteLineAsync(Stream, value, cancellationToken);
            }
            finally
            {
                _writeLock.Release();
            }
        }

        public void Dispose() => _writeLock.Dispose();
    }
}

internal static class Wire
{
    public static async Task<string> ReadLineAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var bytes = new List<byte>(256);
        var one = new byte[1];
        while (true)
        {
            var read = await stream.ReadAsync(one, cancellationToken);
            if (read == 0)
            {
                throw new EndOfStreamException();
            }
            if (one[0] == (byte)'\n')
            {
                break;
            }
            if (one[0] != (byte)'\r')
            {
                bytes.Add(one[0]);
            }
            if (bytes.Count > 64 * 1024)
            {
                throw new InvalidDataException("Control message is too large.");
            }
        }
        return Encoding.UTF8.GetString(bytes.ToArray());
    }

    public static Task WriteLineAsync(NetworkStream stream, string value, CancellationToken cancellationToken) =>
        stream.WriteAsync(Encoding.UTF8.GetBytes(value + "\n"), cancellationToken).AsTask();

    public static async Task ReadExactlyAsync(NetworkStream stream, Memory<byte> buffer, CancellationToken cancellationToken)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer[offset..], cancellationToken);
            if (read == 0)
            {
                throw new EndOfStreamException();
            }
            offset += read;
        }
    }

    public static uint ReadUInt32BigEndian(ReadOnlySpan<byte> bytes) => BinaryPrimitives.ReadUInt32BigEndian(bytes);
}
