package com.laylapro.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.UUID

data class RetryPolicy(val maxAttempts: Int = 1, val backoffMs: Long = 200) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(backoffMs >= 0) { "backoffMs must not be negative" }
    }
}

data class WorkflowNode(
    val nodeId: String,
    val module: String,
    val action: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val timeoutMs: Long = 15_000,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val rollbackAction: String? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "workflow node id must not be blank" }
        require(module.isNotBlank()) { "workflow module must not be blank" }
        require(action.isNotBlank()) { "workflow action must not be blank" }
        require(timeoutMs > 0) { "workflow timeoutMs must be positive" }
    }
}

data class Workflow(
    val workflowId: UUID = UUID.randomUUID(),
    val name: String,
    val nodes: List<WorkflowNode>,
)

sealed class WorkflowOutcome {
    data class Success(val results: Map<String, CommandResult>) : WorkflowOutcome()
    data class Failed(
        val failedNodeId: String,
        val error: String,
        val partialResults: Map<String, CommandResult>,
        val rolledBack: List<String>,
    ) : WorkflowOutcome()
}

class WorkflowEngine(private val dispatcher: Dispatcher) {

    suspend fun execute(workflow: Workflow): WorkflowOutcome {
        validationError(workflow)?.let { error ->
            return WorkflowOutcome.Failed(
                failedNodeId = "validation",
                error = error,
                partialResults = emptyMap(),
                rolledBack = emptyList(),
            )
        }

        val nodesById = workflow.nodes.associateBy { it.nodeId }
        val results = mutableMapOf<String, CommandResult>()
        val executedOrder = mutableListOf<String>()
        val remaining = workflow.nodes.map { it.nodeId }.toMutableSet()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                nodesById.getValue(id).dependencies.all { dep -> dep !in remaining }
            }

            if (ready.isEmpty()) {
                return WorkflowOutcome.Failed(
                    failedNodeId = remaining.first(),
                    error = "Обнаружен цикл в DAG: $remaining",
                    partialResults = results,
                    rolledBack = emptyList(),
                )
            }

            val levelResults = coroutineScope {
                ready.map { nodeId -> async { nodeId to runNode(nodesById.getValue(nodeId)) } }
                    .awaitAll()
            }

            for ((nodeId, result) in levelResults) {
                results[nodeId] = result
                remaining.remove(nodeId)
                if (result.success) {
                    executedOrder.add(nodeId)
                } else {
                    val rolledBack = rollback(executedOrder, nodesById)
                    return WorkflowOutcome.Failed(
                        failedNodeId = nodeId,
                        error = result.error ?: "Узел '$nodeId' завершился с ошибкой",
                        partialResults = results,
                        rolledBack = rolledBack,
                    )
                }
            }
        }

        return WorkflowOutcome.Success(results)
    }

    private fun validationError(workflow: Workflow): String? {
        val ids = workflow.nodes.map { it.nodeId }
        if (ids.toSet().size != ids.size) return "Workflow содержит дублирующиеся nodeId"
        val known = ids.toSet()
        workflow.nodes.forEach { node ->
            if (node.nodeId in node.dependencies) return "Узел '${node.nodeId}' зависит сам от себя"
            val missing = node.dependencies.filterNot { it in known }
            if (missing.isNotEmpty()) return "Узел '${node.nodeId}' ссылается на отсутствующие зависимости: $missing"
        }
        return null
    }

    private suspend fun runNode(node: WorkflowNode): CommandResult {
        var lastResult: CommandResult? = null
        var attempt = 0
        while (attempt < node.retryPolicy.maxAttempts) {
            attempt++
            lastResult = dispatcher.dispatch(
                Command(node.module, node.action, node.parameters, node.timeoutMs)
            )
            if (lastResult.success) return lastResult
            if (attempt < node.retryPolicy.maxAttempts) delay(node.retryPolicy.backoffMs * attempt)
        }
        return lastResult ?: CommandResult(false, error = "Узел '${node.nodeId}' не выполнился ни разу")
    }

    private suspend fun rollback(
        executedOrder: List<String>,
        nodesById: Map<String, WorkflowNode>,
    ): List<String> {
        val rolledBack = mutableListOf<String>()
        for (nodeId in executedOrder.asReversed()) {
            val node = nodesById.getValue(nodeId)
            val rollbackAction = node.rollbackAction ?: continue
            val outcome = dispatcher.dispatch(Command(node.module, rollbackAction, node.parameters))
            if (outcome.success) rolledBack.add(nodeId)
        }
        return rolledBack
    }
}
