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
