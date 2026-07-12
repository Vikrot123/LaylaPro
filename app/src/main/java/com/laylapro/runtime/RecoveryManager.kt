package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovery Manager (Том 98): "При возникновении ошибки Runtime обязан:
 * зафиксировать событие; сохранить состояние; перезапустить модуль;
 * повторить выполнение задачи; при невозможности восстановления уведомить пользователя.
 * Runtime никогда не завершает работу полностью при отказе одного модуля."
 */
fun interface Restartable {
    /** Сбрасывает внутреннее состояние модуля. Не должен бросать исключения. */
    fun restart()
}

/** Слушатель, которого RuntimeManager/UI регистрирует, чтобы получать пользовательские уведомления. */
fun interface UnrecoverableFailureListener {
    fun onUnrecoverable(moduleName: String, lastError: String?)
}

class RecoveryManager(private val maxAttemptsBeforeDegraded: Int = 3) {

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

    /** Вызывается диспетчером/watchdog при исключении или зависании модуля. Возвращает номер попытки. */
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
            // "При невозможности восстановления уведомить пользователя" — но НЕ завершать работу.
            unrecoverableListener?.onUnrecoverable(moduleName, cause?.message)
            return attempt
        }

        val restartable = restartables[moduleName]
        if (restartable != null) {
            runCatching { restartable.restart() }
            EventBus.tryPublish(CoreEvent.ModuleRestarted(moduleName, attempt))
        }
        return attempt
    }

    /** Сбрасывает счётчик ошибок после успешного вызова — модуль считается "здоровым" снова. */
    fun recordSuccess(moduleName: String) {
        failureCounts[moduleName]?.set(0)
        degraded.remove(moduleName)
    }

    /**
     * "Повторить выполнение задачи": оборачивает suspend-действие ретраями с
     * экспоненциальной задержкой, ограниченными [Task.maxRetries].
     */
    suspend fun <T> retryTask(task: Task, action: suspend () -> T): Result<T> {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt <= task.maxRetries) {
            try {
                return Result.success(action())
            } catch (e: Exception) {
                lastError = e
                task.retryCount = ++attempt
                recordFailure(task.ownerModule, e)
                if (attempt <= task.maxRetries) {
                    kotlinx.coroutines.delay(200L * attempt) // простой backoff
                }
            }
        }
        return Result.failure(lastError ?: RuntimeException("Задача ${task.id} провалилась без деталей"))
    }
}
