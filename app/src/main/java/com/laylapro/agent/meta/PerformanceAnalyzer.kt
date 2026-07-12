package com.laylapro.agent.meta

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

/**
 * Performance Analyzer — первая из пяти частей бывшего единого "MetaAgent"
 * (по требованию: Meta Supervisor / Performance Analyzer / Failure Analyzer /
 * Learning Manager / Strategy Optimizer вместо одного класса). Отвечает ТОЛЬКО
 * за сбор сырой описательной статистики (успех/провал/шаги) на агента — никаких
 * решений здесь не принимается, только измерение.
 *
 * Полностью реактивен: подписывается на [CoreEvent.GoalCompleted]/[CoreEvent.GoalFailed]
 * через Event Bus вместо того, чтобы [com.laylapro.agent.AgentCoordinator] вызывал его
 * напрямую — именно так достигается слабая связанность, о которой просили: координатор
 * ничего не знает о существовании этого класса.
 *
 * Компромисс по консистентности: обработка событий асинхронна (Kotlin Flow/coroutines),
 * поэтому статистика может обновиться на доли секунды позже, чем сам вызов агента
 * завершился. Для системы, рассчитанной на десятки/сотни агентов, это осознанный
 * выбор в пользу масштабируемости (eventual consistency), а не блокирующей
 * синхронизации на каждое событие.
 */
data class AgentPerformanceSnapshot(
    val agentId: String,
    var totalGoals: Int = 0,
    var completedGoals: Int = 0,
    var failedGoals: Int = 0,
    var totalStepsTaken: Int = 0,
) {
    val successRate: Float get() = if (totalGoals == 0) 0f else completedGoals.toFloat() / totalGoals
    val avgSteps: Float get() = if (totalGoals == 0) 0f else totalStepsTaken.toFloat() / totalGoals
}

class PerformanceAnalyzer(scope: CoroutineScope) {

    private val stats = ConcurrentHashMap<String, AgentPerformanceSnapshot>()

    init {
        EventBus.events.filterIsInstance<CoreEvent.GoalCompleted>()
            .onEach { record(it.handledBy, success = true, steps = it.stepsTaken) }
            .launchIn(scope)

        EventBus.events.filterIsInstance<CoreEvent.GoalFailed>()
            .onEach { event -> event.agentId?.let { record(it, success = false, steps = 0) } }
            .launchIn(scope)
    }

    private fun record(agentId: String, success: Boolean, steps: Int) {
        val snapshot = stats.getOrPut(agentId) { AgentPerformanceSnapshot(agentId) }
        synchronized(snapshot) {
            snapshot.totalGoals++
            if (success) snapshot.completedGoals++ else snapshot.failedGoals++
            snapshot.totalStepsTaken += steps
        }
        EventBus.tryPublish(CoreEvent.AgentPerformanceUpdated(agentId, snapshot.successRate, snapshot.avgSteps))
    }

    fun snapshotFor(agentId: String): AgentPerformanceSnapshot? = stats[agentId]
    fun allSnapshots(): List<AgentPerformanceSnapshot> = stats.values.toList()
}
