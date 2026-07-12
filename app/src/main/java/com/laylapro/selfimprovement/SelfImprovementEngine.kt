package com.laylapro.selfimprovement

import com.laylapro.learning.LearningSystem
import com.laylapro.learning.NegativeFeedbackLog
import com.laylapro.reflection.ReflectionResult

/**
 * Self-Improvement Engine — Этап 2, пункт 7.
 *
 * Единственная ответственность: превратить [ReflectionResult] (диагноз "что пошло не
 * так") в РЕАЛЬНОЕ изменение поведения системы. Намеренно переиспользует уже
 * существующий [LearningSystem] (Этап 1) как единственный канал воздействия на
 * системный промпт — вместо того чтобы изобретать второй, параллельный механизм
 * "патчей промпта". Это отличается от [com.laylapro.agent.meta.LearningManager]
 * (Этап 1): тот сглаживает численный бонус к выбору АГЕНТА, этот — меняет содержимое
 * промпта для БУДУЩИХ ответов (два разных рычага, оба нужны, не дублируют друг друга).
 */
interface SelfImprovementEngine {
    suspend fun improve(reflection: ReflectionResult, context: String)
}

class LearningSystemSelfImprovement(
    private val learningSystem: LearningSystem,
    /** Ниже этого порога качество считается "достаточно плохим, чтобы стоило запомнить урок". */
    private val qualityThreshold: Float = 0.5f,
) : SelfImprovementEngine {

    override suspend fun improve(reflection: ReflectionResult, context: String) {
        if (reflection.quality >= qualityThreshold) return // всё было приемлемо — улучшать нечего
        val suggestion = reflection.suggestion ?: return

        learningSystem.logNegativeFeedback(
            NegativeFeedbackLog(
                userCorrection = suggestion,
                failedActionId = context,
                contextSnapshot = reflection.issues.joinToString("; ").ifBlank { "(без деталей)" },
            )
        )
    }
}
