# 浏览器按需组件验证报告

本文件记录最初的固定 tag 方案，已被后续“独立组件版本、只查询最新 Release”的方案替代。当前发布规则见 [browser-kernel-distribution.md](browser-kernel-distribution.md)，最新验证结果见 [browser-kernel-latest-validation.md](browser-kernel-latest-validation.md)。本文件中的旧来源 tag 和旧产物目录只作历史验证记录。

日期：2026-11-01。基线提交：`64c6fbef`，在现有工作区上修改，保留原有客户端版本 `1.20.55`。GeckoView 和 JCEF 原生版本均未升级。本轮只生成本地产物，没有上传附件或发布 Gitee Release。

## 实现状态

- Android 主包保留 Gecko Java API、资源、服务和论坛扩展；原生库及 `omni.ja` 由同一生产下载器校验安装。Java 加载层适配绝对路径、ELF 依赖及外置资源，不修改官方原生二进制。保留多进程和 TextureView。
- Windows 主包保留 JCEF Java API 和 `build_meta.json`，原生目录由下载管理器准备，显式跳过 JCEF 自带安装器。
- 客户端固定组件身份、来源 tag 和清单 SHA-256；依次校验清单、分卷、完整 ZIP 和文件。支持取消、持久断点、Range 降级、失败重试、临时安装及离线复用。
- 论坛与赛事共用准备界面，用户同意后下载；内核准备完成后才申请一次性登录票据。已加载版本不热替换。中英文文本均已补齐。
- `packaging/build.ps1 browser-kernels` 生成 Android 三 ABI 和当前桌面平台附件；本机生成的是 Windows amd64。

## 已执行的验证

| 范围 | 结果 |
| --- | --- |
| 公共管理器 `BrowserKernelTest` | 12 项通过：版本/架构不符、缺卷、断流、Range/忽略 Range/分段 Range、取消、损坏哈希、目录损坏、越界 ZIP、安装中断、并发合并、离线复用及旧目录导入 |
| 客户端更新解析 `UpdateDownloadPlanTest` | 13 项通过，包含同一 Release 混有客户端和 `.kernel.001` 附件的回归 |
| 桌面准备界面 `BrowserKernelGateTest` | 2 项通过，覆盖同意前零请求、失败重试、离线复用、准备完成及账户切换后回调 |
| Windows 真实 JCEF 窗口 | 3 项通过，覆盖认证模组/资源下载和原生文件选择上传；组件通过本地 HTTP 使用本轮导出的真实包安装，后续离线复用 |
| Android/共享单元测试 | `BundleParseTest` 21 项、`ForumBrowserHandoffTest` 6 项通过 |
| Redmi 独立外置加载宿主 | 下载后的真实 Gecko、GPU/网页/辅助子进程、票据与游客桥接、认证下载、私有文件上传均通过；覆盖安装及进程重启后复用组件 |
| Redmi 共享生产浏览器代码 | 同包名自仪器测试 6 项通过，包含前后台恢复、会话关闭重开、票据/游客、认证模组/缩略图下载和文件上传；最后两轮分别耗时 6.316s、6.422s |
| 客户端构建 | Android Debug/Release、桌面 fat JAR、Windows Inno 安装包均通过；正式构建未传诊断测试参数 |

Redmi 为 `Redmi K30 Pro`，Android 10、arm64。测试使用独立包 `io.github.rwjs.browserkerneltest`，未覆盖正式客户端，未使用真实账号票据，也未修改线上论坛数据。共享浏览器测试直接运行生产 `EmbeddedBrowser` 实现；下载服务和网页端使用本地 fixture。

一次覆盖安装后立即运行仪器测试时，系统正在执行 DEX 优化，同时出现 Gecko 网页子进程 attach 超时，导致会话重开用例未收到票据。保留了该失败日志；同一 APK、同一组件目录在冷启动后的两次完整 6 项测试均通过。没有以关闭多进程或增加加载回退来绕过问题，仍需在更多设备上观察此安装后启动时序。

Windows 真实窗口验证在隔离的浏览器用户目录运行。取消、Range 和旧目录迁移由公共管理器及界面测试覆盖；未把它们描述成完整安装后客户端里的人工操作验收。

## 减包结果与产物

| 客户端 | 现有本地内置版本 | 按需版本 | 减少 |
| --- | --- | --- | --- |
| Android Release APK | 272.67 MiB | 43.59 MiB | 约 84% |
| Windows 安装包 | 310.42 MiB | 155.98 MiB | 约 50% |
| Windows fat JAR | 246.85 MiB（本地 1.20.38 参考包） | 92.65 MiB | 约 62%，参考版本不同 |

Android Debug 约 50.16 MiB。客户端 APK 中没有 Gecko 原生库或 `omni.ja`；fat JAR 中没有 JCEF 原生归档。客户端携带的需求哈希与导出清单、分卷及解压文件逐项复核，结果见交付目录的 `asset-verification.json`。

- `build/artifacts/browser-kernel-on-demand/`：本轮客户端、独立仪器测试宿主、更新分卷、SHA-256 和日志/报告。旧的 `*-official/` 基线产物保持原样。
- `build/artifacts/browser-kernels/`：4 份组件清单、5 个组件分卷及上传说明。Android 单个 ABI 下载约 82–90 MiB；Windows 下载约 155 MiB。单卷不超过 95 MiB。
- 复现命令和来源 tag 维护规则见 [browser-kernel-distribution.md](browser-kernel-distribution.md)。

## 尚未完成的验收

1. **真实 Gitee 组件下载**：附件未上传，因此尚未验证组件附件的匿名下载、重定向及 Range。指定 `v1.20.55` 的匿名 Release API 已验证可访问，但不能代替附件联调。上传后须使用真实通道复核下载、续传和 SHA-256。
2. **Android 17、armeabi-v7a、x86_64 运行**：本轮结束时只有 Redmi 在线；其他 ABI 已生成组件，缺少对应设备运行证据。macOS/Linux 的平台选择逻辑保留，本轮未运行或打包验收。
3. **完整正式客户端现场验收**：双端生产产物已构建、生产浏览器实现已测试；未安装本轮正式客户端去操作真实账号、游戏内横竖屏和公开论坛。需在组件上传后完成该轮联调，尤其观察安装后首次启动和厂商后台策略。

默认组件来源固定为 `v1.20.55`，与本轮保留的应用版本相互独立。正式客户端发行时需按常规提升应用版本并使用新发行版；不要将新的客户端分卷覆盖到包含旧三卷/四卷的同一发行版。组件本身可以追加至其固定来源 tag，并被后续客户端复用。
