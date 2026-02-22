package com.dailydiary

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dailydiary.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private lateinit var transcriptionManager: TranscriptionManager

    /** Accumulates finalized transcription lines for the current session */
    private val fullTranscription = StringBuilder()

    // ── BroadcastReceiver for live speech results ───────────────────────

    private val transcriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(AudioRecordingService.EXTRA_TEXT) ?: return
            when (intent.action) {
                AudioRecordingService.BROADCAST_PARTIAL -> {
                    // Show what the user is currently saying (in italics below the card)
                    binding.tvPartialResult.text = "… $text"
                }
                AudioRecordingService.BROADCAST_FINAL -> {
                    // Append finished sentence to the transcript
                    binding.tvPartialResult.text = ""
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    fullTranscription.append("[$time] $text\n\n")
                    binding.tvLiveTranscription.text = fullTranscription.toString()
                    binding.tvLiveIndicator.text = "🔴 Live Transcription"
                    binding.tvLiveIndicator.setTextColor(
                        ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                    // Auto-scroll to the latest line
                    binding.scrollTranscription.post {
                        binding.scrollTranscription.fullScroll(View.FOCUS_DOWN)
                    }
                    updateTranscriptionCount()
                }
                AudioRecordingService.BROADCAST_STATUS -> {
                    binding.tvStatus.text = text
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        transcriptionManager = TranscriptionManager(this)

        setupUI()
        checkPermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        registerTranscriptionReceiver()
        updateStatus()
        updateTranscriptionCount()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(transcriptionReceiver) } catch (_: Exception) {}
    }

    // ── Receiver registration ───────────────────────────────────────────

    private fun registerTranscriptionReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioRecordingService.BROADCAST_PARTIAL)
            addAction(AudioRecordingService.BROADCAST_FINAL)
            addAction(AudioRecordingService.BROADCAST_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcriptionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcriptionReceiver, filter)
        }
    }

    // ── UI Setup ────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnToggleRecording.setOnClickListener {
            if (isRecording) {
                stopRecordingService()
            } else {
                if (hasRequiredPermissions()) {
                    startRecordingService()
                } else {
                    checkPermissions()
                }
            }
        }

        binding.btnTestSummary.setOnClickListener {
            testGenerateSummary()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnOptimizeBattery.setOnClickListener {
            requestBatteryOptimization()
        }

        binding.btnClearTranscription.setOnClickListener {
            fullTranscription.clear()
            binding.tvLiveTranscription.text = "Tap Start Recording to see live transcription…"
            binding.tvPartialResult.text = ""
            binding.tvLiveIndicator.text = "📝 Live Transcription"
            binding.tvLiveIndicator.setTextColor(
                ContextCompat.getColor(this, R.color.primary))
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    // ── Service start / stop ────────────────────────────────────────────

    private fun startRecordingService() {
        val intent = Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        isRecording = true
        updateStatus()
        Toast.makeText(this, "Recording started 🎙️", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecordingService() {
        val intent = Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_STOP
        }
        startService(intent)
        isRecording = false
        updateStatus()
        binding.tvPartialResult.text = ""
        binding.tvLiveIndicator.text = "📝 Live Transcription"
        binding.tvLiveIndicator.setTextColor(
            ContextCompat.getColor(this, R.color.primary))
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    // ── Status helpers ──────────────────────────────────────────────────

    private fun updateStatus() {
        val prefs = getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)

        if (isRecording) {
            binding.btnToggleRecording.text = "Stop Recording"
            binding.btnToggleRecording.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.tvStatus.text = "🔴 Listening (auto-detect)"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.ivMicIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        } else {
            binding.btnToggleRecording.text = "Start Recording"
            binding.btnToggleRecording.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.tvStatus.text = "⏸️ Recording Paused"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.ivMicIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        // Config status
        val apiKey = prefs.getString("openai_api_key", "") ?: ""
        val email = prefs.getString("email_to", "") ?: ""

        if (apiKey.isBlank() || email.isBlank()) {
            binding.tvConfigStatus.text = "⚠️ Please configure API key and email in Settings"
            binding.tvConfigStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        } else {
            binding.tvConfigStatus.text = "✅ Configured — Summary will be sent to $email"
            binding.tvConfigStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    private fun updateTranscriptionCount() {
        lifecycleScope.launch {
            try {
                val count = transcriptionManager.getTodayCount()
                binding.tvTranscriptionCount.text = "Today's transcriptions: $count"
            } catch (e: Exception) {
                binding.tvTranscriptionCount.text = "Today's transcriptions: --"
            }
        }
    }

    // ── Test Summary ────────────────────────────────────────────────────

    private fun testGenerateSummary() {
        binding.btnTestSummary.isEnabled = false
        binding.btnTestSummary.text = "Generating..."

        lifecycleScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val displayDateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
                val today = dateFormat.format(Date())
                val displayDate = displayDateFormat.format(Date())

                val transcriptions = transcriptionManager.getFormattedTranscriptionsForDate(today)

                if (transcriptions.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "No transcriptions yet today. Start recording first!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val summarizer = AISummarizer(this@MainActivity)
                val summary = summarizer.generateSummary(transcriptions, displayDate)

                val builder = android.app.AlertDialog.Builder(this@MainActivity)
                builder.setTitle("📔 Today's Diary Preview")
                builder.setMessage(summary)
                builder.setPositiveButton("OK", null)
                builder.setNeutralButton("Send Email") { _, _ ->
                    sendTestEmail(summary, displayDate)
                }
                builder.show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnTestSummary.isEnabled = true
                binding.btnTestSummary.text = "Test Summary Now"
            }
        }
    }

    private fun sendTestEmail(summary: String, date: String) {
        lifecycleScope.launch {
            val emailSender = EmailSender(this@MainActivity)
            val sent = emailSender.sendDiarySummary(
                "📔 Daily Diary - $date",
                "$summary\n\n---\nGenerated by Daily Diary App"
            )
            Toast.makeText(
                this@MainActivity,
                if (sent) "Email sent! 📧" else "Failed to send email. Check settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Battery optimization ────────────────────────────────────────────

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already disabled ✓", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Menu ────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings")
        menu.add(0, 2, 0, "About")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            2 -> {
                val builder = android.app.AlertDialog.Builder(this)
                builder.setTitle("Daily Diary v1.1")
                builder.setMessage(
                    "Your AI-powered daily diary.\n\n" +
                    "Records and transcribes speech in real time, automatically " +
                    "detecting English, French, Moroccan Arabic (Darija), and Spanish.\n\n" +
                    "Generates a beautifully summarized diary entry every night " +
                    "and emails it to you.\n\n" +
                    "© 2026 Daily Diary"
                )
                builder.setPositiveButton("OK", null)
                builder.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted! ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required for recording",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
