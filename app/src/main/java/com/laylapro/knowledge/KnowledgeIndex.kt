package com.laylapro.knowledge

import com.laylapro.vectormemory.VectorMemory

/**
 * Knowledge Index — Этап 2, пункт 3.
 *
 * Объединяет два независимых сигнала поиска в одну ранжированную выдачу для `RagEngine`
 * (пункт 4): [VectorMemory] (семантическая/лексическая близость эмбеддингов, Этап 2,
 * пункт 2) и уже существующий `KnowledgeBase` (Этап 1 — простые факты по ключу/substring,
 * работает как надёжный fallback, когда эмбеддинг-модель "промахивается" по чисто
 * лексическому совпадению). Единственная ответственность — индексация и поиск;
 * решение о ТОМ, что именно достойно попасть в индекс, принимает Memory Consolidation
 * (Этап 2, пункт 5) — здесь этого решения нет намеренно (Single Responsibility).
 */
data class KnowledgeResult(
    val text: String,
    val score: Float,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
)

interface KnowledgeIndex {
    suspend fun index(text: String, metadata: Map<String, String> = emptyMap())
    suspend fun search(query: String, limit: Int = 5): List<KnowledgeResult>
}

class HybridKnowledgeIndex(
    private val vectorMemory: VectorMemory,
    private val knowledgeBase: KnowledgeBase,
    private val vectorWeight: Float = 0.7f,
    private val keywordWeight: Float = 0.3f,
) : KnowledgeIndex {

    override suspend fun index(text: String, metadata: Map<String, String>) {
        vectorMemory.add(text, metadata)
        // Дублируем в KnowledgeBase под детерминированным ключом — обеспечивает
        // keyword-fallback поиск той же записи (см. search()) без второго прохода по эмбеддингам.
        knowledgeBase.storeFact(key = "idx_${text.hashCode()}", value = text)
    }

    override suspend fun search(query: String, limit: Int): List<KnowledgeResult> {
        val vectorResults = vectorMemory.search(query, limit = limit * 2)
            .map { KnowledgeResult(it.record.text, it.score * vectorWeight, "vector", it.record.metadata) }

        val keywordResults = knowledgeBase.searchBySubstring(query)
            .map { (_, value) -> KnowledgeResult(value, keywordWeight, "keyword") }

        // Дедупликация по тексту: если запись нашлась ОБОИМИ путями — берём максимальный
        // скор и помечаем оба источника (сигнал, что совпадение особенно надёжное).
        return (vectorResults + keywordResults)
            .groupBy { it.text }
            .map { (text, group) ->
                KnowledgeResult(
                    text = text,
                    score = group.maxOf { it.score },
                    source = group.joinToString(",") { it.source }.split(",").distinct().joinToString(","),
                    metadata = group.first().metadata,
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
    }
}
