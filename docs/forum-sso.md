# 客户端内打开论坛自动登录

在资源浏览器中打开论坛时，已登录的账号通过 UAS 一次性票据自动创建对应 Flarum 会话；未登录时先清理浏览器旧论坛会话，再以游客打开。Android 内置 GeckoView 和桌面 JCEF 共用此流程。普通外部浏览器不会自动获得客户端身份。

## 配置

设置页的论坛地址默认为 `https://zyz.xn--rhqr8xvr4ahqsgka.com/`。支持 `RWJS_FORUM_URL` 环境变量或 `rwjs.forum.url` JVM 属性覆盖，优先级高于保存的设置。

UAS 地址、AppKey 仍使用账号设置；也支持 `RWJS_ACCOUNT_API_URL` / `rwjs.account.apiUrl` 和 `RWJS_ACCOUNT_APP_KEY` / `rwjs.account.appKey`。AppKey 必须属于实际的 TXJS 应用，论坛集成 Secret 永远不放入客户端。

论坛和客户端需连接同一个 UAS 实例。UAS 管理者先升级服务并运行：

```powershell
# 替换为真实应用 code，不是 AppKey；在 UAS 项目目录运行。
go run ./cmd/forum-bridge --mode allow-sso --source-app-code TXJS_APP_CODE --app-code FORUM_APP_CODE
```

论坛升级统一账号扩展并执行 `php flarum migrate`。目标应用 code 从论坛 `/sso/client/config` 读取，无需客户端硬编码。UAS 账号服务兼容现有 HTTP 与 HTTPS 地址，默认 HTTP 地址及旧客户端无需迁移。票据申请由原生 OkHttp 执行，不受网页混合内容限制。论坛页面和准备/撤销接口仍要求 HTTPS；论坛 HTTP 仅允许 `localhost`、`127.0.0.1` 和 `::1` 本机联调。

## 会话处理

原生客户端请求票据，再通过独立 CookieJar、有 CSRF 校验的论坛接口准备会话。论坛只返回一次性 handoff 和撤销专用 key；长效 UAS JWT、论坛应用 JWT、Flarum access token 均不会注入网页或出现在 URL 中。两平台只在准确匹配配置引导页的主文档中执行 handoff。

退出账号或切换账号会立刻使旧的准备结果失效，并在独立后台任务中撤销对应论坛会话，不依赖资源浏览器仍然打开。撤销失败时 key 保留在账号配置中，下次打开论坛前重试；同站旧会话撤销失败时显示登录失败，避免继续使用旧身份。选择“以游客浏览”也通过论坛 POST 清理浏览器会话。

登录失败提供重试与游客入口。浏览器历史、刷新、资源下载沿用既有功能；论坛自动登录过程中导航受到限制，避免向其它页面注入 handoff。

错误提示显示失败步骤（旧会话撤销、读取论坛配置、UAS 票据申请、论坛票据兑换或本机保存）及 HTTP 状态，不显示接口响应正文或异常原文。UAS 票据申请返回 401/403 时会提示检查客户端登录与应用互通授权；这两类原因需要结合实际 UAS 配置判断，不能仅凭状态认定未授权。

新版 UAS 也可直接通过 `.env` 配置 `FORUM_SSO_ALLOWED_PAIRS=txjs:test_1` 后重启完成授权，来源 `txjs` 必须替换为实际客户端应用 Code。该变量设置后在启动时替换完整授权列表，显式留空并重启会清空授权；不设置时沿用命令管理的数据库授权。

原生登录错误独立于网页导航错误保存，不会被 Chromium/Gecko 的初始化或加载开始事件清掉。公网 HTTP 论坛地址会显示明确的 HTTPS 配置提示；HTTP UAS 地址正常申请票据。引导页已经使用一次后，重试或游客入口会重新加载引导页，重新获取 CSRF；浏览器控制器尚未就绪时保留待发送 handoff。

Android 的 handoff 由随 APK 打包的 WebExtension 传递：原生端验证当前 session、主文档、完整引导页 URL 和 content-script 来源；扩展再次验证完整 URL、票据格式和单次提交。原生端等待页面加载完成及扩展就绪后发送，扩展仅调用引导页既有的 `rwForumClient`，不提供任意 JavaScript 执行接口。`.rwmod` 下载直接接收 Gecko 已认证的响应流，保留确认、进度、取消及安装流程，不将 Gecko 的 Cookie 导出给其它 HTTP 客户端。

启用 HTTPS 后仍停在引导页时，检查论坛 `app/config.php` 的 `url` 已改为 HTTPS，并确认 UAS 新接口及应用授权已配置。论坛扩展新版使用同源表单及重定向，避免旧 HTTP 配置阻断登录；论坛其它页面的资源 URL 仍需要正确配置。UAS 票据请求不跟随跳转，不接受携带账号密码、查询或 fragment 的服务地址。HTTP 通信仍为明文；本次兼容保留已有部署方式。

## 论坛模组上传

点击论坛编辑器的文件上传按钮，首次选择“浏览文件”或“从模组管理器选择”。选择保存在客户端设置中，后续直接打开对应选择器；可在“设置 → 客户端 → 资源页面默认模组上传来源”修改或重置为首次询问。模组选择器也提供“改用浏览文件”。

模组选择器列出符合网页文件类型要求的已安装模组（包括未启用的模组），支持搜索，并按网页的单选/多选要求返回文件。文件夹模组在后台流式打包成 `.rwmod`（网页仅接受 `.zip` 时使用 `.zip`）；上传读取私有临时快照，关闭资源浏览器后清理。此操作不启用模组、不重载游戏。图片/视频等专用文件输入直接打开文件选择器，不修改模组上传偏好。

从模组管理器上传时，临时上传文件使用模组显示标题命名，例如磁盘文件 `v033.rwmod`、模组标题“破境边缘 V0.33”上传为 `破境边缘 V0.33.rwmod`；选择器第二行显示实际上传名称。中文、空格、括号和版本号保留；不支持的文件名字符替换为下划线，控制字符移除，过长标题按完整 Unicode 码点截短，标题为空或不可用时回退原文件名。每个模组独立保存快照，同名模组多选不会覆盖；磁盘模组原名和内容不变。“浏览文件”继续使用所选文件的原名。网页仅接受 ZIP 时使用相同标题加 `.zip` 后缀。

Android 使用 WebView `onShowFileChooser`、系统文件选择器和缓存目录的 FileProvider；桌面使用 Chromium `CefDialogHandler` 和文件选择回调。上传仍由论坛网页处理，保留浏览器登录 Cookie，无需新增论坛接口。取消选择、页面导航和关闭浏览器会结束未完成的选择回调，迟到的选择结果自动忽略。

## 验证

```powershell
.\gradlew.bat :rwpp-core-api:test --console=plain
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests ForumSsoSessionTest --tests '*ForumBrowserHandoffTest' --tests '*AccountSession*' --tests BundleParseTest :rwpp-core:compileKotlinDesktop --console=plain
.\gradlew.bat :rwpp-core:desktopTest --tests '*BrowserModUploadUiTest' --console=plain
```

真实桌面 Chromium 上传回归需可用的桌面会话，单独运行（避免其它 native 测试提前销毁全局 Chromium runtime）：

```powershell
$env:RWJS_BROWSER_NATIVE_TEST = '1'
$env:JAVA_TOOL_OPTIONS = '--add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED'
.\gradlew.bat :rwpp-core:desktopTest --tests '*BrowserModNativeUploadTest' --console=plain
```

上传测试覆盖首次偏好保存与重启恢复、图片输入直通文件选择器、目录打包内容、临时文件清理、取消与迟到回调，以及真实 Chromium 文件输入取消后再次选择、multipart 上传文件名及字节完整性。Android 还需真机验收系统选择器返回、SAF 缓存快照及 Gecko 文件上传。

测试覆盖真实 HTTP 请求形状、PKCE、独立 CSRF Cookie、跳转阻断、URL 安全校验、TOML 配置读写、网页注入边界、单次注入、退出及迟到响应撤销、离线撤销队列与真实双语 bundle 解析。服务端另有真实 MySQL 和 Flarum 中间件集成测试。

上线验收：同一 UID 在客户端与论坛对应；已登录打开资源页无需输入密码；退出客户端后论坛旧会话无效；切号只显示新账号；客户端未登录以游客进入；票据重放失败。需在升级后的真实站点上完成 Android/桌面端验收，单元测试不代替设备验收。
