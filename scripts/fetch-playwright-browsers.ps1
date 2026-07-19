# Offline prep only: install Chromium into src/main/resources/ms-playwright.
# open_ai_api never downloads browsers at runtime (PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$browsersPath = Join-Path $root "src\main\resources\ms-playwright"
$pom = Join-Path $root "pom.xml"
$versionLine = Select-String -Path $pom -Pattern "<playwright.version>([^<]+)</playwright.version>" | Select-Object -First 1
if (-not $versionLine) { throw "playwright.version not found in pom.xml" }
$playwrightVersion = $versionLine.Matches[0].Groups[1].Value
Write-Host "Installing chromium for Playwright Java $playwrightVersion"

New-Item -ItemType Directory -Force -Path $browsersPath | Out-Null
$env:PLAYWRIGHT_BROWSERS_PATH = $browsersPath
Push-Location $root
try {
    & .\mvnw.cmd -q exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Playwright chromium installed to $browsersPath"
    Write-Host "Keep playwright.version=$playwrightVersion in pom.xml in sync with this folder."
}
finally {
    Pop-Location
}
