# UI 性能与动画开关（2026-10）

本轮检查范围为共享 Compose 启动器界面及两端编译，不改变游戏引擎线程、网络保活、模组重载或会话清理时序。下面的收益依据代码审计与受控 Compose 渲染测试；尚未对 Android 真机或完整游戏运行时做 CPU/GPU、功耗及帧率前后对比。

## 问题与修改

| 已确认问题 | 处理方式 | 直接作用 |
| --- | --- | --- |
| `Settings.enableAnimations` 是普通字段，设置页只更新自身开关状态 | `setUiAnimationsEnabled()` 写入配置并通知 Compose，主题向子树提供统一策略 | 已组合的界面和独立窗口即时响应，无需重启 |
| 只有部分页面转场检查开关 | 页面/好友/模组切换、尺寸、颜色、按钮缩放、滚动条、自动滚动统一检查策略；禁用 `AnimatedContent` 的尺寸变换 | 禁用时直接显示目标状态 |
| Material 控件、波纹仍有动画 | 共用主题提供零时长 MotionScheme，停用 ripple/indication | 开关、勾选框、单选框、标签/菜单等内部动画遵守偏好 |
| 无限加载和房间流光忽略设置 | 静态不定进度指示器、停止流光；保留真实下载数值和可访问性“不定进度”语义 | 禁用时移除循环动画与逐帧请求 |
| 好友入口没有未读也创建无限动画 | 只有有未读且开启动画时才创建脉冲，缩放状态仅在 graphicsLayer 中读取 | 去掉无用时钟；脉冲不逐帧重组入口 |
| 自定义加载器每根线一条动画协程，每帧创建透明度列表和重复计算三角函数 | 一个动画时钟；缓存几何；在 Canvas 绘制中读取时钟 | 默认 8 个循环协程减为 1 个，禁用时为 0 |
| 多人顶栏动画数值在页面组合中读取 | 列表顶部内边距在测量时读取，顶栏偏移在布局时读取；滚动历史改为协程局部变量 | 避免每帧使父页面重组和滚动时写无用 Snapshot 状态 |
| 同步进度动画值传入整个状态行，流光每帧创建画刷和路径 | 绘制阶段读取进度，缓存渐变画刷并复用路径 | 减少逐帧重组和分配 |
| 图片默认总是淡入 | Coil 请求拦截器读取当前偏好，复用现有 ImageLoader 与缓存 | 新图片请求尊重开关 |
| 邀请的业务超时直接等待动画结束 | 独立 10 秒超时协程；视觉倒计时可停止/继续 | 禁用动画、切换开关或系统动画倍率为零都不会提前忽略邀请或重置期限 |
| 主题重组反复创建排版对象 | 根据颜色和字体缓存 Typography | 减少不变主题的对象分配 |

## 自动化验证

`UiMotionTest` 使用真实 Compose 渲染场景及测试时钟验证：

- 禁用加载指示器后 `ImageComposeScene.hasInvalidations()` 为 false；跨时间渲染像素相同。
- 开启动画后画面变化；运行中关闭后不再请求动画帧。
- Material 开关、勾选框、单选框和自定义动画值直接到达目标状态；到达后无动画帧请求。
- 关闭开关能中止正在运行的滑块和勾选框动画；UiSwitch/UiCheckbox 保留外层交互和键盘焦点，只重建视觉节点。
- 顶栏动画进行期间父组合次数不随每一帧增长。
- 邀请在切换开关后仍按原来的 10 秒期限超时一次。

`ThemeSettingsUiTest` 通过真实设置页点击开关，验证已经组合的兄弟组件即时更新以及保存的配置值。现有导航、会话生命周期、弹窗交互、加载取消、拖放、浏览器方向、主题和多人顶栏测试用于防止行为回归。截图输出到 `rwpp-core/build/reports/ui-motion/`。

两端校验命令：

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-core:compileKotlinDesktop :rwpp-core:compileDebugKotlinAndroid :rwpp-desktop:compileKotlin :rwpp-android:compileDebugKotlin
.\gradlew.bat :rwpp-core:desktopTest --tests UiMotionTest --tests '*ThemeSettingsUiTest' --tests '*MultiplayerTopBarUiTest' --tests '*SharedWidgetInteractionTest' --tests '*LoadingViewTest' --tests '*LauncherInteractionUiTest' --tests '*ResourceBrowserOrientationUiTest' --tests '*ModFileDropInteractionTest' --tests '*SessionLifecycleTest' --tests '*AccountDialogInteractionTest' --tests '*MainMenuThemeTest'
```

## 后续维护和设备验收

- 新 UI 读取 `uiAnimationsEnabled()`，设置写入走 `Settings.setUiAnimationsEnabled()`，不要直接修改字段后期待 Compose 自动刷新。持续动画必须在禁用时退出组合，不能只把时长置零或冻结输出。
- 新建开关/勾选框应使用 `UiSwitch`/`UiCheckbox`，以便中止已有视觉动画并保留键盘焦点。
- Material3 **1.9.0-beta03** 尚未公开 MotionScheme；适配封装在 `widget/UiMotion.kt`，需要局部 `INVISIBLE_MEMBER/INVISIBLE_REFERENCE` 抑制。升级 Material3/Compose/Kotlin 时必须重跑两端编译和 Material 控件测试，公开 API 可用后迁移；当前不为这个修复升级依赖。
- 计时、网络轮询、下载、重载和会话清理不能依赖动画完成。真实进度的离散更新、文本光标、用户拖拽以及浏览器网页/操作系统/游戏引擎的动画不属于启动器装饰动画开关。
- Android 真机验收：启动加载、开关即时切换、好友列表/聊天、模组/地图切页、服务器列表滚动、房内同步、邀请倒计时、键盘与退出房间；桌面额外检查窗口/弹窗交互。
- 如需量化整机收益，应在相同设备、分辨率、模组与房间规模下记录空闲菜单、快速列表滚动及同步场景的 CPU/GPU、帧时间分位数和功耗。当前测试能证明指定组件停止动画请求及减少重组，不能据此给出整机提升百分比。

参考：[Compose 渲染阶段与延迟读取状态](https://developer.android.com/develop/ui/compose/performance/phases)。

## PC 页面切换卡顿排查（2026-11-12）

用户反馈为开启动画后，每次切换页面的整段动画都不流畅。本次使用修复启动冻结后的 `1.20.73` JAR，在独立游戏资源目录中运行真实 Windows 客户端，阻断外部服务，自动调用生产导航入口切换单人、设置、资源中心和模组页。没有覆盖现有客户端或修改其用户配置。

### 已确认的渲染路径

`DesktopLauncherOverlay.createMenuPanel(true)` 为透出原生战场背景使用 `RenderSettings.SwingGraphics()`。根据本机 Compose 1.10.1 / Skiko 0.9.37.4 实现，这条路径在 Windows 上可使用 Direct3D，并非固定纯软件绘制；但 GPU 绘制完成后还需回到 Swing：

1. EDT 执行整窗绘制，尺寸按窗口逻辑尺寸乘显示器缩放比例计算。
2. `Direct3DSwingRedrawer` 等待 GPU 完成。
3. `SoftwareSwingPainter` 执行 `readPixels`，复制到 `BufferedImage`，交给 AWT 绘制。
4. AWT 再提交透明顶层窗口。仅测 `SkiaSwingLayer.paint()` 不包含全部窗口提交耗时。

因此 GPU 较强也不能消除整窗回读、复制和透明窗口提交的串行成本。页面淡入淡出期间新旧页面同时绘制，会持续走这条路径。游戏背景的 30 FPS 上限是另一条渲染循环，不能直接当作启动器页面动画的帧率上限。

### 真实客户端基线

测试窗口为 1920×1017，显示缩放为 1.0，保留动态战场背景与动画。临时 Java agent 分别采样真实 Swing 绘制、读回复制、EDT 事件，并记录 JFR；每页首次打开后再重复两轮。以下为两轮重复打开的实测范围：

| 页面 | 单次 Swing 绘制 P95 | 相邻绘制完成间隔 P95 |
| --- | --- | --- |
| 单人游戏 | 7.8–12.0 ms | 24.1–25.1 ms |
| 设置 | 8.5–11.4 ms | 30.8–36.0 ms |
| 资源中心 | 10.1–18.8 ms | 34.4–36.9 ms |
| 模组 | 7.4–8.8 ms | 24.2–29.2 ms |

其中单次读回/复制的中位数约为 3.6–4.9 ms。相邻绘制间隔包含绘制之外的调度和 AWT 提交，不能全部归因于 `readPixels`。代码检查未发现这条 Compose/Swing 调帧链存在固定 16 ms 休眠，不应把差额解释成“固定等待 16 ms 再绘制”。

JFR 为透明窗口提交提供了更直接的证据：重复切页阶段的 280 个 EDT 采样中，192 个（约 68.6%）包含 `D3DRenderQueue.flushAndInvokeNow ← TranslucentWindowPainter$VIOptWindowPainter.update ← WWindowPeer.updateWindow` 调用链。另可见 Skiko 读回和 GPU 等待样本。这个比例是采样分布，不等于精确耗时占比；它说明优化应优先覆盖组件绘制结束后的 AWT 透明窗口提交，不能只优化 Compose 内部动画。

60 Hz 的帧预算约为 16.7 ms；上述重复进入的间隔已能解释连续动画的顿挫。900 ms 采样窗口包含动画结束后的静止时间，不能拿总帧数除以 0.9 当作动画 FPS。首次打开单人页的首个 Swing 绘制另耗时约 894 ms，设置页约 352 ms；首次类加载、资源及布局初始化需要与持续绘制成本分开处理。

原始记录位于本机忽略目录 `build/tmp/desktop-startup-baseline-20261112/navperf/baseline-valid/`，包括 `summary.csv`、`paints.csv` 与 `navigation.jfr`。这些是诊断记录，不随客户端打包。

### 共享界面的次级问题

- 未发现 `App` 顶层逐帧读取动画值而使整棵应用树持续重组。现有按钮缩放等已把状态读取放在绘制层。
- `expandIn` / `shrinkOut` 会改变动画外层尺寸并增加布局/裁剪工作，但当前 Compose 实现仍以原约束测量内容，不能据此声称整页内容每帧按缩小尺寸重新排版。
- 模组页首次组合会扫描目录、读取模组元数据与规范路径；地图、回放、任务页也有同步文件枚举。这些会增加入页停顿，模组/文件越多越明显，但不足以单独解释所有页面整段动画卡顿。
- 迁移文件工作时应只将纯文件读取、解析和排序放到后台；引擎访问保留原线程契约。不要通过长期保留所有页面组合来绕开初始化，否则会影响轮询和会话清理生命周期。

### 替代路径对照与实施方向

软件渲染对照保留了相同的透明窗口、动态背景及页面动画，以 `SKIKO_RENDER_API=SOFTWARE_FAST` 仅覆盖诊断子进程。重复打开单人页的绘制完成间隔 P95 为 35.7–47.7 ms，资源中心为 45.7–60.9 ms，均未显示收益。因此不建议以切换软件模式作为这次问题的通用修复。

原生 SkiaSurface 透明实验用临时 agent 在菜单容器创建时通知 `onWindowTransparencyChanged(true)`，并在诊断子进程选择 Direct3D。普通 `ComposePanel.isOpaque=false` 不会传播这项窗口透明状态，因此不能把此前白底直接等同于“原生渲染不支持透明”。公开 API 的正式实现候选为由 `ComposeDialog(owner)` 自身承载内容并设置 `isTransparent=true`；实验中的内部接口插桩不应直接打包进客户端。

为避免混用 Swing 绘制与原生 swap 的采样点，另一组基线与原生实验共同采集 `ComposeSceneMediator.onRender`。第二轮重复进入的 Scene 回调完成间隔 P95 如下：

| 页面 | SwingGraphics 对照 | 原生 Direct3D 实验 |
| --- | --- | --- |
| 单人游戏 | 21.9 ms | 12.6 ms |
| 设置 | 22.8 ms | 10.4 ms |
| 资源中心 | 20.6 ms | 12.7 ms |
| 模组 | 23.6 ms | 10.5 ms |

这组对照支持绕过 Swing 输出链的方向，但 Scene 回调间隔不是屏幕实际 present 帧率。原生实验仍出现资源中心约 49 ms 的单次最大间隔，首次初始化停顿也未消失；不能宣称所有切页都已稳定无卡顿。前几组诊断窗口存在被其他桌面应用遮挡的情况，不能仅凭 AWT `isShowing` 或截图文件存在认定可见性验收通过。相关记录分别在本机 `navperf/baseline-scene/` 与 `navperf/native-direct3d/`。

另一次 `navperf/native-front/` 复核确认资源中心截图时 Win32 前台句柄属于本次 RWJS 菜单窗口，人工检查截图可见原版地图和单位透出，没有白底。随后第二轮重复开页的 Scene 回调间隔 P95 分别为单人 11.1 ms、设置 11.6 ms、资源中心 11.1 ms、模组 11.1 ms。该轮重复切页的 EDT JFR 样本中未再见上述 AWT 透明提交/Skiko 读回调用链；样本数较少，不能转为精确节省百分比。此复核未覆盖所有页面的鼠标、键盘、弹窗、全屏、多显示器及后端回退行为，仍需正式实现后的原生窗口回归。

实施前确定的方向是优先改造菜单窗口输出路径，保留动态背景、Canvas 原生句柄与兼容回退，再拆分同步文件扫描。以上实验仅覆盖 Windows Direct3D，不能直接推广到其他后端或系统；正式落地情况见下一节。

## PC 切页正式修复（1.20.74）

收到用户实施授权后，正式实现改为 `ComposeDialog(owner)` 自身承载菜单，`isTransparent=true`，首次显示菜单时才创建原生渲染器；启动加载页继续使用独立的不透明窗口。正常原生路径不会把备用 `ComposePanel` 加到可显示窗口中，避免重复组合 `App`、重复启动页面协程或提前创建第二个 GPU 渲染器。

菜单单独选择 Direct3D，普通窗口仍沿用用户原来的后端，全局 `skiko.renderApi` 和设置文件不作改写。Skiko 0.9.37.4 会在首次 peer 初始化时重放构造时的后端队列，单独调用 `SkiaLayer.renderApi = DIRECT3D` 会被覆盖；实现使用公开的 Renderer 状态回调，只在首次 `pack()` 期间重选一次，并在完成或抛异常时解除重选。`pack()` 后再次检查实际后端。显式软件模式、创建失败及后续设备回退均使用原 Swing 兼容菜单，不影响游戏 Canvas 的父容器、原生句柄或 LWJGL 上下文。菜单焦点统一交给实际显示的 surface。

完整客户端验证另外发现实际对局退回时的跨线程等待：EDT 停在 Windows `WComponentPeer.pShow`，游戏线程停在 `DisplaySwitcher.switchTo(Menu)` 的 `invokeAndWait`。因此游戏线程发起 `GameSessionManager.returnToMenu()` 时改为异步排入 EDT，保留游戏线程的原生消息处理；EDT 发起则仍立即完成。新开局通过代次作废未执行的旧返回任务，引擎清理和其他切画面入口没有一并迁移线程。

纯文件工作同时拆出 UI 线程：

- 模组：在 UI 线程捕获引擎引用及路径/开关/错误快照，IO 线程解析 ZIP 标题、扫描未加载模组、规范化路径；卡片使用已缓存的路径。
- 地图：IO 线程递归扫描文件、检查缩略图和读取大小，后续引擎地图缓存刷新保留原线程。
- 回放：IO 线程执行两端共用的文件扫描、标签解析和筛选项构建；播放/引擎入口仍走原有调用路径。
- 页面先显示可关闭的加载态；依赖数据的操作等加载完成后启用，错误可重试。加载状态使用代次和取消检查，dispose 后不发布旧结果。
- 任务列表仍混用桌面 `ScriptEngine.root.convertMapName` 和 Android 原版 Activity 路径，本次保留其线程，不将整个引擎入口迁到 IO。

正式代码的第一轮完整客户端测试使用用户原有 `OpenGL` 配置，无 `SKIKO_RENDER_API` 覆盖，也不加载实验 NativeMenuProbe。实际窗口已创建 native peer，日志确认菜单为原生 Direct3D，前台截图可见动态地图背景。单人、设置、资源中心、模组第二轮重复打开的 Scene 回调完成间隔 P95 分别为 10.7、10.8、11.0、10.7 ms，记录在本机 `navperf/official-fresh-perf/`。这仍是渲染回调采样指标，不是端到端屏显帧率保证；首次类加载/资源初始化仍可能有停顿。

### 最终构建与验证

- 共享界面 53 项测试通过，覆盖文件加载的线程响应、取消/过期结果、失败重试，以及真实模组布局/拖放、导航、动画设置与会话生命周期。共享 UI 的 Android Debug Kotlin 编译通过，未打 Android APK 或做 Android 真机验证。
- 桌面端 40 项测试通过，覆盖原生/兼容菜单、软件选择、初始化失败、设备回退、Canvas 存活、窗口跟随、启动页、开局提示、原生 WINDOW 层及返回菜单的消息泵和代次。另 1 项需要真实前台的原生键盘/Dialog/Popup 输入测试因外部应用占前台而按前置条件跳过，没有向其他应用发送输入；不把它计为键盘验收通过。
- 最终 JAR 无实验 NativeMenuProbe。重复开页的共同 Scene 回调完成间隔 P95：单人 11.9 ms、设置 10.8 ms、资源中心 12.9 ms、模组 12.4 ms；原始数据在 `navperf/packaged-final-perf/`。资源中心仍有一次约 53 ms 的较大间隔，首次类加载及菜单初始化也仍有停顿，不宣称所有帧都满足 60 Hz 预算。
- 独立 Software 配置启动复核通过，保持软件兼容菜单。完整客户端通过同一全屏控制器的 Win32 模式往返、最小化/恢复、真实沙盒房间及开局；这些局部诊断不等同物理 F11/ESC 按键测试。
- 最终返回修复后的真实对局在 `12:32:15.508` 发起返回，`12:32:15.553` 完成，约 45 ms；随后 EDT 心跳正常，Canvas 原生句柄保留，原生 Direct3D 菜单实际可见。对照旧路径会在 `pShow` 挂住超过 8 秒。记录在 `navperf/packaged-return-fixed/`；所有诊断进程已结束。

产物：`rwpp-desktop/build/compose/jars/RWJS-windows-x64-1.20.74-release.jar`，SHA-256 为 `D56AC33F10813EDB5A2F3261D9A93208E80289404F48991E25F4B9C97C5774A9`。JAR 继续配合已有游戏资源和启动脚本使用，本次没有安装或覆盖现有客户端。
