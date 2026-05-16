# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

## 代码生成流水线

`rwpp-ksp` 是一个自定义 KSP 处理器，在编译期扫描 `lib/` 下的游戏库 jar：

- **Desktop**: 传入 `lib=game-lib`，处理 `lib/game-lib.jar`
- **Android**: 传入 `lib=android-game-lib`，处理 `lib/android-game-lib.jar`

KSP 生成的代码输出到 `build/generated/ksp/`。同时 Koin 也通过 `koin-ksp-compiler` 生成 DI 代码。

构建前需确保 `lib/` 目录中的游戏库 jar 存在（Android 缺少 assets/res 时可从本地已安装的铁锈战争客户端补齐）。

## 关键技术与版本

- **Kotlin** 2.1.20, **Compose Multiplatform** 1.10.1, **AGP** 8.7.3
- **Koin** 4.0.1（编译期注解 + KSP 生成 DI 代码）
- **Coil3** (`coil-compose` + `coil-network-okhttp`) 图片加载，`rwpp-core` 中有自定义 `ImageableFetcher`/`ImageableKeyer`
- **LuaJ** 4.0.2 (`luajava` + `lua54`) 用于 Lua 脚本支持
- **LWJGL** (`lib/lwjgl.jar` 等) 桌面 OpenGL 渲染
- **WiX** (`wix/Program.cs`) .NET 项目，通过自定义 Gradle task `packageWixDistribution` 构建 Windows MSI 安装包

## 平台抽象模式

`rwpp-core/src/commonMain/` 中通过 `expect` 声明平台差异接口：
- `platform/Back.kt` — 返回导航
- `platform/ContextMenuArea.kt` — 右键菜单
- `platform/Graphics.kt` — 图形初始化
- `graphics/GL20.kt`, `graphics/GL30.kt` — OpenGL 接口

Android `actual` 实现在 `rwpp-core/src/androidMain/`，桌面实现在 `rwpp-desktop/src/main/` 中。

## 导航模式

无第三方导航库。`App.kt` 中通过 `MutableState<Boolean>` 变量（如 `showMultiplayerView`, `showSettingsView`, `showRoomView`）配合 `AnimatedVisibility` 控制页面切换。当前视图为 null 时显示主菜单。

## 国际化

`rwpp-core/src/commonMain/composeResources/files/` 下有 `bundle_en.toml` 和 `bundle_zh.toml`，通过 `BaseGameI18nResolverImpl` 解析。

## 许可证

AGPL-3.0。所有源文件头部需包含版权声明（参见 `settings.gradle.kts` 中的模板）。
