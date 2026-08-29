package com.laylapro.runtime

import com.laylapro.planning.TaskStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExecutionBoundaryContractTest {
    @Test
    fun confirmation_required_tool_fails_closed_without_dispatch() = runBlocking {
        val calls = AtomicInteger(0)
        val dispatcher = Dispatcher(RecoveryManager()).apply {
            register("AndroidIntegration") {
                calls.incrementAndGet()
                CommandResult(true, output = "clicked")
            }
        }
        val executor = DispatcherToolExecutor(dispatcher)

        val result = executor.execute(
            listOf(
                TaskStep(
                    id = "click-1",
                    moduleName = "AndroidIntegration",
                    action = "click_by_text",
                    params = mapOf("text" to "Pay"),
                    requiresUserConfirmation = true,
                )
            )
        ).getValue("click-1")

        assertFalse(result.success)
        assertEquals(0, calls.get())
    }

    @Test
    fun safe_tool_reaches_dispatcher() = runBlocking {
        val calls = AtomicInteger(0)
        val dispatcher = Dispatcher(RecoveryManager()).apply {
            register("DeviceControl") {
                calls.incrementAndGet()
                CommandResult(true, output = "opened")
            }
        }
        val result = DispatcherToolExecutor(dispatcher).execute(
            listOf(TaskStep("safe", "DeviceControl", "open_sound_settings"))
        ).getValue("safe")

        assertTrue(result.success)
        assertEquals(1, calls.get())
    }

    @Test
    fun dispatcher_propagates_parent_cancellation() = runBlocking {
        val dispatcher = Dispatcher(RecoveryManager()).apply {
            register("cancel") { throw CancellationException("cancelled") }
        }

        var propagated = false
        try {
            dispatcher.dispatch(Command("cancel", "run"))
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun recovery_retry_propagates_cancellation() = runBlocking {
        val task = Task(
            sessionId = "test",
            name = "cancel",
            type = TaskType.BACKGROUND_JOB,
            ownerModule = "module",
        )
        var propagated = false
        try {
            RecoveryManager().retryTask(task) { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
