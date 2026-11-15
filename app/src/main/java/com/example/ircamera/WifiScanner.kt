package com.example.ircamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

class WifiScanner(
    private val context: Context,
    private val onResults: (List<String>) -> Unit
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleResults()
        }
    }

    fun startScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    onResults(listOf("Нет разрешения на геолокацию для сканирования Wi-Fi."))
                    return
                }
                context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            }

            val success = wifiManager.startScan()
            if (!success) {
                onResults(listOf("Не удалось запустить сканирование. Включите GPS и разрешение на геолокацию."))
            }
        } catch (e: Exception) {
            onResults(listOf("Ошибка при запуске: ${e.message}"))
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun handleResults() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                onResults(listOf("Разрешение на геолокацию отозвано. Результаты недоступны."))
                return
            }
            val results = wifiManager.scanResults
            val lines = results.map { r ->
                val ssid = if (r.SSID.isNullOrBlank()) "(скрытая)" else r.SSID
                val channel = freqToChannel(r.frequency)
                "$ssid\n  BSSID: ${r.BSSID}\n  Сигнал: ${r.level} dBm, канал: $channel"
            }
            onResults(lines.ifEmpty { listOf("Сети не найдены") })
        } catch (e: SecurityException) {
            onResults(listOf("Ошибка безопасности: ${e.message}"))
        } catch (e: Exception) {
            onResults(listOf("Ошибка сканирования: ${e.message}"))
        }
    }

    private fun freqToChannel(freq: Int): Int {
        return when {
            freq in 2412..2484 -> (freq - 2407) / 5
            freq in 5170..5825 -> (freq - 5000) / 5
            else -> 0
        }
    }
}