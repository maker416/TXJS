/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import javassist.ClassPool
import javassist.LoaderClassPath
import javassist.expr.ExprEditor
import javassist.expr.MethodCall
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiplayerNotificationWiringTest {
    @Test
    fun chatVisibilityUsesTheApplicationLifecycleInsteadOfTheLegacyGameView() {
        val pool = ClassPool(true).apply {
            appendClassPath(LoaderClassPath(javaClass.classLoader))
            appendClassPath(File("../lib/android-game-lib.jar").absolutePath)
        }
        val calls = mutableSetOf<String>()
        pool.get("io.github.rwpp.android.impl.inject.NetInject").getDeclaredMethod("notify2")
            .instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    calls.add("${call.className}.${call.methodName}")
                }
            })
        assertTrue("io.github.rwpp.android.BackgroundKeepAlive.isAppInForeground" in calls)
        assertTrue(calls.none { it.startsWith("com.corrodinggames.rts.appFramework.") },
            "Compose room visibility must not depend on the vanilla Activity/View lifecycle")
    }

    @Test
    fun allThreeNotificationsCallTheSameReturnEntry() {
        val pool = ClassPool(true).apply {
            appendClassPath(LoaderClassPath(javaClass.classLoader))
            appendClassPath(File("../lib/android-game-lib.jar").absolutePath)
        }
        for ((className, methodName) in listOf(
            "io.github.rwpp.android.KeepAliveService" to "buildNotification",
            "io.github.rwpp.android.impl.inject.NetInject" to "notify1",
            "io.github.rwpp.android.impl.inject.NetInject" to "notify2",
        )) {
            var sharedEntries = 0
            val unsafeEntries = mutableListOf<String>()
            pool.get(className).getDeclaredMethod(methodName).instrument(object : ExprEditor() {
                override fun edit(call: MethodCall) {
                    if (call.className == "io.github.rwpp.android.MultiplayerNotificationActivity\$Companion" &&
                        call.methodName == "contentIntent") sharedEntries++
                    if (call.methodName == "getLaunchIntentForPackage" ||
                        call.className == "android.app.PendingIntent" && call.methodName == "getActivity") {
                        unsafeEntries.add("${call.className}.${call.methodName}")
                    }
                }
            })
            assertEquals(1, sharedEntries, "$className.$methodName must use the shared entry")
            assertEquals(emptyList(), unsafeEntries, "$className.$methodName bypasses the shared entry")
        }
    }

    @Test
    fun returnActivityKeepsTheApplicationTaskAffinityAndNeverClearsTheStack() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val entry = (0 until activities.length).map { activities.item(it).attributes }
            .single { it.getNamedItem("android:name")?.nodeValue == MultiplayerNotificationActivity::class.java.name }
        assertEquals("false", entry.getNamedItem("android:exported")?.nodeValue)
        assertEquals("@android:style/Theme.NoDisplay", entry.getNamedItem("android:theme")?.nodeValue)
        for (attribute in listOf("android:taskAffinity", "android:launchMode", "android:clearTaskOnLaunch",
            "android:finishOnTaskLaunch", "android:noHistory")) {
            assertNull(entry.getNamedItem(attribute), "Unexpected task override: $attribute")
        }
    }
}
