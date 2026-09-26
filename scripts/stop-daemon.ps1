# Gracefully stop the local Agent Daemon via loopback POST /local/shutdown.
# Works without elevation (the daemon exits voluntarily). Fails clearly if the running
# daemon predates /local/shutdown or the token does not match.
param(
    [int]$Port = 8767
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$tokenFile = Join-Path $repoRoot '.tools\daemon-shutdown.token'

function Test-Listening {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $Port); return $true }
    catch { return $false }
    finally { $client.Dispose() }
}

if (-not (Test-Listening)) { Write-Output "Daemon is not listening on port $Port."; exit 0 }

$headers = @{}
if (Test-Path $tokenFile) { $headers['X-MCAI-Token'] = (Get-Content $tokenFile -Raw).Trim() }
try {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/local/shutdown" -Headers $headers -TimeoutSec 3 | Out-Null
    Write-Output "Requested graceful shutdown on port $Port."
} catch {
    Write-Output "Graceful shutdown rejected or unavailable ($($_.Exception.Message))."
    Write-Output "If this daemon predates /local/shutdown, stop it once from an admin shell or Task Manager."
    exit 1
}
for ($i = 0; $i -lt 40 -and (Test-Listening); $i++) { Start-Sleep -Milliseconds 100 }
if (Test-Listening) { Write-Output "Port $Port is still listening."; exit 1 }
Write-Output "Daemon stopped."
