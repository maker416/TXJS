# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent 配置

创建 Agent 时始终显式指定 `model: "sonnet"`。Explore 等 agent 默认使用 Haiku，但当前环境 Haiku 模型路由未配置，会导致 400 错误。

## 构建命令

```bash
# 桌面端 fat jar
./gradlew :rwpp-desktop:packageReleaseUberJarForCurrentOS

# Windows MSI 安装包（需要 .NET SDK）
./gradlew :rwpp-desktop:packageWixDistribution

# 运行测试（仅 rwpp-core 有测试）
./gradlew :rwpp-core:test

# Android Debug APK
./gradlew :rwpp-android:assembleDebug

# Android Release APK（需先配置签名）
./gradlew :rwpp-android:assembleRelease
```

Gradle JVM 参数及版本号均在 `gradle.properties` 中。需要 JDK 17+。

## 模块架构

| 模块 | 类型 | 职责 |
|------|------|------|
| `rwpp-core-api` | JVM library | 纯接口/数据模型层——Game、Net、Config、Event、Inject 等抽象，无 Compose 依赖 |
| `rwpp-core` | KMP (Android + Desktop JVM) | 共享 Compose Multiplatform UI + 核心逻辑，通过 `expect`/`actual` 实现平台抽象 |
| `rwpp-android` | Android app | Android 入口 (`MainApplication`, `MainActivity`, `LoadingScreen`) + `impl/` 平台实现 (~26 个 Impl 类 + 14 个 Inject 模块) |
| `rwpp-desktop` | Compose Desktop app | 桌面入口 (`Main.kt` — Swing JFrame + ComposePanel + OpenGL Canvas) + `impl/` 平台实现 |
| `rwpp-ksp` | KSP processor | 编译期代码生成——处理 `lib/` 中的游戏库 jar，为平台模块生成注入代码 |

**依赖流向**: `rwpp-core-api` ← `rwpp-core` ← `rwpp-android` / `rwpp-desktop`，`rwpp-ksp` 作为 KSP 插件被 android/desktop 使用。

## 桌面端游戏生命周期

桌面端使用双层显示架构：**ComposePanel**（菜单 UI）和 **AWT Canvas**（OpenGL 游戏画面）通过 `CardLayout` 切换。

### 启动流程

1. `main()` 启动 Koin DI → 检查是否需要重新生成注入配置（`Builder.prepareReloadingLib()`）
2. 若需要重新注入：显示 `InjectConsole`，重新构建 game-lib.jar → 提示重启
3. 若正常启动：`Game.load()` → `GameImpl.load()` 创建独立游戏线程 → LWJGL `Display.setParent(gameCanvas)` → 初始化游戏引擎（Main、ScriptEngine、OpenAL 等）→ 广播 `GameLoadedEvent`
4. 菜单 ComposePanel 渲染 `App()`，游戏 Canvas 由游戏线程独立驱动

### DisplaySwitcher

[DisplaySwitcher.kt](rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/DisplaySwitcher.kt) 封装 `CardLayout` 切换逻辑：
- `DisplayMode.Menu` — 显示 ComposePanel
- `DisplayMode.Game` — 显示 OpenGL Canvas
- 必须在 EDT 上执行切换（内部通过 `SwingUtilities.invokeAndWait` 保证线程安全）
- 提供 `onBeforeSwitch` / `onAfterSwitch` 钩子

### GameSessionManager

[GameSessionManager.kt](rwpp-desktop/src/main/kotlin/io/github/rwpp/desktop/GameSessionManager.kt) 编排游戏会话：
- 接收 `GameStartMode`（Mission / Skirmish / Multiplayer / Replay / Continue）并调用 `DisplaySwitcher.switchTo(Game)`
- `returnToMenu()` 切回菜单并调用 `DisplaySwitcher.switchTo(Menu)`
- 管理 `isGameOver`、`gameSpeed`、`isHost` 等游戏状态

### OffscreenComposeRenderer

游戏运行时，Compose UI 无法直接绘制到 OpenGL Canvas。`OffscreenComposeRenderer` 将 Compose 内容离屏渲染为 `BufferedImage`，再通过 OpenGL 纹理贴到游戏画面上（`renderFullscreenQuad`），实现游戏内 UI 叠加。

## KSP 字节码注入系统

这是项目最核心的技术——通过编译期注解 + KSP 生成配置，运行时修改游戏库 jar 的字节码。

### 注解（定义在 `rwpp-core-api/inject/annotations.kt`）

| 注解 | 作用 |
|------|------|
| `@InjectClass(KClass)` | 标记顶层 `object` 为注入目标类的注入器 |
| `@Inject(method, mode, desc)` | 将函数注入到目标类的指定方法中 |
| `@InjectMode.Override` | 完全替换目标方法体 |
| `@InjectMode.InsertBefore` | 在原方法体前插入代码（返回类型必须为 `Any`） |
| `@SetInterfaceOn(classes)` | 让多个类实现指定接口（可配合 `@NewField`/`@Accessor` 添加字段） |
| `@RedirectTo(from, to)` | 类重定向 |
| `@RedirectMethod` | 方法调用重定向到另一个方法 |

### 处理流程

1. **编译期**：`rwpp-ksp/MainProcessor.kt` 扫描 `@InjectClass` / `@SetInterfaceOn` 等注解 → 收集 `InjectInfo`、`SetInterfaceOnInfo` 等 → 调用 `Builder.saveConfig()` 序列化到 JSON 配置文件
2. **运行期**：`Main.kt` 启动时 `Builder.prepareReloadingLib()` 检查配置是否过期 → 若需要，用 `Builder.init()` 读取配置、加载 game-lib.jar 的 class pool（javassist）、应用所有注入/重定向/接口设置 → 写回新的 game-lib.jar → 提示用户重启

### 桌面端注入模块

`rwpp-desktop/impl/inject/` 下有 20 个注入模块，每个都是一个被 `@InjectClass` 标记的顶层 `object`，对游戏库中的关键类进行方法级别的覆盖或插入：

- **RootInject.kt** — `Root.showMainMenu()`, `showBattleroom()`, `makeSendMessagePopup()` → 桥接到 `GameSessionManager` / `DisplaySwitcher`
- **MainInject.kt** — `Main.onStartGame()` → 广播 `StartGameEvent`；`onPlayerJoin()` → 发送欢迎消息
- **GameInject.kt** / **GameEngineInject.kt** / **GameViewInject.kt** — 游戏核心逻辑注入
- **ClientInject.kt** / **NetworkInject.kt** / **NetPacketInject.kt** — 网络层注入
- **GuiInject.kt** / **DrawableInject.kt** / **AssetInject.kt** — UI/资源注入
- **ScriptEngineInject.kt** / **MapTriggerInject.kt** — Lua 脚本和地图触发器
- 等等

Android 端在 `rwpp-android/impl/inject/` 下有对应的注入模块。

## Koin DI 模式

使用 Koin 4.0.1 + KSP 编译期注解（`koin-annotations`）：

- `ConfigModule`（`@Module` + `@ComponentScan("io.github.rwpp.config")`）— 自动扫描配置类
- `DesktopModule` / `AndroidModule` — 平台特定模块，通过 `@Single(binds = [Game::class])` 将实现绑定到接口
- `CompModule` — 组件模块
- `startKoin { modules(...) }` 在 `Main.kt` / `MainActivity` 中启动，通过 `.module` 属性获取 KSP 生成的模块代码

关键接口（`rwpp-core-api` 中定义，平台模块实现）：
- `Game` — 游戏核心（加载、开房、加入、地图/任务/回放管理）
- `Net` — 网络抽象
- `ConfigIO` — 配置读写
- `Settings` — 用户设置

## 事件系统

`rwpp-core-api/event/` 提供协程式事件总线：

- `Event` 接口 + `AbstractEvent` 基类 — 自定义事件继承 `AbstractEvent`
- `GlobalEventChannel` — 全局事件通道，支持优先级和拦截
- `event.broadcast()` — 挂起函数，等待所有监听器处理完成
- `event.broadcastIn()` — 异步广播（在协程中）
- `GlobalEventChannel.filter(EventClass).onDispose { subscribeAlways { ... } }` — Compose 中订阅事件的模式

常用事件：`GameLoadedEvent`, `QuitGameEvent`, `StartGameEvent`, `ReturnMainMenuEvent`, `RefreshUIEvent`

## 导航模式

无第三方导航库。`App.kt` 中通过 `MutableState<Boolean>` 变量（如 `showMultiplayerView`, `showSettingsView`, `showRoomView`）配合 `AnimatedVisibility` 控制页面切换。当前视图为 null 时显示主菜单。

## 国际化

`rwpp-core/src/commonMain/composeResources/files/` 下有 `bundle_en.toml` 和 `bundle_zh.toml`，通过 `BaseGameI18nResolverImpl` 解析。

## 平台抽象模式

`rwpp-core/src/commonMain/` 中通过 `expect` 声明平台差异接口：
- `platform/Back.kt` — 返回导航
- `platform/ContextMenuArea.kt` — 右键菜单
- `platform/Graphics.kt` — 图形初始化
- `graphics/GL20.kt`, `graphics/GL30.kt` — OpenGL 接口

Android `actual` 实现在 `rwpp-core/src/androidMain/`，桌面实现在 `rwpp-desktop/src/main/` 中。

## lib/ 目录结构

构建前需确保 `lib/` 中的游戏库 jar 存在：

```
lib/
├── game-lib.jar          # 桌面端游戏库（KSP 注入目标）
├── android-game-lib.jar  # Android 游戏库（KSP 注入目标）
├── android-platform-lib.jar
├── android.jar
├── lwjgl.jar, lwjgl_util.jar, lwjgl_util_applet.jar  # LWJGL 2.x
├── natives-linux.jar
├── slick.jar             # Slick2D 游戏框架
├── ibxm.jar, jinput.jar, jnlp.jar
├── jogg-0.0.7.jar, jorbis-0.0.15.jar  # OGG/Vorbis 音频
├── tinylinepp.jar        # SVG/矢量渲染
└── httpclient-*.jar, httpcore-*.jar, httpmime-*.jar,
    commons-codec-*.jar, commons-logging-*.jar, fluent-hc-*.jar  # HTTP 客户端
```

Android 缺少 assets/res 时可从本地已安装的铁锈战争客户端补齐。

## 关键技术与版本

- **Kotlin** 2.1.20, **Compose Multiplatform** 1.10.1, **AGP** 8.7.3
- **Koin** 4.0.1（编译期注解 `@Module`/`@Single` + KSP 生成 DI 代码）
- **Coil3** (`coil-compose` + `coil-network-okhttp`) 图片加载，`rwpp-core` 中有自定义 `ImageableFetcher`/`ImageableKeyer`
- **LuaJ** 4.0.2 (`luajava` + `lua54`) 用于 Lua 脚本支持
- **LWJGL** 2.x (`lib/lwjgl.jar` 等) 桌面 OpenGL 渲染
- **Javassist**（通过 `rwpp-ksp` 依赖）运行时字节码操作
- **WiX** (`wix/Program.cs`) .NET 项目，通过自定义 Gradle task `packageWixDistribution` 构建 Windows MSI 安装包

## 许可证

AGPL-3.0。所有源文件头部需包含版权声明（参见 `settings.gradle.kts` 中的模板）。
