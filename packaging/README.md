# PC 一体包：原版游戏本体

安装包会把原版铁锈战争运行时与 RWJS 启动器打在一起，安装到独立目录（默认 `%ProgramFiles%\Minxyzgo\RWJS`）。

## 配置游戏根目录

**不要把游戏本体提交进 Git。** 构建时从本机原版安装目录读取。

优先级：

1. 环境变量 `RW_GAME_ROOT`
2. `packaging/game-root.local.txt`（单行路径，可被 gitignore）
3. 默认：`D:\APP\Steam\steamapps\common\Rusted Warfare`

示例：

```text
set RW_GAME_ROOT=D:\APP\Steam\steamapps\common\Rusted Warfare
.\build\build.ps1 msi
```

## 会打进安装包的内容

- `assets/`、`font/`、`libs/`、`res/`、`mods/`
- `game-lib.jar`、原版 exe、原生 DLL、Steam 相关文件等

## 不会打进安装包的内容

- `jvm/`、`jvm64/`（启动器自带 `runtime/`）
- `cache/`、`saves/`、`replays/`、`generated_lib/`
- RWPP/RWJS 用户配置、日志、`launcher.bat` 等
