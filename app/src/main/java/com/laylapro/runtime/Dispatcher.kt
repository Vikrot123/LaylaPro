package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatcher (Том 98): "Все запросы проходят исключительно через Dispatcher.
 * Запрещается вызывать методы других модулей напрямую."
 *
 * Определяет модуль-исполнитель, очередь выполнения (через [Scheduler]/[TaskQueue]
 * выше по стеку), необходимость ожидания результата и таймаут выполнения.
 *
 * Пример из документа:
 *   "Открой Telegram" -> Dispatcher -> Android Integration -> Accessibility -> Результат -> Dispatcher -> AI Core
 */
data class Command(
    val targetModule: String,
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
    val timeoutMs: Long = 15_000,
)

data class CommandResult(
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null,
    val timedOut: Boolean = false,
)

fun interface ModuleHandler {
    suspend fun handle(command: Command): CommandResult
}

class Dispatcher(
    private val recoveryManager: RecoveryManager,
    private val moduleRegistry: ModuleRegistry? = null,
) {

    private val handlers = ConcurrentHashMap<String, ModuleHandler>()

    fun register(moduleName: String, handler: ModuleHandler) {
        handlers[moduleName] = handler
    }

    fun unregister(moduleName: String) {
        handlers.remove(moduleName)
    }

    fun isRegistered(moduleName: String): Boolean = handlers.containsKey(moduleName)

    suspend fun dispatch(command: Command): CommandResult {
        val handler = handlers[command.targetModule]
            ?: return CommandResult(
                success = false,
                error = "Модуль '${command.targetModule}' не зарегистрирован в Dispatcher",
            )

        if (moduleRegistry?.isRegistered(command.targetModule) == false) {
            return CommandResult(
                success = false,
                error = "Модуль '${command.targetModule}' не прошёл регистрацию в Module Registry",
            )
        }

        EventBus.tryPublish(CoreEvent.TaskStarted(taskId = "-", module = command.targetModule))

        return try {
            val result = withTimeoutOrNull(command.timeoutMs) { handler.handle(command) }
            if (result == null) {
                EventBus.tryPublish(
                    CoreEvent.TaskTimedOut(taskId = "-", module = command.targetModule, timeoutMs = command.timeoutMs)
                )
                recoveryManager.recordFailure(command.targetModule, RuntimeException("timeout ${command.timeoutMs}мс"))
                CommandResult(success = false, error = "Таймаут выполнения (${command.timeoutMs}мс)", timedOut = true)
            } else {
                if (result.success) recoveryManager.recordSuccess(command.targetModule)
                result
            }
        } catch (e: Exception) {
            val attempt = recoveryManager.recordFailure(command.targetModule, e)
            EventBus.tryPublish(
                CoreEvent.TaskFailed(
                    taskId = "-",
                    module = command.targetModule,
                    error = e.message ?: "unknown error",
                    attempt = attempt,
                )
            )
            CommandResult(success = false, error = e.message ?: "Ошибка модуля ${command.targetModule}")
        }
    }
}
