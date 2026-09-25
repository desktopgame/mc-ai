# Store only in the ignored project-local tools directory. Never echo the key.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$secretDir = Join-Path $repoRoot '.tools'
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
$secretValue = Read-Host 'LM Studio API key' -AsSecureString
$secretText = [System.Net.NetworkCredential]::new('', $secretValue).Password
if ([string]::IsNullOrWhiteSpace($secretText)) { throw 'API key is empty' }
try {
    [System.IO.File]::WriteAllText((Join-Path $secretDir 'social-api-key.txt'), $secretText, [System.Text.UTF8Encoding]::new($false))
} finally {
    $secretText = $null
    $secretValue.Dispose()
}
Write-Output 'Saved to .tools/social-api-key.txt (excluded from Git). Restart Agent Daemon to apply.'
