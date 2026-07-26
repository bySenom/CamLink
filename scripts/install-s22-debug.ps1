[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at '$adb'. Install Android SDK Platform-Tools first."
}
if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK was not found at '$apk'. Run scripts/build-android.ps1 first."
}

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
if ($devices.Count -ne 1) {
    throw 'Exactly one authorized Android device must be connected. Enable USB debugging and accept its RSA prompt.'
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed with exit code $LASTEXITCODE."
}

Write-Host 'CamLink Camera was installed. Start the Windows companion, then run scripts/camlink-usb.ps1.'

