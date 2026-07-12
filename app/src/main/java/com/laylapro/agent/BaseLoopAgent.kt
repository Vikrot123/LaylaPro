package com.laylapro.agent

import com.laylapro.core.AICore
import com.laylapro.core.InputPayload
import com.laylapro.core.UserInput
import com.laylapro.logging.Logger

/**
 * Общая реализация многошагового цикла "мысль -> действие -> проверка" поверх
 * однопроходного [AICore.processInput] (который уже включает Reasoning/Planning/
 * Tool execution за один вызов — см. AICoreImpl). Вынесена в базовый класс, чтобы
 * [ConversationalAgent] и [AutomationAgent] не дублировали одну и ту же логику
 * ReAct-цикла — отличаются только эвристика fit-score и формулировка continuation-промпта
 * (Template Method pattern).
 */
abstract class BaseLoopAgent(
    private val aiCore: AICore,
    private val goalManager: GoalManager,
    private val loggingManager: Logger,
) : SpecializedAgent {

    /** Как именно попросить модель продолжать/явно завершить цель — специфично для агента. */
    protected abstract fun continuationPrompt(goal: Goal): String

    override suspend fun pursue(goal: Goal): GoalOutcome {
        var lastResponse = ""
        var steps = 0
        var currentPrompt = goal.description

        while (goal.status == GoalStatus.DELEGATED && steps < goal.maxSteps) {
            steps++
            val response = aiCore.processInput(UserInput(sessionId = goal.sessionId, payload = InputPayload.Text(currentPrompt)))
            lastResponse = response.text
            goalManager.recordStep(goal.id, lastResponse)

            if (response.isError) {
                goalManager.fail(goal.id, lastResponse)
                break
            }
            if (looksComplete(lastResponse)) {
                goalManager.complete(goal.id)
                break
            }
            currentPrompt = continuationPrompt(goal)
        }

        if (goal.status == GoalStatus.DELEGATED) {
            loggingManager.warning(id, "Цель ${goal.id} не завершена за ${goal.maxSteps} шагов — abandon")
            goalManager.abandon(goal.id)
        }

        return GoalOutcome(
            goalId = goal.id.toString(),
            handledBy = id,
            finalText = lastResponse,
            status = goal.status,
            stepsTaken = steps,
        )
    }

    private fun looksComplete(text: String): Boolean = text.trim().startsWith("ЗАВЕРШЕНО", ignoreCase = true)
}
