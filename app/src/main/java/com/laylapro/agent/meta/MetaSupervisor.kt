package com.laylapro.agent.meta

import com.laylapro.agent.AgentRegistry
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicInteger

/**
 * Meta Supervisor — пятая, верхняя часть: сама ничего не измеряет и не действует
 * напрямую, а ПЕРИОДИЧЕСКИ (раз в [analysisIntervalGoals] завершённых/провалённых
 * целей) прогоняет остальные четыре meta-компонента по всем зарегистрированным
 * агентам и публикует консолидированные рекомендации ([CoreEvent.MetaAgentSuggestion]).
 * Это и есть "анализирует эффективность, перераспределяет задачи, оптимизирует
 * стратегии" — но как оркестратор специализированных компонентов, а не как один
 * гигантский класс, отвечающий сразу за всё.
 */
class MetaSupervisor(
    scope: CoroutineScope,
    private val agentRegistry: AgentRegistry,
    private val performanceAnalyzer: PerformanceAnalyzer,
    private val failureAnalyzer: FailureAnalyzer,
    private val strategyOptimizer: StrategyOptimizer,
    private val analysisIntervalGoals: Int = 5,
) {
    private val goalsSinceLastAnalysis = AtomicInteger(0)

    init {
        EventBus.events.filterIsInstance<CoreEvent.GoalCompleted>()
            .onEach { onGoalFinished() }
            .launchIn(scope)
        EventBus.events.filterIsInstance<CoreEvent.GoalFailed>()
            .onEach { onGoalFinished() }
            .launchIn(scope)
    }

    private fun onGoalFinished() {
        if (goalsSinceLastAnalysis.incrementAndGet() >= analysisIntervalGoals) {
            goalsSinceLastAnalysis.set(0)
            runAnalysisPass()
        }
    }

    private fun runAnalysisPass() {
        for (agent in agentRegistry.all()) {
            strategyOptimizer.reoptimizeCapacity(agent.id)

            val snapshot = performanceAnalyzer.snapshotFor(agent.id)
            if (snapshot != null && snapshot.totalGoals >= 3 && snapshot.successRate < 0.3f) {
                EventBus.tryPublish(
                    CoreEvent.MetaAgentSuggestion(
                        "Агент '${agent.id}' имеет низкий success rate (${snapshot.successRate}) за ${snapshot.totalGoals} целей — " +
                            "рассмотрите пересмотр estimateFit или добавление более специализированного агента."
                    )
                )
            }

            failureAnalyzer.allCategoriesFor(agent.id).filterValues { it >= 3 }.forEach { (category, count) ->
                EventBus.tryPublish(
                    CoreEvent.MetaAgentSuggestion(
                        "Агент '${agent.id}' систематически проваливается по категории $category ($count раз) — " +
                            "возможно, нужен отдельный специализированный агент под этот случай."
                    )
                )
            }
        }
    }

    /** Ручной запуск анализа (например, из будущего экрана диагностики), не дожидаясь порога событий. */
    fun forceAnalysisNow() = runAnalysisPass()
}
