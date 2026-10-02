/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.game.mod.heap.formatBytes
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

private val paper = Color(0xF3F5F7)
private val ink = Color(0x182D3A)
private val teal = Color(0x087E8B)
private val muted = Color(0x536775)

fun main(args: Array<String>) {
    // Windows 的 java/javaw 标准流编码可能独立于 file.encoding，统一日志与中文命令行报告。
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(System.err, true, Charsets.UTF_8))
    // 测量子进程先分派，避免 Swing 初始化影响基线或依赖图形桌面。
    if (args.firstOrNull() == "--measure-worker") {
        EngineHeapAnalyzer.runWorker(args.drop(1))
        return
    }
    val gameRootOption = args.indexOf("--game-root")
    require(gameRootOption < 0 || gameRootOption + 1 < args.size) { "--game-root <完整桌面游戏目录>" }
    val gameRoot = if (gameRootOption < 0) EngineHeapAnalyzer.findGameRoot() else File(args[gameRootOption + 1])
    val inputs = args.filterIndexed { index, _ -> gameRootOption < 0 || (index != gameRootOption && index != gameRootOption + 1) }
    if (inputs.firstOrNull() == "--analyze") {
        require(inputs.size == 2) { "--analyze <模组路径> [--game-root <完整桌面游戏目录>]" }
        val root = requireNotNull(gameRoot) { "未找到完整桌面游戏目录，请用 --game-root 指定。" }
        val result = EngineHeapAnalyzer.measure(File(inputs[1]), root) { System.err.println(it) }
        println(result.formatReport())
        return
    }
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val font = Font("Microsoft YaHei UI", Font.PLAIN, 13)
    UIManager.getDefaults().keys().toList().filter { it.toString().endsWith(".font") }
        .forEach { UIManager.put(it, font) }
    if (inputs.firstOrNull() == "--render-preview") {
        require(inputs.size == 3) { "--render-preview <模组路径> <PNG路径> [--game-root <完整桌面游戏目录>]" }
        val file = File(inputs[1])
        val root = requireNotNull(gameRoot) { "未找到完整桌面游戏目录，请用 --game-root 指定。" }
        val measurement = EngineHeapAnalyzer.measure(file, root) { System.err.println(it) }
        SwingUtilities.invokeAndWait {
            val window = HeapWindow(initialGameRoot = root)
            window.showMeasurement(file, measurement)
            window.pack()
            window.validate()
            val output = File(inputs[2])
            output.parentFile?.mkdirs()
            val image = BufferedImage(window.contentPane.width, window.contentPane.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            window.contentPane.printAll(graphics)
            graphics.dispose()
            ImageIO.write(image, "png", output)
            check(window.visibleUnitCount == measurement.unitCount)
            window.dispose()
            println("界面渲染验证通过: ${measurement.unitCount} 个单位；${output.absolutePath}")
        }
        return
    }
    SwingUtilities.invokeLater {
        val window = HeapWindow(initialGameRoot = gameRoot)
        window.isVisible = true
        if (inputs.isNotEmpty()) window.analyze(inputs.map(::File))
    }
}

private data class Analysis(val file: File, val measurement: MeasuredModHeap? = null, val error: String? = null) {
    override fun toString(): String = file.name + when {
        measurement == null -> "  ·  失败"
        measurement.unitCount == 0 -> "  ·  未加载单位"
        else -> "  ·  ${formatBytes(measurement.incrementalHeapBytes)}"
    }
}

private data class Update(val status: String? = null, val analysis: Analysis? = null)

internal class HeapWindow(
    private val analyzer: (File, File, (String) -> Unit) -> MeasuredModHeap = EngineHeapAnalyzer::measure,
    initialGameRoot: File? = EngineHeapAnalyzer.findGameRoot(),
) : JFrame("RWJS · 模组堆内存实测") {
    private val path = JTextField()
    private val gameRootPath = JTextField(initialGameRoot?.absolutePath.orEmpty())
    private val selectGameRoot = JButton("选择游戏目录…")
    private val status = JLabel("选择文件、目录，或将模组拖入窗口")
    private val progress = JProgressBar()
    private val results = DefaultListModel<Analysis>()
    private val list = JList(results)
    private val heading = JLabel("等待分析", SwingConstants.LEFT)
    private val sourcePath = JLabel("支持 .rwmod / .zip 和完整模组目录")
    private val metrics = listOf("GC 后堆净增", "实际加载单位", "单位对象图堆", "加载采样堆峰值")
        .map { title -> Metric(title) }
    private val budget = JLabel("每个模组由独立 JVM 调用真实桌面核心加载")
    private val columns = arrayOf("单位名称", "定义文件", "独占实测堆 (字节)")
    private val model = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(column: Int): Class<*> = when {
            column == 2 -> java.lang.Long::class.java
            else -> String::class.java
        }
    }
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)
    private val filter = JTextField()
    private val report = textArea()
    private val warnings = textArea()
    private val selectFiles = JButton("选择模组文件…")
    private val selectDirectory = JButton("选择目录…")
    private val run = JButton("分析路径")
    private val cancel = JButton("取消")
    private val exportText = JButton("导出报告…")
    private val exportCsv = JButton("导出单位 CSV…")
    private var worker: SwingWorker<Unit, Update>? = null
    private var selected: Analysis? = null
    private var chooserDirectory: File? = null
    val visibleUnitCount: Int get() = table.rowCount

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(980, 680)
        preferredSize = Dimension(1180, 800)
        contentPane = JPanel(BorderLayout(0, 16)).apply {
            background = paper
            border = BorderFactory.createEmptyBorder(20, 24, 18, 24)
        }
        val top = JPanel(BorderLayout(0, 14)).apply { isOpaque = false }
        val title = JPanel(BorderLayout()).apply { isOpaque = false }
        title.add(JLabel("MOD HEAP / 模组堆内存实测").apply {
            foreground = ink
            font = font.deriveFont(Font.BOLD, 25f)
        }, BorderLayout.NORTH)
        title.add(JLabel("调用真实核心加载 · 独立进程测量 · 需要桌面游戏资源").apply {
            foreground = muted
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }, BorderLayout.SOUTH)
        top.add(title, BorderLayout.NORTH)
        val inputs = JPanel(BorderLayout(8, 8)).apply { isOpaque = false }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        buttons.add(selectFiles)
        buttons.add(selectDirectory)
        inputs.add(buttons, BorderLayout.WEST)
        path.toolTipText = "粘贴模组文件或目录的完整路径，也可以直接拖入文件"
        inputs.add(path, BorderLayout.CENTER)
        inputs.add(run, BorderLayout.EAST)
        val inputRows = JPanel(BorderLayout(0, 8)).apply { isOpaque = false }
        inputRows.add(inputs, BorderLayout.NORTH)
        gameRootPath.toolTipText = "完整桌面版铁锈战争目录，需要游戏资源和 LWJGL 原生库"
        inputRows.add(JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(selectGameRoot, BorderLayout.WEST)
            add(gameRootPath, BorderLayout.CENTER)
            add(JLabel("需要完整游戏资源与原生库").apply { foreground = muted }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        top.add(inputRows, BorderLayout.SOUTH)
        contentPane.add(top, BorderLayout.NORTH)

        val resultPanel = JPanel(BorderLayout(0, 10)).apply {
            background = Color.WHITE
            border = BorderFactory.createEmptyBorder(14, 12, 14, 12)
            preferredSize = Dimension(238, 500)
        }
        resultPanel.add(JLabel("分析列表").apply { font = font.deriveFont(Font.BOLD, 15f) }, BorderLayout.NORTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = 60
        list.fixedCellWidth = 210
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                val component = super.getListCellRendererComponent(l, value, index, selected, focus) as JLabel
                component.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                val analysis = value as? Analysis
                component.toolTipText = analysis?.file?.absolutePath
                if (analysis != null) {
                    val name = analysis.file.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val detail = analysis.toString().substringAfter("  ·  ")
                    component.text = "<html>$name<br><small>$detail</small></html>"
                }
                return component
            }
        }
        resultPanel.add(JScrollPane(list).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        resultPanel.add(JLabel("可一次选择多个模组对比").apply { foreground = muted }, BorderLayout.SOUTH)

        val details = JPanel(BorderLayout(0, 12)).apply { isOpaque = false }
        val summary = JPanel(BorderLayout(0, 12)).apply { isOpaque = false }
        val info = JPanel(GridLayout(2, 1, 0, 5)).apply { isOpaque = false }
        heading.font = heading.font.deriveFont(Font.BOLD, 20f)
        heading.foreground = ink
        sourcePath.foreground = muted
        info.add(heading)
        info.add(sourcePath)
        summary.add(info, BorderLayout.NORTH)
        summary.add(JPanel(GridLayout(1, 4, 10, 0)).apply {
            isOpaque = false
            metrics.forEach { add(it.panel) }
        }, BorderLayout.CENTER)
        budget.foreground = muted
        summary.add(budget, BorderLayout.SOUTH)
        details.add(summary, BorderLayout.NORTH)

        table.rowSorter = sorter
        table.rowHeight = 32
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.gridColor = paper
        intArrayOf(200, 330, 190).forEachIndexed { i, width -> table.columnModel.getColumn(i).preferredWidth = width }
        table.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                horizontalAlignment = SwingConstants.RIGHT
                text = (value as? Long)?.let { formatBytes(it) }.orEmpty()
                toolTipText = "$value 字节"
            }
        }
        val unitPanel = JPanel(BorderLayout(0, 10)).apply {
            background = Color.WHITE
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        unitPanel.add(JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(JLabel("筛选单位 / 文件"), BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        unitPanel.add(JScrollPane(table), BorderLayout.CENTER)
        val tabs = JTabbedPane().apply {
            addTab("单位占用排行", unitPanel)
            addTab("完整报告", JScrollPane(report))
            addTab("分析提示", JScrollPane(warnings))
        }
        details.add(tabs, BorderLayout.CENTER)
        val footer = JPanel(BorderLayout(0, 8)).apply { isOpaque = false }
        footer.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(exportText)
            add(exportCsv)
        }, BorderLayout.NORTH)
        footer.add(JLabel("测量当前桌面 JVM 堆；显存、原生内存和游戏中的单位实例不计入堆净增。").apply { foreground = muted }, BorderLayout.SOUTH)
        details.add(footer, BorderLayout.SOUTH)
        contentPane.add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, resultPanel, details).apply {
            resizeWeight = 0.0
            dividerLocation = 238
            dividerSize = 12
            border = BorderFactory.createEmptyBorder()
            isOpaque = false
        }, BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout(10, 0)).apply { isOpaque = false }
        bottom.add(status, BorderLayout.CENTER)
        bottom.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            progress.preferredSize = Dimension(150, 16)
            add(progress)
            add(cancel)
        }, BorderLayout.EAST)
        contentPane.add(bottom, BorderLayout.SOUTH)
        setBusy(false)
        setExportEnabled(false)
        list.addListSelectionListener { if (!it.valueIsAdjusting) display(list.selectedValue) }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateFilter()
            override fun removeUpdate(e: DocumentEvent?) = updateFilter()
            override fun changedUpdate(e: DocumentEvent?) = updateFilter()
        })
        selectFiles.addActionListener { choose(false) }
        selectDirectory.addActionListener { choose(true) }
        selectGameRoot.addActionListener { chooseGameRoot() }
        run.addActionListener { analyzePath() }
        path.addActionListener { analyzePath() }
        cancel.addActionListener { worker?.cancel(true) }
        exportText.addActionListener { export(false) }
        exportCsv.addActionListener { export(true) }
        val drop = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                worker == null && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                analyze(files)
                return true
            }
        }
        rootPane.transferHandler = drop
        list.transferHandler = drop
        table.transferHandler = drop
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { worker?.cancel(true) }
        })
        pack()
        setLocationRelativeTo(null)
    }

    private fun choose(directory: Boolean) {
        val chooser = JFileChooser(chooserDirectory).apply {
            dialogTitle = if (directory) "选择模组目录" else "选择一个或多个模组"
            fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = !directory
            if (!directory) fileFilter = FileNameExtensionFilter("完整模组 / ZIP", "rwmod", "zip")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            chooserDirectory = chooser.currentDirectory
            analyze(if (directory) listOf(chooser.selectedFile) else chooser.selectedFiles.toList())
        }
    }

    private fun analyzePath() {
        val text = path.text.trim().removeSurrounding("\"")
        if (text.isBlank()) { status.text = "请先选择文件或粘贴路径"; return }
        analyze(listOf(File(text)))
    }

    private fun chooseGameRoot() {
        val chooser = JFileChooser(gameRootPath.text.takeIf { it.isNotBlank() }?.let(::File)).apply {
            dialogTitle = "选择完整桌面版铁锈战争目录（包含游戏资源和原生库）"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            gameRootPath.text = chooser.selectedFile.absolutePath
        }
    }

    fun analyze(files: List<File>) {
        if (worker != null || files.isEmpty()) return
        val inputs = files.distinctBy { it.absolutePath }
        path.text = inputs.first().absolutePath
        val rootText = gameRootPath.text.trim().removeSurrounding("\"")
        if (rootText.isEmpty()) {
            status.text = "请先选择完整桌面游戏目录，需要游戏资源与原生库"
            return
        }
        val gameRoot = File(rootText)
        if (!gameRoot.isDirectory) {
            status.text = "游戏目录不存在：${gameRoot.absolutePath}"
            return
        }
        results.clear()
        display(null)
        setBusy(true)
        worker = object : SwingWorker<Unit, Update>() {
            override fun doInBackground() {
                for ((index, file) in inputs.withIndex()) {
                    if (isCancelled) break
                    publish(Update(status = "分析 ${index + 1}/${inputs.size}：${file.name}"))
                    var lastUpdate = 0L
                    val result = try {
                        Analysis(file, analyzer(file, gameRoot) { message ->
                            if (isCancelled) throw CancellationException()
                            val now = System.nanoTime()
                            if (now - lastUpdate > 120_000_000L) {
                                publish(Update(status = "${index + 1}/${inputs.size} · $message"))
                                lastUpdate = now
                            }
                        })
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        Analysis(file, error = e.message ?: e.javaClass.simpleName)
                    }
                    publish(Update(analysis = result))
                }
            }

            override fun process(chunks: MutableList<Update>) {
                if (worker !== this || !isDisplayable) return
                chunks.lastOrNull { it.status != null }?.status?.let { status.text = it }
                for (update in chunks) update.analysis?.let {
                    results.addElement(it)
                    if (list.selectedIndex < 0) list.selectedIndex = 0
                }
            }

            override fun done() {
                if (worker !== this) return
                var error: String? = null
                try { get() } catch (_: CancellationException) {
                    // 已完成的分析保留，可以导出。
                } catch (e: ExecutionException) {
                    error = e.cause?.message ?: "分析异常"
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                worker = null
                setBusy(false)
                status.text = error ?: if (isCancelled) "已取消；已完成的结果保留" else {
                    val failures = (0 until results.size()).count { results.get(it).measurement == null }
                    "分析完成：${results.size() - failures} 个成功，$failures 个失败"
                }
            }
        }
        worker!!.execute()
    }

    fun showMeasurement(file: File, measurement: MeasuredModHeap) {
        results.addElement(Analysis(file, measurement))
        list.selectedIndex = results.size() - 1
        status.text = "分析完成：1 个成功，0 个失败"
    }

    private fun display(value: Analysis?) {
        selected = value
        model.rowCount = 0
        filter.text = ""
        val measurement = value?.measurement
        heading.text = value?.file?.name ?: "等待分析"
        sourcePath.text = value?.file?.absolutePath ?: "支持 .rwmod / .zip 和完整模组目录"
        sourcePath.toolTipText = value?.file?.absolutePath
        metrics.forEach { it.value.text = "—" }
        report.text = value?.error ?: "选择文件、目录或拖入模组，开始分析。\n\n支持多文件批量分析，点左侧列表查看各个模组的结果。"
        warnings.text = value?.error ?: "提示将在分析后显示。"
        budget.text = "每个模组由独立 JVM 调用真实桌面核心加载"
        setExportEnabled(measurement != null)
        if (measurement == null) return
        metrics[0].value.text = formatBytes(measurement.incrementalHeapBytes)
        metrics[1].value.text = measurement.unitCount.toString()
        metrics[2].value.text = formatBytes(measurement.definitionHeapBytes)
        metrics[3].value.text = formatBytes(measurement.sampledPeakHeapBytes)
        budget.text = "单位间共享对象堆：${formatBytes(measurement.sharedDefinitionHeapBytes)} · 排行只列独占对象"
        if (measurement.warnings.isNotEmpty()) budget.text = "${budget.text} · ${measurement.warnings.size} 项提示"
        // 一次通知排序器，避免大量单位逐行插入时重复排序。
        measurement.units.forEach {
            model.dataVector.add(java.util.Vector<Any>(listOf<Any>(it.name, it.fileName, it.exclusiveBytes)))
        }
        model.fireTableDataChanged()
        sorter.sortKeys = listOf(RowSorter.SortKey(2, SortOrder.DESCENDING))
        report.text = measurement.formatReport()
        report.caretPosition = 0
        warnings.text = (measurement.warnings.ifEmpty { listOf("真实核心加载未返回额外提示。") } + listOf(
            "测量环境：${measurement.runtimeDescription}",
            "GC 后堆净增来自加载前后实际 JVM 堆用量；共享缓存和 GC 会影响结果。",
            "单位对象图按实际对象大小去重；多单位共享对象单列，不重复分配给单位排行。",
            "采样峰值可能漏掉采样间隔内的瞬时峰值；结果对应当前桌面 JVM。",
            "引擎贴图与音频记账不等于 Java 堆，也不能直接相加得到进程总内存。"
        )).joinToString("\n\n")
        warnings.caretPosition = 0
    }

    private fun updateFilter() {
        val query = filter.text.trim()
        sorter.rowFilter = if (query.isEmpty()) null else object : RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean =
                entry.getStringValue(0).contains(query, true) || entry.getStringValue(1).contains(query, true)
        }
    }

    private fun export(csv: Boolean) {
        val result = selected ?: return
        val measurement = result.measurement ?: return
        val extension = if (csv) "csv" else "txt"
        val chooser = JFileChooser(chooserDirectory).apply {
            dialogTitle = if (csv) "导出所有单位（不受筛选影响）" else "导出完整报告"
            selectedFile = File("${result.file.nameWithoutExtension}-heap.$extension")
            fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var output = chooser.selectedFile
        if (!output.name.endsWith(".$extension", true)) output = File(output.path + ".$extension")
        if (output.exists() && JOptionPane.showConfirmDialog(this, "覆盖已有文件 ${output.name}？", "导出", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        try {
            output.writeText(if (csv) "\uFEFF" + measurement.unitCsv() else "来源：${result.file.absolutePath}\n\n${measurement.formatReport()}\n", Charsets.UTF_8)
            status.text = "已导出：${output.absolutePath}"
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "导出失败：${e.message}", "导出失败", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun setBusy(busy: Boolean) {
        listOf<JComponent>(selectFiles, selectDirectory, selectGameRoot, run, path, gameRootPath).forEach { it.isEnabled = !busy }
        cancel.isEnabled = busy
        progress.isIndeterminate = busy
    }

    private fun setExportEnabled(enabled: Boolean) { exportText.isEnabled = enabled; exportCsv.isEnabled = enabled }
}

private class Metric(title: String) {
    val value = JLabel("—").apply { foreground = teal; font = font.deriveFont(Font.BOLD, 23f) }
    val panel = JPanel(BorderLayout(0, 12)).apply {
        background = Color.WHITE
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color(0xDEE6EA)), BorderFactory.createEmptyBorder(14, 12, 14, 12))
        add(JLabel(title).apply { foreground = muted; font = font.deriveFont(12f) }, BorderLayout.NORTH)
        add(value, BorderLayout.CENTER)
    }
}

private fun textArea() = JTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
    foreground = ink
}
