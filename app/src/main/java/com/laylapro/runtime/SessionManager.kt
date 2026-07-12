package com.laylapro.runtime

import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session Manager (Том 98): "Управляет пользовательскими сессиями:
 * создание, закрытие, переключение, восстановление, сохранение.
 * Каждая пользовательская команда принадлежит конкретной сессии."
 */
data class SessionInfo(
    val sessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
    var closed: Boolean = false,
)

class SessionManager {

    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    @Volatile private var activeSessionId: String? = null

    fun create(): SessionInfo {
        val id = UUID.randomUUID().toString()
        val session = SessionInfo(sessionId = id)
        sessions[id] = session
        activeSessionId = id
        EventBus.tryPublish(CoreEvent.SessionCreated(id))
        return session
    }

    fun close(sessionId: String) {
        sessions[sessionId]?.closed = true
        if (activeSessionId == sessionId) activeSessionId = null
        EventBus.tryPublish(CoreEvent.SessionClosed(sessionId))
    }

    /** Переключение активной сессии — восстанавливает существующую или создаёт новую. */
    fun switchTo(sessionId: String): SessionInfo {
        val existing = sessions[sessionId]
        val session = if (existing != null && !existing.closed) {
            existing.lastActiveAt = System.currentTimeMillis()
            existing
        } else {
            SessionInfo(sessionId = sessionId).also { sessions[sessionId] = it }
        }
        activeSessionId = sessionId
        return session
    }

    fun touch(sessionId: String) {
        sessions[sessionId]?.lastActiveAt = System.currentTimeMillis()
    }

    fun active(): SessionInfo? = activeSessionId?.let { sessions[it] }

    fun get(sessionId: String): SessionInfo? = sessions[sessionId]

    fun all(): List<SessionInfo> = sessions.values.toList()
}
