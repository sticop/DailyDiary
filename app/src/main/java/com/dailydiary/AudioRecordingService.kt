package com.dailydiary

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Foreground service that uses Android's SpeechRecognizer for real-time
 * speech-to-text transcription with multilingual support.
 *
 * Broadcasts partial results (as the user speaks) and final results
 * (when a phrase is complete) so the UI can display live text.
 */
class AudioRecordingService : Service() {

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.dailydiary.action.START_RECORDING"
        const val ACTION_STOP = "com.dailydiary.action.STOP_RECORDING"
        const val ACTION_CHANGE_LANGUAGE = "com.dailydiary.action.CHANGE_LANGUAGE"
        const val EXTRA_LANGUAGE = "extra_language"

        // Broadcast actions (app-local, explicit package)
        const val BROADCAST_PARTIAL = "com.dailydiary.PARTIAL"
        const val BROADCAST_FINAL = "com.dailydiary.FINAL"
        const val BROADCAST_STATUS = "com.dailydiary.STATUS"
        const val EXTRA_TEXT = "extra_text"

        /** Ordered map of BCP-47 tag → display name */
        val SUPPORTED_LANGUAGES = linkedMapOf(
            "en-US" to "English",
            "fr-FR" to "Français",
            "ar-MA" to "العربية المغربية",
            "es-ES" to "Español"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var transcriptionManager: TranscriptionManager
    private var currentLanguage = "en-US"

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        transcriptionManager = TranscriptionManager(this)
        currentLanguage = getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)
            .getString("speech_language", "en-US") ?: "en-US"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP  -> stopListening()
            ACTION_CHANGE_LANGUAGE -> {
                currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en-US"
                getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)
                    .edit().putString("speech_language", currentLanguage).apply()
                if (isListening) {
                    mainHandler.post { restartRecognizer() }
                    updateNotification()
                    broadcast(BROADCAST_STATUS,
                        "🔴 Listening (${SUPPORTED_LANGUAGES[currentLanguage]})")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Start / Stop ────────────────────────────────────────────────────

    private fun startListening() {
        if (isListening) return

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isListening = true
        mainHandler.post { setupAndStartRecognizer() }
        broadcast(BROADCAST_STATUS,
            "🔴 Listening (${SUPPORTED_LANGUAGES[currentLanguage]})")
        Log.d(TAG, "Started listening in $currentLanguage")
    }

    private fun stopListening() {
        isListening = false
        mainHandler.post { destroyRecognizer() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcast(BROADCAST_STATUS, "⏸️ Stopped")
        Log.d(TAG, "Stopped listening")
    }

    // ── SpeechRecognizer management ─────────────────────────────────────

    private fun setupAndStartRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            broadcast(BROADCAST_STATUS, "❌ Speech recognition unavailable on this device")
            return
        }
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
        }
        beginRecognition()
    }

    private fun restartRecognizer() {
        destroyRecognizer()
        setupAndStartRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying recognizer", e)
        }
        speechRecognizer = null
    }

    private fun beginRecognition() {
        if (!isListening || speechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Longer silence thresholds so natural pauses don't cut off speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recognition", e)
            scheduleRestart()
        }
    }

    // ── RecognitionListener ─────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "User started speaking")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "User stopped speaking")
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH          -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Silence timeout"
                SpeechRecognizer.ERROR_AUDIO              -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT             -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                SpeechRecognizer.ERROR_NETWORK            -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
                SpeechRecognizer.ERROR_SERVER              -> "Server error"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
                else -> "Error $error"
            }
            Log.w(TAG, "Recognition error: $msg")
            // Restart for every recoverable error
            if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                broadcast(BROADCAST_FINAL, text)
                serviceScope.launch {
                    try {
                        transcriptionManager.saveTranscription(text)
                        Log.d(TAG, "Saved: ${text.take(80)}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Save error", e)
                    }
                }
            }
            scheduleRestart() // continue listening
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                broadcast(BROADCAST_PARTIAL, text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun scheduleRestart() {
        if (!isListening) return
        mainHandler.postDelayed({
            if (isListening) beginRecognition()
        }, 250)
    }

    private fun broadcast(action: String, text: String) {
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, text)
        })
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
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
            .setContentText("Listening in ${SUPPORTED_LANGUAGES[currentLanguage]}…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isListening = false
        mainHandler.post { destroyRecognizer() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
