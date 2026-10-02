# 模组堆内存实测工具

工具调用本项目 lib/game-lib.jar 中的真实桌面核心，先加载原版资源，再通过原版模组重载入口加载指定模组。继承、变量替换、逻辑表达式、单位间引用、贴图和声音都由引擎处理，不再通过配置文本推算对象大小。

需要 Java 21、完整桌面游戏目录及其原生库，电脑需支持 LWJGL2 的离屏 OpenGL Pbuffer。测量不会打开游戏窗口；每个模组在独立 JVM 中加载，工作目录、原版资源副本和配置均在临时目录中，退出或取消后清理，不改变正在运行的游戏或原有模组启用设置。

## 打开和使用

双击仓库根目录的 `启动模组内存分析.bat`。首次运行会构建工具；修改代码后需重新执行 `packageTool`。

界面自动尝试从 `packaging/game-root.local.txt` 或当前游戏目录定位资源。未找到时，点击“选择游戏目录”，选择包含 `assets/units`、`res` 及原生库的完整桌面游戏安装目录。

选择一个或多个 `.rwmod` / `.zip` 文件，或一个完整模组目录。支持拖入文件、粘贴路径、批量分析、取消、按单位名或定义文件筛选、表头排序，以及报告和单位 CSV 导出。单份 `.ini` 缺乏可靠的模组根和资源上下文，不再作为完整模组分析输入。

核心加载错误、缺失资源、未加载任何单位或部分单位加载失败都会显示失败，不将不完整结果标成成功。一个模组失败后，批量分析继续处理其他模组。

## 构建和命令行

```powershell
.\gradlew.bat :rwpp-mod-heap-tool:test :rwpp-mod-heap-tool:packageTool
java -jar build\mod-heap-tool\RWJS-ModHeapTool.jar
java -jar build\mod-heap-tool\RWJS-ModHeapTool.jar --analyze D:\Mods\example.rwmod --game-root "D:\Games\Rusted Warfare"
```

Gradle 命令：

```powershell
.\gradlew.bat :rwpp-mod-heap-tool:measureModHeap "-Pmod=D:\Mods\example.rwmod" "-PgameRoot=D:\Games\Rusted Warfare"
```

旧的 `:rwpp-core-api:estimateModHeap -Pmod=...` 命令会转交真实核心测量。API 中的 `ModHeapEstimator` 仅保留为静态模型兼容代码，不再用于工具界面或上述 Gradle 命令。

测量子进程默认最大堆为 4096 MiB（4 GiB），双击启动脚本即可使用此上限。可通过父进程 JVM 属性调整，例如设为 8 GiB：

```powershell
java -Drwpp.heap.maxHeapMiB=8192 -jar build\mod-heap-tool\RWJS-ModHeapTool.jar --analyze D:\Mods\large.rwmod --game-root "D:\Games\Rusted Warfare"
```

测量子进程同时使用 `-Xss16m`，为原版核心递归解析深层 `select(...)` 等逻辑表达式提供 16 MiB 线程栈。线程栈和 Java 堆分别受限；`StackOverflowError` 表示线程栈溢出，提高 `-Xmx` 无法解决。可通过父进程属性 `-Drwpp.heap.stackMiB=32` 调整栈配置（1..256 MiB）。报告记录实际传入的栈配置，线程栈不计入 Java 堆占用。

失败时界面保留异常类型、失败阶段和原因，包括线程栈溢出、核心加载内存不足与对象图诊断内存不足，避免长调用栈截掉异常标题。

## 客户端深层 Select 优化与对比

安卓和桌面客户端在真实核心的 `LogicString$Select` 中注入迭代解析、求值及诊断输出。对完整的嵌套 `select(...)`，扫描器一次建立括号位置，用原字符串的范围拆分参数，避免每层重复复制、扫描整段剩余表达式；显式栈替代递归。叶子表达式仍交给原版解析器，构造的仍是原版 Select 节点，保留字段、类型校验顺序、条件执行顺序和分支惰性求值。

优化没有修改模组内容，也没有用哈希查表改变重复条件的“首次匹配”规则。无法明确识别的语法继续使用原版解析入口；特别是引号内包含 ASCII 逗号或括号、运算符包裹及链式调用的情况。优化仅覆盖 Select，其他深层函数、资源解码和运行中单位实例仍可能消耗大量内存。

工具默认继续测量原版桌面核心。对比客户端优化时，显式开启以下属性，报告的测量环境会标记 `RWJS Select 迭代解析与求值`：

```powershell
java -Drwpp.heap.selectOptimization=true -Drwpp.heap.maxHeapMiB=256 -Drwpp.heap.stackMiB=1 -jar build\mod-heap-tool\RWJS-ModHeapTool.jar --analyze D:\Mods\example.rwmod --game-root "D:\Games\Rusted Warfare"
```

客户端通过注入访问器直接读写原版字段；工具的可选对比模式通过 agent 修改同一核心类，并复用公共迭代算法。对比时应固定 Java 版本、最大堆和线程栈大小，较小的堆会促使 GC 更早运行，不能把不同堆上限下的采样峰值差全部归因于优化。桌面核心测量与安卓 ART 真机结果仍需分别验证。

分发时复制 `RWJS-ModHeapTool.jar`，并让用户选择其完整游戏目录。JAR 包含核心、依赖和测量 agent；游戏运行资源与系统对应的原生库仍需要从原版安装提供。

## 结果含义

- **GC 后堆净增**：原版加载完成与模组加载完成后分别执行 GC，用 `MemoryMXBean` 读取 Java 堆，取后者减前者。包含解析缓存、单位和其他加载开销，可能受共享缓存释放及 GC 状态影响。
- **单位对象图堆**：从引擎实际加载的单位定义出发，通过 `Instrumentation.getObjectSize` 测量真实 Java 对象大小，按对象身份全局去重。跨单位引用在单位定义边界截止；多个单位共享的对象单列，排行显示每个单位的独占对象。独占总和加共享部分等于对象图总量。
- **加载采样堆峰值**：每 10 ms 读取一次加载期间的 Java 堆，包含解析临时对象。对象图遍历在采样结束后进行，因此测量工具自身的图遍历开销不混入加载峰值。采样可能漏掉瞬时峰值，不是重载峰值保证。
- **贴图/音频记账**：来自引擎该模组的 G / H 字段。虽然资源实际加载，它们仍是引擎账面数据，不能当作精确显存或 native 内存，也不能与 Java 堆简单相加得到进程总占用。

对象图是有边界的可达 Java 堆，不是堆快照支配关系分析得到的保留堆。类、类加载器、线程、弱引用和 direct buffer 的 native 管理对象不沿引用继续遍历；不可访问的字段会让测量失败，不静默漏算。该图不包括独立全局解析缓存，缓存由 GC 后堆净增反映。

结果对应当前桌面 JVM 的对象布局，不换算为 Android ART 堆。工具没有启动对局，因此不包含运行中的单位实例；不再显示固定 Android 512 MiB 预算或将占用乘二冒充实测。

## 界面验证

```powershell
java -jar build\mod-heap-tool\RWJS-ModHeapTool.jar --render-preview D:\Mods\example.rwmod build\mod-heap-tool\preview.png --game-root "D:\Games\Rusted Warfare"
```

此命令先执行真实测量，再离屏渲染 Swing 界面。自动化测试另验证共享对象去重、循环和跨单位引用边界、真实 agent 对象大小、反射失败、结果导出及批量 UI 行为。
