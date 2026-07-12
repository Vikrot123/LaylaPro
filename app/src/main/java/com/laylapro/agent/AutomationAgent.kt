package com.laylapro.agent

import com.laylapro.core.AICore
import com.laylapro.logging.Logger

/**
 * Специализированный агент для целей, требующих РЕАЛЬНЫХ действий на устройстве
 * (переключить настройку, открыть приложение, нажать элемент через Accessibility и т.д.)
 * — см. Device Controller / Android Automation модули. Отличается от [ConversationalAgent]
 * тем, что: (1) даёт высокий fit-score только когда формулировка цели прямо указывает
 * на действие с устройством, (2) в continuation-промпте явно требует подтверждения
 * результата каждого шага, а не просто "продолжай разговор".
 *
 * Пример добавления НОВОГО специализированного агента без изменения этого файла или
 * [AgentCoordinator]: `class ResearchAgent(...) : BaseLoopAgent(...) { ... }` с
 * `capabilities = setOf(AgentCapability.RESEARCH_AND_MEMORY)` и высоким estimateFit
 * для целей вида "найди", "вспомни", "изучи" — затем просто `agentRegistry.register(...)`.
 */
class AutomationAgent(
    aiCore: AICore,
    goalManager: GoalManager,
    loggingManager: Logger,
) : BaseLoopAgent(aiCore, goalManager, loggingManager) {

    override val id: String = "automation-agent"
    override val capabilities: Set<AgentCapability> = setOf(AgentCapability.DEVICE_AUTOMATION)

    // Эвристика оценки fit — простая и дешёвая (см. контракт estimateFit: без вызова LLM).
    private val actionKeywords = listOf(
        "открой", "включи", "выключи", "нажми", "настрой", "установи", "перейди",
        "wifi", "wi-fi", "bluetooth", "громкост", "яркост", "приложени", "экран",
        "будильник", "таймер", "уведомлени",
    )

    override fun estimateFit(goal: Goal): Float {
        val lower = goal.description.lowercase()
        val matches = actionKeywords.count { lower.contains(it) }
        return when {
            matches == 0 -> 0.2f // почти наверняка не автоматизация устройства
            matches == 1 -> 0.7f
            else -> 0.9f // несколько совпадений — почти наверняка серия действий на устройстве
        }
    }

    override fun continuationPrompt(goal: Goal): String =
        "Продолжай выполнять цель \"${goal.description}\" на устройстве. Явно подтверди результат " +
            "каждого выполненного действия. Если ВСЕ необходимые действия уже выполнены и подтверждены — " +
            "начни ответ со слова ЗАВЕРШЕНО."
}
