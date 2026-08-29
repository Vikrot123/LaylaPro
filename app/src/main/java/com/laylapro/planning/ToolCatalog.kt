package com.laylapro.planning

import com.laylapro.api.ToolParam
import com.laylapro.api.ToolSpec

/** Function-calling catalogue. Risk metadata travels with the tool into TaskStep. */
object ToolCatalog {
    data class Entry(
        val spec: ToolSpec,
        val moduleName: String,
        val action: String,
        val requiresUserConfirmation: Boolean = false,
    )

    val entries: List<Entry> = listOf(
        Entry(
            spec = ToolSpec(
                name = "device_control_set_wifi",
                description = "Включить или выключить Wi-Fi на устройстве (на Android 10+ открывает системную панель).",
                parameters = mapOf(
                    "enabled" to ToolParam("boolean", "true — включить Wi-Fi, false — выключить"),
                ),
                required = listOf("enabled"),
            ),
            moduleName = "DeviceControl",
            action = "set_wifi_state",
        ),
        Entry(
            spec = ToolSpec(
                name = "device_control_open_bluetooth_settings",
                description = "Открыть системные настройки Bluetooth.",
                parameters = emptyMap(),
            ),
            moduleName = "DeviceControl",
            action = "open_bluetooth_settings",
        ),
        Entry(
            spec = ToolSpec(
                name = "device_control_open_sound_settings",
                description = "Открыть системные настройки звука.",
                parameters = emptyMap(),
            ),
            moduleName = "DeviceControl",
            action = "open_sound_settings",
        ),
        Entry(
            spec = ToolSpec(
                name = "android_integration_click_by_text",
                description = "Нажать на элемент интерфейса в текущем стороннем приложении по видимому тексту через Accessibility Service.",
                parameters = mapOf(
                    "text" to ToolParam("string", "Видимый текст элемента, на который нужно нажать"),
                ),
                required = listOf("text"),
            ),
            moduleName = "AndroidIntegration",
            action = "click_by_text",
            // Until a real UI/biometric approval bridge is connected, model-generated
            // accessibility clicks must fail closed rather than execute silently.
            requiresUserConfirmation = true,
        ),
    )

    fun byName(name: String): Entry? = entries.find { it.spec.name == name }
    fun specs(): List<ToolSpec> = entries.map { it.spec }
}
