using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Diagnostics;

namespace CamLink.Desktop;

internal sealed record HubUpdate(string Version, Uri ArchiveUri, Uri ChecksumUri);

internal static class UpdateService
{
    private const string ReleasesApi = "https://api.github.com/repos/bySenom/CamLink/releases/latest";
    private const string ArchiveName = "CamLinkHub-win-x64.zip";
    private static readonly HttpClient Client = CreateClient();

    public static async Task<HubUpdate?> CheckForUpdateAsync(CancellationToken cancellationToken = default)
    {
        using var response = await Client.GetAsync(ReleasesApi, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound)
        {
            return null;
        }
        response.EnsureSuccessStatusCode();

        using var document = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(cancellationToken));
        var root = document.RootElement;
        if (root.TryGetProperty("prerelease", out var prerelease) && prerelease.GetBoolean())
        {
            return null;
        }

        var version = root.GetProperty("tag_name").GetString()?.TrimStart('v') ?? string.Empty;
        if (!IsNewerThanCurrent(version))
        {
            return null;
        }

        Uri? archiveUri = null;
        Uri? checksumUri = null;
        foreach (var asset in root.GetProperty("assets").EnumerateArray())
        {
            var name = asset.GetProperty("name").GetString();
            var url = asset.GetProperty("browser_download_url").GetString();
            if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(url))
            {
                continue;
            }
            if (name == ArchiveName) archiveUri = new Uri(url);
            if (name == $"{ArchiveName}.sha256") checksumUri = new Uri(url);
        }

        return archiveUri is not null && checksumUri is not null
            ? new HubUpdate(version, archiveUri, checksumUri)
            : null;
    }

    public static async Task DownloadAndRestartAsync(HubUpdate update, int currentProcessId, CancellationToken cancellationToken = default)
    {
        var updateRoot = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CamLink", "updates", update.Version, Guid.NewGuid().ToString("N"));
        var archivePath = Path.Combine(updateRoot, ArchiveName);
        var stagingDirectory = Path.Combine(updateRoot, "staging");
        Directory.CreateDirectory(updateRoot);

        await DownloadFileAsync(update.ArchiveUri, archivePath, cancellationToken);
        var expectedChecksum = (await Client.GetStringAsync(update.ChecksumUri, cancellationToken))
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)[0];
        var actualChecksum = Convert.ToHexString(await SHA256.HashDataAsync(File.OpenRead(archivePath), cancellationToken));
        if (!actualChecksum.Equals(expectedChecksum, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("The downloaded hub update failed its SHA-256 check.");
        }

        ExtractArchiveSafely(archivePath, stagingDirectory);
        var executableName = Path.GetFileName(Environment.ProcessPath ?? "CamLink.Desktop.exe");
        if (!File.Exists(Path.Combine(stagingDirectory, executableName)))
        {
            throw new InvalidDataException("The update archive does not contain the CamLink Hub executable.");
        }

        var installDirectory = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var updaterScript = Path.Combine(updateRoot, "apply-update.cmd");
        await File.WriteAllTextAsync(updaterScript, $"""
            @echo off
            setlocal
            :wait_for_hub
            tasklist /FI "PID eq {currentProcessId}" /NH | find "{currentProcessId}" >nul
            if not errorlevel 1 (
              timeout /t 1 /nobreak >nul
              goto wait_for_hub
            )
            robocopy "{stagingDirectory}" "{installDirectory}" /E /IS /IT /NFL /NDL /NJH /NJS >nul
            start "" "{Path.Combine(installDirectory, executableName)}"
            del "%~f0"
            """, cancellationToken);

        Process.Start(new ProcessStartInfo
        {
            FileName = updaterScript,
            UseShellExecute = true,
            CreateNoWindow = true
        });
    }

    private static async Task DownloadFileAsync(Uri source, string destination, CancellationToken cancellationToken)
    {
        using var response = await Client.GetAsync(source, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var output = File.Create(destination);
        await input.CopyToAsync(output, cancellationToken);
    }

    private static void ExtractArchiveSafely(string archivePath, string destination)
    {
        Directory.CreateDirectory(destination);
        var destinationRoot = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
        using var archive = ZipFile.OpenRead(archivePath);
        foreach (var entry in archive.Entries)
        {
            var targetPath = Path.GetFullPath(Path.Combine(destination, entry.FullName));
            if (!targetPath.StartsWith(destinationRoot, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("The update archive contains an invalid path.");
            }
            if (string.IsNullOrEmpty(entry.Name))
            {
                Directory.CreateDirectory(targetPath);
                continue;
            }
            Directory.CreateDirectory(Path.GetDirectoryName(targetPath)!);
            entry.ExtractToFile(targetPath, overwrite: true);
        }
    }

    private static bool IsNewerThanCurrent(string version) =>
        Version.TryParse(version, out var available) &&
        available > (typeof(UpdateService).Assembly.GetName().Version ?? new Version(0, 0));

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("CamLink-Hub-Updater/0.1");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }
}
