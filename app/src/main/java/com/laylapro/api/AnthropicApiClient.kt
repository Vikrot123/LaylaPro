package com.laylapro.api

import com.laylapro.util.SecureStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Модуль 15 (API Layer) — клиент Anthropic Claude Messages API.
 *
 * Документация: https://docs.claude.com/en/api/messages
 * Модель по умолчанию: claude-sonnet-5 (актуальная на момент написания;
 * при необходимости смотри Anthropic Model overview на предмет более новых ID).
 *
 * ВАЖНО: ключ API никогда не хардкодится — берётся из [SecureStorage]
 * (Android Keystore-backed EncryptedSharedPreferences, см. Security Layer в ТЗ).
 */
class AnthropicApiClient(
    private val secureStorage: SecureStorage,
    private val model: String = DEFAULT_MODEL,
) : ApiLayer {

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        // Патч 2: retry только для временных ошибок (сетевой сбой, 429, 5xx) — НЕ для
        // ошибок аутентификации/некорректного запроса, повтор которых бессмыслен.
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 500L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<AnthropicMessage>,
        val temperature: Float,
        val stream: Boolean = false,
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class AnthropicResponse(
        val content: List<ContentBlock> = emptyList(),
        val error: ApiError? = null,
    )

    @Serializable
    private data class ContentBlock(val type: String, val text: String? = null)

    @Serializable
    private data class ApiError(val type: String? = null, val message: String? = null)

    private fun apiKey(): String =
        secureStorage.getApiKey()
            ?: throw ApiLayerException(
                "API-ключ Anthropic не задан. Откройте Настройки в приложении и укажите ключ " +
                    "(console.anthropic.com -> API Keys)."
            )

    private fun buildRequest(body: AnthropicRequest): Request {
        val payload = json.encodeToString(AnthropicRequest.serializer(), body)
        return Request.Builder()
            .url(BASE_URL)
            .addHeader("x-api-key", apiKey())
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Патч 2: безопасный retry ТОЛЬКО для временных ошибок — сетевой сбой (IOException,
     * например обрыв соединения/DNS/таймаут) и HTTP 429/5xx (перегрузка/временная
     * недоступность сервера). НЕ повторяет запрос при 4xx, кроме 429 (400/401/403 и т.п. —
     * не временные, повтор ничего не изменит и только зря потратит время/квоту).
     * Не меняет публичный контракт [ApiLayer] — используется только внутри этого класса.
     */
    private suspend fun executeWithRetry(request: Request): Response {
        var lastError: Throwable? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val response = httpClient.newCall(request).executeSuspend()
                if (response.isSuccessful || !isTransientHttpError(response.code)) {
                    return response // успех ИЛИ окончательная (не временная) ошибка — не повторяем
                }
                lastError = ApiLayerException("Anthropic API временная ошибка ${response.code} (попытка $attempt/$MAX_ATTEMPTS)")
                response.close()
            } catch (e: CancellationException) {
                throw e // Патч 1: отмену корутины никогда не проглатываем, в том числе внутри retry-цикла
            } catch (e: IOException) {
                lastError = e // сетевой сбой — считаем временным, пробуем ещё раз
            }

            if (attempt < MAX_ATTEMPTS) {
                delay(RETRY_BASE_DELAY_MS * attempt) // небольшой линейный backoff: 500мс, затем 1000мс
            }
        }

        throw ApiLayerException(
            "Anthropic API недоступен после $MAX_ATTEMPTS попыток: ${lastError?.message}",
            lastError as? Exception,
        )
    }

    private fun isTransientHttpError(code: Int): Boolean = code == 429 || code in 500..599

    override suspend fun complete(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Float,
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(
            AnthropicRequest(
                model = model,
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
                temperature = temperature,
                stream = false,
            )
        )

        executeWithRetry(request).use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsedError = runCatching { json.decodeFromString(AnthropicResponse.serializer(), bodyStr) }.getOrNull()
                throw ApiLayerException(
                    "Anthropic API ошибка ${response.code}: ${parsedError?.error?.message ?: bodyStr}"
                )
            }
            val parsed = json.decodeFromString(AnthropicResponse.serializer(), bodyStr)
            parsed.content.firstOrNull { it.type == "text" }?.text
                ?: throw ApiLayerException("Пустой ответ от Anthropic API")
        }
    }

    override suspend fun completeWithTools(
        systemPrompt: String,
        userMessage: String,
        tools: List<ToolSpec>,
        maxTokens: Int,
        temperature: Float,
    ): ToolCallResponse = withContext(Dispatchers.IO) {
        val toolsJson = buildJsonArray {
            tools.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    putJsonObject("input_schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            tool.parameters.forEach { (paramName, spec) ->
                                putJsonObject(paramName) {
                                    put("type", spec.type)
                                    put("description", spec.description)
                                    spec.enumValues?.let { values ->
                                        putJsonArray("enum") { values.forEach { add(it) } }
                                    }
                                }
                            }
                        }
                        putJsonArray("required") { tool.required.forEach { add(it) } }
                    }
                }
            }
        }

        val bodyJson = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("temperature", temperature.toDouble())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                }
            }
            put("tools", toolsJson)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("x-api-key", apiKey())
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeWithRetry(request).use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiLayerException("Anthropic API ошибка ${response.code}: $bodyStr")
            }

            val root = json.parseToJsonElement(bodyStr).jsonObject
            val contentArray = (root["content"] as? JsonArray) ?: JsonArray(emptyList())

            var text: String? = null
            val toolCalls = mutableListOf<ToolCall>()

            for (block in contentArray) {
                val obj = block.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> text = obj["text"]?.jsonPrimitive?.contentOrNull
                    "tool_use" -> {
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val input = (obj["input"] as? JsonObject)
                            ?.mapValues { (_, v) -> jsonElementToAny(v) }
                            ?: emptyMap()
                        toolCalls.add(ToolCall(id = id, toolName = name, input = input))
                    }
                }
            }

            ToolCallResponse(text = text, toolCalls = toolCalls)
        }
    }

    /** Плоское преобразование JsonElement -> Kotlin-примитив/List/Map для input тул-вызова. */
    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        is JsonPrimitive -> element.booleanOrNull
            ?: element.doubleOrNull
            ?: element.contentOrNull
    }

    override fun completeStreaming(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Float,
    ): Flow<String> = callbackFlow {
        val request = buildRequest(
            AnthropicRequest(
                model = model,
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = listOf(AnthropicMessage(role = "user", content = userMessage)),
                temperature = temperature,
                stream = true,
            )
        )

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject
                    val eventType = root["type"]?.jsonPrimitive?.content
                    if (eventType == "content_block_delta") {
                        val delta = root["delta"]?.jsonObject
                        val text = delta?.get("text")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) trySend(text)
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(ApiLayerException("Ошибка потоковой генерации: ${t?.message ?: response?.message}", t))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * Патч 4: лёгкая проверка работоспособности сохранённого ключа — минимальный запрос
     * (max_tokens=1), чтобы не тратить впустую квоту пользователя. Намеренно НЕ использует
     * [executeWithRetry]: невалидный ключ не станет валидным после повтора, а лишняя задержка
     * ухудшит отклик экрана настроек, где пользователь ждёт немедленного результата.
     */
    suspend fun validateApiKey(): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        val request = buildRequest(
            AnthropicRequest(
                model = model,
                maxTokens = 1,
                system = "",
                messages = listOf(AnthropicMessage(role = "user", content = "Hi")),
                temperature = 0f,
                stream = false,
            )
        )
        try {
            httpClient.newCall(request).executeSuspend().use { response ->
                when {
                    response.isSuccessful -> ApiKeyValidationResult.Valid
                    response.code == 401 || response.code == 403 -> ApiKeyValidationResult.InvalidKey
                    response.code == 429 -> ApiKeyValidationResult.QuotaExceeded
                    response.code in 500..599 -> ApiKeyValidationResult.ServerError(response.code)
                    else -> ApiKeyValidationResult.Unknown("HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e // Патч 1: отмену корутины (например, уход с экрана настроек) не проглатываем
        } catch (e: IOException) {
            ApiKeyValidationResult.NoInternet
        } catch (e: Exception) {
            ApiKeyValidationResult.Unknown(e.message ?: "неизвестная ошибка")
        }
    }
}

/** Патч 4: результат проверки ключа — ровно те исходы, которые запрошены явно. */
sealed class ApiKeyValidationResult {
    object Valid : ApiKeyValidationResult()
    object InvalidKey : ApiKeyValidationResult()
    object QuotaExceeded : ApiKeyValidationResult()
    object NoInternet : ApiKeyValidationResult()
    data class ServerError(val code: Int) : ApiKeyValidationResult()
    data class Unknown(val message: String) : ApiKeyValidationResult()
}

/** Небольшой suspend-обёртка над OkHttp Call, чтобы не тянуть отдельную корутин-либу. */
private suspend fun Call.executeSuspend(): Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response))
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
