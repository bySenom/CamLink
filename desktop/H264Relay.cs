using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net.Sockets;

namespace CamLink.Desktop;

internal sealed record H264AccessUnit(byte[] Data, ulong PresentationTimeUs);
internal sealed record VideoCodecConfig(string Codec, byte[]? Vps, byte[] Sps, byte[] Pps, int Fps);

internal sealed class H264Relay : IDisposable
{
    private readonly ConcurrentDictionary<Guid, System.Threading.Channels.Channel<H264AccessUnit>> _subscribers = new();
    private readonly object _configLock = new();
    private VideoCodecConfig? _config;

    public bool HasConfig
    {
        get
        {
            lock (_configLock)
            {
                return _config is not null;
            }
        }
    }

    public int Fps
    {
        get
        {
            lock (_configLock)
            {
                return _config?.Fps ?? 30;
            }
        }
    }

    public VideoCodecConfig GetConfig()
    {
        lock (_configLock)
        {
            if (_config is null)
            {
                throw new InvalidOperationException("No codec configuration has arrived from the phone.");
            }
            return _config;
        }
    }

    public void SetConfig(string codec, string spsBase64, string ppsBase64, string? vpsBase64, int fps)
    {
        lock (_configLock)
        {
            var normalizedCodec = codec.Equals("h265", StringComparison.OrdinalIgnoreCase) ? "h265" : "h264";
            var vps = string.IsNullOrWhiteSpace(vpsBase64) ? null : NormalizeNal(Convert.FromBase64String(vpsBase64));
            _config = new VideoCodecConfig(
                normalizedCodec,
                vps,
                NormalizeNal(Convert.FromBase64String(spsBase64)),
                NormalizeNal(Convert.FromBase64String(ppsBase64)),
                Math.Clamp(fps, 1, 240));
        }
    }

    public async Task ReadVideoAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var header = new byte[12];
        while (!cancellationToken.IsCancellationRequested)
        {
            await Wire.ReadExactlyAsync(stream, header, cancellationToken);
            var length = Wire.ReadUInt32BigEndian(header.AsSpan(0, 4));
            var presentationTimeUs = BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(4, 8));
            if (length == 0 || length > 16 * 1024 * 1024)
            {
                throw new InvalidDataException($"Invalid H.264 access-unit length: {length}.");
            }
            var payload = new byte[length];
            await Wire.ReadExactlyAsync(stream, payload, cancellationToken);
            Publish(new H264AccessUnit(payload, presentationTimeUs));
        }
    }

    public RelaySubscription Subscribe()
    {
        var id = Guid.NewGuid();
        var channel = System.Threading.Channels.Channel.CreateBounded<H264AccessUnit>(new System.Threading.Channels.BoundedChannelOptions(24)
        {
            FullMode = System.Threading.Channels.BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
        _subscribers.TryAdd(id, channel);
        return new RelaySubscription(this, id, channel.Reader);
    }

    private void Publish(H264AccessUnit unit)
    {
        foreach (var subscriber in _subscribers.Values)
        {
            subscriber.Writer.TryWrite(unit);
        }
    }

    private void Remove(Guid id)
    {
        if (_subscribers.TryRemove(id, out var channel))
        {
            channel.Writer.TryComplete();
        }
    }

    public void Dispose()
    {
        foreach (var id in _subscribers.Keys)
        {
            Remove(id);
        }
    }

    private static byte[] NormalizeNal(byte[] data)
    {
        var offset = data.Length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1
            ? 4
            : data.Length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1 ? 3 : 0;
        return data[offset..];
    }

    internal sealed class RelaySubscription : IDisposable
    {
        private H264Relay? _owner;
        private readonly Guid _id;
        public System.Threading.Channels.ChannelReader<H264AccessUnit> Reader { get; }

        internal RelaySubscription(H264Relay owner, Guid id, System.Threading.Channels.ChannelReader<H264AccessUnit> reader)
        {
            _owner = owner;
            _id = id;
            Reader = reader;
        }

        public void Dispose()
        {
            Interlocked.Exchange(ref _owner, null)?.Remove(_id);
        }
    }
}
