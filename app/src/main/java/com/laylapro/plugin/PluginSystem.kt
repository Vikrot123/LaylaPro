package com.laylapro.plugin

/**
 * Модуль 14 (Слой 6) — динамическая подгрузка функционала
 * (см. ТЗ, Часть II, п.14: DexClassLoader для .apk/.dex, либо JS-плагины в QuickJS).
 */
interface IPlugin {
    val id: String
    val manifest: PluginManifest
    suspend fun execute(action: String, params: Map<String, Any?>): PluginResult
}

data class PluginManifest(
    val name: String,
    val version: String,
    val supportedActions: List<String>,
)

data class PluginResult(val success: Boolean, val output: String)

/**
 * STUB: реестр плагинов в памяти. Реальная динамическая загрузка .dex/.apk через
 * DexClassLoader — это отдельная (и рискованная с точки зрения Google Play Policy)
 * задача, вынесенная за рамки MVP.
 */
class PluginSystem {
    private val plugins = mutableMapOf<String, IPlugin>()

    fun register(plugin: IPlugin) {
        plugins[plugin.id] = plugin
    }

    fun get(id: String): IPlugin? = plugins[id]

    fun listManifests(): List<PluginManifest> = plugins.values.map { it.manifest }
}
