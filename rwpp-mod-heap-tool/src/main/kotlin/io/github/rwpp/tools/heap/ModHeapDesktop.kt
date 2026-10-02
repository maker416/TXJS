/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.game.mod.heap.ModHeapEstimate
import io.github.rwpp.game.mod.heap.ModHeapEstimator
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
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val font = Font("Microsoft YaHei UI", Font.PLAIN, 13)
    UIManager.getDefaults().keys().toList().filter { it.toString().endsWith(".font") }
        .forEach { UIManager.put(it, font) }
    if (args.firstOrNull() == "--render-preview") {
        require(args.size == 3) { "--render-preview <模组路径> <PNG路径>" }
        val file = File(args[1])
        val estimate = ModHeapEstimator.estimate(file)
        SwingUtilities.invokeAndWait {
            val window = HeapWindow()
            window.showEstimate(file, estimate)
            window.pack()
            window.validate()
            val output = File(args[2])
            output.parentFile?.mkdirs()
            val image = BufferedImage(window.contentPane.width, window.contentPane.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            window.contentPane.printAll(graphics)
            graphics.dispose()
            ImageIO.write(image, "png", output)
            check(window.visibleUnitCount == estimate.unitCount)
            window.dispose()
            println("界面渲染验证通过: ${estimate.unitCount} 个单位；${output.absolutePath}")
        }
        return
    }
    SwingUtilities.invokeLater {
        val window = HeapWindow()
        window.isVisible = true
        if (args.isNotEmpty()) window.analyze(args.map(::File))
    }
}

private data class Analysis(val file: File, val estimate: ModHeapEstimate? = null, val error: String? = null) {
    override fun toString(): String = file.name + when {
        estimate == null -> "  ·  失败"
        estimate.unitCount == 0 -> "  ·  未找到单位"
        else -> "  ·  ${formatBytes(estimate.heapBytes)}"
    }
}

private data class Update(val status: String? = null, val analysis: Analysis? = null)

internal class HeapWindow : JFrame("RWJS · 模组堆内存分析") {
    private val path = JTextField()
    private val status = JLabel("选择文件、目录，或将模组拖入窗口")
    private val progress = JProgressBar()
    private val results = DefaultListModel<Analysis>()
    private val list = JList(results)
    private val heading = JLabel("等待分析", SwingConstants.LEFT)
    private val sourcePath = JLabel("支持 .rwmod / .zip / .ini 和模组目录")
    private val metrics = listOf("单位定义保留堆", "单位定义", "逻辑节点", "包内贴图参考")
        .map { title -> Metric(title) }
    private val budget = JLabel("静态估算不包含进程其他开销")
    private val columns = arrayOf("单位名称", "定义文件", "估算堆 (字节)", "逻辑节点", "小节", "memory 变量")
    private val model = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
        override fun getColumnClass(column: Int): Class<*> = when {
            column == 2 -> java.lang.Long::class.java
            column >= 3 -> java.lang.Integer::class.java
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
        title.add(JLabel("MOD HEAP / 模组堆内存分析").apply {
            foreground = ink
            font = font.deriveFont(Font.BOLD, 25f)
        }, BorderLayout.NORTH)
        title.add(JLabel("无需启动游戏 · 静态分析单位配置 · 找出占用较高的定义").apply {
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
        top.add(inputs, BorderLayout.SOUTH)
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
        intArrayOf(180, 260, 125, 90, 65, 115).forEachIndexed { i, width -> table.columnModel.getColumn(i).preferredWidth = width }
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
        footer.add(JLabel("估算单位定义的保留堆；不代表实测内存或重载峰值。").apply { foreground = muted }, BorderLayout.SOUTH)
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
            if (!directory) fileFilter = FileNameExtensionFilter("模组 / ZIP / 单位配置", "rwmod", "zip", "ini")
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

    fun analyze(files: List<File>) {
        if (worker != null || files.isEmpty()) return
        val inputs = files.distinctBy { it.absolutePath }
        path.text = inputs.first().absolutePath
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
                        Analysis(file, ModHeapEstimator.estimate(file) { message ->
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
                    val failures = (0 until results.size()).count { results.get(it).estimate == null }
                    "分析完成：${results.size() - failures} 个成功，$failures 个失败"
                }
            }
        }
        worker!!.execute()
    }

    fun showEstimate(file: File, estimate: ModHeapEstimate) {
        results.addElement(Analysis(file, estimate))
        list.selectedIndex = results.size() - 1
        status.text = "分析完成：1 个成功，0 个失败"
    }

    private fun display(value: Analysis?) {
        selected = value
        model.rowCount = 0
        filter.text = ""
        val estimate = value?.estimate
        heading.text = value?.file?.name ?: "等待分析"
        sourcePath.text = value?.file?.absolutePath ?: "支持 .rwmod / .zip / .ini 和模组目录"
        sourcePath.toolTipText = value?.file?.absolutePath
        metrics.forEach { it.value.text = "—" }
        report.text = value?.error ?: "选择文件、目录或拖入模组，开始分析。\n\n支持多文件批量分析，点左侧列表查看各个模组的结果。"
        warnings.text = value?.error ?: "提示将在分析后显示。"
        budget.text = "静态估算不包含进程其他开销"
        setExportEnabled(estimate != null)
        if (estimate == null) return
        metrics[0].value.text = if (estimate.unitCount == 0) "无法确认" else formatBytes(estimate.heapBytes)
        metrics[1].value.text = estimate.unitCount.toString()
        metrics[2].value.text = estimate.logicNodes.toString()
        metrics[3].value.text = formatBytes(estimate.imageAccountedBytes)
        val percent = estimate.heapBytes * 100.0 / ModHeapEstimator.ANDROID_LARGE_HEAP_BYTES
        budget.text = "512 MiB 参考预算占比：%.2f%% · 新旧同规模单位表并存：%s".format(
            java.util.Locale.ROOT, percent, formatBytes(estimate.heapBytes * 2))
        if (estimate.warnings.isNotEmpty()) budget.text = "${budget.text} · ${estimate.warnings.size} 项提示"
        if (estimate.unitCount == 0) budget.text = "未找到可加载单位，不能当作零占用；请查看分析提示。"
        // 一次通知排序器，避免大量单位逐行插入时重复排序。
        estimate.units.forEach {
            model.dataVector.add(java.util.Vector<Any>(listOf<Any>(it.name, it.fileName, it.heapBytes, it.logicNodes, it.childSections, it.memoryVariables)))
        }
        model.fireTableDataChanged()
        sorter.sortKeys = listOf(RowSorter.SortKey(2, SortOrder.DESCENDING))
        report.text = estimate.formatReport()
        report.caretPosition = 0
        warnings.text = (estimate.warnings.ifEmpty { listOf("未发现读取或继承解析问题。") } + listOf(
            "这是静态模型，尚未用实测堆快照校准；表达式、共享对象和运行平台会造成偏差。",
            "单位表并存只是参考，不包含解析临时对象，不能当作真实峰值。",
            "包内贴图按宽 × 高 × 8 计数，包含未引用的图片，不包含音频。",
            "不执行游戏脚本；变量替换、小节继承及部分引擎语法未完整模拟。"
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
        val estimate = result.estimate ?: return
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
            output.writeText(if (csv) "\uFEFF" + estimate.unitCsv() else "来源：${result.file.absolutePath}\n\n${estimate.formatReport()}\n", Charsets.UTF_8)
            status.text = "已导出：${output.absolutePath}"
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "导出失败：${e.message}", "导出失败", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun setBusy(busy: Boolean) {
        listOf<JComponent>(selectFiles, selectDirectory, run, path).forEach { it.isEnabled = !busy }
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

internal fun ModHeapEstimate.unitCsv(): String = buildString {
    append("单位名称,定义文件,估算堆字节,逻辑节点,小节对象,memory变量\r\n")
    for (unit in units) {
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        append(listOf(cell(unit.name), cell(unit.fileName), unit.heapBytes.toString(), unit.logicNodes.toString(), unit.childSections.toString(), unit.memoryVariables.toString()).joinToString(","))
        append("\r\n")
    }
}
