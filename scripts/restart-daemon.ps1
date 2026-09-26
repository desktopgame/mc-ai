# Stop any running Agent Daemon (matched by command line, never by a hardcoded PID) and start it fresh
# with the given config. Safe to re-run: matching is scoped to this repo's daemon.py so it cannot
# touch an unrelated process.
param(
    [int]$Port = 8767,
    [string]$Config = 'agent/config.local.json',
    [string]$DecisionConfig = 'agent/decision.local.json'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$daemonScript = Join-Path $repoRoot 'agent\src\daemon.py'
$logDir = Join-Path $repoRoot '.tools'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Find-Daemons {
    Get-CimInstance Win32_Process -Filter "Name='python.exe' or Name='pythonw.exe'" |
        Where-Object { $_.CommandLine -and ($_.CommandLine.Contains('agent\src\daemon.py') -or $_.CommandLine.Contains('agent/src/daemon.py')) }
}

foreach ($proc in Find-Daemons) {
    Write-Output "Stopping existing daemon: PID $($proc.ProcessId)"
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
}
# Python's HTTPServer sets SO_REUSEADDR, so a survivor would silently share the port with the new
# process and requests would land on either one. Never start while one is still alive.
for ($i = 0; $i -lt 10 -and (Find-Daemons); $i++) { Start-Sleep -Milliseconds 500 }
$survivors = Find-Daemons
if ($survivors) { throw "Could not stop daemon PID $(($survivors.ProcessId) -join ', '). Stop it manually before retrying." }

$stdout = Join-Path $logDir 'daemon.stdout.log'
$stderr = Join-Path $logDir 'daemon.stderr.log'
$pidFile = Join-Path $logDir 'daemon.pid'
$proc = Start-Process -FilePath 'python' `
    -ArgumentList $daemonScript, '--port', $Port, '--config', $Config, '--decision-config', $DecisionConfig `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
    -PassThru -WindowStyle Hidden
Set-Content -Path $pidFile -Value $proc.Id -NoNewline

$listening = $false
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Milliseconds 500
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { $listening = $true; break }
}
if (-not $listening) { throw "Daemon did not start listening on port $Port. Check $stderr." }
$running = @(Find-Daemons)
if ($running.Count -ne 1) {
    throw "Expected exactly one daemon, found $($running.Count) (PID $(($running.ProcessId) -join ', ')). Stop them all and retry."
}
Write-Output "Daemon listening on 127.0.0.1:$Port (PID $($proc.Id)). Logs: $stdout / $stderr"
