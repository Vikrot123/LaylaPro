package com.laylapro.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Workflow Engine (Том 98): "Исполняет последовательности действий. Каждый Workflow
 * представляет собой направленный граф (DAG)... Workflow считается завершённым
 * только после успешного выполнения всех узлов."
 */
data class RetryPolicy(val maxAttempts: Int = 1, val backoffMs: Long = 200)

data class WorkflowNode(
    val nodeId: String,
    val module: String,
    val action: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val timeoutMs: Long = 15_000,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    /** Действие для отката, если workflow прервался ПОСЛЕ успешного выполнения этого узла. */
    val rollbackAction: String? = null,
)

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

/**
 * Топологически исполняет узлы workflow через [Dispatcher] (единственная точка
 * входа для вызова модулей — см. Dispatcher). Независимые узлы без общих
 * зависимостей выполняются параллельно (`coroutineScope { async { ... } }`).
 */
class WorkflowEngine(private val dispatcher: Dispatcher) {

    suspend fun execute(workflow: Workflow): WorkflowOutcome {
        val nodesById = workflow.nodes.associateBy { it.nodeId }
        val results = mutableMapOf<String, CommandResult>()
        val executedOrder = mutableListOf<String>()
        val remaining = workflow.nodes.map { it.nodeId }.toMutableSet()

        // Простой уровневый топологический обход: на каждой итерации выполняем
        // все узлы, чьи зависимости уже выполнены, — это и даёт параллелизм
        // независимым веткам DAG без явного построения уровней заранее.
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                nodesById.getValue(id).dependencies.all { dep -> dep !in remaining }
            }

            if (ready.isEmpty()) {
                return WorkflowOutcome.Failed(
                    failedNodeId = remaining.first(),
                    error = "Обнаружен цикл или недостижимая зависимость в DAG: $remaining",
                    partialResults = results,
                    rolledBack = emptyList(),
                )
            }

            val levelResults = coroutineScope {
                ready.map { nodeId ->
                    async { nodeId to runNode(nodesById.getValue(nodeId)) }
                }.map { it.await() }
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

    private suspend fun runNode(node: WorkflowNode): CommandResult {
        var lastResult: CommandResult? = null
        var attempt = 0
        while (attempt < node.retryPolicy.maxAttempts) {
            attempt++
            lastResult = dispatcher.dispatch(
                Command(
                    targetModule = node.module,
                    action = node.action,
                    params = node.parameters,
                    timeoutMs = node.timeoutMs,
                )
            )
            if (lastResult.success) return lastResult
            if (attempt < node.retryPolicy.maxAttempts) {
                delay(node.retryPolicy.backoffMs * attempt)
            }
        }
        return lastResult ?: CommandResult(success = false, error = "Узел '${node.nodeId}' не выполнился ни разу")
    }

    /** Откатывает уже успешно выполненные узлы в обратном порядке, если workflow прервался. */
    private suspend fun rollback(
        executedOrder: List<String>,
        nodesById: Map<String, WorkflowNode>,
    ): List<String> {
        val rolledBack = mutableListOf<String>()
        for (nodeId in executedOrder.asReversed()) {
            val node = nodesById.getValue(nodeId)
            val rollbackAction = node.rollbackAction ?: continue
            val outcome = dispatcher.dispatch(
                Command(targetModule = node.module, action = rollbackAction, params = node.parameters)
            )
            if (outcome.success) rolledBack.add(nodeId)
        }
        return rolledBack
    }
}
