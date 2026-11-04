$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$javacCmd = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\javac.exe'
} else {
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { $cmd.Source } else { throw 'JDK 17 or newer is required; set JAVA_HOME' }
}
$javaCmd = Join-Path (Split-Path -Parent $javacCmd) 'java.exe'
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
$stdlib = Get-ChildItem (Join-Path $gradleHome 'caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib') -Filter '*.jar' -Recurse |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
$classes = Join-Path $root 'app\build\tmp\kotlin-classes\release'
if (-not (Test-Path $classes)) {
    $classes = Join-Path $root 'app\build\tmp\kotlin-classes\debug'
}
if (-not (Test-Path $classes)) { throw 'Build :app:assembleRelease or :app:assembleDebug first' }
$output = Join-Path $root 'tools\out\hotspot-policy-test'
New-Item -ItemType Directory -Force $output | Out-Null
& $javacCmd -encoding UTF-8 -cp "$classes;$stdlib" -d $output (Join-Path $PSScriptRoot 'HotspotConnectionPolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& $javaCmd -cp "$classes;$stdlib;$output" com.zbox.ai.ptpip.HotspotConnectionPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed' }
Write-Host 'PASS HotspotConnectionPolicyTest'
