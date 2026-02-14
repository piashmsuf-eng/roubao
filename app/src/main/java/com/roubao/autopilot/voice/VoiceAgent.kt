package com.roubao.autopilot.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.roubao.autopilot.agent.ConversationMemory
import com.roubao.autopilot.data.AppSettings
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.*
import org.json.JSONArray

/**
 * VoiceAgent - Bangla/Banglish voice chat with Jarvis-style responses.
 */
class VoiceAgent(
    private val context: Context,
    private val onTaskRequest: ((String) -> Unit)? = null,
    private val isTaskAllowed: (() -> Boolean)? = null
) {
    companion object {
        private const val TAG = "VoiceAgent"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var memory: ConversationMemory? = null
    private var ttsEngine: TtsEngine = TtsEngine(context)
    private var lastSpeechTime = System.currentTimeMillis()
    private var lastCheckinTime = 0L

    fun start(settings: AppSettings) {
        if (listening) return
        listening = true
        memory = ConversationMemory.withSystemPrompt(buildSystemPrompt(settings))
        initRecognizer(settings)
        startListening(settings)
        startIdleCheckins(settings)
    }

    fun stop() {
        listening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.coroutineContext.cancelChildren()
    }

    private fun initRecognizer(settings: AppSettings) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (listening) {
                        scope.launch { delay(600); startListening(settings) }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        lastSpeechTime = System.currentTimeMillis()
                        scope.launch { handleUserText(text, settings) }
                    }
                    if (listening) {
                        scope.launch { delay(400); startListening(settings) }
                    }
                }
            })
        }
    }

    private fun startListening(settings: AppSettings) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
        }
    }

    private suspend fun handleUserText(text: String, settings: AppSettings) {
        val normalized = if (settings.banglishEnabled) BanglishParser.normalize(text) else text

        if (shouldTriggerTask(normalized)) {
            if (isTaskAllowed?.invoke() != false) {
                onTaskRequest?.invoke(text)
            }
        }

        val memory = this.memory ?: return
        memory.addUserMessage(normalized)

        val baseUrl = settings.voiceBaseUrl.ifBlank { settings.currentVoiceProvider.baseUrl }
        if (baseUrl.isBlank() || settings.voiceApiKey.isBlank()) {
            val warn = "Boss, voice API config set korte hobe."
            memory.addAssistantMessage(warn)
            ttsEngine.speak(warn, settings)
            return
        }

        val client = VLMClient(
            apiKey = settings.voiceApiKey,
            baseUrl = baseUrl,
            model = settings.voiceModel
        )

        val messagesJson: JSONArray = memory.toMessagesJson(includeImages = false)
        val response = client.predictWithContext(messagesJson).getOrElse { "Boss, response dite parlam na." }

        memory.addAssistantMessage(response)
        ttsEngine.speak(response, settings)
    }

    private fun shouldTriggerTask(text: String): Boolean {
        val keywords = listOf(
            "open", "call", "send", "search", "play", "type", "message", "sms",
            "whatsapp", "youtube", "chrome", "camera", "settings", "music", "volume",
            "turn on", "turn off", "click", "scroll", "app"
        )
        return keywords.any { text.contains(it) }
    }

    private fun startIdleCheckins(settings: AppSettings) {
        scope.launch {
            while (listening) {
                if (settings.idleCheckinEnabled) {
                    val now = System.currentTimeMillis()
                    val idleMs = settings.idleCheckinSeconds * 1000L
                    if (now - lastSpeechTime > idleMs && now - lastCheckinTime > idleMs) {
                        lastCheckinTime = now
                        ttsEngine.speak(settings.idleCheckinMessage, settings)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun buildSystemPrompt(settings: AppSettings): String {
        return """
You are JARVIS from Iron Man. You speak in Bangla + English (Banglish). 
Be calm, concise, and proactive. If user is silent, ask if they need help.
Respond naturally, like a real assistant who understands emotions.
""".trimIndent()
    }
}