param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop';$p=Split-Path -Parent $PSScriptRoot
if(!$JavaHome -or !(Test-Path "$JavaHome\bin\javac.exe")){throw 'Set JAVA_HOME to the existing JDK; this script never downloads tools'}
$classes="$p\app\build\intermediates\javac\release\compileReleaseJavaWithJavac\classes"
if(!(Test-Path "$classes\com\zbox\ai\core\PhoneToolsPolicy.class")){$classes="$p\app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes"}
if(!(Test-Path "$classes\com\zbox\ai\core\PhoneToolsPolicy.class")){throw 'Build :app:assembleRelease or :app:assembleDebug first'}
$out="$p\app\build\phone-policy-check";New-Item -ItemType Directory -Force $out|Out-Null
& "$JavaHome\bin\javac.exe" -encoding UTF-8 -cp $classes -d $out "$p\tools\tests\PhoneToolsPolicyCheck.java";if($LASTEXITCODE -ne 0){throw 'Phone policy test compilation failed'}
& "$JavaHome\bin\java.exe" -cp "$classes;$out" PhoneToolsPolicyCheck;if($LASTEXITCODE -ne 0){throw 'Phone policy checks failed'}
