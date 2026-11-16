# 按天日志与玩家反馈

两端运行日志统一由 `rwpp-core-api/diagnostics/RuntimeLogs.kt` 管理。`DailyLogOutputStream` 按设备本地日期追加 `rwjs-yyyy-MM-dd.log`，保留当天及前六天。重复启动不会覆盖日志；写入和反馈快照时检查跨日并清理过期日志。不足七天时上传现有日志，不制造空白历史文件。

- Android：应用内部 `filesDir/logs`，不依赖管理文件权限；接入 Logback、stdout/stderr、未捕获异常及引擎主要日志入口。旧公共 `rustedWarfare/rwpp-log.txt` 可读时迁入其修改日期对应的日志。
- Desktop：游戏运行目录 `logs`，不可写时使用 `%LOCALAPPDATA%/RWJS/logs`；仍在 SLF4J 初始化前接管 stdout/stderr。旧版每次运行的 `rwjs-*.log` 按最后修改日期迁入对应日文件。
- 两端启动记录版本/系统和启动边界。原生 JVM `hs_err_pid*.log` 不属于日常日志压缩包，目前仍独立保存在桌面日志目录。

“反馈与说明 → 提交问题反馈”打开独立反馈界面，包含“提交反馈”和“反馈记录”。玩家填写标题、描述与可选联系方式，明确勾选同意后才提交七天日志、应用版本和系统信息。失败保留表单供重试；成功显示反馈编号。同一份表单的 UUID 在重试时保留，服务端据此返回已有反馈，避免网络响应丢失后产生重复记录。修改内容、切换账号或匿名/关联模式会生成新 UUID。

未登录也可提交；客户端在第一次发送前将任务 UUID 持久化，保存失败则不上传。只保存服务地址、身份分组和编号，不保存反馈正文、联系方式、日志或 Token。匿名记录在“反馈记录 → 本机匿名记录”中每页查询最多20条，显示处理状态、接收/更新时间及开发者公开回复；未确认接收的编号继续保留，可使用原表单重试。清除本机配置后匿名历史无法找回；匿名记录不会在随后登录时自动归属账号。

反馈页可打开现有账号中心登录。已登录时默认关联当前账号，也可取消勾选匿名提交。账号上传和历史查询使用 `X-App-Key` 与 UAS Bearer，由服务端核验归属，客户端不能自行指定账号 ID。账号历史每页50条，跨设备可查看本账号反馈标题、描述、处理状态和公开回复；登录失效明确提示，不会自动降级匿名提交。切号或登出会取消在途请求、清空原账号页面状态，并作废迟到响应。

匿名公开状态只返回 `id/status/received_at/updated_at/public_reply`，不返回反馈描述、联系方式、内部处理备注、日志或账号。开发者公开回复与管理员内部备注分开。查询限流或网络失败保留已显示状态，稍后可手动刷新。

打包在 IO 线程流式完成：短暂锁定日志输出捕获文件长度，压缩阶段释放锁，只读取捕获时已有的内容，不阻塞游戏线程。压缩包上传后或失败时删除，进程被强杀后遗留的包会在后续启动清理。原始日志最多 128 MiB、压缩包最多 64 MiB；超限明确提示，不静默丢弃历史内容。

默认服务地址为 `https://fk.xn--rhqr8xvr4ahqsgka.com`（标准443端口），部署方须配置域名证书和反向代理。覆盖优先级：系统属性 `rwjs.feedback.apiUrl` → 环境变量 `RWJS_FEEDBACK_API_URL` → `FeedbackPreferences.apiUrl` → 构建参数 `-PfeedbackApiUrl=...`。旧 HTTP 覆盖地址仍可匿名提交，发送账号凭据必须为 HTTPS；本机回环 HTTP 仅用于直接联调，并且绕过外部代理。所有反馈请求不跟随 HTTP 重定向。Android 本地联调可用 `adb reverse tcp:11556 tcp:11556`，通过配置文件把 `apiUrl` 设为 `http://127.0.0.1:11556`。

配套服务端为 `D:\workspace\RW\RWJS-all` 中的 `modules/fakuiserver`。玩家客户端不使用管理员密钥；管理人员通过平台 UAS 后台维护状态、内部备注和公开回复。原独立项目保留，账号历史等新增接口需要部署升级后的服务端。

回归：`:rwpp-core-api:test` 覆盖同日重启、跨午夜切分、七天边界、快照期间继续写入、超限及上传契约；`:rwpp-desktop:test --tests '*DesktopRuntimeLogTest'` 验证控制台和日志同时接收 UTF-8 异常；两端 Kotlin 编译与反馈页窄屏检查；服务端 `go test ./...` 与 `go vet ./...`。上线前还需在公网服务地址和 Android 真机上验收一次实际反馈提交。

本次本机验证：JDK21 下 FeedbackClientTest / FeedbackHistoryApiTest / FeedbackHistoryStoreTest 合计11项通过；FeedbackUiTest 四项通过（窄屏失败重试同UUID、匿名状态与公开回复及429保留已知状态、退出后清空账号历史、迟到历史响应不回写）。Android compileDebugKotlin 与 Desktop compileKotlin 通过。历史页截图在 rwpp-core/build/reports/feedback-ui/anonymous-history.png 和 account-history.png，已查看。未打包、安装或向线上发送反馈；公网HTTPS证书/代理和Android真机需部署后联调。
