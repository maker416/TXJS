<div align="center">
<h1>铁锈战争极速版</h1>
<div align="center">
  <strong>基于 RWPP，面向萌新云生态的多平台铁锈战争启动器</strong>
</div>
<br />
<div align="center">
 <img src = "https://github.com/Minxyzgo/RWPP/blob/main/rwpp-core/src/commonMain/composeResources/drawable/logo.png" width = "100px"/>
</div>
<br />

----
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.10.1-brightgreen)](https://www.jetbrains.com/lp/compose-multiplatform/)
![Android](https://img.shields.io/badge/Android-green)
![Desktop](https://img.shields.io/badge/Desktop-tomato)
[![License](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
</div>

## 关于本项目

**铁锈战争极速版**是从 [RWPP](https://github.com/Minxyzgo/RWPP) 体系 fork 并独立维护的项目，在遵守上游及本仓库**开源许可**的前提下持续开发。技术基底与 RWPP 一脉相承，产品方向聚焦于**萌新云**与更清爽的铁锈联机体验，致力于缓解当前铁锈生态中的典型痛点。

### 我们关注的问题

- **上手与开放流程繁琐**：原版路径长、步骤多，不利于快速开局。
- **服务器列表体验混乱**：列表信息杂乱，难以快速找到合适房间。
- **广告与干扰过多**：影响浏览与进房效率。
- **公开房间曝光困难**：房间难以稳定、清晰地出现在公开列表中，房主与玩家匹配成本高。

本项目以「极速版」为定位，在 RWPP 能力之上持续优化上述体验（具体能力以各版本 Release 说明为准）。

### 模组分享账号认证

通过 relaymod 分享模组需要先登录极速版账号；接收同步和下载仍免登录。
服务端每次验证房主 token 并检查房间归属。分享中登录失效会停止上传，在房间状态条重新登录后可点击「重新分享」。

部署新版服务端时配置 UAS 地址及 TXJS 应用 Key 白名单，并将客户端「同步服务器地址」设为实际的 HTTPS 入口；
公网 HTTP 地址和未升级的同步节点不能接收上传凭据，本机 HTTP 仅用于联调。
所有镜像均需升级；旧客户端仅可继续下载。配置及旧房间迁移详见相邻 `relaymod` 仓库的 README。

<h1 align="center">下载</h1>

请在 **本仓库 Releases** 页面下载对应平台的发行包（若仓库已迁移，请使用新仓库的 Releases）。

<h1 align="center">运行</h1>

## Windows（Inno Setup）

- 运行 `RWJS-Setup.exe`，默认安装到 `C:\Program Files\RWJS`，也可选择独立目录。
- 安装包自带游戏运行资源和 Java 运行时；通过桌面/开始菜单快捷方式或 `RWJS.exe` 启动。
- 此安装器采用全新的 RWJS 身份，不迁移旧 MSI 安装。

## Jar 版本

- 安装 **Java 17** 或以上。
- 将发行包中的 `.jar` 放到游戏根目录，例如：  
  `SteamLibrary\steamapps\common\Rusted Warfare\`
- 使用随包提供的 `launcher.bat` 或等价脚本启动（以 Release 说明为准）。

<h1 align="center">构建</h1>

- 使用 **OpenJDK 17** 或以上。
- 桌面端可执行任务：`rwpp-desktop:packageReleaseUberJarForCurrentOS`
- 构建 Windows 一体包：执行 `./gradlew :rwpp-desktop:packageInnoDistribution`，或 `powershell -File packaging/build.ps1 installer`（需 Inno Setup 6.5+ 和本机原版游戏目录；见 [打包说明](packaging/README.md)）。
- Android 端若缺少部分 assets/res，可从本机已安装的铁锈战争客户端中对照补齐。

## 模组堆内存分析工具

安装 Java 21 后，双击仓库根目录的 `启动模组内存分析.bat` 即可打开独立桌面界面，支持选择或拖入模组、批量分析、单位占用排行及 TXT / CSV 导出。结果为静态估算。构建、命令行用法与估算范围见 [工具说明](docs/mod-heap-tool.md)。

<h1 align="center">参与贡献</h1>

若发现缺陷或有功能建议，欢迎提交 Issue；也欢迎通过 Pull Request 参与改进。

<h1 align="center">致谢与协议</h1>

- 上游启动器：[RWPP](https://github.com/Minxyzgo/RWPP)
- 相关生态：[RW-HPS](https://github.com/deng-rui/RW-HPS)

**许可**：本仓库沿用 **GNU Affero General Public License v3.0（AGPL-3.0）**（见根目录 [`LICENSE`](./LICENSE)）。fork 与再分发时请务必保留许可与版权信息，并遵守 AGPL-3.0 的全部义务。
