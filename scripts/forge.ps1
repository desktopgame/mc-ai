# Forward all arguments to the pinned Gradle wrapper.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$jdkDir = Join-Path $repoRoot '.tools\jdk8u504-b01'
if (-not (Test-Path -LiteralPath (Join-Path $jdkDir 'bin\javac.exe'))) {
    throw 'Run scripts/setup-jdk.ps1 first.'
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousGradleHome = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $jdkDir
    $env:PATH = "$jdkDir\bin;$env:PATH"
    $env:GRADLE_USER_HOME = Join-Path $repoRoot '.tools\gradle-home'
    & (Join-Path $repoRoot 'forge-mod\gradlew.bat') -p (Join-Path $repoRoot 'forge-mod') --no-daemon --console plain @args
    $resultCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:GRADLE_USER_HOME = $previousGradleHome
}
exit $resultCode
