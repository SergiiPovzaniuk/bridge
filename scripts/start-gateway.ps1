$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $i = $line.IndexOf("=")
    if ($i -lt 1) { return }
    $name = $line.Substring(0, $i).Trim()
    $value = $line.Substring($i + 1).Trim()
    Set-Item -Path "Env:$name" -Value $value
  }
}

if (-not $env:BRIDGE_URL) { $env:BRIDGE_URL = "http://127.0.0.1:8787/" }

Write-Host "BRIDGE_URL=$env:BRIDGE_URL"
Write-Host "RELAY_TOKEN set=$([bool]$env:RELAY_TOKEN)"
Write-Host "BEARER_TOKEN set=$([bool]$env:BEARER_TOKEN)"
Write-Host "PLAYWRIGHT_BROWSERS_PATH=$env:PLAYWRIGHT_BROWSERS_PATH"

& .\mvnw.cmd spring-boot:run
