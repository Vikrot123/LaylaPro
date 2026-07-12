package com.laylapro.consolidation

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.knowledge.KnowledgeIndex
import com.laylapro.memory.MemorySystem
import com.laylapro.router.ModelRouter
import com.laylapro.router.TaskCategory
import com.laylapro.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Memory Consolidation — Этап 2, пункт 5.
 *
 * Отвечает ровно на тот вопрос, который [KnowledgeIndex] намеренно оставляет без
 * ответа (см. его KDoc): "что из недавней памяти сессии ДОСТОЙНО попасть в
 * долгосрочный индекс". В отличие от [com.laylapro.memory.MemorySystem.rememberExplicit]
 * (явный жест пользователя "запомни это") — это АВТОМАТИЧЕСКОЕ, но осторожное
 * извлечение durable-фактов из разговора, которое пользователь не запрашивал явно.
 */
interface MemoryConsolidation {
    /** Возвращает извлечённые факты (для логов/тестов) — пустой список, если консолидировать нечего. */
    suspend fun consolidate(sessionId: String): List<String>
}

/**
 * Вызывает модель ТОЛЬКО когда накопилось достаточно контекста ([minEntriesToTrigger]) —
 * иначе каждое сообщение стоило бы лишнего API-вызова без реальной пользы (та же
 * логика экономии, что и в [com.laylapro.agent.SpecializedAgent.estimateFit] —
 * не звать LLM там, где можно обойтись дешёвой проверкой).
 */
class LlmMemoryConsolidation(
    private val memorySystem: MemorySystem,
    private val knowledgeIndex: KnowledgeIndex,
    private val modelRouter: ModelRouter,
    private val minEntriesToTrigger: Int = 6,
) : MemoryConsolidation {

    override suspend fun consolidate(sessionId: String): List<String> {
        val recent = memorySystem.queryRelevantContext(sessionId, limit = 20)
        if (recent.size < minEntriesToTrigger) return emptyList()

        val systemPrompt = """
            Ты — модуль Memory Consolidation внутри AI-ассистента LaylaPro.
            Просмотри фрагмент разговора и выдели НЕ БОЛЕЕ 3 долгосрочных фактов о
            пользователе или мире, которые стоит помнить и после конца сессии
            (предпочтения, важные даты, устойчивые обстоятельства). Если таких фактов
            нет — верни пустой ответ. Каждый факт — с новой строки, без нумерации и
            вводных фраз.
        """.trimIndent()

        val response = try {
            val routing = modelRouter.route(TaskCategory.SUMMARIZATION)
            routing.apiLayer.complete(
                systemPrompt = systemPrompt,
                userMessage = recent.joinToString("\n") { it.rawText },
                maxTokens = 300,
            )
        } catch (e: CancellationException) {
            throw e // Патч 1: не проглатывать отмену корутины вместе с обычными ошибками
        } catch (e: Exception) {
            return emptyList() // Деградация: недоступен движок для суммаризации — не консолидируем, не роняем вызывающий код
        }

        val facts = response.lines().map { it.trim() }.filter { it.length > 10 }
        facts.forEach { fact ->
            knowledgeIndex.index(fact, metadata = mapOf("source" to "consolidation", "sessionId" to sessionId))
        }
        return facts
    }
}

/**
 * Реактивная обёртка (тот же паттерн, что [com.laylapro.agent.meta.PerformanceAnalyzer] и
 * другие подписчики Event Bus): консолидация запускается САМА при закрытии сессии,
 * а не по прямому вызову из [com.laylapro.runtime.RuntimeManager] — слабая связанность.
 */
class MemoryConsolidationService(
    scope: CoroutineScope,
    private val consolidation: MemoryConsolidation,
    private val loggingManager: Logger,
) {
    init {
        EventBus.events.filterIsInstance<CoreEvent.SessionClosed>()
            .onEach { event ->
                val facts = runCatching { consolidation.consolidate(event.sessionId) }.getOrDefault(emptyList())
                if (facts.isNotEmpty()) {
                    loggingManager.info("MemoryConsolidationService", "Законсолидировано ${facts.size} факт(ов) из сессии ${event.sessionId}")
                }
            }
            .launchIn(scope)
        // TODO: также триггерить на CoreEvent.MissionCompleted, как только это событие
        // будет обогащено sessionId (сейчас несёт только missionId, см. CoreEvent.kt) —
        // потребует либо расширения события, либо поиска через MissionManager.get(missionId).
    }
}
