# 浏览器组件分发

浏览器组件来源为 `maker416/TXJS` 的最新 Gitee Release，运行时只查询 `releases/latest`，不依赖历史 tag。每次进入论坛或赛事页先检查已验证的本地组件，再检查最新发行版；存在兼容的新组件时显示当前版本、目标版本和下载量，用户选择下载或继续使用当前版本。组件可用后才创建原生浏览器并申请论坛 handoff。

## 发布

```powershell
powershell -NoProfile -File packaging/build.ps1 browser-kernels
powershell -NoProfile -File packaging/build.ps1 android-release -Channel official
powershell -NoProfile -File packaging/build.ps1 installer -Channel official
# 可选：为本次组件构建覆盖独立版本；应同时更新 properties 供后续构建使用。
powershell -NoProfile -File packaging/build.ps1 browser-kernels -GeckoComponentVersion 1.1.0 -JcefComponentVersion 1.0.0
```

组件附件收集到 `build/artifacts/browser-kernels/`，上传其中的 `RWJS-Browser-*.json`、`*.kernel.001/.002/...`。不要上传中间 AAR、输入 classes.jar。脚本不上传附件，也不创建或修改发行版。

`packaging/browser-kernels.properties` 分别维护 `gecko.componentVersion` 和 `jcef.componentVersion`，初始均为 `1.0.0`。它们独立于客户端版本和完整上游引擎版本。组件未变化时原样复用文件，不提升组件版本、不重新打包；组件内容变化时提升对应组件版本。同一个版本不可替换成不同内容。

例如 Android arm64 文件名为 `RWJS-Browser-gecko-android-arm64-v8a-v1.0.0.json` 和 `RWJS-Browser-gecko-android-arm64-v8a-v1.0.0.kernel.001`。客户端从清单文件名解析数字版本，按 `major.minor.patch` 比较；所有分卷必须使用相同版本前缀、从 001 连续编号。文件名版本与清单不一致时拒绝下载。

每次发布新 Release 时，将仍需支持的组件清单和全部分卷原样带到最新发行版。仓库可以只保留这一个 Release；但如果只保留新客户端所需的组件，旧客户端没有可复用的本地组件时将需要先升级客户端。历史上固定 tag 的测试产物不支持删除来源 tag 后转查最新发行版，本轮新版客户端才支持此机制。

`KernelReleaseTag` 参数已停用，兼容旧命令时只输出说明，不再决定下载来源。客户端正式发布仍应提升应用版本，并避免混留旧安装包的多余分卷。本次保留工作区原有应用版本，不自动发布。

Gitee 单附件限制按 100M 处理，分卷上限为 95 MiB。附件内容是普通 ZIP 的按字节分卷，后缀刻意使用 `.kernel.001`，避免已发布的旧更新器把它当成客户端安装包。新旧客户端更新资产仍沿用原命名。

## 信任与安装

按用户确认的信任方式，主包保留兼容基线及其清单哈希，远端新版本则信任我们最新 Gitee Release 的清单，不新增签名密钥。清单中的每卷、完整 ZIP 和解压文件 SHA-256 仍逐项校验。此方案检查传输及安装内容的一致性，不能抵御 Release 清单和附件被一起替换；它取代了此前所有远端清单必须命中主包固定哈希的方案。

客户端只选择与主包 Java API 对应的完整引擎版本、适配格式、平台、架构一致且不低于兼容基线的组件。遇到不兼容的新内核会提示更新客户端。最新发行版低于本地版本时不降级；检查失败或离线时继续使用已验证的兼容本地组件。检查与下载使用同一份发行版快照，不在下载中途混用新发行版的分卷。

下载和安装在 IO 线程执行。取消保留未完成分卷；Range 被服务器忽略时从该卷开头重新下载。未验证的内容只留在临时目录，完整校验后才提交为可用目录。浏览器进程只绑定一个已验证版本：尚未加载内核时可直接启用新组件；已加载旧内核时新包只安装到独立目录，并提示重启客户端，当前进程可以继续使用旧版本。

Android 组件保存在 `noBackupFilesDir/browser/kernels/<组件ID>/`；桌面保存在 `~/.rwjs/browser/kernels/<组件ID>/`。它们不位于系统可清理的缓存目录，也不与浏览器登录态/profile 混放。桌面旧的 `runtime-<releaseTag>` 只有在文件清单和哈希完全匹配时才可导入。安装包更新不清理这些持久组件。

## Android 加载适配

固定 GeckoView `157.0.20260924084938`，构建期保留官方 Java API、Android 资源和服务声明，将原生库与 `omni.ja` 分离。Java 加载层适配库/资源路径、绝对路径加载及 ELF 依赖预加载；辅助服务在加载前初始化 Context。升级上游时方法结构检查不通过即停止构建，官方原生二进制不修改。

外置资源容器保留 `assets/omni.ja` 布局；`resource://android/assets/rwpp-forum-bridge/` 仍指向主包中的论坛扩展。主进程在首次创建内核前原子写入同一兼容系列的 `.active-*` 指针；Java 路径解析层直接通过 Context、进程 ABI 和指针定位组件，并在进程内固定该路径，不依赖 Koin 或游戏初始化。子进程读取同一指针。官方原生二进制不修改，内容、网络和媒体子进程继续使用原有机制。

客户端默认启用外置内核。`-PexternalBrowserKernel=false` 仅供对照构建或发布供其他应用使用的 Android library AAR；JitPack 的 library 工作流自动沿用官方依赖，避免 AGP 禁止 local AAR 嵌入的限制。正式客户端打包不传此关闭参数。

独立测试宿主为 `rwpp-browser-kernel-test`，包名 `io.github.rwjs.browserkerneltest`。本地 HTTP fixture：

```powershell
.\gradlew.bat :rwpp-browser-kernel-test:assembleDebug
python packaging/tests/browser-kernel-fixture.py --root build/browser-kernels/android
adb -s 2993cd77 reverse tcp:8765 tcp:8765
adb -s 2993cd77 install -r rwpp-browser-kernel-test/build/outputs/apk/debug/rwpp-browser-kernel-test-debug.apk
adb -s 2993cd77 shell am start -n io.github.rwjs.browserkerneltest/.MainActivity
```

fixture 仅供独立调试宿主；生产组件源不接受这个 HTTP 覆盖入口。正式 Gitee 附件上传后，仍需单独验证匿名下载、重定向及 Range，不能用本地服务器测试结果替代。

独立宿主默认访问 `http://127.0.0.1:8765`，会自动依次验证票据桥接、认证下载、游客桥接及文件上传。`KernelTest` 日志应出现 `DOWNLOAD true`、两次 `BRIDGE` 和 `UPLOAD PROMPT`；fixture 应记录票据/`null`、`UPLOAD_CONTENT_VERIFIED True`、`UPLOAD_COOKIE_VERIFIED True`。覆盖安装后不应再次请求组件附件。

共享浏览器仪器测试可在同一独立宿主中运行，复用其包名、签名和私有组件目录：

```powershell
.\gradlew.bat :rwpp-browser-kernel-test:assembleDebug -PbrowserKernelIntegrationHost=true
adb -s 2993cd77 install -r rwpp-browser-kernel-test/build/outputs/apk/debug/rwpp-browser-kernel-test-debug.apk
adb -s 2993cd77 shell am instrument -w -e class io.github.rwpp.platform.GeckoBrowserIntegrationTest io.github.rwjs.browserkerneltest/androidx.test.runner.AndroidJUnitRunner
```

该参数只用于诊断宿主，正式客户端构建不得传入；不传参数时生产模块不包含这些测试类。也可独立安装原有 `rwpp-core` 仪器测试 APK。不要通过未限定设备的命令同时安装到所有连接设备。
