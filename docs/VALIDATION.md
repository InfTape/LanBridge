# 验证记录

验证日期：2026-10-22。Android 1.10.1（versionCode 40）在接收历史加载成功、无读取错误且列表为空时显示 ReceivedEmptyState：居中人物/树木插画，下方 16dp 间隔显示居中“最近 24 小时没有收到文件或文本”；插画最大宽 200dp、按原 888:678 比例等比缩放，不增加背景卡片。加载中仍只显示加载文案，读取错误继续显示原错误/重试；有记录时显示原历史列表，手机和双栏布局共用该入口。

插画来自 ReadYouApp/ReadYou 的 palette.kt，固定来源 commit d2b979ccad9a3e54b9499929a9dc2824578b0fd9，完整保留 49 条 SVG 路径、路径顺序及透明度。以 PathParser/ImageVector 原生矢量显示，不引入 SVG 运行库或网络请求；路径解析懒加载，主题改变时更新调色，图片为装饰图不重复朗读空文案。APK 内附可独立渲染的 SVG 源文件（原主题角色以 data-theme-role 保留，设置浅色预览值）、Read You 仓库完整 GPL-3.0 许可和来源/修改说明，项目根目录添加 THIRD_PARTY_NOTICES.md。

已用 SVG 渲染器检查浅色/深色预览，人物、树木和地面完整、无图形失真或截断；这是矢量素材预览，不是 Android 屏幕截图。此次仅调整空状态 UI，未修改接收持久化或连接/传输逻辑，未增加或重复运行低影响 UI 的 JVM 测试。


1.10.1 Debug/Release 构建、两套 Lint 与签名验证通过（各 0 errors / 25 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.10.1/40，沿用原 Debug/Release 证书，Release 不可调试。校验 SVG 与原生矢量的 49 条路径逐条相同，Debug/Release 均包含当前 SVG 源文件、完整许可及来源说明，资产字节与源码一致。ADB 无设备，尚未真机验证空状态布局、文字位置及主题切换；本次预览仅检查矢量素材。

验证日期：2026-10-22。Android 1.10.0（versionCode 39）新增 ReceivedHistory 应用私有本地接收记录仓库，保存稳定 ID、完成接收的时间戳、名称、文本全文、文件/目录 URI、大小和类型。使用带版本及 CRC32 的二进制快照，同目录临时文件写入、flush/fd.sync 后以原子替换提交；失败保留原磁盘快照和本进程新记录，后续重试持久化。损坏/不可读快照不以空列表覆盖，恢复读取后合并期间新到记录；用户显式清空可重置坏快照。元数据写入不操作实际内容 URI。

文件通过完整校验并发布后、目录提交后、文本完成解码后，统一登记时间并持久化，再继续原接收回执。引擎以单一历史锁串行化启动恢复、收件、过期清理及清空，避免加载结果或定时器覆盖刚收到/刚清空的记录。仅保留接收时间大于当前时间减 24 小时且不晚于当前时间的记录；边界恰好 24 小时移除，取消原 80 条限制，时间相同按新到在前。启动和前台 onStart 刷新，存活期间按最早到期时间调度，最长每分钟复核一次；进程不运行时不另建定时任务，下次打开先过滤过期项再显示。

“最近收到”注明最近 24 小时规则，增加加载状态；读写失败显示记录风险与重试，清空仍可用于恢复损坏记录。恢复记录沿用 ID 并标记不闪烁，新收到的记录仍闪三次。清空写入空快照，过期和清空只删记录、不删已保存的文件/目录。旧版仅内存历史无法跨升级补回，不扫描下载目录推断旧接收记录。原图片分享授权、接收进度、连接及传输协议保持不变。

新增 ReceivedHistoryTest 10 项，使用真实 JVM 临时文件、fsync/原子替换和可控时钟，覆盖文件/目录/20 万字节 UTF-8 文本完整恢复与稳定 ID、恢复不闪标记、24 小时精确边界、运行中及久未打开后的过期、101 条无旧上限与相同时间顺序、清空重开不恢复及不删内容文件、替换中断不破坏旧快照且重试保存新记录、清空失败重试、截断/CRC 损坏保护与恢复合并、并发收件不丢失。完整 JVM 共 89 项，84 通过、5 项缺少外部 fixture/二维码图片而跳过，0 failures/errors；测试使用桌面文件系统，不代表 Android 私有存储、实际进程强杀、MediaStore/SAF URI 或 UI 动画验证。此次未重复运行未改动的 Windows 协议互通 fixture。


1.10.0 Debug/Release 构建、两套 Lint 和 APK 签名检查通过（各 0 errors / 25 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.10.0/39，沿用原 Debug/Release 证书，Release 不可调试。最终 Release/Test 构建再次运行 89 项 JVM 测试，84 通过、5 跳过，0 failures/errors；测试空断言警告已清理。ADB 无设备，未安装到手机或真机强杀/重启验证，也未改动实际用户记录/文件。

验证日期：2026-10-22。Android 1.9.11（versionCode 38）删除接收成功 Snackbar 卡片右侧“查看”按钮，并从 ReceiveSnackbarVisuals 移除成功态 actionLabel；失败态保留“详情”，接收中仍显示百分比。同步清理该成功动作专用的 ACTION_VIEW 回调、无法打开错误状态与对话框。成功提示继续按 Short 时长显示图标、标题和单行文件名，最近收到列表打开/分享功能及文件授权流程未改动。只删除提示动作，未修改文件接收或保存逻辑；此次低影响 UI 删除未新增或重复运行 JVM 测试。


1.9.11 Debug/Release 构建、两套 Lint 和 APK 签名检查通过（各 0 errors / 25 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.11/38，沿用原 Debug/Release 证书，Release 不可调试。静态检查确认接收成功无可见/语义操作，失败详情仍保留。ADB 无设备，尚未真机验证成功提示和失败详情按钮。

验证日期：2026-10-22。Android 1.9.10（versionCode 37）将首页 ComputerHeaderPager 的显示列表按 defaultComputerId 稳定分组，与管理页一致：默认设备在第一张，其余维持原序，添加页仍单独位于末尾。列表顺序使用 remember 缓存，并以排序后的设备 ID 序列作为分页重建键；实际当前设备继续通过 ID 查找页码，避免排序后沿用旧下标造成卡片/连接目标不一致。清除默认后恢复原绑定顺序。仅调整首页显示顺序，不修改存储顺序、默认设置、实际连接或点击连接行为；此次低影响 UI 变更未新增或重复运行 JVM 测试。


1.9.10 Debug/Release 构建、两套 Lint 与 APK 签名检查通过（各 0 errors / 26 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.10/37，沿用原 Debug/Release 证书，Release 不可调试。静态检查确认分页与连接按钮共用排序后的设备列表，按 ID 映射当前连接，添加页计数/位置保持不变。ADB 无设备，尚未真机验证 pin 变更后的首页排序、当前页及点击连接。

验证日期：2026-10-22。Android 1.9.9（versionCode 36）在首页 DeviceConnectionCard 设备名旁增加 ic_pin 图钉，与管理页同为主题 primary 色、16dp 图标和 6dp 名称间距。仅当该卡片确有设备且 ID 等于 defaultComputerId 时显示，绑定在线/离线均可标记，添加电脑页不标记；长名称按权重为图钉预留空间并单行省略。增加“默认连接设备”辅助语义，非当前页预览继续由原容器清除辅助语义。只改变标记显示，不修改分页顺序、连接触发或默认存储；此次低影响显示变更未增加或重复运行 JVM 测试。


1.9.9 Debug/Release 构建、两套 Lint 与签名校验通过（各 0 errors / 26 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.9/36，沿用原 Debug/Release 证书，Release 不可调试。静态检查图钉以卡片 ID 匹配默认 ID，添加页不显示；ADB 无设备，未真机验证首页图钉、长名称和切换默认后的显示。

验证日期：2026-10-22。Android 1.9.8（versionCode 35）在 DeviceManagementCard 右侧统一提供连接动作按钮：离线设备显示 ic_link，当前已连接/连接中显示原 ic_unlink 并分别说明断开/停止。点击携带该行稳定设备 ID，复用原 deviceConnectionAction；忙碌期间禁止重复请求，跨设备连接继续按 deviceSwitchBlocked 禁用，后台引擎仍复核传输/队列。按钮独立于行点击和长按选择，不通过行选择自动连接；连接失败沿用设备动作错误提示。

管理列表显示时稳定分组，将 defaultComputerId 对应设备移至第一，其余按原有顺序显示；默认变更/进入管理后滚动到首行。名称旁增加主题 primary 色 16dp 图钉，提供“默认连接设备，已置顶”语义，长名称为图标预留空间且单行省略。pin 继续决定下次启动默认连接，未修改当前连接、存储顺序或首页分页顺序；取消默认后管理列表恢复原顺序。此次仅调整管理 UI，未新增或重复执行低影响 UI 的 JVM 测试。


1.9.8 Debug/Release 构建、两套 Lint 及签名检查通过（各 0 errors / 26 warnings）。根目录 APK 与对应输出 SHA-256 一致，版本 1.9.8/35，沿用原 Debug/Release 证书，Release 不可调试。静态检查确认管理列表连接动作按行 ID 调用引擎、默认设备稳定分组首位、图钉不占用右侧连接按钮。ADB 无设备，尚未真机验证两台电脑连接/断开、修改 pin 后置顶和长名称图钉显示。

验证日期：2026-10-22。Android 1.9.7（versionCode 34）按用户要求移除卡片之间的设备管理/添加电脑等所有操作胶囊。DeviceHeaderSwipe 删除胶囊 UI、动作回调参数、Action 锚点、额外 88dp 行程及展开/收起辅助动作，仅保留当前/上一页/下一页锚点；前后翻页行程均为卡片宽度加 8dp，保留原生拖动、速度判断与弹簧吸附。删除不再使用的 DeviceSwipeLayout 及其三项胶囊布局测试。

顶部卡片右侧连接按钮继续显式调用连接，滑动仅修改本地浏览页；卡片点击/长按管理、末尾完整添加页和管理页添加入口保留。页码辅助文案同步移除短滑展开说明，继续支持上一台/下一台辅助操作。仅删除 UI 操作区，未修改连接引擎、存储和传输协议；此次未增加或重复运行低影响 UI 变更的 JVM 测试。


1.9.7 Debug/Release 构建、两套 Lint 与 APK 签名校验通过（各 0 errors / 26 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.7/34，沿用原 Debug/Release 证书，Release 不可调试。静态检查确认已无操作胶囊参数、Action 锚点或短滑展开文案。ADB 无设备，未真机验证滑动浏览、连接图标及保留的管理/添加入口。

验证日期：2026-10-22。Android 1.9.6（versionCode 33）修复用户截图中操作胶囊与下一张添加卡片重叠的问题。根因是上一版下一页位移只按整卡宽度加 8dp 间距计算，在短滑 88dp 操作锚点时，下一张已进入当前窗口；胶囊仍固定于右侧并以透明度覆盖预览，造成图标/文字叠印。现在统一使用 DeviceSwipeLayout 计算原生锚点及图层位置：下一页行程包含操作区，短滑锚点时下一张仍在视口外；超过操作锚点后，胶囊跟随当前卡片左移，在前后卡片之间保持独立间隔，不再通过叠加淡出来掩盖冲突。胶囊整体移出视口后移除组件，避免残留点击/语义目标；右滑上一台保留原整卡行程。连接触发、设备绑定存储和传输协议均未改动。

新增 DeviceSwipeLayoutTest 三项回归测试，以不同屏幕宽度/密度逐步遍历短滑与完整翻页位移，验证短滑时邻卡不进入视口、胶囊与前后卡片不相交、完成翻页时邻卡对齐且胶囊移出，以及反向翻页不显示当前操作。该测试验证位置关系，不代替 Compose 绘制和真机触摸验证。


1.9.6 Debug/Release 构建、两套 Lint 及签名验证通过（各 0 errors / 26 warnings）。JVM 共 82 项，77 通过、5 项缺少外部 fixture/二维码图片而跳过，0 failures/errors，包括新增 3 项回归测试。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.6/33，沿用原 Debug/Release 证书，Release 不可调试。ADB 无设备，尚未真机验证截图场景及不同字号下的实际绘制/点击/手势；此次未重复运行 Windows 协议互通 fixture。

验证日期：2026-10-22。Android 1.9.5（versionCode 32）将首页顶部设备条的 HorizontalPager 替换为 Compose Foundation AnchoredDraggable，使用原生横向触摸判定、速度计算与弹簧吸附。短距离左滑在 88dp 操作锚点停留，露出主题 primaryContainer 圆角胶囊；继续左滑到下一页锚点，或右滑到上一页锚点，仅更新本地浏览页。相邻卡片随手指位移，有 8dp 间距；松手按原生锚点吸附或回到原位。边界不创建越界目标，单台绑定仍有添加页，未绑定直接保留添加入口。

离线设备操作胶囊调用连接，已连接设备打开普通设备管理，添加页打开原扫码/手动入口；右侧连接图标和长按进入管理保留。连接请求期间禁用滑动与重复连接，跨设备连接/添加仍受原传输与排队保护；拖动与落页不改变引擎、实际目标或默认设备。点击滑开的卡片/返回键收起；辅助功能提供上一台/下一台、展开/收起操作。相邻预览清除辅助语义且无点击回调，连接回调仅存在于显式按钮操作；位移在布局阶段、透明度在图层阶段读取，避免逐帧重组整个设备头部。未修改绑定存储或传输协议。


1.9.5 Debug/Release 构建及两套 Lint 通过（各 0 errors / 26 warnings）。JVM 单元测试 79 项，74 通过、5 项因未配置 Windows fixture/二维码图片而跳过，0 failures/errors；此次仅涉及 UI，未重新运行协议互通 fixture。根目录 APK 与对应输出 SHA-256 一致，版本 1.9.5/32，沿用原 Debug/Release 证书，Release 不可调试。ADB 无设备，未安装或真机验证触摸跟随、长短滑阈值、松手吸附、快速反向、无障碍及真实连接点击；上述构建/单元测试不代表真机交互验证。

验证日期：2026-10-22。Android 1.9.4（versionCode 31）将管理页标题固定为“设备管理”，所有入口清空选择；正常状态显示返回箭头，隐藏默认/删除/更多选择工具栏，列表不添加选中语义、勾选徽标或高亮。点击列表项打开按 ID 定位的设备设置底部面板，提供连接、默认连接切换、重命名、复制 IP 与确认删除；保留卡片右侧独立断开入口。

列表改用原生 combinedClickable，长按才进入多选；多选模式保留设备管理标题，以副标题显示数量，显示原工具栏。退出选择或返回键先清空选择，普通管理状态再返回主页。主页点击/长按设备条和设置里的设备管理入口统一以空选择进入；未绑定时管理页仍可添加电脑。仅调整管理 UI 和入口，未修改引擎、存储或传输协议。

1.9.4 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 26 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.4/31，复用原 Debug/Release 证书，Release 不可调试。静态检查各入口不再预选设备，只有列表长按或选择态点击修改选择；此次未重复运行存储/协议单元测试。ADB 无设备，尚未真机验证普通管理入口、设备设置面板、长按进入多选及返回层级。

验证日期：2026-10-22。Android 1.9.3（versionCode 30）移除顶部分页滚动/停止观察器及其连接回调，HorizontalPager 只修改自身页码；增加未连接设备右侧独立 48dp 连接 IconButton。只有按钮 onClick 启动连接协程，按设备 ID 调用原 deviceConnectionAction(false)，覆盖当前设备重连和跨设备切换；连接中及请求中禁用重复点击，跨设备连接继续受引擎收发/排队保护。纯浏览可在传输期间进行，滑到添加页不连接也不改变实际目标。

连接状态仍从实际 selectedComputerId/connected 推导，不把当前浏览页当作已连接。发送区标题改为“发送到 实际设备名”，长名称单行省略，未连接时保持“发送”。保留长按管理、添加页、默认启动连接和管理卡片右侧断开。此次仅调整 UI 触发路径，未改动引擎、存储或传输协议；静态检查确认设备条不存在 snapshotFlow/滑动回调连接，仅连接按钮触发 connectComputer。

1.9.3 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 26 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.3/30，复用原 Debug/Release 证书，Release 不可调试。此次未重复运行存储/协议单元测试；ADB 无设备，尚未真机验证只浏览不连接、按钮点击后的连接/重连及双设备发送目标显示。

验证日期：2026-10-22。Android 1.9.2（versionCode 29）按截图将断开连接移到 DeviceManagementCard 右侧，替换原默认图钉标记；仅当前已连接/连接中的设备显示独立 48dp IconButton，分别提供“断开连接”/“停止连接”无障碍说明，操作中禁用重复点击，卡片选择回调与该按钮分离。动作携带对应设备 ID，复用原引擎校验与断开逻辑；默认连接仍由顶部图钉设置/取消。

三点菜单在当前连接活跃时不再提供重复的断开/停止项，离线设备仍提供连接设备，重命名和复制 IP 保留。此次未更改引擎、绑定存储或传输协议。

1.9.2 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 27 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.2/29，复用原 Debug/Release 证书，Release 不可调试。此次仅调整入口位置，未重复运行存储/协议单元测试；ADB 无设备，尚未真机验证按钮位置、独立点击及断开/停止操作。

验证日期：2026-10-22。Android 1.9.1（versionCode 28）删除 DeviceConnectionCard 的 AnimatedVisibility 展开体、绑定说明/分隔线/内嵌菜单/操作按钮、旋转箭头、展开状态及旧解除绑定确认 UI。保留设备分页、紧凑名称/状态、页码与添加页，设备行点击/长按统一进入管理；无绑定的行直接打开添加选择。设置中的连接入口改为设备管理/首次添加，避免引用已移除的展开面板。

设备管理三点菜单新增按目标 ID 执行的连接、停止连接或断开操作，复用原切换/重连/断开路径；停止操作在引擎锁内校验仍是目标设备，避免旧菜单断开另一台电脑。原复制 IP/改名、默认连接和确认删除保留。添加面板只在已绑定且存在传输/排队时阻止添加，首次未绑定时可继续为排队分享配对；未改动绑定存储或传输协议。

1.9.1 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 27 warnings，新增 4 项为移除展开区域后不再使用的图标资源）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.1/28，复用原 Debug/Release 证书，Release 不可调试。静态检查确认不再有 pairExpanded、ConnectionInfo、DeviceMenu 或旧展开文案引用；此次未重复运行存储/协议测试。ADB 无设备，尚未真机验证简化布局及迁移后的设备设置操作。

验证日期：2026-10-22。Android 1.9.0（versionCode 27）在设备条增加原生 combinedClickable 长按管理入口，原点击展开与左右分页保留。长按选中该设备并以 Crossfade 展开全部绑定的滚动列表；顶部替换为退出、选中数量、设为/取消默认连接、删除和设备设置菜单。列表点击多选，单选支持默认连接/改名/复制 IP；删除以固定 ID 快照确认，多选原子移除，返回键退出管理。管理列表显示真实连接状态与默认图钉；不自动连接被选中的行。

“pin”按用户澄清实现为默认连接，独立于当前连接和排序。新增加密存储格式 v2，读取 v1 时将其最后选择迁移为默认，单凭据迁移保留；v2 保存默认 ID，取消后保持空值，新增/滑动不覆盖默认。启动先持久化选择默认，避免后续编辑仍作用于旧选择；无默认时普通打开不自动连接，主动分享和手动重连保持原行为。修改默认/编辑非当前设备只更新元数据；批量移除当前设备前复核传输/队列锁，移除非当前设备不关闭当前连接，默认设备被移除后清空默认，其他绑定保留。

新增 DeviceManagementTest 8 项和存储仓库 3 项测试，覆盖默认/当前分离、取消默认、启动目标及后续编辑一致性、旧 v1 密文迁移、非当前设备改名、批量移除/无效目标原子失败、默认删除清空及默认/删除写入失败缓存不变。运行 scripts/test-interop.ps1：共 79 项，78 通过，1 项缺少二维码测试图片而跳过，0 failures/errors；Windows 文本/文件回执、发现和文件夹 fixture 通过。仓库测试使用内存 SharedPreferences 和 JVM AES，不代替 Android Keystore 或实际长按/选择/连接测试。

1.9.0 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 23 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.9.0/27，复用原 Debug/Release 证书，Release 不可调试。ADB 无设备，尚未真机验证长按管理/多选、顶部工具栏、取消默认后的实际启动、批量删除和 v1 Android Keystore 存储迁移；未安装到手机或改动实际用户绑定数据。

验证日期：2026-10-22。Android 1.8.1（versionCode 26）在有绑定时将顶部 HorizontalPager 页数改为设备数 + 1，末尾为带加号的“添加电脑”页；单设备也启用横向滑动，未绑定时仍保留原首次连接界面。添加页无设备 ID，不调用 switchComputer，也不登记到绑定存储；选择页提供扫码与手动输入两种方式，取消不改变原连接。滑到添加页时隐藏当前设备展开详情/菜单，滑回恢复，保持原生分页和收起动画；页码与无障碍说明包含操作页。

手动连接的启用条件从“未连接”调整为“不在连接中，且已绑定时无收发/待发送任务”，因此已有连接也可确认另一台电脑的连接信息，首次未绑定时排队分享仍可配对。新增入口沿用现有扫码、手动输入和认证后保存流程，传输期间仍锁定滑动/添加；未修改加密存储或传输引擎。

1.8.1 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 23 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.8.1/26，复用原 Debug/Release 证书，Release 不可调试。此次只修改分页操作入口及手动面板启用条件，未重复运行存储/协议单元测试；ADB 无设备，尚未真机验证单台左滑、添加页返回、添加/取消及扫码/手动新增流程。

验证日期：2026-10-22。Android 1.8.0（versionCode 25）使用 Compose 原生 HorizontalPager 实现顶部设备条左右翻页，滑动停止后才选择并连接目标电脑，拖回原页不重连。单设备不启用滑动，多设备显示页数，头部点击展开/折叠和此前紧凑行间距保留；列表变化按不含凭据的稳定 ID 重建分页，程序切换/恢复选择会同步页码，失败时回到当前设备。添加电脑仍用扫码，旧绑定保留；重命名和解除绑定只作用于当前设备，移除后保留其他设备并选择相邻设备（不自动连接）。

底层将单条凭据改为包含全部设备和最后选择的版本化加密存储，复用原 Android Keystore 密钥、AES-GCM 随机 IV 与独立 AAD。首次读取迁移旧凭据及备注，写入新记录成功才移除旧键；UI 仅接收随机 ID、名称与地址，配对码不进入 Compose/Activity 保存状态。身份以已有配对凭据识别，重新连接和地址变化不新增重复页。切换先持久化选择，再关闭原连接并按所选身份重新发现/认证；新扫码只有成功握手并保存后才登记。收发/保存中或分享队列不为空时锁定切换与移除，队列计数和连接操作在引擎锁内复核。连接清理、重试状态和心跳创建均按连接代次并在锁内处理，防止旧连接收尾改写新连接。

新增 ComputerBindingsTest 10 项与 ComputerStorageTest 6 项，覆盖旧格式迁移、多个独立名称、地址更新/去重/稳定顺序、加密恢复末次选择、单项/末项移除、反复选择、错误身份、密文篡改/错误密钥/格式、传输锁定，以及生产存储仓库的迁移写入失败保留旧数据、选择/删除持久化失败不更新缓存、重读与损坏新数据不回退旧凭据。存储仓库测试使用内存 SharedPreferences 适配器和 JVM AES 密钥，不代表真实 Android Keystore/磁盘/UI 验证。运行 scripts/test-interop.ps1 后共 68 项，67 通过、1 个未提供二维码图片的用例跳过，0 failures/errors；真实 Windows 文本/文件回执、TCP/UDP 发现与文件夹 fixture 通过，未修改 Windows 协议或程序。

1.8.0 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 23 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.8.0/25，复用原 Debug/Release 证书，Release 不可调试。ADB 无设备，尚未真机验证滑动/回弹/折叠、两台实体电脑切换、Android Keystore 旧记录迁移及进程重启恢复；模型/仓库和 Windows fixture 检查不能替代这些验证。

验证日期：2026-10-22。Android 1.7.3（versionCode 24）将连接卡片中设备名/状态的文字间距由 6dp 改为 2dp，绑定标题/说明由 5dp 改为 2dp；对应标题/副文行高设为 20sp/18sp，收紧默认行高带来的空白。保留原字号、图标、按钮点击范围、菜单及连接行为，仅调整这两组文字的垂直排版。

1.7.3 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 25 warnings）。根目录 APK 与对应构建输出 SHA-256 一致，版本 1.7.3/24，复用原证书，Release 不可调试。此次仅调整文字间距/行高，未新增或重复运行单元测试；ADB 无设备，尚未真机检查显示效果。

验证日期：2026-10-22。Android 1.7.2（versionCode 23）按参考图简化连接卡片：头部移除 IP 行，保留单行设备名与连接状态，展开箭头移至头部右侧；设备三点菜单从头部移至展开区绑定说明右侧。连接与已绑定离线状态共用说明/菜单结构，保留复制 IP、重命名设备、解除绑定及其原启用条件，解除绑定仍使用主题错误色和确认框。收起卡片时关闭菜单，保留原生展开/收起、箭头旋转和点击涟漪。切换电脑/断开连接按钮及未绑定的扫码/手动连接入口保留。

1.7.2 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 25 warnings）。两个根目录 APK 与对应构建输出 SHA-256 一致，版本 1.7.2/23，分别复用原 Debug/Release 证书，Release 不可调试。此次仅调整卡片布局与菜单位置，未重复运行传输/重命名单元测试；ADB 无设备，未真机验证视觉布局、折叠动画与菜单点击。

验证日期：2026-10-22。Android 1.7.1（versionCode 22）按参考图调整设备卡片：名称旁显示旋转展开箭头，IP 与连接状态纵向排列，右侧设备菜单提供复制 IP、重命名、解除绑定。菜单独立于展开点击区域，解除绑定使用主题错误色及原有确认框，展开区保留切换电脑和断开连接。主页全局更多菜单不恢复。

重命名使用原生 Material 3 对话框，支持留空恢复默认名称、去除首尾空白、最多 32 个 Unicode 码点、拒绝换行及控制字符，并在对话框内报告保存错误。备注随当前绑定保存，同一配对身份重连/IP 变化保留，切换绑定/解除绑定清除；引擎序号防止过期对话框修改新设备，编辑草稿和序号可跨 Activity 重建保存。配对凭据继续使用原有 Android Keystore AES-GCM 加密，未更改传输协议。

新增 DeviceNamesTest 5 项通过，覆盖规范化/默认名称、Unicode 长度及非法输入、同一身份地址变化、切换身份不继承旧备注。JVM 共 52 项，47 通过、5 项依赖外部 fixture/二维码图片的测试跳过，0 failures/errors；此次未重复运行 Windows 互通 fixture。该套测试不执行 Android SharedPreferences、Keystore 或真实菜单点击。

1.7.1 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 25 warnings，新增项为 SharedPreferences.edit 的 KTX 风格建议）。两个根目录 APK 与对应构建输出 SHA-256 一致，版本 1.7.1/22，分别复用原 Debug/Release 证书，Release 不可调试。ADB 无设备，未真机验证卡片布局、菜单点击、进程重启后名称恢复及设备切换流程。

验证日期：2026-10-22。Android 1.7.0（versionCode 21）按三张参考图增加独立接收 SnackbarHost 和带细边框的接收卡片。接收中显示蓝色进度、单行名称/来源、百分比、已收/总大小和真实保存目录；成功显示绿色图标与“查看”；失败显示红色图标与“详情”。查看使用 ACTION_VIEW、文件 MIME、ClipData 与临时读取授权，没有可打开的应用时显示说明；详情用原生对话框显示完整文件名、已收字节及原因。收发卡片分别管理，在同一个底部容器中排列，进度更新不重启提示时长。未更改设备卡片、发送区及接收历史布局。

引擎按文件头创建独立接收标识，实际写入后更新进度；普通文件 SHA-256 验证通过且 MediaStore 发布成功，或完整文件夹提交成功后，才登记成功 URI 和查看入口。保存阶段与主动断开通过锁内状态转换确定先后，保存已开始则等待实际存储结果，避免回执网络错误把已保存文件标为失败。读取、校验、权限、空间、目录提交和远端取消异常显示失败，原有不完整内容清理保留；旧标识更新不会覆盖新文件卡片。文件夹拒绝接收后可继续使用原连接。

新增 ReceiveProgressTest 6 项通过，覆盖收齐字节与发布状态分离、断线后不提供查看、不允许未完成内容进入保存、已发布内容不被后续网络失败改写、空文件、非法/倒退进度和目录实际名称。执行 test-interop.ps1 后 JVM 共 47 项，46 通过、1 个未提供二维码图片的用例跳过，0 failures/errors；真实 Windows TCP/UDP/文件夹及现有发送回执 fixture 均运行。上述测试不涉及 Android MediaStore 或真机 UI，不能替代手机端保存与按钮点击验证。

1.7.0 Debug/Release 构建、两套 Lint 和签名验证通过（各 0 errors / 24 warnings，新增项为 toUri 风格建议）。两个根目录 APK 与对应构建输出一致，版本 1.7.0/21，分别复用原 Debug/Release 证书，Release 不可调试。ADB 无设备，尚未真机验证接收卡片、同时收发时的布局、文件/目录保存和查看/详情按钮。

验证日期：2026-10-22。Android 1.6.3（versionCode 20）按截图删除设置面板中圈出的解除绑定按钮及“普通文件保存至 下载 / LanBridge；文件夹使用授权目录。”说明行，保留两个设置选项和版本信息。设备卡片中的解除绑定与原确认框保留，未更改保存目录或传输逻辑。

1.6.3 Debug/Release 构建、两套 Lint 及签名验证通过（各 0 errors / 23 warnings）。根目录 APK 与构建输出一致，版本 1.6.3/20，分别复用原证书，Release 不可调试。此次仅删除设置面板两处显示，未重复运行传输测试；ADB 无设备，未真机验证。

验证日期：2026-10-22。Android 1.6.2（versionCode 19）删除窄屏/宽屏主页的保存位置卡片、其组件实现，以及 TopAppBar 的三点按钮、菜单和对应状态变量。设置中的接收目录选择保留；将原顶部菜单提供的离线解除绑定入口移至设置，继续使用同一个确认框。断开/重连/扫码由设备卡片提供，清空记录由“最近收到”提供；接收条目的更多菜单不受影响。

1.6.2 Debug/Release 构建、两套 Lint 及签名验证通过（各 0 errors / 23 warnings）。两个根目录 APK 与构建输出一致，版本 1.6.2/19，分别复用原证书，Release 不可调试。确认主页无 FolderDestination 调用、顶部更多按钮或 topMenu 状态。仅删除界面与调整入口，未重复运行传输测试；ADB 无设备，未进行真机验证。

验证日期：2026-10-22。Android 1.6.1（versionCode 18）按参考图将发送区两个 FilledIconButton 改为透明底 IconButton，加号 20dp，圆圈上箭头 24dp，保留 48dp 点击区域及原有启用条件；移除“支持多选文件…”和“正在发送，请稍候…”固定说明行，保留有任务时的分享排队数量。文本输入、粘贴及文件选择回调沿用原逻辑。

设置中的两个 ListItem 改为与选择面板共用的 SheetOption，包含 20dp 圆角、52dp 彩色圆形图标底、统一字号/行间距/内边距和右侧箭头；两处均用 SheetContent 统一标题、16dp 外边距、10dp 行间距和滚动行为。原有连接设置及接收目录入口不变。图片、文件和文件夹选项的配色由同一个枚举决定，深色模式使用原有对应配色。

1.6.1 Debug/Release 构建、两套 Lint 与签名验证通过（各 0 errors / 23 warnings，其中新增 1 项为原箭头图标不再使用）。两个根目录 APK 与构建输出一致，版本为 1.6.1/18，分别复用原证书，Release 不可调试。此次只调整 UI 和复用组件，未重复运行传输测试；ADB 无设备，尚未进行真机视觉或点击验证。

验证日期：2026-10-22。Android 1.6.0（versionCode 17）按三态参考图重做连接卡片：头部白色图标底、名称/地址、状态图标和旋转箭头；展开内容与头部共用灰色圆角底，以分隔线分开。连接、已记住离线和未绑定分别显示相应说明及操作。删除原 Hero/PairCard 两张卡片结构，保留原生 AnimatedVisibility 的展开、收起与淡入淡出。未加入参考图中的预览说明文字或示例 IP。

所有按钮连接到现有引擎/扫码流程：已连接时扫描其他电脑保留原连接，取得有效二维码后 engine.connect 才替换；离线搜索和打开手动面板先停止自动重试，保留绑定。手动连接在 ModalBottomSheet 中校验 Pairing，面板内显示错误，退出清除配对码；输入不写入保存状态。解除绑定有原有确认框，并在顶部更多菜单保留离线入口。连接成功后同步最近保存地址，卡片离线地址使用绑定地址而非手动输入的草稿。底部普通状态提示不恢复。

1.6.0 Debug/Release 构建、两套 Lint 与签名验证通过（各 0 errors / 22 warnings）。JVM 测试共 41 项，36 项通过、5 项未配置外部 fixture 而跳过，0 failures/errors；包含手动面板复用的 Pairing 校验。两个根目录 APK 与构建输出一致，版本 1.6.0/17，分别复用原有证书，Release 不可调试。ADB 未发现设备，未实测三态布局、展开动效、扫码取消保留连接和底部手动面板；本次没有再次运行跨端 fixture 或修改 Windows 程序。

验证日期：2026-10-22。Android 1.5.4（versionCode 16）将发送卡片准备、发送、确认及成功副标题限制为单行（softWrap=false、maxLines=1、Ellipsis），超长文件名不再撑高卡片；失败原因允许两行。将 Surface.shadowElevation 从 2dp 改为 0dp：本机 Compose Animation 1.12.1 的 AnimationModifierKt 字节码确认 animateContentSize 调用 clipToBounds，原外层动画会裁剪内部 Surface 阴影，符合截图中的底部阴影截断现象；移除阴影后没有该阴影可被裁剪。导航栏系统手势区域的安全边距保持由 Scaffold 管理。

1.5.4 Debug/Release 构建与两套 Lint 均通过（各 0 errors / 22 warnings）。根目录 APK 与构建输出一致，签名分别复用原证书，版本为 1.5.4/16，Release 不可调试。此次仅调整文本布局和阴影，未重复运行传输测试。ADB 无设备，尚未验证真机上的长文件名、卡片底部与手势区显示。

验证日期：2026-10-22。Android 1.5.3（versionCode 15）按用户截图完全删除 Scaffold 的旧 bottomBar，底部不再绘制 state.status、progressLabel 或旧式进度条。此前 1.5.2 已删除通用状态文字，本次连剩余底部容器与接收进度一并移除。独立发送 SnackbarHost 和三种发送卡片保留。

1.5.3 Debug/Release 构建、两套 Lint 及签名验证通过；各 0 errors / 22 warnings。两个根目录 APK 与构建输出一致，版本 1.5.3/15，分别复用原签名，Release 不可调试。确认主界面没有 bottomBar、state.status 或 progressLabel 渲染入口。仅做显示删除，未重新运行传输测试；ADB 无设备，未进行覆盖安装或真机视觉验证。

验证日期：2026-10-22。Android 1.5.2（versionCode 14）按真机截图精简界面：发送卡片副标题仅保留文件名和多选序号，移除重复百分比，右侧百分比与蓝色进度条保留；删除底部常驻的连接/绑定状态文本，空闲和发送期间不再为该状态栏保留高度。底部仅在接收未完成时显示接收进度，顶部设备连接卡片保留。

1.5.2 Debug/Release 构建与 Lint 均通过（各 0 errors / 22 warnings），两个根目录 APK 与构建输出一致，版本 1.5.2/14；签名分别复用原证书，Release 无 application-debuggable。此次仅调整显示内容与布局，没有重新运行传输测试。ADB 无已连接设备，未做新版本的真机视觉检查。

验证日期：2026-10-22。Android 1.5.1（versionCode 13）按三张参考图制作发送中、成功、失败卡片，使用 Scaffold 的 SnackbarHost 承载。活跃任务用 Indefinite 时长，进度更新不重建 Snackbar；成功用 Short，失败用 Long，提示持续时间遵循 Material 的无障碍时长处理。卡片采用白色圆角底、蓝色上传图标/线形进度、绿色成功图标与双勾、红色错误图标；进度条使用 Compose 数值动画，卡片高度和出入使用 Compose/Material 原生动画。文件名、当前项序号和百分比来自发送状态，接收进度继续单独显示。

发送任务现在等待 Windows 确认：普通文本及文件等待现有 type 6 回执，文件夹等待 type 15 提交成功及随后的 type 6，全部项目确认后显示成功。注册等待发生在发包前，防止快速回执丢失；等待按 Wire 对象隔离，旧连接不能确认新连接的任务。断线、主动断开、准备/读取失败与确认超时会显示失败卡片；确认超时关闭原连接。未增加重试、暂停或断点续传。原工作区已有的加号选择面板、图片选择器及文件/文件夹入口保留。

JVM 回归共 41 项，40 通过、1 个未提供外部二维码图片的用例跳过，0 failures/errors。新增 6 项 SendReceiptGateTest 验证快速回执、写出后继续等待、旧连接隔离、写入失败清理、超时清理及文件夹确认后再发送下一项；新增 SendReceiptInteropTest 使用生产 Wire/SendReceiptGate 和真实 Windows 测试服务，验证文本、空文件和 262145 字节分块文件连续收到确认，字节、SHA-256 及后续心跳一致。真实 TCP/UDP/文件夹互通 fixture 已运行，测试不改写 Windows 剪贴板。ADB 未检测到设备，尚未进行手机上的 Snackbar 显示、自动消失、键盘/旋转和深色模式视觉验证。

1.5.1 Debug/Release 构建及两套 Lint 均通过（各 0 errors / 22 warnings，其中 3 项为现有发送区调整后未使用的图标资源）。两个根目录 APK 与对应构建输出的 SHA-256 一致，apksigner 校验通过，分别复用原有 Debug/Release 证书；aapt 确认 1.5.1/13，Release 没有 application-debuggable 标志。最终仅补充连接错误时先标记离线再释放发送忙状态，避免确认超时后队列抢先使用已关闭连接；重新完成两套构建与 Lint。未安装到手机。

验证日期：2026-10-21。Android 1.5.0（versionCode 12）按用户参考图重做主界面：固定中性配色、紧凑设备卡片、文本输入区与粘贴图标、横排文件/文件夹发送按钮、保存位置卡片、带原生矢量图标和更多菜单的接收记录。新增设置底部面板与顶部更多菜单，保留断开/重连、扫码、手动连接、解除绑定、清空记录、文件打开/分享及文字预览/复制入口。沿用 Compose 连接面板动画与三次接收高亮；深色模式和宽屏双栏保留。授权目录名称在 IO 线程读取，普通文件与文件夹的实际保存路径策略未改变。

Debug/Release 构建与 Lint 完成；现有 JVM 测试 34 项中 30 项通过、4 项外部 fixture 用例跳过，0 failures/errors。根目录 APK 更新为 1.5.0/12，分别复用原有 Debug/Release 签名，Release 无 application-debuggable 标志。ADB 未检测到设备，本机未安装模拟器，尚未进行新版布局、菜单点击和动画的设备视觉验证；构建与单元测试不代表这些交互已在手机上实测。

验证日期：2026-10-21。Android 1.4.3（versionCode 11）将连接设置改为状态条下方的折叠面板：连接成功默认收起，连接断开默认展开，点击横条切换。展开状态保存在 BridgeScreen，按 connected 状态切换重置，普通状态消息及传输进度更新不重置；宽屏和窄屏共享此状态。使用 Compose AnimatedVisibility 的 expandVertically/shrinkVertically 与 fadeIn/fadeOut，展开方向从状态条向下、收起方向向上；间距包含在动画区域内，收起后不留空白。状态条使用 Material 可点击 Surface，带涟漪、原生数值动画旋转箭头及无障碍状态描述。宽屏左栏增加滚动以保留完整设置入口。

Debug/Release 构建、两套 Lint 完成；JVM 测试 30 通过、4 外部 fixture 用例跳过，0 failures/errors。两个根目录 APK 更新为 1.4.3/11，分别保留原签名；Release 不可调试。ADB 未检测到设备，尚未进行实机点击、自动连接收起和断开展开的视觉验证。实现依据 [Compose 内置出现/消失动画](https://developer.android.com/develop/ui/compose/animation/composables-modifiers)，没有自定义动画计时循环。

验证日期：2026-10-21。Android 1.4.2（versionCode 10）新增“最近收到”卡片三次高亮：每次 300 ms 亮起、300 ms 淡回，共三次，正常动画比例约 1.8 秒。背景、文字及边框颜色按当前主题插值，动画结束恢复默认；接收列表仅在完整接收后添加记录，因此未完成文件不会触发。每条 Received 分配独立 UUID，宽屏和窄屏列表均按 UUID 保持卡片身份；rememberSaveable 保存该记录已提示状态，避免普通重组、插入新记录或滚动返回时重复播放。动画开始后卡片离开组合会取消剩余动画，返回不会重新闪三次。

Debug/Release APK 构建与两套 Lint 均完成；现有 JVM 测试 30 通过、4 外部 fixture 用例跳过，0 failures/errors。两套 APK 签名验证通过，分别复用各自上一版证书；aapt 确认版本为 1.4.2/10，Release 无 application-debuggable 标志。根目录两个 APK 均已更新，可分别覆盖同签名旧版。本次 ADB 无已连接设备，没有执行实机动画、滚动及屏幕旋转的视觉验证。动画和列表状态实现参考 [Compose 值动画](https://developer.android.com/develop/ui/compose/animation/value-based) 与 [Lazy 列表条目标识](https://developer.android.com/develop/ui/compose/lists#item-keys)。

验证日期：2026-10-21。新增 Android 1.4.1 Release APK（versionCode 9），根目录文件名为 LanBridge-Android16-release.apk，原 Debug APK 保留且 SHA-256 未变化。build-android.ps1 增加 -Release，使用项目目录外的长期发布密钥，密码以当前用户 DPAPI 形式保存在本机；重复执行复用签名。

`build-android.ps1 -Release -Test` 成功执行 assembleRelease、lintRelease，并复用未变更的 Debug JVM 测试结果（30 通过、4 外部 fixture 用例跳过）。apksigner 验证 APK v2 签名通过，证书为 CN=LanBridge Android Release，RSA 3072，证书 SHA-256 为 `37fec7695bf577640536964379e364dfc8ece1d6059165e579494649032b7305`，与 Debug 签名不同。aapt 检查实际 Release APK 中没有 application-debuggable、debuggable 或 testOnly 标记；包名 dev.lanbridge、版本 1.4.1/9、minSdk 31、targetSdk 36 正确。未开启 R8 混淆/压缩。未安装到手机，也未卸载现有应用；因为签名不同，Release 不能直接覆盖现有 Debug 版。

验证日期：2026-10-21。Android 1.4.1（versionCode 9）修复接收图片的分享类型：从 ContentResolver 获取具体 MIME，缺少具体类型时按扩展名回退，未知内容使用 application/octet-stream；ACTION_SEND 的 EXTRA_STREAM 与 ClipData 指向同一文件，并授予临时读取权限。分享按钮调用此逻辑，图片不重新编码。实现遵循 [Android 分享二进制内容与 MIME 类型说明](https://developer.android.com/develop/ui/compose/sharing/send)。

assembleDebug、lintDebug、testDebugUnitTest、assembleDebugAndroidTest 全部完成。JVM 测试 34 项中 30 项通过、4 项因未配置外部互通或二维码 fixture 跳过，0 failures、0 errors。新增 ReceivedFileShareTest 已编译，覆盖 PNG、JPEG、WebP、GIF、PDF、未知二进制文件的分享类型，以及文件 URI、ClipData、分享面板的读取权限传递；由于 ADB 未检测到设备，此设备测试未执行，也未验证其他 App 的实际展示。APK v2 签名验证通过，证书 SHA-256 与旧 APK 相同，根目录 LanBridge-Android16-debug.apk 已更新为 1.4.1，可覆盖安装。本次未改动手机中的应用或配对数据。

验证日期：2026-10-21。Windows 1.6.5 修复剪贴板及窗口发送入口离线时没有保存待发送任务的问题，并新增剪贴板位图转 PNG。Android 和菜单身份包不变。Release 构建通过；FolderIntegration、ShellIntegration、BridgeIntegration、UiIntegration、QueuedClipboardIntegration 共 5 项相关集成测试通过，0 skipped。

新增 QueuedClipboardIntegration 运行实际 Win32 程序，离线连续点击发送两张 CF_DIB 图片、剪贴板文字和编辑框文字，随后用本地加密客户端完成握手。验证自动按序发送、剪贴板覆盖后原图与原文字保持、PNG 字节及 SHA-256 一致、发送后临时 PNG 删除；再次断线排队第三张图片，重新连接后只发送一次。测试结束恢复原剪贴板。该用例对旧正式 EXE 在“offline clipboard is queued”断言失败，对修复后的 EXE 通过。图片读取直接使用并校验 DIB 数据，避免 CF_BITMAP 自动转换失败。此轮使用协议客户端验证，没有向真实手机发送测试图片。

队列仍只保存在当前进程中；正常退出、重置配对会释放暂存图片，进程被强制结束时可能留下临时 PNG。传输已开始后发生错误不自动重试，原文件引用也不会复制为快照。上述 5 项为本次针对性回归，未重新运行其余启动注册及 Android 测试。

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
