/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.desktop

import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.recoveryModFiles
import io.github.rwpp.modDir
import java.io.File
import javax.swing.*
import kotlin.system.exitProcess

/** Runs before initializing either game resources or the Compose menu. Selection is a session override. */
internal fun chooseDesktopRecovery() {
    val failed = ModLoadSafety.previousFailure() ?: return
    val show = {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(JLabel("上次资源加载未完成。请选择本轮组合，文件和原启用配置不会被删除或覆盖。"))
        if (failed.mod.isNotEmpty()) panel.add(JLabel("最后处理：${failed.mod}"))
        val boxes = recoveryModFiles(listOf(File(modDir))).map { file ->
            file to JCheckBox(file.name, false).also(panel::add)
        }
        val scroll = JScrollPane(panel).apply { preferredSize = java.awt.Dimension(700, 420) }
        when (JOptionPane.showOptionDialog(null, scroll, "资源加载恢复", JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE, null, arrayOf("按所选组合启动", "按原配置重试", "退出"), "按所选组合启动")) {
            0 -> ModLoadSafety.useRecoverySelection(boxes.associate { it.first.canonicalPath to it.second.isSelected })
            1 -> Unit
            else -> exitProcess(0)
        }
    }
    if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeAndWait(show)
}

internal fun showDesktopResourceFailure(message: String) {
    SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(null,
            "$message\n当前进程不能继续加载。关闭后重新打开会先进入恢复页，可以减少本轮模组。",
            "资源加载已停止", JOptionPane.WARNING_MESSAGE)
        // Do not ask partially initialized game services to save or refresh their registries.
        exitProcess(0)
    }
}
