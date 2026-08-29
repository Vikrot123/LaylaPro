package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

fun interface HealthCheck {
    suspend fun ping(): Boolean
}

class WatchDog(
    private val recoveryManager: RecoveryManager,
    private val moduleRegistry: ModuleRegistry? = null,
    private val onModuleUnresponsive: ((String) -> Unit)? = null,
    private val intervalMs: Long = 1_000,
    private val timeoutMs: Long = 800,
) {
    private val checks = ConcurrentHashMap<String, HealthCheck>()
    private val lastHeartbeat = ConcurrentHashMap<String, Long>()
    private var job: Job? = null

    companion object {
        val CORE_MONITORED_MODULES = listOf(
            "AICore", "Memory", "Planning", "Reasoning", "Voice", "Vision", "DeviceControl",
            "AndroidIntegration", "ApiLayer", "Plugins", "Synchronization", "Monitoring", "Security",
        )
    }

    fun register(moduleName: String, check: HealthCheck) {
        checks[moduleName] = check
        lastHeartbeat[moduleName] = System.currentTimeMillis()
    }

    fun unregister(moduleName: String) {
        checks.remove(moduleName)
        lastHeartbeat.remove(moduleName)
    }

    fun reportHeartbeat(moduleName: String, latencyMs: Long = 0, memoryUsageBytes: Long = 0) {
        lastHeartbeat[moduleName] = System.currentTimeMillis()
        EventBus.tryPublish(CoreEvent.ModuleHeartbeat(moduleName, latencyMs, memoryUsageBytes))
    }

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(intervalMs)
                checkAll()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun checkAll() {
        for ((module, check) in checks) {
            val healthy = try {
                withTimeoutOrNull(timeoutMs) { check.ping() } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }

            if (healthy) {
                lastHeartbeat[module] = System.currentTimeMillis()
                moduleRegistry?.updateHealth(module, 1.0f)
                recoveryManager.recordSuccess(module)
            } else {
                EventBus.tryPublish(CoreEvent.ModuleHealthCheckFailed(module))
                moduleRegistry?.let {
                    it.updateHealth(module, 0.0f)
                    it.updateState(module, ModuleState.DEGRADED)
                }
                onModuleUnresponsive?.invoke(module)
                recoveryManager.recordFailure(module, RuntimeException("Watchdog: модуль '$module' не ответил за ${timeoutMs}мс"))
            }
        }
    }
}
