package com.laylapro.embedding

import kotlin.math.sqrt

/**
 * Embedding Engine — Этап 2, пункт 1.
 *
 * Контракт превращает произвольный текст в фиксированной размерности вектор для
 * последующего косинусного/скалярного поиска (см. `vectormemory.VectorMemory`).
 * Намеренно НЕ suspend-независим от конкретной технологии — реализация может быть
 * как локальной (эта), так и нейросетевой (ONNX/llama.cpp embedding-модель, Этап 6),
 * как и облачным API эмбеддингов — вызывающий код (`VectorMemory`, `KnowledgeIndex`,
 * `RagEngine`) зависит только от этого интерфейса.
 */
interface EmbeddingEngine {
    /** Размерность вектора — фиксирована для конкретной реализации, важна для VectorMemory. */
    val dimensions: Int

    suspend fun embed(text: String): FloatArray

    /** По умолчанию — последовательно; настоящая нейросетевая реализация может переопределить батчем. */
    suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
}

/**
 * MVP-реализация без нейросети: детерминированный "hashing trick" (как в
 * sklearn.HashingVectorizer / Vowpal Wabbit) — токенизация на слова и биграммы слов,
 * хэш каждого токена определяет индекс и знак вклада в вектор фиксированной размерности,
 * результат L2-нормализуется. Это НАСТОЯЩАЯ, детерминированная техника (не заглушка,
 * не случайные числа) — даёт осмысленную близость для текстов с лексическим пересечением,
 * но НЕ улавливает глубокую семантику (синонимы, перефразирование) так, как нейросетевая
 * embedding-модель.
 *
 * TODO(Этап 6): заменить на `OnnxEmbeddingEngine`/`LlamaCppEmbeddingEngine` поверх
 * реальной модели (например, bge-small-en, см. анализ архитектуры Layla — токенизаторы
 * под BGE уже были найдены в её native-слое) — весь остальной код (VectorMemory,
 * KnowledgeIndex, RagEngine) не потребует изменений, т.к. зависит только от [EmbeddingEngine].
 */
class HashingEmbeddingEngine(override val dimensions: Int = 256) : EmbeddingEngine {

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = tokenize(text)

        for (token in tokens) {
            addToken(vector, token)
        }
        // Биграммы соседних слов — ловят немного больше контекста, чем "мешок слов".
        for (i in 0 until tokens.size - 1) {
            addToken(vector, "${tokens[i]}_${tokens[i + 1]}")
        }

        return normalize(vector)
    }

    private fun addToken(vector: FloatArray, token: String) {
        val hash = stableHash(token)
        val index = Math.floorMod(hash, dimensions)
        val sign = if ((hash ushr 31) and 1L == 0L) 1f else -1f
        vector[index] += sign
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }

    /** 64-битный хэш вместо String.hashCode() — меньше коллизий, детерминирован между запусками/устройствами. */
    private fun stableHash(token: String): Long {
        var h = 1125899906842597L // произвольная large prime — только для равномерного распределения
        for (c in token) {
            h = 31 * h + c.code
        }
        return h and Long.MAX_VALUE
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (v in vector) sumSquares += v.toDouble() * v.toDouble()
        val norm = sqrt(sumSquares).toFloat()
        if (norm < 1e-6f) return vector
        for (i in vector.indices) vector[i] = vector[i] / norm
        return vector
    }
}

/** Косинусное сходство — переиспользуется VectorMemory и ConsensusEngine (в будущем — вместо word-overlap эвристики). */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Векторы разной размерности: ${a.size} vs ${b.size}" }
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA < 1e-9f || normB < 1e-9f) return 0f
    return dot / (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
}
