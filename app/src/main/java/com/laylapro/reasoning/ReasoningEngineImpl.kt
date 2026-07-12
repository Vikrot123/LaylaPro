package com.laylapro.reasoning

import com.laylapro.memory.MemoryEntry
import com.laylapro.router.ModelRouter
import com.laylapro.router.TaskCategory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Реализация Reasoning Engine поверх [ModelRouter] — сама не завязана на конкретный
 * облачный/локальный движок; Model Router решает, чем классифицировать интент
 * (сейчас — облачный Claude, в будущем может быть лёгкая локальная модель, т.к.
 * классификация интента не требует топового качества, см. TaskCategory.REASONING
 * в Blueprint §7, Этап 6).
 */
class ReasoningEngineImpl(
    private val modelRouter: ModelRouter,
) : ReasoningEngine {

    @Serializable
    private data class IntentJson(
        val monologue: String,
        val intent: String,
        val confidence: Float,
        val tools: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun evaluateContext(
        prompt: String,
        memoryContext: List<MemoryEntry>,
    ): ReasoningResult {
        val memoryBlock = memoryContext.joinToString("\n") { "- ${it.rawText}" }
        val systemPrompt = """
            Ты — модуль рассуждений (Reasoning Engine) внутри AI-ассистента LaylaPro.
            Кратко проанализируй запрос пользователя и релевантный контекст памяти.
            Ответь СТРОГО в формате JSON без markdown-обрамления:
            {"monologue": "краткие внутренние размышления", "intent": "название интента", "confidence": 0.0..1.0, "tools": ["модуль1", "модуль2"]}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("Контекст памяти:")
            appendLine(memoryBlock.ifBlank { "(пусто)" })
            appendLine()
            appendLine("Запрос пользователя: $prompt")
        }

        return try {
            val routing = modelRouter.route(TaskCategory.REASONING)
            val raw = routing.apiLayer.complete(systemPrompt = systemPrompt, userMessage = userPrompt, maxTokens = 400)
            val parsed = json.decodeFromString<IntentJson>(extractJson(raw))
            ReasoningResult(
                internalMonologue = parsed.monologue,
                predictedIntent = parsed.intent,
                confidence = parsed.confidence.coerceIn(0f, 1f),
                requiredTools = parsed.tools,
            )
        } catch (e: CancellationException) {
            throw e // Патч 1: не проглатывать отмену корутины вместе с обычными ошибками
        } catch (e: Exception) {
            // Деградация: если модель не вернула валидный JSON (или нет доступного движка),
            // отдаём базовый результат — Reasoning Engine никогда не должен ронять весь запрос.
            ReasoningResult(
                internalMonologue = "Не удалось разобрать структурированный ответ: ${e.message}",
                predictedIntent = "general_chat",
                confidence = 0.4f,
            )
        }
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
