param([switch]$Test)
$ErrorActionPreference = 'Stop'
$compilerCmake = Get-Command cmake -ErrorAction SilentlyContinue
if ($compilerCmake) { $cmakePath = $compilerCmake.Source } else {
    $locator = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (!(Test-Path -LiteralPath $locator)) { throw '请安装 Visual Studio 2022 Build Tools 的“使用 C++ 的桌面开发”组件。' }
    $installation = & $locator -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    $cmakePath = Join-Path $installation 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
}
& $cmakePath -S (Join-Path $PSScriptRoot 'windows') -B (Join-Path $PSScriptRoot 'windows\build') -G 'Visual Studio 17 2022' -A x64 -DBUILD_TESTING=ON
if ($LASTEXITCODE) { throw 'CMake 配置失败' }
& $cmakePath --build (Join-Path $PSScriptRoot 'windows\build') --config Release
if ($LASTEXITCODE) { throw '编译失败' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'windows\build\Release\LanBridge.exe') -Destination (Join-Path $PSScriptRoot 'LanBridge.exe')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'windows\build\Release\LanBridgeShell.dll') -Destination (Join-Path $PSScriptRoot 'LanBridgeShell.dll')
& (Join-Path $PSScriptRoot 'scripts\build-context-menu.ps1')
if ($Test) {
    $testRunner = Join-Path (Split-Path $cmakePath) 'ctest.exe'
    & $testRunner --test-dir (Join-Path $PSScriptRoot 'windows\build') -C Release --output-on-failure
    if ($LASTEXITCODE) { throw '测试失败' }
}
