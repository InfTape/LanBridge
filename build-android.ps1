param([string]$SdkPath = '', [switch]$Test)
$ErrorActionPreference = 'Stop'
if ($SdkPath) {
    $resolvedSdk = (Resolve-Path -LiteralPath $SdkPath).Path.Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'android\local.properties') -Value "sdk.dir=$resolvedSdk" -Encoding utf8
}
Push-Location (Join-Path $PSScriptRoot 'android')
try {
    $tasks = @('assembleDebug', 'lintDebug')
    if ($Test) { $tasks += 'testDebugUnitTest' }
    & '.\gradlew.bat' @tasks --console=plain
    if ($LASTEXITCODE) { throw 'Android 构建或检查失败' }
    Copy-Item -LiteralPath '.\app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $PSScriptRoot 'LanBridge-Android16-debug.apk')
} finally { Pop-Location }
