# 存档列表与管理

## 问题根因

原来的 `GameImpl.getAllMaps()` 将存档与普通地图一起长期缓存。玩家在进房前已经触发过地图枚举，局内新保存的文件不会进入缓存；再次打开「加载保存的游戏」只会看到旧列表，直到手动刷新或重启。启动器也缺少独立的存档管理入口。

旧实现还把存档统一当成「去后缀的地图名 + `.rwsave`」处理。原版加载器使用真实文件名且不自动补后缀，因此历史无扩展名存档、名称中带点号的旧档不能通过任意截断再拼接来加载。Android 的组合文件后端还支持应用私有存档，必须保留 `[INTERNAL-PATH]/` 标识，避免加载到公共目录里的同名档。

旧单人加载流程先建立默认遭遇战房间，再以多人模式读档；房间的原版入口丢弃了读档布尔结果。存档损坏时可能把默认地图当作读档成功。现在单人直接调用原版读档（`network=false`），检查返回值与地图加载状态，成功后才切换画面；失败保留管理页并清理残缺会话。房间存档加载也检查原版返回值，交给既有开局失败恢复处理。

## 当前行为

- 单人游戏的加载列表与房间存档选项每次重新扫描磁盘，不依赖普通地图缓存。
- 原「模组与地图」入口更名为「本地内容」，其「模组 / 地图 / 存档」第三个标签提供搜索、时间排序、继续游戏、导入、重命名与删除；单人游戏页的加载入口直达同一管理页。
- 列表显示 `.rwsave` 文件，包括损坏档，便于管理。历史无扩展名或自定义扩展名存档通过原版 `writeUTF("rustedWarfareSave")` 头标识识别；`.map`、`.tmp`、`.tmp2` 与子目录不进入列表。
- 展示名只移除末尾 `.rwsave`，保留名称里的点号、下划线等字符。引擎加载使用完整真实文件名，私有后端另带路径标识。
- 重命名与删除同步处理 `<完整存档文件名>.map` 伴随文件。只管理指定存档根目录内的直属普通文件，不跟随符号链接或递归删除。
- 导入重名时自动追加 ` (2)` 等序号另存，保留已有进度与源文件。复制先写 `.tmp2`，完整复制后再发布；协程调用使用 `runInterruptible(Dispatchers.IO)`，中断时清理未发布的临时文件。

## 存储位置与线程

主目录统一由 `AppContext.externalStoragePath("saves/")` 提供：桌面端为游戏运行目录的 `saves/`，Android 为公共 `rustedWarfare/saves/`。Android 同时读取 `AppContext.internalStoragePath("saves/")` 中的旧档；新存档和导入仍写主后端。两处同名文件分别展示并使用各自的加载标识，重命名或删除针对条目实际所在目录。

扫描、大小/时间采集和文件管理是纯文件 IO，可在 IO 调度器执行。加载和开始会话继续沿用 `GameSessionController.beginSession` 及原有平台线程契约；Android 引擎入口不能因为新增文件扫描而迁入游戏线程。

## 验证

自动回归：

```powershell
.\gradlew.bat :rwpp-core-api:test --tests SavedGameFilesTest
.\gradlew.bat :rwpp-desktop:test --tests '*SavedGame*' --tests '*DesktopGameThreadDispatcherTest'
.\gradlew.bat :rwpp-android:testDebugUnitTest --tests '*MatchStartInjectionTest' --tests '*SavedGameStorageTest'
.\gradlew.bat :rwpp-core:desktopTest --tests '*SavedGamesManagementUiTest'
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests BundleParseTest
```

`SavedGameFilesTest` 覆盖新鲜列表、完整文件名、旧档、损坏档、伴随文件、目录边界、同名导入、不完整复制、中断清理，以及 Android 双后端同名档定位。桌面集成回归在预热地图缓存后新建存档，并用真实引擎路径解析器核对加载文件名。

真实桌面引擎保存/恢复回归需显式指定包含完整资源与 native 库的原版目录，并提供可用 OpenGL/音频设备：

```powershell
.\gradlew.bat :rwpp-mod-heap-tool:test --tests '*SavedGameRoundTripIntegrationTest' '-Drwpp.heap.integrationGameRoot=<完整原版游戏目录>'
```

该测试在独立 JVM 与临时工作目录中加载真实地图，新建存档，改变内存状态后再以单人模式读档，核对时间、玩家席位、玩家名、资金、AI 类型与单位对象数量，同时验证损坏档和未来版本档返回失败。原版目录只作为资源与 native 库来源，不读取或修改用户已有存档。未传入目录时测试会跳过，不能将跳过当作实测通过。

2026-11-16 已执行通过：`roundtrip=true`、`networkLoad=false`、`corruptRejected=true`、`newerRejected=true`，恢复的游戏时间为 `123456`、单位对象数为 `491`。结果写入 `rwpp-mod-heap-tool/build/reports/saved-game-roundtrip.properties`，JUnit 报告位于该模块的 `build/test-results/test/`。

实际对局验收：

1. PC 进入任意单人对局并保存，返回启动器后立即打开「加载保存的游戏」，确认无需刷新或重启即可看到新档并加载原进度。
2. 保存两次、重命名、删除、同名导入，再分别打开管理页与房间存档选择器，确认列表一致，原有文件内容未被覆盖。
3. 验证中文、空格、下划线与点号档名，以及带 `.map` 的外部地图存档；取消删除不改变文件。
4. Android 分别准备公共目录和应用私有目录的同名旧档，确认可独立加载、重命名和删除；检查已有存储权限不足时的失败提示与重试。

上述离屏真实引擎回归不能替代 PC 原生窗口中的完整操作与 Android 真机验收。
