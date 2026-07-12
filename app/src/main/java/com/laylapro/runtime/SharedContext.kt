package com.laylapro.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Общая память состояния (Том 98): каждый модуль может узнать, какая сейчас
 * задача выполняется, что уже сделано, что осталось, какие были ошибки и
 * какие данные получены — без потери контекста между шагами плана.
 */
data class TaskContextSnapshot(
    val taskId: UUID,
    val completedSteps: List<String> = emptyList(),
    val remainingSteps: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val collectedData: Map<String, Any?> = emptyMap(),
)

class SharedContext {

    private data class MutableEntry(
        val completedSteps: MutableList<String> = mutableListOf(),
        val remainingSteps: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
        val collectedData: MutableMap<String, Any?> = ConcurrentHashMap(),
    )

    private val entries = ConcurrentHashMap<UUID, MutableEntry>()

    private fun entryFor(taskId: UUID) = entries.getOrPut(taskId) { MutableEntry() }

    fun setRemainingSteps(taskId: UUID, steps: List<String>) {
        entryFor(taskId).apply { remainingSteps.clear(); remainingSteps.addAll(steps) }
    }

    fun markStepCompleted(taskId: UUID, step: String) {
        val entry = entryFor(taskId)
        entry.remainingSteps.remove(step)
        entry.completedSteps.add(step)
    }

    fun recordError(taskId: UUID, error: String) {
        entryFor(taskId).errors.add(error)
    }

    fun putData(taskId: UUID, key: String, value: Any?) {
        entryFor(taskId).collectedData[key] = value
    }

    fun getData(taskId: UUID, key: String): Any? = entries[taskId]?.collectedData?.get(key)

    fun snapshot(taskId: UUID): TaskContextSnapshot {
        val entry = entryFor(taskId)
        return TaskContextSnapshot(
            taskId = taskId,
            completedSteps = entry.completedSteps.toList(),
            remainingSteps = entry.remainingSteps.toList(),
            errors = entry.errors.toList(),
            collectedData = entry.collectedData.toMap(),
        )
    }

    /** Очистка после завершения задачи — предотвращает утечку памяти в долгоживущем процессе. */
    fun clear(taskId: UUID) {
        entries.remove(taskId)
    }
}
