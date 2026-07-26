# CamLink wire protocol

The phone opens two TCP connections to the Windows hub (`6020` by default): a UTF-8 JSON-lines **control** channel and a binary **video** channel. The desktop exposes an RTSP server on `8554` only as the internal feed for the dedicated OBS virtual-camera collection; end-user applications select **OBS Virtual Camera** as their video device.

## Control channel

Every message is one compact JSON object followed by LF. The first message is:

```json
{"type":"hello","channel":"control","deviceName":"SM-S901B","protocol":1}
```

The desktop accepts with `{"type":"accepted","protocol":1}`. The phone sends `capabilities`, `status` and `videoConfig` messages. The desktop sends `command` messages:

```json
{"type":"command","id":"uuid","name":"setZoom","value":2.0}
```

Supported commands are `start`, `stop`, `selectCamera`, `selectProfile`, `setZoom`, `setExposure`, `setWhiteBalance`, `setTorch` and `setFocusMode`.

## Video channel

The video socket starts with the usual `hello` JSON line, with `channel` equal to `video`. It then carries repeated H.264 or HEVC/H.265 encoded-access-unit records:

```text
uint32 big-endian encoded_access_unit_length
uint64 big-endian presentation_time_us
encoded H.264 access unit
```

The `videoConfig` message carries the codec-specific SPS/PPS values (and VPS for HEVC) encoded in base64. The desktop converts incoming H.264 to RTP/H.264 (RFC 6184) and HEVC to RTP/HEVC fragmentation units (RFC 7798), then serves it locally over RTSP. The current RTSP implementation intentionally accepts interleaved TCP transport only; OBS/FFmpeg supports it.

## Transports

| Mode | Control | Video | Notes |
| --- | --- | --- | --- |
| USB | TCP over `adb reverse` | TCP over `adb reverse` | Most reliable and lowest-latency development path. |
| Wi-Fi | TCP over trusted LAN | TCP over trusted LAN | Desktop IP is entered in the phone app. |
| Smart | USB first, Wi-Fi fallback | USB first, Wi-Fi fallback | Uses the configured Wi-Fi address only after USB fails. |
| Bluetooth | Not implemented in this build | Never | Reserved for a future pairing/control extension; not sufficient for video bandwidth. |
