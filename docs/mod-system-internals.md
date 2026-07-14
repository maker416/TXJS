# 铁锈战争模组系统内部机制

> 基于反编译 `android-game-lib.jar`（Android 端混淆类）分析整理。
> 桌面端 `game-lib.jar` 类名映射不同但逻辑一致。

## 1. 核心类映射

| 层 | 混淆类（Android） | 混淆类（Desktop） | 作用 |
|---|---|---|---|
| 引擎主类 | `gameFramework.k` | `gameFramework.l` | `GameEngine.t()` 的返回类型，单例 |
| 模组管理器 | `gameFramework.i.a` | — | `GameEngine.t().bW` 的类型 |
| 模组对象 | `gameFramework.i.b` | — | 单个模组的注册信息 |
| 设置引擎 | `SettingsEngine` | 同 | `GameEngine.t().bN`，持久化 modSettings |
| 路径工具 | `gameFramework.e.a` | — | 目录扫描、路径解析 |
| 单位注册表 | `game.units.custom.l` / `ag` | — | 单位定义管理 |

## 2. 模组对象 `i.b` 关键字段

| 字段 | 类型 | 默认值 | 含义 |
|------|------|--------|------|
| `f` | `boolean` | `false` | **禁用标志**。`true`=禁用，`false`=启用。RWPP 中 `Mod.isEnabled = !it.f` |
| `g` | `boolean` | `false` | **设置已加载标志**。`e()` 方法从 modSettings 恢复状态后置 `true` |
| `c` | `String` | — | 模组显示名 |
| `e` | `String` | — | 模组路径（引擎内部格式，用于 `b(path)` 查找和 `d()` 保存） |
| `q` | `String` | — | 模组名（备用，来自 ini） |
| `s` | `String` | — | 描述 |
| `P` | `String` | `null` | 错误信息（`null`=无错误） |
| `a` | `int` | 自增 | 模组 ID |
| `B` | `boolean` | `true` | 构造时为 `true`，`a(false,false)` 后可能变 `false` |

> **关键**：构造函数 **不初始化 `f`**，Java 默认 `false`（启用）。这是新模组默认启用的根因。

## 3. 模组管理器 `i.a` 关键方法

### 3.1 `d()` — 保存状态

```
遍历 e 列表 → 对每个模组构造 "name|path|enabled/disabled" → 用 "," 拼接
→ 存入 SettingsEngine.modSettings
→ 设置 modSettingsVersion = 1
```

格式：`"ModA|/path/to/A.rwmod|enabled,ModB|/path/to/B.rwmod|disabled"`

### 3.2 `e()` — 恢复状态

```
读取 SettingsEngine.modSettings → split(",") → 每项 split("|") → [name, path, state]
→ b(path) 在 e 列表中查找模组对象
→ 如果找到：
     state=="enabled"  → it.f = false, it.g = true
     state=="disabled" → it.f = true,  it.g = true
→ 如果没找到（模组已被删除）：跳过，日志 "Did not find mod in settings"
```

> **关键**：`e()` 只处理 modSettings 中已记录的模组。扫描到的新模组不在 modSettings 中 → `e()` 不会碰它 → `f` 保持默认 `false`（启用）。

### 3.3 `f()` — 全部禁用

```java
for (i.b mod : e) { mod.f = true; }  // 全部设为禁用
```

### 3.4 `a(boolean p1, boolean p2)` — 重新注册/加载单位

```
1. 遍历 e 列表，重置每个模组的错误状态（P=null, 各种计数器=0）
2. 调用 j()（Android）/ k()（Desktop）扫描全部模组目录
3. 保存当前单位定义列表副本
4. 调用 ag.e()/ag.h() — 解析 f=false（启用）模组的单位定义
5. 如果 p1=true：检查丢失的单位定义
6. 调用 l.A() — 完成单位定义注册
7. 调用 p.F() / n.P() — 刷新玩家相关
8. 调用 f.i.n() / f.g.K() — 网络/其他刷新
```

> **关键**：目录扫描发生在这个方法内部。调用者在进入方法前修改 `e` 中旧对象的 `f`
> 仍然不够，因为步骤 2 会创建本次才发现的新对象。必须在 `j()/k()` 返回后、
> `ag.*()` 开始解析单位前应用状态。

### 3.5 `a(String dir, boolean, boolean)` — 扫描目录

```
列出 dir 下的文件/目录 → 对每个 .ini 文件或子目录
→ 调用 a(name, shortName, fullPath, displayName, p1, p2) 创建/注册模组
```

### 3.6 `a(String, String, String, String, boolean, boolean)` — 创建模组

```
1. b(path) 检查是否已存在同路径模组
2. 如果不存在 → new i.b()，并根据扫描目录参数设置 `f`
3. 设置 c=name, d=shortName, e=path, q=displayName 等
4. 调用 `i.b.j()/r()` 读取 `mod-info.txt` 元数据
5. 添加到 e 列表
```

> 创建方法只读取模组元数据，不在这里解析单位定义；真正的单位加载发生在
> `a(boolean, boolean)` 扫描完成后的 `ag.e()/ag.h()`。

### 3.7 `b(String path)` — 按路径查找

```
遍历 e 列表 → 匹配 it.e.equals(path) → 返回找到的 i.b 或 null
```

### 3.8 `a()` / `b()` — 统计计数

- `a()` 返回：启用（`f==false`）且正常（`B==false`）的模组数
- `b()` 返回：启用（`f==false`）且有错误（`P!=null`）的模组数

## 4. 完整重载流程（`runReloadCore`）

```
RWPP ModManagerImpl.runReloadCore():

1. bW.d()               ← 保存当前所有模组状态到 modSettings 字符串
2. bN.save()            ← 持久化到磁盘
3. t.bo = true          ← 设置标志
4. t.f()                ← 只停止线程并清理当前场景，不扫描模组
5. 激活 RWPP 本次重载的文件名→启用状态映射
6. bW.a(false, false)   ← 扫描并重建单位定义注册表
     a. 调用 j()/k() 扫描 units/，创建新模组对象
     b. j()/k() 的 InsertAfter 注入立即应用 RWPP 状态映射
     c. ag.e()/ag.h() 只解析此时 f==false 的模组
7. 关闭本次状态映射并保存扫描后的 modSettings
8. t.bo = false
9. t.q()                ← 后处理
```

### 4.1 为什么新模组被自动加载

```
时间线：
  d()/e() 保存状态（此时 e 中没有新模组）
  → a(false,false) 内部调用 j()/k() 扫描，新模组进入 e
  → 旧实现曾在调用 a(false,false) 之前应用状态，只能覆盖旧对象
  → 新对象仍按引擎默认状态进入 ag.e()/ag.h()
  → 单位定义已经被解析后再设 f=true，时机过晚
```

## 5. RWPP 模组管理 API 映射

| RWPP 接口 | 引擎调用 | 说明 |
|-----------|---------|------|
| `modReload()` | 保存 → `t.f()` → 受控 `a(false,false)` → `t.q()` | 停止当前场景后全量重载 |
| `modSaveChange()` | 保存 → 受控 `a(false,false)` → 再保存 | 应用状态并重建单位定义 |
| `modUpdate()` | `k()` | 保存 SAF 链接 |
| `getAllMods()` | 读 `bW.e` 列表 | 返回所有模组 |

## 6. 解决方案

### 问题总结

1. **新模组被自动加载**：旧实现调用 `a(false,false)` 前就应用启用状态，但新模组是在该方法内部的 `j()/k()` 扫描中才创建，因此状态映射没有覆盖新对象。扫描后的 `ag.e()/ag.h()` 随即按引擎默认状态解析了它。

2. **UnloadedMod 不持久化**：UnloadedMod 只存在于 Compose 状态中，退出再进入模组界面时 `mods` 从 `getAllMods()` 重新初始化，引擎列表中不包含该模组 → 列表中消失。

### 方案：UnloadedMod + 扫描后注入 + 已加载集合

三个机制配合：

**`scanUnloadedMods(existing)`** — 扫描 `modDir` 中的 `.rwmod` 文件，过滤掉引擎已扫描的（按文件名匹配），返回 `UnloadedMod` 列表（默认禁用）。

**`ModReloadSelection` + `ModManagerInject`** — `modReload/modSaveChange` 激活完整 UI 状态；
Android 在 `i.a.j()`、Desktop 在 `i.a.k()` 返回后注入，将状态写入本次扫描产生的全部
模组对象。该时点位于单位解析之前。

**`loadedEnabledFileNames`** — Mods 页面记录最近一次成功重载时真正启用的文件名。
若用户打开了不在集合中的模组，“应用”只提示“已启用但尚未加载”，要求先点“重载”。

```
导入流程：
  copyToWithProgress()  → 仅复制文件
  mods.add(UnloadedMod) → UI 列表显示（禁用），引擎零参与

退出再进入：
  mods = getAllMods() + scanUnloadedMods(getAllMods())
  → 文件系统扫描发现新 .rwmod → 以 UnloadedMod 显示（禁用）→ 持久化 ✓

点击重载：
  knownStates 记录所有模组状态（含 UnloadedMod: false）
  modReload() → a(false,false) 内部 j()/k() 扫描
  j()/k() InsertAfter → 对新旧对象应用 knownStates
  ag.e()/ag.h() → 只解析启用模组
  getAllMods() + scanUnloadedMods() → 引擎已包含该模组，scanUnloadedMods 返回空
  → UI 记录本次真正启用的文件名

点击应用：
  若存在 isEnabled=true 且不在 loadedEnabledFileNames 中的模组 → 提示先重载
  若只是导入了默认禁用模组且没有已加载模组被禁用 → 直接退出，不调用引擎
  否则 modSaveChange(knownStates) → 扫描后注入同样保证禁用新文件不被加载
```

### 涉及的类与方法

| 组件 | 说明 |
|------|------|
| `UnloadedMod` | 纯文件级 `Mod` 实现，不依赖引擎，默认 `isEnabled=false` |
| `scanUnloadedMods()` | 扫描 `modDir` 按文件名匹配，返回未被引擎加载的 `.rwmod` |
| `ModReloadSelection` | 一次受控扫描期间共享文件名启用状态 |
| 平台 `ModManagerInject` | 在 Android `j()` / Desktop `k()` 扫描后应用状态 |
| `ModsView.mods` 初始化 | `getAllMods() + scanUnloadedMods()` 合并 |
| `ModsView.reloadMods()` | 传入 `knownStates`，成功后更新已加载集合 |

## 8. 模组元数据轻量读取

引擎在创建模组对象时调用 `i.b.j()`，该方法调用 `m()` 从 `.rwmod`（ZIP）中读取 `mod-info.txt`，仅解析元数据（名称、描述、版本），**不加载单位定义**。

### mod-info.txt 格式（INI）

```ini
[mod]
title=模组名称
description=模组描述（支持 \n 换行）
minVersion=1.15

[music]
sourceFolder=music/
```

### 引擎解析流程（`i.b.j()` → `i.b.m()`）

1. 构建路径 `sourceFolder + "/mod-info.txt"`
2. 如果是 `.rwmod`（`h==true`）→ 从 ZIP 读取（`g.c("mods-info", path)`）
3. 如果是目录（`h==false`）→ 直接读文件（`a.k(path)`）
4. 解析 INI：`ae.get("mod", "title", null)` → `q` 字段，`ae.get("mod", "description", null)` → `s` 字段

### RWPP 中的轻量解析（`parseModMetadata`）

`UnloadedMod` 在构造时调用 `parseModMetadata(file)`，打开 `.rwmod` 作为 `ZipFile`，读取 `mod-info.txt` 并解析 `[mod]` 段的 `title`/`description`/`minVersion`。开销极小（只读一个小文本文件），不触及单位 `.ini` 文件。

## 9. 模组状态持久化格式

`SettingsEngine.modSettings`（String）：

```
ModName1|/path/to/mod1.rwmod|enabled,ModName2|/path/to/mod2.rwmod|disabled
```

- 分隔符：条目间 `,`，字段间 `|`
- 字段：`[显示名, 引擎路径, enabled|disabled]`
- `d()` 写入，`e()` 读取
- `bN.save()` 持久化到磁盘
