# 客户端内打开论坛自动登录

在资源浏览器中打开论坛时，已登录的账号通过 UAS 一次性票据自动创建对应 Flarum 会话；未登录时先清理浏览器旧论坛会话，再以游客打开。Android WebView 和桌面 JCEF 共用此流程。普通外部浏览器不会自动获得客户端身份。

## 配置

设置页的论坛地址默认为 `https://zyz.xn--rhqr8xvr4ahqsgka.com/`。支持 `RWJS_FORUM_URL` 环境变量或 `rwjs.forum.url` JVM 属性覆盖，优先级高于保存的设置。

UAS 地址、AppKey 仍使用账号设置；也支持 `RWJS_ACCOUNT_API_URL` / `rwjs.account.apiUrl` 和 `RWJS_ACCOUNT_APP_KEY` / `rwjs.account.appKey`。AppKey 必须属于实际的 TXJS 应用，论坛集成 Secret 永远不放入客户端。

论坛和客户端需连接同一个 UAS 实例。UAS 管理者先升级服务并运行：

```powershell
# 替换为真实应用 code，不是 AppKey；在 UAS 项目目录运行。
go run ./cmd/forum-bridge --mode allow-sso --source-app-code TXJS_APP_CODE --app-code FORUM_APP_CODE
```

论坛升级统一账号扩展并执行 `php flarum migrate`。目标应用 code 从论坛 `/sso/client/config` 读取，无需客户端硬编码。当前仓库原有 UAS 默认值是公网 HTTP 地址，部署前必须将账号地址改为可用的 HTTPS 地址；免密接口拒绝公网 HTTP。仅 `localhost`、`127.0.0.1` 和 `::1` 允许 HTTP 本机联调。

## 会话处理

原生客户端请求票据，再通过独立 CookieJar、有 CSRF 校验的论坛接口准备会话。论坛只返回一次性 handoff 和撤销专用 key；长效 UAS JWT、论坛应用 JWT、Flarum access token 均不会注入网页或出现在 URL 中。两平台只在准确匹配配置引导页的主文档中执行 handoff。

退出账号或切换账号会立刻使旧的准备结果失效，并在独立后台任务中撤销对应论坛会话，不依赖资源浏览器仍然打开。撤销失败时 key 保留在账号配置中，下次打开论坛前重试；同站旧会话撤销失败时显示登录失败，避免继续使用旧身份。选择“以游客浏览”也通过论坛 POST 清理浏览器会话。

登录失败提供重试与游客入口。浏览器历史、刷新、资源下载沿用既有功能；论坛自动登录过程中导航受到限制，避免向其它页面注入 handoff。

## 验证

```powershell
.\gradlew.bat :rwpp-core-api:test --console=plain
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests ForumSsoSessionTest --tests '*ForumBrowserHandoffTest' --tests '*AccountSession*' --tests BundleParseTest :rwpp-core:compileKotlinDesktop --console=plain
```

测试覆盖真实 HTTP 请求形状、PKCE、独立 CSRF Cookie、跳转阻断、URL 安全校验、TOML 配置读写、网页注入边界、单次注入、退出及迟到响应撤销、离线撤销队列与真实双语 bundle 解析。服务端另有真实 MySQL 和 Flarum 中间件集成测试。

上线验收：同一 UID 在客户端与论坛对应；已登录打开资源页无需输入密码；退出客户端后论坛旧会话无效；切号只显示新账号；客户端未登录以游客进入；票据重放失败。需在升级后的真实站点上完成 Android/桌面端验收，单元测试不代替设备验收。
