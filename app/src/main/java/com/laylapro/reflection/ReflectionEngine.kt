package com.laylapro.reflection

import com.laylapro.agent.GoalManager
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.router.ModelRouter
import com.laylapro.router.TaskCategory
import com.laylapro.logging.Logger
import com.laylapro.selfimprovement.SelfImprovementEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection Engine — Этап 2, пункт 6.
 *
 * Отличие от [com.laylapro.agent.meta.FailureAnalyzer] (Этап 1): FailureAnalyzer
 * категоризирует провалы ДЁШЕВО, по строковым паттернам, без обращения к модели —
 * это годится для статистики на масштабе сотен агентов. Reflection Engine, напротив,
 * ДОРОГОЙ (реальный вызов LLM) целенаправленный самоанализ конкретного провала —
 * поэтому запускается ИЗБИРАТЕЛЬНО (см. [ReflectionService]: только на `GoalFailed`,
 * не на каждое сообщение чата), а не как общий механизм мониторинга.
 */
data class ReflectionResult(
    val quality: Float, // 0..1 — самооценка того, насколько цель была близка к успеху
    val issues: List<String>,
    val suggestion: String?,
)

interface ReflectionEngine {
    suspend fun reflect(goalDescription: String, outcome: String): ReflectionResult
}

class LlmReflectionEngine(private val modelRouter: ModelRouter) : ReflectionEngine {

    @Serializable
    private data class ReflectionJson(
        val quality: Float,
        val issues: List<String> = emptyList(),
        val suggestion: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun reflect(goalDescription: String, outcome: String): ReflectionResult {
        val systemPrompt = """
            Ты — Reflection Engine внутри AI-ассистента LaylaPro. Тебе показывают цель,
            которую пытался достичь один из специализированных агентов, и то, чем это
            закончилось. Оцени, насколько близко агент подошёл к успеху, и предложи ОДНО
            конкретное улучшение на будущее (или null, если улучшать нечего).
            Ответь СТРОГО JSON без markdown: {"quality": 0.0..1.0, "issues": ["..."], "suggestion": "..." или null}
        """.trimIndent()

        return try {
            val routing = modelRouter.route(TaskCategory.REASONING)
            val raw = routing.apiLayer.complete(
                systemPrompt = systemPrompt,
                userMessage = "Цель: $goalDescription\nИтог: $outcome",
                maxTokens = 300,
            )
            val parsed = json.decodeFromString<ReflectionJson>(extractJson(raw))
            ReflectionResult(parsed.quality.coerceIn(0f, 1f), parsed.issues, parsed.suggestion)
        } catch (e: CancellationException) {
            throw e // Патч 1: не проглатывать отмену корутины вместе с обычными ошибками
        } catch (e: Exception) {
            // Нейтральная деградация: не можем отрефлексировать — не блокируем и не искажаем
            // статистику (0.5 — "не знаем", а не "плохо"), в отличие от FailureAnalyzer,
            // который в это же время УЖЕ дёшево посчитал провал в своей статистике.
            ReflectionResult(quality = 0.5f, issues = emptyList(), suggestion = null)
        }
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}

/**
 * Реактивная обёртка: рефлексия запускается САМА на [CoreEvent.GoalFailed], а результат
 * сразу передаётся в [SelfImprovementEngine] — это НЕ через Event Bus (сознательное
 * решение, см. Blueprint Этапа 1, §7: Event Bus — для слабо связанных cross-cutting
 * подписчиков вроде World Model; здесь же "рефлексия -> улучшение" — линейный,
 * логически неразрывный конвейер из двух шагов, прямой вызов проще и ничего не теряет
 * в связанности, т.к. Self-Improvement Engine и так предназначен ТОЛЬКО для потребления
 * результатов Reflection Engine).
 */
class ReflectionService(
    scope: CoroutineScope,
    private val reflectionEngine: ReflectionEngine,
    private val selfImprovementEngine: SelfImprovementEngine,
    private val goalManager: GoalManager,
    private val loggingManager: Logger,
) {
    private val history = ConcurrentHashMap<String, ReflectionResult>()

    init {
        EventBus.events.filterIsInstance<CoreEvent.GoalFailed>()
            .onEach { event ->
                val goal = runCatching { goalManager.get(UUID.fromString(event.goalId)) }.getOrNull()
                val description = goal?.description ?: event.reason
                val result = reflectionEngine.reflect(description, "провал: ${event.reason}")
                history[event.goalId] = result
                loggingManager.info(
                    "ReflectionService",
                    "Рефлексия по цели ${event.goalId}: quality=${result.quality}, suggestion=${result.suggestion}",
                )
                selfImprovementEngine.improve(result, context = description)
            }
            .launchIn(scope)
    }

    fun latestFor(goalId: String): ReflectionResult? = history[goalId]
}
