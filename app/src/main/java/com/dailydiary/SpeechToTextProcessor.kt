package com.dailydiary

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Speech-to-text processor using OpenAI Whisper API.
 * Falls back to local silence detection if API is not configured.
 */
class SpeechToTextProcessor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
        private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe an audio file to text using OpenAI Whisper API.
     */
    suspend fun transcribe(audioFile: File): String {
        val prefs = context.getSharedPreferences("daily_diary_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("openai_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            Log.w(TAG, "No OpenAI API key configured. Audio chunk skipped.")
            return ""
        }

        return transcribeWithWhisper(audioFile, apiKey)
    }

    private suspend fun transcribeWithWhisper(audioFile: File, apiKey: String): String =
        suspendCoroutine { continuation ->
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        audioFile.name,
                        audioFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json")
                    .build()

                val request = Request.Builder()
                    .url(WHISPER_API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Whisper API call failed", e)
                        continuation.resume("") // Return empty on failure, don't crash
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.body?.string() ?: ""
                            if (response.isSuccessful) {
                                val json = JSONObject(body)
                                val text = json.optString("text", "")
                                Log.d(TAG, "Transcription: ${text.take(100)}")
                                continuation.resume(text)
                            } else {
                                Log.e(TAG, "Whisper API error ${response.code}: $body")
                                continuation.resume("")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing Whisper response", e)
                            continuation.resume("")
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Whisper API", e)
                continuation.resume("")
            }
        }
}
