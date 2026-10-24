/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.GameStartSafety

/** Deliberately independent of thumbnails, mod enumeration and a working engine. */
@Composable
fun ResourceLoadFailureScreen(onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xff191c1e)).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("资源加载已停止", color = Color.White)
        Text(if (GameStartSafety.memoryExhausted)
            "内存余量不足。当前进程不能继续开局或重载，请退出后重新打开，在恢复页减少本轮启用的模组。"
            else "资源加载失败。当前进程不能继续开局或重载，请退出后重新打开，在恢复页调整本轮模组。",
            color = Color.White)
        Text("模组文件和原有启用配置会保留，不会自动重试同一组合。", color = Color.White)
        Button(onClick = onExit) { Text("退出，手动重新打开") }
    }
}
