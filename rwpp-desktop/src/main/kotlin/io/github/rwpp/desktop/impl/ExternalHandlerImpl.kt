/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.core.Initialization
import io.github.rwpp.external.Extension
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.impl.BaseExternalHandlerImpl
import io.github.rwpp.io.unzipTo
import io.github.rwpp.logger
import io.github.rwpp.resourceOutputDir
import io.github.rwpp.utils.Reflect
import javassist.LoaderClassPath
import org.koin.core.annotation.Single
import java.io.File
import java.net.URLClassLoader
import javax.swing.JFileChooser

@Single(binds = [ExternalHandler::class, Initialization::class])
class ExternalHandlerImpl : BaseExternalHandlerImpl() {
    override fun enableResource(resource: Extension?) {
        if (resource?.config?.hasResource == false) return
        _usingResource = resource
        rebuildResourceOverlay()
    }

    override fun rebuildResourceOverlay() {
        val overlays = composeResourceOverlays()
        val outDir = File(resourceOutputDir)
        // 注意：resourceOutputDir 自带结尾 "/"，直接拼接 "_tmp" 会变成其子目录（resource_generated/_tmp），
        // renameTo 父目录必失败、回退复制又因 outDir 先删而丢失源——必须是同级目录
        val tmpDir = File(outDir.parentFile, outDir.name + "_tmp")

        if (overlays.isEmpty()) {
            tmpDir.deleteRecursively()
            outDir.deleteRecursively()
            return
        }

        // 先在临时目录完整构建（原版基线 + 覆盖层），完成后原子换名，
        // 避免引擎在重建中途读到半成品
        tmpDir.deleteRecursively()
        listOf("gui", "units", "tilesets", "music", "shaders").forEach {
            File("assets/$it").copyRecursively(File(tmpDir, it), true)
        }
        listOf("drawable", "raw").forEach {
            File("res/$it").copyRecursively(File(tmpDir, "res/$it"), true)
        }
        overlays.forEach { overlay ->
            if (overlay.isDirectory) {
                overlay.copyRecursively(tmpDir, true)
            } else {
                overlay.unzipTo(tmpDir)
            }
        }

        outDir.deleteRecursively()
        if (!tmpDir.renameTo(outDir)) {
            logger.warn("rename resource tmp dir failed, fallback to copy")
            tmpDir.copyRecursively(outDir, true)
            tmpDir.deleteRecursively()
        }
    }

    override fun openFileChooser(
        onProgress: ((FileChooseProgress) -> Unit)?,
        onChooseFile: (File) -> Unit
    ) {
        val fileChooser = JFileChooser()
        val result = fileChooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            onChooseFile(fileChooser.selectedFile)
        }
    }

    override fun loadJarToSystemClassPath(jar: File) {
        val url = jar.toURI().toURL()
        val classLoader = Thread.currentThread().contextClassLoader as URLClassLoader
        Reflect.callVoid(classLoader, "addURL", args = listOf(url))
    }
}
