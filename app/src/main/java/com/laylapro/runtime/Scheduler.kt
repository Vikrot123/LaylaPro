package com.laylapro.runtime

/**
 * Планировщик (Том 98, "Планировщик"): решает, что выполнять, когда, каким
 * модулем, можно ли параллельно/отменить/поставить на паузу.
 *
 * MVP-версия — тонкая обёртка над [TaskQueue] с явными точками расширения
 * (лимит параллельных задач, приоритетное вытеснение и т.п.), чтобы дальше
 * можно было наращивать логику, не трогая вызывающий код.
 */
class Scheduler(private val queue: TaskQueue, private val maxParallelTasks: Int = 1) {

    private val runningCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** Отдаёт следующую задачу, если система готова её принять (не превышен лимит параллелизма). */
    fun nextTask(): Task? {
        if (runningCount.get() >= maxParallelTasks) return null
        val task = queue.poll() ?: return null
        if (!task.canRunInParallel && runningCount.get() > 0) {
            // Задача не параллелится, а что-то уже выполняется — возвращаем в очередь.
            queue.enqueue(task)
            return null
        }
        runningCount.incrementAndGet()
        return task
    }

    fun markFinished() {
        runningCount.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun submit(task: Task) = queue.enqueue(task)

    fun cancel(task: Task) = queue.cancel(task.id)
    fun pause(task: Task) = queue.pause(task.id)
    fun resume(task: Task) = queue.resume(task.id)

    fun pendingCount(): Int = queue.size()
}
