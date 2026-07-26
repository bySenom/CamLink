[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseRoot = Join-Path $projectRoot ("artifacts\release\v{0}" -f $Version)
if (Test-Path -LiteralPath $releaseRoot) {
    throw "Release output already exists: $releaseRoot"
}

$hubPublish = Join-Path $releaseRoot 'hub'
New-Item -ItemType Directory -Path $hubPublish -Force | Out-Null
dotnet publish (Join-Path $projectRoot 'desktop\CamLink.Desktop.csproj') --configuration Release --no-restore --output $hubPublish ("-p:Version={0}" -f $Version)
if ($LASTEXITCODE -ne 0) {
    throw "Hub publish failed with exit code $LASTEXITCODE."
}

$hubArchive = Join-Path $releaseRoot 'CamLinkHub-win-x64.zip'
Compress-Archive -Path (Join-Path $hubPublish '*') -DestinationPath $hubArchive -CompressionLevel Optimal

& (Join-Path $PSScriptRoot 'build-android.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "Android build failed with exit code $LASTEXITCODE."
}

$apk = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$releaseApk = Join-Path $releaseRoot 'CamLinkCamera.apk'
Copy-Item -LiteralPath $apk -Destination $releaseApk

foreach ($asset in @($hubArchive, $releaseApk)) {
    $hash = (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$asset.sha256" -Value $hash -NoNewline
}

Write-Host "Release assets ready in $releaseRoot"
