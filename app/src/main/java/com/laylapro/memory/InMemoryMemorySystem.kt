package com.laylapro.memory

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MVP-реализация Memory System: хранит записи в оперативной памяти процесса.
 *
 * Полный контракт из ТЗ (Часть II, п.4) предполагает queryRelevantContext(queryEmbedding,
 * limit) на основе векторного поиска — это требует Knowledge Base (модуль 5) с реальными
 * эмбеддингами (bge-micro-v2 или аналог) и SQLite/ObjectBox-хранилища на диске.
 *
 * TODO(persist): заменить CopyOnWriteArrayList на Room/SQLite DAO поверх схемы
 * embeddings_db / EpisodicMemory, описанной в ТЗ.
 * TODO(semantic): подключить Knowledge Base для по-настоящему релевантного (не только
 * по recency/importance) поиска контекста.
 */
class InMemoryMemorySystem : MemorySystem {

    private val entries = CopyOnWriteArrayList<MemoryEntry>()

    override suspend fun save(entry: MemoryEntry, type: MemoryType) {
        entries.add(entry.copy(type = type))
    }

    override suspend fun queryRelevantContext(sessionId: String, limit: Int): List<MemoryEntry> {
        val now = System.currentTimeMillis()
        // Явно запомненное (LONG_TERM, importance ~1.0 — см. MemorySystem.rememberExplicit)
        // видно из ЛЮБОЙ сессии: пользователь сказал "запомни это" не для одного разговора,
        // а навсегда. Остальное — обычная память, привязанная к конкретной сессии.
        val globalLongTerm = entries.filter { it.type == MemoryType.LONG_TERM && it.importanceScore >= 0.9f }
        val sessionScoped = entries.filter { it.sessionId == sessionId && it !in globalLongTerm }

        return (globalLongTerm + sessionScoped)
            .distinct()
            .sortedByDescending { score(it, now) }
            .take(limit)
            .sortedBy { it.timestamp } // хронологический порядок для промпта
    }

    /** Score = w1*Recency + w2*Relevancy + w3*Importance (Relevancy пока не вычисляется — см. TODO). */
    private fun score(entry: MemoryEntry, now: Long): Float {
        val ageMinutes = (now - entry.timestamp) / 60000.0
        val recency = (1.0 / (1.0 + ageMinutes)).toFloat() // чем свежее, тем ближе к 1
        val w1 = 0.5f
        val w3 = 0.5f
        return w1 * recency + w3 * entry.importanceScore
    }

    // Патч 3: delete/update — не меняют модель памяти, только добавляют недостающий CRUD.
    override suspend fun delete(id: UUID) {
        entries.removeIf { it.id == id }
    }

    override suspend fun update(id: UUID, newText: String, newImportanceScore: Float?) {
        val existing = entries.find { it.id == id } ?: return
        val updated = existing.copy(
            rawText = newText,
            importanceScore = newImportanceScore ?: existing.importanceScore,
        )
        entries.replaceAll { if (it.id == id) updated else it }
    }
}
