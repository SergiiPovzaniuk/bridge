$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:18080"
$script:results = @()

function Test-Case($name, $script) {
  try {
    & $script
    $script:results += [pscustomobject]@{ Test = $name; Status = "PASS" }
    Write-Host "PASS $name" -ForegroundColor Green
  } catch {
    $script:results += [pscustomobject]@{ Test = $name; Status = "FAIL"; Error = $_.Exception.Message }
    Write-Host "FAIL $name : $($_.Exception.Message)" -ForegroundColor Red
  }
}

Test-Case "health" {
  $h = Invoke-RestMethod "$base/health"
  if ($h.status -ne "ok") { throw "health not ok" }
}

Test-Case "models" {
  $m = Invoke-RestMethod "$base/v1/models"
  if ($m.data.Count -lt 1) { throw "no models" }
}

Test-Case "small_chat" {
  $body = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "Reply with exactly: pong" })
  } | ConvertTo-Json -Depth 5
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body -TimeoutSec 300
  $msg = $r.choices[0].message
  if (-not $msg.content -and -not $msg.tool_calls) { throw "empty message" }
}

Test-Case "large_context_1mb" {
  $chunk = "Lorem ipsum dolor sit amet, context block for huge prompts. "
  $filler = ($chunk * 17000)
  $payload = @{
    model = "auto"
    stream = $false
    messages = @(
      @{ role = "system"; content = "Ignore filler below. Answer the last user message only." }
      @{ role = "user"; content = $filler }
      @{ role = "user"; content = "Reply with exactly: ok" }
    )
  }
  $json = $payload | ConvertTo-Json -Depth 5 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetByteCount($json)
  Write-Host "  payload bytes: $bytes"
  if ($bytes -lt 1000000) { throw "payload too small: $bytes" }
  $tmp = Join-Path $env:TEMP "e2e-large-context.json"
  $outFile = Join-Path $env:TEMP "e2e-large-context-out.json"
  [System.IO.File]::WriteAllText($tmp, $json)
  $code = curl.exe -s -S -m 600 -o $outFile -w "%{http_code}" -X POST "$base/v1/chat/completions" `
    -H "Content-Type: application/json" --data-binary "@$tmp"
  if ($LASTEXITCODE -ne 0) { throw "curl failed exit=$LASTEXITCODE" }
  $bodyText = [System.IO.File]::ReadAllText($outFile)
  if ($code -eq "413") { throw "413 payload too large" }
  if ($code -ne "200") { throw "HTTP $code : $($bodyText.Substring(0, [Math]::Min(400, $bodyText.Length)))" }
  $r = $bodyText | ConvertFrom-Json
  if (-not $r.choices -or $r.choices.Count -lt 1) { throw "no choices: $($bodyText.Substring(0, [Math]::Min(300, $bodyText.Length)))" }
  $msg = $r.choices[0].message
  if (-not $msg.content -and -not $msg.tool_calls) { throw "empty message for large context" }
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
$script:results | Format-Table -AutoSize
$failed = @($script:results | Where-Object { $_.Status -eq "FAIL" }).Count
if ($failed -gt 0) { exit 1 }
