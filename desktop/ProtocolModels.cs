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

internal sealed record HealthStreamProfile(int Width, int Height, int Fps, string Codec)
{
    public override string ToString() => $"{Width}x{Height}@{Fps}/{Codec}";

    public static HealthStreamProfile? FromJson(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Object ||
            !value.TryGetProperty("width", out var width) ||
            !value.TryGetProperty("height", out var height) ||
            !value.TryGetProperty("fps", out var fps))
        {
            return null;
        }

        return new HealthStreamProfile(
            width.GetInt32(),
            height.GetInt32(),
            fps.GetInt32(),
            value.TryGetProperty("codec", out var codec) ? codec.GetString() ?? "h264" : "h264");
    }
}

internal sealed record DeviceHealth(
    int SchemaVersion,
    int? BatteryLevelPercent,
    float? BatteryTemperatureCelsius,
    bool IsCharging,
    string ChargingSource,
    int? ThermalStatus,
    string ThermalStatusLabel,
    float? ThermalHeadroom,
    float? ActualFps,
    long? DroppedFrames,
    string? ActiveProtectionAction,
    HealthStreamProfile? RequestedProfile,
    HealthStreamProfile? ActiveProfile,
    float? ActiveBitrateMbps,
    long TimestampMs)
{
    public static DeviceHealth FromJson(JsonElement root) => new(
        root.TryGetProperty("schemaVersion", out var schema) ? schema.GetInt32() : 1,
        OptionalInt(root, "batteryLevelPercent"),
        OptionalFloat(root, "batteryTemperatureCelsius"),
        root.TryGetProperty("isCharging", out var charging) && charging.ValueKind == JsonValueKind.True,
        root.TryGetProperty("chargingSource", out var source) ? source.GetString() ?? "UNKNOWN" : "UNKNOWN",
        OptionalInt(root, "thermalStatus"),
        root.TryGetProperty("thermalStatusLabel", out var thermalLabel) ? thermalLabel.GetString() ?? "Nicht verfügbar" : "Nicht verfügbar",
        OptionalFloat(root, "thermalHeadroom"),
        OptionalFloat(root, "actualFps"),
        OptionalLong(root, "droppedFrames"),
        root.TryGetProperty("activeProtectionAction", out var action) && action.ValueKind == JsonValueKind.String ? action.GetString() : null,
        root.TryGetProperty("requestedProfile", out var requested) ? HealthStreamProfile.FromJson(requested) : null,
        root.TryGetProperty("activeProfile", out var active) ? HealthStreamProfile.FromJson(active) : null,
        OptionalFloat(root, "activeBitrateMbps"),
        OptionalLong(root, "timestampMs") ?? 0L);

    private static int? OptionalInt(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var result) ? result : null;

    private static long? OptionalLong(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result) ? result : null;

    private static float? OptionalFloat(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetSingle(out var result) ? result : null;
}

internal sealed record ProtectionConfiguration(int SchemaVersion, string Json)
{
    public static ProtectionConfiguration FromJson(JsonElement root)
    {
        var version = root.TryGetProperty("schemaVersion", out var schema) ? schema.GetInt32() : 1;
        var json = root.TryGetProperty("config", out var config) && config.ValueKind == JsonValueKind.Object
            ? config.GetRawText()
            : "{}";
        return new ProtectionConfiguration(version, json);
    }

    public static bool TryValidate(string json, out string error)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                error = "Protection configuration must be a JSON object.";
                return false;
            }
            if (!Within(root, "lowBatteryPercent", 1, 100) || !Within(root, "criticalBatteryPercent", 1, 100))
            {
                error = "Battery thresholds must be between 1 and 100 percent.";
                return false;
            }
            var low = root.GetProperty("lowBatteryPercent").GetInt32();
            var critical = root.GetProperty("criticalBatteryPercent").GetInt32();
            if (critical > low)
            {
                error = "Critical battery threshold cannot exceed the warning threshold.";
                return false;
            }
            if (!Within(root, "bitrateReductionPercent", 1, 90) || !Within(root, "minimumBitrateMbps", 1, 200))
            {
                error = "Bitrate values are outside the allowed range.";
                return false;
            }
            if (!WithinDouble(root, "batteryTemperatureWarningCelsius", 0, 80) || !WithinDouble(root, "batteryTemperatureCriticalCelsius", 0, 80))
            {
                error = "Battery-temperature thresholds must be between 0 and 80 °C.";
                return false;
            }
            var warningTemperature = root.GetProperty("batteryTemperatureWarningCelsius").GetDouble();
            var criticalTemperature = root.GetProperty("batteryTemperatureCriticalCelsius").GetDouble();
            if (criticalTemperature <= warningTemperature)
            {
                error = "Critical battery temperature must be higher than the warning value.";
                return false;
            }
            if (!WithinLong(root, "minimumActionIntervalMs", 1_000, 3_600_000) ||
                !WithinLong(root, "thresholdDurationMs", 0, 3_600_000) ||
                !WithinLong(root, "cooldownMs", 0, 7_200_000) ||
                !Within(root, "maximumProfileChanges", 1, 20) ||
                !WithinLong(root, "profileChangeWindowMs", 60_000, 86_400_000))
            {
                error = "Hysteresis or profile-change values are outside the allowed range.";
                return false;
            }
            if (!PositiveIntegers(root, "fpsFallbackOrder", 1, 240) || !PositiveIntegers(root, "resolutionFallbackHeights", 240, 8_640))
            {
                error = "Fallback orders must contain supported numeric video values.";
                return false;
            }
            if (!root.TryGetProperty("thermalActions", out var thermalActions) || thermalActions.ValueKind != JsonValueKind.Object)
            {
                error = "Thermal actions are required.";
                return false;
            }
            error = string.Empty;
            return true;
        }
        catch (Exception exception)
        {
            error = $"Invalid protection JSON: {exception.Message}";
            return false;
        }
    }

    private static bool Within(JsonElement root, string name, int minimum, int maximum) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt32(out var number) && number >= minimum && number <= maximum;

    private static bool WithinLong(JsonElement root, string name, long minimum, long maximum) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt64(out var number) && number >= minimum && number <= maximum;

    private static bool WithinDouble(JsonElement root, string name, double minimum, double maximum) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetDouble(out var number) && number >= minimum && number <= maximum;

    private static bool PositiveIntegers(JsonElement root, string name, int minimum, int maximum) =>
        root.TryGetProperty(name, out var values) && values.ValueKind == JsonValueKind.Array &&
        values.GetArrayLength() > 0 && values.EnumerateArray().All(value =>
            value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number) && number >= minimum && number <= maximum);
}

internal sealed record ProtectionConfigurationAck(bool Accepted, ProtectionConfiguration? Configuration, string? Error)
{
    public static ProtectionConfigurationAck FromJson(JsonElement root) => new(
        root.TryGetProperty("accepted", out var accepted) && accepted.ValueKind == JsonValueKind.True,
        root.TryGetProperty("config", out var config) && config.ValueKind == JsonValueKind.Object
            ? new ProtectionConfiguration(root.TryGetProperty("schemaVersion", out var schema) ? schema.GetInt32() : 1, config.GetRawText())
            : null,
        root.TryGetProperty("error", out var error) ? error.GetString() : null);
}
