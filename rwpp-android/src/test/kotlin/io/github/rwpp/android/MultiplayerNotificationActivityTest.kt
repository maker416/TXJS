/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29, 34],
    application = Application::class,
    manifest = Config.NONE,
)
class MultiplayerNotificationActivityTest {
    @Before
    fun setUp() {
        gameLoaded = true
    }

    @After
    fun tearDown() {
        gameLoaded = false
        isGaming = false
        isReturnToBattleRoom = false
    }

    @Test
    fun notificationUsesOneImmutableActivityIntentWithoutResettingTheTask() {
        val context = RuntimeEnvironment.getApplication()
        val pending = MultiplayerNotificationActivity.contentIntent(context)
        val shadow = shadowOf(pending)
        val intent = shadow.savedIntent

        assertEquals(MultiplayerNotificationActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.action)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertEquals(pending, MultiplayerNotificationActivity.contentIntent(context))
        pending.send()
        assertEquals(intent.component, shadowOf(context).nextStartedActivity.component)
    }

    @Test
    fun warmReturnOnlyFinishesItselfInBothRoomAndMatch() {
        for (inMatch in listOf(false, true)) {
            isGaming = inMatch
            isReturnToBattleRoom = false
            val controller = Robolectric.buildActivity(MultiplayerNotificationActivity::class.java)
            shadowOf(controller.get()).setIsTaskRoot(false)
            controller.create()
            val activity = controller.get()

            assertFalse(activity.isTaskRoot)
            assertTrue(activity.isFinishing)
            // 特别防止重走 LoadingScreen -> MainActivity(CLEAR_TOP)，把对局误作退出处理。
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(inMatch, isGaming)
            assertFalse(isReturnToBattleRoom)
            controller.destroy()
        }
    }

    @Test
    fun lostProcessStartsInitializationBeforeRevealingAnyStaleGameActivity() {
        gameLoaded = false
        assertStartsInitialization()
    }

    @Test
    fun missingTaskStartsInitializationEvenIfEngineIsLoaded() {
        assertStartsInitialization(taskRoot = true)
    }

    private fun assertStartsInitialization(taskRoot: Boolean = false) {
        val controller = Robolectric.buildActivity(MultiplayerNotificationActivity::class.java)
        shadowOf(controller.get()).setIsTaskRoot(taskRoot)
        controller.create()
        val activity = controller.get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(LoadingScreen::class.java.name, next.component?.className)
        assertEquals(0, next.flags)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.destroy()
    }
}
