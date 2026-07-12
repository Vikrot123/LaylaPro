package com.laylapro.agent

import com.laylapro.agent.meta.StrategyOptimizer
import com.laylapro.logging.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Координатор многоагентной системы — расширенная версия (Mission Manager сверху,
 * Consensus Engine вместо простого слияния, Capability Manager для быстрого сужения
 * пула кандидатов, Agent Capacity Manager для параллелизма, Strategy Optimizer для
 * адаптивного веса агентов). Рассчитан на масштабирование до десятков/сотен агентов:
 * ни при добавлении нового агента (см. [AgentRegistry]), ни при добавлении нового
 * движка (см. [com.laylapro.router.ModelRouter]), ни при добавлении нового
 * meta-компонента этот класс МЕНЯТЬСЯ не должен (OCP) — только конструкторы вызывающего
 * кода ([com.laylapro.runtime.RuntimeManager]) собирают новый граф зависимостей.
 *
 * ВАЖНО про "разрешение конфликтов" — честная граница возможностей: единственный вид
 * конфликта, который координатор умеет обнаруживать МЕХАНИЧЕСКИ (без семантического
 * понимания того, что именно планирует сделать каждый агент) — это "два (под)цели
 * назначены ОДНОМУ И ТОМУ ЖЕ агенту одновременно". Такие подцели выполняются
 * последовательно (в порядке приоритета) внутри своей группы, а разные агенты —
 * параллельно между группами. Более глубокое обнаружение конфликтов (например,
 * "один агент включает Wi-Fi, другой его выключает") потребовало бы анализа
 * запланированных действий ДО их выполнения — это открытая точка расширения
 * (см. TODO в [pursueDecomposed]), а не то, что реализовано сейчас.
 */
class AgentCoordinator(
    private val agentRegistry: AgentRegistry,
    private val goalManager: GoalManager,
    private val missionManager: MissionManager,
    private val loggingManager: Logger,
    private val capabilityManager: CapabilityManager,
    private val goalDecomposer: GoalDecomposer,
    private val capacityManager: AgentCapacityManager,
    private val consensusEngine: ConsensusEngine,
    private val strategyOptimizer: StrategyOptimizer,
) {

    suspend fun pursueGoal(
        sessionId: String,
        description: String,
        maxSteps: Int = 6,
        priority: GoalPriority = GoalPriority.NORMAL,
        missionId: UUID? = null,
        requiresConsensus: Boolean = false,
    ): GoalOutcome {
        val goal = goalManager.create(
            sessionId = sessionId,
            description = description,
            maxSteps = maxSteps,
            priority = priority,
            missionId = missionId,
            requiresConsensus = requiresConsensus,
        )

        val outcome = when {
            goal.requiresConsensus -> pursueWithConsensus(goal)
            else -> {
                val subGoals = goalDecomposer.decompose(goal)
                if (subGoals.size <= 1) pursueSingle(goal) else pursueDecomposed(goal, subGoals)
            }
        }

        goal.missionId?.let { missionManager.recomputeProgress(it) }
        return outcome
    }

    // ---------------------------------------------------------------------
    // Обычный путь: один агент, с попыткой следующего по рейтингу при провале
    // ---------------------------------------------------------------------
    private suspend fun pursueSingle(goal: Goal): GoalOutcome {
        val ranked = rankAgents(goal)
        if (ranked.isEmpty()) {
            goalManager.fail(goal.id, "Нет ни одного зарегистрированного агента, подходящего для цели")
            return GoalOutcome(goal.id.toString(), "none", "Нет доступных агентов для обработки цели", GoalStatus.FAILED, 0)
        }

        for ((agent, score) in ranked) {
            loggingManager.info("AgentCoordinator", "Делегирую цель ${goal.id} агенту '${agent.id}' (score=$score)")
            val outcome = runAgentOnGoal(goal, agent)
            if (goal.status == GoalStatus.COMPLETED) return outcome

            if (goal.status == GoalStatus.FAILED || goal.status == GoalStatus.ABANDONED) {
                loggingManager.warning(
                    "AgentCoordinator",
                    "Агент '${agent.id}' не справился с целью ${goal.id} (${goal.status}) — пробую следующего"
                )
                goalManager.resetForRetry(goal.id)
                continue
            }
        }

        goalManager.fail(goal.id, "Ни один из ${ranked.size} агентов не смог завершить цель")
        return GoalOutcome(goal.id.toString(), ranked.last().first.id, "Не удалось выполнить цель", GoalStatus.FAILED, goal.stepsTaken.size)
    }

    // ---------------------------------------------------------------------
    // Декомпозиция: независимые подцели -> параллельно между агентами,
    // последовательно внутри одного агента (см. KDoc класса про конфликты)
    // ---------------------------------------------------------------------
    private suspend fun pursueDecomposed(parentGoal: Goal, subGoals: List<Goal>): GoalOutcome {
        subGoals.forEach { goalManager.register(it) }

        // TODO(семантические конфликты): здесь — идеальная точка, чтобы в будущем
        // спросить у каждого выбранного агента "что именно ты планируешь сделать"
        // ДО выполнения (например, через Planning Engine в режиме dry-run) и сравнить
        // планы между собой, прежде чем запускать. Сейчас распознаётся только
        // конфликт "один и тот же агент назначен дважды" — см. группировку ниже.
        val assignments = subGoals.map { sub -> sub to rankAgents(sub).firstOrNull()?.first }
        val byAgent = assignments.groupBy { it.second?.id }

        val outcomes = coroutineScope {
            byAgent.map { (_, groupAssignments) ->
                async {
                    groupAssignments
                        .sortedBy { it.first.priority.ordinal }
                        .map { (subGoal, agent) -> runAssignment(subGoal, agent) }
                }
            }.awaitAll().flatten()
        }

        val merged = consensusEngine.reconcile(parentGoal, outcomes, MergeMode.INDEPENDENT_SUBGOALS)
        if (merged.status == GoalStatus.COMPLETED) {
            goalManager.complete(parentGoal.id)
        } else {
            goalManager.fail(parentGoal.id, "Часть подцелей не выполнена (см. детали в объединённом результате)")
        }
        return merged
    }

    // ---------------------------------------------------------------------
    // Consensus-режим: N лучших агентов параллельно на ОДНУ и ту же цель,
    // независимые ветки (branchGoal), результаты сверяются ConsensusEngine
    // ---------------------------------------------------------------------
    private suspend fun pursueWithConsensus(goal: Goal, topN: Int = 2): GoalOutcome {
        val ranked = rankAgents(goal)
        if (ranked.isEmpty()) {
            goalManager.fail(goal.id, "Нет ни одного зарегистрированного агента для consensus-режима")
            return GoalOutcome(goal.id.toString(), "none", "Нет доступных агентов", GoalStatus.FAILED, 0)
        }

        val chosenAgents = ranked.take(topN.coerceAtLeast(1)).map { it.first }

        val outcomes = coroutineScope {
            chosenAgents.map { agent ->
                async { runAssignment(branchGoal(goal), agent) }
            }.awaitAll()
        }

        val merged = consensusEngine.reconcile(goal, outcomes, MergeMode.REDUNDANT_CONSENSUS)
        if (merged.status == GoalStatus.COMPLETED) {
            goalManager.complete(goal.id)
        } else {
            goalManager.fail(goal.id, "Consensus Engine не смог согласовать результаты нескольких агентов")
        }
        return merged
    }

    // ---------------------------------------------------------------------
    // Общие внутренние помощники
    // ---------------------------------------------------------------------

    /**
     * Ранжирует агентов для цели: если задана [Goal.requiredCapability] — сначала
     * сужаем пул через [CapabilityManager] (дёшево на масштабе сотен агентов), иначе
     * рассматриваем всех. Финальный скор — estimateFit, взвешенный адаптивным
     * множителем от [StrategyOptimizer] (историческая эффективность агента).
     */
    private fun rankAgents(goal: Goal): List<Pair<SpecializedAgent, Float>> {
        val pool = goal.requiredCapability
            ?.let { capability -> capabilityManager.candidatesFor(capability).map { it.agent } }
            ?.takeIf { it.isNotEmpty() }
            ?: agentRegistry.all()

        // MJ2 (независимый аудит реализации): estimateFit() — контракт, который потенциально
        // реализуют сторонние/будущие агенты. Один агент с багом в estimateFit() не должен
        // ронять исключением весь проход ранжирования для ВСЕХ агентов на эту цель — трактуем
        // сбой как "агент не подходит" (score = 0), а не как фатальную ошибку координатора.
        return pool
            .map { agent ->
                val fit = runCatching { agent.estimateFit(goal) }
                    .onFailure { loggingManager.error("AgentCoordinator", "estimateFit() агента '${agent.id}' упал с исключением: ${it.message}") }
                    .getOrDefault(0f)
                agent to fit * strategyOptimizer.scoringMultiplier(agent.id)
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
    }

    private suspend fun runAssignment(goal: Goal, agent: SpecializedAgent?): GoalOutcome {
        if (agent == null) {
            goalManager.fail(goal.id, "Нет подходящего агента для подцели \"${goal.description}\"")
            return GoalOutcome(goal.id.toString(), "none", "Нет подходящего агента", GoalStatus.FAILED, 0)
        }
        return runAgentOnGoal(goal, agent)
    }

    private suspend fun runAgentOnGoal(goal: Goal, agent: SpecializedAgent): GoalOutcome {
        goalManager.delegate(goal.id, fromAgent = "AgentCoordinator", toAgent = agent.id, capability = agent.capabilities.joinToString())
        // MJ2: та же изоляция для pursue() — если у конкретного агента есть баг (не покрытый
        // его собственной внутренней обработкой ошибок, как это сделано в BaseLoopAgent), это
        // должно стать провалом ЭТОЙ цели/подцели, а не необработанным исключением, поднимающимся
        // через AgentCoordinator наружу и потенциально роняющим весь вызывающий pursueGoal().
        return runCatching { capacityManager.withAgentSlot(agent.id) { agent.pursue(goal) } }
            .getOrElse { e ->
                loggingManager.error("AgentCoordinator", "pursue() агента '${agent.id}' упал с исключением: ${e.message}")
                goalManager.fail(goal.id, "Агент '${agent.id}' упал с исключением: ${e.message}")
                GoalOutcome(goal.id.toString(), agent.id, "Внутренняя ошибка агента: ${e.message}", GoalStatus.FAILED, goal.stepsTaken.size)
            }
    }

    /** Независимая копия цели для consensus-веток — НЕ шарит mutable-состояние (status/stepsTaken) между параллельными попытками. */
    private fun branchGoal(parent: Goal): Goal {
        val branch = Goal(
            sessionId = parent.sessionId,
            description = parent.description,
            maxSteps = parent.maxSteps,
            priority = parent.priority,
            missionId = parent.missionId,
            parentGoalId = parent.id,
        )
        goalManager.register(branch)
        return branch
    }
}
