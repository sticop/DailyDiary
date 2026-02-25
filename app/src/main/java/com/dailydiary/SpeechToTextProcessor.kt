package com.dailydiary

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Speech-to-text processor using Deepgram Nova-2 API.
 * Supports multilingual transcription with automatic language detection
 * across English, French, Moroccan Arabic (Darija), and Spanish.
 *
 * Uses Deepgram's `language=multi` parameter for seamless code-switching
 * between all supported languages — no manual selection needed.
 *
 * Includes SSL trust fix for older Android devices (API < 25) that may
 * not trust modern root CAs (e.g., ISRG Root X1 used by Let's Encrypt).
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

    private val client: OkHttpClient = createHttpClient()

    /**
     * Create an OkHttpClient that trusts the bundled ISRG Root X1 certificate
     * on older Android versions where system trust store is outdated.
     */
    private fun createHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)

        // On Android < 7.1 (API 25), system CAs may not include ISRG Root X1
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            try {
                // Load the bundled ISRG Root X1 certificate
                val cf = CertificateFactory.getInstance("X.509")
                val caInput = context.resources.openRawResource(R.raw.lets_encrypt_isrg_root_x1)
                val ca = caInput.use { cf.generateCertificate(it) }

                // Create a KeyStore with both system CAs and our bundled CA
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("isrg_root_x1", ca)
                }

                // Also add all system trusted CAs
                val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                defaultTmf.init(null as KeyStore?)
                val defaultTrustManagers = defaultTmf.trustManagers
                for (tm in defaultTrustManagers) {
                    if (tm is X509TrustManager) {
                        for (cert in tm.acceptedIssuers) {
                            keyStore.setCertificateEntry(cert.subjectDN.name.hashCode().toString(), cert)
                        }
                    }
                }

                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore)
                val trustManagers = tmf.trustManagers
                val x509TrustManager = trustManagers[0] as X509TrustManager

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustManagers, null)

                builder.sslSocketFactory(sslContext.socketFactory, x509TrustManager)
                Log.d(TAG, "Custom SSL trust configured for older Android")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure custom SSL trust, using defaults", e)
            }
        }

        return builder.build()
    }

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
