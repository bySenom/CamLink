# CamLink updates

CamLink checks the public GitHub release feed at startup and only accepts a release that provides the matching asset and a SHA-256 sidecar file.

| Component | Release asset | Checksum asset |
| --- | --- | --- |
| Windows Hub | `CamLinkHub-win-x64.zip` | `CamLinkHub-win-x64.zip.sha256` |
| Android app | `CamLinkCamera.apk` | `CamLinkCamera.apk.sha256` |

The Hub asks before downloading, verifies the archive, then restarts into the update. The phone checks automatically at launch and has a manual **Check for updates** button. Android downloads the APK and invokes the system installer; Android requires the user to confirm the installation.

The current Hub archive is framework-dependent and requires the .NET 10 Windows Desktop Runtime, which is already present on the development PC.

## Publishing a release

1. Increase the Hub version in `desktop/CamLink.Desktop.csproj` and both `versionCode` and `versionName` in `android/app/build.gradle.kts`.
2. Run `powershell -ExecutionPolicy Bypass -File scripts/package-release.ps1 -Version <version>`.
3. Create a non-prerelease GitHub release with tag `v<version>` in `bySenom/CamLink`.
4. Upload all four generated files from `artifacts/release/v<version>/`.

The app installed during development is debug-signed. Its updates must use the same signing certificate, which is why the packaging script is run on this Windows machine. Before publishing CamLink to other people, replace this with a protected release keystore and sign every Android release with that same key. Never commit a keystore or its password to the repository.
