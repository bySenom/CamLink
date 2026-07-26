[CmdletBinding()]
param(
    [int]$HubPort = 6020
)

$ErrorActionPreference = 'Stop'

$sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and (Test-Path -LiteralPath $sdkAdb)) {
    $adb = $sdkAdb
}
if (-not $adb) {
    throw 'adb was not found. Install Android SDK Platform-Tools or set ANDROID_HOME.'
}

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
if ($devices.Count -eq 0) {
    throw 'No authorized Android device found. Enable USB debugging on the phone and accept the RSA prompt.'
}
if ($devices.Count -gt 1) {
    throw 'More than one authorized device found. Disconnect the other devices, then run this helper again.'
}

& $adb reverse "tcp:$HubPort" "tcp:$HubPort"
if ($LASTEXITCODE -ne 0) {
    throw "adb reverse failed for port $HubPort."
}

Write-Host "CamLink USB bridge is active on TCP $HubPort. In the phone app choose Smart or USB and connect."
