$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:18080"
$outDir = $PSScriptRoot

# ~1.2MB of conversation context (many system/user chunks)
$chunk = ("Lorem ipsum dolor sit amet, context block for huge prompts. " * 80)
$messages = New-Object System.Collections.Generic.List[object]
$messages.Add(@{ role = "system"; content = "You are a concise assistant. Ignore bulk context except the final user question." })
1..120 | ForEach-Object {
  $messages.Add(@{ role = "user"; content = "Context chunk $_ : $chunk" })
  $messages.Add(@{ role = "assistant"; content = "Ack $_" })
}
$messages.Add(@{ role = "user"; content = "Reply with exactly: huge-ok" })

$bodyObj = @{
  model = "auto"
  stream = $false
  messages = $messages
}
$path = Join-Path $outDir "req-huge-context.json"
$bodyObj | ConvertTo-Json -Depth 6 -Compress | Set-Content -Path $path -Encoding UTF8
$size = (Get-Item $path).Length
Write-Host "Payload bytes: $size"

if ($size -lt 300000) {
  throw "Expected large payload (>300KB), got $size bytes"
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$resp = curl.exe -s -m 600 -w "`nHTTP_CODE:%{http_code}" -X POST "$base/v1/chat/completions" `
  -H "Content-Type: application/json" `
  -d "@$path"
$sw.Stop()

$codeLine = ($resp -split "`n") | Where-Object { $_ -like "HTTP_CODE:*" } | Select-Object -Last 1
$json = ($resp -split "`nHTTP_CODE:")[0]
$code = [int]($codeLine -replace "HTTP_CODE:","")

Write-Host "Elapsed ms: $($sw.ElapsedMilliseconds)"
Write-Host "HTTP: $code"
Write-Host "Response head: $($json.Substring(0, [Math]::Min(300, $json.Length)))"

if ($code -ne 200) { throw "Expected HTTP 200, got $code : $json" }
$parsed = $json | ConvertFrom-Json
$content = $parsed.choices[0].message.content
if (-not $content) { throw "Empty content" }
if ($content -notmatch "huge-ok") {
  Write-Host "WARN: content did not contain huge-ok: $content"
}
Write-Host "PASS huge-context e2e"
