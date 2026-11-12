# 启动器界面稳定性回归

本次修复涉及共享 Compose 界面与桌面窗口，不改变游戏引擎、网络协议或 Android 引擎调用线程。

## 修复依据

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| X 点击无反应 | 关闭按钮只有 30dp，后绘制的整块正文覆盖命中区 | 共用 48dp 图标按钮置顶；地图标题与设置页预留关闭区域 |
| 切页后暂时点不到新页面 | 退出动画保留的旧页遮住新页，包括 Scaffold 内的任务页 | 活动页置顶，退出页降层；旧页关闭其原生弹窗与下拉层 |
| 弹窗关闭后仍挡点击 | 原生 Dialog 等待调用方清 visible，内容已经缩没 | 退出完成即移除原生窗口；不可关闭进度显式禁止外部关闭 |
| PC 已进入沙盒/多人对局，中央仍显示“正在准备并加载地图” | 开局先隐藏 ComposePanel，再提交 RUNNING；隐藏的启动器暂停界面帧，独立加载 Dialog 没有及时销毁 | 开局提示单独使用启动器根容器内的模态遮罩，与宿主同步隐藏；保持加载成功后才切画面、失败不提交 RUNNING 的门控 |
| 点按钮大幅抽动 | 按下缩放到 70%，pointerInput 随按下状态重启 | 用点击组件的 InteractionSource，120ms 轻微缩放到 97% |
| 跨窗口尺寸档位突然变窄 | ExpandedCard 在档位切换时从 95% 变到 75% | 宽度连续响应窗口，最大 1440dp |
| 暗色卡片文字不清楚、表单过大 | 半透明背景继承默认黑色文字，输入框使用标题字号 | 卡片明确使用主题前景色；输入与下拉使用正文字号，保留主题字体 |
| 高 DPI 窗口尺寸来回变化 | CardLayout 写逻辑尺寸，Canvas resize 回调再写物理尺寸 | NativeCanvasCardLayout 单次布局直接使用最终物理尺寸 |
| 挂件闪动或替换不更新 | 每次定位都 pack，挂件引用不是 Compose State | 内容尺寸变化才 pack，挂件替换触发重组，AWT 操作统一 EDT |
| 地图选择挤压、错位 | 固定五列、图片与长名称改变行高、筛选 index 回传错误 | 自适应列、固定卡片行高、保留源 index、宽窄屏工具栏 |
| 桌面地图弹窗/下拉菜单晃动，Skiko 可能崩溃 | Compose 1.10.1 WINDOW 弹层按每帧绘制范围调整 native 窗口，动画与阴影触发绘制期间 resize | Windows 启动时安装固定宿主视口兼容处理，移除逐帧绘制范围录制，宿主 resize 排到绘制结束后 |
| 地图弹窗盖住任务管理器、浏览器等其他应用 | Compose WINDOW 弹层默认将原生 JDialog 设置为系统级 alwaysOnTop | 首次显示前取消系统置顶，通过 owner 关系保持在游戏窗口上方，随游戏进入后台、最小化与恢复 |
| 地图载入失败后无反馈 | 列表/名称解析异常中断界面 | 显示错误并允许刷新重试，保留原调用线程 |
| 玩家列表滚动时列宽抽动 | 滚动状态动画改变 end padding | 固定预留滚动条位置 |
| 玩家改名、换队后行反复重建 | 行 key 包含可变昵称、槽位与排序 index | 每个列表按玩家引用分配唯一 Long key，离开的映射及时释放 |
| 手机竖屏房间可能崩溃 | 外层滚动容器给内层 LazyColumn 无限高度 | 内部玩家列表与消息区使用有限高度 |
| 聊天输入按键不工作或重复发送 | 消费所有按键，Enter 未区分按下与松开 | 仅处理 Enter 按下，其余编辑按键交回输入框 |
| 长按 F11 反复切全屏 | 操作系统自动重复 KEY_PRESSED | 每次按下只切换一次，松开或失焦清除状态 |

## 自动验证

```powershell
.\gradlew.bat :rwpp-core-api:test
.\gradlew.bat :rwpp-core:desktopTest :rwpp-desktop:test
.\gradlew.bat :rwpp-android:assembleDebug
```

`LauncherInteractionUiTest` 验证退出动画期间新页面关闭按钮的实际指针命中、账号/好友叠层、原生弹窗销毁；`SharedWidgetInteractionTest` 验证正文覆盖下关闭按钮、取消按压后再次点击、带权重输入框焦点与编辑；`MapSelectionUiTest` 验证宽窄屏地图布局、源 index、刷新与错误恢复、聊天按键；`NativeCanvasCardLayoutTest` 验证重复布局、切卡和 DPI 切换。既有导航、会话生命周期、主题与账号界面测试同时运行。

地图截图由测试保存到 `rwpp-core/build/reports/ui-stability/`；使用真实 Compose 组件和测试地图数据，不代表真实 Android 设备或完整游戏会话的验证。

## Windows 原生弹层视口

`rwpp-desktop/DesktopWindowLayers.kt` 在第一个 `ComposePanel` 创建前，为当前固定的 Compose 1.10.1 安装兼容处理。可聚焦的原生 `Dialog` / `Popup` 窗口覆盖宿主 Compose 视口，内容与命中范围继续由 Compose 管理；地图空列表、缩略图、阴影及缩放动画不再改变 native 窗口边界。保留 `compose.layers.type=WINDOW`，资源浏览器上的弹窗与悬浮控件仍能覆盖 Chromium 原生子窗口。**非聚焦的浏览器悬浮控件保留按实际内容裁剪的小窗口**，其 resize 同样延后执行，避免扩大鼠标拦截区挡住网页。

所有 WINDOW 弹层在首次显示前取消上游默认的 `alwaysOnTop=true`。原生 `JDialog` 保持游戏窗口的 owner 关系，借此覆盖游戏和 Chromium 子窗口，随游戏进入后台、最小化与恢复；不能使用系统置顶来覆盖其他应用。此规则同样适用于非聚焦浏览器悬浮控件。

透明原生菜单局部使用 Direct3D 时，WINDOW 子层也必须在首次创建 peer 前沿用菜单实际使用的 Direct3D；否则全局 OpenGL 偏好会让详情窗口重新选择 OpenGL，出现遮罩变暗但卡片没有显示的情况。`DesktopWindowLayers` 仅为透明 `ComposeDialog` 的子层选择初始后端，不修改全局设置，后续设备故障仍允许 Skiko 正常回退。Swing / 软件菜单保持原路径，Android 不涉及此兼容处理。

兼容处理在类首次定义前使用现有 Javassist 依赖修改 `WindowComposeSceneLayer`，启动后无需反射或 javaagent。可聚焦弹层不再逐帧录制绘制范围，非聚焦悬浮控件仍按实际内容裁剪。内部字段类型、置顶初始化与绘制范围录制调用的签名与数量均在定义类前校验；不兼容或类已提前加载时记录 `[UI]` 警告并使用原库。升级 Compose 时必须重新验证此入口，不能仅凭编译成功认定仍生效。可见窗口的尺寸更新通过 EDT 合并执行；已销毁窗口不执行迟到更新。

`DesktopWindowLayersTest` 使用真实 Swing、Skiko 和 WINDOW 弹层，覆盖入场动画、下拉菜单、空列表切换、宿主 resize/move、关闭后迟到更新，以及非聚焦悬浮控件保留小窗口。同时检查可聚焦/非聚焦弹层均不系统置顶，使用 Win32 窗口顺序验证无 owner 的普通窗口可以遮住游戏弹窗、游戏弹窗仍位于宿主上方，并验证宿主最小化/恢复时原生弹层的可见性。缩放回归可执行：

```powershell
.\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopWindowLayersTest '-Prwjs.test.uiScale=1'
.\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopWindowLayersTest '-Prwjs.test.uiScale=1.5'
.\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopWindowLayersTest '-Prwjs.test.uiScale=2'
```

该测试在非 Windows 或无图形环境时跳过；共享 Compose 离屏测试不覆盖这条 native 窗口路径。

`DesktopModDetailsWindowTest` 使用生产透明菜单和真实模组页，在全局 OpenGL 偏好下检查详情子层实际使用 Direct3D，覆盖动画开关、关闭重开、标题/删除/关闭按钮与卡片绘制。默认向测试自身 Canvas 派发输入并读取 SkiaLayer 位图，不依赖前台权限；这能验证布局、绘制和后端选择，不能替代屏幕实际呈现验收。运行同一测试并传入 `-Prwjs.test.uiScale=2` 可复核 HiDPI。

设置 `RWJS_TEST_VISIBLE_CAPTURE=true` 可追加真实屏幕颜色断言：仅在截图期间短暂提升测试自己的窗口树，随后恢复，不向其他应用发送输入。2026-11-12 的同机对照中，旧实现的 Skia 位图已有详情卡片，但屏幕仅有变暗的列表，动画开关两项均在屏幕断言失败；修复后两项通过，200% 缩放同样通过。截图和后端记录位于 `rwpp-desktop/build/reports/desktop-mod-details/`。真实前台 Robot 输入另以 `RWJS_TEST_ROBOT_INPUT=true` 显式启用，未启用时不将定向事件等同于物理鼠标验收。

## PC 开局提示与画面切换

`MatchStartLoadingOverlay` 只负责开局进度展示，仍由 `MatchStartController` 决定地图加载是否成功。PC 使用启动器整窗根容器内的模态遮罩，进入游戏的同一次 CardLayout 切换即隐藏遮罩；不会额外创建能盖在游戏 Canvas 上的原生 Dialog。Android 继续使用原有 Dialog。账号根层弹窗与此开局提示是显式允许的根容器内遮罩；地图选择、普通弹窗以及浏览器上方的弹层继续保留原生 WINDOW 模式。

`MatchStartLoadingWindowTest` 使用真实 `ComposePanel`、WINDOW 配置、Swing `CardLayout` / 透明菜单宿主和 AWT `Canvas`，复现“先切游戏，再 commit RUNNING”的实际顺序。分别覆盖开启动画的沙盒和关闭动画的多人开局，递归检查对局可见时没有残留原生加载窗，以及返回启动器后提示不会重新出现。既有 `MatchStartRecoveryTest` 和 `MatchStartGateTest` 继续验证开局失败不会提交成功、重复与过期回调不会误开新会话。

## PC 主菜单实时战场

`DesktopLauncherOverlay` 将菜单放在主窗口拥有的透明窗口中，游戏 `Canvas` 始终留在原父容器和原生句柄上。Windows 优先由 `ComposeDialog` 自身承载菜单，通过 `isTransparent=true` 通知原生 SkiaSurface；在首个 peer 初始化期间仅为菜单选择 Direct3D，初始化完成后检查实际后端。普通 `ComposePanel.isOpaque=false` 不会向 Skia 传播窗口透明状态，不能据此直接替代原生透明窗口配置。局部后端选择不改写全局属性或用户配置，显式软件模式、初始化失败及后续设备回退继续使用 `RenderSettings.SwingGraphics()` 兼容菜单。普通透明 Swing 面板会让 AWT 裁掉下层 Canvas，不能用于此处。战场直接由 LWJGL 渲染，不回读游戏帧、不把整屏 Compose 上传为游戏纹理；静止菜单也不随每个游戏帧重绘。

菜单层不使用系统级置顶，跟随宿主内容区移动、缩放、DPI、最小化和显示状态。进入对局隐藏菜单层，返回时恢复键盘焦点；原有 WINDOW 弹窗仍由菜单层拥有。根内容保留极薄的非零 alpha，防止 Windows 把完全透明的页边缘点击穿透给游戏窗口。不支持像素透明窗口的平台回落既有卡片切换和静态背景。

窗口缩放、全屏或 DPI 切换期间，主窗可见性与内容区布局就绪分别判断。宿主仍可见，但 `displayHost` 短暂为零尺寸或尚未 showing 时，保留已显示的菜单，尚未显示的菜单等待有效视口；每 80ms 重试一次，最多 20 次，不能依赖下一次鼠标输入或组件事件恢复。预算耗尽记录 `[UI]` 警告，后续显式 `refresh()` 或组件事件可重新开始重试，不建立常驻重绘循环。

`refresh()` 立即同步边界，并在 120ms、320ms 各补同步一次；全屏切换完成和启动主窗显示后均调用此入口。即使窗口 bounds 未变化，也验证并补绘实际 `contentComponent`：原生菜单使用已挂载的 SkiaLayer，兼容菜单使用 ComposePanel，不能触碰未挂载的兼容面板而创建第二个场景。完整窗口层级验证后，在后续 EDT 回合补绘，使静止界面的视口与边缘也能更新。

布局刷新本身不产生焦点请求。`showMenu()` 或宿主重新获焦时登记焦点恢复意图，布局恢复后仅在焦点仍属于宿主或菜单时交回菜单内容，不能抢走自有子弹窗或其他前台应用的键盘输入。菜单已经持焦时不再向 Dialog 重复申请焦点，避免内部 Canvas 暂时失焦清空 Compose 节点焦点。启动页和正式菜单分别保留显示门控：启动页使用不透明窗口，正式菜单初始关闭，核心就绪后才启用。共享刷新入口分别刷新两个实例，不改变其模式；`showGame()`、宿主隐藏或 `dispose()` 撤销重试与延后补同步，迟到任务不得重开菜单。

原版脚本从游戏线程退回菜单时，`GameSessionManager.returnToMenu()` 将返回回调和显示菜单一起排到 EDT，不同步等待窗口显示。真实 LWJGL 对局验证曾发现 EDT 的 `WComponentPeer.pShow` 等游戏窗口线程处理原生消息，而该线程又在 `invokeAndWait` 等 EDT，造成互等。引擎清理仍在原调用线程，`DisplaySwitcher.switchTo()` 的其他同步调用契约保持原状；返回任务用代次检查，不能迟到隐藏新开局。`GameSessionManagerReturnTest` 验证消息泵、EDT 同步调用和过期返回三条路径。

`DesktopMenuBackgroundEngine` 在原游戏线程复用原版 AI 演示地图，仅在无真实地图、无连接、会话空闲且无开局/重载时重建。已有真实地图保留给“继续游戏”，此时主页使用渐变兜底。有效的自选/主题背景图片、隐藏窗口及原版 `renderBackground=false` 会停止独立演示的模拟和绘制；纯菜单限 30 FPS，真实对局沿用原帧率，房间网络泵继续。原版 `u.render` 同时消费菜单任务，停绘时仍须处理 Main / ScriptEngine 队列和延后设置保存，不能让业务回调依赖背景可见性。加载、保连接重载及资源失败期间继续服从既有闸门。

回归：`DesktopMenuBackgroundEngineTest` 覆盖场景保留、演示重建与连接门控；`DesktopMenuBackgroundWindowTest` 分别验证原生和兼容菜单的底层画面更新、按钮点击、画布存活、宿主跟随，以及软件选择、创建失败和设备后端变化的回退。正常路径只允许一个菜单组合，未使用的兼容面板不得初始化第二个渲染器。另运行 `NativeCanvasCardLayoutTest`、`DesktopStartupWindowTest`、`MatchStartLoadingWindowTest`、`DesktopWindowLayersTest`。人工检查首次启动背景持续变化、自选图片/主题覆盖、沙盒进出与继续游戏、论坛原生浏览器、菜单输入、F11、最小化及跨屏 DPI。

新增的 `DesktopMenuBackgroundWindowTest` 回归在原生 Direct3D 与 Swing 兼容菜单两条路径检查：无输入缩放后的 Compose 视口和边缘像素、bounds 相等时无效子布局的验证重绘、零尺寸或未 showing 且无后续事件时的恢复与键盘焦点、重试耗尽后显式刷新恢复、切游戏或销毁后迟到任务失效，以及刷新和待恢复焦点期间保留其他窗口的输入。`DesktopNativeMenuInputWindowTest` 同时验证重复焦点请求保留 Compose 键盘目标，`DesktopStartupWindowTest` 覆盖加载期刷新不提前组合正式菜单及首帧后的焦点交接。

原生键盘测试的像素探针使用真正透明的空白区域，并在 Dialog、Popup 保持打开时改变底层 Canvas 颜色，继续按原有每通道 ±2 容差校验。不要给探针先涂非零 alpha 的黑底再期待未经合成的原色：默认 Dialog 遮罩会参与已有 alpha 的合成；主线代码也可复现原先蓝色通道 144→141 的失败。产品为边缘鼠标命中保留的极薄背景不受测试探针调整影响。

Compose 1.10.1 的 WINDOW 子层没有更新自身 `LocalWindowInfo.isWindowFocused`；测试层内的 `FocusRequester` 在内容附着后通过一次性 effect 申请焦点，不能等待该值为 true。主菜单继续使用窗口焦点门控，实际通过条件仍同时要求 AWT/Win32 前台、Compose 节点焦点及 Robot 按键回调。测试只在初次激活时短暂露出自有标题栏，并在 Win32 命中核验后点击；所有置顶状态随后恢复，后续弹窗焦点交接不自动纠正。另一个测试窗口误持前台判为失败，外部应用遮挡时跳过且不向其发送输入。

200% 缩放可运行：

```powershell
.\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopMenuBackgroundWindowTest '-Prwjs.test.uiScale=2'
```

这些回归使用真实 Swing/Compose 窗口与测试 Canvas，验证范围限于组件窗口行为；不能替代完整客户端、真实 LWJGL 对局、不同显示器和驱动的人工验收。

2026-11-12 同机验证：9 类桌面回归 40 项通过；菜单、原生键盘和启动页在 200% 缩放下 17 项通过，均无失败或跳过。另在隔离的完整客户端中加载原版资源和真实 LWJGL，完成菜单/任务对局各两次全屏切换、进入任务及经原版返回路径回到菜单；主窗、Canvas、LWJGL 子窗句柄及主 GL 上下文保持不变，返回后的原生 Direct3D 菜单与内容区匹配。该客户端验证通过 AWT 键盘分派器触发 F11，经生产会话入口启动任务，不等同于物理 F11、跨显示器或驱动故障验收。

验证范围：真实地图组件连续 12 次切换地图类型时，未检测到弹窗内容位置变化，也未发生 Skiko 崩溃。仍可测到原生下拉窗口开关时的短暂停顿，采样主要落在 AWT 透明窗口刷新及 Skiko Direct3D 设备创建/释放路径；固定视口不能消除这些开销，不能据此宣称完整客户端已无卡顿。原始录像的问题在更新后的完整客户端中暂未复现，仍需在完整房间、真实存档及浏览器页面中人工验收。

## 人工验收

账号页使用 `AccountCenterLayout` 的左侧功能导航，右侧仅组合「账号资料 / 积分 / 隐私 / 账号安全」中的当前页。标题、刷新与关闭入口不参与正文滚动；窄屏保留图标和文字导航。每个分类通过 `SaveableStateHolder` 保存独立滚动位置。刷新资料只在顶栏显示进度，避免销毁分类及其状态。隐私保存的作用域和乐观状态属于整个账号页，切换分类不能取消请求；积分流水沿用原有分页弹窗，并纳入账号弹窗焦点恢复门控。

可运行 `:rwpp-core:desktopTest --tests AccountCenterUiTest --tests AccountAccessUiTest --tests AccountDialogInteractionTest` 检查分类隔离、横竖屏入口、滚动恢复及原有弹窗。账号页截图输出到 `rwpp-core/build/reports/account-center/`；双语资源由 `:rwpp-core:testDebugUnitTest --tests BundleParseTest` 实解析验证。

- Windows 100%/125%/150%/200% 缩放下拖动窗口、跨显示器移动、切菜单与游戏、按住再松开 F11；检查画布覆盖与点击位置。
- 快速进入并关闭设置、任务、模组、地图选择、账号、好友；退出动画期间点击下一页 X；检查没有无形遮罩。
- 等待房间打开地图选择，输入长地图名、筛选、选择、重开、刷新；检查定位、空结果、地图类型切换和滚动稳定。
- 地图选择保持打开时切换到浏览器、任务管理器及其他应用；游戏弹窗应被前台应用遮住。最小化、恢复游戏后检查弹窗跟随宿主；资源浏览器上的游戏弹窗和悬浮控件仍应正常覆盖网页。
- 等待房间多人列表滚动、邀请/锁房按钮换行、聊天输入与 Enter；局内聊天关闭后再次发送团队/普通聊天。
- Android 真机横竖屏及大字体检查地图工具栏、X 命中、返回键、软键盘、房间聊天；维持既有 IME 系统栏闸门。

编译及桌面组件测试不能替代 Android 真机、LWJGL 原生窗口和联网房间验收。
