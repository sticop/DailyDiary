package com.dailydiary

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Speech-to-text processor using Deepgram Nova-2 API.
 * Supports multilingual transcription with automatic language detection
 * across English, French, Moroccan Arabic (Darija), and Spanish.
 *
 * Uses Deepgram's `language=multi` parameter for seamless code-switching
 * between all supported languages — no manual selection needed.
 */
class SpeechToTextProcessor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
        private const val DEEPGRAM_API_KEY = "6f9cc0cb7c2180febeb41ce630690592652f16dc"
        private const val DEEPGRAM_API_URL =
            "https://api.deepgram.com/v1/listen" +
            "?model=nova-2" +
            "&language=multi" +       // Auto-detect: EN, FR, AR (Darija), ES
            "&smart_format=true" +     // Smart formatting (punctuation, casing)
            "&punctuate=true" +        // Ensure punctuation
            "&diarize=false" +         // Single speaker diary use-case
            "&utterances=false"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe an audio file to text using Deepgram Nova-2 API.
     * Language is auto-detected across English, French, Arabic (Moroccan Darija),
     * and Spanish — supports mid-sentence code-switching seamlessly.
     */
    suspend fun transcribe(audioFile: File): String {
        return transcribeWithDeepgram(audioFile)
    }

    private suspend fun transcribeWithDeepgram(audioFile: File): String =
        suspendCoroutine { continuation ->
            try {
                // Deepgram accepts raw audio body (not multipart) with Content-Type header
                val requestBody = audioFile.asRequestBody("audio/wav".toMediaType())

                val request = Request.Builder()
                    .url(DEEPGRAM_API_URL)
                    .addHeader("Authorization", "Token $DEEPGRAM_API_KEY")
                    .addHeader("Content-Type", "audio/wav")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Deepgram API call failed", e)
                        continuation.resume("")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val body = response.body?.string() ?: ""
                            if (response.isSuccessful) {
                                val json = JSONObject(body)
                                // Deepgram response: results.channels[0].alternatives[0].transcript
                                val transcript = json
                                    .optJSONObject("results")
                                    ?.optJSONArray("channels")
                                    ?.optJSONObject(0)
                                    ?.optJSONArray("alternatives")
                                    ?.optJSONObject(0)
                                    ?.optString("transcript", "") ?: ""

                                // Also log detected language if available
                                val detectedLang = json
                                    .optJSONObject("results")
                                    ?.optJSONArray("channels")
                                    ?.optJSONObject(0)
                                    ?.optString("detected_language", "multi")
                                Log.d(TAG, "Deepgram [$detectedLang]: ${transcript.take(100)}")

                                continuation.resume(transcript)
                            } else {
                                Log.e(TAG, "Deepgram API error ${response.code}: $body")
                                continuation.resume("")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing Deepgram response", e)
                            continuation.resume("")
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error calling Deepgram API", e)
                continuation.resume("")
            }
        }
}
