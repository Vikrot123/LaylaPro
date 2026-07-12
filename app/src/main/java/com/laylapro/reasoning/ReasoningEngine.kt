package com.laylapro.reasoning

import com.laylapro.memory.MemoryEntry

/**
 * Модуль 2 (Слой 1) — логический вывод на базе Chain-of-Thought / ReAct.
 * Контракт совпадает с описанием в ТЗ (Часть II, п.2).
 */
interface ReasoningEngine {
    suspend fun evaluateContext(
        prompt: String,
        memoryContext: List<MemoryEntry>,
    ): ReasoningResult
}

data class ReasoningResult(
    val internalMonologue: String, // Скрытые размышления модели
    val predictedIntent: String,
    val confidence: Float,
    val requiredTools: List<String> = emptyList(),
)
