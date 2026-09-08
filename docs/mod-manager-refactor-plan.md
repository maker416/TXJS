# 模组管理器重构计划（RWPP/TXJS）

> 状态：已确认（2025 决策记录见 §2）
> 配套分析：与 `Rusted Warfare.apk`（RW 1.15）原版 `ModsActivity` / 引擎 `gameFramework/i.a|i.b` 的逐条对比，见会话分析记录；结论要点已并入 §1。

## 1. 背景：现状问题清单（代码实证）

| # | 问题 | 证据位置 | 后果 |
|---|---|---|---|
| P1 | UI 直接改引擎内存态（拨开关即写 `i.b.f`），但没有落盘；"保存/取消"语义不存在，退出页面行为不可预期 | `ui/Mods.kt changeModEnabled()` → 平台 wrapper `isEnabled` setter；全仓 `bW.d()` 只在 `ModManagerImpl` 的 reload/saveChange 内；Mods 页不调用 `modSaveChange` | 开关"改了又丢"或"没想改却生效" |
| P2 | 删除/更新已启用模组被迫 ≥2 次全量引擎重建；删除未加载模组退出也强制重载 | `Mods.kt`：delete 拦截 enabled（`mod.removeEnabledInfo`）→ 需先禁用+重载；`exit()` 对 `deletedMod` 无条件 `reloadMods()` | 慢、OOM 风险窗口翻倍 |
| P3 | 应用/重载守卫是散弹式 if 组合（`enabledNotLoaded`、`needsUnitRebuild`、`deletedMod`、OOM 锁），状态来源多（`loadedEnabledFileNames`、列表、引擎对象） | `Mods.kt` 中 `apply`/`reloadMods`/`exit` | 语义难懂、易漏组合、新手困惑 |
| P4 | 导入能力弱：单选、仅 `.rwmod`、重复文件拒绝覆盖 | `importModFile()`、`ExternalHandlerImpl.openFileChooser`（`*/*` 单选） | 无法批量、无法用新版覆盖旧版 |
| P5 | 缺文件级操作：无导出/分享入口 | 全仓 grep 无 `shareMod` 等价物 | 模组传播场景（QQ 群等）体验缺失 |
| P6 | 行信息缺失：无"已加载/未加载/未保存"持续状态、无存储提示 | 对比原版 `i.b.h()` 绿字、`menus.mods.*` 文案 | 用户不知道"为什么还没生效" |
| P7 | 代码结构：`Mods.kt` 1398 行单文件，引擎调用与 UI 状态混在 composable 内，几乎无单测 | `ui/Mods.kt` | 后续每步改动都高风险 |

## 2. 已锁定的决策（2025-XX-XX 确认）

1. **交互模型 = 方案 B：实时开关 + 脏标记 + 统一「保存并应用」**
   - 拨开关只改草稿（列表立即视觉反馈），不改引擎、不落盘；
   - 顶栏脏标记（"N 项更改未保存 · M 个模组待重载生效"）+ 卡片状态徽标（已加载 / 改动未生效 / 已导入未加载 / 错误 / 网络同步）；
   - 底部主按钮随状态切换：`完成` ↔ `保存并应用` ↔ `放弃更改`；执行中全宽进度（阶段文案，不可取消）；
   - 退出拦截三选一：`保存并应用` / `放弃更改` / `取消`（补回原版 Cancel 语义）；
   - 删除启用中的模组不再当场拒绝 → 改为"标记删除"，与开关改动合并到一次确认执行；
   - `重载数据`降为次级按钮（引擎外手动放文件场景），文案说明后果。
2. **本次范围 = 核心体验**：状态模型 + 保存/删除/导入流程（R1~R3，含导入覆盖升级与导出/分享）。**不做** SAF 开发文件夹链接、存储向导、模组拖拽排序。
3. **纳入 R0 引擎时序探针**：验证"仅保存、下一局生效"（PersistOnly）是否可行，再定 `deriveModApplyPlan` 是否启用免重载路径。

## 3. 目标与原则

**目标**：用户对"当前启用了哪些模组、哪些改动还没生效、下一步会发生什么"任何时候都一目了然；任何语义变化**最多触发一次**引擎重建，且只发生在用户主动确认的时刻。

**原则**
1. 单一事实来源：UI 持"草稿选择集"；引擎内存态只在执行应用那一刻被同步。
2. 引擎/UI 分层：composable 只做渲染与事件；状态机与执行计划推导放独立控制器 + 纯函数（可单测）。
3. 批量合并：一次会话内所有改动（开关、删除、导入）合并成一个执行计划，一次确认、一次引擎重建。
4. 兼容性：`ModManager` 对外 API 签名不变（`modReload(forceImmediate, enabledByFileName)` 等），`ModSyncController`/`Logic` 调用面零破坏；OOM 防线与"扫描后注入"机制保留。
5. 不引入新架构（无 ViewModel 栈），沿用 Compose 状态 + Koin + 事件总线，把逻辑从 composable 搬出。

## 4. 目标架构

```
┌─ ui/Mods.kt（拆分后仅 UI 组件）──────────────┐
│ ModsView / ModListPanel / ModCard / 各对话框   │
└──────────────┬──────────────────────────────┘
               │ 事件（toggle/delete/import/apply/cancel）
┌──────────────▼──────────────────────────────┐
│ ModsScreenController（commonMain，无 Compose） │
│   committed: Set<fileName>   ← 最近一次成功应用/进入时的引擎真实态
│   draft:     Map<fileName,Boolean>
│   pendingDelete: List<Mod>
│   pendingImport: List<ImportIntent>（含覆盖意图）
│   derivePlan(): ModApplyPlan  ← 纯函数
│   apply / discard / exit      ← 唯一会碰 ModManager 的地方
└──────────────┬──────────────────────────────┘
               │ ModManager（接口不变 + 少量新方法）
┌──────────────▼──────────────────────────────┐
│ 平台 impl（android/desktop）+ 注入点（保持现状）│
│   modReload/modSaveChange（现状）              │
│   + importFiles / exportMod                   │
└─────────────────────────────────────────────┘
```

**核心纯函数（`rwpp-core-api/game/mod/`，可单测）**：

```kotlin
enum class ModApplyKind { None, PersistOnly, FullRebuild /* 扫描+重建单位表 */ }

data class ModApplyPlan(
    val kind: ModApplyKind,
    val enabledByFileName: Map<String, Boolean>, // 应用时刻的完整期望状态
    val filesToDelete: List<File>,
    val filesToImport: List<File>,
    val summary: String, // 供确认框/i18n
)
fun deriveModApplyPlan(committed, draft, pendingDelete, pendingImport): ModApplyPlan
```

推导规则（收敛今天散落的守卫）：
- 无任何变化 → `None`，退出直接返回；
- 仅纯禁用开关、无删除且 R0 证明开局会按新集合加载 → `PersistOnly`（落盘不重建）；
- 其余（任何对已加载模组的启用/禁用/删除、任何新文件导入）→ `FullRebuild`。

## 5. 分期执行

### R0：引擎加载时序探针（半天～1 天，可并行）
- 读引擎 `a(false,false)` → `ag.e()`/`l.A()` 的调用点：启动、进单机、建房、进房、Mods 页重载各自何时触发；
- 确认"只改 modSettings 不重建"后，下一次游戏开局是否按新集合加载；
- 产出：时序结论 → 决定 `PersistOnly` 是否启用。

### R1：状态模型与控制器落地（UI 外观不变，纯重构）
- 从 `Mods.kt` 抽出 `ModsScreenController`，持有 committed/draft/pendingDelete/pendingImport；
- 平台 `getAllMods()` 的 `Mod` wrapper **只读化**（页面不再经 wrapper setter 写引擎）；
- 新增 `deriveModApplyPlan` + 单测（导入默认禁用、删除已加载、禁用未加载、网络模组、混合批量、OOM 锁后拒绝）；
- `ModSyncController` 回归验证（同步路径仍直走 `modReload`）。
- 验收：Mods 页行为与重构前完全一致（含守卫弹窗），引擎内存态只在应用瞬间被写。

### R2：保存语义与批量执行（第一波用户可见提升）
- 底部栏动态化与执行进度；退出脏检查三选一；顶栏脏标记；
- `exit()` 重构：一次应用 = 统一删文件（`deleteModFileSafely`）+ 导入/覆盖 + 一次 `modReload(enabledByFileName=draft)`；删除已启用模组改"标记 + 统一处理"；
- 卡片状态徽标（已加载/未生效/未加载），替代 `enabledNotLoaded` 弹窗式拦截；
- 保留：OOM 锁、失败清单对话框、`MemoryExhaustedDialog` 回归。
- 验收：语义变化最多一次全量重建；开关→退出→重启状态不丢（补 `modSaveChange` 调用与持久化断言）；放弃/取消与引擎态一致。

### R3：导入升级与导出/分享
- **导入 v2**（`ModManager.importFiles`）：Android 多选（`ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`）；扩展名分类（`.zip` 复制改名 `.rwmod`，`.tmx/.replay/.rwsave` 跳转各自页面或提示）；重复文件"覆盖/保留两者/取消"（覆盖 = tmp+rename 原子写，参照 `CustomMapsManagementView` overwrite 路径）；批量结果摘要；进度条；
- 导入后引导：确认框可勾选"导入并启用"，否则以 `UnloadedMod` 入列并显示"待启用"徽标；
- **导出/分享**：`exportMod(mod)`——desktop 存到所选目录；Android 系统分享（`.rwmod` 直发，文件夹模组经 `zipFolderToByte` 打成临时 `.rwmod`）；入口与平台 `ContextMenuArea` 对齐；
- 卡片补信息：存储位置提示（外置目录模组"读取较慢"）、minVersion 对齐警告（可选）。
- 验收：更新已启用模组 = "导入覆盖 → 保存并应用"一步完成。

### R4：回归与收尾
- 手工回归矩阵：Android/Desktop × 单机建房 / 联机房间 / 模组同步（缺模组加入、清单一致、取消加入清理）/ 大模组重载 OOM 提示 / 进程重启后状态 / 快速反复进入退出；
- i18n 全量走 `bundle_zh/en.toml` 并跑 `BundleParseTest`；
- 性能：重载前后堆占用日志（已有）验证无"同页两次 FullRebuild"；
- 文档：更新 `AGENTS.md` 模组管理小节、`docs/mod-system-internals.md` 状态机说明、本计划标记完成状态。

## 6. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| R1 | "仅保存延迟到下一局"依赖引擎开局才重建单位表；若启动时已 eager 加载则不成立 | R0 先探明；不成立则全走 FullRebuild，但保证一次会话最多一次 |
| R2 | 触碰 reload/saveChange 周边可能影响模组同步（心跳/forceImmediate 时序） | R1 保持 API 与行为不变并跑同步回归；新控制器不接管同步路径 |
| R3 | 应用瞬间引擎对象是新扫描实例（`ModReloadSelection` 注入时序），wrapper 只读化后须在应用时重算"文件名→引擎对象"映射 | 沿用 `resolveModEnabledByFileName` + 注入点，不在页面缓存引擎引用 |
| R4 | OOM：合并执行减少次数但单次重建风险不变 | 现有防线全保留；确认框文案提示大模组风险 |
| R5 | `Mods.kt` 拆分期间行为漂移 | R1 纯重构先行 + 现状行为对照验收 |

## 7. 交付物清单（文件级变更预估）

- `rwpp-core-api/game/mod/`：新增 `ModApplyPlan.kt`（纯函数 + 枚举）；`ModManager.kt` 增 `importFiles/exportMod` 等 + wrapper 只读语义注释
- `rwpp-core/.../ui/Mods.kt` → 拆分为 `ui/mods/{ModsView,ModsCard,ModsDialogs,ModsImport}.kt` + 新 `ModsScreenController.kt`
- 平台：`ModManagerImpl`（android/desktop）增批量导入/导出实现；Android `ExternalHandlerImpl` 增多选；desktop 增导出对话框
- 单测：`deriveModApplyPlan` 系列 + 导入分类改名用例；`bundle_*.toml` 新增文案
- 文档：§5 R4 所列更新
