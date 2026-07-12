package com.laylapro.sync

/**
 * Модуль 16 (Слой 6) — резервное копирование и синхронизация памяти/настроек
 * между устройствами через зашифрованный облачный бэкенд (см. ТЗ, Часть II, п.16).
 *
 * STUB: требует backend (например, PostgreSQL/Supabase), которого нет в рамках
 * клиентского Android-проекта. Здесь только контракт и заготовка ключа шифрования.
 *
 * TODO: AES-GCM-256, ключ через PBKDF2 от мастер-пароля пользователя, хранение
 * ключа в Android Keystore (переиспользовать SecureStorage), отправка блоба по HTTPS.
 */
interface SynchronizationLayer {
    suspend fun backupNow(): SyncResult
    suspend fun restoreLatest(): SyncResult
}

data class SyncResult(val success: Boolean, val message: String)

class NotConfiguredSynchronizationLayer : SynchronizationLayer {
    override suspend fun backupNow(): SyncResult =
        SyncResult(success = false, message = "Backend синхронизации не настроен (см. TODO в SynchronizationLayer.kt)")

    override suspend fun restoreLatest(): SyncResult =
        SyncResult(success = false, message = "Backend синхронизации не настроен (см. TODO в SynchronizationLayer.kt)")
}
