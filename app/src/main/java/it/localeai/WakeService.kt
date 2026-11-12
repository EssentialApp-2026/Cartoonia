package it.localeai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Ascolto continuo della parola "SAM" in primo piano (notifica fissa).
 * Quando la sente risponde "Ti ascolto" e ascolta il comando.
 */
class WakeService : Service(), SamBrain.Listener {
    companion object {
        const val ACTION_STOP = "it.localeai.STOP_WAKE"
        const val ACTION_LISTEN_NOW = "it.localeai.LISTEN_NOW"
        private const val CHANNEL = "sam_wake"
        private const val NOTIF_ID = 42

        @Volatile var running = false
            private set

        private val WAKE = Regex("\\b(sam|sem|samm|saam|sammy)\\b", RegexOption.IGNORE_CASE)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_STOP))
        }

        fun listenNow(context: Context) {
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_LISTEN_NOW))
        }
    }

    private enum class State { WAKE, GREETING, COMMAND, WORKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var speech: SpeechInput
    private lateinit var muter: BeepMuter
    private var state = State.WAKE
    private var consecutiveErrors = 0

    private val voiceIdle: () -> Unit = { onVoiceIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SamBrain.init(this)
        muter = BeepMuter(this)
        speech = SpeechInput(this, ::onSpeech, ::onSpeechEnd)
        SamBrain.addListener(this)
        SamBrain.voice.addIdleListener(voiceIdle)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN_NOW -> {
                if (running) {
                    speech.cancel()
                    SamBrain.voice.stop()
                    listenForCommand()
                    return START_STICKY
                }
            }
        }
        if (!running) {
            running = true
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            } catch (e: Exception) {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            if (intent?.action == ACTION_LISTEN_NOW) listenForCommand() else listenForWake()
            SamBrain.setStatus("In ascolto: di' «SAM»")
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ascolto di SAM", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SAM è in ascolto")
            .setContentText("Di' «SAM» seguito da una richiesta")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Ferma", stop)
            .build()
    }

    private fun listenForWake() {
        if (!running) return
        state = State.WAKE
        if (AccountStore.muteBeeps) muter.mute()
        speech.start(partials = true, silenceMs = 1200L)
    }

    private fun listenForCommand() {
        if (!running) return
        state = State.COMMAND
        muter.unmute()
        speech.start(partials = false, silenceMs = 1800L)
    }

    private fun handleCommand(text: String) {
        state = State.WORKING
        speech.cancel()
        muter.unmute()
        SamBrain.submit(text, speak = true)
        resumeIfIdle()
    }

    private fun onSpeech(text: String, isFinal: Boolean) {
        when (state) {
            State.WAKE -> {
                val m = WAKE.find(text) ?: return
                // Aspetta la frase completa: "SAM, che ore sono" arriva in un solo risultato.
                if (!isFinal) return
                val rest = text.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?')
                if (rest.length >= 3) {
                    handleCommand(rest)
                } else {
                    state = State.GREETING
                    speech.cancel()
                    muter.unmute()
                    SamBrain.voice.speak("Ti ascolto")
                }
            }
            State.COMMAND -> if (isFinal) handleCommand(text)
            else -> Unit
        }
    }

    private fun onSpeechEnd(error: Int?) {
        if (!running) return
        when (state) {
            State.WAKE -> {
                consecutiveErrors = if (error == null || error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 0 else consecutiveErrors + 1
                val delay = when {
                    consecutiveErrors > 10 -> 10_000L
                    consecutiveErrors > 3 -> 2_000L
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    else -> 250L
                }
                main.postDelayed({ if (state == State.WAKE && !speech.listening) listenForWake() }, delay)
            }
            State.COMMAND -> {
                if (error != null) {
                    // Nessun comando sentito: torna ad aspettare "SAM".
                    main.postDelayed({ listenForWake() }, 300L)
                }
            }
            else -> Unit
        }
    }

    private fun onVoiceIdle() {
        when (state) {
            State.GREETING -> main.postDelayed({ listenForCommand() }, 150L)
            State.WORKING -> resumeIfIdle()
            else -> Unit
        }
    }

    private fun resumeIfIdle() {
        main.postDelayed({
            if (running && state == State.WORKING && !SamBrain.busy && !SamBrain.voice.isSpeaking) {
                listenForWake()
            }
        }, 600L)
    }

    override fun onMessagesChanged() {}

    override fun onStateChanged() {
        if (state == State.WORKING) resumeIfIdle()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        SamBrain.removeListener(this)
        SamBrain.voice.removeIdleListener(voiceIdle)
        speech.destroy()
        muter.unmute()
        SamBrain.refreshStatus()
        super.onDestroy()
    }
}
