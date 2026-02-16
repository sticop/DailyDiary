package com.dailydiary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends daily diary summary emails via SMTP.
 * Supports Gmail, Outlook, and custom SMTP servers.
 */
class EmailSender(private val context: Context) {

    companion object {
        private const val TAG = "EmailSender"
    }

    suspend fun sendDiarySummary(subject: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("daily_diary_prefs", Context.MODE_PRIVATE)
        val smtpHost = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com"
        val smtpPort = prefs.getString("smtp_port", "587") ?: "587"
        val emailFrom = prefs.getString("email_from", "") ?: ""
        val emailPassword = prefs.getString("email_password", "") ?: ""
        val emailTo = prefs.getString("email_to", "") ?: ""

        if (emailFrom.isBlank() || emailPassword.isBlank() || emailTo.isBlank()) {
            Log.e(TAG, "Email settings not configured")
            return@withContext false
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort)
                put("mail.smtp.ssl.trust", smtpHost)
                put("mail.smtp.ssl.protocols", "TLSv1.2")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(emailFrom, emailPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(emailFrom, "Daily Diary"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailTo))
                setSubject(subject)
                setText(body, "utf-8", "plain")
            }

            Transport.send(message)
            Log.d(TAG, "Email sent successfully to $emailTo")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email", e)
            false
        }
    }
}
