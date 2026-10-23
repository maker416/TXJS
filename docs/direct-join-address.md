# 直连地址校验

2026-10-23 排查：桌面安装版的 `MultiplayerPreferences.joinServerAddress` 保存了 `192.168.1.2:5123\nat android.net.SSLCertificateSocketFactory...`；运行日志也记录了同一份多行连接参数。

Compose 的 `singleLine=true` 只控制显示，不能作为地址合法性检查。多行内容可隐藏在一行输入框中，随后交给官方解析器：带端口时报 `Bad port number`，不带端口时报未知主机。错误消息里的 Android 类名来自地址内容，不能据此判断 TLS 连接失败。

共享连接入口在短码转换及网络操作前拒绝换行、控制字符与 Unicode 行分隔符，并提示清空重输。打开多人页时清空旧的多行输入；历史记录拒绝写入多行地址，并过滤含空白的堆栈行。合法 IP、域名和 Q/R/T 地址继续走原来的连接流程。

回归覆盖：`RoomAddressTest.directAddressesRejectHiddenLogLines`、`NetworkJoinHistoryTest.pastedLogIsNotRecordedAndStackFramesAreNotOfferedAsAddresses`。安装版仍需更新构建后生效。
