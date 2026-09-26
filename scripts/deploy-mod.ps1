# Deploy a built mod jar into the Prism instance, disabling any other enabled version so only
# one mc-ai-companion jar is ever active. Refuses to run while Minecraft for that instance is open,
# since a locked jar/log and a live mod class both make for a bad time.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Instance = '1.7.10-mod-basic',
    [string]$ModsDir = "$env:APPDATA\PrismLauncher\instances\1.7.10-mod-basic\minecraft\mods"
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$jarName = "mc-ai-companion-$Version.jar"
$jarPath = Join-Path $repoRoot "forge-mod\build\libs\$jarName"
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "$jarPath not found. Run .\scripts\forge.ps1 build first."
}
if (-not (Test-Path -LiteralPath $ModsDir)) {
    throw "Mods folder not found: $ModsDir"
}

$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Instance) }
if ($running) {
    throw "Minecraft ($Instance) appears to be running (PID $($running.ProcessId -join ', ')). Close it first."
}

Get-ChildItem -Path $ModsDir -Filter 'mc-ai-companion-*.jar' | Where-Object { $_.Name -ne $jarName } | ForEach-Object {
    Write-Output "Disabling $($_.Name)"
    Rename-Item -LiteralPath $_.FullName -NewName "$($_.Name).disabled"
}

$destination = Join-Path $ModsDir $jarName
Copy-Item -LiteralPath $jarPath -Destination $destination -Force
$hash = Get-FileHash -LiteralPath $destination -Algorithm SHA256
Write-Output "Deployed $jarName to $ModsDir"
Write-Output "SHA-256: $($hash.Hash)"
