# Run the Agent Daemon unit tests from the repository root.
# Extra arguments are forwarded (for example: -v).
# stderr is intentionally not treated as a terminating error: unittest logs to stderr.
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    & python -m unittest discover -s agent/tests @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
