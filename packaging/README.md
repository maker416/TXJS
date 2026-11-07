# RWJS 发布打包工具

Linux 提供自带 Java 21 和原版运行资源的完整包；Windows 安装器继续使用 Inno Setup，产物为 `RWJS-Setup.exe`。PC 完整包优先从原版 ZIP 读取资源。

## Linux 构建

将 Linux x64 原版 ZIP 命名为 **`Rusted-Warfare-linux.zip`**，放在 **`build/build.sh` 同级目录**。同一项目可以同时保存两个平台的资源包：

```text
build/
  build.sh
  build.ps1
  Rusted-Warfare-linux.zip
  Rusted-Warfare-win.zip
```

Linux 入口使用 Bash，自动检测 Python **3.9+** 和完整 **JDK 21**（包含 `javac`、`jlink`、`jmods`）。会跳过 PATH 中的旧 Python、无效的 Windows `JAVA_HOME` 或非 21 的 JDK，从 PATH、常见系统目录、SDKMAN 和用户 JDK 目录中查找合适版本，并配置当前打包进程的 `JAVA_HOME` / `PATH`，不写用户启动文件或系统环境变量。

缺少工具时自动使用已有的 apt-get / dnf 安装：APT 使用 `python3`、`openjdk-21-jdk`；DNF 使用 `python3`、`java-21-openjdk-devel`、`java-21-openjdk-jmods`。首次需要网络和 root/sudo 权限，交互终端可以响应系统 sudo 提示。仓库没有对应版本、安装失败或无支持的包管理器时停止并提示手动安装，不添加软件源，也不下载便携 JDK。`--check`、`--no-install` 和帮助命令不会安装软件，`clean` 不会自动安装 JDK。

PC JAR、Linux 完整包和桌面测试不要求 Android SDK。Android APK 和包含 Gecko 的 `browser-kernels` 目标仍需有效的 `local.properties` 中 `sdk.dir`、SDK Platform 37.1 和 Build Tools 36.0.0。Android Release 沿用 `build/key/` 原签名，不要重新生成密钥。

ZIP 可直接包含 `game-lib.jar`、非空 `assets/`，也可带 `Rusted Warfare/` 外层目录。Linux 完整包必须使用含 Linux x64 原生库的原版 ZIP；不会使用压缩包内的旧 JVM。

```bash
# 当前本机 build/build.sh 转发到仓库维护的 packaging/build.sh
./build/build.sh --check                       # 只检查依赖和 ZIP，不安装、不编译
./build/build.sh linux --channel official      # Linux x64 完整包
./build/build.sh linux --no-install            # 禁止自动安装，使用已有工具打包
./build/build.sh android-release --channel official
./build/build.sh android-debug
./build/build.sh desktop                       # 仅 Linux fat jar
./build/build.sh test
./build/build.sh browser-kernels
./build/build.sh clean                         # 保留 build 下 ZIP、脚本、密钥和产物

# 新检出可直接使用仓库入口；省略目标可打开交互菜单
./packaging/build.sh linux --game-archive '/path/to/Rusted-Warfare-linux.zip'
```

Linux 原版 ZIP 优先级：`--game-archive` → **`build/Rusted-Warfare-linux.zip`** → `RW_GAME_ARCHIVE` → `packaging/game-archive.local.txt`。同级 ZIP 优先于从其他机器复制来的旧环境/路径文件，显式参数始终优先。相对路径以仓库根目录为基准；同级 ZIP 不存在时，也可复制 `packaging/game-archive.local.txt.example` 为本机配置文件以指定自定义路径。整个 `build/` 被 Git 忽略，根 `clean` 会保留同级 ZIP。PowerShell 7 在 Linux 调用 `packaging/build.ps1` 时也转到同一 Bash 入口，支持 `-Check` / `-NoInstall`。

Linux 产物位于 `build/artifacts/linux-<渠道>/`：`RWJS/` 可直接运行，`RWJS-Linux-x64-<版本>.tar.gz` 和 `.sha256` 用于分发。解压到可写目录，运行 `./RWJS/RWJS.sh`；包内已包含 Java 21。启动脚本自动切换工作目录、设置原生库路径，并支持首次注入后的自动重启。运行需要 Linux x64 图形桌面、OpenGL/OpenAL 等系统依赖。Linux 包当前采用手动更新，客户端原有 EXE 自动更新流程仍针对 Windows。

Linux 的 `installer` 别名指向 Linux 完整包；Windows EXE 安装包仍在 Windows 构建。`desktop` 只收集当前版本 Linux fat jar，不附带运行资源或 Java。`test` 调用共享模块的 `desktopTest` 和 core-api 的 `test`。所有目标默认增量构建，失败时返回非零，不会自动清理或重试。打包收集成功后才替换对应渠道的旧产物。

脚本回归测试（使用临时假 Gradle/JDK 验证流程，不能代替客户端实际构建）：

```bash
python3 -m unittest discover -s packaging/tests -p 'test_*.py' -v
```

## Windows 构建

将 Windows x64 原版 ZIP 命名为 **`Rusted-Warfare-win.zip`**，放在 **`build/build.ps1` 同级目录**：

```text
TXJS/
  build/
    build.ps1
    Rusted-Warfare-win.zip
  packaging/
    build.ps1
    windows-environment.ps1
```

在项目根目录运行；也可以进入 `build/` 后运行 `./build.ps1` 并选择菜单 2：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\build.ps1 installer -Channel official
```

脚本自动检测完整的 **x64 JDK 21**（含 `javac`、`jlink`、`jpackage`、`jmods`）和 **Inno Setup 6.5+**，从环境变量、PATH、注册表及常见安装目录查找，并配置当前打包进程的 `JAVA_HOME`、`PATH`、`INNO_SETUP_COMPILER`。复制项目时带来的无效 Linux 路径会跳过，已安装的合适版本优先复用，无需重开终端或手改系统环境变量。

缺少工具时优先通过 WinGet 安装 `EclipseAdoptium.Temurin.21.JDK` / `JRSoftware.InnoSetup`（首次需要网络，Windows 可能弹出 UAC）。WinGet 返回“已安装、无可用升级”时重新检测实际工具，不直接当作安装失败。Inno Setup 版本读取真实编译引擎，兼容 `ISCC.exe` 文件版本为 `0.0.0.0`，并读取 32/64 位、当前用户/系统卸载登记中的自定义路径。

没有 WinGet，或 WinGet 执行后仍无可用工具时，从 [Eclipse Adoptium 官方发布](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9) / [Inno Setup 官方发布](https://github.com/jrsoftware/issrc/releases/tag/is-6_7_4)下载固定版本（JDK 21.0.13、Inno 6.7.4），先校验固定 SHA-256，再配置到 `%LOCALAPPDATA%\RWJS\BuildTools`。JDK 使用 ZIP，Inno 使用当前用户便携模式，不覆盖已有安装；再次打包会复用。下载失败、校验不符、运行检查不通过或用户取消安装时停止构建。PC JAR 不需要 Inno Setup；PC 打包不需要 Android SDK，Android 构建的 SDK 与签名配置仍按前述要求准备。

```powershell
# 只检查，不安装软件、不调用 Gradle；未指定目标时检查 installer
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\build.ps1 -Check
# 使用已安装工具，禁止自动安装
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\build.ps1 installer -NoInstall
```

`build/` 仍整目录被 Git 忽略，原版 ZIP、密钥和产物只留本地。根 Gradle `clean` 会保留 `build/` 根目录的 `.zip` / `.ZIP`、脚本、`key/` 和 `artifacts/`，不会清掉同级原版包。安装包始终自带 Java 运行时，用户无需安装 Java。

需要使用其他原版资源时，可以显式覆盖自动查找：

```powershell
# 可选：编译器安装在自定义目录时指定
$env:INNO_SETUP_COMPILER = 'C:\Tools\Inno Setup 6\ISCC.exe'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\build.ps1 installer -GameArchive 'D:\Games\Rusted-Warfare-win.zip'
```

Windows 资源优先级：`-GameArchive`（或底层 `-GameRoot`）→ **`build/Rusted-Warfare-win.zip`** → `RW_GAME_ARCHIVE` → 兼容的 `RW_GAME_ROOT` → `packaging/game-archive.local.txt` → 兼容的 `packaging/game-root.local.txt`。同级 ZIP 优先于其他开发机的旧环境/路径文件；显式参数始终优先。仍支持旧目录输入，已移除硬编码的 D 盘回退。不要将游戏资源、本机路径或生成安装包提交进 Git。Linux ZIP 不含 Windows DLL，不能直接制作 Windows 安装包。

环境配置逻辑的独立测试（测试使用临时目录和安装边界模拟，不安装软件）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/tests/WindowsEnvironment.Tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/tests/WindowsBootstrap.Tests.ps1
```

## 本机 debug 覆盖安装（不提交 game-root.local.txt）

开发时只替换 `app\` 下若干 jar，不走完整安装包：

```powershell
.\packaging\debug-desktop-install.ps1   # 编译、结束 RWJS 进程、覆盖 jar，不启动
.\packaging\debug-desktop-run.ps1       # 同上后再启动 RWJS.exe
.\packaging\debug-desktop.ps1           # 兼容旧习惯，等同 run；-NoLaunch 等同 install
```

共享逻辑在 `packaging/Debug-RwjsLocal.ps1`；默认游戏目录 `D:\RWJS`，可用 `-GameRoot` 覆盖。

仓库跟踪的发布脚本在构建后收集产物并生成 Gitee 分卷：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/build.ps1 installer
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/build.ps1 installer -Channel official
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/build.ps1 installer -GameArchive 'D:\Games\Rusted Warfare.zip'
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

Windows 安装包包含 `RWJS.exe`、`app/`（过滤非 Windows Skiko）、`runtime/`、图标、AGPL 许可证，以及原版 ZIP/目录的 `game-lib.jar`、根目录原生库、`steam_appid.txt`、`assets/`、`font/`、`libs/`、`res/`。Linux 包以 `RWJS.sh` 启动，包含相同白名单资源、Linux fat jar 和由 JDK 21 的 jlink 生成的运行时。

桌面构建会从仓库 `lib/android.jar` 生成 `app/rwjs-android-compat.jar`，补齐原版桌面核心引用的 `Build.VERSION` 等类型。兼容 jar 排除 JDK 命名空间及 `game-lib.jar` 已提供的类，保留引擎自身的 Android 桌面实现。原版 `libs/android.jar` 和 Android 游戏核心仍不进入安装包。

只包含 RWJS 入口，不附带原版 exe、启动脚本、原版 JVM、用户模组/地图、用户配置、缓存、存档、回放、日志、备份或注入生成库。安装时创建空 `mods/units` 和 `mods/maps`。Windows 资源暂存到 `build/tmp/game-payload/`，目录输入跳过符号链接/junction；ZIP 拒绝路径逃逸、链接、重复文件和歧义游戏目录。Linux 在独立临时产物目录中提取，打包失败保留已有产物，原版 ZIP 始终只读。

## 注册表、更新和卸载

全新且固定的 AppId：`{C9F7D3ED-E974-4C19-9291-9D5460608316}`。Inno Setup 自动登记 Windows 卸载项；发布后不能更换此 AppId。

64 位 `HKLM\SOFTWARE\RWJS` 登记 `InstallDir` 与 `InstalledVersion`。安装需要管理员权限；引擎仍向根目录写配置和资源，安装器为普通用户设置该目录的修改权限。不会读取、修改或迁移旧 RWPP 注册表、MSI 或旧安装目录。

```powershell
# 自动更新显示进度，锁定新 RWJS 注册表指向的目录
.\RWJS-Setup.exe /RWJS_UPDATE=1 /SILENT /SP- /NORESTART /LOG
# 正常静默安装可指定独立目录
.\RWJS-Setup.exe /VERYSILENT /SP- /NORESTART /DIR="D:\Games\RWJS"
```

客户端通过 Windows 原生 `ShellExecuteW` 的 `runas` 请求 UAC 授权；拒绝授权时留在全屏更新页，可重试。路径直接作为 Unicode 字符串交给系统，不通过命令解释器。接口行为参见[微软 ShellExecuteW 文档](https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-shellexecutew)。

缺少有效 RWJS 安装记录时，更新模式中止并提示正常安装。客户端只调用新参数。覆盖更新清理安装器管理的 `app/*.jar`，避免版本改名后依赖冲突；保留用户配置和模组。更换已安装的 RWJS 目录前须先卸载。

目标目录含 `game-lib.jar` 和 `assets/` 但不是 RWJS 安装时，视为原版游戏目录并拒绝安装：选择目录页拦截，选择目录页被跳过时由 `PrepareToInstall` 兜底。否则安装会覆盖原版文件，卸载时这些文件也会被删除。

卸载仅移除安装器登记的文件、快捷方式、注册表值及空目录，不通配删除游戏根目录；用户自行添加的模组、存档、回放和配置保留。包内附带的资源属于安装文件，会随卸载删除。

## 验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/tests/payload-test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/tests/archive-test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/inno/tests/installer-test.ps1
.\gradlew.bat :rwpp-core-api:test :rwpp-desktop:packageInnoDistribution
```

安装器测试使用同一生产脚本，以独立 AppId、HKCU、临时目录和测试快捷方式隔离真实安装，覆盖原版目录拦截、注册表、更新、旧 jar 清理和卸载数据保留。发布前还需在 Windows 实际验证中文向导、普通用户启动游戏和占用文件提示。

简体中文消息文件来自 [Inno Setup 官方源码](https://github.com/jrsoftware/issrc/blob/2f452efbbba794d8329592c386576361ad611e6c/Files/Languages/ChineseSimplified.isl)，保留其维护者信息，随仓库固定以避免编译器安装缺少翻译文件。参数与身份规则见[官方文档](https://jrsoftware.org/ishelp/topic_setupcmdline.htm)和 [AppId 文档](https://jrsoftware.org/ishelp/topic_setup_appid.htm)。
