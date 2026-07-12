package com.laylapro.vectormemory

import com.laylapro.embedding.EmbeddingEngine
import com.laylapro.embedding.cosineSimilarity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Vector Memory — Этап 2, пункт 2.
 *
 * Отличие от `memory.MemorySystem` (Этап 1): MemorySystem ранжирует записи по
 * Recency+Importance (эвристика без эмбеддингов, см. InMemoryMemorySystem). Vector
 * Memory даёт СЕМАНТИЧЕСКИЙ (точнее — лексический, см. TODO в EmbeddingEngine.kt)
 * поиск по содержанию через косинусное сходство эмбеддингов. `KnowledgeIndex` (пункт 3)
 * объединяет оба сигнала для `RagEngine` (пункт 4).
 */
data class VectorRecord(
    val id: UUID = UUID.randomUUID(),
    val text: String,
    val vector: FloatArray,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
)

data class VectorSearchResult(val record: VectorRecord, val score: Float)

interface VectorMemory {
    suspend fun add(text: String, metadata: Map<String, String> = emptyMap()): VectorRecord
    suspend fun search(query: String, limit: Int = 5, minScore: Float = 0f): List<VectorSearchResult>
    suspend fun delete(id: UUID)
    suspend fun deleteByMetadata(key: String, value: String)
    fun size(): Int
}

/**
 * MVP-реализация: полный перебор (brute-force cosine similarity) в памяти процесса.
 * Для сотен-тысяч записей этого достаточно (O(N) на запрос, N — типичный размер личной
 * памяти пользователя). TODO(масштаб): при росте N на порядки — приближённый поиск
 * (HNSW/IVF) или персистентная БД с векторным индексом (см. Blueprint, Этап 2 план);
 * интерфейс [VectorMemory] уже готов к такой замене без изменения вызывающего кода.
 */
class InMemoryVectorMemory(private val embeddingEngine: EmbeddingEngine) : VectorMemory {

    private val records = CopyOnWriteArrayList<VectorRecord>()

    override suspend fun add(text: String, metadata: Map<String, String>): VectorRecord {
        val vector = embeddingEngine.embed(text)
        val record = VectorRecord(text = text, vector = vector, metadata = metadata)
        records.add(record)
        return record
    }

    override suspend fun search(query: String, limit: Int, minScore: Float): List<VectorSearchResult> {
        if (records.isEmpty()) return emptyList()
        val queryVector = embeddingEngine.embed(query)

        return records
            .map { VectorSearchResult(it, cosineSimilarity(queryVector, it.vector)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    override suspend fun delete(id: UUID) {
        records.removeIf { it.id == id }
    }

    override suspend fun deleteByMetadata(key: String, value: String) {
        records.removeIf { it.metadata[key] == value }
    }

    override fun size(): Int = records.size
}
