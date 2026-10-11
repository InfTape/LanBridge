# LanBridge · Windows / Android 局域网互传

桌面项目包含完整源码和可运行文件。Windows 当前版本 1.4.3，Android 版本 1.2.2。支持扫码配对、手机分享后自动发送，以及 Windows 11 第一层右键菜单“发送到手机”。

- **Windows**：C++20，纯 Win32 API 窗口与标准控件，原生 DPI 缩放、托盘、文件对话框和拖放；网络使用 Winsock2，加密使用 BCrypt。界面参考 `native-screenshot-tool` 的中文原生控件设计。
- **Android**：Kotlin + Jetpack Compose BOM 2026.09.00 + Material 3 Expressive 1.5.0-beta01，实际使用 `MaterialExpressiveTheme`、动态配色、Expressive 动效与加载组件，支持深色模式和宽屏双栏。Material 3 Expressive 当前使用 Beta API。
- **Android 16**：targetSdk 36；为满足新版 UI 库要求，compileSdk 37。最低支持 Android 12 / API 31。
- 双向文本与多文件传输、加密配对、分块传输、SHA-256 校验、进度、接收回执、同名文件保留、接收历史和重新复制。
- Windows 接收文本自动写入文本剪贴板；接收文件保存后自动写入文件剪贴板，资源管理器可直接 Ctrl+V。
- Windows 显示配对二维码，Android 使用 CameraX 1.6.2 + ZXing 3.5.4 在设备上离线识别，扫描成功后自动连接；无需 Google Play 服务或下载扫码模型。

## 使用

1. 在 Windows 双击项目根目录的 `LanBridge.exe`。
2. 在 Android 安装 `LanBridge-Android16-debug.apk`。这是可安装的测试签名 APK，不是商店正式发布包。
3. 手机和电脑接入同一局域网。Windows 默认选择真实 Wi-Fi / 以太网的 IPv4，优先有网关的网卡；VPN、VMware、Hyper-V 等虚拟网卡只供手动选择。没有可用的真实局域网网卡时，不会自动选择 VPN。如果电脑连接多个局域网，可在地址下拉框中选择与手机同网段的 IPv4。
4. 手机点击 **扫码连接 Windows**，首次使用允许相机权限，对准 Windows 窗口中的二维码；识别成功后自动返回并连接。Windows 切换地址会同步刷新二维码。也可展开“手动输入 / 粘贴连接信息”，填写电脑地址和 **16 位配对码**，或粘贴 Windows 复制的连接信息；端口默认为 42831。
5. Android 中输入文本、选择文件，或在其他应用中“分享 → LanBridge”。从分享菜单进入后自动发送文件或文本，不需要再次确认；尚未连接时，先扫码配对，连接成功后自动发送。已有传输时，新的分享会排队等待。顶部大标语已移除，仅保留简洁的连接状态。
6. Windows 中选择文件、拖入文件或点击发送剪贴板，也可发送编辑框中的文本。手机收到的文件在系统“下载/LanBridge”目录，文本可手动复制。

Windows 接收目录为当前用户的 **下载/LanBridge**。右上角关闭按钮收起到托盘，仍继续接收；彻底退出使用托盘右键菜单。配对码每次启动重新生成。一次连接一台手机，多文件按顺序传输；文件夹请先压缩。连续接收多个文件时剪贴板保留最后一个文件，其余文件可在历史列表中重新复制。

1.4.1 参考 `native-screenshot-tool` 的 Win32 自绘方式，将标题栏、托盘右键菜单、托盘悬停提示与普通控件统一为 Microsoft YaHei UI，启用 ClearType 并按所在屏幕 DPI 缩放；未连接局域网时的二维码占位文字也使用同一字体。标题栏保留拖动、双击最大化、边缘缩放和最小化/最大化/关闭操作；托盘菜单保留键盘操作与开机启动勾选状态。

1.4.2 将 Android 应用的双向箭头图标复用于 Windows 程序文件、标题栏、任务栏、托盘、右键发送与菜单身份包；ICO 内含 16、20、24、32、40、48、64、96、128、256 像素版本，SVG 和 PNG 源素材位于 `windows/assets`。

1.4.3 将托盘菜单和悬停提示调整为截图工具同样的 9pt 微软雅黑 UI。菜单采用相同的行高、左右文字边距与正常菜单宽度，较长文案仍自动扩展；“显示主窗口”“开机启动”使用简洁文案，勾选标记保持可见。主窗口的字号不受此调整影响。

## Windows 开机启动

勾选窗口右上角 **开机启动（默认收至托盘）**，或在托盘右键菜单勾选“开机启动”。实现参考 `native-screenshot-tool`，使用纯 Win32 API 写入当前用户的 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值名为 `LanBridge`，命令为带引号的完整程序路径加 `--startup`，无需管理员权限。

登录 Windows 后程序直接在托盘运行并等待连接，不弹出主窗口。手动双击程序或点击托盘图标会显示窗口；重复的自启动不会弹窗、重启已有实例或断开现有连接。取消勾选会删除本程序的启动项，设置保存在启动项本身，不会在下次启动时自动重新开启。初次运行默认不勾选，本机按用户要求已启用。

便携目录移动后，在新位置打开程序并勾选开机启动以更新路径；删除程序前先取消勾选。如果曾在 Windows 任务管理器中单独禁用 LanBridge 的启动项，需要在任务管理器中重新启用。开机启动只启动 Windows 程序，手机仍需扫描这次启动生成的新二维码。

## Windows 11 右键发送

完整解压 `LanBridge-Windows-portable.zip`，保留 `LanBridge.exe`、`LanBridgeShell.dll`、`context-menu` 和 `scripts` 在同一目录。打开程序，点击右上角 **启用右键发送**；托盘右键菜单也有相同入口。首次启用会弹出 Windows 管理员授权，用于信任本机生成的菜单包签名证书。证书仅加入 `LocalMachine/TrustedPeople`，不是根 CA；菜单身份包注册到当前用户。普通文件传输不需要管理员权限。

启用后，选中一个或多个文件，右键选择 **发送到手机**。这是 Windows 11 第一层原生菜单；命令使用 `IExplorerCommand` 实现。手机已连接时立即发送，传输繁忙时排队；未连接时显示配对二维码，扫码成功后自动发送，不必再选一次文件。文件夹不提供此命令，需先压缩。单次最多 1024 个文件；待发送队列保存在当前 Windows 进程内，退出程序后需要重新选择。排队期间文件被删除或在发送前无法打开时，报告发送失败并保留连接；传输已开始后失败则断开以清理未完成文件。

1.3.1 修复了 1.3.0 中右键加载菜单会结束主程序、导致手机断线的问题：主程序保持独立的 Win32 进程，仅菜单 COM 宿主使用包身份。显示菜单不会重启主程序或重新配对；重复点击“启用右键发送”也不再反复注销同一个菜单包。旧程序已经退出的连接，更新后需重新扫码一次。

需要取消时点击 **移除右键发送**：注销本组件身份包，并移除由本组件添加的证书信任（会再次请求管理员授权）。不会修改其他应用菜单或切换系统右键菜单样式。便携目录移动后，重新点击“启用右键发送”更新绑定路径；删除目录前先移除菜单。菜单暂未刷新时，可关闭并重新打开资源管理器窗口；系统仍缓存旧扩展时，重新登录后生效。

也可用 Windows PowerShell 执行：

```powershell
.\scripts\register-context-menu.ps1 -Action Enable
.\scripts\register-context-menu.ps1 -Action Disable
```

本地构建使用自签名代码签名证书。私钥只保存在构建用户的 `CurrentUser/My` 证书存储，未导出或打入便携包；公开 `.cer` 随身份包提供。其他电脑使用此开发签名包时同样需要授权证书信任。正式公开分发可用受信任的代码签名证书重新签名。实现依据：[Windows 11 原生菜单扩展](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/integrate-packaged-app-with-file-explorer)、[MSIX 签名证书与信任](https://learn.microsoft.com/en-us/windows/msix/package/create-certificate-package-signing)。

扫码只负责填写地址并发起连接，手机仍需与电脑处于同一局域网。每次重启 Windows 端后需扫描新的二维码；二维码包含当前配对码，只提供给要连接的手机。相机权限被拒绝、设备没有相机或扫码失败时，可使用手动连接。

若出现 Windows 防火墙提示，仅允许受信任的专用网络。连接失败时检查地址、同一网段、VPN 和 Wi-Fi 的客户端隔离设置。必要时用管理员 PowerShell 按需运行 `scripts/allow-private-network.ps1`；它只允许专用网络、本地子网的 TCP 42831，安装和构建过程不会修改防火墙。

Android 端使用数据同步前台服务和通知维持连接；可在应用或通知中断开。系统可能因前台服务时限或设备省电策略终止长时间后台连接，应用会显示断开后允许重新配对。传输中断的未完成文件会被清理，需要重新发送。

待发送分享由应用统一管理，旋转屏幕不会重复加入队列；分享附件时发送文件，分享纯文本时发送文本。队列保留在当前应用进程内，系统终止进程后需要重新分享；已经开始但失败的传输不会自动重试。

共享文件在开始网络传输前出现读取权限、文件已删除等错误时，仅提示发送失败，保持现有连接。文件头已经发送后的错误会断开连接，以便两端清理未完成传输；界面会保留实际失败原因。

## 构建

GitHub 仓库保存源码、资源、构建脚本与验证文档。根目录的 EXE、APK、Shell DLL 和签名菜单包由以下脚本生成，不提交构建缓存、本机 SDK 路径或签名密钥。

Windows 安装 Visual Studio 2022 / Build Tools 的“使用 C++ 的桌面开发”和 Windows SDK，然后：

```powershell
.\build-windows.ps1 -Test
```

Windows Release 使用静态 C++ 运行库，只需系统 DLL，无需 Qt、WebView 或外部界面运行时。

构建脚本同时生成原生 Shell DLL 和签名身份包，需要 Windows SDK 的 MakeAppx / SignTool。首次构建会在当前用户证书存储创建不可导出的开发签名私钥，构建本身不会启用菜单或更改证书信任。

Android 使用 JDK 17+（本机验证使用 JDK 21）、Android SDK Platform 37.0 和 Build Tools 36.0.0。Gradle Wrapper 固定为 9.3.1，Android Gradle Plugin 为 9.1.1。Android Studio 可直接打开 `android` 文件夹；或：

```powershell
.\build-android.ps1 -SdkPath '你的 Android SDK 路径' -Test
```

先构建两端后，可在 Windows 运行实际 Kotlin / BCrypt 互通测试：

```powershell
.\scripts\test-interop.ps1
```

同时验证实际 Win32 界面二维码与 Android 解码代码（指定路径的父目录须已存在）：

```powershell
.\scripts\test-interop.ps1 -QrImage "$env:TEMP\LanBridge-QR.png"
```

该测试捕获实际 Windows 二维码及切换地址后的二维码，使用生产 `QrDecoder.kt` 解码，并验证旋转画面。它不会打开相机或改写剪贴板。

ADB 真机分享测试（手机需允许安装测试 APK）：

```powershell
cd android
.\gradlew.bat assembleDebugAndroidTest
cd ..
.\scripts\test-android-share.ps1 -AdbPath '你的 adb.exe 路径'
```

该测试使用临时文件及单独的 Win32 回显服务，覆盖首次分享、旋转重建、连接后自动发送、再次分享以及多文件分享，不改写 Windows 剪贴板。测试会断开手机当前连接，完成后需要重新扫码。

## 验证范围

- Windows 原生协议集成测试：双向 Unicode 文本与文件、SHA-256、空文件、同名文件、心跳、错误配对码、目录穿越拒绝及临时文件清理。
- Windows ShellIntegration：原生 COM 命令标题/图标、多文件状态、文件夹隐藏、无效 IPC 拒绝、中文路径、命令行引用、首次右键启动后等待配对、复用运行中程序、队列顺序，以及已连接后的多文件发送与 SHA-256 校验；不改写剪贴板。
- Windows 已注册菜单回归：使用 `ShellIntegration.exe --registered-live 完整路径\LanBridge.exe`，在加密连接已建立时经系统激活菜单 30 次，验证原进程与连接存活，再实际调用已注册菜单发送两个文件并校验 SHA-256。需预先启用菜单、关闭其他 LanBridge 实例；不改写剪贴板。
- Windows StartupIntegration：真实注册表读写、中文和带空格路径、取消时保留其他启动项、`--startup` 隐藏窗口但可加密连接、重复自启动保持原连接，以及手动打开恢复窗口；标题字体、拖动命中区域、最小化/最大化/还原/关闭到托盘和托盘菜单期间保持连接。UI 测试备份并恢复原 LanBridge 启动项，不改写剪贴板。
- Windows 实际窗口测试：接收后自动生成 `CF_UNICODETEXT` / `CF_HDROP`、反向发送、接收历史和重新复制；测试会保存并恢复原剪贴板。若原剪贴板含无法保存的虚拟文件数据，测试会跳过，避免覆盖用户内容。
- Android JVM 测试：配对输入校验；**实际生产 Wire.kt** 与 Windows BCrypt 服务互通，覆盖 0、1、262144、262145、1048593 字节文件以及 Unicode 文本和心跳。
- Android 二维码测试：配对格式与无效二维码拒绝、相机亮度缓冲区行间距/像素间距处理、空画面、Windows 实际界面解码、地址刷新及旋转画面。
- Android 分享队列测试：未连接时等待、连接后只发送一次、传输期间的新分享按顺序发送，以及连接竞争时保留未接收的请求。
- Android APK 构建成功，Lint 无错误。用户已在 Android 16 真机完成扫码连接；ADB 实际分享验证通过：262161 字节文件在 Wi-Fi 下无需确认自动发送，SHA-256 一致，Windows 自动复制文件。不可读取的分享文件报错后连接保持，下一次分享仍成功。系统文件选择器、完整多文件真机测试及后台省电行为尚未完整验证。

协议细节见 `docs/PROTOCOL.md`。传输内容不会上传到互联网。

官方框架文档：[Compose Material 3](https://developer.android.com/jetpack/androidx/releases/compose-material3)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Android 16 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。
