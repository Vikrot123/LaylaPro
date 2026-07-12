package com.laylapro.runtime

import com.laylapro.core.ToolExecutionResult
import com.laylapro.core.ToolExecutor
import com.laylapro.planning.TaskStep

/**
 * Мост между AI Core (который знает только про [ToolExecutor]) и Runtime Core
 * (единственный владелец [Dispatcher]). Шаги выполняются последовательно с учётом
 * dependsOn — для параллельных/более сложных графов используйте [WorkflowEngine] напрямую.
 */
class DispatcherToolExecutor(private val dispatcher: Dispatcher) : ToolExecutor {

    override suspend fun execute(steps: List<TaskStep>): Map<String, ToolExecutionResult> {
        val results = mutableMapOf<String, ToolExecutionResult>()

        for (step in steps) {
            val dependencyFailed = step.dependsOn.any { depId -> results[depId]?.success == false }
            if (dependencyFailed) {
                results[step.id] = ToolExecutionResult(false, "Пропущено: не выполнена зависимость (${step.dependsOn})")
                continue
            }

            val result = dispatcher.dispatch(
                Command(targetModule = step.moduleName, action = step.action, params = step.params)
            )
            results[step.id] = ToolExecutionResult(
                success = result.success,
                output = result.output?.toString() ?: result.error ?: "",
            )
        }

        return results
    }
}
