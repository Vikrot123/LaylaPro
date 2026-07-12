package com.laylapro.agent.meta

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

/**
 * Learning Manager — третья из пяти meta-частей: превращает сырую статистику
 * [PerformanceAnalyzer] в МЕДЛЕННО меняющийся сигнал доверия к агенту
 * (экспоненциальное скользящее среднее, а не мгновенная реакция на последний
 * результат) — единичная неудача не обрушивает выбор агента, но устойчивая
 * тенденция постепенно на него влияет. Это "обучение" в узком, честном смысле —
 * не переобучение модели, а адаптивное взвешивание уже существующих метрик.
 *
 * ADR-021: обновление сглаженного сигнала ([updateFromPerformance]) происходит
 * ТОЛЬКО реактивно — по подписке на [CoreEvent.AgentPerformanceUpdated], который
 * [PerformanceAnalyzer] публикует при КАЖДОМ генуинно новом результате
 * (`GoalCompleted`/`GoalFailed`). До этого исправления `StrategyOptimizer.
 * scoringMultiplier()` вызывал `updateFromPerformance` при каждом ЧТЕНИИ множителя
 * (то есть при каждом ранжировании агентов на каждую цель) — нарушение Command-Query
 * Separation, из-за которого EMA "поджималось" к одному и тому же значению много
 * раз без поступления новой информации, искусственно ускоряя сходимость сильнее,
 * чем предполагает архитектурная гарантия "медленного обучения" (находка
 * независимого аудита Этапов 1-2, M3). Теперь [biasFor] — чистый запрос без
 * побочных эффектов, а [updateFromPerformance] вызывается исключительно из
 * подписки ниже.
 */
class LearningManager(scope: CoroutineScope, private val smoothingFactor: Float = 0.2f) {

    private val bias = ConcurrentHashMap<String, Float>()

    init {
        EventBus.events.filterIsInstance<CoreEvent.AgentPerformanceUpdated>()
            .onEach { event -> updateFromPerformance(event.agentId, event.successRate) }
            .launchIn(scope)
    }

    /** Обновляет сглаженную оценку агента — вызывается ТОЛЬКО реактивной подпиской выше. */
    private fun updateFromPerformance(agentId: String, latestSuccessRate: Float) {
        bias.merge(agentId, latestSuccessRate) { old, new -> old + smoothingFactor * (new - old) }
    }

    /** 0.5..1.5 — множитель к estimateFit; 1.0 (нейтрально), если данных ещё нет. Чистый запрос, без побочных эффектов. */
    fun biasFor(agentId: String): Float {
        val b = bias[agentId] ?: return 1.0f
        return (0.5f + b).coerceIn(0.5f, 1.5f)
    }
}
