package com.laylapro.agent

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mission Manager — новый уровень НАД Goal Manager.
 *
 * Разница с [Goal]: одна цель — это то, что можно попробовать выполнить за один вызов
 * [AgentCoordinator.pursueGoal] (несколько шагов ReAct-цикла, но одна логическая задача).
 * [Mission] — это долгоживущий контейнер из МНОЖЕСТВА целей, которые могут создаваться
 * не одновременно, а на протяжении времени (минуты, часы, дни), и миссия считается
 * выполненной только когда выполнены (или явно исключены) все её цели.
 *
 * Пример: миссия "Организовать переезд" может состоять из целей "найти квартиру"
 * (сегодня), "заказать грузчиков" (через неделю), "уведомить провайдера интернета"
 * (в день переезда) — каждая создаётся отдельным вызовом [attachGoal] по мере
 * появления соответствующего запроса пользователя, но все они привязаны к одной миссии.
 */
enum class MissionStatus { ACTIVE, PAUSED, COMPLETED, FAILED }

data class Mission(
    val id: UUID = UUID.randomUUID(),
    val sessionId: String,
    val title: String,
    var status: MissionStatus = MissionStatus.ACTIVE,
    val goalIds: MutableList<UUID> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)

class MissionManager(private val goalManager: GoalManager) {

    private val missions = ConcurrentHashMap<UUID, Mission>()

    fun create(sessionId: String, title: String): Mission {
        val mission = Mission(sessionId = sessionId, title = title)
        missions[mission.id] = mission
        EventBus.tryPublish(CoreEvent.MissionCreated(mission.id.toString(), sessionId, title))
        return mission
    }

    /** Привязывает уже созданную (или ещё не созданную — см. перегрузку ниже) цель к миссии. */
    fun attachGoal(missionId: UUID, goalId: UUID) {
        missions[missionId]?.let {
            it.goalIds.add(goalId)
            it.updatedAt = System.currentTimeMillis()
        }
    }

    /**
     * Удобная перегрузка: создаёт новую цель СРАЗУ привязанной к миссии — именно так
     * [AgentCoordinator] обычно и работает с миссиями (см. `pursueGoal(..., missionId=...)`).
     */
    fun createGoalFor(
        missionId: UUID,
        description: String,
        maxSteps: Int = 6,
        priority: GoalPriority = GoalPriority.NORMAL,
    ): Goal {
        val goal = goalManager.create(
            sessionId = missions[missionId]?.sessionId ?: "unknown",
            description = description,
            maxSteps = maxSteps,
            priority = priority,
            missionId = missionId,
        )
        attachGoal(missionId, goal.id)
        return goal
    }

    /**
     * Пересчитывает прогресс миссии по фактическому статусу её целей. Вызывается
     * координатором после завершения (успешного или нет) любой цели миссии —
     * см. AgentCoordinator.pursueGoal. Если ВСЕ цели миссии в терминальном статусе
     * (COMPLETED/FAILED/ABANDONED) и хотя бы одна COMPLETED — миссия считается
     * выполненной; если ни одна не COMPLETED, а есть FAILED — миссия проваливается.
     */
    fun recomputeProgress(missionId: UUID) {
        val mission = missions[missionId] ?: return
        // m3 (независимый аудит реализации): при параллельной декомпозиции несколько подцелей
        // одной миссии могут завершиться почти одновременно из разных корутин (AgentCoordinator.
        // pursueDecomposed запускает независимые группы параллельно) — без synchronized здесь
        // возможна дублирующая публикация MissionCompleted/MissionFailed для одной и той же миссии.
        synchronized(mission) {
            val goals = goalManager.goalsForMission(missionId)
            if (goals.isEmpty()) return

            val completed = goals.count { it.status == GoalStatus.COMPLETED }
            val terminal = goals.count {
                it.status == GoalStatus.COMPLETED || it.status == GoalStatus.FAILED || it.status == GoalStatus.ABANDONED
            }

            if (mission.status != MissionStatus.ACTIVE) return // уже финализирована другим потоком — не публикуем повторно

            mission.updatedAt = System.currentTimeMillis()
            EventBus.tryPublish(CoreEvent.MissionProgressed(missionId.toString(), completed, goals.size))

            if (terminal == goals.size) {
                if (completed > 0) {
                    mission.status = MissionStatus.COMPLETED
                    EventBus.tryPublish(CoreEvent.MissionCompleted(missionId.toString()))
                } else {
                    mission.status = MissionStatus.FAILED
                    EventBus.tryPublish(CoreEvent.MissionFailed(missionId.toString(), "Ни одна из ${goals.size} целей миссии не завершена успешно"))
                }
            }
        }
    }

    fun pause(missionId: UUID, reason: String) {
        missions[missionId]?.let {
            it.status = MissionStatus.PAUSED
            EventBus.tryPublish(CoreEvent.MissionPaused(missionId.toString(), reason))
        }
    }

    fun resume(missionId: UUID) {
        missions[missionId]?.status = MissionStatus.ACTIVE
    }

    fun get(id: UUID): Mission? = missions[id]

    fun activeMissionsFor(sessionId: String): List<Mission> =
        missions.values.filter { it.sessionId == sessionId && it.status == MissionStatus.ACTIVE }

    fun all(): List<Mission> = missions.values.toList()
}
