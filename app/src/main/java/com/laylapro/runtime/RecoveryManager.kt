package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun interface Restartable {
    fun restart()
}

fun interface UnrecoverableFailureListener {
    fun onUnrecoverable(moduleName: String, lastError: String?)
}

class RecoveryManager(private val maxAttemptsBeforeDegraded: Int = 3) {
    init {
        require(maxAttemptsBeforeDegraded > 0) { "maxAttemptsBeforeDegraded must be positive" }
    }

    private val restartables = ConcurrentHashMap<String, Restartable>()
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val degraded = ConcurrentHashMap.newKeySet<String>()
    private var unrecoverableListener: UnrecoverableFailureListener? = null

    fun registerRestartable(moduleName: String, restartable: Restartable) {
        restartables[moduleName] = restartable
    }

    fun setUnrecoverableFailureListener(listener: UnrecoverableFailureListener?) {
        unrecoverableListener = listener
    }

    fun isDegraded(moduleName: String): Boolean = moduleName in degraded

    fun recordFailure(moduleName: String, cause: Throwable?): Int {
        val counter = failureCounts.getOrPut(moduleName) { AtomicInteger(0) }
        val attempt = counter.incrementAndGet()

        if (attempt > maxAttemptsBeforeDegraded) {
            degraded.add(moduleName)
            EventBus.tryPublish(
                CoreEvent.ModuleDegraded(
                    moduleName,
                    "Превышено число попыток восстановления ($maxAttemptsBeforeDegraded). Последняя ошибка: ${cause?.message}",
                )
            )
            unrecoverableListener?.onUnrecoverable(moduleName, cause?.message)
            return attempt
        }

        restartables[moduleName]?.let { restartable ->
            try {
                restartable.restart()
                EventBus.tryPublish(CoreEvent.ModuleRestarted(moduleName, attempt))
            } catch (e: Exception) {
                EventBus.tryPublish(
                    CoreEvent.ErrorOccurred(
                        "RecoveryManager",
                        "Перезапуск модуля '$moduleName' не удался: ${e.message ?: "unknown error"}",
                    )
                )
            }
        }
        return attempt
    }

    fun recordSuccess(moduleName: String) {
        failureCounts[moduleName]?.set(0)
        degraded.remove(moduleName)
    }

    suspend fun <T> retryTask(task: Task, action: suspend () -> T): Result<T> {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt <= task.maxRetries) {
            try {
                return Result.success(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                task.retryCount = ++attempt
                recordFailure(task.ownerModule, e)
                if (attempt <= task.maxRetries) {
                    kotlinx.coroutines.delay(200L * attempt)
                }
            }
        }
        return Result.failure(lastError ?: RuntimeException("Задача ${task.id} провалилась без деталей"))
    }
}
