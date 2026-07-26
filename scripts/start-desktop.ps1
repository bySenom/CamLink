[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& dotnet run --project (Join-Path $projectRoot 'desktop\CamLink.Desktop.csproj')
if ($LASTEXITCODE -ne 0) {
    throw "CamLink Desktop exited with code $LASTEXITCODE."
}

