using System.Diagnostics;

namespace CamLink.Desktop;

internal static class AdbUsbBridge
{
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromSeconds(5);

    public static async Task<AdbBridgeResult> ConfigureReverseAsync(int port)
    {
        var adbPath = FindAdb();
        if (adbPath is null)
        {
            return new AdbBridgeResult(false, "ADB was not found; USB needs Android platform-tools.");
        }

        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = adbPath,
                Arguments = $"reverse tcp:{port} tcp:{port}",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            });

            if (process is null)
            {
                return new AdbBridgeResult(false, "ADB could not be started.");
            }

            var outputTask = process.StandardOutput.ReadToEndAsync();
            var errorTask = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(CommandTimeout);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
                return new AdbBridgeResult(false, "ADB did not respond within five seconds.");
            }

            var diagnostic = (await errorTask).Trim();
            if (string.IsNullOrWhiteSpace(diagnostic)) diagnostic = (await outputTask).Trim();
            return process.ExitCode == 0
                ? new AdbBridgeResult(true, "USB forwarding ready.")
                : new AdbBridgeResult(false, string.IsNullOrWhiteSpace(diagnostic) ? "ADB reverse failed." : diagnostic);
        }
        catch (Exception exception)
        {
            return new AdbBridgeResult(false, $"ADB setup failed: {exception.Message}");
        }
    }

    private static string? FindAdb()
    {
        var sdkRoots = new[]
        {
            Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT"),
            Environment.GetEnvironmentVariable("ANDROID_HOME"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk")
        };

        return sdkRoots
            .Where(root => !string.IsNullOrWhiteSpace(root))
            .Select(root => Path.Combine(root!, "platform-tools", "adb.exe"))
            .FirstOrDefault(File.Exists);
    }
}

internal sealed record AdbBridgeResult(bool Success, string Message);
