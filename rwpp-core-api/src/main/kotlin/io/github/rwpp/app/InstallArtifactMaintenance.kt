/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Android 安装后清理可再生产物。只操作明确属于程序的目录，不清空应用数据、用户资源或浏览器 profile。
 * 私有产物与公共资源分别记录安装标记：后者须等存储权限就绪再处理。
 */
class InstallArtifactMaintenance(
    private val filesDirectory: File,
    private val dexDirectory: File,
    private val cacheDirectory: File,
) {
    fun prepareGeneratedArtifacts(installationId: String): Boolean = prepare(
        File(filesDirectory, ".rwpp-artifacts-install"), installationId
    ) {
        deleteTree(File(filesDirectory, "generated_lib"))
        deleteTree(dexDirectory)
        if (!dexDirectory.mkdirs() && !dexDirectory.isDirectory) {
            throw IOException("Cannot recreate DEX directory: $dexDirectory")
        }
    }

    fun prepareResources(installationId: String, resourceDirectory: File): Boolean = prepare(
        File(filesDirectory, ".rwpp-resources-install"), installationId
    ) {
        deleteTree(resourceDirectory)
        deleteTree(File(resourceDirectory.parentFile, resourceDirectory.name + "_tmp"))
    }

    /** 安装器异步读取 APK；正常启动保留最近交给安装器的文件，升级完成或超过一天才回收。 */
    fun cleanTemporaryFiles(afterInstall: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        cacheDirectory.listFiles().orEmpty().forEach { file ->
            val updateApk = file.name.startsWith("rwpp-update-") && file.name.endsWith(".apk")
            val abandonedUpload = file.name.startsWith("rwjs-browser-upload-")
            val temporaryGameJar = file.name.startsWith("android-game-lib") && file.name.endsWith(".jar")
            val temporaryPatchedJar = file.name.startsWith("temp-android-game-lib.jar") && file.name.endsWith(".jar")
            if (file.name.startsWith("rwpp-update-work-") || abandonedUpload || temporaryGameJar || temporaryPatchedJar ||
                (updateApk && (afterInstall || nowMillis - file.lastModified() >= 24L * 60 * 60 * 1000))) {
                deleteTree(file)
            }
        }
    }

    private fun prepare(marker: File, installationId: String, cleanup: () -> Unit): Boolean {
        require(installationId.isNotBlank())
        if (marker.isFile && marker.readText() == installationId) return false
        cleanup()
        // 清理完成才写标记；中途失败/进程终止后，下次启动继续清理。
        if (!filesDirectory.mkdirs() && !filesDirectory.isDirectory) {
            throw IOException("Cannot create installation marker directory: $filesDirectory")
        }
        val temporaryMarker = File.createTempFile("rwpp-install-", ".tmp", filesDirectory)
        try {
            temporaryMarker.writeText(installationId)
            try {
                Files.move(temporaryMarker.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryMarker.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporaryMarker.delete()
        }
        return true
    }

    private fun deleteTree(file: File) {
        val path = file.toPath()
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        // 不跟随符号链接，避免生成目录中的链接牵连用户原始资源。
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
