/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAndroidCompatibilityTest {
    private val libDir = File(System.getProperty("rwjs.test.libDir"))
    private val compatUrl = Class.forName("android.os.Build\$VERSION").protectionDomain.codeSource.location
    private val engineUrls = libDir.listFiles()!!.filter {
        it.extension == "jar" && it.name !in setOf("android.jar", "android-game-lib.jar", "android-platform-lib.jar")
    }.map { it.toURI().toURL() }

    @Test fun compatibilityJarContainsMissingTypesWithoutReplacingEngineOrJdkClasses() {
        ZipFile(File(compatUrl.toURI())).use { compat ->
            val entries = compat.entries().asSequence().map { it.name }.toSet()
            assertTrue("android/os/Build\$VERSION.class" in entries)
            assertFalse(entries.any { it.startsWith("java/") })
            assertFalse(entries.any { it.endsWith(".class") && it.startsWith("javax/") && !it.startsWith("javax/microedition/") })
            ZipFile(File(libDir, "game-lib.jar")).use { engine ->
                assertFalse(engine.entries().asSequence().any { it.name.endsWith(".class") && it.name in entries })
            }
        }
    }

    @Test fun actualDesktopEngineInitializesWithPackagedCompatibilityJar() {
        // 隔离 classloader 复现安装包缺类，再以完全相同的游戏库验证修复；不创建窗口或 GL 上下文。
        URLClassLoader(engineUrls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val failure = assertFailsWith<NoClassDefFoundError> {
                Class.forName("com.corrodinggames.rts.gameFramework.l", true, loader)
            }
            assertEquals("android/os/Build\$VERSION", failure.message)
        }
        URLClassLoader((engineUrls + compatUrl).toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertNotNull(Class.forName("com.corrodinggames.rts.gameFramework.l", true, loader))
            val point = Class.forName("android.graphics.Point", true, loader)
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(3, 7)
            assertEquals(3, point.javaClass.getField("a").getInt(point))
        }
    }
}
