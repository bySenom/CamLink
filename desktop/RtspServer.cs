using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CamLink.Desktop;

internal sealed class RtspServer : IAsyncDisposable
{
    private readonly H264Relay _relay;
    private readonly CancellationTokenSource _shutdown = new();
    private TcpListener? _listener;

    public RtspServer(H264Relay relay) => _relay = relay;

    public Task StartAsync(int port)
    {
        if (_listener is not null)
        {
            return Task.CompletedTask;
        }
        _listener = new TcpListener(IPAddress.Loopback, port);
        _listener.Start();
        _ = Task.Run(AcceptLoopAsync);
        return Task.CompletedTask;
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
            var session = Guid.NewGuid().ToString("N");
            while (!_shutdown.IsCancellationRequested)
            {
                var request = await RtspRequest.ReadAsync(stream, _shutdown.Token);
                if (request is null)
                {
                    return;
                }

                switch (request.Method)
                {
                    case "OPTIONS":
                        await WriteResponseAsync(stream, request.CSeq, 200, "OK", ["Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"], null);
                        break;
                    case "DESCRIBE":
                        if (!_relay.HasConfig)
                        {
                            await WriteResponseAsync(stream, request.CSeq, 503, "Stream not ready", [], null);
                            break;
                        }
                        var config = _relay.GetConfig();
                        var sdp = CreateSdp(config);
                        await WriteResponseAsync(stream, request.CSeq, 200, "OK", ["Content-Type: application/sdp"], sdp);
                        break;
                    case "SETUP":
                        if (!request.Headers.TryGetValue("Transport", out var transport) || !transport.Contains("RTP/AVP/TCP", StringComparison.OrdinalIgnoreCase))
                        {
                            await WriteResponseAsync(stream, request.CSeq, 461, "Unsupported Transport", [], null);
                            break;
                        }
                        await WriteResponseAsync(stream, request.CSeq, 200, "OK", [$"Transport: RTP/AVP/TCP;unicast;interleaved=0-1", $"Session: {session}"], null);
                        break;
                    case "PLAY":
                        await WriteResponseAsync(stream, request.CSeq, 200, "OK", [$"Session: {session}", "RTP-Info: url=trackID=0;seq=1;rtptime=0"], null);
                        await StreamRtpAsync(stream, _shutdown.Token);
                        return;
                    case "TEARDOWN":
                        await WriteResponseAsync(stream, request.CSeq, 200, "OK", [$"Session: {session}"], null);
                        return;
                    default:
                        await WriteResponseAsync(stream, request.CSeq, 405, "Method Not Allowed", [], null);
                        break;
                }
            }
        }
        catch (EndOfStreamException)
        {
            // OBS closed the source.
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
    }

    private async Task StreamRtpAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var packetizer = new H264RtpPacketizer();
        var config = _relay.GetConfig();
        var timestamp = 0u;
        var initialNals = config.Codec == "h265"
            ? new[] { config.Vps!, config.Sps, config.Pps }
            : new[] { config.Sps, config.Pps };
        for (var index = 0; index < initialNals.Length; index++)
        {
            foreach (var packet in packetizer.Packetize(initialNals[index], timestamp, index == initialNals.Length - 1, config.Codec))
            {
                await WriteInterleavedAsync(stream, packet, cancellationToken);
            }
        }

        using var subscription = _relay.Subscribe();
        await foreach (var accessUnit in subscription.Reader.ReadAllAsync(cancellationToken))
        {
            timestamp = unchecked((uint)(accessUnit.PresentationTimeUs * 90 / 1000));
            var nals = H264RtpPacketizer.SplitAccessUnit(accessUnit.Data).ToArray();
            for (var index = 0; index < nals.Length; index++)
            {
                foreach (var packet in packetizer.Packetize(nals[index], timestamp, index == nals.Length - 1, config.Codec))
                {
                    await WriteInterleavedAsync(stream, packet, cancellationToken);
                }
            }
        }
    }

    private static string CreateSdp(VideoCodecConfig config)
    {
        var formatParameters = config.Codec == "h265"
            ? $"a=fmtp:96 sprop-vps={Convert.ToBase64String(config.Vps!)};sprop-sps={Convert.ToBase64String(config.Sps)};sprop-pps={Convert.ToBase64String(config.Pps)}"
            : $"a=fmtp:96 packetization-mode=1;profile-level-id={Convert.ToHexString(config.Sps.AsSpan(1, 3)).ToLowerInvariant()};sprop-parameter-sets={Convert.ToBase64String(config.Sps)},{Convert.ToBase64String(config.Pps)}";
        return string.Join("\r\n",
            "v=0",
            "o=- 0 0 IN IP4 127.0.0.1",
            "s=CamLink",
            "c=IN IP4 127.0.0.1",
            "t=0 0",
            "a=tool:CamLink",
            "m=video 0 RTP/AVP 96",
            $"a=rtpmap:96 {(config.Codec == "h265" ? "H265" : "H264")}/90000",
            formatParameters,
            "a=control:trackID=0",
            string.Empty);
    }

    private static async Task WriteResponseAsync(NetworkStream stream, string cseq, int status, string reason, IEnumerable<string> headers, string? body)
    {
        var lines = new List<string> { $"RTSP/1.0 {status} {reason}", $"CSeq: {cseq}" };
        lines.AddRange(headers);
        if (body is not null)
        {
            lines.Add($"Content-Length: {Encoding.UTF8.GetByteCount(body)}");
        }
        lines.Add(string.Empty);
        if (body is not null)
        {
            lines.Add(body);
        }
        await stream.WriteAsync(Encoding.UTF8.GetBytes(string.Join("\r\n", lines)));
    }

    private static async Task WriteInterleavedAsync(NetworkStream stream, byte[] packet, CancellationToken cancellationToken)
    {
        var header = new byte[4];
        header[0] = (byte)'$';
        header[1] = 0;
        BinaryPrimitives.WriteUInt16BigEndian(header.AsSpan(2), checked((ushort)packet.Length));
        await stream.WriteAsync(header, cancellationToken);
        await stream.WriteAsync(packet, cancellationToken);
    }

    public async ValueTask DisposeAsync()
    {
        _shutdown.Cancel();
        _listener?.Stop();
        await Task.CompletedTask;
        _shutdown.Dispose();
    }
}

internal sealed record RtspRequest(string Method, string Uri, string CSeq, Dictionary<string, string> Headers)
{
    public static async Task<RtspRequest?> ReadAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        string firstLine;
        try
        {
            firstLine = await Wire.ReadLineAsync(stream, cancellationToken);
        }
        catch (EndOfStreamException)
        {
            return null;
        }
        var parts = firstLine.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 3)
        {
            throw new InvalidDataException("Invalid RTSP request line.");
        }
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        while (true)
        {
            var line = await Wire.ReadLineAsync(stream, cancellationToken);
            if (line.Length == 0)
            {
                break;
            }
            var separator = line.IndexOf(':');
            if (separator > 0)
            {
                headers[line[..separator].Trim()] = line[(separator + 1)..].Trim();
            }
        }
        return new RtspRequest(parts[0], parts[1], headers.TryGetValue("CSeq", out var cseq) ? cseq : "0", headers);
    }
}

internal sealed class H264RtpPacketizer
{
    private const int MaxPayload = 1_200;
    private ushort _sequence;
    private readonly uint _ssrc = unchecked((uint)Random.Shared.NextInt64());

    public IEnumerable<byte[]> Packetize(byte[] nal, uint timestamp, bool marker, string codec)
    {
        nal = NormalizeNal(nal);
        if (nal.Length == 0)
        {
            yield break;
        }
        if (nal.Length <= MaxPayload)
        {
            yield return BuildPacket(nal, timestamp, marker);
            yield break;
        }

        if (codec == "h265")
        {
            foreach (var packet in PacketizeH265(nal, timestamp, marker))
            {
                yield return packet;
            }
            yield break;
        }

        var nalHeader = nal[0];
        var position = 1;
        var maxFragment = MaxPayload - 2;
        while (position < nal.Length)
        {
            var length = Math.Min(maxFragment, nal.Length - position);
            var start = position == 1;
            var end = position + length == nal.Length;
            var fu = new byte[length + 2];
            fu[0] = (byte)((nalHeader & 0xE0) | 28);
            fu[1] = (byte)((start ? 0x80 : 0) | (end ? 0x40 : 0) | (nalHeader & 0x1F));
            Buffer.BlockCopy(nal, position, fu, 2, length);
            yield return BuildPacket(fu, timestamp, marker && end);
            position += length;
        }
    }

    private IEnumerable<byte[]> PacketizeH265(byte[] nal, uint timestamp, bool marker)
    {
        if (nal.Length < 3)
        {
            yield break;
        }
        var nalType = (nal[0] >> 1) & 0x3f;
        var position = 2;
        var maxFragment = MaxPayload - 3;
        while (position < nal.Length)
        {
            var length = Math.Min(maxFragment, nal.Length - position);
            var start = position == 2;
            var end = position + length == nal.Length;
            var fu = new byte[length + 3];
            fu[0] = (byte)((nal[0] & 0x81) | (49 << 1)); // F + FU type + layer-id high bit
            fu[1] = nal[1];
            fu[2] = (byte)((start ? 0x80 : 0) | (end ? 0x40 : 0) | nalType);
            Buffer.BlockCopy(nal, position, fu, 3, length);
            yield return BuildPacket(fu, timestamp, marker && end);
            position += length;
        }
    }

    public static IEnumerable<byte[]> SplitAccessUnit(byte[] data)
    {
        var positions = new List<int>();
        for (var i = 0; i + 3 < data.Length; i++)
        {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && data[i + 3] == 1)))
            {
                positions.Add(i);
            }
        }
        if (positions.Count == 0)
        {
            yield return data;
            yield break;
        }
        for (var index = 0; index < positions.Count; index++)
        {
            var start = positions[index] + (data[positions[index] + 2] == 1 ? 3 : 4);
            var end = index + 1 < positions.Count ? positions[index + 1] : data.Length;
            if (end > start)
            {
                yield return data[start..end];
            }
        }
    }

    private byte[] BuildPacket(byte[] payload, uint timestamp, bool marker)
    {
        var packet = new byte[payload.Length + 12];
        packet[0] = 0x80;
        packet[1] = (byte)(96 | (marker ? 0x80 : 0));
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(2), _sequence++);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4), timestamp);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(8), _ssrc);
        Buffer.BlockCopy(payload, 0, packet, 12, payload.Length);
        return packet;
    }

    private static byte[] NormalizeNal(byte[] data)
    {
        var offset = data.Length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1 ? 4
            : data.Length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1 ? 3 : 0;
        return data[offset..];
    }
}
