param([Parameter(Mandatory=$true)][string]$AdbPath, [int]$Port = 46998)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$testApk = Join-Path $projectRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (!(Test-Path -LiteralPath $testApk)) { throw '先运行 android/gradlew.bat assembleDebugAndroidTest。' }
$testCode = [Guid]::NewGuid().ToString('N').Substring(0,16).ToUpperInvariant()
$start = [System.Diagnostics.ProcessStartInfo]::new()
$start.FileName = Join-Path $projectRoot 'windows\build\Release\BridgeIntegration.exe'
$start.Arguments = "--fixture $Port $testCode"
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$fixture = [System.Diagnostics.Process]::Start($start)
try {
    if (!$fixture.StandardOutput.ReadLine().StartsWith('READY ')) { throw 'Win32 fixture failed' }
    & $AdbPath reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE) { throw 'ADB reverse failed' }
    & $AdbPath install -r (Join-Path $projectRoot 'LanBridge-Android16-debug.apk')
    if ($LASTEXITCODE) { throw 'App install failed' }
    & $AdbPath install -r -t $testApk
    if ($LASTEXITCODE) { throw 'Test APK install failed' }
    $result = & $AdbPath shell am instrument -w -r -e class dev.lanbridge.SharedFlowDeviceTest -e fixturePort $Port -e fixtureCode $testCode dev.lanbridge.test/androidx.test.runner.AndroidJUnitRunner
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw 'Android share test failed' }
} finally {
    & $AdbPath reverse --remove "tcp:$Port"
    if (!$fixture.HasExited) { $fixture.StandardInput.WriteLine('stop'); $fixture.WaitForExit(15000) | Out-Null }
    $fixture.Dispose()
}
