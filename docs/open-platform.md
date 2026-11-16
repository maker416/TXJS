# 开放平台入口与房间关闭提示

多人页的“申请24小时列表位”打开独立的开放平台。默认地址为
`https://listup.xn--rhqr8xvr4ahqsgka.com`，上线前应将该域名通过 HTTPS 代理到开放平台服务。

构建时可使用 `-PopenPlatformUrl=https://你的平台域名` 覆盖默认入口，值写入
`rwpp-core-api` 的 `BuildConfig.OPEN_PLATFORM_URL`。运行时也可通过 JVM 系统属性
`rwjs.openPlatform.url` 或环境变量 `RWJS_OPEN_PLATFORM_URL` 覆盖；系统属性优先于环境变量，
运行时覆盖优先于构建参数。不新增玩家设置项，也不向网址附带账号 Token 或应用 Key。

入口必须使用 HTTPS；本机调试允许 `http://localhost`、`http://127.0.0.1` 和 `http://[::1]`
及其端口。网址不得包含用户名、密码、query 或 fragment。非法构建参数会使构建失败，
非法运行时覆盖会回落到构建时入口。

RWList 的 `GET /servers` 房间条目支持可选字符串 `closed_message`。客户端将其映射到
`RoomDescription.closedMessage`；字段缺失、null 或非字符串时按空字符串处理，兼容旧服务端。
当 `available="0"` 时，房间详情显示非空关闭原因和通用“暂不可加入”状态，并继续隐藏加入按钮。
`available="1"` 时不显示之前保留的关闭原因。

关闭原因以可选择的普通文本展示，不执行 HTML、Markdown 或网址。不可加入房间仍遵循原有的列表排序
和玩家筛选设置；手动输入游戏服务器地址的加入方式保持独立。因此关闭状态是列表入口的展示与加入控制，
实际游戏服的加入权限仍由游戏服决定。

针对性验证：

```powershell
.\gradlew.bat :rwpp-core-api:test --tests io.github.rwpp.net.RwListParserTest --tests io.github.rwpp.config.OpenPlatformConfigTest
.\gradlew.bat :rwpp-core:desktopTest --tests io.github.rwpp.ui.RoomClosureUiTest --tests io.github.rwpp.ui.RoomListCardUiTest
.\gradlew.bat :rwpp-core:compileDebugKotlinAndroid :rwpp-core:compileKotlinDesktop :rwpp-android:compileDebugKotlin :rwpp-desktop:compileKotlin
```

以上测试和编译不替代 Android 真机以及实际部署域名的端到端验收。
