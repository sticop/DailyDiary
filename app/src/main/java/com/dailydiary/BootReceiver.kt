package com.dailydiary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the recording service after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("daily_diary_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)

            if (autoStart) {
                val serviceIntent = Intent(context, AudioRecordingService::class.java).apply {
                    action = AudioRecordingService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
