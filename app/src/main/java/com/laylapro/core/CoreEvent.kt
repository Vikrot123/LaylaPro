package com.laylapro.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Том 98 «Runtime Core Architecture»: единая асинхронная шина событий.
 * Все события публикуются ТОЛЬКО через [EventBus] — прямое взаимодействие
 * между модулями запрещено.
 *
 * Категории событий из ТЗ реализованы как маркерные интерфейсы: каждый подкласс
 * [CoreEvent] помечен одной или несколькими категориями, и подписчики могут
 * фильтроваться по категории (`events.filterIsInstance<TaskEvent>()`), не теряя
 * при этом единого потока для простых случаев.
 */
interface SystemEvent
interface TaskEvent
interface MemoryEvent
interface ConversationEvent
interface VoiceEvent
interface VisionEvent
interface DeviceEvent
interface PluginEvent
interface NetworkEvent
interface SecurityEvent
interface HealthEvent
interface AgentEvent
interface MissionEvent

sealed class CoreEvent {
    data class SpeechRecognized(val text: String) : CoreEvent(), VoiceEvent
    data class UserMessageReceived(val sessionId: String, val text: String) : CoreEvent(), ConversationEvent
    data class AssistantResponded(val sessionId: String, val text: String) : CoreEvent(), ConversationEvent
    data class ExecuteCommand(val module: String, val action: String, val params: Map<String, Any?> = emptyMap()) : CoreEvent(), TaskEvent
    data class DangerousActionRequested(val action: String, val stepId: String) : CoreEvent(), SecurityEvent
    data class LowMemoryAlert(val availableBytes: Long) : CoreEvent(), MemoryEvent, SystemEvent
    data class ErrorOccurred(val source: String, val message: String) : CoreEvent(), SystemEvent

    // ---- Том 98: Runtime State Machine ----
    data class StateChanged(val from: String, val to: String) : CoreEvent(), SystemEvent

    // ---- Том 98: Task Manager / Scheduler ----
    data class TaskEnqueued(val taskId: String, val taskType: String, val priority: String) : CoreEvent(), TaskEvent
    data class TaskStarted(val taskId: String, val module: String) : CoreEvent(), TaskEvent
    data class TaskCompleted(val taskId: String, val durationMs: Long) : CoreEvent(), TaskEvent
    data class TaskFailed(val taskId: String, val module: String, val error: String, val attempt: Int) : CoreEvent(), TaskEvent
    data class TaskTimedOut(val taskId: String, val module: String, val timeoutMs: Long) : CoreEvent(), TaskEvent

    // ---- Том 98: WatchDog / Health Manager ----
    data class ModuleHealthCheckFailed(val module: String) : CoreEvent(), HealthEvent
    data class ModuleHeartbeat(val module: String, val latencyMs: Long, val memoryUsageBytes: Long) : CoreEvent(), HealthEvent
    data class ModuleRestarted(val module: String, val attempt: Int) : CoreEvent(), HealthEvent, SystemEvent
    data class ModuleDegraded(val module: String, val reason: String) : CoreEvent(), HealthEvent, SystemEvent

    // ---- Том 98: Resource Manager ----
    data class ResourcePressure(val kind: String, val value: Float, val action: String) : CoreEvent(), SystemEvent, MemoryEvent

    // ---- Том 98: Permission Manager ----
    data class PermissionDenied(val permission: String, val requestedBy: String) : CoreEvent(), SecurityEvent

    // ---- Том 98: Session Manager ----
    data class SessionCreated(val sessionId: String) : CoreEvent(), ConversationEvent
    data class SessionClosed(val sessionId: String) : CoreEvent(), ConversationEvent

    // ---- Этап 1: Agent Framework / Goal Manager ----
    data class GoalCreated(val goalId: String, val sessionId: String, val description: String) : CoreEvent(), TaskEvent
    data class GoalDelegated(val goalId: String, val fromAgent: String, val toAgent: String, val capability: String) : CoreEvent(), TaskEvent
    data class GoalCompleted(val goalId: String, val stepsTaken: Int, val handledBy: String) : CoreEvent(), TaskEvent
    data class GoalFailed(val goalId: String, val reason: String, val agentId: String? = null) : CoreEvent(), TaskEvent

    // ---- Этап 1: Model Router ----
    data class ModelRouted(val category: String, val profileId: String, val engineId: String, val score: Float) : CoreEvent(), SystemEvent

    // ---- Этап 1+: Mission Manager (долгоживущие миссии из нескольких целей) ----
    data class MissionCreated(val missionId: String, val sessionId: String, val title: String) : CoreEvent(), MissionEvent
    data class MissionGoalAttached(val missionId: String, val goalId: String) : CoreEvent(), MissionEvent
    data class MissionProgressed(val missionId: String, val completedGoals: Int, val totalGoals: Int) : CoreEvent(), MissionEvent
    data class MissionCompleted(val missionId: String) : CoreEvent(), MissionEvent
    data class MissionFailed(val missionId: String, val reason: String) : CoreEvent(), MissionEvent
    data class MissionPaused(val missionId: String, val reason: String) : CoreEvent(), MissionEvent

    // ---- Этап 1+: Consensus Engine ----
    data class ConsensusConflictDetected(val goalId: String, val agentIds: String) : CoreEvent(), AgentEvent
    data class ConsensusReconciled(val goalId: String, val chosenSummary: String) : CoreEvent(), AgentEvent

    // ---- ADR-020: явный переход "провал -> повторная попытка со следующим агентом" ----
    data class GoalRetryScheduled(
        val goalId: String,
        val failedAgentId: String,
        val previousStatus: String,
        val sessionId: String,
        val description: String,
    ) : CoreEvent(), AgentEvent

    // ---- Этап 1+: Meta-уровень (Performance/Failure Analyzer, Learning Manager, Strategy Optimizer, Meta Supervisor) ----
    data class AgentPerformanceUpdated(val agentId: String, val successRate: Float, val avgSteps: Float) : CoreEvent(), AgentEvent
    data class FailurePatternDetected(val agentId: String, val pattern: String, val occurrences: Int) : CoreEvent(), AgentEvent
    data class StrategyAdjusted(val agentId: String, val newMultiplier: Float, val reason: String) : CoreEvent(), AgentEvent
    data class MetaAgentSuggestion(val message: String) : CoreEvent(), AgentEvent
}

/**
 * Единая асинхронная шина событий (Singleton) — 4. Memory System, 12. Device Control
 * и другие модули публикуют/подписываются на неё вместо прямых зависимостей друг на друга.
 */
object EventBus {
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    suspend fun publish(event: CoreEvent) {
        _events.emit(event)
    }

    fun tryPublish(event: CoreEvent): Boolean = _events.tryEmit(event)
}
