package com.laylapro.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Module Registry (Том 98): хранилище всех зарегистрированных модулей.
 * "При отсутствии регистрации модуль не может участвовать в работе системы" —
 * поэтому [Dispatcher.register] в этой версии дергает [ModuleRegistry.register]
 * внутри [RuntimeManager], а не работает в обход реестра.
 */
enum class ModuleState { UNINITIALIZED, STARTING, RUNNING, DEGRADED, STOPPED }

data class ModuleDescriptor(
    val moduleId: String,
    val moduleName: String,
    val version: String = "0.1.0",
    val dependencies: List<String> = emptyList(),
    val priority: TaskPriority = TaskPriority.NORMAL,
    var state: ModuleState = ModuleState.UNINITIALIZED,
    var health: Float = 1.0f, // 0.0 (мертв) .. 1.0 (полностью здоров)
    val capabilities: List<String> = emptyList(),
)

class ModuleRegistry {

    private val modules = ConcurrentHashMap<String, ModuleDescriptor>()

    fun register(descriptor: ModuleDescriptor) {
        modules[descriptor.moduleId] = descriptor
    }

    fun unregister(moduleId: String) {
        modules.remove(moduleId)
    }

    fun get(moduleId: String): ModuleDescriptor? = modules[moduleId]

    fun isRegistered(moduleId: String): Boolean = modules.containsKey(moduleId)

    fun updateState(moduleId: String, state: ModuleState) {
        modules[moduleId]?.state = state
    }

    fun updateHealth(moduleId: String, health: Float) {
        modules[moduleId]?.health = health.coerceIn(0f, 1f)
    }

    /** Проверяет, что все зависимости модуля зарегистрированы и не в состоянии STOPPED. */
    fun dependenciesSatisfied(moduleId: String): Boolean {
        val descriptor = modules[moduleId] ?: return false
        return descriptor.dependencies.all { depId ->
            val dep = modules[depId]
            dep != null && dep.state != ModuleState.STOPPED
        }
    }

    fun all(): List<ModuleDescriptor> = modules.values.toList()
}
