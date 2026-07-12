package com.laylapro.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Часть Security Layer (модуль 17 в ТЗ): API-ключи и другие секреты
 * шифруются и хранятся через Android Keystore, а не в открытом виде.
 */
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "laylapro_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_ANTHROPIC_API_KEY, key).apply()
    }

    fun getApiKey(): String? = prefs.getString(KEY_ANTHROPIC_API_KEY, null)

    fun clearApiKey() {
        prefs.edit().remove(KEY_ANTHROPIC_API_KEY).apply()
    }

    companion object {
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
    }
}
