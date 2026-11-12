# 启动流程与性能检查

## 当前流程

桌面端入口是 `rwpp-desktop/.../Main.kt`：

1. 接管运行日志、安装窗口兼容层、创建 Koin、读取配置。
2. `Builder.prepareReloadingLib()` 扫描扩展并比较注入配置。
3. 创建宿主窗口与独立、不透明的普通 Compose 加载窗口；标题图在 IO 解码，首帧不依赖主题字体、国际化或引擎。
4. 加载首帧完成且初始布局事件已处理后，暂缓 Canvas 尺寸更新，在 IO 执行 `AppContext.init()`、恢复网络模组工作副本、清理临时地图；原有准备顺序保持不变。
5. 加载协程调用 `GameImpl.load()`，游戏线程创建 GL 上下文；容器 setup 返回后排队解除尺寸暂缓，统一应用期间最新窗口尺寸/DPI，再继续原有音频、libRocket/字体和引擎资源初始化。
6. 引擎就绪后显示菜单，菜单首帧绘制后销毁加载窗口并停止启动诊断。Windows 原生透明 Direct3D 菜单（或 SwingGraphics 兼容菜单）及游戏背景仅在此时启用；不透明回退菜单沿用原有模式。

Android 入口是 `MainApplication` → `LoadingScreen` → `MainActivity`：

1. 仅主进程创建 Koin、日志和后台保活组件；读取配置，检查安装版本及注入产物。
2. 已有有效产物时加载 DEX 并验证游戏类。
3. `LoadingScreen` 等待文件权限；在既有 IO 协程上准备资源，执行 `AppContext.init()`、清理临时地图。
4. 设置存储类型、整理网络模组缓存，再调用原版引擎初始化。
5. 广播 `GameLoadedEvent`，打开 `MainActivity`。

两端首次安装/注入变更另走类库生成分支，Android 还需编译 DEX，然后重启。它与已有有效产物的普通进程冷启动应分开统计。版本检查、账号恢复等在共享 `App` 的协程中启动，不是原版引擎加载之前的串行网络等待。

## 本次优化

### PC 加载画面（2026-11-12）

旧加载页复用透明菜单的 SwingGraphics 绘制路径，并通过 `painterResource` 在首次组合时同步解码标题、加载主题字体。真实隔离启动的线程采样观察到 EDT 位于 `MenuLoadingView` 标题图的 `Image.makeFromEncoded` / resources `runBlocking` 调用链；单次栈只能证明同步解码，不能证明数秒等待全部耗于该处。

现在启动全程使用普通、不透明 Compose 窗口及系统字体，先绘制转圈和提示，标题图、用户背景图在 IO 解码。内存信息和受保护模组提示在国际化准备完成后恢复；动画开关继续生效。原版 GL、音频、字体和单位加载仍在游戏线程，未改变引擎上下文归属或初始化顺序。

LWJGL 的 `Display.create` 与 Canvas resize listener 共用全局锁。启动首绘后先处理已有尺寸事件，再暂缓 Canvas 布局更新；setup 返回后在 EDT 应用最新尺寸，降低尺寸回调撞上 GL 创建的机会。这不保证消除显卡驱动或原生 AWT surface 的所有等待；不替换原版 listener，不修改 GL 锁或重建 Canvas。

实际运行进一步抓到了更长的阻塞：EDT 连续停在 `WWindowPeer.requestWindowFocus → WindowsDisplay$3.run`，游戏线程同时正常执行资源初始化。Windows 焦点调用等待拥有原生游戏子窗口的线程处理消息，导致加载页也停住。LWJGL 的 `$2` 回调会先把 `isFocusable` 重设 false/true，单设 false 不足以解决。`DesktopGameCanvas` 因此在启动期间门控 `requestFocus()` / `requestFocusInWindow()`，引擎加载返回后才允许正常请求；被抑制的启动抢焦点不重放，进入对局仍由原有 DisplaySwitcher 获取输入。

启动期间的 `DesktopStartupDiagnostics` 使用后台 daemon 监测 EDT 心跳，最多排一个待处理心跳；超过 500ms 时限量抓取 EDT/游戏线程栈与锁持有者，重复抓栈至少间隔 1s。首帧、连续动画长帧及收尾汇总使用 `[STARTUP-UI]`。成功、失败、取消或窗口销毁时关闭采样，不留下局内轮询。关闭动画时不把文本离散更新之间的空闲时间当成长帧。

2026-11-12 本机 Windows / Java 21 / 无模组、无网络缓存，在 `build/tmp/desktop-startup-baseline-20261112` 隔离目录使用真实原版资源运行；未修改实际安装或账号配置。焦点门控之前的两轮诊断版分别出现 4504/4219ms 的加载内容绘制间隔及 3627/3444ms 的 EDT 焦点等待。门控后的两轮窗口模式分别绘制 406/414 次，最大间隔 139/122ms，多秒焦点等待消失。

最终 `RWJS-windows-x64-1.20.73-release.jar` 又验证了全屏与关闭动画：全屏加载内容绘制 366 次、最大间隔 121ms、菜单首绘约 6.99s；关闭动画时仍按进度更新，未误报长帧，菜单首绘约 6.73s。两轮菜单后的线程转储均确认诊断线程已退出。全屏一轮出现本机 OpenAL 播放设备初始化失败后继续启动，未据此验收音频播放。

绘制间隔从滚动内容层内采样，避免父层复用绘制记录导致少计动画帧；它不等于显示器实际帧率，也不包含首次绘制前的初始化或最后一帧到主菜单的交接等待。本机仍观察到首次 Compose 初始化和主菜单初次提交各约 0.6–0.7s 的 EDT 等待，真实引擎资源加载约 2.6–2.9s。总启动耗时未表现出稳定、大幅下降，本轮验证的是加载期间可持续刷新。大模组、网络缓存、其他显卡/驱动仍需分别复测。

### 桌面网络模组缓存

旧路径会先完整读取、解密、校验所有缓存，再对每个模组的最新版本重复读取、解密、校验并写入工作副本。

新路径先读取有界的小文件头安排版本顺序，再逐个完整验证，直接把当次得到的最新有效明文写入工作副本。旧版本仍验证并保留索引，后续可以显式激活；最新版本损坏时使用较旧的有效版本。不同模组的明文不会集中保存在内存中。

文件头在 AES-GCM 认证和 SHA-256 校验通过之前只用于排序，不能进入可用索引。原有 TTL、损坏清理、退出清理、单次初始化与显式激活时的校验均保留。

### Android 网络模组缓存

旧路径为每个活动文件重复列出并解析所有 `.meta`，最坏产生平方级的元数据读取。新路径只取一次目录快照、解析一次元数据，用集合检查无主文件。载荷 SHA-256 校验仍逐个执行。

该优化针对存在网络模组缓存的启动；没有缓存时不应期待同等幅度的收益。未改变原版单位/图片/音频加载顺序、游戏线程契约或模组选中状态。

## 对照测量

2026-10-24，本机 Windows/JVM，三个各 32 MiB 的缓存、共 96 MiB；同一个基准连续创建新的缓存管理器，测量 `prepareStartup()`（包含工作副本落盘），计时之外准备加密数据和清理副本。

| 轮次 | 修改前（ms） | 修改后（ms） |
|---|---:|---:|
| 1 | 2611 | 1375 |
| 2 | 2962 | 1438 |
| 3 | 2794 | 1392 |
| 4 | 2577 | 1358 |
| 中位数 | 2702.5 | 1383.5 |

本场景的缓存恢复中位耗时下降约 **49%**，减少约 **1.32 秒**。这是固定数据的阶段基准，包含 JVM/文件系统缓存效应，**不是完整游戏冷启动或 Android 真机提速结论**。没有对 Android 元数据优化给出实机百分比。

手动复现（PowerShell，默认测试会跳过此基准）：

```powershell
$env:RWJS_BENCHMARK_STARTUP = '1'
try {
    .\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.NetworkCacheStartupBenchmark --rerun-tasks
} finally {
    Remove-Item Env:RWJS_BENCHMARK_STARTUP
}
```

输出在 `rwpp-desktop/build/test-results/test/TEST-io.github.rwpp.desktop.NetworkCacheStartupBenchmark.xml` 的 `CACHE_STARTUP` 行。对比不同提交时应保持 JVM、数据、磁盘和后台负载一致，多次运行取中位数。

## 后续如何定位

两端 `[STARTUP]` 阶段日志采用 `System.nanoTime()`；计时包装本身不切换线程：

- `*.config`、`*.injectCheck`：启动器配置与注入检查。
- `app.logic`、`app.scripts`、`app.ui`、`app.<实现类名>`：共享初始化各阶段。
- `*.networkModCache`：网络缓存恢复。
- `desktop.engine`、`android.engine`：原版引擎加载。
- `desktop.engine.containerSetup/sharedGlContext/audio/platform/libRocketSetup/fonts/cjkFonts/libRocketScripts/resources`：PC 容器（包含 GL）、共享上下文、音频、界面、字体和真实引擎的细分阶段，父子阶段不能相加。
- `*.injectBuild`、`android.dexBuild`、`android.dexLoad`：首次构建与 DEX 工作。
- `desktop.beforeWindow/windowVisible/menuReady/menuFirstFrame`、`android.engineReady`：从平台入口计时的里程碑。`menuFirstFrame` 是绘制回调后排队的标记，不代表显示器实际呈现时间；加载首绘和帧间隔另见 `[STARTUP-UI]`。

`completed=false` 表示该阶段异常退出。父子阶段有包含关系，不能相加；`menuReady/engineReady` 也不代表首帧已实际显示，更不包含操作系统创建进程之前的耗时。

后续继续对无模组、大量本地模组、大量网络缓存三种场景分别采样，确认主耗时后再做优化。尚未实施的候选包括无脚本扩展时按需初始化 Lua、注入检查中的扩展目录扫描、地图目录扫描及原版单位/贴图加载。引擎并行化与线程迁移必须先核实 GL/View 依赖并做真实游戏回归。

## 回归检查

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopNetworkModCacheTest --tests io.github.rwpp.desktop.DesktopEngineStartupTest :rwpp-android:testDebugUnitTest
.\gradlew.bat :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopStartupDiagnosticsTest --tests io.github.rwpp.desktop.DesktopStartupWindowTest --tests io.github.rwpp.desktop.NativeCanvasCardLayoutTest --tests io.github.rwpp.desktop.MatchStartLoadingWindowTest --tests io.github.rwpp.desktop.DesktopMenuBackgroundWindowTest --tests io.github.rwpp.desktop.ReloadLoadingWindowTest
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests BundleParseTest
```

桌面测试覆盖多缓存恢复、幂等、损坏隔离、最新版本选择、历史版本激活、过期清理、多字节长名称和空载荷；Android 测试覆盖版本切换、同长度篡改、过期/孤立文件及坏元数据清理。上述编译和 JVM 测试不能替代首次安装、普通启动及带模组进房的真机验收。

本轮 21 项桌面定向回归（含真实窗口、慢标题解码期间的动画/静态进度、LWJGL 重设 focusable 后的门控及恢复、尺寸/DPI 暂缓、诊断收尾、开局与重载提示）和 23 项 `BundleParseTest` 均通过；桌面发布 JAR 构建通过。没有生成或安装新安装器，没有验收带模组的真实对局。
