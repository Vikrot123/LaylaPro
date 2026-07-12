package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.concurrent.atomic.AtomicReference

/**
 * Том 98 «Runtime Core Architecture» — состояния системы.
 * Переход между состояниями разрешён ТОЛЬКО через RuntimeManager (никакой другой
 * модуль не должен напрямую вызывать StateMachine.transition).
 */
enum class RuntimeState {
    BOOT,
    INITIALIZING,
    READY,
    LISTENING,
    UNDERSTANDING,
    REASONING,
    PLANNING,
    EXECUTING,
    WAITING,
    RESPONDING,
    LEARNING,
    SYNCING,
    SLEEP,
    RECOVERY,
    ERROR,
    SHUTDOWN,
}

/** Алиас для обратной совместимости с кодом, писавшимся под первую версию Тома 98. */
typealias AssistantState = RuntimeState

private val ALLOWED_TRANSITIONS: Map<RuntimeState, Set<RuntimeState>> = mapOf(
    RuntimeState.BOOT to setOf(RuntimeState.INITIALIZING, RuntimeState.ERROR),
    RuntimeState.INITIALIZING to setOf(RuntimeState.READY, RuntimeState.ERROR),
    RuntimeState.READY to setOf(
        RuntimeState.LISTENING, RuntimeState.UNDERSTANDING, RuntimeState.LEARNING,
        RuntimeState.SYNCING, RuntimeState.SLEEP, RuntimeState.SHUTDOWN, RuntimeState.ERROR,
    ),
    RuntimeState.LISTENING to setOf(RuntimeState.UNDERSTANDING, RuntimeState.READY, RuntimeState.ERROR),
    RuntimeState.UNDERSTANDING to setOf(RuntimeState.REASONING, RuntimeState.RESPONDING, RuntimeState.ERROR),
    RuntimeState.REASONING to setOf(RuntimeState.PLANNING, RuntimeState.RESPONDING, RuntimeState.ERROR),
    RuntimeState.PLANNING to setOf(RuntimeState.EXECUTING, RuntimeState.RESPONDING, RuntimeState.ERROR),
    RuntimeState.EXECUTING to setOf(RuntimeState.WAITING, RuntimeState.RESPONDING, RuntimeState.RECOVERY, RuntimeState.ERROR),
    RuntimeState.WAITING to setOf(RuntimeState.EXECUTING, RuntimeState.RESPONDING, RuntimeState.ERROR),
    RuntimeState.RESPONDING to setOf(RuntimeState.READY, RuntimeState.LEARNING, RuntimeState.ERROR),
    RuntimeState.LEARNING to setOf(RuntimeState.READY, RuntimeState.ERROR),
    RuntimeState.SYNCING to setOf(RuntimeState.READY, RuntimeState.ERROR),
    RuntimeState.SLEEP to setOf(RuntimeState.READY, RuntimeState.LISTENING),
    RuntimeState.RECOVERY to setOf(RuntimeState.READY, RuntimeState.ERROR, RuntimeState.SLEEP),
    RuntimeState.ERROR to setOf(RuntimeState.RECOVERY, RuntimeState.READY, RuntimeState.SLEEP, RuntimeState.SHUTDOWN),
    RuntimeState.SHUTDOWN to emptySet(),
)

/**
 * Типовой цикл из Тома 98:
 * READY -> LISTENING -> UNDERSTANDING -> REASONING -> PLANNING -> EXECUTING -> RESPONDING -> READY
 */
class StateMachine {
    private val current = AtomicReference(RuntimeState.BOOT)

    val state: RuntimeState get() = current.get()

    fun transition(to: RuntimeState) {
        val from = current.getAndSet(to)
        if (from == to) return

        val allowed = ALLOWED_TRANSITIONS[from].orEmpty()
        if (to !in allowed) {
            EventBus.tryPublish(
                CoreEvent.ErrorOccurred(
                    "StateMachine",
                    "Нестандартный переход $from -> $to (допустимые: $allowed)."
                )
            )
        }
        EventBus.tryPublish(CoreEvent.StateChanged(from.name, to.name))
    }

    inline fun <T> withState(to: RuntimeState, block: () -> T): T {
        transition(to)
        return block()
    }
}
