# CamLink wire protocol

The phone opens two TCP connections to the Windows hub (`6020` by default): a UTF-8 JSON-lines **control** channel and a binary **video** channel. The desktop exposes an RTSP server on `8554` only as the internal feed for the dedicated OBS virtual-camera collection; end-user applications select **OBS Virtual Camera** as their video device.

## Control channel

Every message is one compact JSON object followed by LF. The first message is:

```json
{"type":"hello","channel":"control","deviceName":"SM-S901B","protocol":1}
```

The desktop accepts with `{"type":"accepted","protocol":1}`. The phone sends `capabilities`, `status`, `videoConfig`, `health`, `protectionConfig`, `protectionConfigAck` and `streamProfile` messages. Older hubs ignore unknown message types, so these extensions remain backwards-compatible. The desktop sends `command` messages:

```json
{"type":"command","id":"uuid","name":"setZoom","value":2.0}
```

Supported commands are `start`, `stop`, `selectCamera`, `selectProfile`, `setZoom`, `setExposure`, `setWhiteBalance`, `setTorch`, `setFocusMode`, `getProtectionConfig` and `setProtectionConfig`.

## Health telemetry (schema version 1)

The Android client remains the authority for protection decisions. It emits a
regular `health` snapshot at most once per second while streaming, plus an
immediate snapshot when Android's public thermal status changes. Health values
are not queued when the control socket is unavailable.

```json
{
  "type":"health",
  "schemaVersion":1,
  "batteryLevelPercent":82,
  "batteryTemperatureCelsius":38.4,
  "isCharging":true,
  "chargingSource":"USB",
  "thermalStatus":0,
  "thermalStatusLabel":"Normal",
  "thermalHeadroom":0.42,
  "actualFps":59.94,
  "droppedFrames":2,
  "activeProtectionAction":"REDUCE_BITRATE",
  "requestedProfile":{"width":3840,"height":2160,"fps":30,"codec":"h264"},
  "activeProfile":{"width":1920,"height":1080,"fps":30,"codec":"h264"},
  "activeBitrateMbps":22.5,
  "timestampMs":1760000000000
}
```

`batteryTemperatureCelsius` is **battery temperature only**, obtained from the
public battery broadcast; it is never presented as a whole-device temperature.
`thermalStatus` is Android's public `PowerManager` status. `thermalHeadroom` is
omitted when the Android version or device does not provide a reliable value.

`protectionConfig` contains a `schemaVersion` and the full persistent Android
configuration object. The Hub may send the object as the `value` of a
`setProtectionConfig` command. Android validates and persists it locally, then
answers with `protectionConfigAck` containing `accepted`, either the confirmed
configuration or a human-readable error. `streamProfile` announces a requested
profile change, an active profile, or a controlled stop so the Hub can keep its
display in sync during Camera2 session restarts.

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
