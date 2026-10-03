# Android 内置浏览器

资源站使用随 APK 打包的稳定版 GeckoView `157.0.20260924084938`，不再调用系统 WebView。桌面端继续使用 JCEF。Mozilla Maven 仓库仅用于 `org.mozilla.geckoview` 依赖。

一个应用进程共用一个 `GeckoRuntime`；每次打开资源站新建 `GeckoSession`，关闭时释放 View/session、消息端口及待确认下载流。Gecko 的子进程通过 `MainApplication` 的进程检查跳过游戏核心和 Koin 初始化。View 使用 TextureView 后端，以参与 Compose 动画、裁剪和弹窗合成。

登录仍使用一次性 handoff，内置 WebExtension 等待引导页就绪后调用网页既有的 `rwForumClient`。原生端和扩展均检查完整 URL 和主文档来源；票据不放入 URL，也不向网页发送长期账号 token。旧 WebView Cookie 不迁移，首次打开重新执行客户端登录或游客流程。细节见 [forum-sso.md](forum-sso.md)。

模组下载直接复制 Gecko 返回的已认证响应流，不导出浏览器 Cookie。下载前仍需用户确认；取消、关闭资源站及协程取消均释放响应流；已知 Content-Length 时验证接收长度。

## 构建与验证

新版 GeckoView 及其传递依赖要求 Kotlin 2.4.20、compileSdk 37.1，因此同步升级 KSP/Gradle/AGP。JDK 21、Android SDK Platform 37.1 及 Build Tools 36.0.0 必须可用；minSdk 26 和 targetSdk 35 保持现有配置。AGP 9 通过 `android.builtInKotlin=false`、`android.newDsl=false` 保留当前 KMP `androidTarget`；后续升级 AGP 10 前需迁移至新 Android KMP 插件。

```powershell
node --test rwpp-core/src/androidMain/bridge-tests/forum-bridge.test.cjs
.\gradlew.bat :rwpp-core-api:test --console=plain
.\gradlew.bat :rwpp-core:testDebugUnitTest --tests '*ForumBrowserHandoffTest' --tests BundleParseTest :rwpp-core:compileKotlinDesktop :rwpp-android:assembleDebug --console=plain
```

扩展测试覆盖握手、单次提交、游客、错误 URL/票据、导航后拒绝和子框架拒绝；下载测试覆盖流复制、取消、截断及阻塞读取取消；共享 handoff 测试覆盖页面与控制器就绪时序。

设备验收需包括：华为游戏内横屏打开资源站、点击头像进入主页、帖子设置菜单、客户端登录/退出/切号、前进后退刷新、带登录态模组下载及取消、关闭并重开资源站。编译和单元测试不能替代这些设备操作。

GeckoView 提供 arm64-v8a、armeabi-v7a、x86_64 内核，通用 APK 会明显增大。发布到 Gitee 前必须核对其单文件 100MB 限制，当前 Android 更新器仍按单 APK 下载；不能直接复用桌面的 zip 分卷方案。
