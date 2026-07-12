package com.laylapro.rag

import com.laylapro.knowledge.KnowledgeIndex

/**
 * RAG Engine — Этап 2, пункт 4.
 *
 * Единственная ответственность: по запросу пользователя вернуть релевантные фрагменты
 * из [KnowledgeIndex], отфильтрованные по порогу уверенности — НЕ решает, как их
 * встроить в промпт (это `PromptEngine`, см. `PromptContext.ragContext`), и НЕ решает,
 * что должно попасть в индекс (это Memory Consolidation, Этап 2, пункт 5).
 */
interface RagEngine {
    suspend fun retrieve(query: String, limit: Int = 3): List<String>
}

class DefaultRagEngine(
    private val knowledgeIndex: KnowledgeIndex,
    private val minScore: Float = 0.15f,
) : RagEngine {

    override suspend fun retrieve(query: String, limit: Int): List<String> =
        knowledgeIndex.search(query, limit)
            .filter { it.score >= minScore }
            .map { it.text }
}
