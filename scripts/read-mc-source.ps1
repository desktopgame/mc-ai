# Read-only access to the decompiled Minecraft sources in the pinned Forge sources jar.
# Optionally filter with -Pattern (a regex passed to Select-String).
param(
    [Parameter(Mandatory = $true)][string]$SourceEntry,
    [string]$Pattern
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$cacheRoot = Join-Path $repoRoot '.tools\gradle-home\caches\minecraft'
$jar = Get-ChildItem $cacheRoot -Recurse -Filter 'forgeSrc-*-sources.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw "forgeSrc sources jar not found under $cacheRoot. Run scripts/forge.ps1 setupDecompWorkspace build first." }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $name = $SourceEntry.TrimStart('/')
    $zipEntry = @($zip.Entries | Where-Object { $_.FullName -eq $name })[0]
    if ($null -eq $zipEntry) { throw "Entry not found: $name" }
    $reader = New-Object System.IO.StreamReader($zipEntry.Open(), [System.Text.Encoding]::UTF8)
    try { $text = $reader.ReadToEnd() } finally { $reader.Close() }
} finally {
    $zip.Dispose()
}
if ($Pattern) { $text -split "`n" | Select-String -Pattern $Pattern } else { $text }
