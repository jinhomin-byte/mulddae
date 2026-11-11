# Claude Code PostToolUse hook: Bash 명령과 결과를 .claude\logs\bash.log 에 기록
$ErrorActionPreference = 'SilentlyContinue'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

$raw  = [Console]::In.ReadToEnd()
$data = $raw | ConvertFrom-Json

$root = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else { (Get-Location).Path }
$logDir = Join-Path $root '.claude\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'bash.log'

$time = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$cmd  = $data.tool_input.command
$desc = $data.tool_input.description
$out  = [string]$data.tool_response.stdout
$err  = [string]$data.tool_response.stderr

function Cut($s, $n) { if ($s.Length -gt $n) { $s.Substring(0, $n) + "`n...(생략)" } else { $s } }

$entry = @"
==================== $time ====================
[설명] $desc
[명령] $cmd
[출력]
$(Cut $out 2000)
"@
if ($err) { $entry += "`n[에러]`n$(Cut $err 2000)" }

Add-Content -Path $log -Value $entry -Encoding UTF8
exit 0
