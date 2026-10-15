# Android 后台联机保活

后台模式保持连接、收发网络包并暂停音乐，不推进游戏模拟、不绘制。房主退到后台时其他玩家会等待；房客回来后由原版逻辑追帧。遭遇战和沙盒属于本地单人服务器，不启动保活服务。

## 会话与服务

- `Game.acquireMultiplayerKeepAlive()` 在 Main 线程申请可关闭的连接 token。多人 Loading 在等待旧房间清理和带外预同步前申请；Android `directJoinServer` 与直接开房另有嵌套 token，覆盖后台重试与外部调用。
- 所有 token 在操作完成、失败或取消的 `finally` / Job completion 中释放。取消后的阻塞 Socket 清理仍按 `BlockingJoinController` 的原有屏障等待，旧 token 不会清除新操作。
- 引擎连接判定为 `bU.C && !bU.G`；`C` 在单人遭遇战/沙盒也为 true，不能单独使用。
- 连接成功主动通知 `onConnected()`，不依赖第一次轮询。临时失连保留服务 10 秒，重连操作的 token 可继续持有；显式退房立即清除宽限期。
- 服务阶段为 `STOPPED / STARTING / RUNNING / STOPPING`。`startForegroundService` 仅表示发出请求，`startForeground` 成功后的服务回调才确认 RUNNING。每次启动有独立 generation，迟到的停止/销毁回调不能覆盖新请求。
- 服务只在应用可见时发起启动。服务停止期间如果用户在前台又加入新房，会立即发出下一代启动请求，避免等到旧 `onDestroy` 时应用已退到后台。
- 活跃连接和前台期间每秒采样一次引擎状态作为兜底；没有会话且已经退到后台时停止轮询。服务维持 `START_NOT_STICKY`，进程丢失后不会单独重启一个没有游戏会话的服务。

## 线程与资源

- 普通后台网络泵仍在原来的游戏线程、引擎 `ai` 锁内执行 `ae.k()/l()/a(float)`。后台或没有 Surface 时跳过原方法体；前台且 Surface 可用时恢复原路径。
- 后台周期为 50ms（约 20Hz），使用单调时钟换算原版 `1/60` 秒的 delta，保留原版 `0..3` 限制。等待发生在引擎锁外；回前台及进入重载时重置计时。
- 后台、保连接重载主循环与重载看门狗共用 `NetworkOnlyPump` 的互斥。看门狗拿到锁后再次检查是否到期，避免两个线程重复推进同一个网络时间片。单位解析和原有引擎入口的执行线程保持原约定。
- `InGameActivity.onStop` 的停线程入口和 `game.j.run()` 的延迟定时器均检查实际多人连接/连接 token。定时器被拦截时调用原版 `i.ap()` 清除旧 Timer，使以后真正离线时可以重新安排停线程。
- CPU partial WakeLock 仅在后台有会话时持有，期限 120 秒，每 30 秒续期。回前台、退房或服务销毁时释放，避免菜单和前台持续持锁。
- Android 26..33 在后台使用 `WIFI_MODE_FULL_HIGH_PERF`。Android 34+ 会把该模式映射为只在亮屏且应用处于前台时生效的 `LOW_LATENCY`，因此不申请这个无效的后台锁。

## 音乐与系统省电

启动器音乐继续使用独立的持久暂停标记。原版音乐还有三个启动路径，均受 `isAppInForeground` 约束：

1. `bc.b()`：引擎 `A()` 的恢复线程，使用现有 `MusicInject.onResume` 注入；后台退房的菜单刷新仍能正常执行。
2. `be.onPrepared(MediaPlayer)`：原版异步准备回调。
3. `redirect.kt` 的资源目录音乐准备回调。

“设置 → 网络 → 后台联机”展示电池优化状态并提供系统设置入口；不自动弹授权框。后台切换日志 `[KEEPALIVE]` 记录是否忽略电池优化、是否处于 Doze，以及 Android 13+ 低电耗待机状态。

前台服务和 WakeLock 不保证绕过 Doze、低电耗待机或厂商省电策略。长时间锁屏联机可由用户在系统设置中允许后台运行，仍须在目标设备验证。

参考：[Wi-Fi 锁限制](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)、[Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)。

## 自动验证

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-android:testDebugUnitTest :rwpp-core:testDebugUnitTest --tests BundleParseTest
.\gradlew.bat :rwpp-android:assembleDebug :rwpp-desktop:compileKotlin
```

Android JVM 测试验证连接前锁屏、取消/嵌套操作、relay 早期重定向、有限重连宽限、单人排除、服务 generation 与启动失败退避，以及后台时间步和音乐恢复门控。注入测试读取本次 KSP 的配置，将新增入口实际应用到 `lib/android-game-lib.jar` 并输出字节码，核对私有 `i.ap()` 和音乐启动入口契约。

Android 单元测试的 KSP 任务禁用，避免测试源的空扫描覆盖主变体 `build/generated/config.toml`。

## 真机验收

编译与 JVM 测试无法证明设备后台策略和真实网络行为。发布前至少覆盖 Android 10..13 与 Android 14+ 各一台设备，并分别以房主和房客执行：

| 场景 | 预期 |
| --- | --- |
| 预同步/连接中立刻切应用或锁屏 | 服务已在前台申请，完成连接后可收发聊天 |
| 等待上一房间退出时切走 | 连接 token 已持有，旧清理不会提前释放新操作 |
| 取消加入后立刻加入另一房 | 旧服务回调不清除新服务状态 |
| 后台 relay 重定向、模组同步重连 | 短暂 C=false 时服务和后台 CPU 锁保留 |
| 后台同步失败、退房、音乐 prepare 完成 | 无后台音乐；必要的菜单刷新仍执行 |
| 遭遇战/沙盒切后台 | 不显示联机保活通知，保留单人停线程流程 |
| 快速切换、多窗口、配置重建 | 无重复服务，回前台后恢复绘制与音乐 |
| 真正退房、取消所有连接 | 服务停止，WakeLock/WifiLock 释放 |
| 房客长期后台后返回 | 追帧可完成，关注命令积压的内存和恢复耗时 |
| 房主后台后返回 | 后台其他玩家等待，返回后对局继续 |

可通过 `adb shell dumpsys activity services io.github.rwjs`、`adb shell dumpsys power` 与应用日志检查服务和锁。强制 Doze 测试后应还原设备状态：

```powershell
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
# 检查连接、日志，以及解除限制后的恢复
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```
