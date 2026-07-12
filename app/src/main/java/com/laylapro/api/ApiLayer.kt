package com.laylapro.api

/**
 * Модуль 15 (Слой 6) — общение с внешними LLM по HTTP/REST
 * (см. ТЗ, Часть II, п.15). Абстракция позволяет подключать разных
 * провайдеров (Anthropic, OpenAI, Google, DeepSeek) без изменения
 * остального кода — Reasoning/Planning/Conversation зависят только от этого интерфейса.
 */
interface ApiLayer {
    /**
     * Простой синхронный (по семантике suspend) запрос "система + сообщение пользователя -> текст".
     * Для потоковой генерации (SSE) см. [completeStreaming].
     */
    suspend fun complete(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): String

    /**
     * Function Calling: модель сама решает, вызывать ли один из [tools] (Anthropic
     * "tool use"), либо просто ответить текстом. Используется Planning Engine,
     * чтобы решать, нужно ли дёргать Device Control / Android Integration Layer,
     * вместо всегда-текстового ответа.
     */
    suspend fun completeWithTools(
        systemPrompt: String,
        userMessage: String,
        tools: List<ToolSpec>,
        maxTokens: Int = 1024,
        temperature: Float = 0.4f,
    ): ToolCallResponse

    /**
     * Потоковая версия — эмитит чанки текста по мере генерации (Server-Sent Events).
     * Используется чат-экраном, чтобы ответ печатался постепенно.
     */
    fun completeStreaming(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): kotlinx.coroutines.flow.Flow<String>
}

/** Описание инструмента в формате, близком к JSON Schema (см. Anthropic tool use). */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema свойств параметров, например {"enabled": {"type": "boolean"}}. */
    val parameters: Map<String, ToolParam>,
    val required: List<String> = emptyList(),
)

data class ToolParam(val type: String, val description: String, val enumValues: List<String>? = null)

data class ToolCall(val id: String, val toolName: String, val input: Map<String, Any?>)

data class ToolCallResponse(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList(),
)

class ApiLayerException(message: String, cause: Throwable? = null) : Exception(message, cause)
