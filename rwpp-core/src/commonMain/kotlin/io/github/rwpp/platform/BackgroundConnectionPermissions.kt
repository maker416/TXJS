/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable

/** 设置页与前台服务共用，避免只检查应用总通知开关而漏掉已关闭的保活通知渠道。 */
const val KEEP_ALIVE_NOTIFICATION_CHANNEL_ID = "rwjs_keepalive"

/** 展示实时授权摘要，用户点击整行后打开详情；不主动申请权限。 */
@Composable
expect fun BackgroundConnectionSettingsEntry()
