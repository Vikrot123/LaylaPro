package com.laylapro.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laylapro.runtime.RuntimeManager
import kotlinx.coroutines.launch

data class ChatUiMessage(val text: String, val isUser: Boolean)

/**
 * Тонкий слой представления. Согласно Тому 98 ("Никто не должен вызывать другой
 * модуль напрямую"), UI обращается не к AICore, а к [RuntimeManager] — тот сам
 * проводит сообщение через очередь задач, конечный автомат состояний и Dispatcher.
 */
class ChatViewModel(private val runtimeManager: RuntimeManager) : ViewModel() {

    val messages = mutableStateListOf<ChatUiMessage>()
    val isLoading = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)

    private val sessionId = java.util.UUID.randomUUID().toString()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        messages.add(ChatUiMessage(text, isUser = true))
        isLoading.value = true
        errorMessage.value = null

        viewModelScope.launch {
            val response = runtimeManager.submitUserMessage(sessionId, text)
            isLoading.value = false
            if (response.isError) {
                errorMessage.value = response.text
            } else {
                messages.add(ChatUiMessage(response.text, isUser = false))
            }
        }
    }
}
