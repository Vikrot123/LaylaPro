package com.laylapro.planning

import com.laylapro.router.ModelRouter
import com.laylapro.router.RoutingConstraints
import com.laylapro.router.TaskCategory
import kotlinx.coroutines.CancellationException

/**
 * Реализация Planning Engine с настоящим Function Calling (Anthropic tool use),
 * теперь через [ModelRouter]: планирование требует именно tool-use, поэтому запрос
 * идёт с `RoutingConstraints(requiresToolUse = true)` — Model Router обязан выбрать
 * движок, который это умеет (сейчас единственный такой — облачный Claude).
 *
 * Если модель не запросила ни одного инструмента — возвращается тривиальный
 * план из одного шага "ответить пользователю через Conversation Engine".
 */
class PlanningEngineImpl(
    private val modelRouter: ModelRouter,
) : PlanningEngine {

    override suspend fun buildPlan(goal: String, availableTools: List<ToolDefinition>): TaskGraph {
        val systemPrompt = """
            Ты — модуль планирования (Planning Engine) внутри AI-ассистента LaylaPro.
            Если запрос пользователя требует реального действия на устройстве (переключить
            Wi-Fi, открыть настройки, нажать на элемент в другом приложении) — вызови
            соответствующий инструмент. Если это обычный вопрос или разговор — не вызывай
            ничего, просто ответь текстом.
        """.trimIndent()

        val response = try {
            val routing = modelRouter.route(TaskCategory.PLANNING, RoutingConstraints(requiresToolUse = true))
            routing.apiLayer.completeWithTools(
                systemPrompt = systemPrompt,
                userMessage = goal,
                tools = ToolCatalog.specs(),
                maxTokens = 400,
            )
        } catch (e: CancellationException) {
            throw e // Патч 1: не проглатывать отмену корутины вместе с обычными ошибками
        } catch (e: Exception) {
            null // Деградация: при ошибке Function Calling (или отсутствии подходящего движка) уходим в обычный чат-ответ
        }

        val toolSteps = response?.toolCalls?.mapNotNull { call ->
            val entry = ToolCatalog.byName(call.toolName) ?: return@mapNotNull null
            TaskStep(
                id = "tool-${call.id}",
                moduleName = entry.moduleName,
                action = entry.action,
                params = call.input,
            )
        }.orEmpty()

        if (toolSteps.isEmpty()) {
            return TaskGraph(
                steps = listOf(
                    TaskStep(
                        id = "step-1",
                        moduleName = "ConversationEngine",
                        action = "respond",
                        params = mapOf("goal" to goal),
                    )
                )
            )
        }

        // Финальный шаг "ответить пользователю" зависит от успешного выполнения всех
        // инструментов — AI Core соберёт их результаты в контекст перед генерацией ответа.
        val respondStep = TaskStep(
            id = "step-respond",
            moduleName = "ConversationEngine",
            action = "respond",
            params = mapOf("goal" to goal),
            dependsOn = toolSteps.map { it.id },
        )

        return TaskGraph(steps = toolSteps + respondStep)
    }

    override suspend fun modifyPlanOnFailure(
        failedStepId: String,
        error: String,
        currentGraph: TaskGraph,
    ): TaskGraph {
        val updatedSteps = currentGraph.steps.map { step ->
            if (step.id == failedStepId) step.copy(status = StepStatus.FAILED) else step
        }
        return currentGraph.copy(steps = updatedSteps)
    }
}
