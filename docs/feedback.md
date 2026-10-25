# 按天日志与玩家反馈

两端运行日志统一由 `rwpp-core-api/diagnostics/RuntimeLogs.kt` 管理。`DailyLogOutputStream` 按设备本地日期追加 `rwjs-yyyy-MM-dd.log`，保留当天及前六天。重复启动不会覆盖日志；写入和反馈快照时检查跨日并清理过期日志。不足七天时上传现有日志，不制造空白历史文件。

- Android：应用内部 `filesDir/logs`，不依赖管理文件权限；接入 Logback、stdout/stderr、未捕获异常及引擎主要日志入口。旧公共 `rustedWarfare/rwpp-log.txt` 可读时迁入其修改日期对应的日志。
- Desktop：游戏运行目录 `logs`，不可写时使用 `%LOCALAPPDATA%/RWJS/logs`；仍在 SLF4J 初始化前接管 stdout/stderr。旧版每次运行的 `rwjs-*.log` 按最后修改日期迁入对应日文件。
- 两端启动记录版本/系统和启动边界。原生 JVM `hs_err_pid*.log` 不属于日常日志压缩包，目前仍独立保存在桌面日志目录。

“反馈与说明 → 提交问题反馈”打开反馈页。玩家填写标题、描述与可选联系方式，明确勾选同意后才提交七天日志、应用版本和系统信息。失败保留表单供重试；成功显示反馈编号。同一份表单的 UUID 在重试时保留，服务端据此返回已有反馈，避免网络响应丢失后产生重复记录。修改内容会生成新 UUID。

打包在 IO 线程流式完成：短暂锁定日志输出捕获文件长度，压缩阶段释放锁，只读取捕获时已有的内容，不阻塞游戏线程。压缩包上传后或失败时删除，进程被强杀后遗留的包会在后续启动清理。原始日志最多 128 MiB、压缩包最多 64 MiB；超限明确提示，不静默丢弃历史内容。

默认服务地址为 `https://fk.xn--rhqr8xvr4ahqsgka.com`。覆盖优先级：系统属性 `rwjs.feedback.apiUrl` → 环境变量 `RWJS_FEEDBACK_API_URL` → `FeedbackPreferences.apiUrl` → 构建参数 `-PfeedbackApiUrl=...`。Android 本地联调可用 `adb reverse tcp:11556 tcp:11556`，通过配置文件把 `apiUrl` 设为 `http://127.0.0.1:11556`；正式包继续使用 HTTPS 默认域名。

配套服务端位于 `D:\workspace\RW\fakuiserver`（独立项目）。其 README 包含端点协议、管理员页面、存储、systemd 和 nginx 部署说明。管理员密钥只保存在服务端，客户端不需要账号登录或管理员凭据。

回归：`:rwpp-core-api:test` 覆盖同日重启、跨午夜切分、七天边界、快照期间继续写入、超限及上传契约；`:rwpp-desktop:test --tests '*DesktopRuntimeLogTest'` 验证控制台和日志同时接收 UTF-8 异常；两端 Kotlin 编译与反馈页窄屏检查；服务端 `go test ./...` 与 `go vet ./...`。上线前还需在公网 HTTPS 域名和 Android 真机上验收一次实际反馈提交。
