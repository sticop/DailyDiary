package com.dailydiary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TranscriptionDao {
    @Insert
    suspend fun insert(transcription: TranscriptionEntity)

    @Query("SELECT * FROM transcriptions WHERE date = :date ORDER BY timestamp ASC")
    suspend fun getTranscriptionsForDate(date: String): List<TranscriptionEntity>

    @Query("DELETE FROM transcriptions WHERE date < :date")
    suspend fun deleteOlderThan(date: String)

    @Query("SELECT COUNT(*) FROM transcriptions WHERE date = :date")
    suspend fun getCountForDate(date: String): Int

    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentTranscriptions(): List<TranscriptionEntity>
}
