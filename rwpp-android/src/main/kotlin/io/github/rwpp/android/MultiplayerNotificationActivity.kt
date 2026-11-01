/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 三种联机通知共用的返回入口，不调用引擎，也不改变启动器导航和会话状态。 */
class MultiplayerNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NEW_TASK 按应用的默认 taskAffinity 找回原任务，再把本页压到栈顶。
        // 热返回只关闭自己，露出原来的房间/对局；不能启动主页或对对局页使用 CLEAR_TOP。
        if (!gameLoaded || isTaskRoot) {
            // 进程已丢失或任务已不存在时，才交给完整初始化流程处理。
            startActivity(Intent(this, LoadingScreen::class.java))
        }
        finish()
    }

    companion object {
        fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MultiplayerNotificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
