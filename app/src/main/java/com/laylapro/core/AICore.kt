package com.laylapro.core

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Модуль 1 (Слой 1: Когнитивное ядро) — см. Часть II ТЗ.
 * Главный оркестратор: точка входа для всех интентов, координирует вызовы
 * подсистем через Event Bus, агрегирует финальный ответ.
 */
interface AICore {
    val eventBus: SharedFlow<CoreEvent>
    suspend fun processInput(input: UserInput): CoreResponse
}

data class UserInput(
    val id: UUID = UUID.randomUUID(),
    val sessionId: String,
    val payload: InputPayload,
    val timestamp: Long = System.currentTimeMillis(),
)

sealed class InputPayload {
    data class Text(val content: String) : InputPayload()
    data class Voice(val audioUri: Uri) : InputPayload()
    data class MultiModal(val text: String, val screenshot: Bitmap) : InputPayload()
}

data class CoreResponse(
    val sessionId: String,
    val text: String,
    val isError: Boolean = false,
)
