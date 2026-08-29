package com.laylapro.planning

import java.util.UUID

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
    val moduleName: String,
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val requiresUserConfirmation: Boolean = false,
    var status: StepStatus = StepStatus.PENDING,
)

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED }
