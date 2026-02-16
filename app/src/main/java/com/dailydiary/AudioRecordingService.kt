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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_SECONDS = 30 // Record 30-second chunks
        const val ACTION_START = "com.dailydiary.action.START_RECORDING"
        const val ACTION_STOP = "com.dailydiary.action.STOP_RECORDING"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var speechToTextProcessor: SpeechToTextProcessor
    private lateinit var transcriptionManager: TranscriptionManager

    override fun onCreate() {
        super.onCreate()
        speechToTextProcessor = SpeechToTextProcessor(this)
        transcriptionManager = TranscriptionManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (isRecording) return

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRecording = true
        serviceScope.launch {
            recordAudioLoop()
        }

        Log.d(TAG, "Recording started")
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Recording stopped")
    }

    private suspend fun recordAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                stopRecording()
                return
            }

            audioRecord?.startRecording()

            while (isRecording) {
                val audioFile = recordChunk(bufferSize)
                if (audioFile != null && audioFile.length() > 44) { // > WAV header size
                    processAudioChunk(audioFile)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
            stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error during recording", e)
            // Continue recording after short delay
            delay(1000)
            if (isRecording) recordAudioLoop()
        }
    }

    private fun recordChunk(bufferSize: Int): File? {
        val audioDir = File(filesDir, "audio_chunks")
        audioDir.mkdirs()
        val audioFile = File(audioDir, "chunk_${System.currentTimeMillis()}.wav")

        try {
            val totalSamples = SAMPLE_RATE * CHUNK_DURATION_SECONDS
            val buffer = ShortArray(bufferSize)
            val audioData = mutableListOf<Short>()
            var samplesRead = 0

            while (isRecording && samplesRead < totalSamples) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    // Simple voice activity detection: check if audio level is above threshold
                    val maxAmplitude = buffer.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    if (maxAmplitude > 500) { // Threshold to filter silence
                        for (i in 0 until read) {
                            audioData.add(buffer[i])
                        }
                    }
                    samplesRead += read
                }
            }

            if (audioData.isEmpty()) {
                return null // No audio above threshold
            }

            // Write WAV file
            writeWavFile(audioFile, audioData.toShortArray())
            return audioFile
        } catch (e: Exception) {
            Log.e(TAG, "Error recording chunk", e)
            audioFile.delete()
            return null
        }
    }

    private fun writeWavFile(file: File, audioData: ShortArray) {
        val byteData = ByteArray(audioData.size * 2)
        val buffer = ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in audioData) {
            buffer.putShort(sample)
        }

        FileOutputStream(file).use { fos ->
            val dataSize = byteData.size
            val header = ByteArray(44)
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            headerBuffer.put("RIFF".toByteArray())
            headerBuffer.putInt(36 + dataSize)
            headerBuffer.put("WAVE".toByteArray())

            // fmt chunk
            headerBuffer.put("fmt ".toByteArray())
            headerBuffer.putInt(16) // Chunk size
            headerBuffer.putShort(1) // PCM format
            headerBuffer.putShort(1) // Mono
            headerBuffer.putInt(SAMPLE_RATE)
            headerBuffer.putInt(SAMPLE_RATE * 2) // Byte rate
            headerBuffer.putShort(2) // Block align
            headerBuffer.putShort(16) // Bits per sample

            // data chunk
            headerBuffer.put("data".toByteArray())
            headerBuffer.putInt(dataSize)

            fos.write(header)
            fos.write(byteData)
        }
    }

    private fun processAudioChunk(audioFile: File) {
        serviceScope.launch {
            try {
                val transcription = speechToTextProcessor.transcribe(audioFile)
                if (transcription.isNotBlank()) {
                    transcriptionManager.saveTranscription(transcription)
                    Log.d(TAG, "Transcription saved: ${transcription.take(50)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio chunk", e)
            } finally {
                // Clean up audio file to save space
                audioFile.delete()
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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
            .setContentTitle("Daily Diary Recording")
            .setContentText("Listening and capturing your day...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
