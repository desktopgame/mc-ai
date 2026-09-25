$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$toolsDir = Join-Path $repoRoot '.tools'
$jdkDir = Join-Path $toolsDir 'jdk8u504-b01'
$archive = Join-Path $toolsDir 'OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip'
$expectedHash = 'ea43d46ede95b51e44a12c66711706cddc762e0a766c54bccea18954e902b2aa'

New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
    throw "JDK archive checksum mismatch: $archive"
}
if (-not (Test-Path -LiteralPath (Join-Path $jdkDir 'bin\javac.exe'))) {
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsDir
}
& (Join-Path $jdkDir 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'Java verification failed' }
& (Join-Path $jdkDir 'bin\javac.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'Javac verification failed' }
