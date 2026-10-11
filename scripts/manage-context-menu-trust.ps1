param(
    [Parameter(Mandatory=$true)][ValidateSet('Enable','Disable')][string]$Action,
    [Parameter(Mandatory=$true)][string]$CertificatePath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9A-Fa-f]{40}$')][string]$Thumbprint,
    [string]$LogPath = ''
)
$ErrorActionPreference = 'Stop'
try {
    $certificate = Get-PfxCertificate -FilePath $CertificatePath
    if ($certificate.Subject -ne 'CN=LanBridge Desktop Context Menu' -or $certificate.Thumbprint -ne $Thumbprint) { throw '证书不属于此 LanBridge 菜单组件。' }
    $storePath = "Cert:\LocalMachine\TrustedPeople\$Thumbprint"
    if ($Action -eq 'Enable') {
        Import-Certificate -FilePath $CertificatePath -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    } elseif (Test-Path -LiteralPath $storePath) {
        $installed = Get-Item -LiteralPath $storePath
        if ($installed.Subject -ne $certificate.Subject) { throw '证书内容不一致。' }
        Remove-Item -LiteralPath $storePath
    }
    if ($LogPath) { [System.IO.File]::WriteAllText($LogPath,'OK',[System.Text.UTF8Encoding]::new($false)) }
    exit 0
} catch {
    if ($LogPath) { [System.IO.File]::WriteAllText($LogPath,$_.Exception.Message,[System.Text.UTF8Encoding]::new($false)) }
    exit 1
}
