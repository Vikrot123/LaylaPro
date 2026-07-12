package com.laylapro.agent.meta

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Failure Analyzer — вторая из пяти meta-частей: в отличие от [PerformanceAnalyzer]
 * (считает КОЛИЧЕСТВО успехов/провалов), эта категоризирует ПРИЧИНЫ провалов и ищет
 * повторяющиеся паттерны — например, один и тот же агент систематически проваливается
 * с одной и той же категорией ошибки, что сигнализирует о завышенном estimateFit для
 * этого класса целей. Полностью реактивен — узнаёт о провалах только через Event Bus.
 */
enum class FailureCategory { NO_AGENT_AVAILABLE, STEP_LIMIT_EXCEEDED, TOOL_EXECUTION_ERROR, MODEL_ROUTING_ERROR, OTHER }

class FailureAnalyzer(scope: CoroutineScope, private val patternThreshold: Int = 3) {

    private val occurrences = ConcurrentHashMap<Pair<String, FailureCategory>, AtomicInteger>()

    init {
        EventBus.events.filterIsInstance<CoreEvent.GoalFailed>()
            .onEach { event ->
                val agentId = event.agentId ?: "unknown"
                val category = categorize(event.reason)
                val count = occurrences.getOrPut(agentId to category) { AtomicInteger(0) }.incrementAndGet()
                if (count >= patternThreshold && count % patternThreshold == 0) {
                    EventBus.tryPublish(CoreEvent.FailurePatternDetected(agentId, category.name, count))
                }
            }
            .launchIn(scope)
    }

    private fun categorize(reason: String): FailureCategory = when {
        reason.contains("Нет подходящего агента", ignoreCase = true) ||
            reason.contains("Нет ни одного зарегистрированного", ignoreCase = true) -> FailureCategory.NO_AGENT_AVAILABLE
        reason.contains("лимит шагов", ignoreCase = true) -> FailureCategory.STEP_LIMIT_EXCEEDED
        reason.contains("ToolExecutor", ignoreCase = true) || reason.contains("инструмент", ignoreCase = true) -> FailureCategory.TOOL_EXECUTION_ERROR
        reason.contains("движка", ignoreCase = true) || reason.contains("API", ignoreCase = true) -> FailureCategory.MODEL_ROUTING_ERROR
        else -> FailureCategory.OTHER
    }

    fun occurrencesFor(agentId: String, category: FailureCategory): Int = occurrences[agentId to category]?.get() ?: 0

    fun allCategoriesFor(agentId: String): Map<FailureCategory, Int> =
        FailureCategory.entries.associateWith { occurrences[agentId to it]?.get() ?: 0 }.filterValues { it > 0 }
}
