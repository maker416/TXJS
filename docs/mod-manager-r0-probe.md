# R0 引擎时序探针报告 —— PersistOnly 是否可行

> 来源：jadx 反编译 `%TEMP%\rwapk\src\sources`（RW 1.15 Android，与 `android-game-lib.jar` 同源）。
> 结论先行：**PersistOnly 可行，但有精确前提**——改动仅限"单位已解析"模组的开关（启用↔禁用均可），
> 无导入、无删除；下一局（单机开局/建房/进房）自动按新集合生效，无需全量重建。

## 1. 单位注册表构建/生效时机表

| 时机 | 代码位置 | 行为 |
|---|---|---|
| 进程启动 | `game/i.java` GameEngine load（~L1811-1845） | `bW=new i.a()` → `j()` 扫描 → `e()` 应用 modSettings 到内存 `f` 标志 → `ag.e()` 解析**启用**模组单位入 `l.c` → 末尾 `ag.a(true)` 过滤出活动表 `l.d` → `ce.bt()` 校验和 |
| Mods 重载（原版 Reload / RWPP modReload、modSaveChange） | `appFramework/ek.java:26` → `i.a.a(false,false)` | 重置错误态 → `j()` 重扫 → `ag.e()` 重建 `l.c` → `l.A()` 现场单位重定向 → `p.F()`/`i.n()` |
| 局内编辑器手动 reload | `game/units/h.java:538/559` | `a(true,false/true)`，网络对局中拒绝（保持同步） |
| **单机开局** | `game/i.java` 开局方法 `a(z,z2,int)` L1941-1946 | 非网络 → **`ag.a(true)`：`l.d = c(true)`，按当前内存 `f` 从 `l.c` 重新过滤** |
| **联机成为主机**（包170 Become server） | `j/ae.java:5369-5372` | `if (!x && !aY) { ag.a(this.o); x=true }`（o=房间含 mod 标志）——**建房时同样按内存 `f` 重过滤** |
| 网络状态重置/离房 | `j/ae.java:5568` | `ag.a(this.o)` |
| 联机客户端开局 | `j/ae.java:2842`（SERVER_INFO 106）→ `game/i.java:1943` | 服务端把**自己的 `l.d` 全量单位数据**（`l.a(bg)`，l.java:618）推给客户端入 `l.e`；开局 `ag.c()` 覆盖本地 `l.d`；单位缺失抛 `bw` → "Server sync mismatch" 断连 |
| 存档/读档 | `gameFramework/aj.java:121/423-424` | 存档写 `l.d`；读档 `l.a(j)` + `ag.c()` |

关键机制（`ag.java`）：
- `l.c` = 全量已解析单位池（进程生命周期内保留）；`l.d` = 当前活动表（开局时从 `l.c` 按内存 `f` 过滤派生）。
- `ag.e()` 解析时**禁用模组直接跳过**（L2583 `if (bVar.f && !loadDisabledModData) { A=true; return; }`）→ 其单位**不进** `l.c`。
- `ag.a(z)`（L1641）= `l.d = c(z)` 重过滤；`c(z)`（L3002）保留 `J==null || (J.i() && z)`，`i.b.i()` = `!f && P==null`。

## 2. PersistOnly 判定

**成立**：纯开关改动（含"禁用→重新启用"）只写内存 `f` + `d()` + `bN.save()`，下一次单机开局/建房即生效
（与原版 `modsSave` 文案 "N selected mods will be used in the next game" 一致）；重启后由 `e()` 恢复。

**必须 FullRebuild**（`deriveModApplyPlan` 的判定边界）：
1. 启用**上次解析时被禁用/不在引擎列表**的模组（单位不在 `l.c`）——即 `bW.a()`（enabled && !B）> 0，
   RWPP 侧对应"不在 `loadedEnabledFileNames`"；新导入模组要启用同属此类；
2. 删除模组（已解析单位需重建才能从 `l.c` 清除；仅禁用+删文件虽可开局过滤，但内存残留至下次重建，按计划仍归 FullRebuild）。

**联机自洽性**：PersistOnly 后主机 required-mods 名单（`bW.i()`）立即反映新开关，建房时 `ag.a(o)` 同步重过滤单位表，
客户端进房再由服务端单位数据覆盖——三处口径一致，无额外窗口。

## 3. RWPP 侧触发点清单（不得破坏）

- `ModSyncController.kt:531`、`Logic.kt:85/105`：`modReload(forceImmediate=true)`（同步路径）
- `ui/Mods.kt:303`：Mods 页 `modReload(knownStates)`；**`modSaveChange` 全仓无调用方**（P1 实证）
- `modReregister`/`modUpdate`：仅定义，无页内调用

## 4. 风险与后续验证

1. **桌面端未逐一复核**（`game-lib.jar` 未反编译；按 internals 文档"逻辑一致"采信）。建议 R1 前在桌面端加临时日志，
   验证单机开局与建房（become-server）两处存在等价 `ag.a` 重过滤调用，跑一次"开关→保存→建房"实测。
2. PersistOnly 判定依赖"已解析集合"准确性：R1 须保证 `loadedEnabledFileNames`（或等价物）在**每次** `ag.e()` 后刷新，
   包括 ModSync 的 forceImmediate 重载（同步路径更新后回 Mods 页，集合不能是旧值）。
3. PersistOnly 不做 `t.f()` 场景清理——当前对局/菜单地图不受影响，正符合"下一局生效"语义。
