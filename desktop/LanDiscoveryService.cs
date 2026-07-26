using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CamLink.Desktop;

/// <summary>
/// Responds to a small UDP broadcast from CamLink Camera on the local network.
/// The discovery exchange only returns the hub address/port; the actual control
/// and video channels remain the normal TCP connection on <see cref="HubPort"/>.
/// </summary>
internal sealed class LanDiscoveryService : IAsyncDisposable
{
    private const int DiscoveryPort = 6021;
    private static readonly byte[] DiscoveryRequest = Encoding.UTF8.GetBytes("CAMLINK_DISCOVER_V1");
    private readonly int _hubPort;
    private readonly CancellationTokenSource _shutdown = new();
    private UdpClient? _listener;

    public LanDiscoveryService(int hubPort) => _hubPort = hubPort;

    public Task StartAsync()
    {
        if (_listener is not null)
        {
            return Task.CompletedTask;
        }

        _listener = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        _ = Task.Run(ReceiveLoopAsync);
        return Task.CompletedTask;
    }

    private async Task ReceiveLoopAsync()
    {
        var listener = _listener;
        if (listener is null)
        {
            return;
        }

        try
        {
            while (!_shutdown.IsCancellationRequested)
            {
                var received = await listener.ReceiveAsync(_shutdown.Token);
                if (!received.Buffer.AsSpan().SequenceEqual(DiscoveryRequest))
                {
                    continue;
                }

                var response = Encoding.UTF8.GetBytes($"CAMLINK_HUB_V1|{_hubPort}");
                await listener.SendAsync(response, received.RemoteEndPoint, _shutdown.Token);
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
        catch (ObjectDisposedException)
        {
            // Normal shutdown.
        }
    }

    public async ValueTask DisposeAsync()
    {
        _shutdown.Cancel();
        _listener?.Dispose();
        _listener = null;
        await Task.CompletedTask;
        _shutdown.Dispose();
    }
}
