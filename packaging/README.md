# RWJS 发布打包工具

PC 安装器完全使用 Inno Setup，产物为 `RWJS-Setup.exe`。默认安装到 `%ProgramFiles%\RWJS`，支持中文向导、桌面/开始菜单快捷方式、覆盖更新与 Windows 应用卸载。

## 构建

在 Windows 上准备 JDK 21、Inno Setup **6.5+**（推荐 6.x）、原版游戏目录（包含 `game-lib.jar` 和 `assets/`）。安装包始终自带 Java 运行时，用户无需安装 Java。编译器从 `INNO_SETUP_COMPILER`、PATH、常见全局/用户安装目录依次查找；自定义位置请指定 `ISCC.exe` 的完整路径。

```powershell
$env:RW_GAME_ROOT = 'D:\APP\Steam\steamapps\common\Rusted Warfare'
# 可选：编译器安装在自定义目录时指定
$env:INNO_SETUP_COMPILER = 'C:\Tools\Inno Setup 6\ISCC.exe'
.\gradlew.bat :rwpp-desktop:packageInnoDistribution
```

游戏目录优先级：`RW_GAME_ROOT` → `packaging/game-root.local.txt` → 上例默认 Steam 目录。不要将游戏资源、本机路径或生成安装包提交进 Git。

仓库跟踪的发布脚本在构建后收集产物并生成 Gitee 分卷：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/build.ps1 installer
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/build.ps1 installer -Channel official
```

- 原始安装包：`build/installer/RWJS-Setup.exe`
- 发布目录：`build/artifacts/installer-<渠道>/`（默认 `installer-official/`）
- 发布文件：`RWJS-Setup.exe`、带版本号的 exe、`RWJS-Setup.zip.001/.002/…`（每卷最多 95 MiB）、`RWJS-Setup.zip.sha256`
- 单卷时发布 exe；多卷时发布全部分卷与校验文件。客户端只在至少两卷连续时采用分卷更新。

已有桌面分发可直接打包：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/package.ps1 -Version 1.19.363
```

## Android 分卷发布

本机 `build/build.ps1` 转发到仓库跟踪的 `packaging/build.ps1`；两种入口使用同一套升级后的打包工具。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build/build.ps1 android-release -Channel official
# Debug 仅供测试
powershell -NoProfile -ExecutionPolicy Bypass -File build/build.ps1 android-debug
```

产物位于 `build/artifacts/android-release-<渠道>/` 或 `android-debug-<渠道>/`：保留完整 APK 供本地安装，同时生成 `RWJS-Android.zip.001/.002/…`（每卷最多 95 MiB）和 `RWJS-Android.zip.sha256`，附发布说明。

Gitee Release 必须上传**全部分卷及校验文件**。即使只有 `.001` 一卷，也要上传对应 SHA-256。这是标准 ZIP 的字节分卷，合并后解出完整签名 APK，再由系统安装；不是 Android 系统的 split APK。分卷不改变 APK 签名。正式发布必须使用 Release 包，保持原签名和正确渠道；不要混传不同渠道或 Debug 分卷。

旧版 Android 客户端只有单 APK 下载能力，不能自动识别新增分卷。首次迁移需手动下载全部分卷，用 7-Zip/WinRAR 打开 `.001` 解出 APK 并覆盖安装；后续版本才可使用分卷自动更新。若另有可用的完整 APK 下载渠道，也可通过该渠道完成首次升级。

Android 客户端优先采用连续且带校验文件的 Android 分卷组；缺卷、重复序号或缺少校验时回退旧版单 APK，否则显示手动安装入口。PC 继续支持原有分卷和单 EXE。下载器先校验 ZIP 的 SHA-256，再流式解包，不落合并后的大 ZIP；CRC 同时检测 ZIP 内容损坏。

打包自测（PowerShell 5.1+）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/tests/UpdatePackages.Tests.ps1
```

## 包内容

`RWJS.exe`、`app/`（过滤非 Windows Skiko）、`runtime/`、图标、AGPL 许可证，以及原版目录的 `game-lib.jar`、根目录原生库、`steam_appid.txt`、`assets/`、`font/`、`libs/`、`res/`。

只包含 RWJS 入口，不附带原版 exe、启动脚本、原版 JVM、用户模组/地图、用户配置、缓存、存档、回放、日志、备份或注入生成库。安装时创建空 `mods/units` 和 `mods/maps`。资源暂存到 `build/tmp/game-payload/`，跳过符号链接/junction。

## 注册表、更新和卸载

全新且固定的 AppId：`{C9F7D3ED-E974-4C19-9291-9D5460608316}`。Inno Setup 自动登记 Windows 卸载项；发布后不能更换此 AppId。

64 位 `HKLM\SOFTWARE\RWJS` 登记 `InstallDir` 与 `InstalledVersion`。安装需要管理员权限；引擎仍向根目录写配置和资源，安装器为普通用户设置该目录的修改权限。不会读取、修改或迁移旧 RWPP 注册表、MSI 或旧安装目录。

```powershell
# 自动更新显示进度，锁定新 RWJS 注册表指向的目录
.\RWJS-Setup.exe /RWJS_UPDATE=1 /SILENT /SP- /NORESTART /LOG
# 正常静默安装可指定独立目录
.\RWJS-Setup.exe /VERYSILENT /SP- /NORESTART /DIR="D:\Games\RWJS"
```

客户端通过 Windows PowerShell 的 `Start-Process -Verb RunAs` 请求 UAC 授权，辅助进程隐藏；拒绝授权时留在全屏更新页，可重试。提权行为参见[微软 Start-Process 文档](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/start-process?view=powershell-5.1)。

缺少有效 RWJS 安装记录时，更新模式中止并提示正常安装。客户端只调用新参数。覆盖更新清理安装器管理的 `app/*.jar`，避免版本改名后依赖冲突；保留用户配置和模组。更换已安装的 RWJS 目录前须先卸载。

卸载仅移除安装器登记的文件、快捷方式、注册表值及空目录，不通配删除游戏根目录；用户自行添加的模组、存档、回放和配置保留。包内附带的资源属于安装文件，会随卸载删除。

## 验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/tests/payload-test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/tests/installer-test.ps1
.\gradlew.bat :rwpp-core-api:test :rwpp-desktop:packageInnoDistribution
```

安装器测试使用同一生产脚本，以独立 AppId、HKCU、临时目录和测试快捷方式隔离真实安装，覆盖注册表、更新、旧 jar 清理和卸载数据保留。发布前还需在 Windows 实际验证中文向导、普通用户启动游戏和占用文件提示。

简体中文消息文件来自 [Inno Setup 官方源码](https://github.com/jrsoftware/issrc/blob/2f452efbbba794d8329592c386576361ad611e6c/Files/Languages/ChineseSimplified.isl)，保留其维护者信息，随仓库固定以避免编译器安装缺少翻译文件。参数与身份规则见[官方文档](https://jrsoftware.org/ishelp/topic_setupcmdline.htm)和 [AppId 文档](https://jrsoftware.org/ishelp/topic_setup_appid.htm)。
