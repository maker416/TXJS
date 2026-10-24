# 模组内存与平台问题修复验证（2026-10-24）

修复在独立工作树 `TXJS-memory-recovery`、分支 `fix/mod-memory-recovery` 完成。未修改用户提供的 `units.zip` 或其中模组内容；Android 覆盖安装保留应用数据。构建和测试证据收集到主仓库忽略目录 `build/review/mod-memory-recovery/`，发布候选产物在 `build/artifacts/mod-memory-recovery/`。

## 修复范围

| 问题 | 处理与验证 |
| --- | --- |
| 同步下载、上传和 PC 缓存加解密依赖整包 ByteArray，放大堆峰值 | 文件流、磁盘快照、校验后原子发布；PC 使用分块认证缓存。96 MiB 堆完成 60 MiB 文件下载、上传和加解密 |
| 旧“安绒宁静”深层表达式的瞬时堆峰值 | 优化深 select else 链，保留原版 AST 和校验；真实引擎差分与真机成功加载 |
| 超限后继续使用半初始化单位表、下次启动重复失败 | 加载日志、实际堆余量检查、进程级失败闸门和启动恢复选择；保留模组文件及原有配置 |
| Android 队列重载失败后仍执行同一帧，触发空对象崩溃 | 在原始帧前消费队列，执行后再次检查失败状态；真实注入函数回归及真机验证 |
| PC 连接完成回调早于 connector 赋值 | 分离完成信号和句柄发布；覆盖同步回调、取消和迟到句柄 |
| 加载框重试后关闭不能可靠取消新任务 | 每轮任务独立取消；Compose 回归覆盖重复打开、重试和关闭 |
| Android 文档 URI 无后缀时误拒绝地图 | 使用提供者 DISPLAY_NAME，IO 线程流式复制并原子发布；真机 URI `/123` 导入 `.tmx` 成功 |
| 异步导入暴露 AppCompat 主题不匹配崩溃 | 无界面的导入 Activity 改用 ComponentActivity；完成页面生命周期和真机导入验证 |

## Android 实测

设备 PLR110，Android 17 / API 37，实际 Java 最大堆 536,870,912 字节（512 MiB）。物理内存余量不能代替单进程 Java 堆余量。样本为 `units.zip` 中 7 个 `.rwmod`，约 884 MiB 压缩内容；外层 ZIP 不作为引擎模组加载。

### 原版对照与峰值来源

用户在原版 `com.corrodinggames.rts` v1.15 中勾选同一个“安绒宁静”。原版约 13 秒重载成功，UI 显示该模组资源估计 RAM 154.03 MB。约 1.2 秒间隔的 dumpsys 采样观测到 Java HeapAlloc 416,321 KiB，之后降至约 26 MiB。该采样不能代表绝对峰值，UI 的模组 RAM 数值也不能替代 Java 堆测量。

RWJS 优化前只启用此模组仍达到保护阈值。压力快照的对象 payload 约 398 MiB，其中 char[] 为 367,272,588 字节（约 350 MiB）；大数组内容是重复的嵌套 select 余串，调用栈停在 `Select.setArgumentsRaw` 递归链。`聊天气泡/汉字编号.template` 的表达式含 1,539 层 select。父解析栈保留长子串，形成远高于加载完成占用的瞬时峰值。

当前没有原版与 RWJS 同一解析深度的成对堆快照，因此不把峰值差额武断归因于某个 DEX 寄存器、Compose 或 GC 策略。此次优化直接消除了这类递归保留来源，并以真机结果验证效果。

### 结果

| 场景 | 结果 | Java 堆记录 |
| --- | --- | --- |
| 优化前，仅“安绒宁静”冷启动 | 约 13 秒后保护中止 | 停止时 483,341,088 B |
| 优化后，仅“安绒宁静”冷启动 | 成功进入主菜单；引擎阶段约 4.6 秒 | 检查点峰值 303,711,040 B（约 290 MiB），完成 28,587,088 B（约 27 MiB） |
| 优化后，单模组连续 3 次菜单重载 | 全部成功，约 3.1 秒/次 | 检查点峰值分别约 362 / 382 / 330 MiB；结束约 41 / 48 / 56 MiB |
| 单模组沙盒开局、返回父页 | 成功，日志记录 main 线程开局 | 未改动 Android 地图加载的 UI 线程契约 |
| 全部 7 个模组冷启动 | 仍超过此设备本轮加载预算，进入原生恢复页，进程存活 | 峰值 491,861,568 B，停止时 483,337,792 B |
| 菜单全部启用重载（7 个样本 + Mega Builders） | 约 133 秒后保护中止，失败闸门及恢复选择有效 | 峰值 488,846,160 B，停止时 483,263,312 B；不清空文件或原有启用配置 |
| 文档 URI `/123`、DISPLAY_NAME 为 `.tmx` | 225 字节完整写入并显示成功提示，页面正常关闭 | 辅助程序和测试地图已删除 |

`[MODMEM] peak` 是解析检查点观测值，不是连续采样，也不包含 native 图像、声音内存。结束值未强制全部归一到 GC 后，不可把 3 次结束数值的差异直接判定为泄漏。当前不能承诺 7 个模组在该 512 MiB 堆设备上同时运行。

关键证据：`device/original-single-heap.log`、`device/original_single_loaded.png`、`device/rwjs-heap-summary.txt`、`device/optimized-single.log`、`device/optimized_sandbox_start.png`、`device/optimized-warm-3.log`、`device/optimized-all-progress.log`、`device/final-all-reload.log`、`device/final_all_reload_state.png`、`device/final_import_success.png`、`device/final-import-process.log`。

## PC 真实核心与兼容性

隔离 JVM 使用仓库真实游戏核心、完整资源、离屏 OpenGL 和音频加载；Java 21，`-Xmx512m -Xss16m`，不读取或保存用户桌面游戏配置。这不是完整桌面 UI 的低堆启动测试。

“安绒宁静”优化前、优化后均有 227 个启用自定义定义。按真实联机使用的 `custom.l.d` 中 `M`（内部名称）与 `H`（配置校验）排序求 SHA-256，结果一致：

```text
b8ec05df69e1eaf6a49950f3bc1402750e6dd26a5a29930ad78c4d99090655b6
```

优化后连续 10 次重载全部成功，摘要保持一致，显式 GC 后占用约 51–52 MiB，没有观察到持续累积。首轮约 3.2 秒，后续约 1.1–1.4 秒。原版 `am.bM()` 只覆盖内置单位，本报告没有把它作为自定义模组一致性证据。配置摘要本身不能证明求值语义，另有真实引擎 AST/结果差分测试补充。

组合 PC 探针还观察到样本资源兼容问题：`缓冲区` 的 `NYPD.jpg` 实际头部为 RIFF/WEBP，原桌面 ImageIO 路径不能解码；一些音频也有既有解码告警。未改写用户模组内容，也不把这个探针结果表述为 7 个模组在 PC 上全部通过。优化前后的单模组对照使用同一资源加载器。

关键证据：`pc/single-before-select.log`、`pc/single-after-select.log`、`pc/combined-before-select.log`。早期基线探针曾被引擎“过多新模组自动禁用”干扰；最终 helper 明确检查每个请求模组启用状态，上述单模组报告不使用被禁用的无效运行作为证据。

## 自动化验证与产物

最终完整命令：

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-core-api:resourceLoadSafetyTest :rwpp-core-api:lowMemoryModTransferTest :rwpp-core:desktopTest :rwpp-desktop:test :rwpp-android:testDebugUnitTest :rwpp-android:assembleRelease :rwpp-desktop:packageReleaseUberJarForCurrentOS --console=plain
```

| 任务 | 用例数 | 失败 | 条件跳过 |
| --- | ---: | ---: | ---: |
| core-api:test | 427 | 0 | 0 |
| resourceLoadSafetyTest | 3 | 0 | 0 |
| lowMemoryModTransferTest | 2 | 0 | 0 |
| core:desktopTest | 167 | 0 | 3 |
| desktop:test | 34 | 0 | 1 |
| android:testDebugUnitTest | 20 | 0 | 0 |
| 合计 | 653 | 0 | 4 |

共 649 次通过、4 次既有条件跳过（低堆任务含独立 JVM 重复验证）。新增 6 个真实表达式差分测试涵盖 1,539 层、number/string/bool/unit、短路求值、大小写/空白、嵌套分支和错误一致性；两端生成的 Select 注入元数据实际应用到游戏 JAR 并经 Javassist Analyzer 检查。其余覆盖流式传输、Range、取消、缓存认证、原子替换失败、启动恢复和同帧失败闸门。

Android Release APK、Windows fat JAR 构建成功。候选 APK 已在测试手机覆盖安装；不是发布到远程渠道。产物目录的校验清单记录源码提交与 SHA-256。

## 尚未覆盖

未进行双客户端真实 relay 房间的 OOM 端到端验证；相关取消、串行化、失败传播和清理路径有自动化验证与代码核对。没有穷尽所有模组、对局时长、原生内存和厂商杀进程情况。有限次重载成功不能证明不存在任何内存泄漏。
