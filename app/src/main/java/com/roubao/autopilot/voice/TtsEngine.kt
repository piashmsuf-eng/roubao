package com.roubao.autopilot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.roubao.autopilot.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TtsEngine - Simple HTTP TTS client (Cartesia/Speechify/Custom)
 * Uses a configurable base URL and plays returned audio bytes.
 */
class TtsEngine(private val context: Context) {
    companion object {
        private const val TAG = "TtsEngine"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun speak(text: String, settings: AppSettings) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        val baseUrl = settings.ttsBaseUrl.trim()
        if (baseUrl.isBlank()) {
            Log.w(TAG, "TTS base URL not set; skipping audio")
            return@withContext
        }

        val requestBody = JSONObject().apply {
            put("text", text)
            if (settings.ttsVoiceId.isNotBlank()) put("voice_id", settings.ttsVoiceId)
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (settings.ttsApiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${settings.ttsApiKey}")
                }
            }
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful || bytes == null) {
                    Log.w(TAG, "TTS failed: ${response.code}")
                    return@withContext
                }
                playAudio(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS error", e)
        }
    }

    private fun playAudio(bytes: ByteArray) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.requestAudioFocus(
            AudioManager.OnAudioFocusChangeListener { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )

        val file = File.createTempFile("tts_", ".mp3", context.cacheDir)
        file.writeBytes(bytes)

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                file.delete()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                file.delete()
                true
            }
            prepare()
            start()
        }
    }
}