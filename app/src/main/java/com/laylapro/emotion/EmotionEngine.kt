package com.laylapro.emotion

/**
 * Модуль 8 (Слой 3) — вектор эмоционального состояния
 * (модель PAD: Валентность/Активация/Доминантность, см. ТЗ, Часть II, п.8).
 * Выходной вектор в полной версии передаётся в Voice Engine (TTS) для
 * динамической просодики речи.
 */
interface EmotionEngine {
    fun analyze(text: String): EmotionState
}

data class EmotionState(
    val valence: Float,   // [-1.0 .. 1.0] Негатив/Позитив
    val arousal: Float,   // [0.0 .. 1.0]  Спокойствие/Возбуждение
    val dominance: Float, // [0.0 .. 1.0]  Субмиссивность/Доминирование
)

/**
 * STUB: примитивная эвристика по ключевым словам и пунктуации.
 * TODO: заменить на полноценный сентимент-анализ (локальная модель или
 * запрос к облачному LLM), а также учитывать интонацию голоса из Voice Engine.
 */
class EmotionEngineImpl : EmotionEngine {

    private val negativeWords = listOf("плохо", "ужасно", "не работает", "бесит", "злюсь", "устал")
    private val positiveWords = listOf("отлично", "супер", "класс", "спасибо", "рад", "круто")

    override fun analyze(text: String): EmotionState {
        val lower = text.lowercase()
        val neg = negativeWords.count { lower.contains(it) }
        val pos = positiveWords.count { lower.contains(it) }
        val exclamations = text.count { it == '!' }

        val valence = ((pos - neg).coerceIn(-3, 3) / 3f)
        val arousal = (exclamations.coerceIn(0, 5) / 5f)
        val dominance = 0.5f // нейтрально по умолчанию

        return EmotionState(valence = valence, arousal = arousal, dominance = dominance)
    }
}
