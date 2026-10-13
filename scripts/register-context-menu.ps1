param(
    [ValidateSet('Enable','Disable')][string]$Action = 'Enable',
    [string]$LogPath = '',
    [switch]$NoShellRefresh
)
$ErrorActionPreference = 'Stop'
# Native CreateProcess does not sanitize a PowerShell 7 parent's module path
# when launching Windows PowerShell. Prefer this host's built-in modules.
$env:PSModulePath = (Join-Path $PSHOME 'Modules') + [IO.Path]::PathSeparator + $env:PSModulePath
$packageName = 'LanBridge.ContextMenu'
$subject = 'CN=LanBridge Desktop Context Menu'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFolder = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'LanBridge'
$statePath = Join-Path $stateFolder 'context-menu-state.json'
$nativeReceiptKey = 'Software\LanBridge\ContextMenu'
$priorState = $null
$importedThumbprint = $null
function Set-MenuTrust([string]$TrustAction, [string]$Thumbprint) {
    $helper = Join-Path $PSScriptRoot 'manage-context-menu-trust.ps1'
    $certificatePath = Join-Path $projectRoot 'context-menu\LanBridge.ContextMenu.cer'
    $trustLog = Join-Path ([IO.Path]::GetTempPath()) ('LanBridge-trust-' + [guid]::NewGuid().ToString('N') + '.log')
    try {
        $arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $helper + '" -Action ' + $TrustAction + ' -CertificatePath "' + $certificatePath + '" -Thumbprint ' + $Thumbprint + ' -LogPath "' + $trustLog + '"'
        $powershell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $admin = Start-Process -FilePath $powershell -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru -Wait
        if ($admin.ExitCode -ne 0) { throw $(if (Test-Path -LiteralPath $trustLog) { Get-Content -LiteralPath $trustLog -Raw } else { '未完成管理员证书授权。' }) }
    } finally { if (Test-Path -LiteralPath $trustLog) { Remove-Item -LiteralPath $trustLog } }
}
function Finish-Result([string]$Message, [int]$Code) {
    if ($LogPath) { [System.IO.File]::WriteAllText($LogPath,$Message,[System.Text.UTF8Encoding]::new($false)) }
    Write-Output $Message
    exit $Code
}
try {
    $priorState = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    if ($Action -eq 'Enable') {
        $osBuild = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuild
        if ($osBuild -lt 22000) { throw '此菜单组件需要 Windows 11。' }
        foreach ($file in @('LanBridge.exe','LanBridgeShell.dll','context-menu\LanBridge.ContextMenu.msix','context-menu\LanBridge.ContextMenu.cer')) {
            if (!(Test-Path -LiteralPath (Join-Path $projectRoot $file) -PathType Leaf)) { throw "便携包缺少 $file，请完整解压。" }
        }
        $packagePath = Join-Path $projectRoot 'context-menu\LanBridge.ContextMenu.msix'
        $certificate = Get-PfxCertificate -FilePath (Join-Path $projectRoot 'context-menu\LanBridge.ContextMenu.cer')
        $signature = Get-AuthenticodeSignature -FilePath $packagePath
        if ($certificate.Subject -ne $subject -or !$signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $certificate.Thumbprint) {
            throw '身份包签名与随附的公开证书不一致。'
        }
        $trustPath = "Cert:\LocalMachine\TrustedPeople\$($certificate.Thumbprint)"
        $ownedTrust = $false
        if (!(Test-Path -LiteralPath $trustPath)) {
            Set-MenuTrust 'Enable' $certificate.Thumbprint
            $ownedTrust = $true; $importedThumbprint = $certificate.Thumbprint
        } elseif ($priorState -and $priorState.Thumbprint -eq $certificate.Thumbprint) { $ownedTrust = [bool]$priorState.OwnedTrust }
        # Appx deployment validates the signature and machine TrustedPeople
        # peer trust; the certificate is a code-signing leaf, not a root CA.
        $signature = Get-AuthenticodeSignature -FilePath $packagePath
        if ($signature.Status -in @('HashMismatch','NotSigned','NotSupportedFileFormat','Incompatible')) { throw "身份包签名无效：$($signature.Status)" }
        $registered = @(Get-AppxPackage -Name $packageName)
        $packageHash = (Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash
        # Repeated Enable is harmless. Re-register only for a changed package,
        # missing registration, or a moved portable folder.
        $alreadyRegistered = $registered.Count -eq 1 -and $priorState -and
            $priorState.PackageHash -eq $packageHash -and
            $priorState.PackageFullName -eq $registered[0].PackageFullName -and
            [string]::Equals($priorState.Location,$projectRoot,[StringComparison]::OrdinalIgnoreCase)
        if (!$alreadyRegistered) {
            foreach ($package in $registered) { Remove-AppxPackage -Package $package.PackageFullName }
            Add-AppxPackage -Path $packagePath -ExternalLocation $projectRoot
            $registered = @(Get-AppxPackage -Name $packageName)
        }
        New-Item -ItemType Directory -Path $stateFolder -Force | Out-Null
        @{ Thumbprint=$certificate.Thumbprint; OwnedTrust=$ownedTrust; Location=$projectRoot; PackageHash=$packageHash; PackageFullName=$registered[0].PackageFullName } |
            ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
        # Typed native receipt allows later launches to verify the actual
        # package, external path, hash and trust without starting PowerShell.
        $receipt = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($nativeReceiptKey)
        try {
            $receipt.SetValue('Schema',0,[Microsoft.Win32.RegistryValueKind]::DWord)
            foreach ($entry in @{Location=$projectRoot; PackageHash=$packageHash; PackageFullName=$registered[0].PackageFullName; Thumbprint=$certificate.Thumbprint}.GetEnumerator()) {
                $receipt.SetValue($entry.Key,[string]$entry.Value,[Microsoft.Win32.RegistryValueKind]::String)
            }
            $receipt.SetValue('Schema',1,[Microsoft.Win32.RegistryValueKind]::DWord)
        } finally { $receipt.Close() }
    } else {
        Get-AppxPackage -Name $packageName | Remove-AppxPackage
        if ($priorState -and $priorState.OwnedTrust -and $priorState.Thumbprint -match '^[0-9A-Fa-f]{40}$') {
            $trustPath = "Cert:\LocalMachine\TrustedPeople\$($priorState.Thumbprint)"
            if (Test-Path -LiteralPath $trustPath) {
                $certificate = Get-Item -LiteralPath $trustPath
                if ($certificate.Subject -eq $subject) { Set-MenuTrust 'Disable' $priorState.Thumbprint }
            }
        }
        if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
        [Microsoft.Win32.Registry]::CurrentUser.DeleteSubKeyTree($nativeReceiptKey,$false)
    }
    if (!$NoShellRefresh) {
        $refreshExecutable = Join-Path $projectRoot 'LanBridge.exe'
        if (Test-Path -LiteralPath $refreshExecutable -PathType Leaf) {
            $refresh = Start-Process -FilePath $refreshExecutable -ArgumentList '--refresh-shell' -WindowStyle Hidden -PassThru -Wait
            if ($refresh.ExitCode -ne 0) { throw '原生右键菜单刷新失败。' }
        }
    }
    if ($Action -eq 'Enable') {
        if ($alreadyRegistered) { Finish-Result '右键发送已启用，无需重复注册。' 0 }
        Finish-Result '已启用：右键文件或文件夹 → 发送到手机。菜单未刷新时，关闭并重新打开资源管理器窗口。' 0
    }
    else { Finish-Result '已移除右键发送菜单及本组件添加的证书信任。' 0 }
} catch {
    # Retain ownership metadata after a failed deployment so Disable can undo
    # the trust grant without requiring the user to edit certificate stores.
    if ($importedThumbprint) {
        New-Item -ItemType Directory -Path $stateFolder -Force | Out-Null
        @{ Thumbprint=$importedThumbprint; OwnedTrust=$true; Location=$projectRoot } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    }
    Finish-Result ("右键菜单更新失败：" + $_.Exception.Message) 1
}
