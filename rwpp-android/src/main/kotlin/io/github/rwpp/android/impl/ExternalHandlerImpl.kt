/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.content.Context
import android.content.Intent
import io.github.rwpp.R
import io.github.rwpp.android.PickFileAction
import io.github.rwpp.android.dexFolder
import io.github.rwpp.android.fileChooser
import io.github.rwpp.android.loadDex
import io.github.rwpp.android.pickFileActions
import io.github.rwpp.core.Initialization
import io.github.rwpp.external.Extension
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.impl.BaseExternalHandlerImpl
import io.github.rwpp.io.unzipTo
import io.github.rwpp.logger
import io.github.rwpp.resourceOutputDir
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.File
import java.util.zip.ZipFile


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
        val resourceList = listOf("units", "tilesets", "music", "shaders")
        resourceList.forEach {
            copyAssets(get(), it, File(tmpDir, it).absolutePath + "/")
        }

        val resList = listOf(
            R.drawable::class.java,
            R.raw::class.java
        )

        val resources = get<Context>().resources

        resList.forEach { clazz ->
            clazz.declaredFields.forEach { field ->
                runCatching {
                    val i = field.get(null) as Int
                    // openRawResource 仅适用于 res/raw；drawable（含 vector）会抛 NotFoundException
                    val bytes = resources.openRawResource(i).use { res -> res.readBytes() }
                    val fi = File(File(tmpDir, "res"), resources.getResourceFileName(i))
                    fi.parentFile!!.run { if (!exists()) mkdirs() }
                    if (!fi.exists()) fi.createNewFile()
                    fi.writeBytes(bytes)
                }.onFailure { e ->
                    logger.warn("skip app resource copy [${field.name}]: ${e.message}")
                }
            }
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
        pickFileActions += PickFileAction(onProgress, onChooseFile)
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("*/*") // 设置文件类型为任意类型
        intent.addCategory(Intent.CATEGORY_OPENABLE) // 添加可打开的文件分类
        fileChooser.launch(intent)
    }

    override fun loadJarToSystemClassPath(jar: File) {
        ZipFile(jar).use { zip ->
            // 查找所有DEX文件
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { entry ->
                    val match = Regex("classes(\\d*)\\.dex").find(entry.name)
                    val firstVal=match?.groupValues?.get(1);
                    if (firstVal?.isEmpty() == true) 0 else (firstVal?.toIntOrNull() ?: 0)
                }
                .toList()
            if (dexEntries.isEmpty()) {
                logger.warn("No DEX files found in JAR: ${jar.name}")
                return
            }

            logger.info("Found ${dexEntries.size} DEX files in JAR: ${jar.name}")

            // 提取并加载每个DEX文件
            dexEntries.forEach { dexEntry ->
                val targetFile = File(
                    dexFolder,
                    "${dexEntry.name.substringBefore('.')}-${jar.nameWithoutExtension}.dex"
                )

                zip.getInputStream(dexEntry).use { inputStream ->
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    targetFile.writeBytes(inputStream.readBytes())
                    targetFile.setReadOnly()

                    loadDex(get(), targetFile.absolutePath)
                }
            }
        }
        //private val exFilePicker = ExFilePicker()
    }
}
