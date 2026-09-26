# Show the tail of the Agent Daemon logs. Read-only.
param(
    [int]$Lines = 20
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
foreach ($name in @('daemon.stderr.log', 'daemon.stdout.log')) {
    $path = Join-Path $repoRoot ('.tools\' + $name)
    Write-Output "== $name =="
    if (Test-Path $path) { Get-Content $path -Tail $Lines } else { Write-Output '(none)' }
}
