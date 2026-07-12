package com.laylapro.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.laylapro.MainActivity
import com.laylapro.R

/**
 * Модуль 20 (Слой 7) — Final Integration Layer: Foreground Service для работы 24/7
 * (см. ТЗ, Часть II, п.20). Гарантирует, что ОС Android не убьёт процессы
 * AI Core и Memory System при нехватке памяти.
 */
class LaylaForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "laylapro_foreground_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: система попытается пересоздать сервис после уничтожения при OOM
        return START_STICKY
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = androidx.core.app.TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(openAppIntent)
            .getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_service_notification_title))
            .setContentText(getString(R.string.foreground_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: заменить на брендированную иконку
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
