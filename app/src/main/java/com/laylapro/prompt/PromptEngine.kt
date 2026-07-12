package com.laylapro.prompt

import com.laylapro.core.ToolExecutionResult
import com.laylapro.emotion.EmotionState
import com.laylapro.memory.MemoryEntry
import com.laylapro.personality.PersonalityEngine
import com.laylapro.planning.TaskStep

/**
 * Модуль 11 (Prompt Engine) — Этап 1 архитектуры LaylaPro AI OS.
 *
 * До этого модуля сборка системного промпта была размазана внутри [com.laylapro.core.AICoreImpl]
 * (см. отчёт по анализу Layla, §10.6: "дублирование prompt-templating логики" — ровно та
 * ошибка, которую мы здесь целенаправленно устраняем). Теперь это ЕДИНСТВЕННОЕ место,
 * где текст системного промпта собирается из кусков (интент, эмоция, память, результаты
 * инструментов, патчи самообучения) — и AICoreImpl, и любой будущий Agent Framework
 * вызывают один и тот же [PromptEngine], так что шаблон не может разойтись между сценариями.
 */
data class PromptContext(
    val predictedIntent: String,
    val confidence: Float,
    val emotion: EmotionState,
    val learningOverride: String = "",
    val memoryContext: List<MemoryEntry> = emptyList(),
    val toolResults: Map<String, ToolExecutionResult> = emptyMap(),
    val actionableSteps: List<TaskStep> = emptyList(),
    /**
     * Этап 2, пункт 4 (RAG Engine): фрагменты из долгосрочной базы знаний, найденные
     * по семантической/лексической близости к запросу — НЕ то же самое, что
     * [memoryContext] (история ЭТОЙ сессии по Recency+Importance). Явно отдельное поле,
     * чтобы модель понимала разницу между "что было в разговоре" и "что мы вообще знаем".
     */
    val ragContext: List<String> = emptyList(),
)

interface PromptEngine {
    fun buildSystemPrompt(context: PromptContext): String
}

class PromptEngineImpl(private val personalityEngine: PersonalityEngine) : PromptEngine {

    override fun buildSystemPrompt(context: PromptContext): String {
        val basePrompt = buildString {
            appendLine("Определённое намерение пользователя: ${context.predictedIntent} (уверенность ${context.confidence}).")
            appendLine(
                "Эмоциональное состояние пользователя (валентность/активация): " +
                    "${context.emotion.valence} / ${context.emotion.arousal}."
            )
            if (context.learningOverride.isNotBlank()) appendLine(context.learningOverride)
            if (context.memoryContext.isNotEmpty()) {
                appendLine("Релевантный контекст из памяти текущей сессии:")
                context.memoryContext.forEach { appendLine("- ${it.rawText}") }
            }
            if (context.ragContext.isNotEmpty()) {
                appendLine("Релевантные знания из долгосрочной базы знаний (могут быть из других сессий):")
                context.ragContext.forEach { appendLine("- $it") }
            }
            if (context.toolResults.isNotEmpty()) {
                appendLine("Результаты выполненных на устройстве действий (учти их в ответе, не выдумывай другие):")
                context.actionableSteps.forEach { step ->
                    val result = context.toolResults[step.id]
                    val status = if (result?.success == true) "успех" else "ошибка"
                    appendLine("- ${step.moduleName}.${step.action}(${step.params}) -> $status: ${result?.output}")
                }
            }
        }
        return personalityEngine.injectPersonality(basePrompt)
    }
}
