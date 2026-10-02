# 模组堆内存分析工具

独立桌面工具，无需启动游戏或准备游戏运行资源。要求 Java 21 或更高版本。

## 打开界面

在仓库根目录双击 `启动模组内存分析.bat`。首次运行会通过 Gradle 构建；后续直接打开已生成的 JAR。

也可以运行：

```powershell
.\gradlew.bat :rwpp-mod-heap-tool:run
```

选择一个或多个 `.rwmod` / `.zip` / `.ini` 文件，或选择模组目录。支持拖入文件、粘贴路径、批量分析、取消，以及按单位名或文件名筛选。点击左侧结果可切换模组；表头可排序。分析在后台运行，读取失败会在列表单独显示，其余模组继续分析。

“导出报告”保存当前模组的完整 TXT 报告；“导出单位 CSV”保存当前模组全部单位，不受界面筛选影响。CSV 带 UTF-8 BOM，方便 Excel 打开。

## 构建与分发

```powershell
.\gradlew.bat :rwpp-mod-heap-tool:packageTool
java -Xmx768m -jar build\mod-heap-tool\RWJS-ModHeapTool.jar
```

生成的 `build/mod-heap-tool/RWJS-ModHeapTool.jar` 可单独复制到其他安装了 Java 21 的机器。工具使用 JDK Swing，无需 Compose、OpenGL 或游戏运行环境。

修改代码后重新执行 `packageTool`，让双击脚本使用最新产物。

原命令行入口继续可用：

```powershell
.\gradlew.bat :rwpp-core-api:estimateModHeap "-Pmod=D:\Mods\example.rwmod"
```

## 如何理解结果

- **单位定义保留堆**：根据配置、继承、逻辑表达式和单位对象结构静态估算 JVM/ART 堆。尚未用实测堆快照校准，不包含原版单位、单位实例、临时解析对象或进程其他开销。
- **包内贴图参考**：只读取图片头，按宽 × 高 × 8 累加引擎记账参考；包括未引用图片，不包含音频。Android 的贴图 native 内存不加进单位定义堆。
- **512 MiB 预算占比**：仅为参考，设备实际堆上限可能不同。
- **新旧单位表并存**：估算值乘二，仅用于观察同规模定义并存的量级，不是重载峰值实测，也不能保证没有 OOM。

继承解析支持相对文件路径、模组根 `ROOT:`、显式 `.template` 与最近一层 `all-units.template`，保留旧工具的无歧义名称查找兼容。模板及当前文件 `dont_load=true` 不单独计为单位。找不到模板、继承成环、空包、变量替换或尚未支持的小节继承会显示提示；遇到歧义名称不会随意挑一个文件。

读取压缩包时跳过音频等无关文件，图片只读最多 256 KiB 的文件头，不解码像素。单份配置上限 8 MiB，总配置上限 128 MiB；超限停止并明确提示。损坏或加固压缩包无法读取时显示失败，不当作占用为零。

## 验证

```powershell
.\gradlew.bat :rwpp-core-api:test :rwpp-mod-heap-tool:test :rwpp-mod-heap-tool:packageTool
```

界面可以离屏渲染用于布局检查：

```powershell
java -jar build\mod-heap-tool\RWJS-ModHeapTool.jar --render-preview D:\Mods\example.rwmod build\mod-heap-tool\preview.png
```
