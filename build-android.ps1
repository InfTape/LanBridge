param([string]$SdkPath = '', [switch]$Test, [switch]$Release)
$ErrorActionPreference = 'Stop'
if ($SdkPath) {
    $resolvedSdk = (Resolve-Path -LiteralPath $SdkPath).Path.Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath (Join-Path $PSScriptRoot 'android\local.properties') -Value "sdk.dir=$resolvedSdk" -Encoding utf8
}
Push-Location (Join-Path $PSScriptRoot 'android')
$previousReleaseStore = $env:LANBRIDGE_RELEASE_KEYSTORE
$previousReleasePassword = $env:LANBRIDGE_RELEASE_PASSWORD
try {
    $variant = 'debug'
    $taskVariant = 'Debug'
    $gradleOptions = @('--console=plain')
    if ($Release) {
        $variant = 'release'
        $taskVariant = 'Release'
        # Keep the long-lived release identity outside the checkout. Protect its
        # password with current-user Windows DPAPI, never in source or arguments.
        $signingDirectory = Join-Path $env:LOCALAPPDATA 'LanBridge\AndroidSigning'
        $releaseKey = Join-Path $signingDirectory 'release.p12'
        $passwordFile = Join-Path $signingDirectory 'password.dpapi'
        New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
        if (!(Test-Path -LiteralPath $passwordFile)) {
            if (Test-Path -LiteralPath $releaseKey) { throw 'Release 密钥已存在但密码文件缺失；请恢复签名密码，不能更换密钥。' }
            $randomBytes = New-Object byte[] 32
            $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $randomGenerator.GetBytes($randomBytes) } finally { $randomGenerator.Dispose() }
            $protectedPassword = ConvertTo-SecureString ([Convert]::ToBase64String($randomBytes)) -AsPlainText -Force
            ConvertFrom-SecureString $protectedPassword | Set-Content -LiteralPath $passwordFile -Encoding ascii
        }
        $protectedPassword = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
        $env:LANBRIDGE_RELEASE_PASSWORD = ([PSCredential]::new('lanbridge', $protectedPassword)).GetNetworkCredential().Password
        $env:LANBRIDGE_RELEASE_KEYSTORE = $releaseKey
        if (!(Test-Path -LiteralPath $releaseKey)) {
            $keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { (Get-Command keytool).Source }
            & $keytool -genkeypair -keystore $releaseKey -storetype PKCS12 -alias lanbridge -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=LanBridge Android Release' -storepass:env LANBRIDGE_RELEASE_PASSWORD -keypass:env LANBRIDGE_RELEASE_PASSWORD -noprompt
            if ($LASTEXITCODE) { throw '生成 Release 签名密钥失败' }
        }
        $gradleOptions += @('--no-daemon', '--no-configuration-cache')
    }
    $tasks = @("assemble$taskVariant", "lint$taskVariant")
    # This project exposes its shared JVM test suite on the Debug variant.
    if ($Test) { $tasks += 'testDebugUnitTest' }
    & '.\gradlew.bat' @tasks @gradleOptions
    if ($LASTEXITCODE) { throw 'Android 构建或检查失败' }
    Copy-Item -LiteralPath ".\app\build\outputs\apk\$variant\app-$variant.apk" -Destination (Join-Path $PSScriptRoot "LanBridge-Android16-$variant.apk")
} finally {
    $env:LANBRIDGE_RELEASE_KEYSTORE = $previousReleaseStore
    $env:LANBRIDGE_RELEASE_PASSWORD = $previousReleasePassword
    Pop-Location
}
