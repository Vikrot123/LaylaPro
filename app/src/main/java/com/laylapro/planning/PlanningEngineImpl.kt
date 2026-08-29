package com.laylapro.planning

import com.laylapro.router.ModelRouter
import com.laylapro.router.RoutingConstraints
import com.laylapro.router.TaskCategory
import kotlinx.coroutines.CancellationException

class PlanningEngineImpl(
    private val modelRouter: ModelRouter,
) : PlanningEngine {

    override suspend fun buildPlan(goal: String, availableTools: List<ToolDefinition>): TaskGraph {
        val systemPrompt = """
            Ты — модуль планирования (Planning Engine) внутри AI-ассистента LaylaPro.
            Если запрос пользователя требует реального действия на устройстве — вызови
            соответствующий инструмент. Если это обычный вопрос — не вызывай инструмент.
            Вызов инструмента не означает разрешение пользователя на опасное действие:
            execution layer отдельно применяет safety/confirmation policy.
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
            throw e
        } catch (e: Exception) {
            null
        }

        val toolSteps = response?.toolCalls?.mapNotNull { call ->
            val entry = ToolCatalog.byName(call.toolName) ?: return@mapNotNull null
            TaskStep(
                id = "tool-${call.id}",
                moduleName = entry.moduleName,
                action = entry.action,
                params = call.input,
                requiresUserConfirmation = entry.requiresUserConfirmation,
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

        return TaskGraph(
            steps = toolSteps + TaskStep(
                id = "step-respond",
                moduleName = "ConversationEngine",
                action = "respond",
                params = mapOf("goal" to goal),
                dependsOn = toolSteps.map { it.id },
            )
        )
    }

    override suspend fun modifyPlanOnFailure(
        failedStepId: String,
        error: String,
        currentGraph: TaskGraph,
    ): TaskGraph = currentGraph.copy(
        steps = currentGraph.steps.map { step ->
            if (step.id == failedStepId) step.copy(status = StepStatus.FAILED) else step
        }
    )
}
