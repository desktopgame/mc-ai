# Read-only Agent Daemon status: listener owner, process count, protocol line, token presence.
# Never prints the shutdown token value.
param(
    [int]$Port = 8767
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent

$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
$owner = if ($listener) { @($listener)[0].OwningProcess } else { $null }
Write-Output ("listening: {0}" -f [bool]$listener)
Write-Output ("owner PID: {0}" -f ($(if ($owner) { $owner } else { '(none)' })))

$daemons = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -and ($_.CommandLine -like '*agent*daemon.py*') })
Write-Output ("daemon processes: {0}" -f $daemons.Count)
foreach ($d in $daemons) {
    $line = $d.CommandLine -replace '--shutdown-token \S+', '--shutdown-token ***'
    Write-Output ("  PID {0}: {1}" -f $d.ProcessId, $line)
}

$tokenFile = Join-Path $repoRoot '.tools\daemon-shutdown.token'
Write-Output ("shutdown token: {0}" -f $(if (Test-Path $tokenFile) { 'present' } else { '(none)' }))

$log = Join-Path $repoRoot '.tools\daemon.stderr.log'
if (Test-Path $log) {
    Write-Output "last log:"
    Get-Content $log -Tail 3 | ForEach-Object { Write-Output ("  " + $_) }
}
