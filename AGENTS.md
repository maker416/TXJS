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
- `net/` — 网络层：房间列表解析、数据包定义、版本查询；**此处包含唯一一组真正的单元测试**（`RwListParserTest.kt`）
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

# Android Release APK（需先配置签名）
./gradlew :rwpp-android:assembleRelease
```

Gradle JVM 参数在根目录 `gradle.properties` 中定义（`-Xmx2048M`）。

### 构建前准备

1. 确保 `lib/` 目录中的游戏库 jar 存在：
   - 桌面端需要 `lib/game-lib.jar`
   - Android 端需要 `lib/android-game-lib.jar`
2. Android 若缺少 assets/res，需从本地已安装的铁锈战争客户端对照补齐。
3. 桌面端运行/构建 MSI 需要 `.NET SDK`（用于 `wix/Program.cs` 的 WiX 打包）。
4. 若首次运行或注入配置变更，`rwpp-desktop` 会在启动时进入「应用注入配置」模式，完成后自动重启。

### 发布产物

- 桌面端：`build/desktop-jar/` 下生成 `.jar`，配合 `launcher.bat` 等脚本使用；MSI 通过 `wix/` 下的 .NET 项目生成
- Android：`rwpp-android/build/outputs/apk/` 下生成 APK

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

- `showMultiplayerView`、`showSettingsView`、`showRoomView`、`showMissionView`、`showModsView`、`showExtensionView`、`showReplayView`、`showContributorList`、`showResourceBrowser`
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

## 测试策略

当前测试覆盖度**极低**，以手动/集成测试为主：

- **单元测试**：`rwpp-core-api/src/test/kotlin/RwListParserTest.kt`
  - 测试房间列表 JSON 解析、URL 迁移、可加入性过滤、mod 房间版本映射等
  - 使用 `kotlin.test` 断言（`assertEquals`、`assertTrue`、`assertFalse`、`assertNull`）
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

## 国际化

- 文本资源位于 `rwpp-core/src/commonMain/composeResources/files/`
- `bundle_en.toml`（英文）、`bundle_zh.toml`（中文）
- 通过 `BaseGameI18nResolverImpl` 解析，游戏内文本分为 `I18nType.RWPP`（启动器自身）与 `I18nType.Game`（游戏本体）

## 安全与部署注意事项

1. **AGPL-3.0**：fork 与再分发时必须保留许可与版权信息，并遵守 AGPL-3.0 的全部义务（包括网络交互版本的源代码提供义务）。
2. **游戏库依赖**：`lib/game-lib.jar` 与 `lib/android-game-lib.jar` 是原版铁锈战争的反编译/提取库，**不**包含在本仓库的纯源码发布中；构建前需自行准备。
3. **Android 权限**：需要 `INTERNET`、`READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` 等广泛存储与网络权限。
4. **桌面端运行**：`.jar` 或 `.exe` 必须放置在游戏根目录（与原版游戏同级），以便加载 `mods/`、`maps/` 等资源。
5. **MSI 打包**：`wix/` 是一个独立的 .NET 控制台项目，通过 Gradle 自定义 task `packageWixDistribution` 调用 `dotnet run` 生成 MSI；依赖 .NET SDK。
6. **网络配置**：桌面与 Android 的 `jvmArgs` / manifest 中都设置了 `preferIPv4Stack=true` 与 `usesCleartextTraffic="true"`。

## 常见问题

- **KSP 增量编译**：根目录 `gradle.properties` 中显式设置了 `ksp.incremental=false`，因为注入元数据变更通常需要全量重新生成。
- **JitPack 构建**：通过检测 `JITPACK` 环境变量排除 app 模块，仅发布 library（`rwpp-core-api`、`rwpp-core`）。
- **HiDPI**：桌面端 `Main.kt` 中通过 `GraphicsEnvironment` 获取系统 DPI 缩放比例，计算逻辑像素尺寸后设置 Canvas 物理尺寸。
