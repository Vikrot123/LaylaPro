package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Потокобезопасная очередь задач с сортировкой по приоритету, затем по времени
 * создания (FIFO внутри одного приоритета) — см. Том 98, "Task Manager" / "Scheduler".
 */
class TaskQueue {

    private val lock = ReentrantLock()
    private val queue = PriorityQueue<Task>(
        compareBy<Task> { it.priority.ordinal }.thenBy { it.createdAt }
    )
    private val byId = mutableMapOf<UUID, Task>()

    fun enqueue(task: Task) {
        lock.withLock {
            task.status = TaskStatus.QUEUED
            queue.add(task)
            byId[task.id] = task
        }
        EventBus.tryPublish(CoreEvent.TaskEnqueued(task.id.toString(), task.type.name, task.priority.name))
    }

    /** Забирает следующую задачу с наивысшим приоритетом, пропуская отменённые/на паузе. */
    fun poll(): Task? = lock.withLock {
        while (queue.isNotEmpty()) {
            val next = queue.poll()
            if (next.status == TaskStatus.QUEUED) return@withLock next
        }
        null
    }

    fun cancel(taskId: UUID): Boolean = lock.withLock {
        val task = byId[taskId] ?: return@withLock false
        if (!task.cancellable) return@withLock false
        task.status = TaskStatus.CANCELLED
        true
    }

    fun pause(taskId: UUID): Boolean = lock.withLock {
        val task = byId[taskId] ?: return@withLock false
        if (task.status != TaskStatus.RUNNING && task.status != TaskStatus.QUEUED) return@withLock false
        task.status = TaskStatus.PAUSED
        true
    }

    fun resume(taskId: UUID): Boolean = lock.withLock {
        val task = byId[taskId] ?: return@withLock false
        if (task.status != TaskStatus.PAUSED) return@withLock false
        task.status = TaskStatus.QUEUED
        queue.add(task)
        true
    }

    fun get(taskId: UUID): Task? = lock.withLock { byId[taskId] }

    fun snapshot(): List<Task> = lock.withLock { byId.values.toList() }

    fun size(): Int = lock.withLock { queue.size }
}
