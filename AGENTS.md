# AGENTS.md

本文件为 AI 编程助手提供项目背景、构建方式、代码组织与开发约定。阅读者应对本项目一无所知，所有信息均基于仓库实际内容，不做假设。

## 项目概述

**铁锈战争极速版**（仓库名 RWPP，内部版本号 1.6.4）是从上游 [RWPP](https://github.com/Minxyzgo/RWPP) 体系 fork 并独立维护的多平台启动器，面向「萌新云」生态与更清爽的铁锈战争（Rusted Warfare）联机体验。产品方向聚焦于降低上手门槛、优化服务器列表、减少广告干扰，以及改善公开房间曝光。

项目采用 **AGPL-3.0** 许可证。所有源文件头部必须保留版权声明模板（参见 `settings.gradle.kts` 中的注释块）。

## 技术栈与关键版本

| 技术 | 版本 | 说明 |
|------|------|------|
| Kotlin | 2.1.20 | 主语言，使用 KMP（Kotlin Multiplatform） |
| Compose Multiplatform | 1.10.1 | 跨平台 UI 框架 |
| Android Gradle Plugin | 8.7.3 | Android 构建 |
| Koin | 4.0.1 | 依赖注入，编译期注解 + KSP 生成 DI 代码 |
| Koin Annotations | 1.4.0 | Koin 的 KSP 注解处理器 |
| KSP | 2.1.20-1.0.32 | Kotlin Symbol Processing |
| Coil3 | 3.2.0 | 图片加载（`coil-compose` + `coil-network-okhttp`） |
| LuaJ | 4.0.2 | Lua 脚本支持（`luajava` + `lua54`） |
| LWJGL | — | 桌面端 OpenGL 渲染（`lib/lwjgl.jar` 等） |
| Javassist | 3.30.2-GA | 运行时字节码操作与注入 |
| OkHttp | 4.12.0 | 网络请求 |
| kotlinx.serialization | — | 序列化（通过 Gradle plugin 引入） |
| markdown renderer | 0.29.0 | `multiplatform-markdown-renderer` 用于渲染更新日志 |
| reorderable | 2.4.3 | Compose 拖拽排序 |

构建要求：**JDK 17+**；但编译目标与 toolchain 实际指向 **Java 21**（兼容 `androidx.core:core-ktx 1.16+` 等依赖）。

## 模块架构

项目包含 5 个 Gradle 子模块，依赖流向为：`rwpp-core-api` ← `rwpp-core` ← `rwpp-android` / `rwpp-desktop`。`rwpp-ksp` 作为 KSP 插件被 android/desktop 使用。

| 模块 | 类型 | 职责 | 源文件数（约） |
|------|------|------|--------------|
| `rwpp-core-api` | JVM Library | 纯接口/数据模型层：Game、Net、Config、Event、Inject 注解、IO 等抽象；**无 Compose 依赖** | ~102 |
| `rwpp-core` | KMP (Android + Desktop JVM) | 共享 Compose Multiplatform UI + 核心逻辑；通过 `expect`/`actual` 实现平台抽象 | ~92 |
| `rwpp-android` | Android Application | Android 入口（`MainApplication`、`MainActivity`、`LoadingScreen`）+ `impl/` 平台实现 + `impl/inject/` 注入模块 | ~50 |
| `rwpp-desktop` | Compose Desktop Application | 桌面入口（`Main.kt` — Swing `JFrame` + `ComposePanel` + OpenGL `Canvas`）+ `impl/` 平台实现 + `impl/inject/` 注入模块 | ~55 |
| `rwpp-ksp` | KSP Processor | 编译期代码生成：扫描 `lib/` 中的游戏库 jar，为平台模块生成注入配置与元数据 | 2 |

当环境变量 `JITPACK` 被设置时，`settings.gradle.kts` 会排除 `rwpp-android` 与 `rwpp-desktop`，仅保留 library 模块，以支持 JitPack 发布。

## 代码组织

### rwpp-core-api（接口与数据模型）

包结构如下，所有接口与数据类均位于 `io.github.rwpp` 下：

- `app/` — `PermissionHelper` 等应用级能力抽象
- `command/` — 命令处理器
- `config/` — 配置模型（`Settings`、`CoreData`、`ServerConfig`、`Blacklist` 等）及 ConfigIO 抽象
- `core/` — 初始化与类加载器抽象
- `event/` — 事件总线（`GlobalEventChannel`、`Event`、`Listener`）及各类游戏事件（`game.kt`、`player.kt`、`world.kt`、`desktop.kt`）
- `external/` — 扩展/插件模型（`Extension`、`ExternalHandler`）
- `game/` — 游戏核心接口
  - `audio/` — 音效与音池
  - `base/` — `GameCanvas`、`GamePaint`、`BaseFactory`、`Difficulty`、`Rect`
  - `data/` — 玩家数据、房间选项、统计
  - `map/` — 地图、任务、回放、网络地图、XML 地图、迷雾模式
  - `mod/` — Mod 与 Mod 管理器
  - `team/` — 队伍模式
  - `ui/` — `GUI` 抽象
  - `units/` — 游戏单位、命令动作、内部单位、`GameObject`
  - `units/comp/` — 单位组件系统接口
  - `world/` — 世界接口
- `i18n/` — 国际化解析
- `inject/` — **注入系统的核心注解与运行时支持**
  - `annotations.kt` — `@InjectClass`、`@InjectClassByString`、`@Inject`、`@RedirectMethod`、`@SetInterfaceOn`、`@NewField`、`@Accessor`、`@RedirectTo`
  - `runtime/Builder.kt` — 运行时字节码注入构建器
  - `runtime/InjectApi.kt` — 注入 API
  - `InjectInfo.kt`、`InjectMode.kt`、`InterruptResult.kt`、`ClassTree.kt`、`GameLibraries.kt`、`BuildLogger.kt`
- `io/` — IO 工具
- `net/` — 网络层：房间列表解析、数据包定义、版本查询；`net/sync/` — 模组同步服务器客户端（DTO、key 推导、OkHttp 客户端）；**真正的单元测试集中于此**（`RwListParserTest.kt`、`ModSyncKeysTest.kt`、`ModSyncModelsTest.kt`）
- `ui/` — UI 工具接口
- `utils/` — 通用工具

### rwpp-core（共享 UI 与核心逻辑）

使用 KMP 的 `commonMain` + `androidMain` + `desktopMain`：

- `commonMain/kotlin/io/github/rwpp/`
  - `App.kt` — 根 Compose 应用，使用 `AnimatedVisibility` 管理页面切换
  - `impl/` — 通用实现基类（`BaseAppContextImpl`、`BaseExternalHandlerImpl`、`BaseNetImpl`、`BaseGameI18nResolverImpl` 等）
  - `ui/` — 各功能页面：主菜单、多人联机、房间、设置、Mod 管理、任务、回放、资源浏览器、扩展、Ban 单位、注入控制台
  - `widget/` — 自定义 Compose 组件（按钮、主题、加载动画、对话框、导航栏、滚动条等）
  - `widget/v2/` — 第二版组件（Brush、按钮动画、LazyColumn 滚动条、加载指示器）
  - `coil/` — 自定义 Coil `ImageableFetcher`/`ImageableKeyer`
  - `graphics/` — OpenGL 抽象（`GL20`、`GL30`、`ShaderProgram`）
  - `platform/` — 平台差异 `expect` 声明：`Back.kt`、`ContextMenuArea.kt`、`Graphics.kt`
  - `scripts/` — Lua 脚本注入与渲染支持（`LuaInjectInfo`、`Render`、`Scripts`）
  - `game/units/comp/` — 通用单位组件实现
  - `event/ComposeUtil.kt` — Compose 侧的事件工具
- `androidMain/` — Android 平台 `actual` 实现（`GL20.android.kt`、`GL30.android.kt`、`platform/Back.kt`、`platform/ContextMenuArea.kt`）
- `desktopMain/` — Desktop 平台 `actual` 实现（仅 `lwjgl.jar` compileOnly 依赖）

`commonMain/composeResources/` 下包含：
- `drawable/` — 图片资源（logo 等）
- `files/` — `bundle_en.toml`、`bundle_zh.toml`（国际化文本，由 `BaseGameI18nResolverImpl` 解析）
- `font/` — 字体资源

### rwpp-desktop（桌面端实现）

`src/main/kotlin/io/github/rwpp/desktop/`：

- `Main.kt` — 程序入口：`main()` 初始化 Koin、配置、游戏库加载，随后进入 Swing 事件循环创建 `JFrame` + `ComposePanel` + `Canvas`
- `swingApplication()` — 构建窗口、显示切换器（`DisplaySwitcher`）、聊天对话框、游戏内挂件对话框
- `GameSessionManager.kt`、`DisplaySwitcher.kt`、`OffscreenComposeRenderer.kt`
- `impl/` — 全部桌面平台实现（约 30 个文件），包括 `GameImpl`、`GameCanvasImpl`、`NetImpl`、`ModManagerImpl`、`GUIImpl` 等
- `impl/inject/` — **桌面端注入模块**（约 20 个），如 `GameInject.kt`、`NetPacketInject.kt`、`GuiInject.kt`、`NetworkInject.kt` 等，使用 `rwpp-core-api` 中的注解标记注入点

### rwpp-android（Android 端实现）

`src/main/kotlin/io/github/rwpp/android/`：

- `MainApplication.kt` — Application 入口
- `MainActivity.kt` — 主 Activity
- `LoadingScreen.kt` — 启动 Loading Activity（也是 `LAUNCHER` intent-filter 的入口）
- `ExternalHelperActivity.kt`、`FileHelper.kt`、`OffscreenSurfaceView.kt`
- `impl/` — Android 平台实现（约 30 个文件），命名与桌面端一一对应
- `impl/inject/` — **Android 端注入模块**（约 15 个）

Android 端有大量资源需要从原版铁锈战争客户端提取（assets、res/drawable、res/raw 等），`.gitignore` 已将这些路径排除。

### rwpp-ksp（编译期代码生成）

`src/main/kotlin/io/github/rwpp/ksp/`：

- `MainProcessor.kt` — 扫描 `@InjectClass`、`@SetInterfaceOn`、`@RedirectTo` 等注解，使用 Javassist 分析 `lib/` 下的游戏 jar，生成注入元数据
- `MainProcessorProvider.kt` — KSP 处理器提供者
- `resources/META-INF/services/...` — SPI 注册文件

构建参数通过 `ksp { arg(...) }` 传入：
- `outputDir` — 生成代码输出目录
- `lib` — 处理哪个库（桌面为 `game-lib`，Android 为 `android-game-lib`）
- `libDir` — `lib/` 根目录路径
- `pathType` — 注入函数路径风格（`Path`）

## 构建与运行

### 常用命令

```bash
# 桌面端 fat jar（当前 OS）
./gradlew :rwpp-desktop:packageReleaseUberJarForCurrentOS

# Windows MSI 安装包（需要 .NET SDK）
./gradlew :rwpp-desktop:packageWixDistribution

# 运行测试（rwpp-core-api 与 rwpp-core 中的测试）
./gradlew :rwpp-core-api:test
./gradlew :rwpp-core:test

# Android Debug APK
./gradlew :rwpp-android:assembleDebug

# Android Release APK（签名见 build/key/keystore.properties）
./gradlew :rwpp-android:assembleRelease
```

Gradle JVM 参数在根目录 `gradle.properties` 中定义（`-Xmx2048M`）。

### 构建前准备

1. 确保 `lib/` 目录中的游戏库 jar 存在：
   - 桌面端需要 `lib/game-lib.jar`
   - Android 端需要 `lib/android-game-lib.jar`
2. Android 若缺少 assets/res，需从本地已安装的铁锈战争客户端对照补齐。
3. Android Release 签名：`build/key/release.keystore` + `build/key/keystore.properties`（`gradlew clean` 不会删除 `build/key/`）。
4. 桌面端运行/构建 MSI 需要 `.NET SDK`（用于 `wix/Program.cs` 的 WiX 打包）。
5. 若首次运行或注入配置变更，`rwpp-desktop` 会在启动时进入「应用注入配置」模式，完成后自动重启。

### Git 工作树（worktree）规范（重要）

新建 git worktree 时，`.gitignore` 排除的本地文件**不会**进入新工作树，但构建/测试依赖其中一部分。**创建 worktree 后必须立即把这部分文件同步过去**，否则 Android 构建、Release 签名、注入元数据生成会失败。

必须同步的文件（相对于仓库根）：

- `local.properties` — Android SDK 路径（无此文件 Android 模块无法配置）
- `packaging/game-root.local.txt` — 本机游戏根路径（打包/桌面运行需要）
- `build/key/`（`release.keystore`、`keystore.properties`）— Android Release 签名
- `build/*.ps1` — 发布打包脚本
- `rwpp-android/src/main/assets/` 全部 — 从原版客户端提取的资源（约 23MB）
- `rwpp-android/src/main/res/` 下被忽略的文件（drawable/anim/raw/values 等，从原版客户端提取）
- `listserver/` — 列表服务器资料

不需要同步：`.gradle/`、`.idea/`、`.kotlin/`、`.claude/`、`.codegraph/`、各模块 `build/` 与 `bin/` 输出、`build/` 下的临时子目录（`artifacts/reports/tmp/tmp-decompile/wix311/wt-verify`）、日志、桌面运行时目录（`generated_lib/`、`maps/`、`units/` 等）、本机用户配置（`io.github.rwpp.config.*.toml`）。`lib/*.jar` 已全部纳入 git 跟踪，worktree 自带，无需复制。

在主仓库根目录执行以下命令一键同步（Git Bash；把 `<worktree路径>` 换成实际路径）：

```bash
git ls-files --others --ignored --exclude-standard -z \
  | grep -z -v -E '^\.(gradle|idea|kotlin|claude|codegraph|cursor)/|^rwpp-[a-z-]+/build/|/bin/|^build/(artifacts|reports|tmp|tmp-decompile|wix311|wt-verify)/|\.log$|^generated_lib/|^extension/|^maps/|^units/|^resource_generated/|^io\.github\.rwpp\.config\.' \
  | tar --null --files-from=- -cf - | tar -xf - -C <worktree路径>
```

同步后建议在新工作树跑一次 `./gradlew :rwpp-core-api:test` 冒烟验证。

### 发布产物

- 桌面端：`build/desktop-jar/` 下生成 `.jar`，配合 `launcher.bat` 等脚本使用；MSI 通过 `wix/` 下的 .NET 项目生成
- Android：`rwpp-android/build/outputs/apk/` 下生成 APK

### 自动更新（Gitee release）

- 版本检查：`Net.getLatestVersionProfile()` 拉取 `gitee.com/maker416/TXJS` 的最新 release 资产列表。
- **Gitee 单文件限制 100MB**，桌面安装包（`RWJS-Setup.exe` 超 100MB）以 **zip 分卷**发布：`build.ps1` 的 `msi` 目标收集产物时自动生成 `RWJS-Setup.zip.001/.002/…`（7-Zip/WinRAR 分卷命名约定，用户可直接解压）与 `RWJS-Setup.zip.sha256`（合并 zip 的 SHA-256）。
- 客户端：`LatestVersionProfile.resolveDesktopUpdatePlan()`（`rwpp-core-api` 的 `net/UpdateDownloadPlan.kt`）识别分卷组（≥2 卷且序号连续，否则回退旧版单 `.exe` 资产）；桌面端 `AutoUpdaterImpl` 顺序下载分卷、sha256 强校验（无校验资产时降级为 zip CRC）、`SequenceInputStream` + `ZipInputStream` 流式合并解出 exe（不落合并后的大 zip），随后以 `RWPP_UPDATE_MODE=1` 启动安装并退出进程。Android 仍为单 `.apk` 资产走系统安装器。

## 平台抽象模式

`rwpp-core/src/commonMain/` 中通过 `expect` 声明平台差异：

| 文件 | 职责 |
|------|------|
| `platform/Back.kt` | 返回/回退导航 |
| `platform/ContextMenuArea.kt` | 右键/长按菜单 |
| `platform/Graphics.kt` | 图形初始化 |
| `graphics/GL20.kt`、`graphics/GL30.kt` | OpenGL 接口抽象 |

Android `actual` 实现在 `rwpp-core/src/androidMain/`；桌面 `actual` 实现在 `rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/impl/` 中（部分直接内联实现，部分通过独立类）。

## 导航与 UI 架构

项目**未使用**任何第三方导航库。页面切换在 `App.kt` 中通过全局 `MutableState<Boolean>` 变量控制：

- `showMultiplayerView`、`showSettingsView`、`showRoomView`、`showMissionView`、`showModsView`、`showExtensionView`、`showReplayView`、`showContributorList`、`showResourceBrowser`、`showSinglePlayerView`
- 配合 `AnimatedVisibility`（`fadeIn`/`fadeOut` + `slideInVertically`/`expandIn`/`shrinkOut`）实现过渡动画
- 当所有视图状态均为 `false` 时，显示主菜单（`UI.UiProvider.MainMenu`）

`UI.kt` 中维护了一些全局 UI 状态（`UI.warning`、`UI.question`、`UI.dialogWidget`、`UI.showNetworkDialog` 等），通过 Compose 重组驱动弹窗与覆盖层。

## 依赖注入（DI）

使用 **Koin 4.0.1**，采用编译期注解 + KSP 生成代码的方式：

- 模块定义：
  - `rwpp-core-api`：`ConfigModule`
  - `rwpp-desktop`：`DesktopModule`
  - `rwpp-android`：`AndroidModule`
  - `rwpp-core`：`CompModule`
- 在入口中通过 `startKoin { modules(...).module }` 组装
- 组件内通过 `koinInject<T>()` 获取依赖
- Koin KSP 生成代码输出到 `build/generated/ksp/main/kotlin`（已加入 `sourceSets`）

## 运行时注入系统（Javassist）

这是本项目最核心的底层机制：通过自定义注解 + KSP 生成元数据 + Javassist 运行时修改游戏库字节码，将启动器逻辑「缝合」进原版游戏。

流程：
1. **编译期**：`rwpp-ksp` 扫描平台模块中的 `@InjectClass` / `@Inject` / `@SetInterfaceOn` / `@RedirectMethod` / `@RedirectTo` 注解，结合 `lib/` 下游戏 jar 的类结构，生成注入配置（`RootInfo`）序列化到 `build/generated/config.toml`
2. **运行时**：`Builder`（在 `rwpp-core-api` 的 `inject/runtime/` 中）读取配置，使用 Javassist 对游戏类进行方法重写、插入、重定向、接口嫁接等操作
3. 生成的修改后类库输出到 `generated_lib/`，由自定义类加载器加载

注入模式（`InjectMode`）：`Override`（覆盖原方法）、`InsertBefore`（插入前置逻辑，要求返回 `Any`）等。

## 模组同步（带外方案）

**游戏联机通讯完全保持原版**，模组同步是启动器自身的带外功能，不注入任何自定义联机包。v2 起加入者**先进房再同步**：模组未下完也能立即进房聊天，进度显示在玩家列表该玩家名字旁（见下「握手放行」）。

- **中转服务端**：独立 Go 服务 `relaymod`（源码在 `D:\workspace\RW\relaymod`，非本仓库模块），部署在公网 IPv4；仅标准库 `net/http`。房间同步记录（清单 + TTL + secret + `host_units_checksum`）与内容寻址 blob（按 SHA-256 去重存储）；另提供加入者进度 Presence（内存态，45s TTL，不落盘；新建 PUT 返回一次性 `peer_secret`，后续须 `X-Peer-Secret`；连接 IP 仅房主可见）。`GET .../peers` 双通道：房主 `X-Secret` 得全量（含 ip），加入者 `X-Peer-Secret` 得脱敏列表。
- **客户端协议层**：`rwpp-core-api` 的 `net/sync/` —— `ModSyncModels.kt`（DTO，snake_case 与 Go 对齐）、`ModSyncKeys.kt`（房间 key 推导：`code:<短码>`、发布后绑定 `sid:<server_id>` 别名；直连 IP 房不提供同步）、`ModSyncClient.kt`（OkHttp，多 baseUrl failover；Presence 带 peer/room secret）。
- **编排层**：`rwpp-core` 的 `core/ModSyncController.kt` ——
  - 房主侧：开房勾选「传输模组」后进房拿到短码即注册（status=preparing，含 `hostUnitsChecksum`）→ `files/check` 查缺 → 上传缺失 blob → ready → 60s 心跳续期 → 发布列表后绑定 sid 别名 → 离房（`DisconnectEvent`）注销；注册成功后每 1s 带 `X-Secret` 拉 peers；可 `clearHostPeers()`。
  - 加入者侧：`Multiplayer.kt` 的 `LoadingView.loadContent` 中、`directJoinServer` 之前执行 `preJoinSync` **轻量段**：查清单（404=无同步，按原版加入）→ 本地启用集合已与清单完全一致（`isEngineAlreadyExact`）则按原版加入；否则上报 `joining` Presence + 心跳，等 ~1.5s（`JOIN_SETTLE_MS`，让房主 1s 轮询看到自己）后放行连接。进房成功后 `onJoinedRoom` 启动 `runPostJoinSync`：preparing 轮询（≤60s，上报 `waiting_host`；轮询中 404=房主已注销则失败退房）→ diff 本地（三路匹配）→ 下载缺失（`downloading`，房间内联进度条 `RoomSelfSyncBar`，不再弹阻塞卡片）→ 启用 + **`modReloadKeepConnected` 保连接重载**（`applying`，见下「连接态重载红线」）→ 双校验（本地集合命中 + `getUnitsChecksum()` vs 清单 `hostUnitsChecksum`）→ 等 `gameRoom.isConnecting` 连续稳定约 1s（已断则保持 `applying` Presence 并用本轮地址 `directJoinServer` 重连，再等稳定，失败 `failInRoom`）→ `synced` 心跳保留至开局/断线。失败/取消（`cancelInRoomSync`）提示、自动退房并**直接**清 Presence/心跳（不只依赖 `DisconnectEvent` 兜底——连接已死时它不会来）。进房窗口期内被踢由 `onKickedDuringSyncEntry` 静默重试（≤2 次，平台层被踢告警钩子抑制弹窗）；重试期间 `finishJoinerPresence` 保留 Presence，重试成功直接打开房间视图并 `onJoinedRoom`。Presence upsert/`listPeersAsPeer` 失败同一错误只 warn 一次并指数退避（2s→8s，须短于 45s TTL）；旧 relay 拒绝 `phase=synced` 时回退 `applying` 心跳保 TTL（房主开局门控会视该玩家未完成，**必须先部署承认 `synced` 的 relaymod**）。
- **连接态重载红线（踩过坑）**：已连接在房时**禁止**走 `modReload`/`runReloadCore`（`t.f()`/`t.q()`，桌面端 `B.e()`/`B.x()`）：`t.f()` 停掉游戏主循环后，网络保活泵（`ae.l()`/`ae.a(float)` tick，由主循环每帧驱动）停摆，连接被超时断开（`ae.C=false`，聊天发送退化为无名字的本地回显）；`k.bo=true` 被网络 tick 读到会直接 `queDisconnect`；`t.q()` 重建菜单态并**清空玩家数组**，直接摧毁房间状态。更进一步：**禁止把 `bW.a()` 投进 `i.b`/`a(float,int)`**——Android `Game.post` 在主循环 InsertBefore **同步 invoke**，单位解析占满主循环同样导致保活停摆（重模组可达数十秒，远超原版超时）。保连接重载只做「保存 → ModReloadSelection → `bW.a(false,false)`」（先例：`modSaveChange`），在**当前协程线程**跑解析；置位 `KeepConnectedReload` 期间主循环只泵网络并 `InterruptResult` 跳过单位 tick，主循环超过约 50ms 未进入则看门狗补泵。apply 后必须等 `isConnecting` **连续稳定约 1s**（`directJoinServer` 在 relay TCP 成功即返回，随后 `PACKET_RECONNECT_TO` 还会再断），已死则保持 `applying` 并重连，禁止留在可打字的僵尸房。房内同步期间 App 层抑制重载弹窗与下载卡片（`inRoomSyncActive`），进度由 `RoomSelfSyncBar` 行内呈现。
- **应用阶段取消回落原版**：等待/下载取消仍立刻退房。`applying` 时先关房间回多人列表，再弹不可关闭的「正在取消」框（`cancellingReload`）；`KeepConnectedReload.requestAbortToVanilla()` + 两端 `CustomUnitLoadInject` 在 `ag.a(String,int,boolean,b,String,String)` 对自定义模组抛 `ReloadAbortToVanilla`（当前模组仍会跑完），随后全关模组并再跑一次原版-only 保连接重载（不广播 `ReloadModEvent`）。Android 此时**禁止**立刻 `activityResume`/`i.q()`（`ag.m` 会扫单位表，与工作线程重建撞车 → `ConcurrentModificationException`）；须等回落结束、闸门仍开时由 `refreshMenuAfterDisconnect` 再跑，然后才 `KeepConnectedReload.end()`。`DisconnectEvent`/`clearRoomPresence` 在 `cancellingReload` 期间不得取消回落 job。不恢复进房前启用集合；OOM 后跳过第二次重载。
- **单位校验和必须用实时重算，重载后还要写回握手缓存**：`Game.getUnitsChecksum()` 返回的是**从单位注册表实时重算**的值（Android `ce.bt()`、桌面 `am.bM()`）；引擎握手用的 init 缓存字段（`k.r()` 读 `c` / `l.z()` 读 `d`）在模组重载后**不会刷新**（`ce.bt()` 全库仅引擎 init 一处调用）。同步注册/校验用实时值；保连接重载成功后调用 `Game.refreshHandshakeChecksumCache()` 把该字段写成新值，否则包 110 发送端仍发旧校验和。`redirectUnitsChecksum` 的回退分支仍返回该字段（写入后即新值，与原版握手行为一致）。
- **握手放行（房主侧注入）**：原版在注册握手（包 110 REGISTER_CONNECTION）比对单位校验和，不匹配即踢。注入层在包处理入口暂存注册包解析出的玩家名/校验和/连接 IP（`ModSyncController.pendingRegistration`），并用 `@RedirectMethod` 把校验和读取重定向到 `shouldAllowMismatch` 回调——房主同步会话活跃且 peers 里有同名未 synced 的加入者（IP 不冲突）时返回暂存的客户端校验和使比对通过，否则返回真实校验和、原版照踢。桌面：`NetworkInject.redirectUnitsChecksum`（`ad.c(au)` 内 `l.z()`）；Android：`NetInject.redirectUnitsChecksum`（`ae.a(bi)` 内 `k.r()`）。对原版/未同步客户端零影响（其名字永不在 relay peers 列表），线上协议零改动。
- **缺单位自断推迟（加入者侧注入）**：握手放行之后，原版仍会在注册/SERVER_INFO 里核对主机要求的单位表；客户端缺失即抛 `custom.bw`，`ae.a(bi)`/`ad.c(au)` 捕获后调用 `b("Missing unit:...")` 自断（`ae.C=false`），聊天变成 `sendChatMessage: not networked` 本地回显。进房后同步期间 `CustomUnitInject` 跳过 `l.a(ab, HashMap)` 的抛出（该方法在读完整包后再抛，不打乱后续解析），并兜底拦截 `ae.b`/`ad.b` 以 `Missing unit:` 开头的自断。同步完成（`phase=synced`）后恢复原版核对。
- **blob 下载**：清单/心跳仍用共享 OkHttp（15s）；`GET /files/{sha256}` 走独立 `blobClient`（块间空闲 60s 即重试，整次调用最多 15 分钟）。中途断流用 HTTP Range 续传（relaymod `ServeContent` 支持 206），失败整段重试 3 次但不丢已收字节；读到一半后不再换镜像（避免下一台 404 把整次判死）。进度约 200ms 节流，每 5s 打下载日志。进房下载**不得**置 `UI.showNetworkDialog`；`cancelInRoomSync`/`failInRoom` 先自增 `receivingEpoch` 作废迟到回调，再在 Main 上同步关弹窗清进度，避免回多人列表后弹出「正在下载房主模组」。下载阶段也要 `startPhaseHeartbeat`，进度停住 Presence 也不掉 TTL。
- **进度 UI**：同步进度画在玩家列表该行名称格内（`RoomPlayerNameCell`：阶段/单位名/下载百分比/已加载单位数，数据源 `ModSyncController.roomPeerBadges`——房主取 peers 轮询快照，同步中房客用 `X-Peer-Secret` 轮询脱敏列表）；房主另有 `ModSyncHostStatusBar`（有未 synced 者时带「清除同步状态」）；加入者自身进度为 `RoomSelfSyncBar`。开局门控：房内有未 synced 玩家 → 硬阻断（可「踢出并开局」）；房外同步中 peer → 二次确认强制开始。
- **服务器地址配置**：`MultiplayerPreferences.modSyncApiUrls`（`;` 分隔多镜像，常量 `DEFAULT_MOD_SYNC_API_URLS`）。
- **注入机制注意**：`Builder.applyConfig` 必须先应用 redirectMethodInfos 再应用 injectInfos（InsertBefore 会把方法体搬入 `__original__<m>` 跳板，redirect 后做会在跳板里找不到目标调用而静默失效）；`InjectApi.redirect` 生成的 `__redirect__` 占位方法体必须带默认 return（javassist 对非 void 空方法体报 no return statement），非 void 目标调用的替换必须经 `$_` 接返回值。回归测试：`rwpp-core-api` 的 `InjectRedirectApplyTest`（用真实 game-lib.jar 走 redirect + InsertBefore 叠加验证字节码形态）。
- **已移除的旧方案**（勿恢复）：自定义联机包 500-511（`ModPacket`）、`HostModTransferScheduler`、`HostManifestCache`、`UnitEngineInject` 校验拦截、PREREGISTER_INFO(161) 中的 `RoomOption` TOML 与 `GameRoom.isRWPPRoom/option`、协议版本检查。

## 测试策略

当前测试覆盖度**极低**，以手动/集成测试为主：

- **单元测试**：`rwpp-core-api/src/test/kotlin/`
  - `RwListParserTest.kt` — 房间列表 JSON 解析、URL 迁移、可加入性过滤、mod 房间版本映射等
  - `ModSyncKeysTest.kt` — 模组同步 key 推导（`sid:`/`code:`）与模组集合指纹计算（顺序无关、目录递归、mtime/size 敏感）
  - `ModSyncModelsTest.kt` — 同步协议 DTO 的 JSON 序列化往返与 snake_case 字段名锚定（防与 Go 端漂移）
  - 使用 `kotlin.test` 断言（`assertEquals`、`assertTrue`、`assertFalse`、`assertNull`）
- **资源校验测试**：`rwpp-core/src/test/kotlin/BundleParseTest.kt`
  - 用与运行时相同的方式实解析两个 `bundle_*.toml`，并检查模组同步相关键存在，把 TOML 非法挡在编译期
- **集成/调试用测试**：`rwpp-core/src/test/kotlin/MainTest.kt`
  - 包含对外部 HTTP API（`rtsbox.cn`）的真实网络请求测试
  - 主要用于开发调试，**不应在 CI 中运行**

运行命令：
```bash
./gradlew :rwpp-core-api:test
./gradlew :rwpp-core:test
```

## 代码风格与开发约定

1. **版权头**：所有 `.kt` 文件头部必须包含统一的 AGPL 版权声明块（双语：中文 + 英文），参见任何现有源文件。
2. **代码风格**：`kotlin.code.style=official`（已在 `gradle.properties` 中设定）。
3. **包名**：统一使用 `io.github.rwpp`。
4. **编译参数**：`-Xjvm-default=all`（所有模块统一）。
5. **命名习惯**：
   - 平台实现类以 `Impl` 结尾（如 `GameImpl`、`NetImpl`）
   - 注入模块以 `Inject` 结尾，按领域分组（如 `GameInject.kt`、`NetPacketInject.kt`）
   - 接口与数据模型放在 `rwpp-core-api`，实现放在平台模块
6. **日志**：使用 SLF4J API（`rwpp-core-api` 引入 `slf4j-api`）；桌面端运行时提供 `slf4j-simple`，Android 端使用 `logback-android`。
7. **中文注释**：核心业务逻辑与复杂注入点通常使用中文注释；公开 API 的 KDoc 也大量使用中文。
8. **资源引用**：Compose Multiplatform 资源通过 generated accessor 访问，如 `Res.drawable.logo`。
9. **提交信息**：git 提交说明统一使用中文。

## 国际化

- 文本资源位于 `rwpp-core/src/commonMain/composeResources/files/`
- `bundle_en.toml`（英文）、`bundle_zh.toml`（中文）
- 通过 `BaseGameI18nResolverImpl` 解析，游戏内文本分为 `I18nType.RWPP`（启动器自身）与 `I18nType.Game`（游戏本体）

### i18n bundle 编辑规范（重要，踩过坑）

`bundle_*.toml` 在**运行时**才被 `BaseGameI18nResolverImpl.init()` → `Toml.parseToTomlTable(...)` 解析，**编译期不做任何校验**。一旦 TOML 非法，启动器一打开就闪退（解析异常抛在 App 初始化路径上）。修改时必须遵守：

1. **点分键路径唯一性**：同一键路径只能有一种类型，**不能既是标量又是表**。下面这种写法会直接让整个 bundle 解析失败、App 闪退：
   ```toml
   [menu]
   singlePlayer = "单人游戏"     # menu.singlePlayer 是字符串

   [menu.singlePlayer]            # menu.singlePlayer 又是表 —— 非法！
   title = "单人游戏"
   ```
   正确做法是让两者名称错开，例如标量项用 `menu.singlePlayerGame`，子表用 `menu.singlePlayer`。
2. **新增键后必须肉眼沿点分路径逐层核对**：确认没有任何祖先/兄弟键与自己冲突，也不要重复定义同一个键。
3. **不要用「编译通过」当作资源正确的证据**：Gradle `BUILD SUCCESSFUL` 只说明 Kotlin 代码合法，对 TOML 资源零校验。改了 `bundle_*.toml` 后，运行 `./gradlew :rwpp-core:testDebugUnitTest --tests BundleParseTest`（用与运行时相同的方式实解析两个 bundle）确认合法，再交付。
4. `readI18n(path)` 在路径不存在时会抛 NPE（`table[next]!!`），不会静默回退；非法 bundle 更会导致全局解析失败。两种情况都可能在运行期才暴露。

## 账号系统约定

- **多人房间昵称绑定账号昵称**：登录 RWJS 统一账号后，多人页顶部用户名固定为账号显示名（`AccountSession.displayName`），输入框只读并显示锁图标。联动点有两处：`AccountSession.applySession()` 末尾的 `syncMultiplayerName()`（登录/注册/改昵称/刷新资料/恢复会话后写入 `lastNetworkPlayerName` 与 `game.setUserName`），以及 `Multiplayer.kt` 中 `LaunchedEffect(loggedIn, displayName)` 的实时同步。退出登录不回退已写入的名字，输入框恢复可编辑。
- **账号 UI 组件**：用户页/好友/登录注册弹窗的现代化组件集中在 `rwpp-core` 的 `ui/AccountWidgets.kt`（头像、状态胶囊、信息行、操作项、主按钮、弹窗头部、提示条、`AccountAuthCard`），新增账号相关界面时优先复用。
- **账号 API 功能边界（presence / 头像 / 积分 / 拉黑 / 换邮箱）**：客户端对接文档 6.23/6.22/6.9-6.10/6.17/6.6-6.7，全部只需 AppKey + Bearer，**不接 `POST /points/change`**（需 AppSecret，文档明确不得进客户端）。
  - **在线状态**：服务端以「最后活跃时间」判定在线（默认 TTL 5 分钟），任何带 Token 的请求都会被动刷新；客户端另在 `AccountSession` 挂了**主动心跳循环**：登录 / 恢复会话即开始每 5 秒 `POST /presence/heartbeat`（`AccountApiClient.heartbeat`），登出 / 清会话停止；循环在全局协程作用域上，**对局中照常上报**，预览模式（`networkEnabled=false`）不启动。429 按 `Retry-After` 退避，`unauthorized`/`user_disabled`/`not_found` 视为 Token 失效直接停止循环（不清本地会话）。回归测试：`rwpp-core` 的 `AccountPresenceHeartbeatTest`（MockWebServer 实收请求）。好友列表项自带 `online`/`last_active_at`（`FriendItem`），随 `refreshLists()` 轮询刷新；对方开启隐藏或任一方向拉黑时响应与真实离线完全无差别，UI 不做区分。自己的可见性开关在账号页「隐私」区块（`AccountSession.presenceSettings` / `updatePresenceSettings`，乐观更新失败回滚）。聊天头部副标题的相对时间用 `account/RelativeTime.kt`（手写 RFC3339 解析，项目无 kotlinx-datetime）。
  - **头像**：`GET /users/{id}/avatar` 返回 JPEG 字节（非 JSON），client 的 `getAvatar` 404 返回 null。Compose 侧走 Coil：`coil/AccountAvatar.kt`（userId + hasAvatar + version）+ Fetcher/Keyer（已注册进 `App.kt` ImageLoader），`AccountAvatarBox(avatar=, online=)` 有头像显真图、否则首字母。自己上传/删除成功后 `AccountSession.avatarVersion++` 使缓存 key 失效；好友覆盖上传会话内可能陈旧（可接受）。上传前客户端预检 ≤1MiB 且 jpg/jpeg/png。
  - **更换邮箱**：`AccountSession.changeEmail()` 成功后服务端使该身份所有 Token 立即失效，该方法**已自动清空本地会话**（`clearSession` + `FriendsSession.clear()`），UI 只需提示重新登录。
  - **积分**：账号页「积分」区块只读展示余额（`AccountSession.points`）与分页流水（`fetchPointLedgers`，不常驻会话状态）。
  - **拉黑**：`FriendsSession.blocks` 随 `refreshLists()` 一起拉取（`runCatching` 容错旧服务端无此端点）；`block()` 后服务端自动删除好友关系与互申，拉黑当前聊天对象会自动 `closeChat()`。入口：好友行拉黑图标（确认弹窗）+ 好友页头部黑名单对话框（解除拉黑）。
  - **AppKey 迁移**：`resolveAccountAppKey()` 把存储值等于上一代出厂 Key（`LEGACY_ACCOUNT_APP_KEY`）按空处理回落新默认——设置页的 AppKey 输入框以解析值初始化、编辑即写回，老用户配置里可能已持久化旧 Key，不迁移换 Key 对其不生效；环境变量/系统属性覆盖不迁移。
- **好友聊天输入法（鸿蒙，踩过坑）**：同一台全屏设备上，等待房间输入框能弹出输入法，是因为它在紧凑栏中部，键盘挡住的是下面的消息区；好友聊天输入框在卡片最底部，不顶起来就看不见。输入框必须用与等待房间相同的普通 `OutlinedTextField`（`weight` 直接加在输入框上）。不要用 `RWSingleOutlinedTextField`（外层 `weight` + `IntrinsicSize` 点不中），不要在获焦时 `setImeImmersiveSuspended` 去改系统栏，也不要在弹出过程中用 `imePadding()` 重排——鸿蒙会在输入法显示完成前丢掉焦点。正确做法是 `settledImeAvoidance()`：等 IME inset 稳定后再垫卡片底部（并扣掉窗口已经 `adjustResize` 掉的高度），禁止把输入框钉死在键盘下面。点消息区收起键盘用 `dismissImeOnTap()`（不可聚焦），不要把 `autoClearFocus()` 套在输入框父级上。
- **房间邀请（好友私信通道）**：等待房间内可通过「邀请好友」入口（按钮行按钮 + 玩家列表空槽位行 `RoomInviteSlotRow`，`MultiplayerRoom.kt`）向好友发邀请。协议在 `rwpp-core-api` 的 `net/account/RoomInvite.kt`：私信 body = `[RWJSINV1]` + JSON（地址/短码/邀请人/地图/人数/模组数/版本/时间戳，TTL 30 分钟），编解码 `RoomInviteCodec`（回归测试 `RoomInviteCodecTest`）。邀请地址优先取当前房间短码（`roomDetails()` 的 `[QR]\d+`），无短码才回退 `lastNetworkIP` 且必须过 `isPlausibleJoinAddress` 校验（拒绝 `Qnews`/`QC6666` 这类快速建房指令——它们会被直连输入框写入 `lastNetworkIP`，但不是可加入地址），都不可用则禁止发送。发送走 `FriendsSession.sendTo(peerId, body)`（不依赖当前会话）。接收端 `Friends.kt` 消息流按前缀识别渲染为 `RoomInviteCard`（`ui/RoomInvite.kt`），点击「立即加入」写入 `UI.pendingInviteJoin` 并跳到多人页，由 `MultiplayerView` 的 `LaunchedEffect(UI.pendingInviteJoin)` 消费后走既有 `LoadingView` → `directJoinServer` 链路（`selectedRoomDescription = null`，等价直连 IP 加入）。房间内聊天不做卡片化（`RoomChatMessageView` 是整段 AnnotatedString 只读 TextField）。
- **邀请悬浮卡片（房间外通知）**：收到好友邀请私信且不在等待房间/对局中时，屏幕右侧滑入悬浮卡片（`RoomInviteToastHost`，挂在 `App.kt` 根布局退出遮罩之前；卡片 `RoomInviteToastCard`），底部 3dp 进度条 10 秒线性耗尽后自动忽略（`Animatable` + `tween(LinearEasing)`），点「立即加入」关闭其他顶层页并写 `UI.pendingInviteJoin` 走既有加入链路（版本不符先经共用的 `InviteVersionMismatchDialog` 确认），点卡片空白处打开好友页并 `FriendsSession.openChat(peer)` 直达会话。检测在 `FriendsSession.refreshLists()` 末尾（`maybeNotifyRoomInvite`）：未读 >0、非自己发送、`RoomInviteCodec.decode` 命中且未过期才置位 `UI.incomingInviteNotification`，每会话同一条消息只弹一次（`inviteToastSeen`，被抑制也记，避免反复打扰）；正在查看该会话或 `showRoomView` 时不弹，进房瞬间已弹出的卡片由宿主侧的 `LaunchedEffect(UI.showRoomView)` 收起。轮询：主菜单局部轮询已上移到 `App.kt` 根的全局 10s 轮询（好友页/账号页打开时跳过，它们有自有轮询）。
- **房主邀请策略（软约束）**：房主可开关「成员邀请」（`RoomInvitePolicy`，`ui/RoomInvite.kt`；开关按钮在锁房图标旁，仅 `isHost` 可见）。机制：邀请走账号私信带外通道，游戏协议无法硬阻断，故房主切换时经房间聊天广播控制消息 `[RWJSCTL1] invite=true|false`；`UI.onReceiveChatMessage(sender, message, color, senderPlayer)` **先校验 `senderPlayer.isRoomHost` 且发送者昵称在房内唯一**（成员侧注入层按昵称匹配玩家，同名可冒名；歧义时一律不信任）（`Player` 接口新增，桌面实现 `n.A()==-99 || n.r()`，Android 实现 `p.T==1 || p.t()==-99`，与引擎 ping 列的 "HOST"/" (HOST)" 标记同源），是房主才应用并抑制显示，非房主伪造的控制消息按普通聊天暴露；两端注入层（`NetworkInject`/`NetInject` 的 `onShowChat`）负责把匹配到的 `Player` 传入。禁止期间每个新进房真人玩家由房主补播一次（`PlayerJoinEvent` 订阅，`MultiplayerRoomView`）。成员端 `canInvite = isHost || membersCanInvite` 为 false 时隐藏邀请按钮与空槽位行。离房（房间视图 dispose）时重置为允许。回归测试 `rwpp-core` 的 `RoomInvitePolicyTest`。
- **房间身份公示（房内名片/加好友）**：等待房间内点击成员 → 个人名片 → 加好友。带外机制，**游戏联机协议零改动**。
  - **机制**：登录客户端在房内时每 25s 向 relaymod `/api/v1/roomid/publish` 公示 `{room_keys, player_name}`（PUT，头带 `X-App-Key` + `Authorization: Bearer`，服务端向 UAS 转发 token 核验身份；记录 TTL 90s，25s 心跳续期；离房 `DELETE` 撤销）。点击成员时按 `room_key + player_name` 调 `GET .../lookup`（room_key 重复 query 参数，一律 200 空数组兜底），命中得账号三元组（user_id/username/nickname），再调 UAS 公开接口 `lookup` + `presence` 补齐名片；加好友走既有 `FriendsSession.sendRequest`。
  - **key 推导**（`rwpp-core-api` 的 `net/roomid/RoomIdentityKeys.kt`）：短码 → `code:XXX`（复用 `ModSyncKeys.forAddress`，归一大写）；直连地址 → `addr:host:port`（去空白、host 小写、无端口补 5123；`Qnews`/`QC6666` 等快速建房指令串不产出 key）；列表房间委托 `ModSyncKeys.forRoomDescription`（sid + code）。每玩家最多 8 个 key，按 sid > code > addr 截断（`prioritizeIdentityKeys`）。
  - **failover 语义（与 `ModSyncClient.withFailover` 不同，独立实现）**：404/503/5xx/网络错换下一镜像；401 直接抛不换镜像；全部镜像 404/503 → `RoomIdFeatureUnavailableException`，会话内降级（`RoomIdentityController.featureUnavailable`），不影响其它功能。
  - **隐私**：开启「对陌生人隐身」（`AccountSession.presenceSettings.hideFromStrangers`）时不发布；未登录 / 预览模式（`networkEnabled=false`）不发布；单人/沙盒房不启动公示。
  - **UI 入口**：`RoomPlayerTableRow` 点击分派（房主/主机/自己 → 玩家配置弹窗，其余真人玩家 → `PlayerCardDialog`）；桌面右键菜单「查看名片」（`ContextMenuArea.kt`，踢出项仍仅房主）；`PlayerOverrideDialog` 底部「查看名片」按钮。名片弹窗 `ui/PlayerCard.kt`：游戏区（名字/队伍/延迟/房主标记）+ 账号区按 `RoomIdentityResult` 渲染（Self/NotLoggedIn/AiPlayer/FeatureUnavailable/Found/NotFound/Ambiguous/Error）。
  - **编排**：`rwpp-core` 的 `account/RoomIdentityController.kt`（不改 `AccountSession`）。挂点：`Multiplayer.kt` LoadingView 成功块 `onJoiningRoom(address, desc)`；`MultiplayerRoom.kt` 短码就绪 `addRoomCodeKey` + 非单人房 `startPublishing`、发布成功 `addServerIdKey`、onDispose `stopPublishing`；`DisconnectEvent` 订阅兜底 stop。baseUrls 复用 `MultiplayerPreferences.modSyncApiUrls`。
  - **红线**：relaymod 必须同步部署承认 `/api/v1/roomid/` 端点组（否则全镜像 404，客户端按功能不可用降级）；回归测试 `rwpp-core-api` 的 `RoomIdentityModelsTest` / `RoomIdentityKeysTest` / `RoomIdentityClientTest`（MockWebServer）。

## 安全与部署注意事项

1. **AGPL-3.0**：fork 与再分发时必须保留许可与版权信息，并遵守 AGPL-3.0 的全部义务（包括网络交互版本的源代码提供义务）。
2. **游戏库依赖**：`lib/game-lib.jar` 与 `lib/android-game-lib.jar` 是原版铁锈战争的反编译/提取库，**不**包含在本仓库的纯源码发布中；构建前需自行准备。
3. **Android 权限**：需要 `INTERNET`、`READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` 等广泛存储与网络权限。
4. **桌面端运行**：`.jar` 或 `.exe` 必须放置在游戏根目录（与原版游戏同级），以便加载 `mods/`、`maps/` 等资源。
5. **MSI 打包**：`wix/` 是一个独立的 .NET 控制台项目，通过 Gradle 自定义 task `packageWixDistribution` 调用 `dotnet run` 生成 MSI；依赖 .NET SDK。
   - 构建产出两个文件：`RWPP.msi`（纯 MSI）与 `RWPP-Setup.exe`（自托管安装器，内部嵌入 MSI）。
   - **首次安装**：双击 `RWPP-Setup.exe`，走完整向导（Welcome → License → Features → InstallDir → Progress → Exit）。
   - **更新模式**：`RWPP-Setup.exe` 支持将命令行参数透传给 `msiexec`，实现自动更新：
     - `RWPP_UPDATE_MODE=1` — 跳过 Welcome/License/Features/InstallDir，直接显示 Progress 进度条；安装路径使用 MSI 注册表中记录的 `INSTALLDIR`，不再重新检测 Rusted Warfare 目录。
     - `/quiet` — 完全静默，不加载任何 UI。
     - `/norestart` — 安装完成后不重启系统。
     - 示例：`RWPP-Setup.exe /quiet /norestart RWPP_UPDATE_MODE=1`
   - **升级实现细节**：
     - `UpgradeCode` 固定为 `abc38343-cdb8-4e3f-aa7f-0ead99385de1`，确保跨版本可被 Windows Installer 识别为同一产品系列。
     - `ProductCode` 基于 `UpgradeCode + Version` 动态生成（确定性 GUID），每次版本号变化都会改变，满足 Major Upgrade 要求。
     - 更新模式若无法通过 `WIX_UPGRADE_DETECTED` 获取旧路径，会 fallback 扫描注册表 `Uninstall` 键，覆盖 `HKLM/HKCU × Registry64/Registry32`（含 WOW 重定向），以支持 perUser 安装和 32-bit 卸载项。
6. **网络配置**：桌面与 Android 的 `jvmArgs` / manifest 中都设置了 `preferIPv4Stack=true` 与 `usesCleartextTraffic="true"`。

## 常见问题

- **KSP 增量编译**：根目录 `gradle.properties` 中显式设置了 `ksp.incremental=false`，因为注入元数据变更通常需要全量重新生成。
- **JitPack 构建**：通过检测 `JITPACK` 环境变量排除 app 模块，仅发布 library（`rwpp-core-api`、`rwpp-core`）。
- **Android 模组重载 OOM 防线**：模组能否加载取决于 ini 表达式密度（`select(`/`memory.`/`[decal_]`/`copyFrom`）而非文件体积——图片音频解码后落在 native 堆，不占 ART 堆（largeHeap=512MB）配额；进程内重载新旧单位表并存，峰值约翻倍，脚本密集型模组必撞 OOM。防线只保不闪退、不做重启引导：两端 `runReloadCore()` 外层 catch `OutOfMemoryError` 吞掉异常并置位 `UI.modReloadMemoryExhausted`（游戏线程不因未捕获 OOM 崩溃）；引擎会把 OOM 包装进肇事模组的错误消息，Mods 页据此弹窗**指名元凶模组**（`MemoryExhaustedDialog`）；标志置位后锁定一切后续重载（二次 OOM 必崩），进程重启后自然清除。
- **HiDPI**：LWJGL2 在 `Display.setParent` 模式下直接以 Canvas 组件尺寸（AWT 逻辑像素）创建原生渲染子窗口，系统缩放 >100% 时会导致画面缩在屏幕左上角。桌面端 `Main.kt` 通过 `syncGameCanvasSizeToNative()` 将 Canvas 尺寸保持为物理像素（容器逻辑尺寸 × 缩放比例）来抵消；缩放比例优先取主窗口实际所在显示器的 `GraphicsConfiguration`（`getDPIScale()`）。由于 CardLayout 每次 `validate` 会把 Canvas 重置回逻辑尺寸，该同步同时挂在 Canvas 自身的 `componentResized`、window 的 `componentResized`/`componentMoved` 与 `DisplaySwitcher.onAfterSwitch` 上，被布局重置后自动纠正。
