package com.laylapro.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowContractTest {
    private fun engine() = WorkflowEngine(Dispatcher(RecoveryManager()))

    @Test
    fun duplicate_node_ids_are_rejected_before_execution() = runBlocking {
        val outcome = engine().execute(
            Workflow(
                name = "duplicate",
                nodes = listOf(
                    WorkflowNode("same", "A", "x"),
                    WorkflowNode("same", "B", "y"),
                ),
            )
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals("validation", (outcome as WorkflowOutcome.Failed).failedNodeId)
    }

    @Test
    fun missing_dependency_is_rejected_before_execution() = runBlocking {
        val outcome = engine().execute(
            Workflow(
                name = "missing",
                nodes = listOf(WorkflowNode("a", "A", "x", dependencies = listOf("missing"))),
            )
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertTrue((outcome as WorkflowOutcome.Failed).error.contains("отсутствующие зависимости"))
    }
}
