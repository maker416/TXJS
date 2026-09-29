# RWJS 主题美术包（.rwtheme）制作指南

面向创作者的主题包格式文档。主题包可以自定义启动器外观与游戏内贴图，**仅本地生效**，不影响联机（不修改联机协议，贴图不参与单位校验和，不会导致无法进房或掉线）。

## 能自定义什么（总览）

| 类别 | 内容 | 载体 |
|---|---|---|
| 主题配色 | 全局 ColorScheme（按钮/卡片/输入框/强调色等 20 个角色） | `theme.toml` 的 `[colors]` |
| 启动器背景图 | 整个启动器底图 | `background.png` / `background.jpg` |
| 主菜单标题图 | 主菜单顶部 LOGO 图，可隐藏「极速版」角标 | `title.png` + `[menu] showTitleBadge` |
| 背景音乐 | 主菜单循环播放（进对局自动停） | `music.<扩展名>` |
| 主菜单布局 | 按钮区方向/列数/对齐/偏移/宽度/高度/圆角/间距 | `[menu.layout]` |
| 主菜单按钮 | 排序、占列宽、隐藏（按语义 id） | `[menu.buttons.<id>]` |
| 按钮背景图 | 单个主菜单按钮的背景图片 | `buttons/<id>.png` |
| 按钮/全局文字 | 任意启动器文案覆盖（含主菜单按钮文字） | `strings_zh.toml` / `strings_en.toml` |
| 全局字体 | 正文/标题字体族 | `[fonts]` + `fonts/*.ttf` |
| 原版单位贴图 | 覆盖 `assets/units/` 下的单位图像 | `game/units/...` |
| 地块贴图 | 覆盖 `assets/tilesets/bitmaps/` 下的地块图像 | `game/tilesets/bitmaps/...` |

不支持：逐按钮自定义（设计决策，配色/文字/布局用上述机制组合实现）；地图 `.tmx` 本身；地图内嵌 base64 图像；模组（.rwmod）包内贴图。

## 打包方式

把下列文件打成 **zip**，后缀改为 `.rwtheme` 即可（zip 根目录直接放 `theme.toml`，不要再套一层文件夹）。玩家在游戏内「设置 → 主题 → 主题包管理 → 导入主题包」导入使用。

```
我的主题.rwtheme（zip 内容）
├── theme.toml            # 必需：元数据 + 配色 + 布局 + 字体声明
├── strings_zh.toml       # 可选：中文文案覆盖
├── strings_en.toml       # 可选：英文文案覆盖
├── background.png        # 可选：启动器背景（也支持 background.jpg）
├── title.png             # 可选：主菜单标题图
├── icon.png              # 可选：管理列表里的包图标
├── buttons/              # 可选：按钮背景图
│   ├── singlePlayer.png
│   └── multiplayer.png
├── fonts/                # 可选：字体文件
│   └── myfont.ttf
├── music.ogg             # 可选：背景音乐
└── game/                 # 可选：游戏内贴图覆盖层（镜像游戏 assets 结构）
    ├── units/tanks/tank.png
    └── tilesets/bitmaps/sand.png
```

## theme.toml 参考

```toml
[theme]
id = "my-theme"          # 必需。只能含字母/数字/下划线/连字符，作为安装目录名，全机唯一
name = "我的主题"         # 必需。展示名
author = "你的名字"
version = "1.0"
description = "一句话介绍"

[colors]
# 全部可选；缺省的角色回落内置 RWPP 主题。格式 #RRGGBB 或 #AARRGGBB，必须带 #
primary = "#82B1FF"           # 主强调色（按钮高亮、标题、徽标）
onPrimary = "#FF000000"       # 主色之上的文字色
primaryContainer = "#FF297EA0"
onPrimaryContainer = "#FFFFFFFF"
secondary = "#FFA1E9DF"
onSecondary = "#FF000000"
secondaryContainer = "#FF005049"
onSecondaryContainer = "#FFFFFFFF"
tertiary = "#FFA0E5E5"
onTertiary = "#FF000000"
tertiaryContainer = "#FF004F50"
onTertiaryContainer = "#FFFFFFFF"
background = "#FF283238"      # 卡片底色的基色
onBackground = "#FFFFFFFF"
surface = "#FF1B1212"
onSurface = "#FFFFFFFF"       # 正文文字主色
surfaceContainer = "#FF3C464C" # 描边/分隔容器色
error = "#FFCF6679"
onError = "#FF000000"
inversePrimary = "#FF2F515F"

[menu]
showTitleBadge = true    # 是否在标题图右下角显示「极速版」角标（自定义标题图通常建议 false）

# ---- 主菜单按钮区布局（全部可选，缺省即内置布局）----
[menu.layout]
orientation  = "grid"    # grid（网格）| vertical（全部整行宽竖排）
columns      = 2         # 1-4，grid 模式列数
align        = "center"  # top | center | bottom，整块（标题+按钮）的垂直位置
offsetY      = 0         # -400..400，整块垂直偏移（dp）
widthPercent = 65        # 30-100，按钮区占屏宽百分比
buttonHeight = 44        # 28-96，按钮最小高度（dp）
buttonCorner = 20        # 0-32，按钮圆角（dp）
spacing      = 10        # 0-32，按钮间距（dp）

# ---- 按钮槽位：排序/占列/隐藏（按语义 id，全部可选）----
[menu.buttons.mods]
order  = -2              # 排序权重，越小越靠前；不写保持内置顺序
span   = 2               # 占几列（grid 模式），占满列数即整行宽

[menu.buttons.openSourceInfo]
hidden = true            # 从主菜单隐藏该按钮
```

**按钮语义 id**（固定不变，可放心引用）：

| id | 内置按钮 |
|---|---|
| `singlePlayer` | 单人游戏 |
| `multiplayer` | 多人游戏 |
| `resourceBrowser` | 资源获取 |
| `mods` | 模组与地图 |
| `settings` | 设置 |
| `openSourceInfo` | 反馈与说明 |

## 文案覆盖（strings_zh.toml / strings_en.toml）

与游戏内置语言文件同款的点分键，**只需写你想覆盖的键**，没写的键自动用内置文本。主菜单按钮文字对应键：

```toml
[menu]
singlePlayerGame = "孤胆征程"   # 单人游戏按钮
multiplayer = "联机大厅"        # 多人游戏按钮
modsAndMaps = "模组与地图"
settings = "设置"
openSourceInfo = "反馈与说明"
exit = "退出"
friends = "好友"

[browser]
resourceBrowser = "资源获取"
```

带 `{0}` 占位的键请保留占位符。键写错不会崩溃，只是不生效。

## 背景音乐

包根放 `music.<扩展名>`。格式按平台分：

| 平台 | 支持格式 |
|---|---|
| Android | mp3 / ogg / m4a / aac / wav / flac |
| 桌面端 | **wav / ogg**（不支持 mp3） |

建议放 ogg（双端通吃）。玩家可在「设置 → 主题 → 背景音乐音量」调节音量（0 即关闭）；进对局自动停，回主菜单续播。

## 字体

```toml
[fonts]
regular = "fonts/myfont.ttf"    # 常规字重（包内相对路径）
bold = "fonts/bold.ttf"         # 可选；缺省时粗体由系统合成
```

替换全局正文/标题字体族（含主菜单按钮）。字体文件较大时注意包体积；**请确认字体的分发许可**。

## 游戏内贴图（game/ 目录）

`game/` 内部镜像游戏 `assets/` 目录结构，同名文件覆盖原版：

- **单位贴图**：`game/units/<单位目录>/<图名>.png`——如 `game/units/tanks/tank.png`。游戏根目录的 `assets/units/` 下可查到全部单位目录与图名。启用/停用主题包时会自动重载单位，立即生效。
- **地块贴图**：`game/tilesets/bitmaps/<图名>.png`——如 `game/tilesets/bitmaps/sand.png`。**注意**：引擎无论地图里写的什么子路径（`terrain/`、`ridges/` 等），实际都从 `tilesets/bitmaps/` 读图，所以覆盖文件一律放在 `game/tilesets/bitmaps/` 下。地块贴图在下次进图时生效。

其他游戏内资源（`gui/`、`music/`、`shaders/` 目录）也在同一覆盖层生效，按需放置。

已知边界：地图 `.tmx` 文件不可覆盖；把图像内嵌进 tmx（base64）的地图拦截不到；模组包（.rwmod）内的贴图不受影响。贴图尺寸变化可能影响个别按图宽计算的布局，建议与原图保持同尺寸。

## 校验与常见错误

导入时客户端会完整校验，**不合法直接拒绝导入**并给出原因：

- 缺 `theme.toml` / 缺 `[theme]` 段 / `id` 或 `name` 为空 / `id` 含非法字符 / 与已安装包 id 重复
- `strings_*.toml` 不是合法 TOML
- 包内文件路径含 `..` 或以 `/`、盘符开头（安全限制）
- 字体路径逃逸出包目录

以下情况**不拒绝但会在导入结果里警告并忽略**：配色色值格式错（忘记带 `#`）、未知配色角色名（检查拼写）、未知按钮槽位 id、`orientation`/`align` 写错（回落默认值）。

数值越界（如 `columns = 99`）会自动钳制到安全范围，不警告。

## 调试技巧

1. 改动后无需重启游戏：主题包管理页里停用再启用即可热切换（游戏内贴图会自动重建资源并重载单位）。
2. 配色没变化 → 检查色值是否带了 `#`。
3. 按钮文字没变 → 检查键名是否与内置语言文件一致（区分大小写）。
4. 地块没变 → 确认图片放在 `game/tilesets/bitmaps/` 且文件名与 `assets/tilesets/bitmaps/` 里的完全一致。
5. 游戏内贴图目录：`resource_generated/`（游戏根目录 / Android 的 `rustedWarfare/`）是叠加结果，可以进去确认你的文件是否被正确叠加。
