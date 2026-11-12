# 远洋图志 2.1 · Pelagic Atlas

浅象牙纸、海图墨线、朱砂与海蓝票券，把启动器变成一册可以出航的战略图志。

本包使用公开 `.rwtheme` 格式：独立经纬线背景、透明几何字形标题、罗盘缩略图、九张原创卡片和完整中英文主页/资源中心文案。「出航作战桌」等待战斗室提供27张专用原创资产：五个材质面板、船员票据、名片印章和十种航令按钮；全包39张PNG及对应可编辑SVG。2.1 已移除全部战斗室结构配置，只通过这些图片、配色和描边装饰客户端内置界面。背景是抽象制图装饰，不表示真实地理或游戏地图。

## 使用

需要支持新版卡片主页和“战斗室只改外观、PC/Android 分别布局”的客户端构建，参见[战斗室开发规范](../../../docs/战斗室美术包开发.md)。`version = "2.1.0"` 是本包版本，不会为旧客户端开启新功能。

从「资源中心 → 扩展/美术包管理」导入 `pelagic-atlas.rwtheme`（或内容相同的 `远洋图志.rwtheme`），再启用。若此前取消了主页背景图片，在设置中选择「恢复主题背景」。顶部拼图管理入口始终可用。停用即可返回内置外观。`Pelagic-Atlas-Example-Kit.zip` 是源码与预览套件，不能当美术包导入。

1.x、2.0 与 2.1 使用同一个 `pelagic-atlas` id，不能重复导入。更新时退出房间，停用旧包并等待资源刷新结束，在美术包管理中删除旧包，再导入2.1并启用。只替换下载目录里的文件不会更新已安装的副本；本包不包含游戏贴图。

## 2.1 更新内容

- 删除战斗室 `layout`、`compact`、`wide`，以及面板排序/占列、玩家行高/列宽/显隐、字号/行数和按钮尺寸等旧字段；新包导入不产生旧结构字段提示。
- 战斗室沿用客户端当前布局，美术包只装饰已有面板、玩家行与按钮，不改变面板位置、列宽、行高或滚动区域。宽屏及紧凑视图均使用真实地图与玩家状态，切换/停用外观不会重建房间内容。
- 保留39张原创图片及名字字重、状态底图、名片图标、配色、圆角和边框；`borderWidth = 0` 取消客户端描边，图片自身的装饰线仍会显示。
- 更新完整规范、最小配置示例、同一安装包生成的真实页面预览和 SHA-256。

主页采用三列九宫格：启航入口、探索入口、维护入口。窄屏自动减为两列或一列并可滚动，前三项保留单人、多人和资源中心；资源中心复用三张入口卡。该配置只属于主页 `[menu]`，不控制战斗室 `[room]`。原生文字和图标始终可交互，未烘焙进图片。

## 出航作战桌

战斗室只提供图片、材质和配色，PC 与 Android 分别使用客户端内置布局。地图图片、玩家昵称、队伍/位置/延迟、同步与准备提示都由客户端真实数据呈现。旧包中的列数、面板排序、行高等结构字段会被忽略；当前源码配置已移除这些字段。

五个面板分别采用船员登记纸、地图方格纸、黄铜刻度纸、斜纹操作衬板和通讯横线纸。玩家票据以颜色与边带图形双重区分：本地海蓝双线、房主赭金编带、AI灰绿机读齿条、准备浅绿连续线、未准备砂色虚线、空邀请位中性虚线。**状态图片优先级遵循客户端：自己 → 房主 → AI → 准备/未准备 → 通用。** 本地或房主底图不会随准备状态切换，真实准备提示仍显示；没有把准备勾选、昵称或锁房文字画在图片里。

没有配置 `slotImages`，因为出生位置图优先于角色图，会覆盖本地/房主票据。名字使用客户端已有字体、600字重和统一深海墨色；字号、行数与列宽由客户端决定。名片使用原创证件人像图标；普通、点亮、未确认可用三个印章底纹仍由真实权限选择。AI等不可查看名片的行保留客户端行为。

队伍与出生位置两列仍显示引擎原生队伍色；当前公开格式不能逐列覆盖它们的颜色。因此黄、青、白等浅色在纸色票据上可能对比较低。两列保持可见，不以隐藏信息掩盖限制；姓名已独立使用深色。玩家票据采用1200×96宽幅，适应桌面约12:1的行比例，窄屏水平裁切时仍保留上、下状态边带。

十个公开操作 id（开始、设置、选图、AI、换位、邀请、公示、锁房、成员邀请策略、发送聊天）都有专用图片和样式。朱砂开始按钮与海蓝发送按钮形成主次重点，按钮文字仍跟随原生业务状态。长按添加AI、名片查询、同步和开始条件不受主题改变。

## 从源码构建

在仓库根目录运行：

```powershell
python -m pip install -r examples/themes/pelagic-atlas/tools/requirements.txt
python examples/themes/pelagic-atlas/tools/build.py
```

脚本生成可编辑 `source/**/*.svg`（战斗室在 `source/room/`），渲染根目录、`buttons/` 与 `room/` 的 PNG，校验三份 TOML、每张图片与战斗室图片引用的一一对应，输出 `build/artifacts/theme-showcase/pelagic-atlas.rwtheme`、同内容的 `远洋图志.rwtheme` 及各自的 SHA-256。ZIP 时间戳固定，文件名单明确限制为配置、许可、说明与PNG；`source/`、`tools/` 和预览不会进入可导入包。SVG 不依赖系统字体；界面正文使用客户端字体。

`room-theme.example.toml` 是较小的独立配置示例。使用时另建目录，把它命名为 `theme.toml`，只复制其引用的 `background.png` 与 `buttons/*.png`（保留相对路径）后压缩成 `.rwtheme`。不要用它覆盖完整示例的 `theme.toml`：完整构建脚本会校验27张专用战斗室图片全部被引用。

## 验证边界

预览与点击验收使用真实 `ThemePackInstaller`、主页、资源中心与战斗室组件，在隔离临时目录运行，不覆盖玩家配置。主页检查1280×720、640×360、400×800；战斗室应检查1280×720、800×400、360×720，覆盖角色、长昵称、准备状态与滚动后的所有按钮。桌面 Compose 逻辑视口预览不等于 Android 真机、软键盘或联网开局测试。具体完成情况与截图请以包旁预览目录及交付说明为准。

English: Version 2.1 removes all room layout, sizing, ordering and visibility overrides. PC and Android retain their own native room structures, with the composer above the message history. All 39 original images, role/state tickets, profile artwork and colours are preserved. Use a client supporting appearance-only room themes. To update the same `pelagic-atlas` id, leave the room, deactivate, wait for reload, remove the old pack, then import and activate 2.1. Artwork remains reproducible from the included sources; see `ASSET-LICENSE.md`.
