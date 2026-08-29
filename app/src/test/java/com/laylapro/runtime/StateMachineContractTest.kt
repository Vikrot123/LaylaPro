package com.laylapro.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineContractTest {
    @Test
    fun invalid_transition_is_rejected_without_mutating_state() {
        val machine = StateMachine()

        assertFalse(machine.transition(RuntimeState.READY))
        assertEquals(RuntimeState.BOOT, machine.state)
    }

    @Test
    fun valid_transition_changes_state() {
        val machine = StateMachine()

        assertTrue(machine.transition(RuntimeState.INITIALIZING))
        assertEquals(RuntimeState.INITIALIZING, machine.state)
    }
}
