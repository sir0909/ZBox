param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $project 'app\build\tmp\kotlin-classes\release'
if (!(Test-Path $classes)) { $classes = Join-Path $project 'app\build\tmp\kotlin-classes\debug' }
if (!(Test-Path $classes)) { throw '请先用项目工具链编译 :app:assembleRelease 或 :app:assembleDebug；脚本不会自动下载工具。' }
if (!$JavaHome -or !$GradleUserHome) { throw '请设置 JAVA_HOME 和 GRADLE_USER_HOME。' }
$stdlibRoot = Join-Path $GradleUserHome 'caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib'
$stdlib = Get-ChildItem $stdlibRoot -Filter 'kotlin-stdlib-*.jar' -Recurse -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$stdlib) { throw '本地没有 Kotlin 标准库缓存；请先确认已有构建环境，不自动联网下载。' }
$out = Join-Path $project 'app\build\native-burst-check'
New-Item -ItemType Directory -Force $out | Out-Null
$classpath = $classes + ';' + $stdlib.FullName
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $out (Join-Path $PSScriptRoot 'tests\NativeBurstProtocolCheck.java')
if ($LASTEXITCODE -ne 0) { throw '协议检查编译失败' }
& (Join-Path $JavaHome 'bin\java.exe') -cp ($out + ';' + $classpath) NativeBurstProtocolCheck
if ($LASTEXITCODE -ne 0) { throw '协议/请求状态检查失败' }
