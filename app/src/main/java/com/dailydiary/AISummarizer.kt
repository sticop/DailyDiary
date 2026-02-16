package com.dailydiary

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Uses OpenAI GPT API to generate a daily diary summary from transcriptions.
 */
class AISummarizer(private val context: Context) {

    companion object {
        private const val TAG = "AISummarizer"
        private const val GPT_API_URL = "https://api.openai.com/v1/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Generate a daily diary summary from transcriptions.
     */
    suspend fun generateSummary(transcriptions: String, date: String): String {
        val prefs = context.getSharedPreferences("daily_diary_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("openai_api_key", "") ?: ""

        if (apiKey.isBlank()) {
            return "⚠️ OpenAI API key not configured. Please set it in Settings.\n\nRaw transcriptions:\n$transcriptions"
        }

        return callGPT(transcriptions, date, apiKey)
    }

    private suspend fun callGPT(transcriptions: String, date: String, apiKey: String): String =
        suspendCoroutine { continuation ->
            try {
                val systemPrompt = """You are a personal diary assistant. You receive raw voice transcriptions
                    |captured throughout someone's day. Your job is to create a well-organized, engaging,
                    |and personal daily diary entry from these transcriptions.
                    |
                    |Guidelines:
                    |1. Organize the content chronologically (morning, afternoon, evening)
                    |2. Identify key activities, conversations, and events
                    |3. Note any emotions or moods expressed
                    |4. Highlight important decisions or insights mentioned
                    |5. Keep the tone personal and reflective, as if writing in a diary
                    |6. Include a brief "Day Summary" at the top
                    |7. Add a "Key Moments" section for standout events
                    |8. End with a "Mood & Reflection" section
                    |9. If transcriptions are sparse or unclear, note that gracefully
                    |10. Use emoji sparingly to add personality
                    |
                    |Format the diary entry with clear sections and make it something the person
                    |would enjoy reading back later.""".trimMargin()

                val userPrompt = """Here are the voice transcriptions from $date.
                    |Please create my daily diary entry:
                    |
                    |$transcriptions""".trimMargin()

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                }

                val requestJson = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("messages", messages)
                    put("max_tokens", 2000)
                    put("temperature", 0.7)
                }

                val request = Request.Builder()
                    .url(GPT_API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "GPT API call failed", e)
                        continuation.resume(
                            "❌ Failed to generate AI summary. Error: ${e.message}\n\nRaw transcriptions:\n$transcriptions"
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.body?.string() ?: ""
                            if (response.isSuccessful) {
                                val json = JSONObject(body)
                                val content = json.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                continuation.resume(content)
                            } else {
                                Log.e(TAG, "GPT API error ${response.code}: $body")
                                continuation.resume(
                                    "❌ AI Summary Error (${response.code}). Raw transcriptions:\n$transcriptions"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPT response", e)
                            continuation.resume(
                                "❌ Error parsing AI response. Raw transcriptions:\n$transcriptions"
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error calling GPT API", e)
                continuation.resume(
                    "❌ Error generating summary: ${e.message}\n\nRaw transcriptions:\n$transcriptions"
                )
            }
        }
}
