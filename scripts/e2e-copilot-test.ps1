$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:18080"
$results = @()

function Test-Case($name, $script) {
  try {
    & $script
    $results += [pscustomobject]@{ Test = $name; Status = "PASS" }
    Write-Host "PASS $name" -ForegroundColor Green
  } catch {
    $results += [pscustomobject]@{ Test = $name; Status = "FAIL"; Error = $_.Exception.Message }
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

Test-Case "ask_plain" {
  $body = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "Reply with exactly: pong" })
  } | ConvertTo-Json -Depth 5
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body
  if (-not $r.choices[0].message.content) { throw "empty content" }
  if (-not $r.cursor.agentId) { throw "missing cursor.agentId" }
}

Test-Case "ask_stream" {
  $out = curl.exe -s -m 120 -X POST "$base/v1/chat/completions" -H "Content-Type: application/json" -d "@$PSScriptRoot/req-stream.json" | Out-String
  if ($out -notmatch "data: ") { throw "not sse: $out" }
  if ($out -notmatch "\[DONE\]") { throw "no DONE" }
}

Test-Case "agent_with_tools" {
  $body = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "What is 2+2? Reply with just the number." })
    tools = @(@{
      type = "function"
      function = @{
        name = "read_file"
        description = "Read a file"
        parameters = @{
          type = "object"
          properties = @{ filePath = @{ type = "string" } }
          required = @("filePath")
        }
      }
    })
    tool_choice = "auto"
  } | ConvertTo-Json -Depth 10
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body -TimeoutSec 300
  if (-not $r.choices[0].message.content) { throw "empty content" }
}

Test-Case "plan_mode" {
  $body = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "Plan how to add a hello world function. Be brief." })
    cursor = @{ mode = "plan" }
  } | ConvertTo-Json -Depth 5
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body -TimeoutSec 300
  if (-not $r.choices[0].message.content) { throw "empty content" }
}

Test-Case "ask_mode_explicit" {
  $body = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "What is TypeScript? One sentence." })
    cursor = @{ mode = "ask" }
  } | ConvertTo-Json -Depth 5
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body -TimeoutSec 300
  if (-not $r.choices[0].message.content) { throw "empty content" }
}

Test-Case "copilot_like_agent" {
  $system = "You are GitHub Copilot. Keep answers short."
  $body = @{
    model = "auto"
    stream = $false
    messages = @(
      @{ role = "system"; content = $system }
      @{ role = "user"; content = "Say hello in one word." }
    )
    tools = @(@{
      type = "function"
      function = @{
        name = "read_file"
        parameters = @{ type = "object"; properties = @{ filePath = @{ type = "string" } }; required = @("filePath") }
      }
    })
  } | ConvertTo-Json -Depth 10
  $r = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body -TimeoutSec 300
  if (-not $r.choices[0].message.content) { throw "empty content" }
}

Test-Case "session_resume" {
  $body1 = @{
    model = "auto"
    messages = @(@{ role = "user"; content = "Remember the codeword: zebra-42. Reply with exactly: ack" })
  } | ConvertTo-Json -Depth 5
  $r1 = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body1 -TimeoutSec 300
  $agentId = $r1.cursor.agentId
  if (-not $agentId) { throw "missing agentId" }

  $body2 = @{
    model = "auto"
    messages = @(
      @{ role = "user"; content = "Remember the codeword: zebra-42. Reply with exactly: ack" }
      @{ role = "assistant"; content = $r1.choices[0].message.content }
      @{ role = "user"; content = "What was the codeword? Reply with only the codeword." }
    )
  } | ConvertTo-Json -Depth 5
  $r2 = Invoke-RestMethod "$base/v1/chat/completions" -Method POST -ContentType "application/json" -Body $body2 -TimeoutSec 300
  if (-not $r2.choices[0].message.content) { throw "empty follow-up" }
  if (-not $r2.cursor.agentId) { throw "missing follow-up agentId" }
}

Test-Case "session_reset" {
  $r = Invoke-RestMethod "$base/v1/cursor/sessions/reset" -Method POST
  if ($r.status -ne "ok") { throw "reset not ok" }
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize
$failed = ($results | Where-Object Status -eq "FAIL").Count
if ($failed -gt 0) { exit 1 }
