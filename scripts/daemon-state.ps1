# Read-only observed-state summary over loopback: counts and a sample of item/block candidates.
param(
    [int]$Port = 8767,
    [int]$Sample = 8
)
$ErrorActionPreference = 'Stop'
$state = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/v1/state" `
    -ContentType 'application/json' -Body '{"version":1}' -TimeoutSec 3
Write-Output ("session={0} sequence={1} stale={2}" -f $state.session, $state.sequence, $state.stale)
$s = $state.state
Write-Output ("dimension={0} companion={1}" -f $s.dimension, $(if ($s.companion) { $s.companion.id } else { '(none)' }))
if ($s.companion) { Write-Output ("companion task={0} result={1} pos={2}" -f $s.companion.task, $s.companion.result, ($s.companion.position -join ',')) }
$items = @($s.items.PSObject.Properties)
$blocks = @($s.blocks.PSObject.Properties)
Write-Output ("items={0} blocks={1}" -f $items.Count, $blocks.Count)
$i = 0
foreach ($p in $blocks) {
    if ($i -ge $Sample) { break }
    Write-Output ("  block {0} => {1} d={2}" -f $p.Name, $p.Value.type, $p.Value.distance)
    $i++
}
$i = 0
foreach ($p in $items) {
    if ($i -ge $Sample) { break }
    Write-Output ("  item  {0} => {1} d={2}" -f $p.Name, $p.Value.type, $p.Value.distance)
    $i++
}
