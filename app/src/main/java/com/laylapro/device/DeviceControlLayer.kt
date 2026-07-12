package com.laylapro.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Модуль 12 (Слой 5) — мост между "мыслями" ИИ и системными переключателями
 * Android (Wi-Fi, Bluetooth, громкость, яркость, будильники), см. ТЗ, Часть II, п.12.
 *
 * AI Core вызывает этот модуль через строго типизированные функции (Function Calling) —
 * в перспективе через Planning Engine -> TaskStep(moduleName = "DeviceControl", ...).
 */
interface DeviceControlLayer {
    fun setWifiState(enabled: Boolean)
    fun openBluetoothSettings()
    fun openSoundSettings()
}

class DeviceControlLayerImpl(private val context: Context) : DeviceControlLayer {

    override fun setWifiState(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Начиная с Android 10 приложения без системных прав не могут переключать
            // Wi-Fi напрямую — открываем панель, как рекомендовано в ТЗ.
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(panelIntent)
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enabled
        }
    }

    override fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun openSoundSettings() {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
