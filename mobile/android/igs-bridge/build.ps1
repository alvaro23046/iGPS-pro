$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
$toolRoot='C:\Users\alvar\Documents\DesktopSC\AG\IOS\EXTRACT DATA\.tools'
$env:JAVA_HOME=Join-Path $toolRoot 'jdk'
$env:ANDROID_HOME=Join-Path $toolRoot 'android-sdk'
Push-Location $projectRoot
try {
    & (Join-Path $toolRoot 'gradle\bin\gradle.bat') --no-daemon testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'iGS Bridge build/tests failed' }
} finally { Pop-Location }
