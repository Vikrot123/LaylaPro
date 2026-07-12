package com.laylapro.knowledge

/**
 * Модуль 5 (Слой 2) — локальное векторное и реляционное хранилище
 * (см. ТЗ, Часть II, п.5: SQLite + расширение для векторов / ObjectBox / Realm).
 *
 * STUB: в MVP хранит факты как простой список пар "ключ-значение" в памяти.
 *
 * TODO: заменить на реальную схему БД embeddings_db из ТЗ:
 *   CREATE TABLE embeddings_db (
 *     id TEXT PRIMARY KEY, vector BLOB, raw_text TEXT,
 *     timestamp INTEGER, metadata TEXT
 *   );
 * и подключить локальную модель эмбеддингов (например, bge-micro-v2 через ONNX Runtime).
 */
interface KnowledgeBase {
    suspend fun storeFact(key: String, value: String)
    suspend fun getFact(key: String): String?
    suspend fun searchBySubstring(query: String): List<Pair<String, String>>
}

class InMemoryKnowledgeBase : KnowledgeBase {
    // MJ5 (независимый аудит реализации): обычный LinkedHashMap не потокобезопасен, а
    // MemoryConsolidationService (Этап 2) пишет сюда из metaScope (Dispatchers.Default,
    // многопоточный) при закрытии разных сессий параллельно — synchronizedMap сохраняет
    // порядок вставки LinkedHashMap, но защищает от конкурентной модификации.
    private val facts = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

    override suspend fun storeFact(key: String, value: String) {
        facts[key] = value
    }

    override suspend fun getFact(key: String): String? = facts[key]

    override suspend fun searchBySubstring(query: String): List<Pair<String, String>> =
        synchronized(facts) {
            facts.filter { (k, v) -> k.contains(query, ignoreCase = true) || v.contains(query, ignoreCase = true) }
                .map { it.key to it.value }
        }
}
