package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.core.ToolExecutionResult
import com.laylapro.core.ToolExecutor
import com.laylapro.planning.TaskStep

/**
 * AI Core -> Runtime execution bridge. A model-proposed action is not permission.
 * Steps marked as requiring confirmation fail closed until the UI confirmation
 * bridge supplies explicit approval in a future controlled execution flow.
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

            if (step.requiresUserConfirmation) {
                EventBus.tryPublish(CoreEvent.DangerousActionRequested(step.action, step.id))
                results[step.id] = ToolExecutionResult(
                    false,
                    "Действие '${step.action}' требует явного подтверждения пользователя и не было выполнено",
                )
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
