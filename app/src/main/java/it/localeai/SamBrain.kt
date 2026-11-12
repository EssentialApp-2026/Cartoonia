package it.localeai

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class ChatMessage(val fromUser: Boolean, val text: String, val source: String = "")

/**
 * Cervello unico di SAM, condiviso da schermata e servizio di ascolto:
 * comandi del telefono, IA online multi-account e modello locale.
 * Tutti i metodi pubblici vanno chiamati dal thread principale.
 */
object SamBrain {
    interface Listener {
        fun onMessagesChanged()
        fun onStateChanged()
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "sam-net") }
    private val listeners = linkedSetOf<Listener>()
    private var handle: CallHandle? = null
    private var initialized = false

    val messages = mutableListOf<ChatMessage>()
    lateinit var voice: SamVoice
        private set

    @Volatile var uiVisible = false
    var busy = false
        private set
    var status = ""
        private set

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        AccountStore.init(app)
        voice = SamVoice(app)
        initialized = true
        status = summary()
        if (AccountStore.mode == EngineMode.LOCAL_ONLY) preloadLocal()
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun changed() = listeners.toList().forEach { it.onMessagesChanged() }
    private fun stateChanged() = listeners.toList().forEach { it.onStateChanged() }

    fun setStatus(text: String) {
        status = text
        stateChanged()
    }

    fun summary(): String {
        val ready = AccountStore.usable().size
        val total = AccountStore.accounts.size
        val local = when {
            LlmEngine.loadedPath != null -> "locale caricato"
            LocalModel.file(app).exists() -> "locale pronto"
            else -> "nessun modello locale"
        }
        return when (AccountStore.mode) {
            EngineMode.LOCAL_ONLY -> "Solo locale · $local"
            EngineMode.ONLINE_ONLY -> "Solo online · $ready/$total account pronti"
            EngineMode.ONLINE_FIRST -> "Online $ready/$total account pronti · $local"
        }
    }

    fun refreshStatus() { if (!busy) setStatus(summary()) }

    fun preloadLocal() {
        val file = LocalModel.file(app)
        if (LlmEngine.loadedPath != null || !file.exists()) return
        setStatus("Caricamento modello locale…")
        net.execute {
            val ok = LocalModel.loadBlocking(file)
            main.post { setStatus(if (ok) summary() else "Il modello locale non si carica (RAM insufficiente?)") }
        }
    }

    /** Elabora una richiesta, scritta o detta. */
    fun submit(text: String, speak: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (busy) stopAll()
        voice.stop()

        messages += ChatMessage(fromUser = true, text = clean)
        changed()
        val speakIt = speak && AccountStore.speakReplies

        val command = try {
            CommandRouter.handle(app, clean)
        } catch (e: Exception) {
            CommandResult("Non sono riuscito a eseguire il comando: ${e.message}")
        }
        if (command != null) {
            if (command.reply.isNotEmpty()) {
                messages += ChatMessage(fromUser = false, text = command.reply, source = "Telefono")
                changed()
                if (speakIt && command.speak) voice.speak(command.reply)
            }
            refreshStatus()
            return
        }

        val turns = messages.takeLast(13)
            .filter { it.text.isNotBlank() }
            .map { Turn(if (it.fromUser) "user" else "assistant", it.text) }
            .dropWhile { it.role != "user" }

        messages += ChatMessage(fromUser = false, text = "")
        val index = messages.lastIndex
        val h = CallHandle()
        handle = h
        busy = true
        setStatus("Sto pensando…")
        changed()

        net.execute {
            var error: Throwable? = null
            try {
                AiRouter.generate(app, turns, h, object : AiRouter.Callbacks {
                    override fun onSource(label: String) {
                        main.post {
                            if (handle !== h || index >= messages.size) return@post
                            messages[index] = messages[index].copy(source = label)
                            changed()
                            setStatus("Risponde $label")
                        }
                    }

                    override fun onDelta(text: String) {
                        main.post {
                            if (handle !== h || index >= messages.size) return@post
                            val m = messages[index]
                            messages[index] = m.copy(text = m.text + text)
                            changed()
                            if (speakIt) voice.feed(text)
                        }
                    }

                    override fun onNotice(text: String) {
                        main.post { if (handle === h) setStatus(text) }
                    }
                })
            } catch (t: Throwable) {
                error = t
            }

            main.post {
                if (handle !== h) return@post
                handle = null
                busy = false
                if (index < messages.size) {
                    val m = messages[index]
                    val finalText = m.text.trim().ifEmpty {
                        error?.message?.let { "⚠ $it" } ?: "(nessuna risposta)"
                    }
                    messages[index] = m.copy(text = finalText)
                    if (speakIt) {
                        if (m.text.isBlank() && error != null) voice.speak(error?.message ?: "Errore.")
                        else voice.finish()
                    }
                }
                changed()
                setStatus(if (error != null) "Errore: ${error?.message}" else summary())
            }
        }
    }

    /** Ferma generazione e voce. */
    fun stopAll() {
        handle?.cancel()
        handle = null
        if (busy) {
            busy = false
            val last = messages.lastOrNull()
            if (last != null && !last.fromUser) {
                messages[messages.lastIndex] = last.copy(text = last.text.trim().ifEmpty { "(interrotto)" })
            }
            changed()
        }
        voice.stop()
        setStatus(summary())
    }

    fun newChat() {
        stopAll()
        messages.clear()
        changed()
    }
}
