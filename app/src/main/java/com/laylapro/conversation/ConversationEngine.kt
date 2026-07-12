package com.laylapro.conversation

import com.laylapro.memory.MemorySystem
import java.util.UUID

/**
 * Модуль 9 (Слой 3) — менеджмент диалоговых сессий, очистка старого контекста,
 * обработка прерываний пользователя (см. ТЗ, Часть II, п.9).
 */
interface ConversationEngine {
    fun startSession(): String
    suspend fun updateSessionContext(sessionId: String, message: Message)
    suspend fun clearContextWindow(sessionId: String)
    suspend fun getHistory(sessionId: String): List<Message>
}

data class Message(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Role { USER, ASSISTANT }

class ConversationEngineImpl(
    private val memorySystem: MemorySystem,
) : ConversationEngine {

    // Простой конечный автомат сессий (State Machine) — id -> история в памяти процесса
    private val sessions = mutableMapOf<String, MutableList<Message>>()

    override fun startSession(): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = mutableListOf()
        return id
    }

    override suspend fun updateSessionContext(sessionId: String, message: Message) {
        sessions.getOrPut(sessionId) { mutableListOf() }.add(message)
    }

    override suspend fun clearContextWindow(sessionId: String) {
        // Очистка при переполнении токенов (см. ТЗ) — здесь просто урезаем историю,
        // сохранив последние сообщения; долговременные факты остаются в MemorySystem.
        sessions[sessionId]?.let { history ->
            val trimmed = history.takeLast(10)
            history.clear()
            history.addAll(trimmed)
        }
    }

    override suspend fun getHistory(sessionId: String): List<Message> =
        sessions[sessionId]?.toList() ?: emptyList()
}
