package com.laylapro.agent

import com.laylapro.core.AICore
import com.laylapro.logging.Logger

/**
 * Специализированный агент общего назначения — диалог, рассуждение, объяснения,
 * творческие и исследовательские запросы без явной необходимости что-то нажимать
 * на устройстве. Служит "базовым уровнем" (fallback): даёт умеренный fit практически
 * для любой цели, но проигрывает более специализированным агентам (см. [AutomationAgent])
 * там, где их специализация точно попадает в формулировку цели.
 */
class ConversationalAgent(
    aiCore: AICore,
    goalManager: GoalManager,
    loggingManager: Logger,
) : BaseLoopAgent(aiCore, goalManager, loggingManager) {

    override val id: String = "conversational-agent"
    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.GENERAL_CONVERSATION,
        AgentCapability.CREATIVE_CONTENT,
    )

    override fun estimateFit(goal: Goal): Float = 0.5f // нейтральный fallback-уровень для любой цели

    override fun continuationPrompt(goal: Goal): String =
        "Продолжай выполнение цели \"${goal.description}\". Если цель уже полностью достигнута — " +
            "начни ответ со слова ЗАВЕРШЕНО."
}
