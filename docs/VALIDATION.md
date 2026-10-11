# 验证记录

验证日期：2026-10-11。Windows 1.3.1，Android 1.2.2。本次修复 Windows 11 右键加载菜单造成主程序退出、手机断线的问题；Android 真机分享结果来自 1.2.1，界面检查来自 1.2.2。Windows x64；MSVC 19.44；JDK 21；Gradle 9.3.1；AGP 9.1.1。

| 检查 | 结果 |
| --- | --- |
| Windows Release 构建 | 通过；静态 C++ 运行库；只依赖系统 DLL |
| 默认地址回归验证 | 通过；VPN `tun0`、VMware 与 Hyper-V 同时启用时，生产 `LocalAddresses()` 将真实 Wi-Fi `192.168.0.102` 排在第一位；虚拟网卡仅允许手动选择 |
| BridgeIntegration | 通过；双向文本/文件、空文件、同名文件、心跳、坏摘要、错误配对码、目录穿越和中断清理 |
| UiIntegration 剪贴板测试 | 本次通过；实际 Win32 窗口双向文本/文件、自动复制、历史重新复制；结束后恢复原剪贴板 |
| ShellIntegration | 通过；原生 IExplorerCommand、中文与多文件路径、格式损坏/相对路径/非法请求文件拒绝、文件夹隐藏、首次启动排队、已运行实例转交、连接后按序自动发送及 SHA-256 |
| Windows 本地文件读取失败 | 通过；不存在的待发送文件报错后，原连接继续发送文本成功 |
| Windows 11 菜单身份包 | MakeAppx 构建、Authenticode 签名验证及实际 Add-AppxPackage 注册通过；版本 1.3.1.0，外部目录绑定至桌面项目；随附公开证书，私钥未导出 |
| 已注册 Shell COM 激活（空闲） | 通过；Windows 实际激活已注册的 packaged COM surrogate，检查中文命令标题、手机图标、多文件可用状态与混合文件夹隐藏状态 |
| 已连接时加载已注册菜单 | 通过；原生测试客户端先建立加密连接，再经系统 COM 注册激活菜单 30 次，每次释放后检查原主进程和同一连接心跳；随后经已注册 Invoke 发送两个文件，文件名、内容、SHA-256 全部一致；测试不改写剪贴板 |
| 主程序身份隔离 | 通过；GetPackageFullName 验证传输进程无包身份，菜单仍由系统 packaged COM surrogate 激活。1.3.0 嵌入主程序的 MSIX 身份已移除 |
| 重复启用菜单 | 通过；在同一加密连接中连续两次以原生 CreateProcess 后台启动实际注册脚本，均返回“无需重复注册”；原主进程和连接心跳保持正常。脚本优先使用当前 PowerShell 宿主的内置模块，避免继承 PowerShell 7 模块路径后加载失败 |
| UiIntegration --qr | 通过；真正的 Win32 窗口生成当前 IPv4 及切换地址后的二维码渲染图，不改写剪贴板 |
| Android assembleDebug | 通过；生成可安装测试签名 APK |
| Android lintDebug | 通过，0 errors；保留部分非阻塞的依赖更新、API 目标和代码风格建议 |
| Android PairingTest | 5 tests，0 failures，0 skipped；包含二维码格式校验与无效内容拒绝 |
| Android QrDecoderTest | 4 tests，0 failures，0 skipped；实际 Win32 二维码解码、地址刷新、旋转画面、相机亮度缓冲区与空画面 |
| Android WireInteropTest | 1 test，0 failures，0 skipped；与 Windows fixture 实际互通 |
| Android SharedSendQueueTest | 3 tests，0 failures，0 skipped；等待配对、单次发送、忙时排队、保留独立的重复分享及连接竞争 |
| Android 新版界面 | ADB 实际界面检查：顶部大标语已移除，仅保留连接状态 |
| 1.2.2 状态卡片精简 | 构建、Lint 与签名校验通过；ADB 更新安装成功（versionCode 5），启动界面正常；生产已连接分支只显示地址，不再绘制“连接已就绪”状态行 |
| Android 16 实际 ACTION_SEND | 通过；ADB 向正式应用发送带读取授权的真实 MediaStore 分享 Intent，无确认点击；262161 字节文件经 Wi-Fi 到达 Windows，原文件与接收文件 SHA-256 一致 |
| 实际分享自动复制 | 通过；Win32 测试读取 CF_HDROP，确认是本次分享文件；执行前备份并在结束后恢复原剪贴板 |
| 不可读取文件后继续分享 | 通过；实际向应用分享不存在的 content URI，界面显示文件不可读取但仍连接；同一 TCP 连接下再次分享有效文件成功并自动复制 |
| Android SharedFlowDeviceTest | 测试 APK 构建通过；当前手机系统拒绝安装测试 APK（INSTALL_FAILED_USER_RESTRICTED），尚未运行该完整测试 |
| APK apksigner verify | 通过，APK Signature Scheme v2 |
| Windows 界面 | 已检查实际 Win32 渲染图，中文文字与控件布局可见 |

WireInteropTest 使用生产 `Wire.kt`，分别验证 Unicode 文本和 0、1、262144、262145、1048593 字节文件的双向传输与 SHA-256 一致性，覆盖空文件与分块边界。

QrDecoderTest 使用生产 `QrDecoder.kt`，识别 Win32 GDI 实际绘制的二维码截图，并与窗口地址、配对码组成的预期 URI 对照。覆盖初始地址及切换至测试地址 `192.168.77.42`、两张图片各旋转 90 度；测试地址仅用于渲染校验，不作为真实网络设备连接。

**用户真机反馈**：选择真实 Wi-Fi 地址 `192.168.0.102` 后，用户确认扫码连接正常；默认误选 VPN 地址的问题已修复。该反馈属于用户操作验证，没有采集真机 ADB 日志。

已通过 ADB 将 1.2.1 更新安装到 Android 16 / API 36 设备（型号 2510DRK44C），检查了实际界面。设备禁止 ADB 模拟点击及测试 APK 安装，因此完整 instrumentation 测试未运行；本次改用正式应用的实际分享 Intent 及 Win32 剪贴板检查完成上述单文件与错误恢复验证。JVM Wire / QR 互通测试需带对应 fixture 执行；普通单元测试命令会跳过这两项外部集成用例。

**尚未完整验证**：Android 16 真机的相机权限拒绝/恢复、文件选择器、接收时 MediaStore 保存、通知/前台服务、Wi-Fi 大文件与多文件传输及厂商后台省电行为。上述实际单文件测试不能替代全部端到端场景。

Windows 测试使用临时文件；UI 剪贴板测试仅在可完整备份原剪贴板时执行，否则报告跳过。二维码渲染测试无需改写剪贴板。未修改系统防火墙规则。

1.3.0 的原有 ShellIntegration 直接加载 DLL，已注册 COM 检查仅在主程序未运行时执行，遗漏了“已连接时系统激活菜单”的生命周期问题。用户报告后在本机复现：激活菜单导致带有同一包身份的主程序结束，没有应用崩溃事件。1.3.1 移除主程序身份后，同一已注册菜单激活路径与双文件发送通过。此回归使用原生加密测试客户端，手机重新扫码后的手动右键操作另行确认。
