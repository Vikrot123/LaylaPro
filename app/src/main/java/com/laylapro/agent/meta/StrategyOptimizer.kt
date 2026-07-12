package com.laylapro.agent.meta

import com.laylapro.agent.AgentCapacityManager
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import com.laylapro.logging.Logger

/**
 * Strategy Optimizer — четвёртая из пяти meta-частей и ЕДИНСТВЕННАЯ, которая
 * действует на основе данных остальных трёх (Performance/Failure Analyzer,
 * Learning Manager сами по себе ничего не меняют — только измеряют/категоризируют/
 * сглаживают). Два вида воздействия:
 *  1. [scoringMultiplier] — готовый множитель для [com.laylapro.agent.AgentCoordinator]:
 *     итоговый скор кандидата = estimateFit(goal) * scoringMultiplier(agentId).
 *  2. [reoptimizeCapacity] — при устойчиво низком success rate снижает лимит
 *     параллелизма агента в [AgentCapacityManager] (меньше шансов навредить параллельно
 *     чему-то ещё, пока агент показывает плохие результаты).
 */
class StrategyOptimizer(
    private val performanceAnalyzer: PerformanceAnalyzer,
    private val learningManager: LearningManager,
    private val capacityManager: AgentCapacityManager,
    private val loggingManager: Logger,
) {

    /**
     * Чистый запрос (ADR-021): просто читает уже накопленный [LearningManager.biasFor],
     * не вызывая никаких обновлений состояния. Обновление происходит асинхронно и
     * реактивно внутри самого [LearningManager] по подписке на
     * [com.laylapro.core.CoreEvent.AgentPerformanceUpdated].
     */
    fun scoringMultiplier(agentId: String): Float {
        val snapshot = performanceAnalyzer.snapshotFor(agentId)
        if (snapshot == null || snapshot.totalGoals < 3) return 1.0f // недостаточно данных — нейтрально
        return learningManager.biasFor(agentId)
    }

    /** Вызывается периодически [MetaSupervisor] — реагирует на устойчиво плохую производительность. */
    fun reoptimizeCapacity(agentId: String) {
        val snapshot = performanceAnalyzer.snapshotFor(agentId) ?: return
        if (snapshot.totalGoals < 5) return

        val currentLimit = capacityManager.limitFor(agentId)
        if (snapshot.successRate < 0.4f && currentLimit > 1) {
            capacityManager.setLimit(agentId, currentLimit - 1)
            val reason = "success rate ${snapshot.successRate} < 0.4 при ${snapshot.totalGoals} целях"
            loggingManager.warning("StrategyOptimizer", "Снижаю параллелизм агента '$agentId' до ${currentLimit - 1}: $reason")
            EventBus.tryPublish(CoreEvent.StrategyAdjusted(agentId, (currentLimit - 1).toFloat(), reason))
        }
    }
}
