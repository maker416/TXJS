# 同名模组替换后仍读取旧内容

## 日志结论与边界

2026-11-14 的玩家反馈与日志符合「磁盘文件已变化，引擎仍复用旧 `.rwmod` ZIP 句柄及目录索引」的问题。现有删除流程会调用真实文件删除；这份日志不足以认定删除只是界面操作，也不能判断玩家具体通过哪个入口删除、导入。

关键证据均来自 `rwjs-2026-11-14.log`，行号为原日志的一基行号：

| 时间 | 行号 | 观察 |
|---|---|---|
| 10:02:49 | 613 | 首次为 `铁锈感染.rwmod` 输出 `Opening new zip`。 |
| 首轮加载 | 803、805–830 | `unitCount: 1`，指挥中心出现 `Could not find canBuild target`。 |
| 10:05:13 | 1012 起 | 同一路径出现 `Stale timestamp`，随后 `Cache miss → Reading → Wrote cache`，仍是旧单位数及同一错误。 |
| 10:05:56、10:07:09、10:08:23、10:08:46 | 1326、1711、2019、2314 起 | 再次检测到修改时间变化并重写磁盘缓存，但均没有再次打开该 ZIP，旧错误持续。 |
| 10:09:28 | 2581 | 新进程 `START`。 |
| 10:09:32 | 2998、3089 | 同一路径重新 `Opening new zip`，随后 `unitCount: 2`，未再出现上述 `canBuild` 错误。 |

11 月 13 日日志也出现修改文件后反复重载、重启后才改变解析结果的模式。以上证据将问题定位到资源读取链，而非仅有列表标题残留：磁盘缓存已识别新时间戳，却从进程内旧 ZIP 读回内容，再将旧内容写入缓存。进程重启销毁这些句柄后才读取新包。

日志没有记录每次删除、导入的返回值、入口或文件 SHA-256。因此「发生了同路径文件变化」「重载继续复用旧 ZIP」有直接证据；每次文件变化是否来自删除重导入或直接覆盖，只能结合玩家描述判断。

## 修复位置

共享的 `ModSourceChanges` 在删除、导入和替换写入前发布规范路径的变更代次，同时作废游玩统计哈希。`ModSourceTracker` 比较文件是否存在、长度、修改时间及显式变更代次。显式代次覆盖「删除再导入但扫描没有观察到缺失状态」以及客户端写入保留文件长度、时间戳的情况；未变来源继续复用缓存。

Android 的 `ModManagerInject` 在原引擎扫描 `i.a.j()` 前后接入 `AndroidModSourceCache`；桌面端在 `i.a.k()` 前后接入 `DesktopModSourceCache`。扫描前按物理来源关联引擎逻辑路径和已打开 ZIP 的别名，只处理有变化的压缩包：

1. 使用引擎原有 ZIP 移除、延迟关闭入口，使后续读取重新打开文件，不立即关闭仍可能被读取的流。
2. 清除该来源在 `mods-info`、`mods-dir-search`、`mods-dir-music` 三组中的磁盘缓存，涵盖包内子路径，保留相邻名称模组的缓存。
3. 移除旧模组注册对象，使标题、包内根目录和单位发现重新计算；暂存其禁用标志及设置已加载标志。
4. 扫描后将保存的状态恢复到新对象，再应用本次调用的 `ModReloadSelection`。显式的新选择优先，不能因重建对象默认启用模组。

Android 对应 `utility.aj` 的 ZIP 表；桌面端对应 `utility.ag`。这些混淆类及字段绑定由真实引擎类回归测试约束。

## 贴图、音效与线程约束

仅重新打开 ZIP 仍不足以刷新按相同资源路径缓存的贴图和音效。来源变化时先从资源缓存摘除对应 key，让本轮解析重新读取资源；旧对象暂存，不能在解析前销毁仍被旧单位或菜单场景引用的资源。

成功重载后再依据当前缓存、单位定义和共享占位图的引用决定释放；仍被引用或释放失败的对象留待后续成功重载处理，失败重载不强行释放。Android 不主动 `Bitmap.recycle()`，桌面纹理释放继续遵循原 GL 上下文要求。

文件写入线程只发布变更，不直接操作引擎缓存。普通重载和房内保连接重载仍使用原有线程及 GL 路径；本修复不迁移 Android 引擎入口，也不把耗时解析移入保活主循环。

## 验证记录与复现

2026-11-16 最终代码验证通过，日志为工作树 `build/mod-source-cache-verified.log`（`BUILD SUCCESSFUL`）：

- `rwpp-core-api:test`：659 项测试，全部通过。
- Android 来源缓存、资源缓存和扫描注入回归：8 项，全部通过。
- Desktop 来源缓存、资源缓存和注入回归：11 项，全部通过。
- `rwpp-android:compileReleaseKotlin` 与 `rwpp-desktop:compileKotlin` 均通过。

上述测试合计 678 项，无失败、错误或跳过。可用以下命令复核：

```powershell
.\gradlew.bat :rwpp-core-api:test
.\gradlew.bat :rwpp-android:testDebugUnitTest --tests '*AndroidModSourceCache*' --tests '*AndroidModResourceCacheTest'
.\gradlew.bat :rwpp-desktop:test --tests '*DesktopModSourceCache*' --tests '*DesktopModResourceCacheTest' --tests '*DesktopModLoadSafetyInjectionTest'
.\gradlew.bat :rwpp-android:compileReleaseKotlin :rwpp-desktop:compileKotlin
```

自动回归覆盖删除重导入的变更检测、同路径替换、包内根目录变化、相同长度及时间戳的显式替换、未变包缓存复用、相邻包名隔离、禁用状态恢复、扫描失败重试，以及资源延迟释放和缓存所有权归还。两端扫描注入均经真实游戏库字节码校验。

真实 ZIP 测试先复现引擎继续读取旧包，再检查刷新后读取新包。Windows 会锁住打开的 ZIP，无法直接执行 Android/Linux 的「删除路径但旧描述符仍可读」操作；Windows 测试以旧包快照承接底层句柄，保留真实引擎的索引与缓存映射来模拟该状态，不改变生产代码。

设备验收应在同一进程先加载有错误的旧包，禁用并删除，再导入同名修复包。重载后核对新标题、包内目录、单位数、单位定义、贴图和音效；随后重复无文件变化的重载，确认正常复用缓存。还需覆盖房内保连接同步，确认网络保活与正常退房不受影响。宿主 JVM 的真实引擎类测试不能替代 Android 真机或桌面实际对局验证。

当前本地依赖已同步，Java 21、Android SDK 37.1、原 Release 签名、Inno 引擎 6.7.4 及 Windows 原版资源 ZIP 已核验；未生成完整 APK/桌面发行包，也未执行双端实际运行验证。主检出缺少 Linux 原版 ZIP，旧 `game-archive.local.txt` 也指向失效的 Linux 路径，故不能宣称具备 Linux 完整包构建资源。Windows 使用有效的 `build/Rusted-Warfare-win.zip`，优先于该旧路径。

## 检测边界

本修复针对引擎 `.rwmod` 压缩包来源。客户端已知写入通过变更代次检测；客户端外部工具修改文件通常通过长度或修改时间检测。如果外部工具同时保留长度和时间戳，且没有经过客户端变更通知，当前轻量检测不能保证识别，不以全量 SHA-256 扫描替代每次模组扫描。
