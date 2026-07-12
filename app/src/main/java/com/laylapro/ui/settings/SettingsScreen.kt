package com.laylapro.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.laylapro.api.AnthropicApiClient
import com.laylapro.api.ApiKeyValidationResult
import com.laylapro.util.SecureStorage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    secureStorage: SecureStorage,
    anthropicApiClient: AnthropicApiClient,
    onBack: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(secureStorage.getApiKey().orEmpty()) }
    var isValidating by remember { mutableStateOf(false) }
    // Патч 4: null = ещё не проверяли в этой сессии экрана (при обычном запуске приложения
    // проверка НЕ выполняется — только сразу после сохранения/изменения ключа, как и требовалось).
    var validationResult by remember { mutableStateOf<ApiKeyValidationResult?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth()) {
            Text(
                text = "Anthropic API-ключ",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Получить ключ можно на console.anthropic.com -> API Keys. " +
                    "Ключ хранится зашифрованным на устройстве (Android Keystore) и никуда не передаётся, " +
                    "кроме прямых запросов к api.anthropic.com.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; validationResult = null },
                label = { Text("sk-ant-...") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(
                    enabled = !isValidating,
                    onClick = {
                        secureStorage.saveApiKey(apiKey.trim())
                        isValidating = true
                        validationResult = null
                        // Патч 4: проверка ТОЛЬКО что сохранённого ключа — не при каждом запуске
                        // приложения, а именно сейчас, сразу после сохранения/изменения.
                        scope.launch {
                            validationResult = anthropicApiClient.validateApiKey()
                            isValidating = false
                        }
                    },
                ) {
                    Text("Сохранить и проверить")
                }
                if (isValidating) {
                    Spacer(modifier = Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                }
            }
            validationResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                val (message, color) = when (result) {
                    is ApiKeyValidationResult.Valid ->
                        "Ключ рабочий ✓" to MaterialTheme.colorScheme.primary
                    is ApiKeyValidationResult.InvalidKey ->
                        "Ключ неверный или отозван — проверьте, что скопировали его полностью" to MaterialTheme.colorScheme.error
                    is ApiKeyValidationResult.QuotaExceeded ->
                        "Ключ рабочий, но превышена квота/лимит запросов — попробуйте позже" to MaterialTheme.colorScheme.error
                    is ApiKeyValidationResult.NoInternet ->
                        "Нет подключения к интернету — проверить ключ не удалось" to MaterialTheme.colorScheme.error
                    is ApiKeyValidationResult.ServerError ->
                        "Сервер Anthropic временно недоступен (код ${result.code}) — попробуйте позже" to MaterialTheme.colorScheme.error
                    is ApiKeyValidationResult.Unknown ->
                        "Не удалось проверить ключ: ${result.message}" to MaterialTheme.colorScheme.error
                }
                Text(text = message, color = color)
            }
        }
    }
}
