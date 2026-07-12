package com.laylapro.learning

/**
 * Модуль 6 (Слой 2) — накопление логов взаимодействия для few-shot обучения
 * "на лету" (см. ТЗ, Часть II, п.6).
 *
 * Реализация в ТЗ: при ошибках (отмена/исправление действия ИИ пользователем)
 * генерируется инкрементальный патч для системного промпта, сохраняемый
 * в skills_override.json и подгружаемый в Context Window.
 */
interface LearningSystem {
    suspend fun logNegativeFeedback(log: NegativeFeedbackLog)
    suspend fun getPromptOverride(): String
}

data class NegativeFeedbackLog(
    val userCorrection: String,
    val failedActionId: String,
    val contextSnapshot: String,
)

class LearningSystemImpl : LearningSystem {

    private val logs = mutableListOf<NegativeFeedbackLog>()

    override suspend fun logNegativeFeedback(log: NegativeFeedbackLog) {
        logs.add(log)
        // TODO: персистентно сохранять в skills_override.json (файловое хранилище приложения)
        // и периодически сжимать/резюмировать через LLM, когда логов становится много.
    }

    override suspend fun getPromptOverride(): String {
        if (logs.isEmpty()) return ""
        val recent = logs.takeLast(5)
        return buildString {
            appendLine("Учти прошлые исправления пользователя:")
            recent.forEach { appendLine("- ${it.userCorrection}") }
        }
    }
}
