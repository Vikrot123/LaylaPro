package com.laylapro.agent

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference

/**
 * World Model — единый источник текущего состояния всей системы (Runtime + Agent
 * Framework), в противовес разбросанным по компонентам локальным полям. Ключевое
 * архитектурное решение: World Model НЕ имеет прямых ссылок ни на [MissionManager],
 * ни на [AgentCoordinator], ни на Runtime Core — он узнаёт обо всём ТОЛЬКО подписавшись
 * на уже существующий [EventBus] (см. Том 98). Это именно та "слабая связанность через
 * Event Bus", о которой просили: ни один компонент не обязан знать о существовании
 * World Model и явно ему что-то передавать — просто продолжает публиковать события,
 * которые и так публикует.
 *
 * Компромисс: World Model отражает состояние с задержкой на обработку события
 * (та же eventual consistency, что и у meta-компонентов) — для панели диагностики
 * или дешёвых эвристик (например, "сколько сейчас активных миссий у сессии") это
 * приемлемо; для решений, где нужна гарантированная свежесть (например, реальный
 * список задач конкретного агента прямо сейчас), стоит обращаться к первоисточнику
 * ([GoalManager]/[MissionManager] напрямую), а не к снимку World Model.
 */
data class GoalSummary(val goalId: String, val sessionId: String, val description: String)
data class MissionSummary(val missionId: String, val sessionId: String, val title: String)

data class WorldSnapshot(
    val runtimeState: String = "UNKNOWN",
    val activeGoals: List<GoalSummary> = emptyList(),
    val activeMissions: List<MissionSummary> = emptyList(),
    val agentLoad: Map<String, Int> = emptyMap(),
    val lastResourcePressure: String? = null,
    val recentSuggestions: List<String> = emptyList(),
)

class WorldModel(scope: CoroutineScope) {

    private val ref = AtomicReference(WorldSnapshot())

    fun current(): WorldSnapshot = ref.get()

    init {
        EventBus.events.onEach { applyEvent(it) }.launchIn(scope)
    }

    private fun applyEvent(event: CoreEvent) {
        ref.updateAndGet { snap ->
            when (event) {
                is CoreEvent.StateChanged -> snap.copy(runtimeState = event.to)

                is CoreEvent.GoalCreated -> snap.copy(
                    activeGoals = snap.activeGoals + GoalSummary(event.goalId, event.sessionId, event.description)
                )
                is CoreEvent.GoalDelegated -> snap.copy(
                    agentLoad = snap.agentLoad + (event.toAgent to (snap.agentLoad[event.toAgent] ?: 0) + 1)
                )
                is CoreEvent.GoalCompleted -> snap.copy(
                    activeGoals = snap.activeGoals.filterNot { it.goalId == event.goalId }
                )
                is CoreEvent.GoalFailed -> snap.copy(
                    activeGoals = snap.activeGoals.filterNot { it.goalId == event.goalId }
                )

                // MJ3 (независимый аудит реализации): GoalFailed выше публикуется и для
                // ОКОНЧАТЕЛЬНОГО провала цели, и для провала ОДНОЙ попытки одного агента
                // внутри retry-цикла (см. AgentCoordinator.pursueSingle) — в последнем случае
                // GoalRetryScheduled приходит следом и означает "цель всё ещё активна, просто
                // с другим агентом". Возвращаем её обратно в activeGoals, чтобы WorldModel не
                // "терял" цель на время повторной попытки.
                is CoreEvent.GoalRetryScheduled -> snap.copy(
                    activeGoals = snap.activeGoals.filterNot { it.goalId == event.goalId } +
                        GoalSummary(event.goalId, event.sessionId, event.description)
                )

                is CoreEvent.MissionCreated -> snap.copy(
                    activeMissions = snap.activeMissions + MissionSummary(event.missionId, event.sessionId, event.title)
                )
                is CoreEvent.MissionCompleted -> snap.copy(
                    activeMissions = snap.activeMissions.filterNot { it.missionId == event.missionId }
                )
                is CoreEvent.MissionFailed -> snap.copy(
                    activeMissions = snap.activeMissions.filterNot { it.missionId == event.missionId }
                )

                is CoreEvent.ResourcePressure -> snap.copy(lastResourcePressure = "${event.kind}: ${event.action}")

                is CoreEvent.MetaAgentSuggestion -> snap.copy(
                    recentSuggestions = (snap.recentSuggestions + event.message).takeLast(10)
                )

                else -> snap
            }
        }
    }
}
