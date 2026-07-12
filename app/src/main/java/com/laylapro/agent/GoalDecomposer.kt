package com.laylapro.agent

/**
 * Разбивает формулировку составной цели ("сделай X и ещё Y") на независимые подцели,
 * каждая из которых может быть делегирована ОТДЕЛЬНОМУ агенту и выполнена параллельно
 * (см. AgentCoordinator). Интерфейс сделан подключаемым (OCP): дешёвая эвристика по
 * ключевым словам — это стартовая реализация; более точный декомпозер на основе LLM
 * подключается заменой [GoalDecomposer], без изменений в [AgentCoordinator].
 */
interface GoalDecomposer {
    /** Если декомпозиция не нужна/невозможна — возвращает список из одной и той же цели. */
    fun decompose(goal: Goal): List<Goal>
}

/**
 * Эвристический декомпозер: ищет соединительные маркеры ("и ещё", "затем", ";") и,
 * если находит, разбивает описание на части, СОХРАНЯЯ sessionId/priority/maxSteps
 * родителя и проставляя parentGoalId. Дополнительно эвристически угадывает
 * [Goal.requiredCapability] для каждой подцели по ключевым словам — не идеально, но
 * дёшево (без обращения к модели) и достаточно для сужения пула через
 * [CapabilityManager] в типичных случаях.
 */
object KeywordGoalDecomposer : GoalDecomposer {

    private val splitMarkers = listOf(" и ещё ", " и потом ", " затем ", "; ", " и также ", " а также ")

    private val capabilityKeywords: Map<AgentCapability, List<String>> = mapOf(
        AgentCapability.DEVICE_AUTOMATION to listOf(
            "открой", "включи", "выключи", "нажми", "настрой", "установи", "wifi", "bluetooth",
            "громкост", "яркост", "приложени", "экран", "будильник", "таймер",
        ),
        AgentCapability.RESEARCH_AND_MEMORY to listOf(
            "найди", "вспомни", "изучи", "поищи", "что ты знаешь", "напомни",
        ),
        AgentCapability.CODE_GENERATION to listOf(
            "код", "функци", "напиши программу", "debug", "баг", "скрипт",
        ),
        AgentCapability.CREATIVE_CONTENT to listOf(
            "придумай", "сочини", "напиши историю", "стих", "сценарий",
        ),
    )

    override fun decompose(goal: Goal): List<Goal> {
        val lower = goal.description
        for (marker in splitMarkers) {
            if (!lower.contains(marker, ignoreCase = true)) continue

            val parts = lower.split(marker, ignoreCase = true).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size <= 1) continue

            return parts.map { part ->
                Goal(
                    sessionId = goal.sessionId,
                    description = part,
                    maxSteps = goal.maxSteps,
                    priority = goal.priority,
                    missionId = goal.missionId,
                    parentGoalId = goal.id,
                    requiredCapability = guessCapability(part),
                )
            }
        }
        return listOf(goal)
    }

    private fun guessCapability(text: String): AgentCapability? {
        val lower = text.lowercase()
        return capabilityKeywords.entries
            .map { (capability, keywords) -> capability to keywords.count { lower.contains(it) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
