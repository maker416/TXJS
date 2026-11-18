# 玩家专属外观最小示例

该包定义金色/蓝色背景、金色描边及支持者信息格。外观资源全部预置；账号分配在开放平台完成。

1. 在平台登记美术包，取得永久 ID。
2. 使用 Python 3 执行 `python build.py --pack-id <平台ID>`，生成 `build/artifacts/personalized-player/personalized-player.rwtheme`。脚本只使用标准库，所有图片随包生成。
3. 导入客户端，在包卡片复制发布目录，粘贴到开放平台发布版本。
4. 为目标的 UAS 全局账号 ID 分配 `room.player.background/gold` 等变体。
5. 两个客户端启用该包，登录目标账号并加入支持核验的 TServer。无法核验时显示普通深色背景。

协议、限制和服务器接入见 [玩家专属美术包](../../../docs/玩家专属美术包.md)。脚本不包含凭据，不会访问网络或修改平台。
