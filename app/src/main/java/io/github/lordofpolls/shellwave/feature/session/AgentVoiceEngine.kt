package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import android.content.Intent
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
import android.util.Log
import java.util.Locale

/**
 * Manages audio text-to-speech read-back, alert chime, and 3-second voice response recognition.
 */
internal class AgentVoiceEngine(
    private val context: Context,
    private val onListeningStateChange: (Boolean) -> Unit = {},
    private val onSpeechRecognized: (String) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var toneGenerator: ToneGenerator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var stopListeningRunnable: Runnable? = null

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
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.isNotEmpty()) {
                            val resolved = resolveQuickReply(spoken)
                            onSpeechRecognized(resolved)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    /**
     * Reads aloud the spoken summary, chimes on finish, and opens a 3-second listening window.
     */
    fun speakAndListen(text: String) {
        val activeTts = tts
        if (activeTts == null || !ttsReady) {
            playChime()
            startListeningWindow()
            return
        }

        val utteranceId = "AgentDigest_${System.currentTimeMillis()}"
        activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    playChime()
                    startListeningWindow()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    playChime()
                    startListeningWindow()
                }
            }
        })

        activeTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun playChime() {
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
        }
    }

    fun startListeningWindow(windowMs: Long = 3000L) {
        val recognizer = speechRecognizer ?: return
        mainHandler.post {
            runCatching {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
                onListeningStateChange(true)

                // Schedule auto-stop after 3s
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

    private fun resolveQuickReply(spoken: String): String {
        val lower = spoken.lowercase(Locale.ROOT)
        return when {
            lower in listOf("yes", "yeah", "yep", "proceed", "approve", "ok", "sure", "continue") -> "y"
            lower in listOf("no", "nope", "cancel", "deny", "abort", "stop") -> "n"
            lower in listOf("option 1", "one", "first") -> "1"
            lower in listOf("option 2", "two", "second") -> "2"
            lower in listOf("option 3", "three", "third") -> "3"
            else -> spoken
        }
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
