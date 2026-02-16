package com.dailydiary

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages transcription storage and retrieval.
 */
class TranscriptionManager(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val db = AppDatabase.getDatabase(context)

    suspend fun saveTranscription(text: String) {
        val now = Date()
        val transcription = TranscriptionEntity(
            text = text.trim(),
            timestamp = now.time,
            date = dateFormat.format(now)
        )
        db.transcriptionDao().insert(transcription)
    }

    suspend fun getTranscriptionsForToday(): List<TranscriptionEntity> {
        val today = dateFormat.format(Date())
        return db.transcriptionDao().getTranscriptionsForDate(today)
    }

    suspend fun getTranscriptionsForDate(date: String): List<TranscriptionEntity> {
        return db.transcriptionDao().getTranscriptionsForDate(date)
    }

    suspend fun getFormattedTranscriptionsForDate(date: String): String {
        val transcriptions = db.transcriptionDao().getTranscriptionsForDate(date)
        if (transcriptions.isEmpty()) return ""

        return buildString {
            appendLine("=== Daily Voice Log for $date ===")
            appendLine()
            for (t in transcriptions) {
                val time = timeFormat.format(Date(t.timestamp))
                appendLine("[$time] ${t.text}")
            }
            appendLine()
            appendLine("=== Total entries: ${transcriptions.size} ===")
        }
    }

    suspend fun getTodayCount(): Int {
        val today = dateFormat.format(Date())
        return db.transcriptionDao().getCountForDate(today)
    }

    suspend fun getRecentTranscriptions(): List<TranscriptionEntity> {
        return db.transcriptionDao().getRecentTranscriptions()
    }

    suspend fun cleanupOldData(daysToKeep: Int = 30) {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, -daysToKeep)
        val cutoffDate = dateFormat.format(calendar.time)
        db.transcriptionDao().deleteOlderThan(cutoffDate)
    }
}
