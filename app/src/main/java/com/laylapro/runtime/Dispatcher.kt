package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

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
            ?: return CommandResult(false, error = "Модуль '${command.targetModule}' не зарегистрирован в Dispatcher")

        if (moduleRegistry?.isRegistered(command.targetModule) == false) {
            return CommandResult(false, error = "Модуль '${command.targetModule}' не прошёл регистрацию в Module Registry")
        }
        if (moduleRegistry != null && !moduleRegistry.dependenciesSatisfied(command.targetModule)) {
            return CommandResult(false, error = "Зависимости модуля '${command.targetModule}' не готовы")
        }

        EventBus.tryPublish(CoreEvent.TaskStarted(taskId = "-", module = command.targetModule))

        return try {
            val result = withTimeoutOrNull(command.timeoutMs) { handler.handle(command) }
            if (result == null) {
                EventBus.tryPublish(CoreEvent.TaskTimedOut("-", command.targetModule, command.timeoutMs))
                recoveryManager.recordFailure(command.targetModule, RuntimeException("timeout ${command.timeoutMs}мс"))
                CommandResult(false, error = "Таймаут выполнения (${command.timeoutMs}мс)", timedOut = true)
            } else {
                if (result.success) recoveryManager.recordSuccess(command.targetModule)
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val attempt = recoveryManager.recordFailure(command.targetModule, e)
            EventBus.tryPublish(CoreEvent.TaskFailed("-", command.targetModule, e.message ?: "unknown error", attempt))
            CommandResult(false, error = e.message ?: "Ошибка модуля ${command.targetModule}")
        }
    }
}
