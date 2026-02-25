package com.dailydiary

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Foreground service that records audio in chunks and sends them to
 * Deepgram Nova-2 API for multilingual transcription with automatic
 * language detection. Supports English, French, Moroccan Arabic (Darija),
 * and Spanish seamlessly — no manual language selection needed.
 */
class AudioRecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.dailydiary.action.START_RECORDING"
        const val ACTION_STOP = "com.dailydiary.action.STOP_RECORDING"

        // Broadcast actions
        const val BROADCAST_PARTIAL = "com.dailydiary.PARTIAL"
        const val BROADCAST_FINAL = "com.dailydiary.FINAL"
        const val BROADCAST_STATUS = "com.dailydiary.STATUS"
        const val EXTRA_TEXT = "extra_text"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_MS = 10_000L // 10 seconds per chunk
    }

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var transcriptionManager: TranscriptionManager
    private lateinit var speechProcessor: SpeechToTextProcessor
    private var recordingJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        transcriptionManager = TranscriptionManager(this)
        speechProcessor = SpeechToTextProcessor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Start / Stop ────────────────────────────────────────────────────

    private fun startRecording() {
        if (isRecording) return

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRecording = true
        broadcast(BROADCAST_STATUS, "🔴 Listening — Deepgram multilingual")
        Log.d(TAG, "Started recording with Deepgram Nova-2 multilingual detection")

        recordingJob = serviceScope.launch {
            recordAndTranscribeLoop()
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcast(BROADCAST_STATUS, "⏸️ Stopped")
        Log.d(TAG, "Stopped recording")
    }

    // ── Audio capture + Whisper transcription loop ──────────────────────

    private suspend fun recordAndTranscribeLoop() {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            SAMPLE_RATE * 2 // at least 1 second buffer
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                withContext(Dispatchers.Main) {
                    broadcast(BROADCAST_STATUS, "❌ Microphone initialization failed")
                }
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord started")

            val buffer = ShortArray(bufferSize / 2)

            while (isRecording) {
                yield() // allow cancellation
                // Record one chunk
                val chunkFile = recordChunk(buffer, bufferSize)

                if (chunkFile != null && isRecording) {
                    // Show processing indicator
                    withContext(Dispatchers.Main) {
                        broadcast(BROADCAST_PARTIAL, "🎙️ Processing speech…")
                    }

                    // Transcribe with Deepgram Nova-2 (auto-detects language)
                    try {
                        val text = speechProcessor.transcribe(chunkFile)
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                broadcast(BROADCAST_PARTIAL, "")
                                broadcast(BROADCAST_FINAL, text)
                            }
                            transcriptionManager.saveTranscription(text)
                            Log.d(TAG, "Transcribed: ${text.take(80)}")
                        } else {
                            withContext(Dispatchers.Main) {
                                broadcast(BROADCAST_PARTIAL, "")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Transcription error", e)
                    }

                    // Clean up temp file
                    try {
                        chunkFile.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Recording loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            withContext(Dispatchers.Main) {
                broadcast(BROADCAST_STATUS, "❌ Recording error: ${e.message}")
            }
        } finally {
            releaseAudioRecord()
        }
    }

    /**
     * Records audio for [CHUNK_DURATION_MS] and writes it to a WAV file.
     * Returns null if no meaningful audio was captured (silence).
     */
    private fun recordChunk(buffer: ShortArray, bufferSize: Int): File? {
        val chunkFile = File(cacheDir, "chunk_${System.currentTimeMillis()}.wav")
        val totalSamples = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000).toInt()
        var samplesRecorded = 0
        var maxAmplitude: Short = 0

        try {
            FileOutputStream(chunkFile).use { fos ->
                // Write placeholder WAV header (44 bytes)
                fos.write(ByteArray(44))

                while (samplesRecorded < totalSamples && isRecording) {
                    val samplesToRead = minOf(buffer.size, totalSamples - samplesRecorded)
                    val read = audioRecord?.read(buffer, 0, samplesToRead) ?: -1
                    if (read > 0) {
                        // Convert shorts to bytes (little-endian)
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] =
                                (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            val abs =
                                if (buffer[i] < 0) (-buffer[i]).toShort() else buffer[i]
                            if (abs > maxAmplitude) maxAmplitude = abs
                        }
                        fos.write(byteBuffer)
                        samplesRecorded += read
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read")
                        break
                    }
                }
            }

            // Check if there was meaningful audio (not just silence)
            if (maxAmplitude < 200) {
                chunkFile.delete()
                return null
            }

            // Write proper WAV header
            writeWavHeader(chunkFile, samplesRecorded)
            return chunkFile
        } catch (e: Exception) {
            Log.e(TAG, "Error recording chunk", e)
            try {
                chunkFile.delete()
            } catch (_: Exception) {
            }
            return null
        }
    }

    private fun writeWavHeader(file: File, totalSamples: Int) {
        val dataSize = totalSamples * 2 // 16-bit mono
        val fileSize = dataSize + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(fileSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16) // chunk size
            raf.writeShortLE(1) // PCM format
            raf.writeShortLE(1) // mono
            raf.writeIntLE(SAMPLE_RATE)
            raf.writeIntLE(SAMPLE_RATE * 2) // byte rate
            raf.writeShortLE(2) // block align
            raf.writeShortLE(16) // bits per sample
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
        }
    }

    // Little-endian write helpers
    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun broadcast(action: String, text: String) {
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, text)
        })
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DailyDiaryApp.CHANNEL_ID_RECORDING)
            .setContentTitle("Daily Diary — Live Transcription")
            .setContentText("Listening — Deepgram multilingual (EN/FR/AR/ES)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        isRecording = false
        recordingJob?.cancel()
        releaseAudioRecord()
        serviceScope.cancel()
        super.onDestroy()
    }
}
