package com.laylapro.planning

import com.laylapro.api.ToolParam
import com.laylapro.api.ToolSpec

/**
 * Единый каталог инструментов, доступных Planning Engine через Function Calling
 * (Anthropic tool use). Имя ToolSpec.name сопоставляется с парой (moduleName, action),
 * которые дальше уходят в TaskStep -> Dispatcher.dispatch(Command(...)).
 *
 * Добавление нового инструмента = один Entry здесь + ModuleHandler,
 * зарегистрированный в Dispatcher (см. LaylaApplication.kt).
 */
object ToolCatalog {

    data class Entry(val spec: ToolSpec, val moduleName: String, val action: String)

    val entries: List<Entry> = listOf(
        Entry(
            spec = ToolSpec(
                name = "device_control_set_wifi",
                description = "Включить или выключить Wi-Fi на устройстве (на Android 10+ открывает системную панель).",
                parameters = mapOf(
                    "enabled" to ToolParam(type = "boolean", description = "true — включить Wi-Fi, false — выключить"),
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
                description = "Нажать на элемент интерфейса в текущем открытом стороннем приложении по видимому " +
                    "тексту (через Accessibility Service, без root). Пользователь должен заранее включить " +
                    "Специальные возможности LaylaPro в системных настройках Android.",
                parameters = mapOf(
                    "text" to ToolParam(type = "string", description = "Видимый текст элемента, на который нужно нажать"),
                ),
                required = listOf("text"),
            ),
            moduleName = "AndroidIntegration",
            action = "click_by_text",
        ),
    )

    fun byName(name: String): Entry? = entries.find { it.spec.name == name }
    fun specs(): List<ToolSpec> = entries.map { it.spec }
}
