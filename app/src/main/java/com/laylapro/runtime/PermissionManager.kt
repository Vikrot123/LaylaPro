package com.laylapro.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.laylapro.core.CoreEvent
import com.laylapro.core.EventBus

/**
 * Permission Manager (Том 98): "Каждый модуль обязан получать разрешения
 * исключительно через Permission Manager."
 *
 * Часть разрешений (Accessibility, Overlay, MediaProjection) в Android не выдаются
 * через стандартный runtime-permission диалог, а требуют перехода в системные
 * настройки — для них ниже есть отдельные isXxxGranted()/openXxxSettings().
 * Обычные runtime-разрешения (Микрофон, Камера, Хранилище, Локация, Bluetooth,
 * Уведомления) запрашиваются через ActivityResultContracts на стороне Activity;
 * этот класс только централизованно проверяет их состояние.
 */
enum class LaylaPermission {
    ACCESSIBILITY, OVERLAY, NOTIFICATIONS, MICROPHONE, CAMERA, STORAGE, LOCATION, BLUETOOTH, MEDIA_PROJECTION,
}

class PermissionManager(private val context: Context) {

    private val appContext = context.applicationContext

    fun isGranted(permission: LaylaPermission): Boolean = when (permission) {
        LaylaPermission.ACCESSIBILITY -> isAccessibilityServiceEnabled()
        LaylaPermission.OVERLAY -> isOverlayGranted()
        LaylaPermission.NOTIFICATIONS -> isRuntimePermissionGranted(runtimeManifestPermission(permission))
        LaylaPermission.MICROPHONE -> isRuntimePermissionGranted(Manifest.permission.RECORD_AUDIO)
        LaylaPermission.CAMERA -> isRuntimePermissionGranted(Manifest.permission.CAMERA)
        LaylaPermission.STORAGE -> isRuntimePermissionGranted(runtimeManifestPermission(permission))
        LaylaPermission.LOCATION -> isRuntimePermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        LaylaPermission.BLUETOOTH -> isRuntimePermissionGranted(runtimeManifestPermission(permission))
        LaylaPermission.MEDIA_PROJECTION -> true // запрашивается ad-hoc через MediaProjectionManager.createScreenCaptureIntent()
    }

    /** Модули вызывают это ПЕРЕД использованием возможности; при отказе публикуется PermissionDenied. */
    fun requireGranted(permission: LaylaPermission, requestedBy: String): Boolean {
        val granted = isGranted(permission)
        if (!granted) {
            EventBus.tryPublish(CoreEvent.PermissionDenied(permission.name, requestedBy))
        }
        return granted
    }

    private fun isRuntimePermissionGranted(manifestPermission: String?): Boolean {
        if (manifestPermission == null) return true
        return ContextCompat.checkSelfPermission(appContext, manifestPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun runtimeManifestPermission(permission: LaylaPermission): String? = when (permission) {
        LaylaPermission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        LaylaPermission.STORAGE ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Manifest.permission.WRITE_EXTERNAL_STORAGE else null
        LaylaPermission.BLUETOOTH ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
        else -> null
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        com.laylapro.accessibility.LaylaAccessibilityService.instance != null

    private fun isOverlayGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    /** Формирует Intent для перевода пользователя в нужный экран системных настроек. */
    fun settingsIntentFor(permission: LaylaPermission): android.content.Intent = when (permission) {
        LaylaPermission.ACCESSIBILITY -> android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        LaylaPermission.OVERLAY -> android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${appContext.packageName}"),
        )
        else -> android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${appContext.packageName}")
        }
    }
}
