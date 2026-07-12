package com.laylapro.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Health Manager (Том 98): "Каждый модуль регулярно отправляет состояние:
 * Health, Latency, MemoryUsage, CPUUsage, Status, LastHeartbeat. При отсутствии
 * Heartbeat более установленного времени модуль считается недоступным."
 *
 * WatchDog активно опрашивает модули (pull); HealthManager дополняет это пассивным
 * приёмом heartbeat'ов (push) — модуль сам сообщает о себе после тяжёлой операции.
 * Обе стороны сходятся в одном месте: [WatchDog.reportHeartbeat] тоже пишет сюда.
 */
data class ModuleHealthReport(
    val moduleName: String,
    val health: Float = 1.0f,
    val latencyMs: Long = 0,
    val memoryUsageBytes: Long = 0,
    val cpuUsagePercent: Float = 0f,
    val status: ModuleState = ModuleState.RUNNING,
    val lastHeartbeatAt: Long = System.currentTimeMillis(),
)

class HealthManager(private val staleAfterMs: Long = 10_000) {

    private val reports = ConcurrentHashMap<String, ModuleHealthReport>()

    fun report(report: ModuleHealthReport) {
        reports[report.moduleName] = report.copy(lastHeartbeatAt = System.currentTimeMillis())
    }

    fun reportSimple(moduleName: String, health: Float, latencyMs: Long = 0, memoryUsageBytes: Long = 0) {
        report(ModuleHealthReport(moduleName, health, latencyMs, memoryUsageBytes))
    }

    /** true, если модуль присылал heartbeat недавно (в пределах [staleAfterMs]). */
    fun isAvailable(moduleName: String): Boolean {
        val last = reports[moduleName]?.lastHeartbeatAt ?: return false
        return System.currentTimeMillis() - last <= staleAfterMs
    }

    fun get(moduleName: String): ModuleHealthReport? = reports[moduleName]

    fun snapshotAll(): List<ModuleHealthReport> = reports.values.toList()

    fun staleModules(): List<String> {
        val now = System.currentTimeMillis()
        return reports.values.filter { now - it.lastHeartbeatAt > staleAfterMs }.map { it.moduleName }
    }
}
