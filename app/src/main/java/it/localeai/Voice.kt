package it.localeai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Sintesi vocale: legge le risposte frase per frase mentre arrivano. */
class SamVoice(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val beforeReady = mutableListOf<String>()
    private val buffer = StringBuilder()
    private var pending = 0
    private var counter = 0
    private val idleListeners = mutableListOf<() -> Unit>()

    val isSpeaking: Boolean get() = pending > 0

    override fun onInit(status: Int) {
        main.post {
            if (status != TextToSpeech.SUCCESS) return@post
            val italy = Locale("it", "IT")
            tts.language =
                if (tts.isLanguageAvailable(italy) >= TextToSpeech.LANG_AVAILABLE) italy else Locale.ITALIAN
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finishOne()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishOne()
                override fun onError(utteranceId: String?, errorCode: Int) = finishOne()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finishOne()
            })
            ready = true
            beforeReady.forEach { enqueue(it) }
            beforeReady.clear()
        }
    }

    private fun finishOne() {
        main.post {
            if (pending > 0) pending--
            if (pending == 0) idleListeners.toList().forEach { it() }
        }
    }

    fun addIdleListener(l: () -> Unit) { idleListeners += l }
    fun removeIdleListener(l: () -> Unit) { idleListeners -= l }

    /** Pulisce il Markdown che non va letto ad alta voce. */
    private fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*#`_>|~]+"), " ")
        .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enqueue(text: String) {
        val t = clean(text)
        if (t.isEmpty()) return
        if (!ready) { beforeReady += t; return }
        tts.setSpeechRate(AccountStore.speechRate)
        pending++
        val params = Bundle()
        tts.speak(t, TextToSpeech.QUEUE_ADD, params, "u${counter++}")
    }

    /** Legge subito un testo completo. */
    fun speak(text: String) {
        main.post { enqueue(text) }
    }

    /** Accoda un pezzo di risposta in streaming: legge ogni frase completa. */
    fun feed(delta: String) {
        main.post {
            buffer.append(delta)
            while (true) {
                var idx = -1
                for (i in 0 until buffer.length) {
                    val c = buffer[i]
                    if (c != '.' && c != '!' && c != '?' && c != ';' && c != '\n') continue
                    if (c == '.') {
                        // Un punto finale potrebbe essere un decimale ("3.5"): aspetta altro testo.
                        if (i + 1 >= buffer.length) break
                        if (buffer[i + 1].isDigit()) continue
                    }
                    idx = i
                    break
                }
                if (idx < 0) break
                val sentence = buffer.substring(0, idx + 1)
                buffer.delete(0, idx + 1)
                enqueue(sentence)
            }
            if (buffer.length > 300) {
                val cut = buffer.lastIndexOf(" ").takeIf { it > 50 } ?: buffer.length
                enqueue(buffer.substring(0, cut))
                buffer.delete(0, cut)
            }
        }
    }

    /** Fine della risposta: legge l'ultimo pezzo rimasto. */
    fun finish() {
        main.post {
            if (buffer.isNotEmpty()) enqueue(buffer.toString())
            buffer.clear()
            if (pending == 0) idleListeners.toList().forEach { it() }
        }
    }

    fun stop() {
        main.post {
            buffer.clear()
            beforeReady.clear()
            if (ready) tts.stop()
            pending = 0
            idleListeners.toList().forEach { it() }
        }
    }
}

/**
 * Riconoscimento vocale (una frase). Deve essere usato dal thread principale.
 */
class SpeechInput(
    private val context: Context,
    private val onText: (String, Boolean) -> Unit,   // testo, finale?
    private val onEnd: (error: Int?) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun ensure(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onText(text, false)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onText(text, true)
                onEnd(if (text.isNullOrBlank()) SpeechRecognizer.ERROR_NO_MATCH else null)
            }

            override fun onError(error: Int) {
                listening = false
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
                ) {
                    destroy()
                }
                onEnd(error)
            }
        })
        recognizer = r
        return r
    }

    fun start(partials: Boolean = true, silenceMs: Long = 1500L) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching {
            ensure().startListening(intent)
            listening = true
        }.onFailure {
            listening = false
            destroy()
            onEnd(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun cancel() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

/** Silenzia i "bip" del riconoscimento durante l'ascolto continuo. */
class BeepMuter(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM
    )
    var muted = false
        private set

    fun mute() {
        if (muted || audio == null) return
        streams.forEach { s -> runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } }
        muted = true
    }

    fun unmute() {
        if (!muted || audio == null) return
        streams.forEach { s -> runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } }
        muted = false
    }
}
