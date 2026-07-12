package com.laylapro.memory

import java.util.UUID

/**
 * Модуль 4 (Слой 2) — маршрутизация данных между типами памяти.
 * Ранжирование контекста: Score = w1*Recency + w2*Relevancy + w3*Importance
 * (см. ТЗ, Часть II, п.4).
 */
interface MemorySystem {
    suspend fun save(entry: MemoryEntry, type: MemoryType)
    suspend fun queryRelevantContext(sessionId: String, limit: Int = 8): List<MemoryEntry>

    /**
     * Явное запоминание по прямому жесту пользователя (например, "Layla, запомни это"
     * из системного меню выделения текста — см. анализ Layla, §4 "User-triggered память").
     * В отличие от [save], который используется для рутинного накопления диалога,
     * этот путь всегда идёт в LONG_TERM с максимальной важностью — пользователь явно
     * сказал "это важно", и это не должно быть вытеснено обычной эвристикой Recency/Importance.
     */
    suspend fun rememberExplicit(sessionId: String, text: String, tags: List<String> = emptyList()) {
        save(
            MemoryEntry(
                sessionId = sessionId,
                rawText = text,
                importanceScore = 1.0f,
                contextTags = tags + "user_explicit",
                type = MemoryType.LONG_TERM,
            ),
            MemoryType.LONG_TERM,
        )
    }

    /**
     * Патч 3: удаляет запись памяти по идентификатору. Не бросает исключение,
     * если записи с таким id уже нет — удаление отсутствующей записи не является
     * ошибкой вызывающего кода.
     */
    suspend fun delete(id: UUID)

    /**
     * Патч 3: обновляет текст (и, опционально, важность) уже существующей записи,
     * сохраняя остальные поля (sessionId/timestamp/type/tags) без изменений. Если
     * записи с таким id нет — не делает ничего (тот же принцип мягкого отсутствия
     * ошибки, что и в [delete]). Не меняет модель памяти и не затрагивает
     * [save]/[queryRelevantContext]/[rememberExplicit].
     */
    suspend fun update(id: UUID, newText: String, newImportanceScore: Float? = null)
}

enum class MemoryType { WORKING, SHORT_TERM, LONG_TERM, EPISODIC, SEMANTIC, SKILL }

data class MemoryEntry(
    val id: UUID = UUID.randomUUID(),
    val sessionId: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val importanceScore: Float = 0.5f,
    val contextTags: List<String> = emptyList(),
    val type: MemoryType = MemoryType.SHORT_TERM,
)
