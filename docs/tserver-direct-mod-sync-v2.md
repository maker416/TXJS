# TServer 同端口模组直连同步 v2：真实槽位预约

2026-11-02。v2 在 [Direct v1](tserver-direct-mod-sync.md) 的同游戏 TCP 端口文件传输上增加预约、续租、释放和真实注册确认。客户端先预约实际容量，再下载和重载模组；预约一直覆盖到原版游戏注册完成，避免模组同步结束后被普通连接或 AI 抢走最后一个位置。

TServer 使用 `mods.sync.enabled=true` 启用 Direct，无需增加客户端 `modSyncApiUrls`。仅使用 Direct 时可以设置 `mods.sync.http.enabled=false`；这也会关闭依赖旧 HTTP 的列表服务，房间发现应使用已有目录服务或手动地址。独立 HTTP relaymod 兼容接口与 Direct 仍是不同入口。

## 加入流程与兼容

手动 IP、服务器列表直连、收藏和邀请都经过统一 Loading 流程。实际主机与 TCP 端口支持 Direct；默认端口为 `5123`。短码、快速建房指令和中继入口继续使用原有流程。

1. 新建独立 TCP 控制连接，优先探测 v2 清单。校验游戏协议版本、ready 大厅、scope、完整模组清单和本地大小限制。
2. 用本轮安全随机生成的 32 字节 token 发送 `RESERVE`。只有成功取得有效 `LEASE`，才开始下载和重载。
3. 按名称、大小和 SHA-256 匹配本地模组及已验证缓存，仅下载缺失项。文件仍按内容哈希请求，支持本次下载任务内的 offset 续传；完整 SHA-256 通过后才发布到缓存。
4. 下载后第二次取清单，复核大厅、版本、scope 和完整内容，再执行普通 `modReload(forceImmediate=true)`。空清单同样关闭多余模组；平台重载负责应用选择，不提前修改引擎开关。
5. 核对实际启用集合与实时单位 checksum，刷新原版握手 checksum 缓存；重载后第三次取清单，防止耗时解析期间房间或内容变化。
6. 在续租保护内建立原版游戏连接，通过原版包 160 的 query 交付预约凭证。TCP connect 成功后仍继续等待注册确认。
7. 持续 `RENEW` 查询，直到服务端返回 `state=admitted`，才确认加入成功并打开房间界面。客户端随后关闭本轮凭证句柄并发送 `RELEASE`。

只有 v2 初次探测尚未识别出合法 `MANIFEST` 或 `ERROR`，才允许探测 v1；v1 同样未被识别时才可回落旧 relay。收到合法 v2 `ERROR`，包括 `ROOM_FULL`、`LEASE_EXPIRED`、`LEASE_INVALID`，均终止本次加入，不能静默改成 v1 或无预约连接。识别成功后的非法帧、房间变化、文件校验失败和重载失败也不能触发协议降级。

v1 兼容路径仍按完整清单在进房前下载、重载和三次复核，但不创建预约。它不能保证同步期间仍有空位或大厅不会开局；最终仍可能收到原版满员或已开局拒绝。Direct 两个版本均不创建 relay Presence，也不使用房内保连接重载。

## 线格式

每个请求使用一条独立 TCP 连接，先发送八字节 ASCII magic，最后一个字节必须是 LF `0a`：

```text
54 58 4a 53 4d 44 32 0a    TXJSMD2\n
```

这里的 `\n` 是换行的文字表示，线上不能发送反斜线与字母 n 两个字节。请求和响应随后均使用：

```text
int32 payload_length
int32 kind
byte[payload_length] payload
```

整数均为大端有符号编码，长度不包含两个 int32；响应没有 magic，发送一帧后关闭连接，禁止额外尾随字节。`string` 编码为 `int32 UTF-8字节数 + UTF-8字节`，不使用 Java modified UTF-8，不允许 BOM 或非法 UTF-8。普通字符串上限仍为 64 KiB；租约 scope 另外限制为非空、无控制字符、最多 512 个 UTF-8 字节。

kind 1～5 沿用 [v1 的 MANIFEST、ERROR 和文件格式](tserver-direct-mod-sync.md#线格式)。v2 `MANIFEST.protocol_version=2`；服务端继续稳定输出模组顺序，客户端比较协议版本、游戏版本、scope、checksum 和完整数组。v2 新增：

| kind | 名称 | payload |
|---:|---|---|
| 6 | RESERVE | string scope + byte[32] token |
| 7 | LEASE | 下述租约字段 |
| 8 | RENEW | string scope + 同一 byte[32] token |
| 9 | RELEASE | string scope + 同一 byte[32] token |
| 10 | RELEASED | 空，payload_length 必须为 0 |

`RESERVE`、`RENEW`、`RELEASE` 的 payload 长度必须准确等于 `4 + scope的UTF-8字节数 + 32`，合法范围为 37～548 字节。token 在线上是固定长度的原始 32 字节，不是 64 字节十六进制文本。客户端使用安全随机源生成 256 bit token；它同时是本轮幂等键和 bearer secret，不应进入日志、地址历史或长期配置。

`LEASE` 严格按以下顺序编码，时间单位为毫秒：

```text
string scope
int64  ttlMillis
int64  renewAfterMillis
string state              // reserved / claimed / admitted
int32  occupied
int32  limit
int32  reserved
```

字段含义如下：

| 字段 | 含义 |
|---|---|
| scope | 本次预约对应的不可变 Direct 房间实例标识，必须与请求一致 |
| ttlMillis | 服务端返回时的剩余租期，不能按固定 90 秒替代 |
| renewAfterMillis | 下次建议续租间隔，必须大于 0 且小于 ttlMillis |
| state | 预约状态，只有 admitted 代表真实注册已经完成 |
| occupied | 所有已占用及尚未完成注册的容量之和，包含 AI、真人、普通握手、handoff 和同步预约 |
| limit | 该房间的实际容量上限 |
| reserved | 尚未完成真实注册的同步预约数；这是 occupied 的一部分，不能再次相加 |

正常租期为 **90 秒**，建议每 **30 秒**续租。服务端实际返回的间隔为 `min(30000, ttlMillis / 3)`；剩余租期不足 1 秒时返回 `LEASE_EXPIRED`。未注册预约从创建起最长存活 **60 分钟**，持续续租也不能越过此上限。上述值已对照 TServer `RoomAdmissionLedger.SYNC_TTL_MILLIS`、`SYNC_RENEW_MILLIS` 和 `SYNC_MAX_NANOS`。

相同 scope、token 的重复 `RESERVE` 返回同一预约的剩余租期，不新占位置，也不延长到期时间；只有有效 `RENEW` 续期。`RESERVE` 响应丢失时客户端最多尝试三次，始终保留原 token；最终失败或取消也尝试释放这个可能已在服务端创建的预约。

`RELEASE` 成功响应为 kind 10 的空帧。服务端对未知、已释放或已过期 token 也返回相同空 ACK，使重复释放幂等；scope 不匹配不能修改其他房间的租约。

新增错误码 `ROOM_FULL`、`LEASE_EXPIRED`、`LEASE_INVALID`，使用 kind 4 的 `string` payload。其余错误仍为 `NO_ROOM`、`NOT_LOBBY`、`PREPARING`、`NOT_FOUND`、`BUSY`、`BAD_REQUEST`、`INTERNAL_ERROR`。错误 payload 上限为 16 KiB；这些错误码属于同步协议，不是原版游戏踢人消息。

## 原版握手与注册交付

客户端仅在本轮连接建立期间、对解析后目标 IP 和端口匹配的游戏 Socket 提供凭证，将原版 preregistration 包 **160** 的 query 写成：

```text
txjsmd2_<原始token编码成的64个小写十六进制字符>
```

前缀必须为 `txjsmd2_`，不包含尖括号。该文本只是原始 token 的十六进制编码，不再计算一次 SHA-256；独立同步请求仍发送 raw token[32]。加入地址和 relay UUID 保持原值；凭证不占用原版玩家指纹或 UUID 字段，不增加游戏包号，不改变 `directJoinServer` 接口。旧请求关闭句柄时通过身份比较清理自己的上下文，不能清除新请求的凭证。

服务端收到该 query 后，在原版 `beforeRegistration` 回调中将同一预约原子地从 `reserved` 变为 `claimed`。同一连接重复握手幂等，其他连接不能重放已认领 token。原版版本、单位 checksum、密码、锁房及真实槽位检查继续执行；只有真实 `playerRegistered` 回调到达后，账本才标记 `admitted`。

| state | 客户端行为 |
|---|---|
| reserved | 继续同步或等待连接，持续续租 |
| claimed | 游戏握手正在进行，持续续租，不能显示入房成功 |
| admitted | 真实玩家已注册，完成等待，关闭本轮预约句柄并释放凭证 |

TCP connect 或收到 preregistration 响应均不能代替 `admitted`。客户端最多等待 30 秒确认真实注册，超时则本轮加入失败并清理连接。玩家注册后若立即开局导致房间 scope 换代，服务端仍允许原合法 token 查询已经完成的 `admitted`，避免丢失成功确认。

**已注册后的 RELEASE 不会踢出玩家，也不会把玩家的实际槽位释放给别人。** 实际容量持续由真实玩家连接持有，直到服务端收到该连接的关闭事实。客户端仍发送 RELEASE 统一收尾，并在本地清除本轮凭证；不能把 RELEASED 当成玩家已断开的事实。

## 唯一容量与开局门控

TServer 的 `RoomAdmissionLedger` 在单服和集群 worker 中统一处理容量。普通玩家连接、尚未注册的握手、handoff、同步预约和添加 AI 在同一容量锁下竞争最后一个位置；不能只读取当前已注册玩家数再分别放行。同步预约转换成 `claimed`、再变成 `admitted` 时只转移同一个位置，不重复扣减容量。

AI 的真实添加在游戏线程执行，并在同一容量锁内立即记入占用，采样发布通过 revision 避免旧 AI 计数覆盖新结果。观战真人连接保守计入一个普通容量，转回参战时不会产生可超卖的新位置。因此含观战者的房间可能较早显示满员，这一选择用于保住真实槽位边界。

未注册同步预约存在时，服务端拒绝手动开局和自动开局，并保留预约。自动开局插件单独读取 `syncPending`；同步者不伪造成已经连接的真人，也不填充真人参战人数。同步开始会取消当前倒计时并清除旧名单和失败压制；`syncPending` 归零后，即使真人名单相同，也重新执行完整倒计时。最终开局仍需经过账本的原子门控，不能只依赖自动开局快照。

取消或租期到期立即释放尚未认领的位置。若预约已经绑定在途官方握手，取消或到期会禁止其随后注册，但临时容量要保留到真实连接关闭，防止旧握手与新预约挤占同一引擎位置。房间换代会取消旧的未注册预约。正常注册后容量交给玩家连接，预约释放和 60 分钟上限均不影响该已注册玩家。

## 客户端清理与资源边界

续租覆盖下载、普通重载和真实注册等待。客户端以请求发起时间与返回的剩余 TTL 保守计算本地到期时间，并用独立到期观察停止受保护任务；不能因为续租 Socket 正在等待而无限延长租约。短暂网络错误可在现有租期内重试，服务端明确拒绝或租期耗尽则停止本次加入。

取消 Loading、文件/清单复核失败、重载失败、注册超时或连接失败都会关闭本轮活动 Socket 和凭证句柄，停止心跳及到期观察，并尽力发送一次有界的 RELEASE。若原版连接已建立但房间界面尚未提交，还会先断开该连接。`close()` 幂等；释放响应丢失不阻止本地清理，服务端继续依赖 TTL 和真实关闭事实回收。

未验证临时文件不得发布；所有文件帧继续流式读写。客户端硬上限保持为清单 1 MiB、256 个模组、单模组 512 MiB、累计 2 GiB；TServer 可以配置更低的上限。模组重载后的失败仅阻止本次加入，不自动再次重载恢复原集合。解析或 OOM 触发 `GameStartSafety` 时保留重启门控。

预约保障的是该目标实例的容量，不提供服务器身份认证，也不代表玩家账号身份。同步和 token 传输仍为明文；SHA-256 只保证文件与本次清单一致。预约不提供同步期间房内聊天，也不会自动等待下一局。

## 回归入口与已验证范围

客户端稳定回归入口：

```powershell
.\gradlew.bat :rwpp-core-api:test
.\gradlew.bat :rwpp-core:desktopTest --tests '*Direct*'
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests '*BundleParseTest'
.\gradlew.bat :rwpp-desktop:compileKotlin :rwpp-android:compileDebugKotlin
```

生命周期回归位于 `rwpp-core/src/desktopTest/kotlin/DirectJoinReservationTest.kt`，用虚拟时钟覆盖响应丢失幂等重试、最终清理、下载与重载心跳、租约撤销、到期取消、等待 admitted 和并发关闭。`DirectModSyncControllerTest.kt` 覆盖清单/重载/预约的交接和失败路径，`rwpp-core-api` 的 Direct 测试覆盖帧编码、实际 Socket、v1 兼容探测及凭证绑定。

TServer 仓库的稳定回归入口：

```powershell
.\gradlew.bat :server-runtime:test --tests '*SyncAdmissionLedgerTest' --tests '*LedgerRegistrationAdmissionTest'
.\gradlew.bat :server-runtime:test --tests '*PluginRoomAutomationAdapterTest' --tests '*AutoStartHostIntegrationTest' :plugin-auto-start:test
.\gradlew.bat :server-launcher:test --tests '*DirectModSync*Test'
```

本轮已通过客户端 API 全量 531 项测试、35 项 Direct 相关桌面测试、Android BundleParseTest 的 22 项测试和双端 Kotlin 编译。跨仓手工联调已通过真实客户端传输与 TServer 的清单、文件及租约场景；服务端真实引擎验证使用实际容量减一的 AI 占位，通过原版 160/161/110 握手确认普通连接被拒、持预约连接获得最后槽位。临时联调脚本和日志仅保存在本机 `build/tmp`，不是仓库稳定功能或其他 checkout 的必备入口。

这些结果覆盖协议、准入与编译边界；Android 真机后台同步、完整客户端界面和完整对局仍需按实际运行环境验收。
