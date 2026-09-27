/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 游戏会话生命周期控制器（单一事实来源）。
 *
 * 会话指一次「游戏局」的完整生命周期：单人遭遇战/沙盒、多人加入/开房、任务、回放。
 * 历史上会话的开始与结束散落在各 UI 回调里，退房清理更是用「延迟约 120ms 后断连」的
 * 写法等退出动画结束——新会话若在该窗口内建立，迟到的 disconnect 会把新会话当作旧会话
 * 拆掉（沙盒变遭遇战、加入被取消、主线程卡顿）。后改用 roomSessionEpoch 世代号补丁
 * 识别过期清理；本控制器用结构化并发从机制上消除该竞态：
 *
 * - 退房清理统一经 [scheduleClose] 登记，控制器持有其 [Job]；
 * - 新会话统一经 [beginSession] 开始，**先挂起等待未完成的退房清理跑完再返回**，
 *   保证旧会话一定先于新会话 setup 被完整拆除（DisconnectEvent 照常广播，
 *   模组同步等依赖该事件的清理不错位）；无挂起清理时立即返回，不引入任何延迟。
 *
 * [sessionPhase] 为信息性标记（Compose 可观察），供 UI 与后续步骤消费；
 * 引擎侧状态（房间/连接）仍以引擎为准，本控制器不持有镜像。
 */
object GameSessionController {

    enum class SessionPhase {
        /** 无会话（主菜单/列表页）。 */
        IDLE,

        /** 新会话建立中（退房等待已完成，正在加载地图/连接服务器）。 */
        STARTING,

        /** 房间会话活跃（等待房间或对局中）。 */
        IN_ROOM,

        /** 退房清理执行中（延迟等待 + cancelJoinServer/disconnect 拆除）。 */
        CLOSING,
    }

    /** 当前会话阶段（信息性；只读）。 */
    var sessionPhase by mutableStateOf(SessionPhase.IDLE)
        private set

    @Volatile
    private var closeJob: Job? = null

    /**
     * 开始新会话（单人遭遇战/沙盒、多人加入/开房、任务、回放）。
     * 若仍存在未完成的退房清理：挂起等待其完成（含延迟与断连拆除），随后才返回。
     * 必须在协程中调用（Main 或 IO 均可；Main 上为挂起等待而非阻塞，不会卡 UI）。
     */
    suspend fun beginSession() {
        closeJob?.join()
        sessionPhase = SessionPhase.STARTING
    }

    /** 房间视图已打开（进房/开房成功）：标记会话活跃。 */
    fun onRoomOpened() {
        sessionPhase = SessionPhase.IN_ROOM
    }

    /**
     * 会话被外部结束（被踢回列表、引擎断连、房内同步取消退房等未经 [scheduleClose] 的路径）：
     * 仅同步阶段标记，引擎侧拆除由触发方自行负责。
     */
    fun onExternalSessionEnd() {
        sessionPhase = SessionPhase.IDLE
    }

    /**
     * 登记一次退房清理（房间退出回调专用）。
     * [action]（延迟等待 + cancelJoinServer + onBanUnits + disconnect）在 [scope]
     *（应为 Main 作用域）中执行；防御性地取消上一次未完成的登记。
     */
    fun scheduleClose(scope: CoroutineScope, action: suspend () -> Unit) {
        closeJob?.cancel()
        sessionPhase = SessionPhase.CLOSING
        closeJob = scope.launch {
            try {
                action()
            } finally {
                // beginSession 的 join 在本 Job 完成后才返回，随后置 STARTING，不会互相覆盖
                if (sessionPhase == SessionPhase.CLOSING) sessionPhase = SessionPhase.IDLE
            }
        }
    }
}
