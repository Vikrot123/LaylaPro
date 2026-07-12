package com.laylapro.runtime

import com.laylapro.emotion.EmotionState
import java.util.concurrent.atomic.AtomicReference

/**
 * Context Manager (Том 98): "Формирует глобальный контекст системы... Контекст
 * предоставляется всем модулям только через Context Manager."
 *
 * Иммутабельный снимок + атомарная замена — читатели всегда видят консистентное
 * состояние, без частичных обновлений на середине записи.
 */
data class GlobalContext(
    val activeApp: String? = null,
    val screenState: String? = null,
    val dialogHistory: List<String> = emptyList(),
    val workingMemorySummary: String? = null,
    val activeTaskIds: List<String> = emptyList(),
    val emotionalState: EmotionState? = null,
    val lastUserAction: String? = null,
    val networkState: String = "unknown",
    val deviceState: String = "unknown",
)

class ContextManager {

    private val ref = AtomicReference(GlobalContext())

    fun current(): GlobalContext = ref.get()

    fun update(transform: (GlobalContext) -> GlobalContext) {
        ref.updateAndGet(transform)
    }

    fun setActiveApp(app: String) = update { it.copy(activeApp = app) }
    fun setScreenState(state: String) = update { it.copy(screenState = state) }
    fun appendDialogHistory(line: String, maxEntries: Int = 50) = update {
        it.copy(dialogHistory = (it.dialogHistory + line).takeLast(maxEntries))
    }
    fun setEmotionalState(state: EmotionState) = update { it.copy(emotionalState = state) }
    fun setLastUserAction(action: String) = update { it.copy(lastUserAction = action) }
    fun setNetworkState(state: String) = update { it.copy(networkState = state) }
    fun setDeviceState(state: String) = update { it.copy(deviceState = state) }
    fun setActiveTaskIds(ids: List<String>) = update { it.copy(activeTaskIds = ids) }
}
