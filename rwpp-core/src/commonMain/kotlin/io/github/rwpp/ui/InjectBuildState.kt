/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

enum class StepStatus {
    Pending,
    Running,
    Done,
    Failed,
}

enum class InjectBuildStep {
    Prepare,
    ApplyInject,
    CompileDex,
}

enum class InjectBuildOverall {
    Idle,
    Running,
    Success,
    Failed,
}

data class InjectBuildUiState(
    val steps: Map<InjectBuildStep, StepStatus> = InjectBuildStep.entries.associateWith { StepStatus.Pending },
    val overall: InjectBuildOverall = InjectBuildOverall.Idle,
    val errorSummary: String? = null,
) {
    companion object {
        fun starting(): InjectBuildUiState = InjectBuildUiState().startBuild()
    }

    fun startBuild(): InjectBuildUiState = copy(
        overall = InjectBuildOverall.Running,
        errorSummary = null,
        steps = InjectBuildStep.entries.associateWith { step ->
            if (step == InjectBuildStep.Prepare) StepStatus.Running else StepStatus.Pending
        },
    )

    fun prepareDone(): InjectBuildUiState = copy(
        steps = steps + mapOf(
            InjectBuildStep.Prepare to StepStatus.Done,
            InjectBuildStep.ApplyInject to StepStatus.Running,
        ),
    )

    fun applyDone(): InjectBuildUiState = copy(
        steps = steps + mapOf(
            InjectBuildStep.ApplyInject to StepStatus.Done,
            InjectBuildStep.CompileDex to StepStatus.Running,
        ),
    )

    fun buildSuccess(): InjectBuildUiState = copy(
        overall = InjectBuildOverall.Success,
        steps = steps + mapOf(InjectBuildStep.CompileDex to StepStatus.Done),
    )

    fun buildFailed(error: Throwable): InjectBuildUiState {
        val runningStep = steps.entries.firstOrNull { it.value == StepStatus.Running }?.key
        val updatedSteps = if (runningStep != null) {
            steps + (runningStep to StepStatus.Failed)
        } else {
            steps
        }
        return copy(
            overall = InjectBuildOverall.Failed,
            steps = updatedSteps,
            errorSummary = error.message?.take(200) ?: error::class.simpleName,
        )
    }
}
