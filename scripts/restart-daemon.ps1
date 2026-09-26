# Stop any running Agent Daemon and start it fresh with the given config.
# Primary stop is a loopback HTTP request to POST /local/shutdown, which works without elevation
# even when the running daemon was started elevated. Process termination is only a fallback.
# The daemon is created via WMI so it is NOT a child of this shell: the invoking tool returns
# immediately instead of waiting for the long-lived daemon.
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
# Fast listener probe (~ms) instead of Get-NetTCPConnection (~0.6s per call).
function Test-Listening {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $Port); return $true }
    catch { return $false }
    finally { $client.Dispose() }
}

# 1) Ask the running daemon to exit. HTTP over loopback crosses the elevation boundary, so this
#    succeeds even when the daemon was started from an elevated shell.
$tokenFile = Join-Path $logDir 'daemon-shutdown.token'
$token = if (Test-Path $tokenFile) { (Get-Content $tokenFile -Raw).Trim() } else { $null }
if (Test-Listening) {
    try {
        $headers = @{}
        if ($token) { $headers['X-MCAI-Token'] = $token }
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/local/shutdown" -Headers $headers -TimeoutSec 2 | Out-Null
        Write-Output "Requested graceful shutdown on port $Port"
    } catch {
        Write-Output "Graceful shutdown unavailable; falling back to process stop"
    }
    for ($i = 0; $i -lt 40 -and (Test-Listening); $i++) { Start-Sleep -Milliseconds 100 }
}

# 2) Fallback: terminate survivors directly. Only succeeds when this shell may signal them.
foreach ($proc in Find-Daemons) {
    Write-Output "Stopping existing daemon: PID $($proc.ProcessId)"
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
}
for ($i = 0; $i -lt 30 -and (Test-Listening); $i++) { Start-Sleep -Milliseconds 100 }
if (Test-Listening) {
    throw ("Could not free port $Port. If the daemon was started elevated before /local/shutdown existed, " +
        "stop it once from an admin shell or Task Manager.")
}

# 3) Start fresh, fully detached via WMI (parent is WmiPrvSE, not this shell).
$elevated = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($elevated) { Write-Output "Warning: this shell is elevated; the new daemon will also be elevated. Prefer a normal PowerShell next time." }

$newToken = [guid]::NewGuid().ToString('N')
Set-Content -Path $tokenFile -Value $newToken -NoNewline -Encoding ascii
$stdout = Join-Path $logDir 'daemon.stdout.log'
$stderr = Join-Path $logDir 'daemon.stderr.log'
$pidFile = Join-Path $logDir 'daemon.pid'
$inner = 'python "' + $daemonScript + '" --port ' + $Port + ' --config "' + $Config + '" --decision-config "' +
    $DecisionConfig + '" --shutdown-token ' + $newToken
$commandLine = 'cmd.exe /c ' + $inner + ' > "' + $stdout + '" 2> "' + $stderr + '"'
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $commandLine; CurrentDirectory = $repoRoot }
if ($created.ReturnValue -ne 0) { throw "Win32_Process.Create failed: $($created.ReturnValue)" }

$listening = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 200
    if (Test-Listening) { $listening = $true; break }
}
if (-not $listening) { throw "Daemon did not start listening on port $Port. Check $stderr." }
$running = @(Find-Daemons)
if ($running.Count -ne 1) {
    throw "Expected exactly one daemon, found $($running.Count) (PID $(($running.ProcessId) -join ', ')). Stop them all and retry."
}
Set-Content -Path $pidFile -Value $running[0].ProcessId -NoNewline
Write-Output "Daemon listening on 127.0.0.1:$Port (PID $($running[0].ProcessId)). Logs: $stdout / $stderr"
