package com.laylapro.monitoring

import android.app.ActivityManager
import android.content.Context
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Модуль 19 (Слой 7) — телеметрия и предотвращение OOM
 * (см. ТЗ, Часть II, п.19). Таймер с шагом 500 мс проверяет доступную RAM;
 * при превышении критического лимита публикует LowMemoryAlert в Event Bus,
 * на который в будущем подписывается Infrastructure Layer (выгрузка локальной модели)
 * или API Layer (принудительный переход в облачный режим).
 */
class MonitoringLayer(private val context: Context) {

    private val activityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val scope = CoroutineScope(Dispatchers.Default)

    private companion object {
        const val CHECK_INTERVAL_MS = 500L
        const val LOW_MEMORY_THRESHOLD_BYTES = 512L * 1024 * 1024 // 512 МБ, как в ТЗ
    }

    fun start() {
        scope.launch {
            while (true) {
                checkSystemHealth()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkSystemHealth() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        if (memoryInfo.lowMemory || memoryInfo.availMem < LOW_MEMORY_THRESHOLD_BYTES) {
            EventBus.publish(CoreEvent.LowMemoryAlert(memoryInfo.availMem))
        }
    }
}
