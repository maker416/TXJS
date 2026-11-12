# TServer 同端口模组直连同步 v1

> 当前客户端优先使用 [Direct v2：同步期间真实槽位预约](tserver-direct-mod-sync-v2.md)。本文记录 v1 兼容格式；v1 不预留游戏槽位，不能保证下载完成后仍有位置。只有对端尚未识别 v2 时才可继续探测 v1，合法 v2 响应之后的错误不能通过降级绕过。

本实现用于 TXJS 客户端与独立 TServer 之间的模组同步。客户端先在目标游戏 TCP 地址完成模组同步，再建立原版游戏连接；同步不写入游戏连接、不增加游戏包号，也不跳过原版握手校验。

## 与旧文档的关系

`Mod同步与TXJS协议兼容.md` 描述的是 TServer 独立 HTTP 端口上的 relaymod 兼容接口，需要列表 `server_id` 和单独的同步地址。该接口继续保留。

TServer 的 `外部接口-RWJS模组同步协议v1.md` 是同游戏端口 HTTP 草案。本次采用客户端已经具备的二进制 `TXJSMD1\n` 协议，保留草案中“服务端直接提供、同 TCP 端口、进房前完成、内容寻址、独立 IO”的目标，并增加手输 IP 自动发现和单次下载任务内的断线续传。它不是该 HTTP 草案的 wire 实现。

TServer 配置 `mods.sync.enabled=true` 即启用 Direct。只需 Direct 时可设 `mods.sync.http.enabled=false` 关闭旧的独立 HTTP 端口；客户端无需修改 `modSyncApiUrls`，直接使用游戏的主机和 TCP 端口。此时依赖旧 HTTP 的列表服务也会关闭，房间列表需继续使用已有目录服务或改用手动直连。

## 加入流程

1. 手动 IP、列表直连、收藏或邀请在统一 Loading 流程中解析实际主机和端口。默认端口 `5123`；短码和中继地址继续走原流程。
2. 使用独立 TCP 连接发送 Direct 清单请求。只有初次探测尚未识别出合法 `MANIFEST` 或 `ERROR` 时才允许回落旧 relay。
3. 校验协议版本、游戏协议版本（当前 `176`）、房间 scope、模组名称/大小/哈希及本地硬上限。大厅外或尚未 ready 时停止本次加入。
4. 按内容匹配本地模组和已验证缓存，只下载缺失项。中途断流最多尝试三次，在本次任务的临时文件上用 offset 续传；取消本次任务会删除临时文件。
5. 流式接收并校验完整 SHA-256，成功后才发布到网络模组缓存。清单中的名称只用于展示和模组匹配，文件通过哈希请求。
6. 再取清单，确认仍是同一房间和同一内容；只有成功加载的内容快照与完整清单相同时才跳过解析，否则用普通 `modReload(forceImmediate=true)` 加载。空清单也会关闭多余模组。平台重载负责应用开关，不提前修改引擎状态。
7. 检查本地启用集合与核心单位 checksum，并执行平台握手准备。两端均读取原版 init 时保存的稳定核心缓存（桌面 `l.z()`、Android `k.r()`），不以菜单地图下的实时模板值覆盖握手缓存。模组内容仍由文件 SHA-256、实际启用集合及原版自定义单位定义校验保证。
8. 重载后第三次取清单，确认大厅、版本、scope 和内容仍有效，再建立原版游戏连接。游戏仍会检查满员、锁房、密码、版本和单位数据。

Direct 不创建 relay Presence，不调用房内保连接重载。首次识别 Direct 之后，网络错误、非法帧、文件缺失、哈希不符、房间变化和重载失败均终止本次加入。取消会关闭活动同步 Socket，后续步骤和游戏连接不会继续。

## 线格式

所有整数使用大端有符号 Java `DataInput/OutputStream` 编码。`string` 为 `int32 UTF-8字节数 + UTF-8字节`，不是 Java modified UTF-8；不得带 BOM 或非法 UTF-8。字符串最大 64 KiB。`lobby` 只能是单字节 `0` 或 `1`。

每个请求单独建立 TCP 连接，先发八字节 ASCII 前缀：

```text
54 58 4a 53 4d 44 31 0a    TXJSMD1\n
```

随后请求和响应均为 `int32 payload_length + int32 kind + payload`。长度不包含两个 int32。响应不带 magic；服务端发送一帧后关闭连接，禁止额外尾随字节。

| kind | 名称 | payload |
|---:|---|---|
| 1 | GET_MANIFEST | 空，长度必须为 0 |
| 2 | MANIFEST | 见下文 |
| 3 | GET_FILE | 32 字节原始 SHA-256 + int64 offset，长度必须为 40 |
| 4 | ERROR | string 错误码 |
| 5 | FILE | int64 total_size + int64 offset + 32 字节原始 SHA-256 + 从 offset 开始的原始文件内容 |

MANIFEST 按以下顺序编码：

```text
int32  protocol_version = 1
string game_version     = "176"
string scope            = 当前房间实例标识
string status           = "ready"
byte   lobby            = 1
int64  host_units_checksum
int32  mod_count
repeat mod_count:
    string name
    int64  size
    byte[32] sha256
```

`scope` 必须非空且随房间实例更新。客户端比较 scope、协议/游戏版本、checksum 和完整模组数组。v1 的模组顺序参与复核，服务端必须保持稳定。checksum 是原版有符号 Int 扩展为 int64；`-1` 沿用现有 v1 的“不可用”哨兵，有理论值冲突，未来应通过新协议版本显式表示可用性。

FILE 的 payload_length 必须准确等于 `48 + total_size - offset`。offset 合法范围为 `[0, total_size]`。客户端检查总长度、offset、哈希、实际接收量和连接结束；不将整份文件装入内存。服务端只能返回当前 ready 清单中已登记的制品，不能按名称或外部路径读取文件。

错误码固定为 `NO_ROOM`、`NOT_LOBBY`、`PREPARING`、`NOT_FOUND`、`BUSY`、`BAD_REQUEST`、`INTERNAL_ERROR`。这些是同步协议错误，不是原版游戏踢人消息。

## 限制与兼容

客户端硬上限：清单 1 MiB、256 个模组、单模组 512 MiB、累计 2 GiB；错误 payload 最大 16 KiB。TServer 可以配置更低的分发上限。所有连接、读取和服务端分类/发送均需受时间与并发预算约束。

服务端在原版 TCP accept 入口识别前缀，支持 TCP 分片；非 Direct 的预读字节完整回放给原版引擎，UDP 不参与分流。同步请求使用独立工作线程，不能占用游戏 tick 或原版包处理线程。旧 HTTP 管理/Presence 路由不会通过二进制游戏端口暴露。

v1 不预留游戏槽位，下载者不计入房间玩家人数，不提供下载期间聊天或自动等下一局。房间可能在最终检查与原版握手之间满员或开局，客户端照常显示原版拒绝原因。同步清单和文件为明文，SHA-256 保证与本次清单一致，不提供服务器身份认证。

已完成的重载沿用平台的持久化语义：最终复核失败会阻止加入，但不会再次重载恢复进房前集合。解析或 OOM 触发 `GameStartSafety` 后必须保留重启门控，禁止为恢复选择而绕过门控或仅回写开关。

## 回归入口

```powershell
.\gradlew.bat :rwpp-core-api:test --tests '*DirectModSync*'
.\gradlew.bat :rwpp-core:desktopTest --tests '*DirectModSyncControllerTest'
.\gradlew.bat :rwpp-android:testDebugUnitTest --tests '*AndroidCoreChecksumTest'
.\gradlew.bat :rwpp-desktop:compileKotlin :rwpp-android:compileDebugKotlin
```

控制器回归位于 `rwpp-core/src/desktopTest/kotlin/DirectModSyncControllerTest.kt`，由实际 `desktopTest` 任务运行。
