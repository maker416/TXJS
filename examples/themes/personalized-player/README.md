# 玩家专属外观最小示例

该包包含「极光中继」插画卡面、「棱镜之冠」透明头像框，以及金蓝条纹和支持者信息格。登录后在战役室打开自己的名片，点「试穿装扮」即可组合预览。其他玩家看到的平台分配外观由真实房间连接核验。

1. 在平台登记美术包，取得永久 ID。
2. 使用 Python 3 执行 `python build.py --pack-id <平台ID>`，生成 `build/artifacts/personalized-player/personalized-player.rwtheme`。脚本使用标准库，将示例插画和透明框一并写入包内。
3. 导入客户端，在包卡片复制发布目录，粘贴到开放平台发布版本。
4. 为目标的 UAS 全局账号 ID 分别分配 `room.player.background/aurora` 和 `room.player.frame/prism`。
5. 两个客户端启用该包，登录目标账号并加入支持核验的 TServer。无法核验时显示普通深色背景。

协议、限制和服务器接入见 [玩家专属美术包](../../../docs/玩家专属美术包.md)。脚本不包含凭据，不会访问网络或修改平台。
