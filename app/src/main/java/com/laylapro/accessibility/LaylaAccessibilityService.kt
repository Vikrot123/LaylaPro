package com.laylapro.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Модуль 13 (Слой 5) — автоматизация БЕЗ Root: клики и скролл в сторонних
 * приложениях (см. ТЗ, Часть II, п.13). Пользователь должен вручную включить
 * сервис в Настройки -> Специальные возможности -> LaylaPro.
 */
class LaylaAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Логирование состояния экрана для Vision Engine (модуль 11).
        // TODO: публиковать снимок дерева экрана в EventBus, чтобы Vision Engine
        // мог строить UI Tree без отдельного скриншота через MediaProjection.
    }

    override fun onInterrupt() {
        // Сервис отключён системой — специальных действий не требуется.
    }

    /** Находит кликабельный элемент по тексту и программно нажимает на него. */
    fun performClickByText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Если сам найденный узел не кликабелен, пробуем кликабельного родителя
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        return false
    }

    companion object {
        /** Активный экземпляр сервиса, если пользователь его включил (иначе null). */
        var instance: LaylaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
