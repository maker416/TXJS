# Android 覆盖安装与产物重建

覆盖安装 APK 保留用户设置、账号登录、模组启用记录，以及模组、地图、存档、回放、扩展和主题原包。默认上传来源也是用户设置，升级不重置。

启动时通过已安装 APK 的 versionCode、lastUpdateTime、文件大小和修改时间识别新安装；不依赖应用内下载入口，同版本重装、手动安装和渠道升级均会触发维护。旧版没有安装标记时，也执行一次迁移。

- 主进程加载游戏类之前，删除 `files/generated_lib/` 和 `app_dexfiles/` 中的游戏库、注入配置副本及扩展 DEX，重新应用新 APK 的注入配置。清理成功后记录安装标记，因此注入完成后的自动重启不会再次删除新产物。升级时重置连续重建计数。
- 获得文件权限后、资源系统和游戏引擎初始化之前，删除公共 `rustedWarfare/resource_generated/` 及其同级 `_tmp` 目录。资源系统使用新 APK 的基础资源，再叠加原来启用的扩展和主题。公共资源有独立安装标记；无权限或清理失败不会提前标记完成。
- 首次打开新版内置浏览器时，通过 Gecko API 清理缓存，再从 APK 强制安装内置论坛扩展，避免 `ensureBuiltIn` 复用相同 manifest version 的旧副本。保留 Cookie、DOM storage、网站权限和登录会话；后续打开复用准备结果。
- 下载更新失败、取消，或无法启动安装器时，立即删除临时 APK。交给系统安装器的 APK 暂时保留，因为安装器异步读取文件；升级完成后的启动清理全部旧更新 APK，普通启动只清理超过 24 小时的更新 APK。
- 启动清理遗留的浏览器上传快照及临时游戏 JAR；注入构建的源 JAR 在 `finally` 中删除。清理仅匹配程序专用目录/文件名，不清空用户数据或浏览器 profile，不跟随符号链接。

## 验证

```powershell
.\gradlew.bat :rwpp-core-api:test --console=plain
.\gradlew.bat :rwpp-android:assembleDebug :rwpp-core:testDebugUnitTest --tests '*ForumBrowserHandoffTest' --console=plain
```

`InstallArtifactMaintenanceTest` 覆盖旧版首次迁移、用户数据保留、公共资源延后处理、同一安装重启、下一次安装重新失效、失败重试，以及最近的安装包保护和过期清理。

设备验收：使用同一签名的旧 APK 导入模组/地图/主题，保存设置并登录资源站；覆盖安装新版，确认首次启动重建注入，自动重启后不再次重建，主题资源恢复且设置、账号和用户文件保留。再次正常启动确认产物复用；同版本重装确认再次重建。验证更新下载取消/失败会删除半成品，安装界面可以读完 APK；安装完成后重新启动确认旧 APK 清除。浏览器验收包括登录、上传、下载及关闭后重开。编译和单元测试不能替代设备覆盖升级验收。
