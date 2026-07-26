[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$javaHome = 'C:\Program Files\Android\Android Studio\jbr'

if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-35'))) {
    throw "Android SDK Platform 35 was not found at '$sdkRoot'. Install it with Android Studio's SDK Manager."
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Android Studio's JBR was not found at '$javaHome'. Install Android Studio first."
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
& (Join-Path $projectRoot 'android\gradlew.bat') -p (Join-Path $projectRoot 'android') ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) {
    throw "Android build failed with exit code $LASTEXITCODE."
}

Write-Host "APK: $(Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk')"

