package com.laylapro.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus

/**
 * Resource Manager (Том 98): "Мониторинг RAM/CPU/GPU/NPU/Battery/Temperature/Storage.
 * При превышении лимитов: выгрузить неиспользуемые модели; очистить кэш;
 * приостановить тяжёлые задачи; переключить вычисления на облачный API."
 *
 * GPU/NPU/Temperature не имеют стабильного публичного Android API без вендорских
 * расширений — эти поля оставлены как TODO-хуки для конкретных чипсетов
 * (Qualcomm/MediaTek SDK), см. также Infrastructure Layer (модуль 18).
 */
data class ResourceSnapshot(
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val freeStorageBytes: Long,
)

fun interface ResourcePressureAction {
    /** Вызывается, когда ресурс превышает допустимый лимит. Не должен бросать исключения. */
    fun onPressure(snapshot: ResourceSnapshot)
}

class ResourceManager(context: Context) {

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val pressureActions = mutableListOf<ResourcePressureAction>()

    companion object {
        const val LOW_MEMORY_THRESHOLD_BYTES = 512L * 1024 * 1024 // 512 МБ
        const val LOW_BATTERY_THRESHOLD_PERCENT = 15
        const val LOW_STORAGE_THRESHOLD_BYTES = 200L * 1024 * 1024 // 200 МБ
    }

    fun onPressure(action: ResourcePressureAction) {
        pressureActions.add(action)
    }

    fun snapshot(): ResourceSnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val batteryStatus = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPercent = batteryStatus?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = batteryStatus?.isCharging ?: true

        val stat = StatFs(appContext.filesDir.path)
        val freeStorage = stat.availableBytes

        return ResourceSnapshot(
            availableRamBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            freeStorageBytes = freeStorage,
        )
    }

    /** Проверяет текущий снимок ресурсов и публикует ResourcePressure + вызывает зарегистрированные действия. */
    fun checkAndAct() {
        val snap = snapshot()

        if (snap.lowMemory || snap.availableRamBytes < LOW_MEMORY_THRESHOLD_BYTES) {
            notifyPressure("RAM", snap, "unload_models_and_clear_cache")
        }
        if (!snap.isCharging && snap.batteryPercent < LOW_BATTERY_THRESHOLD_PERCENT) {
            notifyPressure("BATTERY", snap, "pause_heavy_tasks_switch_to_cloud")
        }
        if (snap.freeStorageBytes < LOW_STORAGE_THRESHOLD_BYTES) {
            notifyPressure("STORAGE", snap, "clear_cache")
        }
    }

    private fun notifyPressure(kind: String, snap: ResourceSnapshot, action: String) {
        EventBus.tryPublish(CoreEvent.ResourcePressure(kind = kind, value = snap.availableRamBytes.toFloat(), action = action))
        pressureActions.forEach { runCatching { it.onPressure(snap) } }
    }
}
