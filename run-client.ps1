$ErrorActionPreference = 'Stop'
$envFile = Join-Path $PSScriptRoot '.env'
if (!(Test-Path $envFile)) { throw 'Create .env from .env.example first.' }
Get-Content $envFile | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value.Trim('"', "'"), 'Process')
}
foreach ($name in 'BRIDGE_URL', 'RELAY_TOKEN', 'BEARER_TOKEN', 'BROWSER_EXECUTABLE_PATH') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "$name is required in .env." }
}
if (!(Test-Path $env:BROWSER_EXECUTABLE_PATH -PathType Leaf)) { throw "Browser not found: $env:BROWSER_EXECUTABLE_PATH" }
$jar = Join-Path $PSScriptRoot 'target\open-ai-api-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar -PathType Leaf)) { throw 'Build the application with .\mvnw.cmd clean package first.' }
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
& java -jar $jar
exit $LASTEXITCODE
