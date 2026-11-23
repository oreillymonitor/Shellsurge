package io.github.lordofpolls.shellwave.feature.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Maps recognized speech to an explicit terminal quick-reply token (`"y"`, `"n"`, `"1"`..`"4"`,
 * or `""` for Enter), or `null` when the phrase is not a recognized quick confirmation.
 */
internal fun resolveQuickReply(spoken: String): String? {
    val lower = spoken.trim().lowercase(Locale.ROOT)
    return when {
        lower in setOf("yes", "yeah", "yep", "proceed", "approve", "ok", "okay", "sure", "continue") -> "y"
        lower in setOf("no", "nope", "cancel", "deny", "abort", "stop") -> "n"
        lower in setOf("option 1", "one", "first", "1") -> "1"
        lower in setOf("option 2", "two", "second", "2") -> "2"
        lower in setOf("option 3", "three", "third", "3") -> "3"
        lower in setOf("option 4", "four", "fourth", "4") -> "4"
        lower in setOf("enter", "return") -> ""
        else -> null
    }
}

/**
 * Manages audio text-to-speech read-back, alert chime, and 3-second voice response recognition.
 */
internal class AgentVoiceEngine(
    private val context: Context,
    private val onListeningStateChange: (Boolean) -> Unit = {},
    private val onQuickReply: (String) -> Unit = {},
    private val onFreeformSpeech: (String) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var toneGenerator: ToneGenerator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var stopListeningRunnable: Runnable? = null
    private var activeListenAutoTriggered = false

    init {
        initTts()
        initToneGenerator()
        initRecognizer()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
                ttsReady = true
            }
        }
    }

    private fun initToneGenerator() {
        runCatching {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        }
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onListeningStateChange(true)
                    }

                    override fun onBeginningOfSpeech() {
                        cancelStopTimer()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        onListeningStateChange(false)
                    }

                    override fun onError(error: Int) {
                        onListeningStateChange(false)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChange(false)
                        val autoTriggered = activeListenAutoTriggered
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotEmpty()) {
                            val quickReply = resolveQuickReply(spoken)
                            if (quickReply != null) {
                                onQuickReply(quickReply)
                            } else if (!autoTriggered) {
                                // Only pass free-form dictation when the user explicitly tapped the
                                // mic button; post-TTS auto-listen stays restricted to quick replies.
                                onFreeformSpeech(spoken)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    /**
     * Reads aloud the spoken summary and optionally chimes and opens a 3-second listening window.
     */
    fun speak(text: String, listenAfter: Boolean = false) {
        val activeTts = tts
        if (activeTts == null || !ttsReady) {
            if (listenAfter) {
                playChime()
                startListeningWindow(autoTriggered = true)
            }
            return
        }

        val utteranceId = "AgentDigest_${System.currentTimeMillis()}"
        activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (listenAfter) {
                    mainHandler.post {
                        playChime()
                        startListeningWindow(autoTriggered = true)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (listenAfter) {
                    mainHandler.post {
                        playChime()
                        startListeningWindow(autoTriggered = true)
                    }
                }
            }
        })

        activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun speakAndListen(text: String) {
        speak(text, listenAfter = true)
    }

    fun playChime() {
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
        }
    }

    fun startListeningWindow(windowMs: Long = 3000L, autoTriggered: Boolean = false) {
        val recognizer = speechRecognizer ?: return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        mainHandler.post {
            runCatching {
                activeListenAutoTriggered = autoTriggered
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
                onListeningStateChange(true)

                // Schedule auto-stop after windowMs
                cancelStopTimer()
                val runnable = Runnable {
                    stopListening()
                }
                stopListeningRunnable = runnable
                mainHandler.postDelayed(runnable, windowMs)
            }
        }
    }

    fun stopListening() {
        cancelStopTimer()
        runCatching {
            speechRecognizer?.stopListening()
        }
        onListeningStateChange(false)
    }

    private fun cancelStopTimer() {
        stopListeningRunnable?.let { mainHandler.removeCallbacks(it) }
        stopListeningRunnable = null
    }

    fun release() {
        cancelStopTimer()
        runCatching { tts?.stop(); tts?.shutdown() }
        runCatching { toneGenerator?.release() }
        runCatching { speechRecognizer?.destroy() }
        tts = null
        toneGenerator = null
        speechRecognizer = null
    }
}
