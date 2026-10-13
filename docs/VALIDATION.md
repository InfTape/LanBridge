# 验证记录

验证日期：2026-10-13。Windows 1.6.4 修复局域网地址及二维码未随网络就绪更新，Android 保持 1.4.0，菜单身份包不变。Windows Release 构建及全部 12 项集成测试通过，0 skipped。

旧版仅在 WM_CREATE 读取 LocalAddresses，之后没有地址变化通知或重新打开窗口时的刷新。实际生产连接的本地地址为 192.168.0.102、手机为 192.168.0.100，而窗口仍显示占位提示；传输服务监听所有接口，故可以传文件但扫码界面过期。新版使用 NotifyUnicastIpAddressChange 和 NotifyIpInterfaceChange，仅将通知投递到 UI 线程，250 ms 合并刷新；订阅后立即读取地址避免初始化间隙，打开窗口及 Connected 事件也刷新。通知订阅失败时使用 5 秒定时回读，退出取消通知及定时器；回调不等待 UI，不从回调取消自己。实现参考 [IPv4 地址通知](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-notifyunicastipaddresschange) 和 [网卡变化通知](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-notifyipinterfacechange)。

新增 AddressRefreshIntegration 通过实际隐藏主程序及真实加密 TCP 会话，模拟控件仍持有启动时占位列表并投递生产刷新消息，验证恢复当前系统地址、二维码/复制按钮可用状态、保留明确选中的真实虚拟网卡地址、失效地址恢复真实局域网默认值，以及打开窗口立即刷新。配对码保持一致、原加密连接继续交换心跳，整个用例不改写剪贴板。此测试没有禁用实际 Wi-Fi 或人为改变网卡 IP，不声称实测过 DHCP 换址全过程。原有 11 项测试全部通过，包括无有效菜单记录时启动仅一个进程的审计。

正式桌面 EXE 已更新并打开窗口，原生控件检查显示当前选择 192.168.0.102，列表包含 Wi-Fi、VPN 和 VMware 地址，默认为真实 Wi-Fi；发送控件可用。Windows 本地配对文件修改时间仍为更新前的 2026-10-12，开机启动命令保持正式 EXE 加 --startup。便携包同步更新；地址刷新本身不执行菜单部署或重启服务。

验证日期：2026-10-13。Windows 1.6.3 修复实际登录启动仍启动辅助进程的问题，Android 和菜单身份包不变。Windows Release 构建及全部 11 项集成测试通过，0 skipped。

读取管理员保护的 WDI StartupInfo 后确认，22:32 开机时 1.6.2 主程序确实调用注册脚本，产生 PowerShell 和 Conhost；同一批历史文件保留了更早版本的 csc/cvtres。此前仅根据当前进程和记录时间推断全部是旧统计并不成立。代码路径是在一次 IsCurrent 返回 false 后立即注册；该次原生检查的具体失败原因未记录。本次开机模式完全不调用菜单检查或注册，不再依赖登录阶段包查询是否已就绪。用户明确打开主窗口后才自动检查，必要时注册一次；已注册菜单持续可用。

AutoContextMenuIntegration 使用缺失有效注册的隔离便携目录，以挂起状态将主程序加入 Job 后恢复 --startup；建立真实加密连接、持续心跳后累计进程数和活动进程数均为 1，注册探针未执行。随后正常启动第二个实例打开原窗口，验证延后注册执行一次；重复自启动和再次显示窗口均不再注册，同一连接持续正常。有效菜单记录下的 NativeMenuStartupIntegration 也通过。原有文件夹、配对、启动设置、Shell、传输及剪贴板测试全部通过。

本机启动统计先备份至工作目录，再仅删除当前用户 4 个历史 XML 中 LanBridge 及其由 PID 和启动时间同时匹配的后代，共 14 个进程节点；其他软件的 Process 内容逐项比较一致。通过原子文件替换更新统计，没有删除整个 WDI 目录。重新打开任务管理器后，实际 UI Automation 读取到单独的 LanBridge.exe 条目，无“(5)”组名；LanBridge 对应历史节点为 0。开机启动 Run 命令保持为正式桌面 EXE 加 --startup。修复版实际隐藏启动并保持单主进程。清理统计是本机一次性维护，不加入应用启动逻辑，不以清理历史替代代码修复；新版本尚未再进行整机重启验证。

验证日期：2026-10-13。Windows 1.6.2 优化右键菜单启动检查，Android 保持 1.4.0，菜单身份包保持 1.6.0.0。Windows Release 构建通过，11 项集成测试全部通过，0 skipped。以下为本次验证，后文保留此前版本记录。

| 本次启动检查 | 结果 |
| --- | --- |
| 原生菜单检查 | 使用正式桌面便携目录、当前用户实际注册的包、有效外部路径和 LocalMachine/TrustedPeople 证书验证通过；检查过程不启动脚本或 COM 宿主 |
| 失效记录处理 | 在独立临时 HKCU 测试键验证有效记录，以及目录移动、菜单包摘要变化、包版本不存在、证书指纹错误、未知记录版本、错误注册表类型、缺失字段和缺失目录；失效时均返回需要注册，结束删除测试键，不修改生产记录 |
| 正常启动进程审计 | 将实际主程序以挂起状态加入 Windows Job 后恢复，以 --startup 隐藏启动并建立加密连接；3 秒心跳检查后 Job 累计产生进程数及活动进程数均为 1，已退出的短命子进程也计入累计数量，确认未启动 PowerShell、csc、cvtres 或刷新辅助进程 |
| 首次注册回归 | 隔离便携目录的 AutoContextMenuIntegration 验证失效检查仍调用 Enable；重复实例不再次注册，同一加密连接保持正常 |
| 正式已注册菜单 | 30 次真实 COM 菜单激活、两次手动 Enable，以及已注册菜单多文件和目录 Invoke 通过，内容 SHA-256 一致；原主进程和配对连接保持，主程序仍无包身份，剪贴板未改写 |
| 原有功能回归 | FolderIntegration、PairingIntegration、RememberedDesktopIntegration、StartupIntegration、ShellIntegration、ShellSelectionIntegration、AutoContextMenuIntegration、NativeContextMenuIntegration、NativeMenuStartupIntegration、BridgeIntegration、UiIntegration 全部通过 |

手动 Enable 使用 LanBridge.exe --refresh-shell 调用原生 SHChangeNotify；主程序自动注册后在自身进程刷新。注册脚本已移除 Add-Type，不再动态编译 C#。本机从旧版 JSON 记录补写原生 HKCU 记录时，已有部署和证书得到复用，没有注销重装菜单。本次未重新启动或登录 Windows，因此不声称任务管理器已清除旧启动记录或重新评定启动影响。

Windows 1.6.1 默认自动启用右键发送：已移除主窗口及托盘中的启用/移除入口，首次主进程启动在后台执行原有幂等注册脚本，重复启动只转交已有实例。菜单身份包仍为 1.6.0.0，Android 保持 1.4.0。后台检查成功不覆盖连接和传输状态，注册失败显示原因。Windows Release 构建及 9 项集成测试通过，其中 AutoContextMenuIntegration 使用隔离便携目录和注册脚本探针，验证实际主程序自动调用 Enable、隐藏自启动、按钮移除、重复启动不再调用注册且同一加密连接心跳正常；StartupIntegration 同时确认原按钮已不存在。已注册 COM 的连接与发送检查另行使用正式签名菜单组件，保留原有身份隔离。

正式桌面程序启动后自动注册检查完成，本机手机自动重连。随后执行真实已注册 COM 的标题、图标、ZIP 和文件/目录混合选择检查，前后保持同一主进程 PID、手机 TCP 端口及连接创建时间；不发送测试内容、不改写剪贴板。用于独立加密客户端的 registered-live 用例本次被已连接手机占用单设备会话而无法握手，改用上述生产手机连接下的只读 COM 检查；旧版完整 registered-live 发送记录保留。

验证日期：2026-10-12。当前 Windows 1.6.0、Android 1.4.0（versionCode 8），新增双向文件夹传输。Windows 8 项集成测试通过；Android 构建、Lint（0 errors / 19 warnings）及带真实 TCP、UDP、文件夹、二维码 fixture 的 34 项 JVM 测试通过，0 failures、0 skipped。Xiaomi 2510DRK44C / Android 16 真机安装成功，用户完成 SAF 保存目录授权。以下先记录本次文件夹验证，再保留旧版本的历史检查；历史中的“文件夹隐藏”是旧版行为，1.6.0 已改为目录及混合选择可发送。Windows x64；MSVC 19.44；JDK 21；Gradle 9.3.1；AGP 9.1.1。

| 本次文件夹检查 | 结果 |
| --- | --- |
| Windows 全量回归 | 8 项通过：FolderIntegration、PairingIntegration、RememberedDesktopIntegration、StartupIntegration、ShellIntegration、ShellSelectionIntegration、BridgeIntegration、UiIntegration；最后仅调整断线原因展示后再次运行 FolderIntegration / BridgeIntegration，两项通过 |
| 原生文件夹协议 | 路径穿越、保留名称、重复和大小写冲突拒绝；空根、空子目录、零字节文件、同名根另存、摘要错误及不完整目录清理；加密传输、接收准备拒绝和发送取消后继续心跳；异常终止的子进程目录在下一次恢复清理，仍存活进程的目录不删除 |
| Android 状态机及跨端 | FolderTransferTest 6 项及 FolderInteropTest 1 项通过，使用生产 Kotlin Wire / FolderReceiver 和真实 Windows TCP 服务；包含中文及 emoji、262145 字节分块边界、空文件和空目录，双向内容与 SHA-256 一致 |
| Windows 11 已注册菜单 | 1.6.0.0 身份包实际注册；已认证连接下重复激活真实 COM 菜单 30 次，重复启用两次，文件及目录 Invoke 发送成功；主程序保持无包身份，连接心跳正常 |
| Windows 文件夹剪贴板 | UiIntegration 验证完整提交前不发布目录，完成后一次写入根目录 CF_HDROP、一次历史记录及重新复制；结束恢复原剪贴板 |
| Android SAF 真机接收 | 用户授权本地“下载/LanBridge”，Windows 正式程序原生发送请求传入 LanBridge-folder-test；实际接收中文文件、零字节文件及 nested/empty 空目录，手机界面显示完整接收；再次发送另存为 LanBridge-folder-test (1)，原目录保留 |
| Android SAF 真机发送 | 以具有已持久授权的子目录 URI 向正式应用发送 ACTION_SEND，自动传回 Windows；只发送指定子目录，没有扩大到授权根目录。目录结构及 SHA-256 一致；再次分享在 Windows 另存为 (1)，两份原内容均保留 |
| 真机自动复制 | 原生检查 CF_HDROP 为本次接收的完整根目录；两次目录接收均成功。每次检查前备份、结束恢复原剪贴板 |
| 真机记住绑定 | 本次升级测试时两端本地配对凭据不一致，用户重新扫描当前二维码同步后加密连接成功；随后 force-stop 手机应用再启动，自动重连同一 Windows 进程并再次自动发送目录，无需扫码 |

真机中文文件为 49 字节，Windows 源、手机接收及 Windows 回传的 SHA-256 均为 `717852d41b22456cc31f7698a7e6cea5bee85505ab282d270a03cb1e2d75c6cd`。真机验证使用小型测试目录；大目录、手机低空间、不同 SAF 提供方、系统突然关机及后台长时间运行尚未逐一实测。设备禁止 ADB 代点和测试 APK 安装，系统文件夹选择/授权由用户完成；生产 SAF 接收和分享发送通过正式应用验证，不声称 instrumentation 已运行。

此前 Android 1.3.1 修复 Network.bindSocket EPERM 及前台服务启动顺序，27 项 JVM 测试记录和用户扫码、进程重建自动重连记录继续保留。非绕行 VPN 拒绝显式绑定 Wi-Fi 时继续使用新的系统默认路由 socket，本次未更改该策略。

| 检查 | 结果 |
| --- | --- |
| Android 1.3.1 网卡限制兼容 | SocketRoutingTest 4 tests 通过；模拟 TCP EPERM 与 UDP 权限拒绝，确认关闭失败 socket、创建新 socket 后实际本地 TCP/UDP 通信成功，保留成功绑定并继续抛出无关程序错误；真机扫码与自动重连均记录绑定拒绝后的正常连接 |
| Android 1.3.1 服务启动顺序 | ForegroundStartGateTest 4 tests 通过；覆盖服务确认前取消、连接立即失败、旧连接回调与快速替换，以及乱序回调；网络任务仅在 startForeground 完成的 onStartCommand 回调后启动，取消须等待所有已请求启动得到确认；真机 dumpsys 显示 isForeground=true，扫码与进程重建期间未出现新闪退 |
| Android 1.3.1 真机绑定与重连 | ADB 覆盖安装 versionCode 7 成功；用户扫码后界面显示“已连接 Windows · 已记住绑定”；确认加密绑定文件存在；am force-stop 后 am start 产生新 PID，自动连接同一 Windows 进程，界面再次显示已记住绑定，连接保持超过多个 15 秒心跳周期；未修改剪贴板 |
| Android 1.3.1 构建与互通 | assembleDebug、lintDebug、testDebugUnitTest 通过；Lint 0 errors / 13 warnings；27 tests，0 failures，1 skipped，实际 Windows Wire 和 UDP fixture 均运行；apksigner v2 校验及 ADB 覆盖安装成功 |
| Windows Release 构建 | 通过；静态 C++ 运行库；只依赖系统 DLL |
| 1.5.0 PairingIntegration | 通过；实际当前用户 DPAPI 保存/读取，重复启动复用同一凭据，磁盘内容无明文配对码，重置后新凭据可读取，损坏存储拒绝且不静默换码；跨端 HMAC 向量及实际 UDP 发现应答通过 |
| 1.5.0 RememberedDesktopIntegration | 通过；实际 Win32 主程序以隐藏启动模式建立加密连接并交换心跳，正常退出再启动后配对码保持不变，用首次缓存的凭据直接再次握手并交换心跳；不读写剪贴板 |
| Android 1.3.0 绑定保存 | RememberedPairingTest 3 tests 通过；AES-256-GCM 保存/恢复地址及凭据、密文不含明文凭据、随机 IV、篡改/错误密钥/错误版本及截断拒绝；Keystore 实际设备保存尚待真机验证 |
| Android 1.3.0 发现互通 | DiscoveryProtocolTest 3 tests 通过；接受 Windows HMAC 向量及新地址、拒绝其他电脑凭据/篡改/旧挑战/错误长度，并经真实 Windows UDP fixture 验证生产发现解析 |
| Android 1.3.0 自动重连 | 构建及 Lint 通过；代码在已认证 Hello 后记住绑定，在应用打开和系统分享时恢复，遇到临时断线重试；主动断开暂停，新的分享或重连按钮恢复；设备进程重建、Wi-Fi 地址变化及厂商后台限制的实际运行尚待用户安装后验证 |
| Android 1.3.0 JVM 测试汇总 | 19 tests，0 failures，1 skipped；绑定加密、发现协议、配对校验、分享队列及实际 Wire/UDP 互通均运行通过；二维码外部图片 fixture 用例本次未配置而跳过，下方二维码完整验证为此前记录 |
| StartupIntegration | 通过；独立测试键验证带空格和中文路径的命令、反复启用/取消和保留其他启动项；实际窗口以 `--startup` 保持隐藏并建立加密连接，重复自启动保持原连接，手动启动显示同一窗口；勾选框实际写入/删除 Run 值，结束后恢复原启动项；不改写剪贴板 |
| 本机开机启动配置 | 已启用；通过正式程序勾选框配置当前用户 Run 值 `LanBridge`，实际值为 `"C:\Users\Admin\Desktop\LanBridge\LanBridge.exe" --startup`，勾选状态同步；未执行电脑重启，登录启动行为通过上述真实进程参数测试验证 |
| 默认地址回归验证 | 通过；VPN `tun0`、VMware 与 Hyper-V 同时启用时，生产 `LocalAddresses()` 将真实 Wi-Fi `192.168.0.102` 排在第一位；虚拟网卡仅允许手动选择 |
| BridgeIntegration | 通过；双向文本/文件、空文件、同名文件、心跳、坏摘要、错误配对码、目录穿越和中断清理 |
| UiIntegration 剪贴板测试 | 1.5.0 通过；实际 Win32 窗口双向文本/文件、自动复制、历史重新复制；结束后恢复原剪贴板 |
| ShellIntegration | 通过；原生 IExplorerCommand、中文与多文件路径、格式损坏/相对路径/非法请求文件拒绝、文件夹隐藏、首次启动排队、已运行实例转交、连接后按序自动发送及 SHA-256 |
| Windows 本地文件读取失败 | 通过；不存在的待发送文件报错后，原连接继续发送文本成功 |
| Windows 11 菜单身份包 | MakeAppx 构建、Authenticode 签名验证及实际 Add-AppxPackage 注册通过；版本 1.5.0.0，外部目录绑定至桌面项目；随附公开证书，私钥未导出 |
| 1.4.4 ZIP 回归 | 有效的空 ZIP 经真实 Windows Shell 解析，确认带有 SFGAO_FOLDER；原 DLL 复现 ZIP file state 失败，修复 DLL 的 ShellSelectionIntegration 通过；覆盖 ZIP 单选、ZIP 与普通文件多选，以及 ZIP 混合真实目录（目录名称也为 .zip）时隐藏菜单 |
| 1.4.4 已注册菜单 | 更新身份包后，系统实际 CoCreateInstance 激活正式菜单 DLL，同一 ZIP 与目录回归通过；不调用 Invoke、不发送测试文件、不改写剪贴板 |
| 1.4.4 更新保持手机连接 | 菜单更新及已注册 COM 检查前后，传输主进程 PID 15632 保持不变；同一手机 TCP 连接 192.168.0.100:34820、创建时间 14:59:32 均不变，无需重新配对 |
| 已注册 Shell COM 激活（空闲） | 通过；Windows 实际激活已注册的 packaged COM surrogate，检查中文命令标题、手机图标、多文件可用状态与混合文件夹隐藏状态 |
| 已连接时加载已注册菜单 | 通过；原生测试客户端先建立加密连接，再经系统 COM 注册激活菜单 30 次，每次释放后检查原主进程和同一连接心跳；随后经已注册 Invoke 发送两个文件，文件名、内容、SHA-256 全部一致；测试不改写剪贴板 |
| 主程序身份隔离 | 通过；GetPackageFullName 验证传输进程无包身份，菜单仍由系统 packaged COM surrogate 激活。1.3.0 嵌入主程序的 MSIX 身份已移除 |
| 重复启用菜单 | 通过；在同一加密连接中连续两次以原生 CreateProcess 后台启动实际注册脚本，均返回“无需重复注册”；原主进程和连接心跳保持正常。脚本优先使用当前 PowerShell 宿主的内置模块，避免继承 PowerShell 7 模块路径后加载失败 |
| UiIntegration --qr | 通过；真正的 Win32 窗口生成当前 IPv4 及切换地址后的二维码渲染图，不改写剪贴板 |
| Android assembleDebug | 通过；生成可安装测试签名 APK |
| Android lintDebug | 通过，0 errors、13 warnings；保留部分非阻塞的依赖更新、API 目标和代码风格建议 |
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
| APK apksigner verify | 通过，APK Signature Scheme v2；1.3.1 可覆盖安装旧 APK（versionCode 7） |
| 1.4.2 应用图标 | 通过；Win32 从实际 EXE 资源加载 16～256 像素的 10 种尺寸；正式主窗口的大小图标均已设置；实际标题栏截图与 EXE 图标渲染已检查；四项 Windows 集成测试和已注册菜单连接回归通过 |
| 1.4.3 托盘尺寸 | 通过；TypographyPreview 捕获两个正式程序的实际菜单，当前屏幕均为 308 像素宽，字号、行高与文字边距一致；LanBridge 开机启动勾选未遮挡文字。四项 Windows 集成测试通过，最终菜单文案和宽度调整后再次通过 StartupIntegration 的连接、菜单与启动项检查 |
| Windows 界面 | 已检查实际 Win32 渲染图，中文文字与控件布局可见 |
| 1.4.1 标题栏字体与操作 | 通过；实际标题栏按钮的字体为 Microsoft YaHei UI；拖动区域返回 HTCAPTION；真实按钮执行最大化、还原、最小化及关闭到托盘后，原加密连接心跳保持正常 |
| 1.4.1 托盘菜单 | 通过；真实托盘弹出菜单显示期间及收起后，原加密连接心跳保持正常；TypographyPreview 捕获实际菜单并检查渲染图，中文文字与开机启动勾选标记正常 |
| 统一字体缩放 | 通过；TypographyPreview 检查 100%、150%、200% 字体实例的 Microsoft YaHei UI 家族及对应逻辑高度；当前屏幕实际窗口和菜单渲染已检查，未切换到其他物理屏幕验证 |

WireInteropTest 使用生产 `Wire.kt`，分别验证 Unicode 文本和 0、1、262144、262145、1048593 字节文件的双向传输与 SHA-256 一致性，覆盖空文件与分块边界。

QrDecoderTest 使用生产 `QrDecoder.kt`，识别 Win32 GDI 实际绘制的二维码截图，并与窗口地址、配对码组成的预期 URI 对照。覆盖初始地址及切换至测试地址 `192.168.77.42`、两张图片各旋转 90 度；测试地址仅用于渲染校验，不作为真实网络设备连接。

**用户真机反馈**：选择真实 Wi-Fi 地址 `192.168.0.102` 后，用户确认扫码连接正常；默认误选 VPN 地址的问题已修复。该反馈属于用户操作验证，没有采集真机 ADB 日志。

已通过 ADB 将 1.2.1 更新安装到 Android 16 / API 36 设备（型号 2510DRK44C），检查了实际界面。设备禁止 ADB 模拟点击及测试 APK 安装，因此完整 instrumentation 测试未运行；本次改用正式应用的实际分享 Intent 及 Win32 剪贴板检查完成上述单文件与错误恢复验证。JVM Wire / QR 互通测试需带对应 fixture 执行；普通单元测试命令会跳过这两项外部集成用例。

**尚未完整验证**：Android 16 真机的相机权限拒绝/恢复、文件选择器、接收时 MediaStore 保存、通知权限拒绝/前台服务六小时限额、Wi-Fi 地址变化、大文件与多文件传输及厂商后台省电行为。已验证扫码、前台服务正常启动和进程重建后的自动重连；上述验证不能替代全部端到端场景。

Windows 测试使用临时文件；UI 剪贴板测试仅在可完整备份原剪贴板时执行，否则报告跳过。二维码渲染测试无需改写剪贴板。未修改系统防火墙规则。

1.3.0 的原有 ShellIntegration 直接加载 DLL，已注册 COM 检查仅在主程序未运行时执行，遗漏了“已连接时系统激活菜单”的生命周期问题。用户报告后在本机复现：激活菜单导致带有同一包身份的主程序结束，没有应用崩溃事件。1.3.1 移除主程序身份后，同一已注册菜单激活路径与双文件发送通过。此回归使用原生加密测试客户端，手机重新扫码后的手动右键操作另行确认。
