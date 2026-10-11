# 按需用管理员 PowerShell 运行；只允许专用网络中的本地子网访问。
$ErrorActionPreference = 'Stop'
$programPath = (Resolve-Path -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'LanBridge.exe')).Path
$name = 'LanBridge-Private-LAN'
$existingRule = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
if ($existingRule) { Set-NetFirewallRule -Name $name -Enabled True -Profile Private -Action Allow }
else { New-NetFirewallRule -Name $name -DisplayName 'LanBridge · Private LAN' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 42831 -Program $programPath -Profile Private -RemoteAddress LocalSubnet | Out-Null }
Write-Host '已允许专用网络 / 本地子网访问 TCP 42831。'
