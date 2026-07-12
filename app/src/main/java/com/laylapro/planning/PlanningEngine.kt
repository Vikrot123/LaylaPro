package com.laylapro.planning

import java.util.UUID

/**
 * Модуль 3 (Слой 1) — декомпозиция цели в DAG задач.
 * Контракт совпадает с описанием в ТЗ (Часть II, п.3).
 */
interface PlanningEngine {
    suspend fun buildPlan(goal: String, availableTools: List<ToolDefinition>): TaskGraph
    suspend fun modifyPlanOnFailure(failedStepId: String, error: String, currentGraph: TaskGraph): TaskGraph
}

data class ToolDefinition(
    val moduleName: String,
    val action: String,
    val description: String,
)

data class TaskGraph(
    val graphId: UUID = UUID.randomUUID(),
    val steps: List<TaskStep>,
)

data class TaskStep(
    val id: String,
    val moduleName: String,          // Например, "AndroidIntegration"
    val action: String,              // Например, "open_app"
    val params: Map<String, Any?> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    var status: StepStatus = StepStatus.PENDING,
)

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED }
