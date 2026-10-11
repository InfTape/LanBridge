param([int]$Port = 46999, [string]$QrImage)
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
try {
    $ready = $fixtureProcess.StandardOutput.ReadLine()
    if (!$ready.StartsWith('READY ')) { throw 'Windows 测试服务启动失败' }
    $env:LANBRIDGE_TEST_PORT = "$Port"
    $env:LANBRIDGE_TEST_CODE = $testCode
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
    if (!$fixtureProcess.HasExited) { $fixtureProcess.StandardInput.WriteLine('stop'); $fixtureProcess.WaitForExit(15000) | Out-Null }
    $fixtureProcess.Dispose()
}
