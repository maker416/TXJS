/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.android

import android.app.Activity
import android.graphics.Color
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.recoveryModFiles
import io.github.rwpp.internalModDir
import io.github.rwpp.modDir
import kotlinx.coroutines.*
import java.io.File

/** Owned by the process, not the Activity: rotation/navigation cannot start a second engine init. */
internal object AndroidResourceStartup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Deferred<Result<Unit>>? = null
    @Volatile var recoveryChoiceMade = false
    val started: Boolean get() = synchronized(this) { task != null }

    suspend fun initialize(action: () -> Unit): Result<Unit> {
        val current = synchronized(this) {
            task ?: scope.async {
                runCatching {
                    ModLoadSafety.run("startup", action)
                    gameLoaded = true
                }
            }.also { task = it }
        }
        return current.await()
    }
}

/** No engine, image decoding, mod parsing or Compose tree is needed to escape a failed startup. */
internal fun Activity.showResourceRecovery(onContinue: () -> Unit) {
    val previous = ModLoadSafety.previousFailure()
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(36, 24, 36, 24)
        setBackgroundColor(Color.rgb(25, 28, 30))
    }
    fun label(value: String) = TextView(this).apply {
        text = value; textSize = 18f; setTextColor(Color.WHITE)
        setPadding(0, 12, 0, 12); column.addView(this)
    }
    label("资源加载恢复")
    label(if (GameStartSafety.memoryExhausted || previous?.failure == "memory")
        "上次资源加载内存不足。请减少本轮启用的模组。文件和原有启用配置都会保留。"
        else "上次资源加载未完成。可能是加载失败或进程被关闭；可减少本轮启用的模组后再启动。")
    if (!previous?.mod.isNullOrBlank()) label("最后处理：${previous?.mod}")
    if (GameStartSafety.requiresRestart) {
        label("当前进程无法继续加载。退出后重新打开，将先进入本恢复页，不会自动重试。")
        column.addView(Button(this).apply {
            text = "退出，稍后重新打开"
            setOnClickListener { finishAffinity(); kotlin.system.exitProcess(0) }
        })
    } else {
        label("以下选择仅用于本次启动；默认不加载自定义模组。")
        val choices = recoveryModFiles(listOf(File(modDir), File(internalModDir))).map { file ->
            file to CheckBox(this).apply {
                text = file.name; setTextColor(Color.WHITE); isChecked = false; column.addView(this)
            }
        }
        column.addView(Button(this).apply {
            text = "按所选组合启动"
            setOnClickListener {
                ModLoadSafety.useRecoverySelection(choices.associate { it.first.canonicalPath to it.second.isChecked })
                AndroidResourceStartup.recoveryChoiceMade = true
                onContinue()
            }
        })
        column.addView(Button(this).apply {
            text = "仍按原配置重试"
            setOnClickListener {
                AndroidResourceStartup.recoveryChoiceMade = true
                onContinue()
            }
        })
        column.addView(Button(this).apply {
            text = "退出"
            setOnClickListener { finishAffinity() }
        })
    }
    setContentView(ScrollView(this).apply { addView(column) })
}
