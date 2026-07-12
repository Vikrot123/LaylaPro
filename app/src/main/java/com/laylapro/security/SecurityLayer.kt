package com.laylapro.security

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat

/**
 * Модуль 17 (Слой 7) — Interceptor опасных команд ИИ (см. ТЗ, Часть II, п.17).
 * Если в TaskStep обнаружено действие с тегом #danger (например, delete_file),
 * выполнение приостанавливается и вызывается BiometricPrompt.
 */
interface SecurityLayer {
    fun isDangerousAction(action: String): Boolean
    fun verifyUserBeforeAction(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    )
}

class SecurityLayerImpl : SecurityLayer {

    // TODO: вынести в конфигурируемый список / расширять по мере роста Plugin System
    private val dangerousActions = setOf(
        "delete_file", "send_money", "factory_reset", "uninstall_app", "grant_permission",
    )

    override fun isDangerousAction(action: String): Boolean = action in dangerousActions

    override fun verifyUserBeforeAction(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }

            override fun onAuthenticationFailed() {
                onFailure()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Подтверждение действия LaylaPro")
            .setSubtitle("Это действие помечено как потенциально опасное")
            .setNegativeButtonText("Отмена")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
