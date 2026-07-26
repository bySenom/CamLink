using System.Text.Json;

namespace CamLink.Desktop;

internal sealed record CameraProfile(int Width, int Height, int Fps, bool HighSpeed, string Codec, string Source, string Verification)
{
    public override string ToString()
    {
        var status = Verification switch
        {
            "VERIFIED" => " (verified)",
            "UNSTABLE" => " (unstable)",
            "UNSUPPORTED" => " (unsupported)",
            _ => string.Empty
        };
        return $"{Width} x {Height} @ {Fps} fps" + (HighSpeed ? " (High speed)" : string.Empty) + status;
    }
}

internal sealed record FocusModeOption(int Value)
{
    public override string ToString() => Value switch
    {
        1 => "Auto focus",
        2 => "Locked focus",
        _ => "Continuous video"
    };
}

internal sealed record PhoneCamera(
    string Id,
    string Name,
    float MaxZoom,
    bool HasFlash,
    IReadOnlyList<CameraProfile> Profiles,
    int ExposureMin,
    int ExposureMax,
    IReadOnlyList<string> WhiteBalanceModes,
    IReadOnlyList<FocusModeOption> FocusModes)
{
    public override string ToString() => Name;
}

internal sealed record PhoneCapabilities(string DeviceName, IReadOnlyList<PhoneCamera> Cameras, int ExposureMin, int ExposureMax, IReadOnlyList<string> WhiteBalanceModes)
{
    public static PhoneCapabilities FromJson(JsonElement root)
    {
        var deviceName = root.TryGetProperty("deviceName", out var name) ? name.GetString() ?? "Android phone" : "Android phone";
        var exposureMin = root.TryGetProperty("exposureMin", out var min) ? min.GetInt32() : 0;
        var exposureMax = root.TryGetProperty("exposureMax", out var max) ? max.GetInt32() : 0;
        var whiteBalance = root.TryGetProperty("whiteBalanceModes", out var balance)
            ? balance.EnumerateArray().Select(x => x.GetString() ?? "Auto").ToArray()
            : ["Auto"];

        var cameras = new List<PhoneCamera>();
        if (root.TryGetProperty("cameras", out var camerasJson))
        {
            foreach (var camera in camerasJson.EnumerateArray())
            {
                var profiles = new List<CameraProfile>();
                if (camera.TryGetProperty("profiles", out var profilesJson))
                {
                    foreach (var profile in profilesJson.EnumerateArray())
                    {
                        profiles.Add(new CameraProfile(
                            profile.GetProperty("width").GetInt32(),
                            profile.GetProperty("height").GetInt32(),
                            profile.GetProperty("fps").GetInt32(),
                            profile.TryGetProperty("highSpeed", out var highSpeed) && highSpeed.GetBoolean(),
                            profile.TryGetProperty("codec", out var codec) ? codec.GetString() ?? "h264" : "h264",
                            profile.TryGetProperty("source", out var source) ? source.GetString() ?? "CAMERA2" : "CAMERA2",
                            profile.TryGetProperty("verification", out var verification) ? verification.GetString() ?? "REPORTED" : "REPORTED"));
                    }
                }

                var cameraExposureMin = camera.TryGetProperty("exposureMin", out var cameraMin) ? cameraMin.GetInt32() : exposureMin;
                var cameraExposureMax = camera.TryGetProperty("exposureMax", out var cameraMax) ? cameraMax.GetInt32() : exposureMax;
                var cameraWhiteBalance = camera.TryGetProperty("whiteBalanceModes", out var cameraBalance)
                    ? cameraBalance.EnumerateArray().Select(x => x.GetString() ?? "Auto").ToArray()
                    : whiteBalance;
                var focusModes = camera.TryGetProperty("focusModes", out var focus)
                    ? focus.EnumerateArray().Select(x => new FocusModeOption(x.GetInt32())).ToArray()
                    : [new FocusModeOption(0)];

                cameras.Add(new PhoneCamera(
                    camera.GetProperty("id").GetString() ?? string.Empty,
                    camera.GetProperty("name").GetString() ?? "Camera",
                    camera.TryGetProperty("maxZoom", out var zoom) ? zoom.GetSingle() : 1f,
                    camera.TryGetProperty("hasFlash", out var flash) && flash.GetBoolean(),
                    profiles,
                    cameraExposureMin,
                    cameraExposureMax,
                    cameraWhiteBalance,
                    focusModes));
            }
        }

        return new PhoneCapabilities(deviceName, cameras, exposureMin, exposureMax, whiteBalance);
    }
}

internal sealed record DeviceStatus(string Message, bool IsError)
{
    public static DeviceStatus FromJson(JsonElement root) => new(
        root.TryGetProperty("message", out var message) ? message.GetString() ?? string.Empty : string.Empty,
        root.TryGetProperty("error", out var error) && error.GetBoolean());
}
