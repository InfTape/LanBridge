param([string]$CertificateThumbprint = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
$sdkDirectory = Get-ChildItem -LiteralPath $sdkRoot -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'x64\makeappx.exe')) } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$sdkDirectory) { throw '请安装 Windows SDK（需要 MakeAppx 与 SignTool）。' }
$sdkTools = Join-Path $sdkDirectory.FullName 'x64'
$packageFolder = Join-Path $projectRoot 'context-menu'
$staging = Join-Path $projectRoot 'windows\build\identity-package'
New-Item -ItemType Directory -Path (Join-Path $staging 'Assets') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $packageFolder 'AppxManifest.xml') -Destination $staging
Copy-Item -Path (Join-Path $packageFolder 'Assets\*.png') -Destination (Join-Path $staging 'Assets')
$packagePath = Join-Path $packageFolder 'LanBridge.ContextMenu.msix'
& (Join-Path $sdkTools 'makeappx.exe') pack /o /d $staging /nv /p $packagePath
if ($LASTEXITCODE) { throw '右键菜单身份包构建失败' }
$subject = 'CN=LanBridge Desktop Context Menu'
if ($CertificateThumbprint) {
    $certificate = Get-Item -LiteralPath "Cert:\CurrentUser\My\$CertificateThumbprint"
} else {
    $certificate = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert |
        Where-Object { $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date).AddDays(30) } |
        Sort-Object NotAfter -Descending | Select-Object -First 1
    if (!$certificate) {
        $certificate = New-SelfSignedCertificate -Type Custom -Subject $subject -FriendlyName 'LanBridge local context menu signing' `
            -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 `
            -KeyUsage DigitalSignature -KeyExportPolicy NonExportable -NotAfter (Get-Date).AddYears(3) `
            -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3','2.5.29.19={critical}{text}ca=false')
    }
}
if ($certificate.Subject -ne $subject) { throw '签名证书 Subject 必须与身份包 Publisher 一致' }
& (Join-Path $sdkTools 'signtool.exe') sign /fd SHA256 /s My /sha1 $certificate.Thumbprint $packagePath
if ($LASTEXITCODE) { throw '右键菜单身份包签名失败' }
Export-Certificate -Cert $certificate -FilePath (Join-Path $packageFolder 'LanBridge.ContextMenu.cer') -Force | Out-Null
Write-Output 'Built signed Windows 11 context menu identity package; private signing key remains in CurrentUser/My.'
