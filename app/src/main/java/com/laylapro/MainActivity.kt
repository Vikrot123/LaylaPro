package com.laylapro

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laylapro.integration.LaylaForegroundService
import com.laylapro.runtime.RuntimeManager
import com.laylapro.ui.chat.ChatScreen
import com.laylapro.ui.chat.ChatViewModel
import com.laylapro.ui.settings.SettingsScreen
import com.laylapro.ui.theme.LaylaProTheme
import com.laylapro.util.SecureStorage

/**
 * Точка входа UI. Запускает Final Integration Layer (Foreground Service, модуль 20)
 * и показывает чат поверх AI Core, собранного в [LaylaApplication].
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Заполняется [com.laylapro.text.ChatActivity] при выборе "Обсудить с LaylaPro" в системном меню. */
        const val EXTRA_PREFILLED_MESSAGE = "com.laylapro.EXTRA_PREFILLED_MESSAGE"
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не критичен: без разрешения сервис всё равно работает, просто без уведомления */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureNotificationPermission()
        startForegroundServiceCompat()

        val app = application as LaylaApplication
        val prefilledMessage = intent?.getStringExtra(EXTRA_PREFILLED_MESSAGE)

        setContent {
            LaylaProTheme {
                LaylaProApp(
                    runtimeManager = app.runtimeManager,
                    secureStorage = app.secureStorage,
                    anthropicApiClient = app.anthropicApiClient,
                    prefilledMessage = prefilledMessage,
                )
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startForegroundServiceCompat() {
        val intent = Intent(this, LaylaForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

private enum class Screen { CHAT, SETTINGS }

@Composable
private fun LaylaProApp(
    runtimeManager: RuntimeManager,
    secureStorage: SecureStorage,
    anthropicApiClient: com.laylapro.api.AnthropicApiClient,
    prefilledMessage: String? = null,
) {
    var screen by remember { mutableStateOf(Screen.CHAT) }

    val chatViewModel: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(runtimeManager) as T
        }
    )

    // Пришли из системного меню "Обработать текст" -> "Обсудить с LaylaPro" (см. text/ChatActivity).
    androidx.compose.runtime.LaunchedEffect(prefilledMessage) {
        if (!prefilledMessage.isNullOrBlank()) {
            chatViewModel.sendMessage(prefilledMessage)
        }
    }

    when (screen) {
        Screen.CHAT -> ChatScreen(
            viewModel = chatViewModel,
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            secureStorage = secureStorage,
            anthropicApiClient = anthropicApiClient,
            onBack = { screen = Screen.CHAT },
        )
    }
}
