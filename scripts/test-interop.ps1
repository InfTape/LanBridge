param([ValidateRange(1024,65533)][int]$Port = 46999, [string]$QrImage)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$fixtureExecutable = Join-Path $projectRoot 'windows\build\Release\BridgeIntegration.exe'
if (!(Test-Path -LiteralPath $fixtureExecutable)) { throw '先运行 build-windows.ps1。' }
$testCode = [Guid]::NewGuid().ToString('N').Substring(0,16).ToUpperInvariant()
$start = [System.Diagnostics.ProcessStartInfo]::new()
$start.FileName = $fixtureExecutable
$start.Arguments = "--fixture $Port $testCode"
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$fixtureProcess = [System.Diagnostics.Process]::Start($start)
$previousPort = $env:LANBRIDGE_TEST_PORT
$previousCode = $env:LANBRIDGE_TEST_CODE
$previousQr = $env:LANBRIDGE_QR_IMAGE
$previousDiscovery = $env:LANBRIDGE_DISCOVERY_TEST_PORT
$previousFolder = $env:LANBRIDGE_FOLDER_TEST_PORT
$extraFixtures = @()
try {
    $ready = $fixtureProcess.StandardOutput.ReadLine()
    if (!$ready.StartsWith('READY ')) { throw 'Windows 测试服务启动失败' }
    $env:LANBRIDGE_TEST_PORT = "$Port"
    $env:LANBRIDGE_TEST_CODE = $testCode
    foreach ($spec in @(@('PairingIntegration.exe',($Port+1),'LANBRIDGE_DISCOVERY_TEST_PORT'),@('FolderIntegration.exe',($Port+2),'LANBRIDGE_FOLDER_TEST_PORT'))) {
        $extraStart = [System.Diagnostics.ProcessStartInfo]::new()
        $extraStart.FileName = Join-Path $projectRoot ("windows\build\Release\"+$spec[0])
        $extraStart.Arguments = "--fixture $($spec[1]) $testCode"
        $extraStart.UseShellExecute = $false
        $extraStart.CreateNoWindow = $true
        $extraStart.RedirectStandardInput = $true
        $extraStart.RedirectStandardOutput = $true
        $extra = [System.Diagnostics.Process]::Start($extraStart)
        $extraFixtures += $extra
        $extraReady = $extra.StandardOutput.ReadLine()
        if (!$extraReady -or !$extraReady.StartsWith('READY')) { throw "测试服务启动失败：$($spec[0])" }
        [Environment]::SetEnvironmentVariable($spec[2],"$($spec[1])",'Process')
    }
    if ($QrImage) {
        $QrImage = [System.IO.Path]::GetFullPath($QrImage)
        & (Join-Path $projectRoot 'windows\build\Release\UiIntegration.exe') --qr (Join-Path $projectRoot 'LanBridge.exe') $QrImage
        if ($LASTEXITCODE) { throw 'Windows 二维码渲染失败' }
        $env:LANBRIDGE_QR_IMAGE = $QrImage
    }
    Push-Location (Join-Path $projectRoot 'android')
    try {
        & '.\gradlew.bat' testDebugUnitTest --rerun-tasks --console=plain
        if ($LASTEXITCODE) { throw 'Kotlin / Win32 协议互通测试失败' }
    } finally { Pop-Location }
} finally {
    $env:LANBRIDGE_TEST_PORT = $previousPort
    $env:LANBRIDGE_TEST_CODE = $previousCode
    $env:LANBRIDGE_QR_IMAGE = $previousQr
    $env:LANBRIDGE_DISCOVERY_TEST_PORT = $previousDiscovery
    $env:LANBRIDGE_FOLDER_TEST_PORT = $previousFolder
    foreach ($extra in $extraFixtures) {
        if (!$extra.HasExited) { $extra.StandardInput.WriteLine('stop'); $extra.WaitForExit(15000) | Out-Null }
        $extra.Dispose()
    }
    if (!$fixtureProcess.HasExited) { $fixtureProcess.StandardInput.WriteLine('stop'); $fixtureProcess.WaitForExit(15000) | Out-Null }
    $fixtureProcess.Dispose()
}
