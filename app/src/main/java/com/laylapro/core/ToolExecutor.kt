package com.laylapro.core

import com.laylapro.planning.TaskStep

/**
 * Абстракция, через которую AI Core просит выполнить шаги плана, полученные от
 * Planning Engine (device_control/android_integration и т.п.), не завися напрямую
 * от Runtime Core / Dispatcher (см. Том 98: "Никто не должен вызывать другой
 * модуль напрямую" — эта граница держит AICoreImpl независимым от реализации Dispatcher).
 */
fun interface ToolExecutor {
    suspend fun execute(steps: List<TaskStep>): Map<String, ToolExecutionResult>
}

data class ToolExecutionResult(val success: Boolean, val output: String)

/** Реализация-заглушка для случаев, когда Runtime Core ещё не поднят (юнит-тесты и т.п.). */
object NoOpToolExecutor : ToolExecutor {
    override suspend fun execute(steps: List<TaskStep>): Map<String, ToolExecutionResult> =
        steps.associate { it.id to ToolExecutionResult(false, "ToolExecutor не подключён") }
}
