package com.dailydiary

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dailydiary.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)

        binding.etApiKey.setText(prefs.getString("openai_api_key", ""))
        binding.etEmailFrom.setText(prefs.getString("email_from", ""))
        binding.etEmailPassword.setText(prefs.getString("email_password", ""))
        binding.etEmailTo.setText(prefs.getString("email_to", ""))
        binding.etSmtpHost.setText(prefs.getString("smtp_host", "smtp.gmail.com"))
        binding.etSmtpPort.setText(prefs.getString("smtp_port", "587"))
        binding.etSummaryHour.setText(prefs.getInt("summary_hour", 22).toString())
        binding.etSummaryMinute.setText(prefs.getInt("summary_minute", 0).toString())
        binding.switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnGmailHelp.setOnClickListener {
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Gmail Setup")
            builder.setMessage(
                "To use Gmail for sending diary emails:\n\n" +
                "1. Go to Google Account Settings\n" +
                "2. Enable 2-Factor Authentication\n" +
                "3. Go to Security → App Passwords\n" +
                "4. Generate a new App Password for 'Mail'\n" +
                "5. Use that app password here (not your regular password)\n\n" +
                "SMTP Host: smtp.gmail.com\n" +
                "SMTP Port: 587\n\n" +
                "For Outlook:\n" +
                "SMTP Host: smtp-mail.outlook.com\n" +
                "SMTP Port: 587"
            )
            builder.setPositiveButton("OK", null)
            builder.show()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("daily_diary_prefs", MODE_PRIVATE)

        val apiKey = binding.etApiKey.text.toString().trim()
        val emailFrom = binding.etEmailFrom.text.toString().trim()
        val emailPassword = binding.etEmailPassword.text.toString()
        val emailTo = binding.etEmailTo.text.toString().trim()
        val smtpHost = binding.etSmtpHost.text.toString().trim()
        val smtpPort = binding.etSmtpPort.text.toString().trim()
        val summaryHour = binding.etSummaryHour.text.toString().toIntOrNull() ?: 22
        val summaryMinute = binding.etSummaryMinute.text.toString().toIntOrNull() ?: 0
        val autoStart = binding.switchAutoStart.isChecked

        // Validation
        if (apiKey.isBlank()) {
            binding.etApiKey.error = "API key is required for diary summaries"
            return
        }
        if (emailTo.isBlank()) {
            binding.etEmailTo.error = "Recipient email is required"
            return
        }
        if (summaryHour !in 0..23) {
            binding.etSummaryHour.error = "Hour must be 0-23"
            return
        }
        if (summaryMinute !in 0..59) {
            binding.etSummaryMinute.error = "Minute must be 0-59"
            return
        }

        prefs.edit().apply {
            putString("openai_api_key", apiKey)
            putString("email_from", emailFrom)
            putString("email_password", emailPassword)
            putString("email_to", emailTo)
            putString("smtp_host", smtpHost)
            putString("smtp_port", smtpPort)
            putInt("summary_hour", summaryHour)
            putInt("summary_minute", summaryMinute)
            putBoolean("auto_start", autoStart)
            apply()
        }

        // Reschedule daily summary with new time
        (application as DailyDiaryApp).let {
            // The app will reschedule on next launch
        }

        Toast.makeText(this, "Settings saved! ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
