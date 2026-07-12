package com.laylapro.agent

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Модуль 5 (Goal Manager) — Этап 1 архитектуры LaylaPro AI OS.
 *
 * Разница с [com.laylapro.planning.PlanningEngine]: Planning строит ОДИН граф шагов
 * для одного обращения к AI Core. Goal Manager отслеживает цель, которая может
 * потребовать НЕСКОЛЬКО таких обращений подряд, прежде чем будет считаться достигнутой,
 * КАКОМУ специализированному агенту она делегирована, и (начиная с этого расширения)
 * к какой долгоживущей [Mission] она относится — см. [MissionManager].
 */
enum class GoalStatus { ACTIVE, DELEGATED, COMPLETED, FAILED, ABANDONED }

/** Чем меньше ordinal — тем выше приоритет при конкуренции за ограниченную ёмкость агента. */
enum class GoalPriority { CRITICAL, HIGH, NORMAL, LOW }

data class Goal(
    val id: UUID = UUID.randomUUID(),
    val sessionId: String,
    val description: String,
    val maxSteps: Int = 6,
    val priority: GoalPriority = GoalPriority.NORMAL,
    var status: GoalStatus = GoalStatus.ACTIVE,
    val stepsTaken: MutableList<String> = mutableListOf(),
    var failureReason: String? = null,
    /** Какой SpecializedAgent сейчас (или в итоге) обрабатывает цель — заполняет AgentCoordinator. */
    var assignedAgentId: String? = null,
    /** Заполняется MissionManager, если цель — часть долгоживущей миссии, а не разовый запрос. */
    var missionId: UUID? = null,
    /** Заполняется декомпозицией родительской цели (см. GoalDecomposer), если это подцель. */
    val parentGoalId: UUID? = null,
    /**
     * Явное указание, какая способность нужна (см. [CapabilityManager]) — если задано,
     * AgentCoordinator сужает выбор до агентов с этой способностью ПЕРЕД оценкой fit.
     * Если null — оценка идёт по всем агентам через обычный [SpecializedAgent.estimateFit].
     */
    var requiredCapability: AgentCapability? = null,
    /**
     * Если true — координатор намеренно запускает НЕСКОЛЬКО лучших агентов параллельно
     * на одну и ту же (не декомпозированную) цель и сверяет их ответы через
     * [ConsensusEngine] вместо того, чтобы полагаться на единственного агента.
     * Полезно для критичных/неоднозначных целей, где важна перепроверка.
     */
    var requiresConsensus: Boolean = false,
)

class GoalManager {

    private val goals = ConcurrentHashMap<UUID, Goal>()

    fun create(
        sessionId: String,
        description: String,
        maxSteps: Int = 6,
        priority: GoalPriority = GoalPriority.NORMAL,
        missionId: UUID? = null,
        requiredCapability: AgentCapability? = null,
        requiresConsensus: Boolean = false,
    ): Goal {
        val goal = Goal(
            sessionId = sessionId,
            description = description,
            maxSteps = maxSteps,
            priority = priority,
            missionId = missionId,
            requiredCapability = requiredCapability,
            requiresConsensus = requiresConsensus,
        )
        register(goal)
        return goal
    }

    /** Регистрирует уже собранный объект Goal — используется [GoalDecomposer] для подцелей. */
    fun register(goal: Goal) {
        goals[goal.id] = goal
        EventBus.tryPublish(CoreEvent.GoalCreated(goal.id.toString(), goal.sessionId, goal.description))
        goal.missionId?.let { EventBus.tryPublish(CoreEvent.MissionGoalAttached(it.toString(), goal.id.toString())) }
    }

    fun get(id: UUID): Goal? = goals[id]

    /** Вызывается [AgentCoordinator], когда цель передана конкретному специализированному агенту. */
    fun delegate(id: UUID, fromAgent: String, toAgent: String, capability: String) {
        goals[id]?.let {
            it.status = GoalStatus.DELEGATED
            it.assignedAgentId = toAgent
            EventBus.tryPublish(CoreEvent.GoalDelegated(id.toString(), fromAgent, toAgent, capability))
        }
    }

    fun recordStep(id: UUID, stepSummary: String) {
        goals[id]?.stepsTaken?.add(stepSummary)
    }

    fun complete(id: UUID) {
        goals[id]?.let {
            it.status = GoalStatus.COMPLETED
            EventBus.tryPublish(CoreEvent.GoalCompleted(id.toString(), it.stepsTaken.size, it.assignedAgentId ?: "unknown"))
        }
    }

    fun fail(id: UUID, reason: String) {
        goals[id]?.let {
            it.status = GoalStatus.FAILED
            it.failureReason = reason
            EventBus.tryPublish(CoreEvent.GoalFailed(id.toString(), reason, it.assignedAgentId))
        }
    }

    fun abandon(id: UUID) {
        goals[id]?.let {
            it.status = GoalStatus.ABANDONED
            EventBus.tryPublish(
                CoreEvent.GoalFailed(
                    id.toString(),
                    "Достигнут лимит шагов (${it.maxSteps}) без явного завершения",
                    it.assignedAgentId,
                )
            )
        }
    }

    /**
     * ADR-020: единственный разрешённый способ сбросить провалившуюся цель обратно
     * в состояние, пригодное для повторной попытки со следующим по рейтингу агентом
     * (см. [com.laylapro.agent.AgentCoordinator.pursueSingle]). До этого метода
     * `AgentCoordinator` присваивал `goal.status = GoalStatus.DELEGATED` напрямую,
     * в обход `GoalManager` — находка независимого аудита Этапов 1-2 (M2): нарушение
     * инкапсуляции (GRASP Information Expert) и потеря диагностического события для
     * этого перехода. Теперь переход инкапсулирован здесь и публикует
     * [CoreEvent.GoalRetryScheduled] — предыдущий провал не остаётся "невидимым".
     */
    fun resetForRetry(id: UUID) {
        goals[id]?.let { goal ->
            val previousStatus = goal.status.name
            val failedAgentId = goal.assignedAgentId ?: "unknown"
            goal.status = GoalStatus.DELEGATED
            EventBus.tryPublish(
                CoreEvent.GoalRetryScheduled(id.toString(), failedAgentId, previousStatus, goal.sessionId, goal.description)
            )
        }
    }

    fun activeGoalsFor(sessionId: String): List<Goal> =
        goals.values.filter { it.sessionId == sessionId && (it.status == GoalStatus.ACTIVE || it.status == GoalStatus.DELEGATED) }

    fun goalsForMission(missionId: UUID): List<Goal> = goals.values.filter { it.missionId == missionId }

    fun all(): List<Goal> = goals.values.toList()
}
