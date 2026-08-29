package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.concurrent.atomic.AtomicReference

enum class RuntimeState {
    BOOT, INITIALIZING, READY, LISTENING, UNDERSTANDING, REASONING, PLANNING,
    EXECUTING, WAITING, RESPONDING, LEARNING, SYNCING, SLEEP, RECOVERY, ERROR, SHUTDOWN,
}

typealias AssistantState = RuntimeState

private val ALLOWED_TRANSITIONS: Map<RuntimeState, Set<RuntimeState>> = mapOf(
    RuntimeState.BOOT to setOf(RuntimeState.INITIALIZING, RuntimeState.ERROR, RuntimeState.SHUTDOWN),
    RuntimeState.INITIALIZING to setOf(RuntimeState.READY, RuntimeState.ERROR, RuntimeState.SHUTDOWN),
    RuntimeState.READY to setOf(
        RuntimeState.LISTENING, RuntimeState.UNDERSTANDING, RuntimeState.LEARNING,
        RuntimeState.SYNCING, RuntimeState.SLEEP, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR,
    ),
    RuntimeState.LISTENING to setOf(RuntimeState.UNDERSTANDING, RuntimeState.READY, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.UNDERSTANDING to setOf(RuntimeState.REASONING, RuntimeState.RESPONDING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.REASONING to setOf(RuntimeState.PLANNING, RuntimeState.RESPONDING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.PLANNING to setOf(RuntimeState.EXECUTING, RuntimeState.RESPONDING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.EXECUTING to setOf(RuntimeState.WAITING, RuntimeState.RESPONDING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.WAITING to setOf(RuntimeState.EXECUTING, RuntimeState.RESPONDING, RuntimeState.READY, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.RESPONDING to setOf(RuntimeState.READY, RuntimeState.LEARNING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.LEARNING to setOf(RuntimeState.READY, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.SYNCING to setOf(RuntimeState.READY, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN, RuntimeState.ERROR),
    RuntimeState.SLEEP to setOf(RuntimeState.READY, RuntimeState.LISTENING, RuntimeState.RECOVERY, RuntimeState.SHUTDOWN),
    RuntimeState.RECOVERY to setOf(RuntimeState.READY, RuntimeState.ERROR, RuntimeState.SLEEP, RuntimeState.SHUTDOWN),
    RuntimeState.ERROR to setOf(RuntimeState.RECOVERY, RuntimeState.READY, RuntimeState.SLEEP, RuntimeState.SHUTDOWN),
    RuntimeState.SHUTDOWN to emptySet(),
)

class StateMachine {
    private val current = AtomicReference(RuntimeState.BOOT)

    val state: RuntimeState get() = current.get()

    /** Returns false and leaves state unchanged when the transition is not allowed. */
    fun transition(to: RuntimeState): Boolean {
        while (true) {
            val from = current.get()
            if (from == to) return true

            val allowed = ALLOWED_TRANSITIONS[from].orEmpty()
            if (to !in allowed) {
                EventBus.tryPublish(
                    CoreEvent.ErrorOccurred(
                        "StateMachine",
                        "Отклонён переход $from -> $to (допустимые: $allowed).",
                    )
                )
                return false
            }

            if (current.compareAndSet(from, to)) {
                EventBus.tryPublish(CoreEvent.StateChanged(from.name, to.name))
                return true
            }
        }
    }

    inline fun <T> withState(to: RuntimeState, block: () -> T): T {
        check(transition(to)) { "Invalid runtime transition to $to" }
        return block()
    }
}
