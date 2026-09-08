# 模组管理器重构 —— 任务交接书（给接手模型 / 开发者）

> 使用方法：让接手方**先读完本文件 §1 与 §5 的必读清单**，再按 §6 交付。本文件与
> `docs/mod-manager-refactor-plan.md` 配套：计划文档管"做什么、为什么、验收标准"，
> 本交接书管"接手方要具备的最小上下文、硬性约束、第一步做什么"。

## 0. 任务一句话

重构 RWPP/TXJS（铁锈战争极速版启动器）的模组管理器，目标是优化用户管理模组的体验：
修正"开关不落盘/语义不清""删除或更新已启用模组被迫多次全量重载"等现状缺陷（详见计划 §1 的 P1~P7），
并按 `docs/mod-manager-refactor-plan.md` 已锁定的决策执行。

**已锁定的三个决策（不得擅自更改，如需调整先与负责人确认）：**
1. 交互模型 = **B：实时开关（只改草稿）+ 脏标记 + 统一「保存并应用」**；退出脏检查三选一（保存并应用/放弃更改/取消）。
2. 本次范围 = **核心体验**：R1 状态模型重构、R2 保存/删除批量语义、R3 导入 v2 + 导出/分享。
   **非目标（明确不做）**：SAF 开发文件夹链接、存储向导、模组拖拽排序、引擎注入体系改造。
3. **R0 引擎时序探针必须最先做**：验证"仅保存、下一局生效（PersistOnly）"是否成立，其结果决定 `deriveModApplyPlan` 是否启用免全量重载路径。

## 1. 接手方必读（按顺序，缺一不可）

| 顺序 | 文件 | 作用 |
|---|---|---|
| 1 | 仓库根 `AGENTS.md` | 项目总览：模块结构、构建命令、代码风格、i18n 编辑规范（**有坑必读**）、安全注意事项 |
| 2 | `docs/mod-system-internals.md` | 引擎模组系统混淆类映射 + RWPP 模组管理 API 与引擎的对应关系（**本任务的核心底层知识**） |
| 3 | `docs/mod-manager-refactor-plan.md` | **本任务的执行计划**：问题清单、目标架构、阶段划分、验收标准、风险表 |
| 4 | `docs/mod-system-internals.md` 之外的本次分析结论 | 见 §3"现状结论摘要"，均有代码证据，不必重复做原版对比调研 |

## 2. 工作区事实

- 仓库根目录：`D:\workspace\RW\TXJS`（Kotlin Multiplatform + Compose + Koin + Javassist 注入；AGPL-3.0）
- 游戏库已就位：`lib/game-lib.jar`（桌面）、`lib/android-game-lib.jar`（Android），勿动
- 原版参考 APK：`D:\workspace\RW\Rusted Warfare.apk`（RW 1.15，与 `android-game-lib.jar` 同源混淆命名）
- 原版反编译参考（已存在，可直接阅读）：
  - `D:\workspace\RW\rw\decompiled_apktool\`（apktool 产物：解码后 `res/layout/mods.xml`、smali、`assets/translations/Strings*.properties`）
  - `%TEMP%\rwapk\src\sources\com\corrodinggames\rts\`（jadx 全量反编译 Java 源码；关键类见 §4）——若已失效，重跑：
    `%TEMP%\rwapk\jadx\bin\jadx.bat -d <outdir> "D:\workspace\RW\Rusted Warfare.apk"`（jadx 已解压在 `%TEMP%\rwapk\jadx`）
- 常见构建/测试命令（详见 AGENTS.md）：
  - `./gradlew :rwpp-core-api:test`（单测，含现有 ModSync/解析测试）
  - `./gradlew :rwpp-core:testDebugUnitTest --tests BundleParseTest`（i18n bundle 合法性校验，**改过 bundle 必须跑**）
  - `./gradlew :rwpp-android:assembleDebug` / `:rwpp-desktop:packageReleaseUberJarForCurrentOS`

## 3. 现状结论摘要（已核实的代码事实，直接采信；个别存疑处会标注）

### 我们的实现
- UI 全部在 `rwpp-core/src/commonMain/kotlin/io/github/rwpp/ui/Mods.kt`（**1398 行单文件**，UI 与引擎调用混杂，是 P7）：
  - 双栏（启用/禁用）+ 卡片开关；顶栏搜索/统计；底部按钮：重载 / 导入 / 全部禁用 / 应用。
  - `changeModEnabled()` **拨开关即写引擎对象内存态**（平台 wrapper `Mod.isEnabled` setter 直写 `i.b.f`），但 `bW.d()` 落盘只发生在 `ModManagerImpl` 的 modReload/modSaveChange/modUpdate 内，**Mods 页从不调用 modSaveChange** → P1。
  - 删除仅限已禁用模组（`mod.removeEnabledInfo` 拦截启用中）；删除后退出 `exit()` 无条件 `reloadMods()` → P2（启用模组删除路径 = 禁用→重载→删除→退出再重载，两次全量重建）。
  - 守卫散弹：`enabledNotLoaded` / `needsUnitRebuild` / `deletedMod` / OOM 锁（`UI.modReloadMemoryExhausted`）在 `apply/reload/exit` 三处 if 组合 → P3。
  - 导入：`importModFile()` 仅 `.rwmod` 单文件；目标存在即拒绝（`mod.importAlreadyExists`）；导入物以 `UnloadedMod`（默认禁用）入列 → P4。
  - 无导出/分享 → P5；卡片信息少（无"未加载/未保存"持续徽标、无存储提示）→ P6。
- 引擎接口：`rwpp-core-api/.../game/mod/ModManager.kt`（modReload/modSaveChange/modReregister/modUpdate + 查询）；平台实现 `rwpp-android/.../impl/ModManagerImpl.kt`、`rwpp-desktop/.../impl/ModManagerImpl.kt`；注入点 `ModManagerInject.kt`（android 在引擎 `j()` 扫描后、桌面 `k()` 后应用 `ModReloadSelection`，**时序敏感，勿动**）。
- `modReload(forceImmediate, enabledByFileName)` 语义：全量扫描+重建单位表；`enabledByFileName` 按磁盘文件名（小写）映射启用态，未列出者默认禁用；`forceImmediate=true` 为同步路径专用（绕过主循环投递，5s 超时兜底）。
- **模组同步（ModSync）强耦合**：`rwpp-core/.../core/ModSyncController.kt`、`Logic.kt` 直接调用 `modReload`（含 forceImmediate），依赖其现语义与心跳时序——**不得改变 modReload/modSaveChange 签名与默认行为**；重构只允许"新增方法/只读化 wrapper"，新控制器不得接管同步路径。回归必须手工验证同步场景。
- OOM 防线：进程内全量重载峰值内存约翻倍，脚本密集模组必撞大堆；`runReloadCore` catch OOM 置位 `UI.modReloadMemoryExhausted`，之后锁定一切重载（防二次 OOM 崩溃）。**该防线与 `MemoryExhaustedDialog` 行为必须原样保留**。
- 模组对象映射：启用 = 引擎 `i.b.f==false`；路径/文件操作安全性见 `Mod.kt deleteModFileSafely`（canonical 根白名单）；网络模组标记 `.network.rwmod`。

### 原版参考（RW 1.15 Android，`ModsActivity`，仅作对照，不要求照搬）
- 显式两段式：勾选 CheckBox 只动 UI；`Save Selection` 才 `bW.d()+bN.save()`（引擎 `i.a.d()`，格式 `name|path|enabled,...`）并给出摘要（"N will be used in next game"/"N not loaded, click Reload"）；Cancel/Back 干净丢弃。
- 长按行菜单：Export/Share（仅打包模组）、Delete（仅打包模组、任意状态可删、删文件不触发重载）、Unlink（SAF 链接模组）、Source 信息。
- 导入：多选、按扩展名分拣（`.zip`→改名 `.rwmod`；`.tmx/.replay/.rwsave`；`_map.png` 缩略图须带同批 .tmx）、已存在先删旧再原子写覆盖、内/外存储选择、批量摘要与跳过统计。
- 行信息：RAM + 存储速度警告（`Storage: slow external unpacked`）+ 错误红字 + 绿字状态（`Not yet loaded, reload needed` / `n more warnings...`）。
- 页头说明：服务器要求某 Mod 时禁用的也会被启用（`menus.mods.headerInfoAndroid`）；内部存储被卸载删除的提示。
- 引擎细节见 `docs/mod-system-internals.md`；反编译对照：`appFramework/ModsActivity.java`、`gameFramework/i/a.java`、`gameFramework/i/b.java`。

## 4. 关键文件索引（本任务会动到的）

**本仓库（改动目标）**
- `rwpp-core/src/commonMain/kotlin/io/github/rwpp/ui/Mods.kt` ← 拆分重构主战场
- `rwpp-core/.../ui/ModsAndMapsView.kt`、`ui/CustomMapsManagementView.kt`（参考其 overwrite 导入实现）、`App.kt`（导航）、`ui/UI.kt`（全局状态，OOM 标志所在）
- `rwpp-core-api/.../game/mod/`：`ModManager.kt`、`Mod.kt`、`ModEnabledLookup.kt`（含 `ModReloadSelection`）、`ModInfoParser.kt`、`ProtectedRwmodDetector.kt`、`NetworkModCache.kt`
- 平台：`rwpp-android/.../impl/ModManagerImpl.kt`、`rwpp-android/.../impl/inject/ModManagerInject.kt`、`rwpp-android/.../impl/ExternalHandlerImpl.kt`（文件选择器，需改多选）；`rwpp-desktop/.../impl/ModManagerImpl.kt`
- 依赖方（勿破坏）：`rwpp-core/.../core/ModSyncController.kt`、`core/Logic.kt`、`net/sync/*`（core-api）、`event/events/ReloadMod*.kt`
- 测试：`rwpp-core-api/src/test/kotlin/`（新增 `deriveModApplyPlan` 等单测）、`rwpp-core/src/test/kotlin/BundleParseTest.kt`

**反编译参考（只读）**：见 §2。

## 5. 硬性约束（违反即返工）

1. **i18n**：`bundle_zh.toml`/`bundle_en.toml` 运行时才解析，非法 TOML 或点分键冲突（同一键既是标量又是表）→ App 启动闪退。新文案键必须沿点分路径逐层查重，并跑 `BundleParseTest`（规范全文见 `AGENTS.md`「i18n bundle 编辑规范」）。
2. **版权头**：所有新建/改动的 `.kt` 保留 AGPL 双语版权头模板（照抄现有文件头部）；git 提交信息用中文。
3. **注入体系不动**：不改 `@Inject` 相关注解/注入点/`config.toml` 生成；`ModManagerInject` 的扫描后注入时序是"新模组默认禁用/按需启用"正确性的根基。
4. **ModSync 兼容**：`ModManager.modReload/modSaveChange` 签名与语义不变；新控制器不接管同步路径；手工回归联机同步场景（缺模组加入、取消、房主清单位、OOM 提示）。
5. **OOM 防线保留**：`UI.modReloadMemoryExhausted` 置位后拦截重载 + `MemoryExhaustedDialog` 指名元凶的行为，重构后必须存在且触发条件不变。
6. **应用时刻才写引擎**：wrapper 只读化后，页面不得在任何非应用路径修改引擎 `i.b.f`；"草稿文件名 → 新扫描实例"的映射在应用时经 `resolveModEnabledByFileName` 重算（引擎对象每次扫描可能换新）。
7. **R1 阶段 UI 行为必须与重构前完全一致**（纯重构验收标准），再进入 R2 的可见改动。
8. 代码风格：平台实现类 `*Impl`、注入模块 `*Inject`、接口/模型放 `rwpp-core-api`；Kotlin official style（`gradle.properties` 已设）。

## 6. 第一步任务：R0 引擎时序探针（接手后第一份交付）

**目标**：回答——"在 RWPP 启动器里，引擎自定义单位注册表（`ag.e()`/`l.A()` 链路）分别在哪些时机被（重新）构建：进程启动、进入单机、建房、进房、Mods 页重载？如果只把期望启用集合写入 `modSettings`（`bW.d()+bN.save()`）而不执行全量 `a(false,false)` 重建，下一次开局/进房是否会按新集合加载单位？"

**方法建议**：从 `rwpp-android/.../impl/ModManagerImpl.kt` 与注入点出发，用 jadx 源码（`%TEMP%\rwapk\src\sources`）追 `a(boolean,boolean)`（`gameFramework/i/a.java`）→ `ag.e()`/`ag.h()`/`l.A()`（`game/units/custom/`）的调用链，并在本仓库检索 RWPP 侧所有触发点（启动加载、建房、进房、重载）。可加临时日志验证，不落正式代码。

**产出格式**：一份 ≤1 页结论（写进 `docs/` 或直接回复）：触发时机表 + "PersistOnly 是否可行"结论 + 理由（代码引用）+ 若可行需额外处理的风险（如单位表与设置不一致窗口）。

**决策影响**：可行 → `deriveModApplyPlan` 启用 `PersistOnly`（纯开关改动免全量重载，体验提升最大）；不可行 → 一律 `FullRebuild`，但保证一次会话最多一次。

## 7. 阶段顺序与验收（细节以计划文档为准）

| 阶段 | 内容 | 验收要点 |
|---|---|---|
| R0 | 引擎时序探针 | §6 产出；决定 PersistOnly |
| R1 | `ModsScreenController` + `deriveModApplyPlan` + wrapper 只读化；UI 外观零变化 | 行为与重构前完全一致；新单测通过；引擎只在应用瞬间被写 |
| R2 | 脏标记/退出三选一/批量执行/单次应用；删除已启用改"标记删除" | 一次会话最多一次 FullRebuild；开关→退出→重启不丢；放弃/取消语义正确 |
| R3 | 导入 v2（多选/`.zip` 改名/覆盖更新/批量摘要）+ 导出/分享 + 卡片状态徽标 | "导入覆盖 → 保存并应用"一步更新已启用模组；平台（Android 分享/桌面对话框）可用 |
| R4 | 回归矩阵 + i18n + 文档 | §8 矩阵全绿；`BundleParseTest` 过；AGENTS/CLAUDE/mod-system-internals 同步 |

## 8. 回归矩阵（R4 手工必测）

Android 与 Desktop × 以下场景：
1. 单机：开关改动 → 保存并应用 → 重启后状态保持；放弃更改后引擎态不变。
2. 删除已启用模组：标记删除 → 一次应用完成；对局/菜单无紫色占位贴图。
3. 导入：多选 .rwmod/.zip（改名）；重复文件覆盖；批量摘要正确。
4. 模组同步：加入缺模组房间（下载→重载→进房）；房主清单一致时跳过；取消加入清理；P2P 退化。
5. OOM：脚本密集型模组重载仍弹元凶对话框且不闪退、二次重载被锁。
6. 快速反复进入/退出模组页；进程内"未保存修改"提示不残留。
7. 导出/分享后文件可被原版游戏与另一设备正确加载。
8. 桌面端 Ctrl/右键菜单与 Android 长按入口等价。

## 9. 交付物清单（最终）

- `rwpp-core-api/game/mod/`：新增 `ModApplyPlan.kt`（`ModApplyKind` + `deriveModApplyPlan` 纯函数）；`ModManager.kt` 增加 `importFiles/exportMod` 等（按 R3），wrapper 只读语义注释
- `rwpp-core/.../ui/mods/`：拆分文件（ModsView / ModsCard / ModsDialogs / ModsImport）+ `ModsScreenController.kt`
- 平台：android/desktop `ModManagerImpl`（批量导入/导出）；Android `ExternalHandlerImpl` 多选；desktop 导出对话框
- 单测 + `bundle_zh/en.toml` 新文案 + 文档更新

---

## 附：可直接粘贴给新模型的交接 Prompt（自包含版）

```
你在接手一个已充分调研、方案已锁定的重构任务，禁止重新做前期调研，直接进入执行。
工作区：D:\workspace\RW\TXJS（Kotlin Multiplatform，AGPL-3.0）。
请先完整阅读并按顺序消化：仓库根 AGENTS.md、docs/mod-system-internals.md、
docs/mod-manager-refactor-plan.md、docs/mod-manager-handoff.md，然后回复我：
(1) 任务一句话复述；(2) 三条已锁定决策；(3) 你识别出的前 5 个最大风险。

任务：重构模组管理器（ui/Mods.kt 及其引擎链路），交互模型 = 实时开关(草稿)+脏标记+
统一「保存并应用」，范围 = 状态模型/保存删除语义/导入 v2/导出分享（明确不做 SAF 链接与
拖拽排序），并先完成 R0 引擎时序探针（PersistOnly 是否可行）。

硬约束：modReload/modSaveChange 签名语义不变（ModSync 强依赖）；不碰注入体系；OOM 防线
与 MemoryExhaustedDialog 必须保留；应用时刻才允许写引擎内存态；wrapper 只读化；
i18n 改 bundle_*.toml 必须过 BundleParseTest 且遵守点分键唯一性；.kt 保留 AGPL 版权头；
提交信息用中文；R1 阶段要求 UI 行为与现状完全一致（纯重构），R2 起才做可见改动。

执行顺序：先交 R0 探针报告（≤1 页：触发时机表+PersistOnly 结论+代码引用），经我确认后
再进入 R1→R2→R3→R4；每阶段提交独立 commit 并附验收自检；禁止一次提交跨阶段内容。
关键文件索引、原版参考材料位置、回归矩阵见 docs/mod-manager-handoff.md。
不清楚或与锁定决策冲突时先问我，不要自行扩大范围。
```
