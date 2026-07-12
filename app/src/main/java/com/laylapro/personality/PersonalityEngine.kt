package com.laylapro.personality

/**
 * Модуль 7 (Слой 3) — фильтрация и модификация системного промпта
 * для удержания характера ИИ (см. ТЗ, Часть II, п.7).
 */
interface PersonalityEngine {
    fun injectPersonality(basePrompt: String): String
    fun getSystemInstructions(): String
}

class PersonalityEngineImpl : PersonalityEngine {

    // TODO: вынести в настраиваемый пользователем профиль (тон, обращение, формальность)
    private val corePersonality = """
        Тебя зовут LaylaPro. Ты — персональный AI-ассистент пользователя на Android-устройстве.
        Общайся дружелюбно, по делу, без лишней "воды". Помни, что у тебя есть долговременная
        память о пользователе и ты способна выполнять действия на устройстве.
    """.trimIndent()

    override fun getSystemInstructions(): String = corePersonality

    override fun injectPersonality(basePrompt: String): String =
        "$corePersonality\n\n$basePrompt"
}
