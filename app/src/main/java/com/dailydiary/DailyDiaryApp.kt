package com.dailydiary

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class DailyDiaryApp : Application() {

    companion object {
        const val CHANNEL_ID_RECORDING = "recording_channel"
        const val CHANNEL_ID_SUMMARY = "summary_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleDailySummary()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recordingChannel = NotificationChannel(
                CHANNEL_ID_RECORDING,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Daily Diary is recording audio"
            }

            val summaryChannel = NotificationChannel(
                CHANNEL_ID_SUMMARY,
                "Daily Summary",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for your daily diary summary"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(recordingChannel)
            notificationManager.createNotificationChannel(summaryChannel)
        }
    }

    private fun scheduleDailySummary() {
        val prefs = getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)
        val summaryHour = prefs.getInt("summary_hour", 22) // Default 10 PM
        val summaryMinute = prefs.getInt("summary_minute", 0)

        val currentTime = java.util.Calendar.getInstance()
        val targetTime = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, summaryHour)
            set(java.util.Calendar.MINUTE, summaryMinute)
            set(java.util.Calendar.SECOND, 0)
        }

        if (targetTime.before(currentTime)) {
            targetTime.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }

        val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailySummaryRequest = PeriodicWorkRequestBuilder<DailySummaryWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag("daily_summary")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_summary_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            dailySummaryRequest
        )
    }
}
