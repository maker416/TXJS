# 启动流程与性能检查

## 当前流程

桌面端入口是 `rwpp-desktop/.../Main.kt`：

1. 接管运行日志、安装窗口兼容层、创建 Koin、读取配置。
2. `Builder.prepareReloadingLib()` 扫描扩展并比较注入配置。
3. 普通启动执行 `AppContext.init()`：注册业务事件、初始化 Lua 和 UI、执行各 `Initialization`（国际化、扩展/资源、网络配置等）。
4. `DesktopNetworkModCache.prepareStartup()` 恢复网络模组工作副本，清理临时地图。
5. 创建 Swing/Compose 窗口；加载协程调用 `GameImpl.load()`。
6. 游戏线程创建 GL 上下文，初始化音频、libRocket/字体和原版引擎；等待 `DesktopEngineStartup` 完成后切换到主菜单。

Android 入口是 `MainApplication` → `LoadingScreen` → `MainActivity`：

1. 仅主进程创建 Koin、日志和后台保活组件；读取配置，检查安装版本及注入产物。
2. 已有有效产物时加载 DEX 并验证游戏类。
3. `LoadingScreen` 等待文件权限；在既有 IO 协程上准备资源，执行 `AppContext.init()`、清理临时地图。
4. 设置存储类型、整理网络模组缓存，再调用原版引擎初始化。
5. 广播 `GameLoadedEvent`，打开 `MainActivity`。

两端首次安装/注入变更另走类库生成分支，Android 还需编译 DEX，然后重启。它与已有有效产物的普通进程冷启动应分开统计。版本检查、账号恢复等在共享 `App` 的协程中启动，不是原版引擎加载之前的串行网络等待。

## 本次优化

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

两端增加 `[STARTUP]` 阶段日志，采用 `System.nanoTime()`，不切换线程或引入异步初始化：

- `*.config`、`*.injectCheck`：启动器配置与注入检查。
- `app.logic`、`app.scripts`、`app.ui`、`app.<实现类名>`：共享初始化各阶段。
- `*.networkModCache`：网络缓存恢复。
- `desktop.engine`、`android.engine`：原版引擎加载。
- `*.injectBuild`、`android.dexBuild`、`android.dexLoad`：首次构建与 DEX 工作。
- `desktop.beforeWindow`、`desktop.menuReady`、`android.engineReady`：从平台入口计时的里程碑。

`completed=false` 表示该阶段异常退出。父子阶段有包含关系，不能相加；`menuReady/engineReady` 也不代表首帧已实际显示，更不包含操作系统创建进程之前的耗时。

下一步应对无模组、大量本地模组、大量网络缓存三种场景分别采样，确认主耗时后再做优化。候选包括无脚本扩展时按需初始化 Lua、桌面窗口显示前的资源准备、地图目录扫描及原版单位/贴图加载。它们尚未实测或修改，尤其引擎并行化与线程迁移必须先核实 GL/View 依赖并做真实游戏回归。

## 回归检查

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-desktop:test --tests io.github.rwpp.desktop.DesktopNetworkModCacheTest --tests io.github.rwpp.desktop.DesktopEngineStartupTest :rwpp-android:testDebugUnitTest
```

桌面测试覆盖多缓存恢复、幂等、损坏隔离、最新版本选择、历史版本激活、过期清理、多字节长名称和空载荷；Android 测试覆盖版本切换、同长度篡改、过期/孤立文件及坏元数据清理。上述编译和 JVM 测试不能替代首次安装、普通启动及带模组进房的真机验收。
