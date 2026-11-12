package com.essentialapp.sam

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Servizio sempre attivo: ascolta la parola "SAM" (Vosk, offline), poi la
 * richiesta, la esegue tramite [Brain] o la passa all'IA locale ([Llm]),
 * e risponde a voce. Le risposte dell'IA si dicono frase per frase, appena pronte.
 * Riconosce la voce del proprietario, legge i documenti condivisi e guarda con la fotocamera.
 */
class VoiceService : Service() {

    /** Collegamento leggero con la schermata (stesso processo). */
    interface UiListener {
        fun onMode(m: OrbView.Mode)
        fun onLine(speaker: String, text: String)
        fun onReplace(speaker: String, text: String)
        fun onStatus(s: String)
    }

    /**
     * Tutto quello che va sullo schermo passa di qui e viene ricordato: se la schermata di SAM era chiusa
     * (telefono in tasca, un'altra app aperta), quando la riapri vede lo stato vero, non una scritta vecchia
     * come "Carico il cervello IA…" rimasta lì finché non tocchi Parla.
     */
    private val out = object : UiListener {
        override fun onMode(m: OrbView.Mode) { lastMode = m; ui?.onMode(m) }
        override fun onStatus(s: String) { val x = Identity.say(s); lastStatus = x; ui?.onStatus(x) }
        // 1.22: "SAM" sullo schermo diventa il nome che hai scelto (non nelle righe delle foto e dei video)
        override fun onLine(speaker: String, text: String) { val x = if (speaker == "Media") text else Identity.say(text); keepLine(speaker, x, replace = false); ui?.onLine(speaker, x) }
        override fun onReplace(speaker: String, text: String) { val x = if (speaker == "Media") text else Identity.say(text); keepLine(speaker, x, replace = true); ui?.onReplace(speaker, x) }
    }

    private enum class St { LOADING, IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var actions: Actions
    private lateinit var mem: Memory
    private lateinit var brain: Brain
    @Volatile private var llm: Llm? = null

    // microfono + riconoscimento
    @Volatile private var voskEngine: VoskEngine? = null
    @Volatile private var vosk: VoskRecognizer? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    @Volatile private var micPaused = false
    @Volatile private var needReset = false

    @Volatile private var state = St.LOADING
        set(v) { field = v; stateSince = System.currentTimeMillis(); trackWork(v) }
    // 2.0.2: il lavoro in corso (per riprenderlo se vengo fermato): vedi WorkLog
    private val work by lazy { WorkLog(filesDir) }
    private var workOn = false
    /** l'ultima cosa che mi hai chiesto (quella che si riprende con «continua») */
    private var lastRequest = ""
    /** quello che sto scrivendo adesso (programma o documento), per salvarlo se mi fermo */
    @Volatile private var workPartial: (() -> String)? = null
    /** «continua»: la parte già scritta da cui ripartire (la consuma writeFile) */
    private var resumePartial: String? = null
    private var resumeAt = 0L
    /** l'ultima volta che il cervello ha scritto qualcosa: finché scrive non è bloccato */
    @Volatile private var lastProgressAt = 0L

    private fun trackWork(v: St) {
        try {
            if (v == St.THINKING) { if (!workOn && lastRequest.isNotBlank()) { workOn = true; work.begin(if (writing) "write" else "ask", lastRequest) } }
            else if ((v == St.IDLE || v == St.LISTENING) && workOn && !llmActive && !writing && !drawing && !webbing && !reasoning) { workOn = false; workPartial = null; work.done() }
        } catch (e: Throwable) {}
    }
    /** da quando SAM è nello stato attuale (ogni frase detta lo rinnova) */
    @Volatile private var stateSince = System.currentTimeMillis()
    private var sessionActive = false
    /** frasi passate alla sintesi vocale e non ancora finite (per id: le frasi interrotte non contano) */
    private val utterances = HashSet<String>()
    private var stopAfterSpeak = false

    // risposta dell'IA in arrivo
    private val liveReply = StringBuilder()
    private var llmActive = false
    private var llmQuestion = ""
    private var llmSpoken = 0
    private var llmStopping = false
    private var llmTurn = 0
    /** la risposta in arrivo può finire con "vuoi che lo cerchi su internet?" */
    private var llmOffer = true
    /** oltre questa lunghezza la risposta si ferma (più lunga quando legge un documento) */
    private var llmMaxChars = Reply.MAX_CHARS
    /** chiacchierata normale (non meteo, foto, documenti, né l'altra IA): vale quello che dice sulla memoria */
    private var llmChat = false
    /** cosa fare quando la risposta è finita (es. "Continuo?" mentre legge un documento) */
    private var llmThen: (() -> Unit)? = null
    /** "di cosa abbiamo parlato?": le battute ripetute da SAM, se il cervello non sa rispondere */
    private var llmFallback: String? = null
    /** "pensaci bene": il cervello sta scrivendo il ragionamento (niente ascolto nel frattempo, come mentre risponde) */
    private var reasoning = false
    /**
     * L'ultima risposta del cervello in una chiacchierata e la sua domanda: se a una domanda diversa ridà la stessa
     * risposta ("Non te l'ha ancora detta." a ogni frase), è finito in un giro e si riparte puliti.
     */
    private var prevLlmAnswer: String? = null
    private var prevLlmQuestion: String? = null
    private var prevLlmAt = 0L

    private var nameAsks = 0

    // frasi raccolte finché non c'è una pausa lunga: il testo di un messaggio, o il turno dell'altra IA
    private val collected = StringBuilder()
    @Volatile private var collecting = false
    private var collectPause = 1600L
    private var onCollected: ((String) -> Unit)? = null
    private val commitCollected = Runnable {
        collecting = false
        val t = collected.toString().trim()
        collected.setLength(0)
        val then = onCollected
        onCollected = null
        if (t.isNotEmpty() && state == St.LISTENING) then?.invoke(t)
    }

    // conversazione a voce con un'altra intelligenza artificiale
    private var dialogue = false
    private var partner = ""
    private var dialogueTurns = 0
    private var dialogueQuiet = 0

    // la tua voce
    private lateinit var voiceStore: VoiceStore
    @Volatile private var profile: VoiceProfile? = null
    /** l'ultima volta che una frase è stata riconosciuta come tua: la conversazione resta "tua" per un po' */
    private var ownerSeenAt = 0L
    private var refusedAt = 0L
    /**
     * L'ultima volta che hai toccato "Parla": chi ha in mano il telefono è il proprietario, quindi per 2 minuti
     * (e per tutta la conversazione cominciata così) la voce non si controlla. Mai chiusi fuori da SAM.
     */
    private var touchedAt = 0L
    private var touchSession = false
    /** frasi di fila in cui SAM non era sicuro della voce: alla seconda spiega come rimediare invece di chiedere ancora */
    private var unsureInRow = 0
    private var unsureAt = 0L
    /** l'ultima volta che non ha riconosciuto la tua voce (0 = mai, o già proposto di impararla di nuovo) */
    private var voiceTroubleAt = 0L
    /** l'ultima riga "Info" sulla voce non riconosciuta: una ogni tanto, non a ogni frase della TV */
    private var voiceInfoAt = 0L
    private var adaptsSinceSave = 0
    /** l'impronta della voce dell'ultimo comando (per "chi sta parlando?") */
    private var lastVoice: VoiceSample? = null
    private var enrolling = false
    private val enrollSamples = ArrayList<VoiceSample>()
    private var enrollStep = 0
    private var enrollMisses = 0
    private var enrollWhenReady = false

    // l'enciclopedia sul telefono (Wikipedia di Kiwix): si apre in sottofondo, si legge dove sta
    @Volatile private var enc: Encyclopedia? = null
    /** com'è l'enciclopedia, per il cervello ("Wikipedia in italiano di settembre 2026"); null = non c'è */
    @Volatile private var library: String? = null

    // il documento condiviso con SAM
    private class OpenDoc(val file: File, val name: String, val text: String, val pages: Int, val scanned: Boolean) {
        /** pezzi da leggere ad alta voce (poi "Continuo?") */
        val sections: List<String> = if (scanned) emptyList() else DocText.chunks(text, 900)
        /** pezzi più grandi per riassumere e per rispondere alle domande */
        val chunks: List<String> = if (scanned) emptyList() else DocText.chunks(text, 2400)
        var next = 0
    }
    private var doc: OpenDoc? = null
    private var docWhenReady: Intent? = null

    // la vista: la domanda che aspetta la foto
    private var lookQuestion: String? = null
    private var lookTurn = 0
    /** l'ultima foto data al cervello: se la vista dà errore si riprova una volta, con l'altro modo di farla girare */
    private class LookReq(val image: String, val question: String, val read: Boolean, val then: (() -> Unit)?) { var retried = false }
    private var lastLook: LookReq? = null
    /** la foto della domanda in corso al cervello (null = nessuna) */
    private var llmImage: String? = null
    /** si sta ricaricando il cervello con l'altro modo di vedere: niente ascolto nel frattempo */
    private var visionSwitching = false

    /** modalità conversazione con SAM: ascolta di continuo, senza bisogno di dire "SAM", finché non dici basta */
    private var chatMode = false
    /** in conversazione si chiude solo con un congedo vero, non con "niente" o "nulla" detti parlando */
    private val CHAT_STOP = Lang.rx(
        "^\\W*(?:(?:ok|okay|va bene|allora|dai|bene|sì|si|grazie)\\s+)*(?:basta(?: così)?|stop|fine|finito|ho finito|smettila|smetti|esci|" +
        "chiudi|termina|ciao|ciao ciao|arrivederci|a dopo|a più tardi|buonanotte|ci sentiamo(?: dopo| più tardi| domani)?)" +
        "(?:\\s+(?:grazie|sam|ciao|così|per ora|per oggi))*\\W*$"
    )
    private var chatQuiet = 0
    /** le ultime battute (anche dei comandi): il cervello le riceve quando non le conosce */
    private val talk = Talk()
    /** 1.23: la mente (diario, impegni, abitudini, emozioni, forza dei ricordi), salvata in mente.txt */
    private val mind = Mind()
    private val mindFile by lazy { File(filesDir, "mente.txt") }
    private val saveMind = Runnable { val text = mind.toText(); Thread { writeSafely(mindFile, text) }.start() }
    private var talkNow: Talk.Line? = null
    /**
     * Le battute si salvano su file: se il telefono chiude SAM (per esempio mentre l'app Fotocamera occupa la memoria)
     * e lo riapre, la conversazione riprende da dove era rimasta, invece di ricominciare da zero.
     */
    private val talkFile by lazy { File(filesDir, "conversazione.tsv") }
    private val saveTalk = Runnable { val text = talk.toText(); Thread { writeSafely(talkFile, text) }.start() }

    private val silenceTimeout = Runnable {
        if (state != St.LISTENING) return@Runnable
        // 2.0.2: mentre lavoro (scrivo un programma, un documento, una risposta) non chiedo «ci sei ancora?» e non chiudo
        if (writing || llmActive || drawing || webbing || reasoning) return@Runnable
        if (dialogue) {
            // l'altra IA tace: prima si chiede, poi si chiude
            if (++dialogueQuiet == 1) speak("Ci sei ancora?")
            else endDialogue("Sembra che l'altra intelligenza artificiale non risponda più. Fine della conversazione.")
        } else if (enrolling) {
            enrolling = false
            speak("Non ti sento più: lasciamo stare. Quando vuoi di': SAM, impara la mia voce.", endSession = true)
        } else if (chatMode) {
            // in conversazione si aspetta di più: prima si chiede, poi si chiude
            if (++chatQuiet == 1) speak("Ci sei ancora?")
            else endChat("Ok, chiudo la conversazione. Quando vuoi, di' SAM.")
        } else speak("Ok, sono qui se mi chiami.", endSession = true)
    }

    // ------------------------------------------------------------------ ciclo di vita
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        self = this
        ensureShown(this)            // le righe di prima sullo schermo, anche se SAM era stato chiuso
        out.onStatus("Avvio di SAM…")
        acquireWake()
        startForegroundSafe()
        actions = AndroidActions(this).also { act ->
            // esiti che arrivano dopo (WhatsApp inviato, app bloccata da Android): SAM li dice a voce
            act.speakLater = { msg -> main.post { sayLater(msg) } }
        }
        mem = FileMemory(this)
        brain = Brain(actions, mem)
        // la conversazione di prima (anche se il telefono aveva chiuso SAM): il filo del discorso non si perde
        try { if (talkFile.exists()) talk.load(talkFile.readText(Charsets.UTF_8)) } catch (e: Exception) {}
        talk.onChange = { main.removeCallbacks(saveTalk); main.postDelayed(saveTalk, 1500) }
        brain.talk = talk
        try { if (mindFile.exists()) mind.load(mindFile.readText(Charsets.UTF_8)) } catch (e: Exception) {}
        mind.onChange = { main.removeCallbacks(saveMind); main.postDelayed(saveMind, 2000) }
        brain.mind = mind
        // il riordino veloce dei ricordi (senza cervello): le conversazioni finite da un po' vanno nel diario
        Thread { try { mind.sleep(talk.before(null, within = Talk.RECALL_MS), mem.ownerName(), System.currentTimeMillis()) } catch (e: Throwable) {} }.start()
        voiceStore = VoiceStore(this)
        profile = voiceStore.load()
        // 2.0: la guardia degli avvii (tre finiti male di fila = modalità sicura) e l'avviso dell'ultima chiusura
        PhoneCare.brainProblem = null; PhoneCare.brainLoaded = false; PhoneCare.ttsProblem = null
        PhoneCare.safeMode = PhoneCare.guard(this).started()
        main.postDelayed({ PhoneCare.guard(this).healthy() }, 60_000)
        if (PhoneCare.safeMode) {
            PhoneCare.journal(this).add("W", "avvio", "tre avvii di fila finiti male: parto in modalità sicura")
            out.onLine("SAM", "Le ultime volte mi sono chiuso subito dopo l'avvio, quindi sono partito in modalità sicura: il cervello non l'ho caricato, " +
                "i comandi funzionano. Chiedimi «cosa è andato storto?», e «riparati» per riprovare.")
        } else PhoneCare.journal(this).lastCrash(System.currentTimeMillis() - 86_400_000L)?.let { c ->
            if (prefs.getLong("chiusura_detta", 0L) != c.at) { prefs.edit().putLong("chiusura_detta", c.at).apply()
                out.onLine("Info", "L'ultima volta mi sono chiuso da solo per un errore. L'ho segnato nel registro: chiedimi «cosa è andato storto?».") }
        }
        // 2.0.2: se sono stato chiuso mentre lavoravo, me lo ricordo
        brain.hasWork = { try { work.pending() != null } catch (e: Throwable) { false } }
        try { work.recover()?.let { j -> out.onLine("Info", "Quando mi sono fermato stavo lavorando a: «${WorkLog.short(j.text)}». Di' «continua» e riprendo da lì.") } } catch (e: Throwable) {}
        startTts()
        startEngines()
    }

    /** Accende la sintesi vocale con il motore scelto (o quello del telefono): voce, velocità e tono salvati. */
    private fun startTts() {
        ttsReady = false
        val engine = VoiceSettings.engine(this)
        val onInit = TextToSpeech.OnInitListener { st -> main.post { onTtsInit(st) } }
        tts = try { if (engine != null) TextToSpeech(this, onInit, engine) else TextToSpeech(this, onInit) }
              catch (e: Exception) { TextToSpeech(this, onInit) }
    }

    private fun onTtsInit(st: Int) {
        if (st != TextToSpeech.SUCCESS) {
            // il motore scelto non parte (disinstallato?): si torna a quello del telefono
            if (VoiceSettings.engine(this) != null) {
                VoiceSettings.setEngine(this, null)
                try { tts.shutdown() } catch (e: Exception) {}
                startTts()
            }
            return
        }
        try { tts.setLanguage(Locale.ITALIAN) } catch (e: Exception) {}
        // solo voci installate sul telefono: niente sintesi via internet
        val v = VoiceSettings.apply(this, tts)
        ttsReady = true
        PhoneCare.ttsProblem = if (v == null) "Sul telefono manca una voce italiana che funzioni senza internet" else null
        if (v == null) out.onStatus("Manca la voce italiana offline: Impostazioni › Sintesi vocale › scarica Italiano")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { onSpokenOne(id) } }
            @Deprecated("deprecata in Android") override fun onError(id: String?) { main.post { onSpokenOne(id) } }
            override fun onError(id: String?, code: Int) { main.post { onSpokenOne(id) } }
            override fun onStop(id: String?, interrupted: Boolean) { main.post { onSpokenOne(id) } }
        })
    }

    /** La voce è cambiata nella configurazione: si riapplica (con un altro motore, la sintesi riparte). */
    private fun voiceSettingsChanged(engineChanged: Boolean) {
        if (engineChanged) {
            try { if (ttsReady) { tts.stop(); tts.shutdown() } } catch (e: Exception) {}
            utterances.clear()
            startTts()
        } else if (ttsReady) VoiceSettings.apply(this, tts)
    }

    private var wakeWhenReady = false
    /** scritto mentre SAM si stava ancora avviando: si esegue appena è pronto */
    private var typedWhenReady: String? = null
    /**
     * Hai scritto a SAM invece di parlargli (riunione, posto silenzioso): le risposte restano sullo schermo, senza voce
     * e senza "ti ascolto". Finisce appena tocchi Parla o gli parli.
     */
    @Volatile private var quiet = false
    /** una domanda di SAM rimasta senza risposta scritta non vale più dopo un po' */
    private val expirePending = Runnable {
        if (quiet && state == St.IDLE && brain.hasPending) { brain.cancelPending(); out.onStatus(idleStatus()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe() // obbligatorio dopo ogni startForegroundService
        try { wakeLock?.acquire(12 * 60 * 60 * 1000L) } catch (e: Exception) {}
        // hai aperto SAM: se il microfono non dava audio (tolto da un'altra app, o negato in sottofondo) si riapre adesso,
        // con SAM in primo piano Android lo concede
        if (intent?.action == null || intent.action == ACTION_WAKE) main.post { checkMicOnOpen() }
        when (intent?.action) {
            ACTION_TYPED -> main.post { typedArrived(intent.getStringExtra(EXTRA_TEXT) ?: "") }
            ACTION_WAKE -> main.post {
                quiet = false; main.removeCallbacks(expirePending)
                // hai toccato "Parla": sei tu, anche se in questo momento non ti riconosco dalla voce
                touchedAt = System.currentTimeMillis(); touchSession = true; unsureInRow = 0
                when (state) {
                    St.IDLE -> if (!maybeAskName(first = false) && !offerVoiceRelearn() && !offerNews() && !offerMind()) beginListening()
                    St.LOADING -> wakeWhenReady = true
                    St.THINKING, St.SPEAKING -> interrupt()   // "Parla" mentre risponde: lo zittisce e ascolta
                    St.LISTENING -> if (dialogue) interrupt()   // "Parla" durante la conversazione con l'altra IA: la chiude
                }
            }
            ACTION_STOP -> main.post { endDialogue(null); interruptQuietly(); speakThenStop("Mi spengo. A presto.") }
            ACTION_RELOAD_BRAIN -> { brainOverride = null; Thread { loadBrain() }.start() }
            ACTION_DOCUMENT -> main.post { if (state == St.LOADING) docWhenReady = intent else onDocumentIntent(intent) }
            ACTION_PHOTO -> main.post { onPhoto(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_ERROR)) }
            ACTION_MEDIA -> main.post { onMediaTaken(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_MIME),
                intent.getStringExtra(EXTRA_NAME), intent.getLongExtra(EXTRA_DURATION, 0L), intent.getStringExtra(EXTRA_ERROR)) }
            ACTION_MEDIA_ALLOWED -> main.post { onMediaAllowed(intent.getBooleanExtra(EXTRA_ENGINE, false)) }
            ACTION_ENROLL -> main.post {
                if (state == St.LOADING) enrollWhenReady = true
                else { endDialogue(null); interruptQuietly(); resumeMic(); startEnroll() }
            }
            ACTION_VOICE_CHANGED -> main.post { profile = voiceStore.load() }
            ACTION_ENCYCLOPEDIA -> Thread { openEncyclopedia() }.start()
            ACTION_VOICE_SETTINGS -> main.post { voiceSettingsChanged(intent.getBooleanExtra(EXTRA_ENGINE, false)) }
            ACTION_DOWNLOADS -> Thread {
                // uno scaricamento degli aggiornamenti è finito: si controlla e si mette al suo posto (la novità la dico dopo)
                val news = try { Updater.processDownloads(this) } catch (e: Throwable) { emptyList() }
                if (news.isNotEmpty()) main.post { for (n in news) out.onLine("Info", n) }
                // il modello per disegnare: quando è pronto lo si dice anche a voce
                val img = try { Painter.processDownloads(this) } catch (e: Throwable) { null }
                if (img != null) main.post { sayLater(img) }
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        micRunning = false
        try { micThread?.interrupt(); currentRec?.stop() } catch (e: Throwable) {}
        try { micThread?.join(1500) } catch (e: InterruptedException) {}
        try { vosk?.close() } catch (e: Throwable) {}
        try { voskEngine?.close() } catch (e: Throwable) {}
        try { if (ttsReady) { tts.stop(); tts.shutdown() } } catch (e: Exception) {}
        val l = llm
        Thread { try { l?.close() } catch (e: Throwable) {} }.start()
        try { enc?.close() } catch (e: Throwable) {}
        enc = null
        profile?.let { p -> if (adaptsSinceSave > 0) try { voiceStore.save(p) } catch (e: Exception) {} }
        talk.onChange = null
        writeSafely(talkFile, talk.toText())
        mind.onChange = null
        writeSafely(mindFile, mind.toText())
        saveShownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        if (self === this) self = null
        out.onMode(OrbView.Mode.IDLE); out.onStatus("SAM spento · tocca Parla per riaccenderlo")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ avvio motori
    private fun nativeLib(name: String) = File(applicationInfo.nativeLibraryDir, "lib$name.so").absolutePath

    private fun startEngines() {
        Thread {
            // 1) riconoscimento vocale offline (Vosk), importato nella configurazione;
            //    il modello delle voci (chi parla) è dentro l'app
            if (!ModelStore.voskReady(this)) {
                main.post { out.onStatus("Manca la voce italiana: importala dalla configurazione (⚙︎)."); stopSelf() }
                return@Thread
            }
            try {
                // il modello delle voci si copia dall'app a ogni aggiornamento: se non riesce, si riprova una volta
                val spk = ModelStore.speakerModel(this) ?: ModelStore.speakerModel(this)
                val e = VoskEngine(nativeLib("vosk"), SamApp.voskDir(this).absolutePath, spk?.absolutePath)
                voskEngine = e
                vosk = e.recognizer()
                if (!e.knowsVoices && profile != null && voiceStore.onlyOwner) main.post {
                    out.onLine("Info", "Il modello delle voci non è partito: per ora non posso riconoscere la tua voce, quindi rispondo a tutti. " +
                        "Se succede ancora, riavvia il telefono o reinstalla SAM.apk.")
                }
            } catch (e: Throwable) {
                main.post { out.onStatus("Errore nel modello vocale: ${e.message}"); stopSelf() }
                return@Thread
            }
            main.post {
                startMic()
                state = St.IDLE
                out.onMode(OrbView.Mode.IDLE)
                out.onStatus(idleStatus())
                // prima accensione: SAM si presenta e chiede il nome del proprietario
                val prefs = getSharedPreferences("sam", Context.MODE_PRIVATE)
                if (mem.ownerName() == null && !prefs.getBoolean("nome_chiesto", false)) {
                    prefs.edit().putBoolean("nome_chiesto", true).apply()
                    wakeWhenReady = false
                    whenTtsReady { if (state == St.IDLE) maybeAskName(first = true) }
                } else if (enrollWhenReady) {
                    enrollWhenReady = false
                    whenTtsReady { if (state == St.IDLE) startEnroll() }
                } else if (docWhenReady != null) {
                    val i = docWhenReady!!; docWhenReady = null
                    whenTtsReady { onDocumentIntent(i) }
                } else if (wakeWhenReady) { wakeWhenReady = false; if (!maybeAskName(first = false)) beginListening() }
                // una cosa scritta mentre SAM partiva
                typedWhenReady?.let { t -> typedWhenReady = null; if (state == St.IDLE) onTyped(t) }
            }
            // 2) l'enciclopedia, se l'hai messa (si apre in un attimo: legge solo l'indice)
            openEncyclopedia()
            // 3) cervello locale (LiteRT-LM), in sottofondo
            loadBrain()
            // 4) gli aggiornamenti: il controllo settimanale col Wi-Fi, e gli scaricamenti finiti mentre SAM era spento
            Updater.schedule(this)
            try { Updater.processDownloads(this) } catch (e: Throwable) {}
            try { Painter.processDownloads(this)?.let { n -> main.post { out.onLine("Info", n) } } } catch (e: Throwable) {}
        }.start()
    }

    // il cervello: da quando si sta caricando (0 = non si sta caricando) e, se non è partito, perché
    @Volatile private var brainLoadingSince = 0L
    @Volatile private var brainError: String? = null
    /** il motore deve rifare la copia di lavoro dei pesi (prima volta, o dopo un cambio di cervello): ci mette di più */
    @Volatile private var brainCold = false
    private val prefs get() = getSharedPreferences("sam", Context.MODE_PRIVATE)

    /** La scritta quando SAM aspetta: dice sempre a che punto è il cervello. */
    private fun idleStatus(): String {
        val l = llm
        return when {
            brainLoadingSince > 0 -> loadingStatus()
            l != null && l.ready -> readyStatus(l)
            brainError != null -> "Cervello IA non caricato (i comandi funzionano): $brainError"
            else -> "Di' “SAM” quando vuoi"
        }
    }

    private fun loadingStatus(): String {
        val s = (System.currentTimeMillis() - brainLoadingSince) / 1000
        val usual = prefs.getLong("cervello_ms", 0L) / 1000
        val hint = when {
            brainCold -> " · la prima volta ci mette di più: prepara i pesi"
            usual > 0 && s > usual * 2 + 60 -> " · ci mette più del solito"
            usual > 0 -> " (di solito ${usual} s)"
            else -> ""
        }
        return "Carico il cervello IA… $s s$hint · i comandi funzionano già"
    }

    private fun readyStatus(l: Llm): String {
        val f = loadedName?.let { File(SamApp.modelsDir(this), it) } ?: SamApp.llmFile(this)
        val nome = f?.let { Brains.byFile(it.name)?.name ?: it.nameWithoutExtension } ?: "IA"
        val vista = when {
            l.canSee -> " · vede le immagini" + (if (l.visionBackend == "gpu") " (scheda grafica)" else "")
            l.info?.vision == true -> " · vista non partita"
            else -> ""
        }
        val soloTe = if (::voiceStore.isInitialized && profile != null && voiceStore.onlyOwner) " · solo la tua voce" else ""
        val libro = if (enc != null) " · enciclopedia" else ""
        return "SAM pronto · cervello $nome$vista$libro$soloTe · di' “SAM”"
    }

    /** Mentre il cervello carica, la scritta conta i secondi: si vede che va avanti. */
    private val loadingTicker = object : Runnable {
        override fun run() {
            if (brainLoadingSince <= 0) return
            if (state == St.IDLE) out.onStatus(loadingStatus())
            main.postDelayed(this, 3000)
        }
    }

    /** Carica (o ricarica, dopo un import) il cervello IA. Thread di background. */
    @Synchronized
    private fun loadBrain() {
        if (PhoneCare.safeMode) { brainError = "modalità sicura: di' riparati per caricarlo"; return }
        // il cervello per il lavoro in corso (programmare, guardare), altrimenti quello principale
        val f = brainOverride?.let { File(SamApp.modelsDir(this), it).takeIf { it.isFile } } ?: SamApp.llmFile(this) ?: return
        val t0 = System.currentTimeMillis()
        brainLoadingSince = t0; brainError = null
        main.post {
            if (state == St.IDLE || state == St.LOADING) out.onStatus(loadingStatus())
            main.removeCallbacks(loadingTicker); main.postDelayed(loadingTicker, 3000)
        }
        val old = llm
        llm = null
        try { old?.close() } catch (e: Throwable) {}
        val threads = try { Brains.threadsFor(Hardware.specs(this)) } catch (e: Throwable) { 4 }
        // la copia di lavoro del motore (circa 2 GB) sta in una cartella che Android e la pulizia di Xiaomi
        // non svuotano: nella cache veniva cancellata e il cervello doveva rifarla da capo (minuti)
        val work = SamApp.engineCacheDir(this)
        // la vecchia copia di lavoro nella cache (fino alla 1.6.3) non serve più: via prima di farne una nuova,
        // così non ci sono mai due copie da 2 GB insieme
        try { cacheDir.listFiles()?.filter { it.name.endsWith(".xnnpack_cache") }?.forEach { it.delete() } } catch (e: Throwable) {}
        // i cervelli GGUF (llama.cpp) non hanno una copia di lavoro da preparare
        brainCold = !f.name.endsWith(".gguf", ignoreCase = true) &&
            work.listFiles()?.none { it.name.startsWith(f.name) && it.name.endsWith(".xnnpack_cache") && it.length() > 0 } != false
        val gguf = try { File(applicationInfo.nativeLibraryDir, GgufLiteRt.variantFor(File("/proc/cpuinfo").readText())).absolutePath }
                   catch (e: Throwable) { nativeLib("rnllama_v8") }
        val l = Llm(nativeLib("litert-lm"), f.absolutePath, work.absolutePath, threads, ggufLib = gguf)
        l.ownerName = mem.ownerName()
        l.memory = Remember.forPrompt(mem.memos())
        l.creativity = prefs.getInt("creativita", 0)
        try {
            l.load(); llm = l; loadedName = f.name
            PhoneCare.brainProblem = null; PhoneCare.brainLoaded = true
            val ms = System.currentTimeMillis() - t0
            // "di solito": conta solo un avvio normale, non quello in cui ha preparato i pesi
            if (l.warmStart) prefs.edit().putLong("cervello_ms", ms).apply()
            brainCold = false
            brainLoadingSince = 0
            main.post {
                main.removeCallbacks(loadingTicker)
                if (state == St.IDLE) out.onStatus(readyStatus(l))
                out.onLine("Info", "Cervello pronto in ${(ms + 500) / 1000} secondi (${l.loadDetail}).")
            }
            l.visionNote?.let { n -> main.post { out.onLine("SAM", "Vista: $n.") } }
        } catch (e: Throwable) {
            brainLoadingSince = 0
            brainError = e.message ?: "errore"
            PhoneCare.brainProblem = brainError; PhoneCare.brainLoaded = false
            PhoneCare.journal(this).add("E", "cervello", "${f.nameWithoutExtension} non si è caricato: ${brainError}")
            main.post { main.removeCallbacks(loadingTicker); if (state == St.IDLE) out.onStatus(idleStatus()) }
        }
    }

    // ------------------------------------------------------------------ microfono
    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            out.onStatus("Manca il permesso del microfono."); return
        }
        if (micThread?.isAlive == true) return
        micRunning = true
        micThread = Thread({ micLoop() }, "sam-mic").apply { start() }
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 10_000)
    }

    /** come è finita una registrazione: spenta da SAM, da rifare subito (richiesta), oppure guasta (si riprova tra poco) */
    private enum class MicEnd { STOPPED, RESTART, FAILED }

    /**
     * Il microfono non si arrende: se Android lo toglie a SAM (una chiamata, un'altra app, il sistema audio che riparte)
     * o non riesce ad aprirlo, lo si riapre da solo dopo 1, 2, 3… secondi (al massimo 30). Prima, se si guastava,
     * SAM restava sordo finché non lo riavviavi.
     */
    private fun micLoop() {
        var failures = 0
        while (micRunning) {
            val end = try { recordOnce() } catch (e: Throwable) { MicEnd.FAILED }
            if (!micRunning) break
            if (end == MicEnd.RESTART) { failures = 0; continue }
            failures++
            val f = failures
            if (f == 1 || f % 10 == 0) main.post { onMicTrouble(f) }
            try { Thread.sleep(minOf(30_000L, 1_000L * failures)) } catch (e: InterruptedException) { break }
        }
    }

    /** Una registrazione, finché il microfono funziona. */
    private fun recordOnce(): MicEnd {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return MicEnd.FAILED }   // occupato da un'altra app
        currentRec = rec
        val buf = ByteArray(3200) // 100 ms di audio
        var n = 0
        var reads = 0
        var bad = 0
        var silencedSince = 0L
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MicEnd.FAILED
            lastAudioAt = System.currentTimeMillis()
            main.post { onMicOk() }
            while (micRunning) {
                if (micRestartAsked) { micRestartAsked = false; return MicEnd.RESTART }
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) {
                    // niente audio: il microfono è stato tolto a SAM o il sistema audio è ripartito. Prima qui si girava
                    // a vuoto per sempre (SAM sordo): ora si ricrea
                    if (r == AudioRecord.ERROR_DEAD_OBJECT || ++bad > 25) return MicEnd.FAILED
                    try { Thread.sleep(40) } catch (e: InterruptedException) { return MicEnd.STOPPED }
                    continue
                }
                bad = 0
                lastAudioAt = System.currentTimeMillis()
                // ogni 2 secondi: un'altra app (la Fotocamera, una chiamata) si è presa il microfono? Android ci passa silenzio
                if (++reads % 20 == 0) {
                    val silenced = try { rec.activeRecordingConfiguration?.isClientSilenced == true } catch (e: Throwable) { false }
                    if (silenced && silencedSince == 0L) {
                        silencedSince = System.currentTimeMillis()
                        main.post { onMicSilenced(true, 0L) }
                    } else if (silenced && silencedSince > 0L) {
                        val ms = System.currentTimeMillis() - silencedSince
                        if (ms in 60_000L..62_500L) main.post { onMicSilencedLong() }
                    } else if (!silenced && silencedSince > 0L) {
                        val ms = System.currentTimeMillis() - silencedSince
                        silencedSince = 0L
                        main.post { onMicSilenced(false, ms) }
                    }
                }
                if (micPaused) { needReset = true; continue }
                val v = vosk ?: continue
                if (needReset) { v.reset(); needReset = false }
                if (v.accept(buf, r)) {
                    // la frase e l'impronta della voce di chi l'ha detta
                    val (text, voice) = v.resultWithVoice()
                    if (text.isNotBlank()) main.post { onUtterance(text, voice) }
                } else if (collecting && ++n % 3 == 0) {
                    // sta ancora parlando: il messaggio (o il turno dell'altra IA) non è finito
                    if (v.partial().isNotBlank()) main.post { if (collecting) { main.removeCallbacks(commitCollected); main.postDelayed(commitCollected, collectPause) } }
                } else if (++n % 3 == 0 && state == St.IDLE) {
                    val p = v.partial()
                    if (p.isNotBlank() && Hearing.wakeOnPartial(p)) main.post { if (state == St.IDLE) beginListening() }
                }
            }
            return MicEnd.STOPPED
        } finally {
            currentRec = null
            if (silencedSince > 0L) main.post { onMicSilenced(false, 0L) }
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
    }

    // il microfono: l'ultima volta che è arrivato audio, la registrazione in corso, e la richiesta di rifarla
    @Volatile private var lastAudioAt = 0L
    @Volatile private var currentRec: AudioRecord? = null
    @Volatile private var micRestartAsked = false
    private var micTrouble = false
    /** un'altra app (o Android) tiene il microfono: ci arriva solo silenzio */
    private var micSilenced = false

    /** Rifà la registrazione da capo (anche se è ferma in attesa di audio che non arriva). */
    private fun restartMic() {
        micRestartAsked = true
        lastAudioAt = System.currentTimeMillis()
        try { currentRec?.stop() } catch (e: Throwable) {}
    }

    /** SAM aperto (o "Parla"): il microfono deve funzionare. */
    private fun checkMicOnOpen() {
        if (state == St.LOADING) return
        if (!micRunning || micThread?.isAlive != true) { startMic(); return }
        if (micTrouble || micSilenced || System.currentTimeMillis() - lastAudioAt > 5_000) restartMic()
        cancelMicNotice()
    }

    private fun onMicTrouble(@Suppress("UNUSED_PARAMETER") failures: Int) {
        // una riga sola per ogni volta che si guasta, non una a ogni tentativo
        if (!micTrouble) out.onLine("Info", "Il microfono non risponde (forse lo usa un'altra app): lo riapro da solo.")
        micTrouble = true
        if (state == St.IDLE || state == St.LOADING) out.onStatus("Il microfono non risponde: riprovo da solo…")
    }

    private fun onMicOk() {
        if (micTrouble) {
            micTrouble = false
            out.onLine("Info", "Microfono di nuovo attivo.")
            if (state == St.IDLE) out.onStatus(idleStatus())
        }
    }

    /** Un'altra app ha preso il microfono ([on]) o l'ha lasciato dopo [ms] millisecondi: lo si dice sullo schermo. */
    private fun onMicSilenced(on: Boolean, ms: Long) {
        micSilenced = on
        if (on) { out.onStatus("Un'altra app sta usando il microfono (la Fotocamera, una chiamata…): ti risento appena finisce"); return }
        cancelMicNotice()
        if (ms >= 5000) out.onLine("Info", "Per ${(ms + 500) / 1000} secondi un'altra app ha occupato il microfono (la Fotocamera, " +
            "una chiamata…): in quel tempo non potevo sentirti.")
        if (state == St.IDLE) out.onStatus(idleStatus())
    }

    /**
     * Da un minuto il microfono arriva muto e non c'è una chiamata: o un'altra app lo tiene, o Android non lo lascia
     * a SAM in sottofondo. Una notifica: toccandola si apre SAM, e da lì il microfono riparte.
     */
    private fun onMicSilencedLong() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) return
        notifyUser(NID_MIC, "SAM non riesce a sentirti", "Un'altra app tiene il microfono, oppure Android non lo lascia a SAM: tocca qui per riattivarlo.")
    }

    private fun cancelMicNotice() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID_MIC) } catch (e: Exception) {}
    }

    /**
     * Controllo ogni 10 secondi: il microfono deve dare audio (anche quando SAM parla si legge), e SAM non deve restare
     * bloccato a parlare o a pensare (una voce che non avvisa di aver finito, una risposta che non arriva):
     * in quei casi si sblocca da solo e torna ad ascoltare.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // il filo del microfono è finito per un errore imprevisto: si riparte; una lettura ferma da 15 secondi: si rifà
            if (micRunning && micThread?.isAlive != true) startMic()
            else if (micRunning && currentRec != null && now - lastAudioAt > 15_000) restartMic()
            val since = now - stateSince
            when (state) {
                St.SPEAKING -> if (since > 30_000 && !llmActive && !(ttsReady && try { tts.isSpeaking } catch (e: Exception) { false })) {
                    utterances.clear(); afterSpeaking()
                }
                // 2.0.2: «bloccato» vuol dire che il cervello non scrive più niente da 4 minuti (prima un programma lungo
                // veniva fermato dopo 10 minuti anche se stava ancora scrivendo)
                St.THINKING -> if (minOf(since, now - lastProgressAt) > 240_000 && !reasoning && !drawing && !webbing && lookQuestion == null && !visionSwitching) {
                    interruptQuietly(); resumeMic()
                    PhoneCare.journal(this@VoiceService).add("E", "risposta", "mi sono bloccato mentre pensavo: ho ripreso da solo")
                    speak(if (work.pending() != null) "Scusa, mi ero bloccato. Mi ricordo cosa stavo facendo: di' continua, e riprendo da lì." else "Scusa, mi ero bloccato. Sono di nuovo in ascolto.", endSession = true)
                }
                else -> {}
            }
            main.removeCallbacks(this)          // sempre uno solo in attesa
            main.postDelayed(this, 10_000)
        }
    }

    private fun pauseMic() { micPaused = true }
    private fun resumeMic() { needReset = true; micPaused = false }

    // ------------------------------------------------------------------ flusso
    private fun onUtterance(text: String, voice: VoiceSample?) {
        if (state != St.IDLE && state != St.LISTENING) return
        // 1.22: dopo aver cambiato nome, la prima cosa che dici è il nome: si impara come lo scrive il riconoscitore
        if (learnNameUntil > System.currentTimeMillis()) { learnName(text); return }
        val listening = state == St.LISTENING
        if (dialogue) { onDialogueUtterance(text); return }
        if (enrolling) { if (listening) onEnrollUtterance(text, voice); return }
        if (listening && brain.awaitingText) {
            // testo di un messaggio: si aspetta la fine anche se fai delle pause
            collect(text, 1600, "Scrivo: ") { t -> sessionActive = true; process(t) }
            return
        }
        when (val h = Hearing.decide(text, listening, brain.hasPending)) {
            Heard.Ignore -> if (listening) keepListening()
            Heard.WakeOnly -> if (run { quiet = false; false } || !maybeAskName(first = false) && (listening || (!offerNews() && !offerMind()))) beginListening()
            Heard.Stop -> when {
                // in conversazione si chiude solo con un congedo vero ("basta", "fine", "ciao ciao")
                chatMode && CHAT_STOP.containsMatchIn(Utterance.command(text)) -> endChat("Ok, fine della conversazione. Quando vuoi, di' SAM.")
                // "grazie" è cortesia, non un congedo: si va avanti
                chatMode && Lang.isThanks(text) && Utterance.command(text).split(' ').size <= 2 -> { chatQuiet = 0; speak("Figurati!") }
                // "niente di speciale", "nulla di che": fanno parte del discorso
                chatMode -> { chatQuiet = 0; sessionActive = true; process(Utterance.command(text)) }
                else -> speak(if (Lang.isThanks(text)) "Figurati!" else "Ok, quando ti serve chiamami.", endSession = true)
            }
            is Heard.Command -> {
                main.removeCallbacks(silenceTimeout)
                if (!voiceAllowed(voice, h.text)) return
                quiet = false; main.removeCallbacks(expirePending)
                lastVoice = voice
                sessionActive = true
                chatQuiet = 0
                process(h.text)
            }
        }
    }

    /**
     * Con "rispondi solo a me" i comandi di un'altra voce non si eseguono; nel dubbio (frase troppo corta,
     * rumore) SAM chiede di ripetere, a meno che la conversazione l'abbia appena cominciata il proprietario.
     * Chi tocca "Parla" passa sempre: se SAM non riconosce più la tua voce non resti mai chiuso fuori,
     * e lui ti propone di impararla di nuovo. Intanto, le frasi sicuramente tue migliorano l'impronta.
     */
    private fun voiceAllowed(voice: VoiceSample?, text: String): Boolean {
        val p = profile ?: return true
        // senza il modello delle voci non si può riconoscere nessuno: meglio rispondere a tutti che a nessuno
        if (voskEngine?.knowsVoices != true) return true
        val now = System.currentTimeMillis()
        val v = VoiceId.verdict(p, voice)
        if (v == VoiceId.Verdict.OWNER) {
            ownerSeenAt = now; unsureInRow = 0; voiceTroubleAt = 0L
            voice?.let { learnVoice(p, it) }
            return true
        }
        if (!voiceStore.onlyOwner) return true
        // hai toccato "Parla" da poco: sei tu
        if (touchTrusted(now)) {
            // sei tu di sicuro: se la voce non superava la soglia, la soglia si adatta a come parli davvero
            voice?.let { relaxVoice(p, it) }
            return true
        }
        // "mi riconosci?": domanda innocua, risponde sempre (e dice quanto somiglia la voce)
        if (!brain.hasPending && brain.isVoiceCheck(text)) return true
        if (v == VoiceId.Verdict.UNSURE && now - ownerSeenAt < 90_000L) return true

        // non ti ho riconosciuto: sullo schermo quanto somiglia e come rimediare (la riga una volta ogni tanto).
        // Se mi avevi chiamato (non la TV mentre si chiacchiera), al prossimo "Parla" propongo di imparare di nuovo la voce
        if (!chatMode || v == VoiceId.Verdict.UNSURE) voiceTroubleAt = now
        val how = voiceMatch(p, voice)
        if (now - voiceInfoAt > 10 * 60_000L) {
            voiceInfoAt = now
            out.onLine("Info", "Non ho riconosciuto la voce di «${text.take(60)}» ($how). Se eri tu: tocca Parla e ti propongo " +
                "di impararla di nuovo, oppure ⚙︎ › La tua voce › Rispondi a tutti.")
        }
        val listening = state == St.LISTENING
        if (v == VoiceId.Verdict.UNSURE) {
            if (now - unsureAt > 120_000L) unsureInRow = 0
            unsureAt = now
            when (++unsureInRow) {
                1 -> { sessionActive = true; speak("Non ho riconosciuto bene la tua voce: ripeti, con qualche parola in più.") }
                2 -> speak("Ancora non riconosco la tua voce. Se sei tu, tocca Parla sullo schermo e ti ascolto lo stesso.", endSession = true)
                else -> if (chatMode) keepListening() else endSession()
            }
        } else if (now - refusedAt > 10 * 60_000L) {
            // un'altra voce: lo si dice per bene ogni tanto, non a ogni frase della TV
            refusedAt = now
            speak("Scusa, rispondo solo alla voce di ${mem.ownerName() ?: "chi me l'ha insegnata"}. " +
                "Se sei tu e non ti riconosco, tocca Parla sullo schermo.", endSession = true)
        } else if (listening && !chatMode) {
            // mi avevi chiamato ("SAM", poi il comando): non resto zitto, sembrerebbe che non ti senta
            speak("Non riconosco la tua voce.", endSession = true)
        } else if (chatMode) keepListening() else endSession()
        out.onStatus("Voce non riconosciuta ($how) · se sei tu, tocca Parla")
        return false
    }

    private fun touchTrusted(now: Long) = now - touchedAt < 120_000L || (touchSession && (sessionActive || chatMode))

    /** Per lo schermo: quanto somiglia la frase alla tua voce e quanto serve, o perché non si può dire. */
    private fun voiceMatch(p: VoiceProfile, s: VoiceSample?): String =
        if (s == null || s.frames < VoiceId.MIN_FRAMES) "frase troppo corta per riconoscere la voce"
        else "somiglia ${al(VoiceId.similarity(p, s))}%, serve ${il(Math.round((1f - VoiceId.thresholdFor(p, s)) * 100f).coerceIn(0, 100))}%"

    /** "al 52", "all'82", "il 58", "l'80": l'articolo giusto davanti a un numero, come si dice a voce. */
    private fun al(n: Int) = if (vowelNumber(n)) "all'$n" else "al $n"
    private fun il(n: Int) = if (vowelNumber(n)) "l'$n" else "il $n"
    private fun vowelNumber(n: Int) = n == 1 || n == 8 || n == 11 || n in 80..89

    /** Hai toccato "Parla" poco dopo che SAM non ti aveva riconosciuto: ti propone di imparare di nuovo la tua voce. */
    private fun offerVoiceRelearn(): Boolean {
        val now = System.currentTimeMillis()
        if (profile == null || !voiceStore.onlyOwner || voiceTroubleAt == 0L || now - voiceTroubleAt > 30 * 60_000L) return false
        voiceTroubleAt = 0L
        sessionActive = true
        actions.vibrate(60)
        speak(brain.offerVoiceRelearn())
        return true
    }

    /** Migliora con l'uso: l'impronta si avvicina a come parli adesso. Si salva ogni tanto. */
    private fun learnVoice(p: VoiceProfile, s: VoiceSample) {
        val np = VoiceId.adapt(p, s) ?: return
        profile = np
        if (++adaptsSinceSave >= 5) {
            adaptsSinceSave = 0
            Thread { try { voiceStore.save(np) } catch (e: Exception) {} }.start()
        }
    }

    /** Una frase sicuramente tua (detta dopo "Parla") che non superava la soglia: la soglia si allarga un poco e si salva subito. */
    private fun relaxVoice(p: VoiceProfile, s: VoiceSample) {
        val np = VoiceId.relax(p, s) ?: return
        profile = np
        Thread { try { voiceStore.save(np) } catch (e: Exception) {} }.start()
    }

    /** Esegue [action] appena la sintesi vocale è pronta (al massimo dopo 5 secondi). */
    private fun whenTtsReady(waited: Int = 0, action: () -> Unit) {
        if (ttsReady || waited >= 5000) action()
        else main.postDelayed({ whenTtsReady(waited + 250, action) }, 250)
    }

    /**
     * Se non sa ancora come si chiama il proprietario, glielo chiede: alla prima accensione
     * e, se non ha risposto, al massimo un'altra volta finché SAM resta acceso.
     */
    private fun maybeAskName(first: Boolean): Boolean {
        if (mem.ownerName() != null || nameAsks >= 2) return false
        nameAsks++
        sessionActive = true
        actions.vibrate(60)
        speak(brain.askOwnerName(first))   // la conversazione resta aperta per la risposta
        return true
    }

    private fun beginListening() {
        sessionActive = true
        state = St.LISTENING
        actions.vibrate(60)                 // segnale "ti ascolto", senza bip
        out.onMode(OrbView.Mode.LISTENING)
        out.onStatus("Ti ascolto…")
        main.removeCallbacks(silenceTimeout)
        main.postDelayed(silenceTimeout, 12000)
    }

    private fun keepListening() {
        state = St.LISTENING
        out.onMode(OrbView.Mode.LISTENING)
        out.onStatus(when {
            enrolling -> "Ripeti la frase…"
            chatMode -> "Conversazione · parla pure, di' “basta” per finire"
            else -> "Ti ascolto…"
        })
        main.removeCallbacks(silenceTimeout)
        main.postDelayed(silenceTimeout, if (dialogue) 30000 else if (chatMode) 45000 else 15000)
    }

    /** Aggiunge una frase a quelle raccolte; [then] arriva quando c'è una pausa più lunga di [pause]. */
    private fun collect(text: String, pause: Long, status: String, then: (String) -> Unit) {
        val piece = text.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        if (piece.isEmpty()) return
        if (collected.isNotEmpty()) collected.append(' ')
        collected.append(piece)
        collecting = true; collectPause = pause; onCollected = then
        main.removeCallbacks(silenceTimeout)
        main.removeCallbacks(commitCollected)
        main.postDelayed(commitCollected, pause)
        out.onStatus(status + collected)
    }

    private fun stopCollecting() {
        collecting = false; collected.setLength(0); onCollected = null; main.removeCallbacks(commitCollected)
    }

    private fun endSession() {
        scheduleReflect()
        sessionActive = false
        chatMode = false
        touchSession = false
        state = St.IDLE
        stopCollecting()
        enrolling = false
        if (dialogue) { dialogue = false; llm?.preface = null }
        brain.cancelPending()   // una domanda rimasta senza risposta non vale più
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.IDLE)
        out.onStatus(idleStatus())   // dice anche a che punto è il cervello
    }

    private fun process(text: String) {
        // 1.20: le skill chieste a parole ("traduci in inglese: …", "correggimi questa email: …") partono da sole
        Skills.autoDetect(text)?.let { r -> if (r.skill.kind == Skills.Kind.TEXT) { runSkill(r, auto = true); return } }
        out.onLine("Tu", text)
        talkNow = talk.start(text)
        val o = brain.handle(text)
        if (o !is Outcome.Care) lastRequest = text
        // hai fatto dimenticare o cambiare qualcosa: il cervello IA riparte con i ricordi nuovi. Una cosa aggiunta invece
        // la conosce già dal discorso (la battuta "ricordati che…"): niente ripartenza, così non perde il filo
        brain.takeMemoryChange()
        if (brain.takeMemoryDropped()) llm?.memoryUrgent = true
        brain.takeLearned()?.let { out.onLine("Memoria", "ho imparato «$it»") }
        // 1.23: quello che la mente si è segnata da sola (gli impegni detti di passaggio), e le cose fatte (le abitudini)
        for (n in brain.takeMindNotes()) out.onLine("Memoria", n)
        try { mind.act(text, o, System.currentTimeMillis()) } catch (e: Throwable) {}
        perform(o)
    }

    private fun perform(o: Outcome) {
        when (o) {
            is Outcome.Speak -> { talk.reply(talkNow, o.text); speak(o.text, endSession = o.endSession) }
            is Outcome.Handled -> if (quiet && !chatMode) finishQuiet() else if (o.endSession && !chatMode) endSession() else keepListening()
            is Outcome.ReadChat -> readChat(o)
            // 1.20: una domanda di programmazione va da sola al cervello per programmare
            is Outcome.NeedLlm -> withBrainFor(if (o.reason) ModelRouter.Task.CHAT else ModelRouter.taskFor(o.prompt)) {
                if (o.reason) reasonThen(o.prompt) else askLlm(o.prompt, recall = o.recall, fallback = o.fallback)
            }
            is Outcome.Dialogue -> { endChat(null, quiet = true); startDialogue(o.partner, o.topic) }
            is Outcome.Weather -> weather(o)
            is Outcome.VoiceCmd -> voiceCommand(o.action)
            is Outcome.Doc -> docAction(o)
            is Outcome.Look -> look(o)
            is Outcome.Chat -> chat(o)
            is Outcome.Wiki -> wiki(o.subject)
            is Outcome.Tune -> tune(o)
            is Outcome.BrainCmd -> brainCmd(o.action)
            is Outcome.Care -> careCmd(o)
            is Outcome.Update -> updateCmd(o.action)
            is Outcome.SelfEdit -> selfEdit(o.complaint)
            is Outcome.SaveFile -> fileCmd(o)
            is Outcome.Draw -> drawCmd(o)
            is Outcome.Media -> media(o)
            is Outcome.Rename -> rename(o)
            is Outcome.Web -> withBrainFor(ModelRouter.Task.CHAT) { if (o.deep) research(o.query) else webAnswer(o.query) }
            is Outcome.Many -> {
                // una scorciatoia con più comandi: si dicono le frasi, poi l'ultimo esito (meteo, domanda all'IA…)
                val last = o.parts.lastOrNull() ?: return
                for (p in o.parts.dropLast(1)) if (p is Outcome.Speak) { talk.reply(talkNow, p.text); speak(p.text) }
                perform(last)
            }
            Outcome.NewConversation -> {
                val l = llm
                talk.clear()
                prevLlmAnswer = null; prevLlmQuestion = null
                if (l != null) Thread { try { l.reset() } catch (e: Throwable) {} }.start()
                speak("Fatto, ripartiamo da zero.")
            }
            Outcome.Quit -> speakThenStop("Mi spengo. A presto.")
        }
    }

    // ------------------------------------------------------------------ enciclopedia sul telefono
    /** Apre (o chiude) l'enciclopedia scelta nella configurazione. Thread di sottofondo. */
    private fun openEncyclopedia() {
        val old = enc
        enc = null
        try { old?.close() } catch (e: Throwable) {}
        val uri = EncyclopediaFile.uri(this)
        if (uri == null) { library = null; main.post { brain.encyclopedia = false; brain.encyclopediaInfo = null; refreshIdle() }; return }
        try {
            val e = EncyclopediaFile.open(this, uri)
            e.date            // legge subito i dati del file (e controlla che si apra)
            enc = e
            val label = e.name + (if (e.language == "ita") " in italiano" else e.language?.let { " ($it)" } ?: "") +
                (Encyclopedia.monthYear(e.date)?.let { " di $it" } ?: "")
            val info = "$label, con ${String.format(Locale.ITALY, "%,d", e.articles)} voci"
            library = label
            main.post { brain.encyclopedia = true; brain.encyclopediaInfo = info; refreshIdle() }
            // la prima volta che si apre un file (o uno più nuovo) lo si dice sullo schermo
            val key = "$uri|${e.date}"
            if (prefs.getString("enciclopedia_annunciata", null) != key) {
                prefs.edit().putString("enciclopedia_annunciata", key).apply()
                main.post { out.onLine("Info", "Enciclopedia pronta: ${EncyclopediaFile.describe(e)}. Chiedimi per esempio: chi era Garibaldi?") }
            }
            // un'enciclopedia di più di sei mesi: lo si dice, al massimo una volta al mese
            val age = Encyclopedia.ageMonths(e.date)
            val last = prefs.getLong("enciclopedia_avviso", 0L)
            if (age != null && age >= 6 && !Updater.autoOn(this) && System.currentTimeMillis() - last > 30L * 86_400_000L) {
                prefs.edit().putLong("enciclopedia_avviso", System.currentTimeMillis()).apply()
                main.post { out.onLine("Info", "L'enciclopedia è di ${Encyclopedia.monthYear(e.date)}: quando vuoi, scarica quella nuova " +
                    "(⚙︎ › Enciclopedia). Le cose di attualità potrebbero essere cambiate.") }
            }
        } catch (e: Throwable) {
            library = null
            main.post {
                brain.encyclopedia = false
                brain.encyclopediaInfo = null
                out.onLine("Info", "Non riesco ad aprire l'enciclopedia (${e.message ?: "file non leggibile"}): sceglila di nuovo in ⚙︎ › Enciclopedia.")
            }
        }
    }

    /** Se SAM è fermo in attesa, aggiorna la scritta (per esempio quando l'enciclopedia è pronta). */
    private fun refreshIdle() { if (state == St.IDLE) out.onStatus(idleStatus()) }

    /**
     * La voce dell'enciclopedia per una domanda ("chi era Garibaldi?"), già pronta da mettere davanti alla domanda.
     * Non per le persone e le cose di casa: se il soggetto è nei ricordi o è un nome comune ("chi è Maria?") si salta.
     */
    private fun groundFor(e: Encyclopedia, question: String, memos: List<Memo>): Pair<String, String>? = try {
        val subj = Encyclopedia.subject(question)
        if (subj == null || Encyclopedia.isCommonFirstName(subj) || Encyclopedia.isPersonal(subj, memos.map { it.text + " " + it.key }) ||
            mem.ownerName()?.equals(subj, ignoreCase = true) == true) null
        else e.find(subj, 900)?.let { f ->
            val whenTxt = Encyclopedia.monthYear(e.date)?.let { " di $it" } ?: ""
            f.title to "(Dall'enciclopedia sul telefono, Wikipedia$whenTxt, voce «${f.title}»: ${f.text.replace('\n', ' ')})\n" +
                "Rispondi alla domanda basandoti su questo testo."
        }
    } catch (x: Throwable) { null }

    /** "Cosa dice l'enciclopedia su Napoli?": legge l'inizio della voce, così com'è. */
    private fun wiki(subject: String) {
        val e = enc
        if (e == null) { speak("Non ho ancora l'enciclopedia sul telefono: la puoi mettere da ⚙︎, alla voce Enciclopedia."); return }
        val turn = llmTurn
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Cerco nell'enciclopedia…")
        Thread {
            val f = try { e.find(subject, 1200) } catch (x: Throwable) { null }
            main.post {
                if (turn != llmTurn) return@post          // l'hai interrotto
                if (f == null) {
                    val msg = "Nell'enciclopedia non trovo «$subject». Prova a dirmi il nome intero, per esempio con nome e cognome."
                    talk.reply(talkNow, msg); speak(msg)
                } else {
                    val whenTxt = Encyclopedia.monthYear(e.date)?.let { " di $it" } ?: ""
                    val txt = WikiText.sentences(f.text, 3)
                    out.onLine("Enciclopedia", "«${f.title}»")
                    val msg = "Dall'enciclopedia$whenTxt: $txt"
                    talk.reply(talkNow, msg)
                    speak(msg)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ come parla e come pensa SAM (dalla 1.10)
    /** "Cambia voce", "parla più piano", "voce più grave", "sii più preciso". */
    private fun tune(o: Outcome.Tune) {
        when (o.what) {
            "voice_which" -> speak(describeVoice())
            "voice" -> changeVoice(o.step)
            "rate", "rate_piano" -> {
                val old = VoiceSettings.rate(this)
                val r = Tuning.stepped(old, o.step, Tuning.RATE_MIN, Tuning.RATE_MAX)
                VoiceSettings.setRate(this, r)
                if (ttsReady) try { tts.setSpeechRate(r) } catch (e: Exception) {}
                speak(when {
                    r == old && o.step < 0 -> "Più lentamente di così non riesco."
                    r == old -> "Più veloce di così non si capirebbe niente."
                    o.step < 0 -> "Ok, parlo più lentamente." +
                        (if (o.what == "rate_piano") " Se invece volevi che parlassi più basso, di': abbassa la voce." else "")
                    else -> "Ok, parlo più veloce."
                })
            }
            "pitch" -> {
                val old = VoiceSettings.pitch(this)
                val p = Tuning.stepped(old, o.step, Tuning.PITCH_MIN, Tuning.PITCH_MAX)
                VoiceSettings.setPitch(this, p)
                if (ttsReady) try { tts.setPitch(p) } catch (e: Exception) {}
                speak(when {
                    p == old && o.step < 0 -> "Più grave di così non posso."
                    p == old -> "Più acuta di così non posso."
                    o.step < 0 -> "Ok, voce più grave."
                    else -> "Ok, voce più acuta."
                })
            }
            "creativity" -> {
                val old = prefs.getInt("creativita", 0)
                val now = Tuning.creativity(old, o.step)
                prefs.edit().putInt("creativita", now).apply()
                llm?.creativity = now
                speak(Tuning.creativityReply(old, now, o.step))
            }
        }
    }

    /** Un'altra voce del telefono (avanti o indietro nell'elenco), oppure quella di sempre ([step] 0). */
    private fun changeVoice(step: Int) {
        if (!ttsReady) { speak("La mia voce non è ancora pronta: riprova tra un attimo."); return }
        val list = VoiceSettings.italianVoices(tts)
        val cur = try { tts.voice?.name } catch (e: Exception) { null }
        if (step == 0) {
            VoiceSettings.setVoiceName(this, null, cur)
            VoiceSettings.setRate(this, Tuning.NORMAL)
            VoiceSettings.setPitch(this, Tuning.NORMAL)
            VoiceSettings.apply(this, tts)
            speak("Ok, ho rimesso la mia voce di sempre, con velocità e tono normali.", endSession = true)
            return
        }
        if (list.size < 2) {
            speak("Sul telefono c'è una sola voce italiana che funziona senza internet. Per averne altre: nelle impostazioni del telefono " +
                "cerca Sintesi vocale, scegli il motore di Google e installa i dati vocali in italiano. Poi di' di nuovo: cambia voce.")
            return
        }
        val names = list.map { it.name }
        val target = (if (step < 0) VoiceSettings.previousVoice(this)?.takeIf { it in names && it != cur } else null)
            ?: Tuning.nextVoice(names, cur, step) ?: return
        VoiceSettings.setVoiceName(this, target, cur)
        VoiceSettings.apply(this, tts)
        val v = list.firstOrNull { it.name == target }
        speak(brain.askVoiceLike("Ecco, adesso parlo così: è ${VoiceSettings.describe(v, list)}. Ti piace? Se no, di': un'altra."))
    }

    private fun describeVoice(): String {
        if (!ttsReady) return "La mia voce non è ancora pronta."
        val list = VoiceSettings.italianVoices(tts)
        val v = try { tts.voice } catch (e: Exception) { null }
        val engine = VoiceSettings.engineLabel(tts, VoiceSettings.engine(this))
        return "Uso ${VoiceSettings.describe(v, list)}" + (engine?.let { ", di $it" } ?: "") +
            ", con velocità ${Tuning.rateWords(VoiceSettings.rate(this))} e tono ${Tuning.pitchWords(VoiceSettings.pitch(this))}. " +
            (if (list.size > 1) "Sul telefono ci sono ${list.size} voci italiane che funzionano senza internet: per sentirne un'altra di': cambia voce."
             else "È l'unica voce italiana senza internet che c'è sul telefono.")
    }

    private fun brainName(f: File) = Brains.byFile(f.name)?.name ?: f.nameWithoutExtension

    /** Il cervello: quale usa, passare a un altro (anche scaricandolo col Wi-Fi), installare quello nuovo. */
    // ------------------------------------------------------------------ la cura di sé (2.0)
    @Volatile private var careRunning = false
    @Volatile private var careNote: String? = null

    /** ricordi, mente e conversazione subito sul disco (prima di una copia o di un ripristino) */
    private fun saveNow() {
        try { writeSafely(mindFile, mind.toText()) } catch (e: Throwable) {}
        try { writeSafely(talkFile, talk.toText()) } catch (e: Throwable) {}
    }

    /** Dopo un ripristino: ricordi, mente e conversazione si rileggono dai file. */
    private fun reloadFromDisk() {
        FileMemory.dropCache()
        main.removeCallbacks(saveMind); main.removeCallbacks(saveTalk)
        try { if (mindFile.exists()) mind.load(mindFile.readText(Charsets.UTF_8)) } catch (e: Exception) {}
        try { if (talkFile.exists()) talk.load(talkFile.readText(Charsets.UTF_8)) } catch (e: Exception) {}
        main.removeCallbacks(saveMind); main.removeCallbacks(saveTalk)
        profile = voiceStore.load()
        llm?.let { l -> l.ownerName = mem.ownerName(); l.memory = Remember.forPrompt(mem.memos()) }
    }

    private fun restoreBackup(i: Backups.Info): String {
        val now = System.currentTimeMillis()
        saveNow()
        val b = PhoneCare.backups(this)
        val files = b.restore(i, filesDir, now)
        if (files.isEmpty()) return "Quella copia è rovinata: non ho toccato niente."
        reloadFromDisk()
        PhoneCare.journal(this).did("ripristinata la copia ${b.whenText(i, now)}", now)
        return "Fatto: sono tornato alla copia ${b.whenText(i, now)}. Se ci ripensi di': ripristina la copia di prima."
    }

    /** Le riparazioni che posso fare da solo. Ritorna cosa ho fatto e cosa resta da fare a te. */
    private fun repairNow(): Pair<List<String>, List<String>> {
        val now = System.currentTimeMillis()
        val done = ArrayList<String>(); val left = ArrayList<String>()
        val j = PhoneCare.journal(this); val b = PhoneCare.backups(this)
        for (c in PhoneCare.checks(this, now).filter { it.level != Health.Level.OK }) when (c.id) {
            "ricordi" -> if (c.auto) { saveNow(); b.make(now, Backups.BEFORE); val n = Health.fixMemoryFile(File(filesDir, "ricordi.tsv")); FileMemory.dropCache()
                if (n > 0) done.add("ho tolto $n righe rovinate dai ricordi, dopo averne fatto una copia") } else left.add(c.detail + ". " + (c.fix ?: ""))
            "copie" -> { saveNow(); if (b.make(now, Backups.MANUAL) != null) done.add("ho fatto una copia di sicurezza") else left.add("non sono riuscito a fare la copia di sicurezza") }
            "spazio" -> { val f = PhoneCare.clean(this, now, olderMs = 3_600_000L); if (f > 0) done.add("ho liberato ${Health.size(f)} di file temporanei")
                if (c.level == Health.Level.FAIL) left.add("sul telefono c'è ancora poco spazio: libera qualcosa tu") }
            "cervello" -> if (c.auto && c.level == Health.Level.WARN) {
                PhoneCare.safeMode = false; brainError = null; PhoneCare.brainProblem = null
                Thread { loadBrain() }.start()
                done.add("sto ricaricando il cervello: tra poco è pronto")
            } else if (c.level == Health.Level.FAIL) {} else left.add(c.detail + ". " + (c.fix ?: ""))
            "microfono" -> if (c.level == Health.Level.WARN || micThread?.isAlive != true) { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && voskEngine != null) { restartMic(); done.add("ho riavviato il microfono") } else left.add(c.detail + ". " + (c.fix ?: "")) }
                else left.add(c.detail + ". " + (c.fix ?: ""))
            "registro" -> {}
            else -> left.add(c.detail + ". " + (c.fix ?: ""))
        }
        if (done.isNotEmpty()) j.did("riparazione: " + done.joinToString("; "), now)
        return done to left
    }

    private fun careCmd(o: Outcome.Care) {
        val now = System.currentTimeMillis()
        val j = PhoneCare.journal(this); val b = PhoneCare.backups(this)
        when (o.action) {
            // 2.0.2: il lavoro lasciato a metà
            "doing" -> speak(WorkLog.describe(work.pending(now)), endSession = true)
            "resume" -> {
                val job = work.take(now)
                if (job == null) { speak("Non avevo niente in sospeso. Dimmi cosa vuoi che faccia.", endSession = true); return }
                out.onLine("Info", "Riprendo: «${WorkLog.short(job.text)}»" + (if (job.kind == "write" && job.partial.length > 200) " (da dove ero rimasto)" else ""))
                // (il cervello per programmare può metterci qualche secondo a partire: la parte già scritta vale per due minuti)
                resumePartial = if (job.kind == "write") job.partial else null; resumeAt = now
                process(job.text)
            }
            "check" -> { val c = PhoneCare.checks(this, now); out.onLine("Stato", Health.markdown(c)); speak(Health.spoken(c), endSession = true) }
            "repair" -> {
                val f = SamApp.llmFile(this)
                val known = f?.let { Brains.byFile(it.name) }
                val broken = f != null && Health.modelProblem(f, known?.bytes?.let { it * 9 / 10 } ?: -1L) != null
                val (done, left) = repairNow()
                val text = (if (done.isEmpty() && left.isEmpty() && !broken) "Ho controllato: non c'è niente da riparare, è tutto a posto."
                    else (if (done.isNotEmpty()) "Fatto: " + done.joinToString("; ") + ". " else "") +
                         (if (left.isNotEmpty()) "Resta da fare a te: " + left.joinToString(" ") { it.trim().trimEnd('.') + "." } else "")).trim()
                if (broken && f != null && known != null) speak((text + " " + brain.confirm("Il file del cervello ${known.name} è rovinato: lo cancello e lo scarico di nuovo col Wi-Fi? Sono ${Brains.gb(known.bytes)}.",
                    yes = { val l = llm; llm = null; try { l?.close() } catch (e: Throwable) {}; SamApp.removeBrain(this, f.name); j.did("tolto il cervello rovinato ${known.name}"); Outcome.Update("get:${known.file}") },
                    no = { Outcome.Speak("Va bene, lo lascio com'è.", endSession = true) })).trim())
                else if (broken) speak("$text Il file del cervello è rovinato: toglilo dalla configurazione e importalo di nuovo.".trim(), endSession = true)
                else speak(text, endSession = true)
            }
            "log" -> { out.onLine("Stato", "## Registro\n" + j.markdown(25, now)); speak(j.summary(now), endSession = true) }
            "backup" -> { saveNow(); val i = b.make(now, Backups.MANUAL)
                if (i != null) { j.did("copia di sicurezza chiesta da te", now); speak("Fatto: ho messo al sicuro ricordi, mente e conversazione. È la copia ${b.whenText(i, now)}.", endSession = true) }
                else speak("Non sono riuscito a fare la copia: controlla lo spazio sul telefono.", endSession = true) }
            "backups" -> { out.onLine("Stato", "## Copie di sicurezza\n" + b.markdown(now)); speak(b.spoken(now), endSession = true) }
            "restore" -> {
                val i = b.pick(o.arg, now)
                if (i == null) { speak(if (b.list().isEmpty()) "Non ho ancora nessuna copia da ripristinare." else "Non ho una copia ${o.arg.ifEmpty { "così" }}. " + b.spoken(now), endSession = true); return }
                speak(brain.confirm("Rimetto ricordi e mente com'erano nella copia ${b.whenText(i, now)}? Quello che c'è adesso lo salvo prima in un'altra copia.",
                    yes = { Outcome.Speak(restoreBackup(i), endSession = true) }, no = { Outcome.Speak("Va bene, lascio tutto com'è.", endSession = true) }))
            }
            "clean" -> { val f = PhoneCare.clean(this, now, olderMs = 3_600_000L); if (f > 0) j.did("pulizia: liberati ${Health.size(f)}", now)
                speak((if (f > 0) "Ho fatto pulizia: ho liberato ${Health.size(f)} di file temporanei miei." else "Era già pulito: non avevo file temporanei vecchi.") + " I tuoi file non li tocco.", endSession = true) }
            "space" -> speak(PhoneCare.space(this), endSession = true)
            "version" -> speak("Sono ${Identity.name}, versione ${PhoneCare.version(this)}.", endSession = true)
            "news" -> { out.onLine("Stato", About.newsMarkdown(PhoneCare.version(this))); speak(About.newsSpoken(PhoneCare.version(this)), endSession = true) }
            else -> speak("Questo non lo so fare.", endSession = true)
        }
    }

    private fun brainCmd(action: String) {
        if (action.startsWith("pc_pair:")) {
            prefs.edit().putString("codice_pc", action.removePrefix("pc_pair:")).apply()
            pcSync(); return
        }
        when (action) {
            "pc_sync" -> if (prefs.getString("codice_pc", null) == null)
                speak("Prima dimmi il codice che vedi su SAM per PC, in Impostazioni, sezione Telefono. Per esempio: collega il computer con il codice uno due tre quattro cinque sei.", endSession = true)
                else pcSync()
            "which" -> speak(brainList())
            "openjarvis_on", "openjarvis_off", "openjarvis_status" -> speak("OpenJarvis gira sul computer: lo collego in SAM per PC, " +
                "in Impostazioni, sezione OpenJarvis. Qui sul telefono uso il mio cervello.", endSession = true)
            "install" -> installCmd()
            "learned" -> speak(SelfLearn.describe(mem.memos(), learnOn()))
            "models_auto_on" -> {
                prefs.edit().putBoolean("modelli_auto", true).apply()
                speak("Ok, scelgo io il cervello giusto per ogni lavoro: quello per programmare quando scrivo codice, uno che vede quando " +
                    "guardo, il modello delle immagini quando disegno. Se ne manca uno lo scarico col Wi-Fi.", endSession = true)
            }
            "models_auto_off" -> {
                prefs.edit().putBoolean("modelli_auto", false).apply()
                if (brainOverride != null) { brainOverride = null; Thread { loadBrain() }.start() }
                speak("Ok, uso sempre lo stesso cervello e non scarico niente da solo. Per tornare come prima di': gestisci tu i modelli.", endSession = true)
            }
            "learn_on" -> {
                prefs.edit().putBoolean("autoapprendimento", true).apply()
                speak("Ok, imparo da solo: quando sono libero rileggo le cose che mi dici e mi segno quelle importanti su di te. " +
                    "Per sentirle chiedimi: cosa hai imparato da solo?", endSession = true)
            }
            "learn_off" -> {
                prefs.edit().putBoolean("autoapprendimento", false).apply()
                main.removeCallbacks(reflectLater)
                speak("Ok, non imparo più niente da solo. Ricordo solo quello che mi chiedi tu. Per riaccenderlo di': impara da solo.", endSession = true)
            }
            else -> switchCmd(action)
        }
    }

    // ------------------------------------------------------------------ internet, come Kimi (solo quando lo chiedi)
    /** sta cercando su internet, o scrivendo la risposta / il rapporto con le fonti */
    @Volatile private var webbing = false

    /** Comincia un lavoro su internet: stato, voce e controllo del cervello. null = non si può (e l'ha già detto). */
    private fun webStart(status: String): Llm? {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain("leggere le pagine di internet"), endSession = true); return null }
        webbing = true
        main.removeCallbacks(silenceTimeout)
        state = St.THINKING
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus(status)
        pauseMic()
        return l
    }

    /**
     * "Cerca su internet…": cerca, legge 3-4 pagine e il cervello risponde citando le fonti [1], [2]. Sullo schermo la risposta
     * con i numeri e l'elenco delle fonti (si toccano e si aprono); a voce senza numeri.
     */
    private fun webAnswer(query: String) {
        val l = webStart("Cerco su internet: «$query»…") ?: return
        val turn = ++llmTurn
        out.onLine("SAM", "🔎 Cerco «$query»…")
        talkNow?.let { talk.reply(it, "") }
        Thread {
            val problems = ArrayList<String>()
            val hits = WebFetch.search(query) { problems.add(it) }
            if (turn != llmTurn) return@Thread
            if (hits.isEmpty()) {
                main.post { if (turn == llmTurn) { webbing = false
                    if (problems.isNotEmpty()) out.onLine("Dettaglio", problems.joinToString("; ").take(200))
                    speak("Non riesco a cercare su internet: controlla di essere collegato e riprova.", endSession = true) } }
                return@Thread
            }
            main.post { if (turn == llmTurn) out.onStatus("Leggo ${minOf(4, hits.size)} pagine…") }
            val sources = WebFetch.sources(query, hits, max = 4, perChars = 850, stop = { turn != llmTurn })
            if (turn != llmTurn) return@Thread
            main.post {
                if (turn != llmTurn) return@post
                out.onStatus("Scrivo la risposta…")
                streamAnswer(l, turn, WebSearch.answerPrompt(), WebSearch.answerQuestion(query, sources), 520, intro = null) { text ->
                    val used = WebSearch.cited(text)
                    val shown = sources.filter { used.isEmpty() || it.n in used }
                    out.onLine("Fonti", WebSearch.sourcesText(shown))
                    mind.source("web", shown.take(3).joinToString(", ") { it.title.take(60) }, query, System.currentTimeMillis())
                    talk.reply(talkNow, WebSearch.forSpeech(text))
                }
            }
        }.start()
    }

    /**
     * Una skill toccata nella schermata (1.17, come in Spinta). Le ACTION diventano il comando che SAM sa già fare;
     * le TEXT sono un lavoro del cervello con istruzioni apposta (tradurre, correggere, riassumere…), mostrato mentre arriva.
     */
    private fun runSkill(r: Skills.Request, auto: Boolean = false) {
        if (r.text.isBlank()) { out.onLine("Info", "Scrivi prima il testo per «${r.skill.name}»."); return }
        lastRequest = ""
        when (r.skill.kind) {
            Skills.Kind.ACTION -> { onTyped(Skills.command(r)); return }
            Skills.Kind.FILE -> return
            Skills.Kind.TEXT -> {}
        }
        if (enrolling) { out.onLine("Info", "Sto imparando la tua voce: finisci prima quello."); return }
        main.removeCallbacks(expirePending); main.removeCallbacks(silenceTimeout)
        if (!auto) quiet = true          // dal pannello: solo sullo schermo; a voce risponde a voce
        if (dialogue) endDialogue(null)
        if (!auto && (state == St.SPEAKING || state == St.THINKING)) interruptQuietly()
        if (!auto) stopCollecting()
        out.onLine("Tu", Skills.shown(r))
        if (auto) out.onLine("Azione", "uso la skill «${r.skill.name}»")
        val talkTurn = talk.start(r.text.take(300))
        talkNow = talkTurn
        withBrainFor(if (r.skill.code) ModelRouter.Task.CODE else ModelRouter.Task.CHAT) {
            val l = webStart("${r.skill.emoji} ${r.skill.name}…") ?: return@withBrainFor
            val turn = ++llmTurn
            streamAnswer(l, turn, r.skill.system, Skills.prompt(r), r.skill.maxTokens, intro = null) { text ->
                talk.reply(talkTurn, text.take(600))
                prevLlmAnswer = text; prevLlmQuestion = r.text
            }
        }
    }

    /**
     * Una risposta del cervello con istruzioni sue, mostrata mentre arriva (con le citazioni e la formattazione) e detta a voce
     * frase per frase (senza). [done] riceve il testo finale.
     */
    private fun streamAnswer(l: Llm, turn: Int, system: String, prompt: String, maxTokens: Int, intro: String?, done: (String) -> Unit) {
        val sb = StringBuilder()
        var spoken = 0
        intro?.let { out.onLine("SAM", it); speakRaw(it) }
        out.onLine("SAM", "…")
        fun clean(raw: String) = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "").trim()
        Thread {
            try {
                l.askOnce(Llm.withRules(system, Remember.rulesForPrompt(mem.memos()), mem.ownerName()), "[]", prompt, null, maxTokens,
                    onChunk = { c -> main.post {
                        if (turn != llmTurn || !webbing) return@post
                        sb.append(c)
                        out.onReplace("SAM", clean(sb.toString()).ifEmpty { "…" })
                        val ready = Reply.sentences(WebSearch.forSpeech(clean(sb.toString())), final = false, scrub0 = false)
                        while (spoken < ready.size) speakRaw(ready[spoken++])
                    } },
                    onDone = { main.post {
                        if (turn != llmTurn || !webbing) return@post
                        webbing = false
                        val text = clean(sb.toString())
                        out.onReplace("SAM", text.ifEmpty { "Non ho trovato abbastanza per rispondere." })
                        val all = Reply.sentences(WebSearch.forSpeech(text), final = true, scrub0 = false)
                        while (spoken < all.size) speakRaw(all[spoken++])
                        if (text.isEmpty()) speakRaw("Non ho trovato abbastanza per rispondere.")
                        done(text)
                        if (utterances.isEmpty()) afterSpeaking()
                    } },
                    onError = { e -> main.post {
                        if (turn != llmTurn) return@post
                        webbing = false
                        out.onLine("Dettaglio", e.take(200))
                        speak("Ho avuto un intoppo con il mio cervello mentre leggevo le pagine: riprova.", endSession = true)
                    } })
            } catch (t: Throwable) {
                main.post { if (turn == llmTurn) { webbing = false; speak("Non sono riuscito a leggere le pagine.", endSession = true) } }
            }
        }.start()
    }

    /**
     * "Fai una ricerca approfondita su…" (come Kimi Researcher): il cervello si fa 3-4 domande, SAM cerca e legge fino a 10
     * pagine, si appunta i fatti con la fonte, poi scrive un rapporto con le fonti. Il rapporto si vede sullo schermo e si salva
     * in PDF (Download › SAM). Ci mette qualche minuto; per fermarlo tocca Parla.
     */
    private fun research(topic: String) {
        val l = webStart("Ricerca approfondita: preparo le domande…") ?: return
        val turn = ++llmTurn
        val intro = "Ok, faccio una ricerca approfondita: ci metto qualche minuto. Alla fine ti preparo un rapporto in PDF con le fonti."
        out.onLine("SAM", intro)
        speakRaw(intro)
        val t0 = System.currentTimeMillis()
        Thread {
            fun alive() = turn == llmTurn && webbing
            fun status(s: String) = main.post { if (alive()) out.onStatus(s) }
            // 1) le domande
            val queries = Research.parseQueries(try { l.complete(Research.queriesPrompt(), "[]", "Argomento: $topic", 120, 90_000) } catch (e: Throwable) { null }, topic)
            if (!alive()) return@Thread
            main.post { if (alive()) out.onLine("Ricerca", queries.joinToString("\n") { "🔎 $it" }) }
            // 2) cerca e legge
            val sources = ArrayList<WebSearch.Source>()
            val seen = HashSet<String>()
            for ((i, q) in queries.withIndex()) {
                if (!alive() || sources.size >= Research.MAX_SOURCES) break
                status("Ricerca ${i + 1} di ${queries.size}: «$q»…")
                val hits = WebFetch.search(q)
                val got = WebFetch.sources(topic, hits, Research.PAGES_PER_QUERY, 1400, firstN = sources.size + 1, skipUrls = seen, stop = { !alive() })
                for (s in got) { if (sources.size < Research.MAX_SOURCES && seen.add(s.url)) sources.add(WebSearch.Source(sources.size + 1, s.title, s.url, s.text)) }
            }
            if (!alive()) return@Thread
            if (sources.isEmpty()) {
                main.post { if (alive()) { webbing = false; speak("Non riesco a cercare su internet: controlla di essere collegato e riprova.", endSession = true) } }
                return@Thread
            }
            // 3) gli appunti, pagina per pagina
            val notes = ArrayList<Pair<WebSearch.Source, List<String>>>()
            for ((i, s) in sources.withIndex()) {
                if (!alive()) break
                status("Leggo la fonte ${i + 1} di ${sources.size}: ${s.host}…")
                val raw = try { l.complete(Research.notesPrompt(), "[]", Research.notesQuestion(topic, s), 220, 120_000) } catch (e: Throwable) { null }
                val ns = Research.parseNotes(raw)
                if (ns.isNotEmpty()) notes.add(s to ns)
            }
            if (!alive()) return@Thread
            if (notes.isEmpty()) {
                main.post { if (alive()) { webbing = false; speak("Ho letto ${sources.size} pagine ma non ho trovato niente di utile su questo argomento.", endSession = true) } }
                return@Thread
            }
            val used = notes.map { it.first }
            main.post { if (alive()) out.onLine("Fonti", WebSearch.sourcesText(used)) }
            // 4) il rapporto (lo vedi crescere sullo schermo)
            status("Scrivo il rapporto (${used.size} fonti)… tocca Parla per fermarmi")
            val sb = StringBuilder()
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post { out.onLine("SAM", "📝 Scrivo il rapporto…") }
            try {
                l.askOnce(Research.reportPrompt(), "[]", Research.reportQuestion(topic, notes), null, 1400,
                    onChunk = { c -> sb.append(c); main.post { if (alive()) out.onReplace("SAM", sb.toString().replace(Regex("(?s)<think>.*?</think>"), "")) } },
                    onDone = { latch.countDown() }, onError = { latch.countDown() })
            } catch (e: Throwable) { latch.countDown() }
            latch.await(15, java.util.concurrent.TimeUnit.MINUTES)
            if (!alive()) return@Thread
            val report = SamFiles.clean(sb.toString())
            val title = SamFiles.titleOf(report, "Ricerca su $topic")
            val saved = if (Remember.words(report).size >= 20) try { FileOut.save(this, title, Research.withSources(report, used), pdf = true) } catch (e: Throwable) { null } else null
            val mins = (System.currentTimeMillis() - t0 + 30_000) / 60_000
            main.post {
                if (!alive()) return@post
                webbing = false
                if (saved != null) {
                    FileOut.remember(this, saved); FileOut.notifySaved(this, saved)
                    out.onLine("File", "📥 ${saved.name} · in Download › ${FileOut.FOLDER}")
                }
                // a voce: la sintesi (le prime frasi dopo "Sintesi"), non tutto il rapporto
                val sintesi = report.substringAfter("Sintesi", report).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                val say = Reply.sentences(WebSearch.forSpeech(sintesi), final = true, scrub0 = false).take(3)
                talk.reply(talkNow, "Ho fatto una ricerca approfondita su $topic.")
                for (x in say) speakRaw(x)
                speak(if (saved != null) "Ho finito in circa $mins minuti, con ${used.size} fonti. Il rapporto completo è nel PDF in Download, cartella SAM: " +
                    "di' apri il file per leggerlo." else "Ho finito, ma il rapporto è venuto troppo corto per salvarlo: riprova con un argomento più preciso.",
                    endSession = true)
            }
        }.start()
    }

    // ------------------------------------------------------------------ disegnare (Stable Diffusion sul telefono)
    @Volatile private var drawing = false
    /** i modelli per disegnare (1-2 GB di memoria) si liberano qualche minuto dopo l'ultimo disegno */
    private val releasePainter = Runnable { if (!drawing) Thread { Painter.release() }.start() }

    private fun drawCmd(o: Outcome.Draw) {
        when (o.action) {
            "download" -> speak(Painter.startDownload(this), endSession = true)
            "can" -> speak(if (Painter.ready(this)) "Sì! Dimmi cosa disegnare, per esempio: disegna un gatto astronauta sulla luna. Ci metto qualche minuto."
                else "Sì, ma prima devo scaricare il modello per disegnare: circa un giga, una volta sola, col Wi-Fi. Di': scarica il modello per disegnare.",
                endSession = true)
            "canvideo" -> speak("Sì, in tre modi. Filmo con la fotocamera: di' «registra un video di 20 secondi». Monto le tue foto in un video, " +
                "con movimento e dissolvenze: «fammi un video con le foto di sabato». Oppure disegno una scena e la animo: «fammi un video di un faro " +
                "sul mare in tempesta». I video li vedi qui nella chat e nella Galleria, cartella SAM.",
                endSession = true)
            "video" -> draw(o.prompt ?: "", o.steps, video = true)
            else -> draw(o.prompt ?: "", o.steps)
        }
    }

    /**
     * Disegna [what] con Stable Diffusion: la descrizione la traduce in inglese il cervello (il modello delle immagini capisce
     * solo l'inglese), poi [steps] passi sul processore (qualche minuto), poi l'immagine va nella Galleria e si apre.
     */
    private fun draw(what: String, steps: Int, video: Boolean = false) {
        if (!Painter.ready(this)) {
            if (Painter.downloading(this)) { speak(Painter.progress(this) ?: "Sto ancora scaricando il modello per disegnare.", endSession = true); return }
            // gestione automatica dei modelli: si scarica da solo (col Wi-Fi), senza chiedere
            if (autoModels()) { speak("Per disegnare mi serve il modello delle immagini. " + Painter.startDownload(this), endSession = true); return }
            speak(brain.confirm("Per disegnare mi serve il modello delle immagini: circa un giga, da scaricare una volta sola col Wi-Fi. Lo scarico?",
                yes = { Outcome.Draw(null, "download") },
                no = { Outcome.Speak("Va bene. Quando vuoi di': scarica il modello per disegnare.", endSession = true) }))
            return
        }
        val turn = ++llmTurn
        drawing = true
        main.removeCallbacks(releasePainter)
        main.removeCallbacks(silenceTimeout)
        state = St.THINKING
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Preparo il disegno…")
        val intro = if (video) "Ok, faccio il video: prima disegno la scena, poi la animo. Ci metto qualche minuto; intanto puoi usare il telefono, per fermarmi tocca Parla."
            else "Ok, disegno: ci metto qualche minuto. Intanto puoi usare il telefono; per fermarmi tocca Parla."
        out.onLine("SAM", intro)
        speakRaw(intro)
        val l = llm
        val threads = try { Brains.threadsFor(Hardware.specs(this)) } catch (e: Throwable) { 4 }
        Thread {
            fun alive() = drawing && turn == llmTurn
            // 1) la descrizione in inglese
            var prompt = what
            if (l != null && l.ready) {
                val raw = try { l.complete(DrawCommands.translatePrompt(), "[]", what, 90, 60_000) } catch (e: Throwable) { null }
                DrawCommands.cleanTranslation(raw)?.let { prompt = it }
            }
            if (!alive()) return@Thread
            main.post { out.onLine("Disegno", "«$prompt»") }
            // 2) poca memoria libera: il cervello si mette da parte mentre disegna, e poi si ricarica
            var freed = false
            try {
                val am = getSystemService(android.app.ActivityManager::class.java)
                val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                if (mi.availMem < 3_500_000_000L && llm != null) { val old = llm; llm = null; try { old?.close() } catch (t: Throwable) {}; freed = true }
            } catch (e: Throwable) {}
            main.post { if (alive()) out.onStatus("Carico il modello per disegnare…") }
            val t0 = System.currentTimeMillis()
            var error: String? = null
            val rgb = try {
                val sd = Painter.load(this, nativeLib("litert-lm"), threads)
                val tokens = Painter.tokenizer()!!.tokens77(prompt)
                val g0 = System.currentTimeMillis()
                sd.generate(tokens, steps, System.nanoTime(), onStep = { done, total ->
                    val el = System.currentTimeMillis() - g0
                    val left = if (done > 0) el * (total - done) / done else -1L
                    main.post { if (alive()) out.onStatus("Disegno… passo $done di $total" +
                        (if (left >= 0) " · manca ${Diffusion.eta(left)}" else "") + " · tocca Parla per fermarmi") }
                    alive()
                })
            } catch (t: Throwable) { error = t.message ?: "errore"; null }
            val secs = (System.currentTimeMillis() - t0) / 1000
            val saved = if (rgb != null) try { Painter.savePng(this, rgb, SamFiles.firstWords(what, 6)) } catch (t: Throwable) { null } else null
            // il video: l'immagine animata (zoom e panoramica) in MP4, col codificatore del telefono
            val movie = if (video && rgb != null && alive()) try {
                val style = Motion.styleFor(prompt)
                main.post { if (alive()) out.onStatus("Animo il disegno (${style.label})…") }
                VideoOut.make(this, rgb, 512, SamFiles.firstWords(what, 6), style) { done, total ->
                    if (done % 30 == 0) main.post { if (alive()) out.onStatus("Animo il disegno… ${done * 100 / total}%") }
                    alive()
                }
            } catch (t: Throwable) { null } else null
            main.post {
                val wasMine = turn == llmTurn
                if (wasMine) drawing = false                    // (se nel frattempo è partito un altro disegno, resta suo)
                main.postDelayed(releasePainter, 5 * 60_000L)
                if (!wasMine) return@post                       // l'hai fermato: niente da dire
                when {
                    movie != null -> {
                        FileOut.remember(this, movie)
                        FileOut.notifySaved(this, movie, "Video › SAM")
                        out.onLine("Media", MediaCommands.line(listOf(Gallery.item(movie, Motion.SECONDS * 1000L))))
                        out.onLine("File", "🎬 ${movie.name} · in Galleria, cartella SAM (${secs / 60} min ${secs % 60} s)")
                        if (ui != null) FileOut.open(this, movie)
                        talk.reply(talkNow, "Ho fatto il video «$what».")
                        speak("Ecco il video! ${Motion.SECONDS} secondi, nella Galleria, cartella SAM; c'è anche l'immagine da sola. Se vuoi mandarlo a qualcuno di': condividi il file.", endSession = true)
                    }
                    saved != null -> {
                        if (video) out.onLine("Info", "Il video non è riuscito (il codificatore del telefono ha dato errore): ti lascio l'immagine.")
                        FileOut.remember(this, saved)
                        FileOut.notifySaved(this, saved, "Immagini › SAM")
                        out.onLine("Media", MediaCommands.line(listOf(Gallery.item(saved))))
                        out.onLine("File", "🖼️ ${saved.name} · in Galleria, cartella SAM (${secs / 60} min ${secs % 60} s)")
                        if (ui != null) FileOut.open(this, saved)
                        talk.reply(talkNow, "Ho disegnato «$what».")
                        speak("Ecco il disegno! L'ho messo nella Galleria, nella cartella SAM. Se vuoi mandarlo a qualcuno di': condividi il file.", endSession = true)
                    }
                    rgb != null -> speak("Ho finito il disegno ma non riesco a salvarlo: forse la memoria del telefono è piena.", endSession = true)
                    error != null -> { out.onLine("Dettaglio", error!!.take(200)); speak("Non sono riuscito a disegnare: il modello per le immagini ha dato errore.", endSession = true) }
                    else -> speak("Ok, mi fermo.", endSession = true)
                }
            }
            if (freed) loadBrain()
        }.start()
    }

    // ------------------------------------------------------------------ 1.22: il nome dell'assistente
    /** fino a quando si aspetta che tu dica il nome nuovo (per impararlo) */
    private var learnNameUntil = 0L

    private fun rename(o: Outcome.Rename) {
        when (o.action) {
            "learn" -> {
                learnNameUntil = System.currentTimeMillis() + 20_000
                sessionActive = true
                speak("Ok: dimmi solo il mio nome, ${Identity.name}, come lo diresti per chiamarmi.")
            }
            else -> {
                val old = Identity.name
                IdentityStore.save(this, o.name, emptyList())
                updateNotification()
                talk.reply(talkNow, "Da ora mi chiamo ${Identity.name}.")
                if (Identity.isDefault) { speak("Va bene, torno a chiamarmi SAM. Per chiamarmi di' «SAM».", endSession = true); return }
                learnNameUntil = System.currentTimeMillis() + 20_000
                sessionActive = true
                speak("Va bene, da ora mi chiamo ${Identity.name}${if (old != Identity.DEFAULT && old != Identity.name) " invece di $old" else ""}! " +
                    "Per chiamarmi di' «${Identity.name}». Adesso dimmi solo il mio nome, così imparo come lo sento.")
            }
        }
    }

    /** Quello che il riconoscitore ha scritto quando hai detto il nome: d'ora in poi vale come il nome. */
    private fun learnName(text: String) {
        learnNameUntil = 0L
        val words = Identity.norm(text).split(' ').filter { it.isNotBlank() && it !in setOf("ehi", "hey", "ok", "ciao") }
        if (words.isEmpty() || words.size > 3) { speak("Non ho capito bene: quando vuoi di' «impara come dico il tuo nome» e riprova.", endSession = true); return }
        val form = words.joinToString(" ")
        val already = Identity.strip(form) != null
        if (!already) IdentityStore.addHeard(this, form)
        out.onLine("Memoria", "il nome «${Identity.name}» lo sento così: «$form»")
        speak(if (already) "Perfetto, ti sento benissimo. Chiamami «${Identity.name}» quando vuoi." else
            "Ok! Il riconoscitore scrive «$form»: me lo segno, così quando lo dici mi sveglio. Chiamami quando vuoi.", endSession = true)
    }

    private fun updateNotification() { try { startForegroundSafe() } catch (e: Exception) {} }

    // ------------------------------------------------------------------ 1.21: foto e video
    /** la fotocamera di SAM sta scattando o filmando (intanto SAM non ascolta: finirebbe nel video) */
    @Volatile private var capturing = false
    private var captureTurn = 0
    /** la richiesta che aspetta il permesso di vedere le foto */
    private var pendingMedia: Outcome.Media? = null

    private fun media(o: Outcome.Media) {
        when (o.action) {
            "photo", "record" -> capture(o)
            "screenrec" -> speak("Lo schermo del telefono lo registra il Registratore schermo di Xiaomi: lo trovi nella tendina in alto " +
                "(tira giù due volte) o nell'app Strumenti. Quando hai finito di': mostrami l'ultimo video.", endSession = true)
            else -> {
                // le foto di SAM (i disegni, le foto che ha scattato) si vedono sempre; le altre col permesso
                if (!o.mine && !Gallery.allowed(this)) { askMediaPermission(o); return }
                when (o.action) { "show" -> showMedia(o); "describe" -> describeMedia(o); "reel" -> reel(o) }
            }
        }
    }

    private fun askMediaPermission(o: Outcome.Media) {
        pendingMedia = o
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ASK_MEDIA, true)
        val ok = SamAccessibilityService.launch(i) || try { startActivity(i); true } catch (e: Exception) { false }
        speak(if (ok) "Per vedere le tue foto e i tuoi video mi serve il permesso: te lo chiedo sullo schermo. Tocca Consenti."
              else "Per vedere le tue foto mi serve il permesso: apri SAM e riprova, te lo chiedo sullo schermo.", endSession = !ok)
    }

    private fun onMediaAllowed(ok: Boolean) {
        val o = pendingMedia ?: return
        pendingMedia = null
        if (ok) media(o) else speak("Va bene, senza permesso non guardo le tue foto. Se cambi idea, chiedimelo di nuovo.", endSession = true)
    }

    /** "Mostrami le foto di ieri": nella chat, una grande o a griglia; toccandole si aprono. */
    private fun showMedia(o: Outcome.Media) {
        out.onStatus("Cerco nella Galleria…")
        Thread {
            val limit = if (o.count > 0) o.count.coerceAtMost(30) else 24
            val (items, total) = try { Gallery.find(this, o.kind, o.from, o.to, o.mine, limit) } catch (t: Throwable) { emptyList<MediaCommands.Item>() to 0 }
            main.post {
                if (items.isEmpty()) {
                    val n = MediaCommands.noun(o.kind, o.mine, 2)
                    speak(when {
                        o.mine -> "Non ho ancora fatto $n: chiedimi per esempio «disegna un gatto» o «scatta una foto»."
                        o.period.isNotEmpty() -> "Non trovo $n ${o.period}."
                        else -> "Non trovo $n sul telefono."
                    } + (if (Gallery.partial(this)) " Mi hai dato il permesso solo per alcune foto: per vederle tutte cambialo da Impostazioni › App › SAM › Autorizzazioni." else ""),
                        endSession = true)
                    return@post
                }
                out.onLine("Media", MediaCommands.line(items))
                if (o.open && items.size == 1) (actions as? AndroidActions)?.viewMedia(items[0].ref, items[0].mime)
                val t = MediaCommands.shownText(o, items, total)
                talk.reply(talkNow, t)
                speak(t, endSession = true)
            }
        }.start()
    }

    /** "Cosa c'è nell'ultima foto?": la si mostra e la guarda il cervello che vede. */
    private fun describeMedia(o: Outcome.Media) {
        Thread {
            val (items, _) = try { Gallery.find(this, if (o.kind == "any") "photo" else o.kind, o.from, o.to, o.mine, 1) } catch (t: Throwable) { emptyList<MediaCommands.Item>() to 0 }
            val it = items.firstOrNull()
            val img = File(cacheDir, "galleria.jpg")
            val raw = File(cacheDir, "galleria_orig")
            val ok = it != null && !it.isVideo && Gallery.toFile(this, android.net.Uri.parse(it.ref), raw) && Images.prepare(raw, img)
            raw.delete()
            main.post {
                when {
                    it == null -> speak("Non trovo ${MediaCommands.noun(o.kind, o.mine, 2)}${if (o.period.isNotEmpty()) " " + o.period else " sul telefono"}.", endSession = true)
                    it.isVideo -> speak("I video per ora non li so guardare: solo le foto.", endSession = true)
                    !ok -> speak("Non riesco ad aprire quella foto.", endSession = true)
                    else -> {
                        out.onLine("Media", MediaCommands.line(listOf(it)))
                        withBrainFor(ModelRouter.Task.VISION) { lookAt(img.absolutePath, Llm.visionQuestion(null)) }
                    }
                }
            }
        }.start()
    }

    /** "Scatta una foto", "fammi un selfie tra 3 secondi", "registra un video di 20 secondi": la fotocamera di SAM. */
    private fun capture(o: Outcome.Media) {
        val video = o.action == "record"
        val a = actions as? AndroidActions
        if (a == null) { speak("Non riesco ad aprire la fotocamera.", endSession = true); return }
        if (video) {
            // filmando SAM non ascolta e non parla (la sua voce finirebbe nel video)
            capturing = true; pauseMic()
            out.onLine("SAM", "🎥 Filmo per ${o.seconds} secondi${if (o.front) " con la fotocamera davanti" else ""}: tocca lo schermo per fermare prima.")
        } else {
            val hi = if (o.seconds > 0) "Pronti? Foto tra ${o.seconds} secondi." else if (o.front) "Sorridi!" else "Scatto."
            out.onLine("SAM", "📸 $hi"); speakRaw(hi)
            capturing = true
        }
        val turn = ++captureTurn
        if (!a.capture(if (video) "video" else "photo", o.front, if (video) 0 else o.seconds, o.seconds.coerceAtLeast(2))) {
            capturing = false; resumeMic(); speak("Non riesco ad aprire la fotocamera.", endSession = true); return
        }
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus(if (video) "Filmo…" else "Scatto…")
        main.postDelayed({
            if (capturing && turn == captureTurn) { capturing = false; resumeMic(); speak("La fotocamera non ha risposto.", endSession = true) }
        }, (o.seconds + 40) * 1000L)
    }

    private fun onMediaTaken(uri: String?, mime: String?, name: String?, durationMs: Long, error: String?) {
        if (!capturing) return
        capturing = false; captureTurn++
        resumeMic()
        if (uri == null) { speak(error ?: "Non ci sono riuscito.", endSession = true); return }
        val saved = FileOut.Saved(android.net.Uri.parse(uri), name ?: "foto", mime ?: "image/jpeg")
        FileOut.remember(this, saved)
        out.onLine("Media", MediaCommands.line(listOf(Gallery.item(saved, durationMs))))
        val video = saved.mime.startsWith("video")
        val t = if (video) "Fatto! Il video${if (durationMs > 0) " dura ${MediaCommands.duration(durationMs)} ed" else ""} è nella Galleria, cartella SAM. Toccalo per guardarlo."
                else "Fatto! La foto è nella Galleria, cartella SAM. Se vuoi che ti dica cosa c'è, chiedimi: cosa c'è nell'ultima foto?"
        talk.reply(talkNow, if (video) "Ho filmato un video." else "Ho scattato una foto.")
        speak(t, endSession = true)
    }

    /** "Fammi un video con le foto di sabato": le foto in fila, con movimento e dissolvenze, in un MP4 (Video › SAM). */
    private fun reel(o: Outcome.Media) {
        if (drawing) { speak("Sto ancora lavorando a un disegno o a un video: aspetta che finisca, o tocca Parla per fermarlo.", endSession = true); return }
        val turn = ++llmTurn
        drawing = true
        main.removeCallbacks(silenceTimeout)
        state = St.THINKING
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Cerco le foto…")
        Thread {
            fun alive() = drawing && turn == llmTurn
            val limit = if (o.count > 0) o.count.coerceAtMost(Reel.MAX_PHOTOS) else if (o.from > 0) Reel.MAX_PHOTOS else 12
            val (found, total) = try { Gallery.find(this, if (o.kind == "screenshot") "screenshot" else "photo", o.from, o.to, o.mine, limit) }
                catch (t: Throwable) { emptyList<MediaCommands.Item>() to 0 }
            val items = found.filter { !it.isVideo }.reversed()          // dalla più vecchia: come un racconto
            if (items.isEmpty()) {
                main.post { if (turn == llmTurn) drawing = false
                    speak("Non trovo foto${if (o.period.isNotEmpty()) " " + o.period else ""} per fare il video.", endSession = true) }
                return@Thread
            }
            val plan = Reel.plan(items.size, Reel.portrait(items.map { it.width to it.height }))
            val intro = if (items.size == 1) "Ok, animo la foto: un video di ${plan.seconds} secondi." else
                "Ok, faccio un video con ${items.size} foto${if (o.period.isNotEmpty()) " " + o.period else ""}" +
                    (if (total > items.size) " (le ultime ${items.size} di $total)" else "") + ": circa ${plan.seconds} secondi. Ci metto un minuto o due."
            main.post { out.onLine("SAM", intro); speakRaw(intro) }
            val t0 = System.currentTimeMillis()
            val cache = HashMap<Int, Reel.Pic>()
            val black = Reel.Pic(ByteArray(plan.srcW * plan.srcH * 3), plan.srcW, plan.srcH)
            val pic: (Int) -> Reel.Pic = { k ->
                cache.getOrPut(k) {
                    cache.keys.filter { it < k - 1 }.forEach { cache.remove(it) }
                    Gallery.loadPic(this, android.net.Uri.parse(items[k].ref), plan.srcW, plan.srcH) ?: black
                }
            }
            val title = "Ricordi " + (o.period.removePrefix("di ").removePrefix("del ").removePrefix("della ").ifEmpty { "foto" })
            val movie = try {
                VideoOut.makeReel(this, plan, title, pic) { done, all ->
                    if (done % 30 == 0) main.post { if (alive()) out.onStatus("Monto il video… ${done * 100 / all}% · tocca Parla per fermarmi") }
                    alive()
                }
            } catch (t: Throwable) { null }
            val secs = (System.currentTimeMillis() - t0) / 1000
            main.post {
                val wasMine = turn == llmTurn
                if (wasMine) drawing = false
                if (!wasMine) return@post
                if (movie == null) { speak("Il video non è riuscito: il codificatore del telefono ha dato errore.", endSession = true); return@post }
                FileOut.remember(this, movie)
                FileOut.notifySaved(this, movie, "Video › SAM")
                out.onLine("Media", MediaCommands.line(listOf(Gallery.item(movie, plan.total * 1000L / Reel.FPS))))
                out.onLine("File", "🎬 ${movie.name} · in Galleria, cartella SAM (fatto in ${secs / 60} min ${secs % 60} s)")
                talk.reply(talkNow, "Ho fatto un video con ${items.size} foto.")
                speak("Ecco il video! ${plan.seconds} secondi, nella Galleria, cartella SAM. Se vuoi mandarlo a qualcuno di': condividi il file.", endSession = true)
            }
        }.start()
    }

    // ------------------------------------------------------------------ la memoria condivisa con SAM per PC (1.18)
    /** Cerca SAM per PC sulla rete di casa e si scambiano i ricordi (quelli nuovi si aggiungono, niente si cancella). */
    private fun pcSync() {
        val code = prefs.getString("codice_pc", null) ?: return
        out.onLine("SAM", "🔄 Cerco il computer sulla rete…")
        out.onStatus("Cerco SAM per PC sulla rete di casa…")
        Thread {
            var msg: String
            try {
                var host: java.net.InetAddress? = null
                var port = 47812
                java.net.DatagramSocket().use { u ->
                    u.broadcast = true; u.soTimeout = 1500
                    val q = "SAM-PC?".toByteArray()
                    repeat(3) {
                        if (host != null) return@repeat
                        try {
                            u.send(java.net.DatagramPacket(q, q.size, java.net.InetAddress.getByName("255.255.255.255"), 47811))
                            val buf = ByteArray(256); val p = java.net.DatagramPacket(buf, buf.size); u.receive(p)
                            val r = String(p.data, 0, p.length)
                            if (r.startsWith("SAM-PC")) { host = p.address; port = r.split(' ').getOrNull(1)?.toIntOrNull() ?: 47812 }
                        } catch (e: java.net.SocketTimeoutException) {}
                    }
                }
                val h = host ?: throw IllegalStateException("nessun PC")
                val base = "http://${h.hostAddress}:$port/sam/ricordi?code=$code"
                val get = java.net.URL(base).openConnection() as java.net.HttpURLConnection
                get.connectTimeout = 5000; get.readTimeout = 15000
                if (get.responseCode == 403) throw SecurityException("codice")
                val theirs = get.inputStream.readBytes().toString(Charsets.UTF_8)
                val fm = mem as? FileMemory
                val got = fm?.importText(theirs) ?: 0
                val post = java.net.URL(base).openConnection() as java.net.HttpURLConnection
                post.requestMethod = "POST"; post.doOutput = true; post.connectTimeout = 5000; post.readTimeout = 15000
                post.outputStream.use { it.write((fm?.exportText() ?: "").toByteArray(Charsets.UTF_8)) }
                val sent = post.inputStream.readBytes().toString(Charsets.UTF_8).trim().toIntOrNull() ?: 0
                if (got > 0) memoryEdited = true
                msg = "Fatto: memoria sincronizzata col computer. " + (if (got > 0) "Dal computer ho imparato $got cose nuove" else "Dal computer niente di nuovo") +
                    (if (sent > 0) ", e lui ne ha imparate $sent da me." else ".")
            } catch (e: SecurityException) {
                msg = "Il computer non accetta il codice: controlla quello in SAM per PC, Impostazioni, sezione Telefono, e ridimmelo."
            } catch (e: Exception) {
                msg = "Non trovo SAM per PC sulla rete: controlla che il computer sia acceso con SAM aperto, e che telefono e computer siano sullo stesso Wi-Fi."
            }
            main.post { speak(msg, endSession = true) }
        }.start()
    }

    // ------------------------------------------------------------------ file per te (Download › SAM)
    /** sta scrivendo un documento da salvare ("scrivimi un documento su…") */
    @Volatile private var writing = false

    private fun fileCmd(o: Outcome.SaveFile) {
        when (o.what) {
            "open", "share" -> {
                val f = FileOut.last(this) ?: run {
                    speak("Non ho ancora salvato nessun file. Dimmi per esempio: salvalo in un file, oppure: scrivimi un documento sulla pesca.", endSession = true)
                    return
                }
                val ok = if (o.what == "open") FileOut.open(this, f) else FileOut.share(this, f)
                speak(when {
                    ok && o.what == "open" -> "Ecco «${f.name}»."
                    ok -> "Scegli dove mandare «${f.name}»."
                    else -> "Android adesso non mi lascia aprirlo: apri SAM sullo schermo e riprova, oppure lo trovi nell'app File, in Download, cartella SAM."
                }, endSession = true)
            }
            "answer" -> {
                val t = talk.lastSaid(talkNow)?.takeIf { it.isNotBlank() }
                    ?: run { speak("Non ho ancora detto niente da salvare.", endSession = true); return }
                saveAndTell(SamFiles.titleOf("", SamFiles.firstWords(t)), t, o.pdf)
            }
            "chat" -> {
                val lines = talk.before(talkNow, within = Talk.RECALL_MS)
                if (lines.isEmpty()) { speak("Non abbiamo ancora parlato di niente da salvare.", endSession = true); return }
                val day = java.time.LocalDate.now()
                saveAndTell("Conversazione con SAM del ${day.dayOfMonth}-${day.monthValue}-${day.year}", SamFiles.chatText(lines, mem.ownerName()), o.pdf)
            }
            "doc" -> {
                val d = doc ?: run { speak("Non ho nessun documento aperto.", endSession = true); return }
                if (d.text.isBlank()) { speak("Di «${d.name}» non ho il testo: è una scansione, fatta di immagini.", endSession = true); return }
                saveAndTell(d.name, d.text, o.pdf)
            }
            "write" -> writeFile(o.topic ?: "", o.pdf)
            "slides" -> withBrainFor(ModelRouter.Task.CHAT) { makeSlides(o.topic ?: "", if (o.count > 0) o.count else Slides.DEFAULT_COUNT, o.web) }
            "code" -> withBrainFor(ModelRouter.Task.CODE) { writeFile(o.topic ?: "", false, SamFiles.codeLang(o.topic ?: "")) }
        }
    }

    /**
     * Modalità agente: "fammi una presentazione su…". Il piano si vede sullo schermo; (se chiesto) prima cerca su internet, poi
     * il cervello scrive la scaletta (la vedi crescere), poi SAM fa le slide in PowerPoint (.pptx, modificabile) e in PDF.
     */
    private fun makeSlides(topic: String, count: Int, web: Boolean) {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain("preparare le slide"), endSession = true); return }
        val turn = ++llmTurn
        writing = true
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onLine("Piano", (if (web) "1. Cerco su internet «$topic» e leggo le pagine\n" else "") +
            "${if (web) 2 else 1}. Scrivo la scaletta di $count slide\n${if (web) 3 else 2}. Faccio le slide in PowerPoint e in PDF\n" +
            "${if (web) 4 else 3}. Le salvo in Download › SAM")
        val intro = "Ok, preparo una presentazione di $count slide su $topic. Ci metto un paio di minuti."
        out.onLine("SAM", intro); speakRaw(intro)
        Thread {
            fun alive() = turn == llmTurn && writing
            var sourcesBlock: String? = null
            var sources: List<WebSearch.Source> = emptyList()
            if (web) {
                main.post { if (alive()) out.onStatus("Cerco su internet…") }
                sources = WebFetch.sources(topic, WebFetch.search(topic), max = 4, perChars = 700, stop = { !alive() })
                if (sources.isNotEmpty()) sourcesBlock = sources.joinToString("\n\n") { "[${it.n}] ${it.title}\n${it.text}" }
            }
            if (!alive()) return@Thread
            main.post { if (alive()) { out.onStatus("Scrivo la scaletta… (tocca Parla per fermarmi)"); out.onLine("SAM", "📝 …") } }
            val sb = StringBuilder()
            val latch = java.util.concurrent.CountDownLatch(1)
            try {
                l.askOnce(Slides.prompt(count), "[]", Slides.question(topic, sourcesBlock), null, minOf(2200, 160 + count * 130),
                    onChunk = { c -> sb.append(c); lastProgressAt = System.currentTimeMillis(); main.post { if (alive()) out.onReplace("SAM", "📝 " + sb.toString().replace(Regex("(?s)<think>.*?</think>"), "")) } },
                    onDone = { latch.countDown() }, onError = { latch.countDown() })
            } catch (e: Throwable) { latch.countDown() }
            latch.await(15, java.util.concurrent.TimeUnit.MINUTES)
            if (!alive()) return@Thread
            val deck = Slides.parse(sb.toString(), topic)
            if (deck.slides.size < 2) {
                main.post { if (alive()) { writing = false; speak("La scaletta è venuta male: riprova, magari con un argomento più preciso.", endSession = true) } }
                return@Thread
            }
            main.post { if (alive()) out.onStatus("Faccio le slide…") }
            val base = try { assets.open("pptx/base.pptx").use { it.readBytes() } } catch (e: Throwable) { null }
            val name = SamFiles.fileName(deck.title, "pptx", java.time.LocalDateTime.now())
            val pptx = try { base?.let { FileOut.saveBytes(this, name, FileOut.PPTX, Slides.pptx(it, deck)) } } catch (e: Throwable) { null }
            val pdf = try { FileOut.saveBytes(this, name.removeSuffix(".pptx") + ".pdf", "application/pdf", FileOut.slidesPdf(deck)) } catch (e: Throwable) { null }
            main.post {
                if (!alive()) return@post
                writing = false
                out.onReplace("SAM", Slides.outline(deck))
                if (sources.isNotEmpty()) out.onLine("Fonti", WebSearch.sourcesText(sources))
                if (pptx == null && pdf == null) { speak("Non sono riuscito a salvare le slide: forse la memoria del telefono è piena.", endSession = true); return@post }
                listOfNotNull(pptx, pdf).forEach { out.onLine("File", "📥 ${it.name} · in Download › ${FileOut.FOLDER}") }
                // "apri il file" apre il PDF (si legge ovunque); il PowerPoint è per modificarlo
                (pdf ?: pptx)?.let { FileOut.remember(this, it); FileOut.notifySaved(this, it); if (ui != null) FileOut.open(this, it) }
                talk.reply(talkNow, "Ho preparato la presentazione «${deck.title}», ${deck.slides.size + 1} slide.")
                speak("Fatto: ${deck.slides.size + 1} slide su ${deck.title}. Le trovi in Download, cartella SAM: in PowerPoint, da modificare, e in PDF. " +
                    "Per mandarle a qualcuno di': condividi il file.", endSession = true)
            }
        }.start()
    }

    /** Salva il file in sottofondo, poi lo dice, lo scrive sullo schermo e mette la notifica per aprirlo. */
    private fun saveAndTell(title: String, text: String, pdf: Boolean, code: SamFiles.CodeLang? = null) {
        out.onStatus("Salvo il file…")
        Thread {
            val f = try { FileOut.save(this, title, text, pdf, code) } catch (e: Throwable) { null }
            main.post {
                if (f == null) { speak("Non sono riuscito a salvare il file: forse la memoria del telefono è piena.", endSession = true); return@post }
                FileOut.remember(this, f)
                FileOut.notifySaved(this, f)
                out.onLine("File", "📥 ${f.name} · in Download › ${FileOut.FOLDER}")
                // 2.0.3: una pagina HTML si apre subito nel browser (se SAM è sullo schermo: da sotto Android non lo permette)
                if (code?.ext == "html" && ui != null) try { FileOut.open(this, f) } catch (e: Throwable) {}
                speak("Fatto: l'ho salvato " + (if (code != null) "come file .${code.ext} " else if (pdf) "in PDF " else "") +
                    "nella cartella Download, sotto SAM. " +
                    "Per aprirlo tocca la notifica, oppure di': apri il file. Per mandarlo a qualcuno: condividi il file.", endSession = true)
            }
        }.start()
    }

    /**
     * "Scrivimi un documento su…" o (con [code]) "scrivimi un programma in Python che…": il cervello lo scrive (lo vedi
     * crescere sullo schermo, non lo legge a voce), poi si salva.
     */
    private fun writeFile(topic: String, pdf: Boolean, code: SamFiles.CodeLang? = null) {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain(if (code != null) "scrivere codice" else "scrivere un documento"), endSession = true); return }
        val turn = ++llmTurn
        writing = true
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus((if (code != null) "Scrivo il codice in ${code.name}…" else "Scrivo il documento…") + " (tocca Parla per fermarmi)")
        out.onLine("SAM", "✍️ Scrivo…")
        // 2.0.2: «continua»: si riparte da quello che era già scritto
        val resumed = resumePartial?.takeIf { it.length > 200 && System.currentTimeMillis() - resumeAt < 120_000 }; resumePartial = null
        if (lastRequest.isNotBlank()) { workOn = true; work.begin("write", lastRequest) }
        lastProgressAt = System.currentTimeMillis()
        val rules = Remember.rulesForPrompt(mem.memos())
        val owner = mem.ownerName()
        val system = if (code != null) SamFiles.codePrompt(code, topic) else Llm.withRules(SamFiles.writePrompt(), rules, owner)
        val question0 = if (code != null) SamFiles.codeQuestion(topic) else SamFiles.writeQuestion(topic)

        /**
         * 2.0.3: il cervello scrive un pezzo alla volta; se il file non è finito (blocco di codice non chiuso, pagina senza
         * </html>, graffe aperte, documento che non finisce con un punto) si continua da solo, fino a 12 pezzi. Prima il
         * file veniva salvato così com'era, tronco.
         */
        fun part(before: String?, round: Int) {
            val sb = StringBuilder()
            fun whole(): String = synchronized(sb) { if (before != null) WorkLog.join(before, sb.toString()) else sb.toString() }
            workPartial = { whole() }
            if (before != null) out.onReplace("SAM", "✍️ " + before)
            fun fail(detail: String?) {
                // non è andato: il lavoro resta in sospeso con quello che c'è, «continua» lo riprende
                workOn = false; try { work.interrupt(whole().ifBlank { before }) } catch (x: Throwable) {}; workPartial = null
                writing = false
                if (detail != null) out.onLine("Dettaglio", detail.take(200))
                speak("Non sono riuscito a finire di scrivere: di' continua, e riprendo da dove sono arrivato.", endSession = true)
            }
            Thread {
                try {
                    l.askOnce(system, "[]", if (before != null) WorkLog.continuation(question0, before) else question0, null, if (code != null) 2000 else 1000,
                        onChunk = { c -> lastProgressAt = System.currentTimeMillis(); main.post { if (turn == llmTurn && writing) {
                            synchronized(sb) { sb.append(c) }
                            val all = whole()
                            work.progress(all)
                            out.onReplace("SAM", "✍️ " + (if (code != null) all else SamFiles.clean(all))) } } },
                        onDone = { main.post { if (turn == llmTurn && writing) {
                            val all = whole()
                            val grew = sb.toString().trim().length > 20
                            if (grew && round < 12 && SamFiles.unfinished(all, code)) {
                                out.onStatus("Non è finito: continuo a scrivere (parte ${round + 1})… (tocca Parla per fermarmi)")
                                part(SamFiles.openEnd(all), round + 1)
                            } else finishWrite(topic, all, pdf, code)
                        } } },
                        onError = { e -> main.post { if (turn == llmTurn && writing) {
                            // un errore dopo aver già scritto molto: si salva quello che c'è solo se è completo, altrimenti resta in sospeso
                            val all = whole()
                            if (all.isNotBlank() && !SamFiles.unfinished(all, code)) finishWrite(topic, all, pdf, code) else fail(e)
                        } } })
                } catch (e: Throwable) { main.post { if (turn == llmTurn && writing) fail(e.message) } }
            }.start()
        }
        part(resumed, 1)
    }

    private fun finishWrite(topic: String, raw: String, pdf: Boolean, code: SamFiles.CodeLang? = null) {
        writing = false
        if (code != null) {
            val src = SamFiles.extractCode(raw)
            if (src.trim().lines().size < 2) { speak("Il codice è venuto vuoto: prova a chiedermelo in un altro modo.", endSession = true); return }
            out.onReplace("SAM", src)
            val title = SamFiles.codeTitle(topic)
            talk.reply(talkNow, "Ho scritto il codice in ${code.name}: «$title».")
            // 2.0.3: se dopo tutti i pezzi non è ancora completo, lo dico (prima lo salvava tronco senza dire niente)
            if (SamFiles.unfinished(raw, code)) out.onLine("Info", "Attenzione: il file è molto lungo e potrebbe non essere completo. Guardalo; se manca la fine chiedimi di rifarlo più semplice, o a pezzi.")
            saveAndTell(title, src, false, code)
            return
        }
        val text = SamFiles.clean(raw)
        if (Remember.words(text).size < 5) { speak("Il documento è venuto vuoto: prova a chiedermelo in un altro modo.", endSession = true); return }
        val title = SamFiles.titleOf(text, topic)
        // il titolo è già il nome del file e la prima riga del PDF: nel testo non si ripete
        val first = text.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('•', ' ', ':', '.')?.removePrefix("Titolo:")?.trim()
        val body = if (first == title) text.substringAfter(text.lines().first { it.isNotBlank() }).trim() else text
        out.onReplace("SAM", text)
        talk.reply(talkNow, "Ho scritto il documento «$title».")
        // 2.0.3: i documenti si salvano come pagina HTML (si aprono nel browser); in PDF solo se lo chiedi
        if (SamFiles.unfinished(raw, null)) out.onLine("Info", "Attenzione: il documento è molto lungo e l'ultima frase potrebbe essere tronca.")
        if (pdf) saveAndTell(title, body, true) else saveAndTell(title, SamFiles.docHtml(title, body), false, SamFiles.HTML)
    }

    // ------------------------------------------------------------------ il cervello giusto per ogni lavoro (1.15)
    private fun autoModels() = prefs.getBoolean("modelli_auto", true)
    /** il cervello caricato per un lavoro (nome del file), al posto di quello principale; null = il principale */
    @Volatile private var brainOverride: String? = null
    /** il cervello caricato adesso (nome del file) */
    @Volatile private var loadedName: String? = null
    @Volatile private var switching = false

    /** finito il lavoro speciale, quando SAM è libero si torna al cervello principale */
    private val backToMain: Runnable = object : Runnable {
        override fun run() {
            if (brainOverride == null) return
            if (state != St.IDLE || llmActive || writing || drawing || reasoning || switching || lookQuestion != null) {
                main.postDelayed(this, 60_000L); return
            }
            brainOverride = null
            Thread { loadBrain() }.start()
        }
    }

    /**
     * Fa [then] col cervello giusto per [task] (vedi [ModelRouter]): se serve ne carica un altro, e qualche minuto dopo
     * l'ultimo lavoro speciale torna da solo al principale. Se manca il cervello giusto lo scarica col Wi-Fi e intanto
     * usa quello che c'è. Per chiacchierare non si cambia: se sta usando quello per programmare, continua con quello
     * finché non torna da solo al principale (così "spiegami il codice" lo fa lo stesso cervello che l'ha scritto).
     */
    private fun withBrainFor(task: ModelRouter.Task, then: () -> Unit) {
        if (!autoModels() || brainLoadingSince > 0 || switching || (task == ModelRouter.Task.CHAT && brainOverride != null)) { then(); return }
        val files = SamApp.llmFiles(this).map { it.name }
        val principal = SamApp.llmFile(this)?.name
        if (task != ModelRouter.Task.CHAT) fetchFor(task, files)
        val want = ModelRouter.choose(task, principal, files)
        val target = if (want == principal) null else want
        if (target != null) { main.removeCallbacks(backToMain); main.postDelayed(backToMain, ModelRouter.BACK_AFTER_MS) }
        val loaded = loadedName
        if (target == brainOverride && llm?.ready == true && (loaded == (target ?: principal))) { then(); return }
        if (target == null && brainOverride == null) { then(); return }
        // si cambia cervello: qualche secondo (i GGUF partono subito, Gemma ci mette di più)
        switching = true
        brainOverride = target
        val name = brainName(File(SamApp.modelsDir(this), target ?: principal ?: ""))
        out.onLine("Info", if (target != null) "Per ${task.label} uso $name." else "Torno al cervello di sempre, $name.")
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Carico $name per ${task.label}…")
        val turn = llmTurn
        Thread {
            loadBrain()
            val ok = llm?.ready == true
            main.post {
                switching = false
                if (!ok && target != null) {
                    // il cervello per il lavoro non parte: si torna al principale (e il lavoro lo fa lui)
                    out.onLine("Info", "$name non è partito: uso il cervello di sempre.")
                    brainOverride = null
                    Thread { loadBrain(); main.post { if (turn == llmTurn) then() } }.start()
                    return@post
                }
                if (turn == llmTurn) then()
            }
        }.start()
    }

    /** Manca il cervello giusto per [task]: si scarica da solo (col Wi-Fi; se non c'è, lo scaricamento aspetta il Wi-Fi). */
    private fun fetchFor(task: ModelRouter.Task, files: List<String>) {
        if (task == ModelRouter.Task.VISION) return            // un cervello che vede pesa 3-4 GB: solo se lo chiedi
        val ram = try { Hardware.specs(this).ramBytes } catch (e: Throwable) { 0L }
        val m = ModelRouter.missing(task, files, ram) ?: return
        if (prefs.getLong("agg_cerv_id", -1L) >= 0) return     // ne sta già scaricando uno
        val wifi = Updater.onWifi(this)
        Thread {
            val msg = try { Updater.startBrainDownload(this, m, switchWhenDone = false, addOnly = true, askHf = wifi) } catch (e: Throwable) { null }
            if (msg != null) main.post { out.onLine("Info", msg + (if (!wifi) " Parto appena sei col Wi-Fi." else "") + " Intanto uso il cervello di sempre.") }
        }.start()
    }

    // ------------------------------------------------------------------ autoapprendimento
    private fun learnOn() = prefs.getBoolean("autoapprendimento", true)

    /** Dopo un po' che SAM è libero, rilegge le frasi nuove (se ce ne sono). */
    private val reflectLater = Runnable { reflect() }
    @Volatile private var reflecting = false
    /** riletture andate a vuoto di fila: dopo 3 si aspetta che gli parli di nuovo (niente batteria sprecata) */
    private var reflectFails = 0

    private fun scheduleReflect(retry: Boolean = false) {
        if (retry) { if (++reflectFails >= 3) return } else reflectFails = 0
        main.removeCallbacks(reflectLater)
        main.postDelayed(reflectLater, SelfLearn.IDLE_MS)
    }

    /**
     * Il cervello rilegge le tue frasi nuove e si segna le cose durature su di te (con le tue parole: vedi [SelfLearn]).
     * Solo quando SAM è fermo; se nel frattempo gli parli, quello che stava rileggendo si lascia perdere.
     */
    private fun reflect() {
        // 1.23: il sonno della mente (veloce, senza cervello): le conversazioni finite vanno nel diario, gli impegni si aggiornano
        try { mind.sleep(talk.before(null, within = Talk.RECALL_MS), mem.ownerName(), System.currentTimeMillis()) } catch (e: Throwable) {}
        // 2.0: la manutenzione di ogni giorno (copia di sicurezza, pulizia, controllo), quando sei via
        if (state == St.IDLE && !careRunning) { careRunning = true
            saveNow()
            Thread { val note = try { PhoneCare.daily(this) } catch (e: Throwable) { null } finally { careRunning = false }
                if (note != null) main.post { careNote = note; out.onLine("Info", note) } }.start() }
        val l = llm
        if (reflecting || l == null || !l.ready) return
        if (state != St.IDLE || llmActive || reasoning || chatMode || dialogue || enrolling || lookQuestion != null) { scheduleReflect(retry = true); return }
        val since = prefs.getLong("rifletti_fino", 0L)
        val lines = talk.before(null, within = Talk.RECALL_MS).filter { it.at > since }
        val said = if (learnOn()) SelfLearn.candidates(lines.map { it.you }) else emptyList()
        if (said.isEmpty()) { refineDiary(l); return }
        val upTo = lines.maxOf { it.at }
        val turn = llmTurn
        val owner = mem.ownerName()
        reflecting = true
        Thread {
            val raw = try { l.complete(SelfLearn.prompt(owner), SelfLearn.examplesJson(), SelfLearn.question(said), 120) } catch (e: Throwable) { null }
            main.post {
                reflecting = false
                // gli hai parlato mentre rileggeva: la lettura è stata interrotta, si riprova la prossima volta
                if (turn != llmTurn || raw == null) { scheduleReflect(retry = true); return@post }
                reflectFails = 0
                prefs.edit().putLong("rifletti_fino", upTo).apply()
                val facts = SelfLearn.parse(raw, said, mem.memos(), owner, System.currentTimeMillis())
                for (f in facts) {
                    mem.addMemo(f)
                    out.onLine("Memoria", "ho imparato da solo «${f.text}»")
                }
            }
        }.start()
    }

    /** Il cervello scrive il riassunto vero di un episodio del diario (una frase), se SAM è fermo. */
    private fun refineDiary(l: Llm) {
        val e = mind.unrefined() ?: return
        val lines = talk.before(null, within = Talk.RECALL_MS).filter { it.at in e.start..e.end }
        if (lines.isEmpty()) { mind.refine(e, null); return }
        val turn = llmTurn
        val owner = mem.ownerName()
        reflecting = true
        Thread {
            val raw = try { l.complete(Episodes.prompt(owner), "[]", Episodes.question(lines, owner), 80) } catch (t: Throwable) { null }
            main.post {
                reflecting = false
                if (turn != llmTurn || raw == null) { scheduleReflect(retry = true); return@post }
                mind.refine(e, Episodes.accept(raw, lines))
                // ce n'è un altro? si continua finché SAM resta fermo
                if (mind.unrefined() != null) scheduleReflect()
            }
        }.start()
    }

    private fun brainList(): String {
        val cur = SamApp.llmFile(this) ?: return "Non ho ancora un cervello IA: lo trovi nella configurazione, con l'ingranaggio."
        brainOverride?.let { o ->
            val task = if (ModelRouter.codes(o)) "per programmare" else if (ModelRouter.sees(o)) "per guardare" else "per un lavoro"
            return "Adesso uso ${brainName(File(SamApp.modelsDir(this), o))} $task; tra poco torno a quello di sempre, ${brainName(cur)}." +
                (if (autoModels()) " Scelgo da solo il cervello giusto per ogni lavoro." else "")
        }
        val note = Brains.byFile(cur.name)?.note?.let { ", $it" } ?: ""
        val others = SamApp.llmFiles(this).filter { it.name != cur.name }
        val sb = StringBuilder("Uso ${brainName(cur)}$note.")
        if (others.isNotEmpty()) sb.append(" Sul telefono ho anche ${others.joinToString(" e ") { brainName(it) }}: per passarci di': usa l'altro cervello.")
        Updater.readyBrain(this)?.let { sb.append(" C'è anche una versione nuova di ${it.name} pronta da installare.") }
        return sb.toString()
    }

    /** "Usa il cervello veloce" (fast), "…più intelligente" (smart), "usa l'altro cervello" (other), "sei troppo lento" (slow). */
    private fun switchCmd(action: String) {
        val cur = SamApp.llmFile(this) ?: run { speak("Non ho ancora un cervello IA: lo trovi nella configurazione, con l'ingranaggio."); return }
        fun rank(f: File) = Brains.byFile(f.name)?.let { Brains.rank(it) } ?: 99
        val others = SamApp.llmFiles(this).filter { it.name != cur.name }
        // il cervello per programmare (GGUF): se c'è si usa, se no si propone di scaricarlo
        if (action == "coder") {
            val c = Brains.QWEN_CODER_3B
            if (cur.name.equals(c.file, ignoreCase = true)) { speak("Sto già usando ${c.name}, il cervello per programmare."); return }
            others.firstOrNull { it.name.equals(c.file, ignoreCase = true) }?.let { switchBrain(it); return }
            speak(brain.confirm("Il cervello per programmare è ${c.name}: ${c.note}. Sono ${Brains.gb(c.bytes)} da scaricare col Wi-Fi, " +
                "poi passo a quello; quando vuoi tornare indietro di': usa l'altro cervello. Lo scarico?",
                yes = { Outcome.Update("get:${c.file}") }, no = { Outcome.Speak("Va bene, resto con ${brainName(cur)}.", endSession = true) }))
            return
        }
        val target: File? = when (action) {
            // (il cervello per programmare non conta come "più veloce": per chiacchierare è peggio)
            "fast", "slow" -> others.filter { it.length() < cur.length() && Brains.byFile(it.name) !in Brains.EXTRA }.minByOrNull { it.length() }
            "smart" -> others.filter { rank(it) < rank(cur) }.minByOrNull { rank(it) }
            else -> prefs.getString("cervello_prima", null)?.let { p -> others.firstOrNull { it.name == p } } ?: others.firstOrNull()
        }
        if (target != null) {
            if (action == "slow") {
                speak(brain.confirm("Il cervello che uso, ${brainName(cur)}, è bravo ma non velocissimo. Vuoi che passi a ${brainName(target)}, " +
                    "che risponde più in fretta?", yes = { Outcome.BrainCmd("fast") },
                    no = { Outcome.Speak("Va bene, resto così. Se vuoi risposte più corte, di': d'ora in poi rispondimi in breve.", endSession = true) }))
                return
            }
            switchBrain(target)
            return
        }
        // non ce l'ho sul telefono: si può scaricare col Wi-Fi?
        val specs = try { Hardware.specs(this) } catch (e: Throwable) { null }
        val compat = specs?.let { Brains.compatible(it) } ?: emptyList()
        val curModel = Brains.byFile(cur.name)
        val dl: BrainModel? = when (action) {
            "fast", "slow" -> compat.firstOrNull { it.bytes < cur.length() && it != curModel }
            "smart" -> compat.firstOrNull { curModel == null || Brains.rank(it) < Brains.rank(curModel) }
            else -> null
        }
        when {
            dl != null -> speak(brain.confirm("Sul telefono ho solo ${brainName(cur)}. " + (if (action == "smart") "Uno più bravo" else "Uno più veloce") +
                " è ${dl.name}, ${dl.note}: vuoi che lo scarichi col Wi-Fi? Sono ${Brains.gb(dl.bytes)}, poi passo a quello.",
                yes = { Outcome.Update("get:${dl.file}") }, no = { Outcome.Speak("Va bene, resto con ${brainName(cur)}.", endSession = true) }))
            action == "smart" -> speak("Sto già usando il cervello più bravo che questo telefono regge: ${brainName(cur)}.")
            action == "fast" || action == "slow" -> speak("Sto già usando il cervello più veloce che ho: ${brainName(cur)}." +
                (if (action == "slow") " Se vuoi risposte più corte, di': d'ora in poi rispondimi in breve." else ""))
            else -> speak("Sul telefono ho solo ${brainName(cur)}. Per averne un altro di': usa il cervello veloce, oppure: usa il cervello più intelligente.")
        }
    }

    /** Passa a un altro cervello già sul telefono: si ricarica in sottofondo, intanto i comandi funzionano. */
    private fun switchBrain(f: File) {
        brainOverride = null; main.removeCallbacks(backToMain)        // l'hai scelto tu: niente cambi automatici in corso
        val cur = SamApp.llmFile(this)
        if (cur?.name == f.name) { speak("Sto già usando ${brainName(f)}."); return }
        prefs.edit().putString("cervello_prima", cur?.name).apply()
        SamApp.setActiveBrain(this, f.name)
        val warm = SamApp.engineCacheDir(this).listFiles()?.any { it.name.startsWith(f.name) && it.name.endsWith(".xnnpack_cache") && it.length() > 0 } == true
        speak("Ok, passo a ${brainName(f)}. " + (if (warm) "Mi serve qualche secondo per caricarlo" else "La prima volta ci mette qualche minuto a prepararsi") +
            ": intanto i comandi funzionano.", endSession = true)
        Thread { loadBrain() }.start()
    }

    /** "Installa il cervello nuovo": se è pronto si installa, se si sta scaricando lo dice, altrimenti lo cerca. */
    private fun installCmd() {
        if (Updater.readyBrain(this) != null) { installBrain(); return }
        Updater.statusText(this)?.let { speak(it); return }
        checkUpdates(brainOnly = true)
    }

    /** Mette il cervello nuovo al posto del vecchio; se non parte, rimette il vecchio. */
    private fun installBrain() {
        val r = Updater.readyBrain(this) ?: run { speak("Non trovo più il cervello nuovo: lo riscarico al prossimo controllo."); return }
        speak("Ok, installo ${r.name} nuovo. La prima volta ci mette qualche minuto a prepararsi: intanto i comandi funzionano.", endSession = true)
        Thread {
            val old = llm
            llm = null
            try { old?.close() } catch (e: Throwable) {}
            val f = Updater.swapInBrain(this)
            if (f == null) {
                loadBrain()
                main.post { sayLater("Non sono riuscito a mettere il cervello nuovo al suo posto: tengo quello di prima.") }
                return@Thread
            }
            loadBrain()
            if (llm?.ready == true) {
                Updater.keepNewBrain(this, f.name)
                main.post { sayLater("Fatto: ${brainName(f)} nuovo è pronto.") }
            } else {
                Updater.rollbackBrain(this, f.name)
                loadBrain()
                main.post { sayLater("Il cervello nuovo non è partito su questo telefono: ho rimesso quello di prima.") }
            }
        }.start()
    }

    /** Gli aggiornamenti a voce: controllare adesso, a che punto è lo scaricamento, sì o no a quelli automatici. */
    private fun updateCmd(action: String) {
        when {
            action == "auto_on" -> {
                Updater.setAuto(this, true)
                speak("Ok: una volta alla settimana, col Wi-Fi, controllo da solo se c'è una Wikipedia o un cervello più nuovo.", endSession = true)
            }
            action == "auto_off" -> {
                Updater.setAuto(this, false)
                speak("Ok, niente più aggiornamenti da solo. Quando vuoi chiedimi: ci sono aggiornamenti?", endSession = true)
            }
            action == "status" -> speak(Updater.statusText(this) ?: "Non sto scaricando niente.")
            action.startsWith("get:") -> {
                val model = Brains.byFile(action.removePrefix("get:")) ?: run { speak("Non conosco quel cervello."); return }
                if (!Updater.onWifi(this)) {
                    speak("Per scaricare uso solo il Wi-Fi, e adesso non ci sei collegato: chiedimelo di nuovo quando sei col Wi-Fi.", endSession = true)
                    return
                }
                val turn = ++llmTurn
                out.onStatus("Preparo lo scaricamento…")
                Thread {
                    val msg = try { Updater.startBrainDownload(this, model, switchWhenDone = true) } catch (e: Throwable) { "Non riesco a scaricarlo: ${e.message}." }
                    main.post { if (turn == llmTurn) speak(msg, endSession = true) else out.onLine("Info", msg) }
                }.start()
            }
            else -> checkUpdates(brainOnly = false)
        }
    }

    /** "Ci sono aggiornamenti?": il controllo adesso (solo col Wi-Fi), e la risposta a voce. */
    private fun checkUpdates(brainOnly: Boolean) {
        if (!Updater.onWifi(this)) {
            speak("Per gli aggiornamenti uso solo il Wi-Fi, e adesso non ci sei collegato: chiedimelo di nuovo quando sei col Wi-Fi.", endSession = true)
            return
        }
        val turn = ++llmTurn
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Controllo gli aggiornamenti…")
        Thread {
            val said = try { Updater.check(this, force = true, brainOnly = brainOnly) }
                       catch (e: Throwable) { "Non riesco a controllare gli aggiornamenti: ${e.message ?: "errore"}." }
            main.post {
                if (turn != llmTurn) { if (said.isNotBlank()) out.onLine("Info", said); return@post }
                speak(said.ifBlank { "Nessuna novità: hai già le versioni più recenti." }, endSession = true)
            }
        }.start()
    }

    /**
     * "Non mi piace quando mi interrompi": il cervello scrive la regola per correggersi, SAM chiede se tenerla.
     * È così che SAM cambia da solo il proprio comportamento (i pesi del cervello sul telefono non si riaddestrano).
     */
    private fun selfEdit(complaint: String) {
        val l = llm
        if (l == null || !l.ready) {
            speak("Hai ragione. Dimmi tu come devo cambiare, per esempio: d'ora in poi rispondimi in modo breve. Me lo segno come regola.")
            return
        }
        val turn = ++llmTurn
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Ci penso…")
        val rules = Remember.rulesForPrompt(mem.memos())
        Thread {
            val raw = try { l.complete(Llm.selfEditPrompt(), "[]", Llm.selfEditQuestion(complaint, rules), 60) } catch (e: Throwable) { null }
            val rule = Llm.parseSelfRule(raw)
            main.post {
                if (turn != llmTurn) return@post
                when {
                    rule == null -> speak("Hai ragione. Dimmi tu come devo cambiare, per esempio: d'ora in poi rispondimi in modo breve.")
                    brain.knowsRule(rule) -> speak("Hai ragione, scusa: è già una delle mie regole, «$rule». Ci starò più attento.")
                    else -> {
                        out.onLine("Regola proposta", rule)
                        speak(brain.proposeRule(rule, "Hai ragione. Vuoi che d'ora in poi segua questa regola: «$rule»?"))
                    }
                }
            }
        }.start()
    }

    /** Le novità degli aggiornamenti, dette una volta quando chiami SAM, e il cervello nuovo da installare. true = ha parlato. */
    /** 1.23: al risveglio la mente può avere qualcosa da dire: il saluto del mattino, «com'è andata?», un'abitudine. */
    private fun offerMind(): Boolean {
        val q = try { brain.offerMind() } catch (e: Throwable) { null } ?: return false
        sessionActive = true
        speak(q)
        return true
    }

    private fun offerNews(): Boolean {
        val news = Updater.takeNews(this)
        val ready = Updater.readyBrain(this)?.takeIf { Updater.shouldAskInstall(this) }
        if (news == null && ready == null) return false
        sessionActive = true
        actions.vibrate(60)
        val ask = ready?.let { r ->
            Updater.markAsked(this)
            brain.confirm("Ho scaricato una versione nuova del mio cervello, ${r.name}, e l'ho controllata: la installo adesso? " +
                "Ci vogliono un paio di minuti.", yes = { Outcome.BrainCmd("install") },
                no = { Outcome.Speak("Va bene. Quando vuoi, di': installa il cervello nuovo.", endSession = true) })
        }
        speak(listOfNotNull(news, ask).joinToString(" "))
        return true
    }

    // ------------------------------------------------------------------ modalità conversazione (con SAM)
    /** "Modalità conversazione", "parliamo": SAM ascolta di continuo, senza bisogno di dire "SAM"; i comandi funzionano. */
    private fun chat(o: Outcome.Chat) {
        if (!o.on) { endChat("Ok, fine della conversazione. Quando vuoi, di' SAM."); return }
        endDialogue(null)
        chatMode = true; chatQuiet = 0; sessionActive = true
        val opener = o.opener
        if (opener != null) askLlm(opener)
        else {
            val msg = "Va bene, parliamo! Non serve dire SAM ogni volta: ti ascolto finché non dici basta. Di cosa ti va di parlare?"
            talk.reply(talkNow, msg)
            speak(msg)
        }
    }

    /** Fine della modalità conversazione: [msg] = cosa dire (null = niente); [quiet] = non chiudere l'ascolto. */
    private fun endChat(msg: String?, quiet: Boolean = false) {
        val was = chatMode
        chatMode = false
        if (quiet) return
        if (msg != null) speak(if (was) msg else "Ok.", endSession = true) else endSession()
    }

    private fun noBrain(what: String): String =
        if (SamApp.llmFile(this) == null) "Per $what mi serve il cervello IA: importalo dalla configurazione, con l'ingranaggio."
        else if (brainLoadingSince > 0) "Sto ancora caricando il mio cervello, da ${(System.currentTimeMillis() - brainLoadingSince) / 1000} " +
            "secondi: i comandi funzionano già, per $what aspetta ancora un momento."
        else if (brainError != null) "Il mio cervello non è partito: $brainError. Prova a riaprire SAM."
        else "Sto ancora caricando il mio cervello, un momento."

    // ------------------------------------------------------------------ la tua voce
    private fun voiceCommand(action: String) {
        val p = profile
        when (action) {
            "enroll" -> startEnroll()
            "recognize" -> if (p == null) startEnroll() else voiceCommand("check")
            "check" -> speak(whoAmI())
            "only_on" ->
                if (p == null) speak("Prima devo imparare la tua voce: di' SAM, impara la mia voce.")
                else {
                    voiceStore.onlyOwner = true
                    ownerSeenAt = System.currentTimeMillis()
                    speak("Ok, da ora rispondo solo a te. Per tornare come prima di': SAM, ascolta tutti.", endSession = true)
                }
            "only_off" -> { voiceStore.onlyOwner = false; speak("Ok, rispondo a tutti.", endSession = true) }
            "forget" -> {
                voiceStore.delete(); profile = null; adaptsSinceSave = 0
                speak("Fatto, ho dimenticato la tua voce. Rispondo a tutti.", endSession = true)
            }
        }
    }

    /**
     * "Sai chi sono?", "mi riconosci?": il nome che gli hai detto e, se conosce la tua voce, quanto le somiglia la frase
     * appena detta. Senza nome lo chiede (la risposta la prende il cervello dei comandi).
     */
    private fun whoAmI(): String {
        val n = mem.ownerName() ?: return brain.askOwnerName(first = false)
        val p = profile
        val s = lastVoice
        return when {
            p == null -> "Sei $n, me l'hai detto tu. La tua voce però non la conosco ancora: se vuoi che ti riconosca anche da " +
                "quella, di': impara la mia voce."
            voskEngine?.knowsVoices != true -> "Sei $n, me l'hai detto tu. Dalla voce adesso non posso dirlo: il modello delle voci non è partito."
            s == null || s.frames < VoiceId.MIN_FRAMES -> "Sei $n, me l'hai detto tu. Per dirti se ti riconosco anche dalla voce, " +
                "chiedimelo con una frase un po' più lunga, per esempio: mi riconosci dalla voce?"
            else -> {
                val pct = VoiceId.similarity(p, s)
                val need = Math.round((1f - VoiceId.thresholdFor(p, s)) * 100f).coerceIn(0, 100)
                when (VoiceId.verdict(p, s)) {
                    VoiceId.Verdict.OWNER -> "Sei $n: ti riconosco anche dalla voce, somiglia ${al(pct)} per cento."
                    VoiceId.Verdict.UNSURE -> "Credo che tu sia $n, ma dalla voce non ne sono sicuro: somiglia ${al(pct)} per cento, " +
                        "per esserne sicuro mi serve ${il(need)}." + relearnHint()
                    VoiceId.Verdict.OTHER -> "Dalla voce non mi sembri $n: somiglia solo ${al(pct)} per cento, per riconoscerti mi serve ${il(need)}." +
                        relearnHint()
                }
            }
        }
    }

    /** Come rimediare se SAM non ti riconosce: con "rispondi solo a me" serve prima toccare "Parla". */
    private fun relearnHint(): String =
        if (voiceStore.onlyOwner && !touchTrusted(System.currentTimeMillis()))
            " Se sei tu, tocca Parla sullo schermo e di': impara la mia voce. La imparo di nuovo."
        else " Se sei tu, di': impara la mia voce, e la imparo di nuovo."

    /** Impara la voce: SAM dice delle frasi, tu le ripeti una alla volta. */
    private fun startEnroll() {
        if (voskEngine?.knowsVoices != true) {
            speak("Mi manca il modello delle voci: reinstalla SAM con l'ultimo SAM.apk.", endSession = true); return
        }
        enrolling = true; enrollSamples.clear(); enrollStep = 0; enrollMisses = 0
        sessionActive = true
        out.onStatus("Imparo la tua voce…")
        // meglio come parli di solito a SAM (stessa distanza dal telefono): così poi ti riconosce nella vita di tutti i giorni
        speak("Va bene, imparo la tua voce. Ti dico ${VoiceId.ENROLL_SAMPLES} frasi: ripetile una alla volta con la tua voce di sempre, " +
            "tenendo il telefono come quando mi parli di solito, senza altre persone che parlano. Prima frase: ${VoiceId.PHRASES[0]}")
    }

    private fun onEnrollUtterance(text: String, voice: VoiceSample?) {
        main.removeCallbacks(silenceTimeout)
        if (Lang.isStop(text) || Lang.rx("^(?:annulla|lascia stare|lascia perdere|smetti)\\b").containsMatchIn(text.trim())) {
            enrolling = false
            speak("Va bene, lasciamo stare. Quando vuoi di': SAM, impara la mia voce.", endSession = true)
            return
        }
        out.onLine("Tu", text)
        if (voice == null || voice.frames < VoiceId.ENROLL_FRAMES) {
            if (++enrollMisses > 4) {
                enrolling = false
                speak("Non riesco a sentirti bene. Riproviamo più tardi, in un posto più tranquillo.", endSession = true)
                return
            }
            speak("Non ti ho sentito bene: ripeti con calma. ${VoiceId.PHRASES[enrollStep]}")
            return
        }
        enrollSamples.add(voice)
        enrollStep++
        if (enrollStep < VoiceId.ENROLL_SAMPLES)
            speak((if (enrollStep == VoiceId.ENROLL_SAMPLES - 1) "Ultima: " else "Bene. ") + VoiceId.PHRASES[enrollStep])
        else finishEnroll()
    }

    private fun finishEnroll() {
        enrolling = false
        val p = VoiceId.enroll(enrollSamples)
        if (p == null) {
            speak("Le frasi erano troppo diverse tra loro: forse c'era rumore o un'altra voce. " +
                "Riproviamo quando sei in un posto tranquillo.", endSession = true)
            return
        }
        profile = p; adaptsSinceSave = 0
        ownerSeenAt = System.currentTimeMillis()
        unsureInRow = 0; voiceTroubleAt = 0L; refusedAt = 0L; voiceInfoAt = 0L
        Thread { try { voiceStore.save(p) } catch (e: Exception) {} }.start()
        speak(if (voiceStore.onlyOwner) "Fatto, ho imparato di nuovo la tua voce. Continuo a rispondere solo a te."
              else "Fatto, ho imparato la tua voce. Se vuoi che risponda solo a te, di': SAM, ascolta solo me.", endSession = true)
    }

    // ------------------------------------------------------------------ documenti condivisi
    private fun onDocumentIntent(i: Intent) {
        val path = i.getStringExtra(EXTRA_PATH) ?: return
        val name = i.getStringExtra(EXTRA_NAME) ?: File(path).name
        val mime = i.getStringExtra(EXTRA_MIME)
        endDialogue(null)
        interruptQuietly()
        resumeMic()
        val title = name.substringBeforeLast('.').ifBlank { name }
        out.onLine("Tu", "📄 $name")
        out.onStatus("Apro «$title»…")
        state = St.THINKING
        out.onMode(OrbView.Mode.THINKING)
        Thread {
            val file = File(path)
            val bytes = try { if (file.length() > 60_000_000) null else file.readBytes() } catch (e: Exception) { null }
            val c = if (bytes == null) DocText.Content.Unsupported("Il file è troppo grande, oppure non riesco a leggerlo.")
                    else DocText.extract(bytes, name, mime)
            when (c) {
                is DocText.Content.Text -> main.post {
                    if (c.text.isBlank()) speak("Il file «$title» è vuoto.", endSession = true)
                    else openDoc(OpenDoc(file, title, c.text, c.pages, scanned = false))
                }
                is DocText.Content.Pdf -> {
                    val r = try { PdfText.extract(file) } catch (e: Exception) { null }
                    main.post {
                        when {
                            r == null -> speak("Non riesco ad aprire questo PDF: forse è protetto da password.", endSession = true)
                            DocText.wordCount(r.text) >= 15 -> openDoc(OpenDoc(file, title, r.text, r.pages, scanned = false))
                            else -> openScanned(OpenDoc(file, title, "", r.pages, scanned = true))
                        }
                    }
                }
                is DocText.Content.Image -> {
                    val img = File(cacheDir, "immagine.jpg")
                    val ok = Images.prepare(file, img)
                    main.post {
                        if (!ok) speak("Non riesco ad aprire questa immagine.", endSession = true)
                        else withBrainFor(ModelRouter.Task.VISION) { lookAt(img.absolutePath, Llm.visionQuestion(null), intro = "Guardo «$title».") }
                    }
                }
                is DocText.Content.Unsupported -> main.post { speak(c.why, endSession = true) }
            }
        }.start()
    }

    private fun openDoc(d: OpenDoc) {
        doc = d
        sessionActive = true
        out.onStatus("Documento aperto: «${d.name}»")
        speak(brain.openDocument(d.name, DocText.wordCount(d.text), d.pages))
    }

    /** PDF fatto di immagini (una scansione): si legge pagina per pagina con la vista del cervello. */
    private fun openScanned(d: OpenDoc) {
        val l = llm
        if (l == null || !l.ready || !l.canSee) {
            speak("«${d.name}» è una scansione, fatta di immagini, e il cervello che ho non sa leggerle. " +
                "Se ce l'hai, mandami il PDF con il testo.", endSession = true)
            return
        }
        doc = d
        brain.document = d.name
        speak(if (d.pages > 1) "«${d.name}» è una scansione di ${d.pages} pagine: te le leggo guardandole, una alla volta." else
            "«${d.name}» è una scansione: te la leggo guardandola.")
        readScannedPage(d)
    }

    private fun docAction(o: Outcome.Doc) {
        val d = doc
        if (d == null) { speak("Non ho nessun documento aperto. Condividilo con SAM dal tasto Condividi.", endSession = true); return }
        when (o.action) {
            "read", "restart" -> { d.next = 0; if (d.scanned) readScannedPage(d) else readNext(d) }
            "next" -> if (d.scanned) readScannedPage(d) else readNext(d)
            "summary" -> if (d.scanned) speak("Questa è una scansione: posso leggertela pagina per pagina. Di': leggila.") else summarize(d)
            "ask" -> if (d.scanned) speak("Questa è una scansione: posso leggertela pagina per pagina. Di': leggila.") else askDoc(d, o.question)
            "close" -> { doc = null; brain.document = null; speak("Ok, ho chiuso «${d.name}».", endSession = true) }
        }
    }

    /** Legge ad alta voce il pezzo successivo, poi chiede "Continuo?". */
    private fun readNext(d: OpenDoc) {
        if (d.next >= d.sections.size) { d.next = 0; speak("Il documento è finito. Se vuoi, te lo riassumo.", endSession = false); return }
        val part = d.sections[d.next++]
        sessionActive = true
        out.onLine("SAM", part)
        speakRaw(part)
        if (d.next < d.sections.size) {
            val q = brain.askContinue()
            out.onLine("SAM", q); speakRaw(q)
        } else speak("Fine del documento.")
    }

    private fun readScannedPage(d: OpenDoc) {
        if (d.next >= d.pages || d.next >= 30) { d.next = 0; speak("Il documento è finito.", endSession = true); return }
        val page = d.next++
        val pageFile = File(cacheDir, "pagina.jpg")
        state = St.THINKING
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Guardo la pagina ${page + 1}…")
        Thread {
            val ok = PdfText.renderPage(d.file, page, pageFile)
            main.post {
                if (!ok) { speak("Non riesco ad aprire la pagina ${page + 1}.", endSession = true); return@post }
                lookAt(pageFile.absolutePath, "Leggi il testo di questa pagina, dall'inizio, così com'è scritto.", read = true,
                    intro = if (d.pages > 1) "Pagina ${page + 1}." else null) {
                    if (d.next < d.pages && d.next < 30) { val q = brain.askContinue(); out.onLine("SAM", q); speakRaw(q) }
                    else speakRaw("Fine del documento.")
                }
            }
        }.start()
    }

    /** Riassunto: un documento breve in una volta, uno lungo a pezzi e poi tutto insieme. */
    private fun summarize(d: OpenDoc) {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain("riassumere")); return }
        if (d.text.length <= 2600) {
            askLlm(Llm.docSummaryShort(d.name, d.text), shown = "riassumi", offer = false, once = Llm.docPrompt() to "[]")
            return
        }
        val parts = DocText.spread(d.chunks, 6)
        val partial = parts.size < d.chunks.size
        val turn = ++llmTurn
        speak("È un documento lungo: lo leggo a pezzi, ci vuole un minuto o due. Te lo dico appena ho finito.", endSession = true)
        Thread {
            val sums = ArrayList<String>()
            for ((i, p) in parts.withIndex()) {
                if (turn != llmTurn) return@Thread
                main.post { out.onStatus("Riassumo «${d.name}»: parte ${i + 1} di ${parts.size}…") }
                val s = try { l.complete(Llm.docPrompt(), "[]", Llm.docSummaryPart(d.name, p, i + 1, parts.size), 160) } catch (e: Throwable) { null }
                s?.let { Reply.tidy(it) }?.takeIf { it.isNotBlank() }?.let { sums.add(it) }
            }
            main.post {
                if (turn != llmTurn) return@post
                if (sums.isEmpty()) speak("Non sono riuscito a riassumerlo.", endSession = true)
                else askLlm(Llm.docSummaryFinal(d.name, sums, partial), shown = "riassumi", offer = false, once = Llm.docPrompt() to "[]")
            }
        }.start()
    }

    /** Una domanda sul documento: si danno al cervello solo i pezzi che c'entrano. */
    private fun askDoc(d: OpenDoc, question: String) {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain("rispondere sul documento")); return }
        val ctx = DocText.best(d.chunks, question, 2)
        askLlm(Llm.docQuestion(d.name, ctx, question), shown = question, offer = false, once = Llm.docPrompt() to "[]")
    }

    // ------------------------------------------------------------------ la vista
    /** "Cosa vedi?": si apre la fotocamera, scatta da sola, e la foto va al cervello. */
    private fun look(o: Outcome.Look, routed: Boolean = false) {
        if (o.explain) { speak(sightInfo(o.question)); return }
        // prima il cervello che vede (se quello di adesso non ci vede e ce n'è uno sul telefono)
        if (!routed) { withBrainFor(ModelRouter.Task.VISION) { look(o, true) }; return }
        val l = llm
        // "apri la fotocamera" e SAM non ci vede (nessun cervello, o uno senza vista): si apre l'app Fotocamera
        val blind = SamApp.llmFile(this) == null || (l != null && l.ready && !l.canSee)
        if (o.openIfBlind && blind && brainLoadingSince == 0L) {
            speak(if (actions.openCamera(o.front)) "Ho aperto la fotocamera. Finché resta aperta potrei non sentirti: quando hai finito, chiudila."
                  else "Non riesco ad aprire la fotocamera.", endSession = true)
            return
        }
        if (l == null || !l.ready) { speak(noBrain("guardare")); return }
        if (!l.canSee) { speak(cantSee(l), endSession = true); return }
        lookQuestion = o.question
        val turn = ++lookTurn
        val asked = System.currentTimeMillis()
        if (!actions.look(o.front)) { lookQuestion = null; speak("Non riesco ad aprire la fotocamera.", endSession = true); return }
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus("Guardo…")
        // la schermata della fotocamera non è comparsa: Android (o Xiaomi) l'ha bloccata in silenzio
        main.postDelayed({
            if (turn == lookTurn && lookQuestion != null && lookOpenedAt < asked) {
                lookQuestion = null
                speak("Android non mi ha lasciato aprire la fotocamera. Nella configurazione di SAM attiva il servizio di " +
                    "accessibilità e, tra i permessi speciali, le finestre pop-up in background di Xiaomi.", endSession = true)
            }
        }, 7_000)
        main.postDelayed({
            if (turn == lookTurn && lookQuestion != null) { lookQuestion = null; speak("La fotocamera non ha risposto.", endSession = true) }
        }, 25_000)
    }

    private fun onPhoto(path: String?, error: String?) {
        val q = lookQuestion ?: return
        lookQuestion = null; lookTurn++
        if (path == null) { speak(error ?: "Non sono riuscito a scattare la foto.", endSession = true); return }
        lookAt(path, Llm.visionQuestion(q))
    }

    /** Il cervello guarda l'immagine e risponde; [read] = leggere un testo lungo (risposta più lunga). */
    private fun lookAt(image: String, question: String, read: Boolean = false, intro: String? = null, retry: Boolean = false,
                       then: (() -> Unit)? = null) {
        val l = llm
        if (l == null || !l.ready || !l.canSee) {
            speak(if (l != null && l.ready) cantSee(l) else noBrain("guardare"), endSession = true)
            return
        }
        if (!retry) lastLook = LookReq(image, question, read, then)
        sessionActive = true
        intro?.let { out.onLine("SAM", it); speakRaw(it) }
        askLlm(question, shown = question, offer = false, once = Llm.visionPrompt() to "[]", image = image,
            maxTokens = if (read) 700 else 260, maxChars = if (read) 4000 else Reply.MAX_CHARS, then = then)
    }

    /** Perché il cervello non vede, detto in modo chiaro (con il nome del file, per capire quale serve). */
    private fun cantSee(l: Llm): String {
        val file = SamApp.llmFile(this)?.name ?: "il cervello"
        return if (l.info?.vision != true)
            "Il cervello che hai importato, $file, non ha la parte della vista. Per guardare serve un cervello che vede, " +
                "come Gemma 4 E2B: lo trovi nella configurazione."
        else "La parte della vista del cervello non è partita su questo telefono" + (l.visionNote?.let { ": $it." } ?: ".") +
            " Riavvia SAM e riprova; se non va ancora, chiudi le altre app aperte, che occupano memoria."
    }

    /** "Hai gli occhi?", "come faccio a farti vedere una cosa?", "perché non vedi?": come si usa la vista. */
    private fun sightInfo(q: String): String {
        val l = llm
        val sorry = if (Lang.rx("\\bnon\\b").containsMatchIn(q)) "Scusa, mi sono spiegato male. " else ""
        return sorry + when {
            l == null || !l.ready -> noBrain("guardare")
            !l.canSee -> cantSee(l)
            else -> "Ci vedo con la fotocamera del telefono. Di': cosa vedi? Oppure: leggimi questo, che pianta è questa, guardami. " +
                "Apro la fotocamera, scatto da solo e ti dico cosa vedo. Puoi anche mandarmi una foto da Condividi, Leggi con SAM."
        }
    }

    // ------------------------------------------------------------------ meteo, letto dallo schermo
    private fun screenUnlocked(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (e: Exception) { true }

    /**
     * SAM non va su internet: apre l'app del meteo (o la pagina delle previsioni nel browser), legge lo schermo
     * con il servizio di accessibilità e te lo dice a voce. Con il cervello IA risponde alla tua domanda precisa.
     */
    private fun weather(o: Outcome.Weather) {
        val pkg = actions.openWeather(o.city, o.day)
        if (pkg == null) { speak("Non riesco ad aprire il meteo.", endSession = true); return }
        if (!SamAccessibilityService.isOn()) {
            speak("Ti ho aperto il meteo. Se attivi il servizio di accessibilità di SAM, te lo leggo io.", endSession = true)
            return
        }
        val turn = ++llmTurn
        val locked = !screenUnlocked()
        if (locked) speak("Sblocca il telefono e te lo leggo.", endSession = true)
        else {
            state = St.THINKING
            main.removeCallbacks(silenceTimeout)
            out.onMode(OrbView.Mode.THINKING)
            out.onStatus("Leggo il meteo…")
        }
        SamAccessibilityService.readScreen(pkg.ifEmpty { null }, if (locked) 30_000 else 10_000,
            ready = { WeatherText.hasTemperature(it) && it.length >= 30 }) { screen ->
            if (turn != llmTurn) return@readScreen          // nel frattempo gli hai chiesto altro
            if (screen == null || !WeatherText.hasTemperature(screen)) {
                speak("Ti ho aperto il meteo, ma non riesco a leggerlo: guardalo sullo schermo.", endSession = true)
                return@readScreen
            }
            val l = llm
            if (l != null && l.ready && !dialogue) {
                sessionActive = true
                askLlm(Llm.weatherQuestion(o.question, o.city, o.day, screen), shown = o.question, offer = false,
                    once = Llm.weatherPrompt() to Llm.weatherExamplesJson())
            } else speak(WeatherText.quick(screen, o.day) ?: "Ti ho aperto il meteo: guardalo sullo schermo.", endSession = true)
        }
    }

    // ------------------------------------------------------------------ IA locale, risposta a frasi
    /**
     * [shown] = la domanda come l'hai fatta tu (per pulire la risposta e per l'eventuale ricerca);
     * [offer] = se la risposta è "non posso saperlo" propone di cercare;
     * [once] = istruzioni ed esempi per una domanda a sé (meteo, foto, documenti), in una conversazione nuova;
     * [image] = la foto da guardare; [then] = cosa fare a risposta finita.
     */
    private fun askLlm(prompt: String, shown: String = prompt, offer: Boolean = true, once: Pair<String, String>? = null,
                       image: String? = null, maxTokens: Int = Llm.MAX_OUTPUT_TOKENS, maxChars: Int = Reply.MAX_CHARS,
                       then: (() -> Unit)? = null, recall: Boolean = false, fallback: String? = null) {
        val l: Llm? = llm?.takeIf { it.ready }
        // 2.1: i cervelli online (più account che si danno il cambio) rispondono anche senza il cervello del telefono
        val online = image == null && !dialogue && OnlineBrain.shouldTry(this)
        if (l == null && !online) {
            // "di cosa abbiamo parlato?" anche senza cervello: le battute le ripete SAM
            if (recall && fallback != null) { talk.reply(talkNow, fallback); speak(fallback) } else speak(noBrain("chiacchierare"))
            return
        }
        // 1.18: "cosa ti ho detto…?": il cervello risponde solo da quello che vi siete detti davvero (con quando) e dai ricordi,
        // con istruzioni apposta che gli vietano di inventare (prima rispondeva dalla conversazione e a volte si inventava le cose)
        if (recall && once == null && image == null) {
            val owner0 = mem.ownerName()
            val lines0 = talk.before(talkNow, within = Talk.RECALL_MS)
            val now0 = System.currentTimeMillis()
            // 1.23: anche il diario (i riassunti delle conversazioni più vecchie della settimana)
            val diary = mind.recallBlock(prompt, now0, lines0.minOfOrNull { it.at } ?: now0)
            val block = listOfNotNull(diary, Talk.recallBlock(lines0, prompt, owner0)).joinToString("\n").ifEmpty { null }
            mind.source("conversazione", "", prompt, now0)
            val q = Llm.recallQuestion(prompt, block, Remember.forPrompt(mind.rank(mem.memos(), now0)))
            askLlm(q, shown, offer = false, once = Llm.recallPrompt(owner0) to "[]", maxTokens = maxTokens, maxChars = maxChars,
                then = then, recall = true, fallback = fallback)
            return
        }
        val turn = ++llmTurn
        state = St.THINKING
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.THINKING)
        out.onStatus(if (image != null) "Guardo…" else "Ci penso…")
        pauseMic()
        liveReply.setLength(0)
        llmActive = true; llmQuestion = shown; llmSpoken = 0; llmStopping = false
        llmOffer = offer && !dialogue; llmMaxChars = maxChars; llmThen = then
        llmChat = once == null && image == null && !dialogue
        llmImage = image
        llmFallback = if (recall) fallback else null
        out.onLine("SAM", "…")
        l?.ownerName = mem.ownerName()
        val memos = mem.memos()
        val nowMs = System.currentTimeMillis()
        // 1.23: nel cervello vanno i ricordi più vivi (usati, recenti, importanti), non solo gli ultimi
        val known = Remember.forPrompt(mind.rank(memos, nowMs))
        l?.memory = known
        val rules = Remember.rulesForPrompt(memos)
        l?.rules = rules
        l?.library = library
        if (memoryEdited) { memoryEdited = false; l?.memoryUrgent = true }
        // davanti alla domanda (solo nella conversazione normale): le lezioni che c'entrano (domande simili a cui l'hai
        // corretto) e i ricordi vecchi che c'entrano e che la conversazione in corso non ha nelle istruzioni
        val extra = if (once != null || dialogue) null
            else listOfNotNull(Remember.lessons(prompt, memos), Remember.context(prompt, memos, l?.conversationMemory() ?: known),
                // 1.23: quello che viene in mente (episodi passati, impegni, stato d'animo)
                try { mind.context(prompt, nowMs) } catch (e: Throwable) { null })
                .joinToString("\n").ifEmpty { null }
        if (once == null && !dialogue) {
            // i ricordi che c'entrano con la domanda si rafforzano (come quando ci ripensi)
            try { mind.touch(Remember.find(prompt, memos.filter { it.kind == Remember.NOTA || it.kind == Remember.FATTO }).take(3), nowMs) } catch (e: Throwable) {}
            if (llmChat) mind.source("cervello", SamApp.llmFile(this)?.let { brainName(it) } ?: "", shown, nowMs)
        }
        val toSend = extra?.let { "$it\n$prompt" } ?: prompt
        // il filo del discorso: le ultime battute se il cervello è ripartito da capo, oppure solo quelle che non ha visto.
        // "Di cosa abbiamo parlato?": tutte le battute delle ultime ore, sempre.
        val owner = mem.ownerName()
        val recap = if (!llmChat) null
            else if (recall) Talk.recap(talk.before(talkNow, within = Talk.RECALL_MS).takeLast(10), owner, Talk.RECALL_TITLE)
            else Talk.recap(talk.before(talkNow).takeLast(6), owner, Talk.FRESH_TITLE)
        val unseen = if (!llmChat) null else if (recall) recap else Talk.recap(talk.unseen(talkNow), owner, Talk.UNSEEN_TITLE)
        // le regole valgono anche per il meteo, le foto e i documenti
        val onceRuled = once?.let { Llm.withRules(it.first, rules, owner) to it.second }
        val book = if (llmChat) enc else null
        // per i cervelli online: niente conversazione nel motore, il filo del discorso va sempre davanti alla domanda
        val onlineSystem = if (!online) null else onceRuled?.first
            ?: Llm.systemPrompt(owner, memory = known, rules = rules, library = library)
        val onlineRecap = if (!online || !llmChat) null
            else if (recall) recap
            else Talk.recap(talk.before(talkNow).takeLast(10), owner, Talk.FRESH_TITLE)
        Thread {
            // "chi era Garibaldi?": prima la voce dell'enciclopedia, così il cervello risponde dai fatti e non a memoria
            var send = toSend
            if (book != null) groundFor(book, prompt, memos)?.let { (title, block) ->
                send = "$block\n$toSend"
                mind.source("enciclopedia", title, shown, System.currentTimeMillis())
                main.post { if (turn == llmTurn) out.onLine("Enciclopedia", "«$title»") }
            }
            try {
                val onChunk: (String) -> Unit = { c -> main.post { if (turn == llmTurn) onLlmChunk(c, l) } }
                val onDone: () -> Unit = { main.post { if (turn == llmTurn) onLlmDone() } }
                val onError: (String) -> Unit = { e -> main.post { if (turn == llmTurn) onLlmError(e) } }
                if (online && onlineSystem != null) {
                    val question = listOfNotNull(onlineRecap, send).joinToString("\n")
                    val outcome = OnlineBrain.ask(this, onlineSystem, question, onChunk) { note ->
                        main.post { if (turn == llmTurn) out.onStatus(note) }
                    }
                    when (outcome) {
                        OnlineBrain.Outcome.ANSWERED -> {
                            main.post { if (turn == llmTurn) OnlineBrain.lastSource?.let { out.onStatus("Risposta da $it") } }
                            onDone(); return@Thread
                        }
                        OnlineBrain.Outcome.CANCELLED -> return@Thread
                        OnlineBrain.Outcome.NONE -> {
                            if (l == null || OnlineBrain.onlineOnly(this)) {
                                val why = OnlineBrain.lastProblem?.let { " ($it)" } ?: ""
                                onError(ONLINE_ERR + "Nessun cervello online mi ha risposto$why" +
                                    (if (l == null) " e sul telefono non ho un cervello caricato." else ". Riprova tra poco."))
                                return@Thread
                            }
                            main.post { if (turn == llmTurn) out.onStatus("Account online esauriti: rispondo col cervello del telefono") }
                        }
                    }
                }
                val local = l ?: run { onError(ONLINE_ERR + "Non ho un cervello pronto per rispondere."); return@Thread }
                if (onceRuled != null) local.askOnce(onceRuled.first, onceRuled.second, send, image, maxTokens, onChunk, onDone, onError)
                else local.ask(send, onChunk, onDone, onError, ifFresh = recap, unseen = unseen)
            } catch (t: Throwable) {
                main.post { if (turn == llmTurn) onLlmError(t.message ?: "errore") }
            }
        }.start()
    }

    /**
     * "Pensaci bene": prima il cervello scrive un ragionamento passo per passo con il metodo di [Llm.reasonPrompt]
     * (lo vedi sullo schermo), poi risponde a voce con il ragionamento davanti. Ci mette di più: solo quando lo chiedi.
     */
    private fun reasonThen(question: String) {
        val l = llm
        if (l == null || !l.ready) { speak(noBrain("ragionare")); return }
        val turn = ++llmTurn
        reasoning = true
        main.removeCallbacks(silenceTimeout)
        out.onLine("SAM", "Ci penso bene, dammi qualche secondo.")
        speakRaw("Ci penso bene, dammi qualche secondo.")
        out.onStatus("Ragiono passo per passo…")
        val owner = mem.ownerName()
        val memos = mem.memos()
        val rules = Remember.rulesForPrompt(memos)
        val context = listOfNotNull(Talk.recap(talk.before(talkNow).takeLast(4), owner, Talk.FRESH_TITLE), Remember.lessons(question, memos))
            .joinToString("\n").ifEmpty { null }
        Thread {
            val notes = try {
                l.complete(Llm.withRules(Llm.reasonPrompt(), rules, owner), "[]", Llm.reasonQuestion(question, context), 360)
            } catch (e: Throwable) { null }
            main.post {
                if (turn != llmTurn) return@post          // nel frattempo l'hai interrotto
                reasoning = false
                val clean = notes?.let { Reply.plain(it) }?.takeIf { it.isNotBlank() }
                if (clean != null) out.onLine("Ragionamento", clean)
                askLlm(Llm.answerAfterReasoning(question, clean), shown = question, maxChars = 700)
            }
        }.start()
    }

    private val ONLINE_ERR = "\u0000online:"

    private fun onLlmChunk(chunk: String, l: Llm?) {
        if (!llmActive) return
        liveReply.append(chunk); lastProgressAt = System.currentTimeMillis()
        val raw = liveReply.toString()
        val owner = mem.ownerName()
        out.onReplace("SAM", Reply.tidy(raw, llmQuestion, owner, scrub = !dialogue, chat = llmChat).ifEmpty { "…" })
        if (!llmStopping && Reply.shouldStop(raw, llmMaxChars)) { llmStopping = true; OnlineBrain.cancel(); l?.cancel(fresh = Reply.derailed(raw)) }
        // le frasi già finite si dicono subito, mentre il resto arriva
        val ready = Reply.sentences(raw, llmQuestion, owner, final = false, scrub0 = !dialogue, chat = llmChat)
        while (llmSpoken < ready.size) speakRaw(ready[llmSpoken++])
    }

    private fun onLlmDone() {
        if (!llmActive) return
        llmActive = false
        val then = llmThen; llmThen = null
        val raw = liveReply.toString()
        val all = Reply.sentences(raw, llmQuestion, mem.ownerName(), final = true, scrub0 = !dialogue, chat = llmChat)
        // "non posso memorizzare" / "non posso vedere" (falsi: SAM ricorda e guarda): le frasi sono già state tolte,
        // al loro posto si dice come si fa
        val denied = llmChat && Reply.deniesMemory(raw)
        val blind = llmChat && Reply.deniesSight(raw)
        // "non posso cercare su internet": tolta anche questa, SAM propone la ricerca
        val noSearch = llmChat && Reply.deniesSearch(raw)
        // "non posso leggere file", "copialo e incollalo qui": tolta, SAM dice come si fa
        val noFiles = llmChat && Reply.deniesFiles(raw)
        // "Regola: …" in fondo alla risposta: il cervello propone una regola per sé (non si legge: SAM chiede se tenerla)
        val selfRule = if (llmChat && Reply.asksChange(llmQuestion)) Reply.selfRule(raw)?.takeIf { !brain.knowsRule(it) } else null
        // la battuta va nel filo del discorso (e se era una chiacchierata, il cervello ce l'ha già)
        val line = if (dialogue) null else talkNow
        val fallback = llmFallback; llmFallback = null
        // 1.20: le azioni chieste dal cervello («AZIONE: …»): le fa SAM, con il cervello dei comandi
        val acts = if (llmChat && !dialogue) Agent.actions(raw) else emptyList()
        if (acts.isNotEmpty() && all.isEmpty()) {
            talk.reply(line, "Lo faccio.")
            out.onReplace("SAM", "Lo faccio.")
            runActions(acts)
            then?.invoke()
            if (utterances.isEmpty()) afterSpeaking()
            return
        }
        if (fallback != null && (all.isEmpty() || denied)) {
            // "di cosa abbiamo parlato?": il cervello non sa dirlo (o dice che non ricorda), le battute le ripete SAM
            val said = (all + fallback).joinToString(" ")
            talk.reply(line, said)
            out.onReplace("SAM", said)
            while (llmSpoken < all.size) speakRaw(all[llmSpoken++])
            speakRaw(fallback)
            then?.invoke()
            if (utterances.isEmpty()) afterSpeaking()
            return
        }
        if (all.isEmpty() && llmSpoken == 0 && !denied && !blind && !noSearch && !noFiles && selfRule == null) {
            val msg = "Non saprei, prova a chiedermelo in un altro modo."
            talk.reply(line, msg)
            out.onReplace("SAM", msg); speakRaw(msg); return
        }
        val said = all.joinToString(" ")
        talk.reply(line, said)
        if (llmChat) line?.chat = true
        if (all.isNotEmpty()) out.onReplace("SAM", said)
        while (llmSpoken < all.size) speakRaw(all[llmSpoken++])
        // la stessa risposta di prima a una domanda diversa: il cervello gira a vuoto (succede ai cervelli piccoli quando
        // una frase li confonde). Quelle risposte escono dal filo del discorso, la prossima domanda riparte pulita
        // e SAM lo dice, invece di continuare a ripetersi
        if (llmChat && all.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val looped = now - prevLlmAt < 5 * 60_000L &&
                Reply.sameAnswer(said, prevLlmAnswer) && !Reply.sameAnswer(llmQuestion, prevLlmQuestion)
            prevLlmAnswer = if (looped) null else said
            prevLlmQuestion = llmQuestion
            prevLlmAt = now
            if (looped) {
                talk.forgetAnswer(said)
                llm?.freshNext()
                val msg = "Scusa, mi sono ripetuto: riparto da capo. Cosa mi chiedevi?"
                out.onLine("SAM", msg)
                speakRaw(msg)
                then?.invoke()
                if (utterances.isEmpty()) afterSpeaking()
                return
            }
        }
        val hints = ArrayList<String>()
        if (blind) hints.add("Per farmi guardare qualcosa, di': cosa vedi? Apro la fotocamera e ti dico cosa vedo.")
        if (noFiles) hints.add("Posso leggere i tuoi file: da qualsiasi app tocca Condividi e scegli Leggi con SAM." +
            (if (enc != null) " E l'enciclopedia ce l'ho: chiedimi per esempio chi era Garibaldi, o cosa dice l'enciclopedia su Napoli."
             else " Se vuoi anche Wikipedia sul telefono, mettila dalla configurazione, al punto 8."))
        if (denied) hints.add(brain.memoryHint(llmQuestion))
        for ((i, hint) in hints.withIndex()) {
            talk.reply(line, hint)
            if (all.isEmpty() && i == 0) out.onReplace("SAM", hint) else out.onLine("SAM", hint)
            speakRaw(hint)
        }
        if (selfRule != null) {
            // il cervello si è dato una regola: si tiene solo con il tuo sì (e la conversazione resta aperta per la risposta)
            val q = brain.proposeRule(selfRule)
            talk.reply(line, q)
            if (all.isEmpty() && hints.isEmpty()) out.onReplace("SAM", q) else out.onLine("SAM", q)
            sessionActive = true
            speakRaw(q)
        }
        if (!denied && llmChat && selfRule == null && Reply.promisesMemory(all.joinToString(" "))) {
            // ha risposto "me lo ricorderò": la cosa che hai detto viene salvata davvero
            brain.keepPromise(llmQuestion)?.let { out.onLine("Memoria", "ricordo «$it»") }
            brain.takeMemoryChange()
            if (brain.takeMemoryDropped()) llm?.memoryUrgent = true
        }
        // "non posso saperlo" (notizie, risultati, prezzi…): propone di cercarlo su internet
        if (llmOffer && !dialogue && selfRule == null && (Reply.cantKnow(all.joinToString(" ")) || noSearch)) {
            val offer = brain.offerSearch(llmQuestion)
            talk.reply(line, offer)
            if (all.isEmpty() && hints.isEmpty()) out.onReplace("SAM", offer) else out.onLine("SAM", offer)
            speakRaw(offer)
        }
        if (acts.isNotEmpty()) runActions(acts)
        then?.invoke()
        if (utterances.isEmpty()) afterSpeaking()   // era già stato detto tutto
    }

    /** Fa le azioni chieste dal cervello: ognuna passa dal cervello dei comandi; quelle che tornerebbero al cervello IA no. */
    private fun runActions(acts: List<String>) {
        for (cmd in acts) {
            val o = try { brain.handle(cmd) } catch (t: Throwable) { null } ?: continue
            if (!Agent.isAction(o)) { out.onLine("Info", "azione non riconosciuta: «$cmd»"); continue }
            out.onLine("Azione", cmd)
            brain.takeMemoryChange()
            if (brain.takeMemoryDropped()) llm?.memoryUrgent = true
            perform(o)
        }
    }

    /** "Leggimi la chat" (le ultime battute, a voce e sullo schermo) e "riassumi la chat" (col cervello). */
    private fun readChat(o: Outcome.ReadChat) {
        val lines = talk.before(talkNow, within = Talk.RECALL_MS)
        if (lines.isEmpty()) { speak("La chat è vuota: non ci siamo ancora detti niente negli ultimi giorni.", endSession = true); return }
        val owner = mem.ownerName() ?: "Tu"
        if (!o.summary) {
            val last = lines.takeLast(o.count)
            val text = last.joinToString("\n") { "$owner: ${it.you}\nSAM: ${it.sam}" }
            out.onLine("SAM", text)
            talk.reply(talkNow, "Ti ho letto la chat.")
            for (l in last) { speakRaw("Tu hai detto: ${it(l.you)}."); speakRaw("Io: ${it(l.sam)}") }
            if (utterances.isEmpty()) afterSpeaking()
            return
        }
        val block = Talk.recallBlock(lines, "", mem.ownerName(), last = o.count, relevant = 0)
        withBrainFor(ModelRouter.Task.CHAT) {
            askLlm("Riassumi la conversazione qui sotto in poche frasi: di cosa abbiamo parlato e cosa è stato deciso.\n\n$block",
                shown = "riassumi la chat", offer = false,
                once = Llm.recallPrompt(mem.ownerName()) to "[]")
        }
    }

    private fun it(s: String) = s.take(400)

    private fun onLlmError(e: String) {
        if (!llmActive) return
        llmActive = false
        llmThen = null
        val img = llmImage
        if (llmSpoken > 0) { if (utterances.isEmpty()) afterSpeaking(); return }   // si tiene quanto già detto
        // 2.1: i cervelli online non hanno risposto (messaggio già pronto da dire)
        if (e.startsWith(ONLINE_ERR)) {
            val msg = e.removePrefix(ONLINE_ERR)
            out.onReplace("SAM", msg); speakRaw(msg); return
        }
        val l = llm
        if (img != null && l != null) {
            // la vista ha dato errore: si ricarica il cervello con l'altro modo di farla girare
            // (processore o scheda grafica) e si riprova una volta, con la stessa foto
            val req = lastLook
            if (req != null && req.image == img && !req.retried && l.visionBackend != null) {
                req.retried = true
                val wait = "Un attimo, provo a guardare in un altro modo."
                out.onReplace("SAM", wait)
                visionSwitching = true
                speakRaw(wait)
                Thread {
                    val nb = try { l.switchVision(e.take(160)) } catch (t: Throwable) { null }
                    main.post {
                        visionSwitching = false
                        if (nb != null) lookAt(req.image, req.question, req.read, then = req.then, retry = true)
                        else visionFailed(l, e)
                    }
                }.start()
                return
            }
            visionFailed(l, e)
            return
        }
        // il cervello si sta ancora caricando (o ricaricando): non è un guasto
        if (l == null || !l.ready || e == "non ancora pronto") {
            val msg = noBrain("rispondere")
            out.onReplace("SAM", msg); speakRaw(msg); return
        }
        // due errori di fila (ognuno già riprovato con una conversazione nuova): il motore si è inceppato, lo si ricarica
        // da solo in sottofondo. I comandi intanto funzionano.
        val reload = l.errorsInRow >= 2
        val msg = if (reload) "Il mio cervello si è inceppato: lo sto riavviando, riprova tra qualche secondo."
                  else "Ho avuto un intoppo con il mio cervello: riprova, riparto pulito."
        out.onReplace("SAM", msg)
        out.onLine("Dettaglio", e.take(200))
        speakRaw(msg)
        if (reload) Thread { loadBrain() }.start()
    }

    /** La vista non ce l'ha fatta: lo dice, e mostra il motivo tecnico sullo schermo (per capire cosa non va). */
    private fun visionFailed(l: Llm, e: String) {
        val msg = if (!l.ready) "La vista mi ha bloccato il cervello: lo ricarico. Riprova tra qualche secondo."
                  else "Non sono riuscito a guardare la foto: la parte della vista del cervello non funziona su questo telefono."
        out.onReplace("SAM", msg)
        out.onLine("Dettaglio", (l.visionNote ?: e).take(200))
        if (!l.ready) Thread { loadBrain() }.start()
        sessionActive = false
        speakRaw(msg)
    }

    /** Un esito arrivato più tardi: lo dice senza interrompere ciò che sta facendo. */
    private fun sayLater(msg: String) {
        if (state == St.IDLE) speak(msg, endSession = true)
        else { out.onLine("SAM", msg); speakRaw(msg) }
    }

    // ------------------------------------------------------------------ conversazione con un'altra IA
    /** SAM si presenta e comincia a parlare con l'altra IA (sul PC o su un altro telefono, in modalità voce). */
    private fun startDialogue(name: String?, topic: String?) {
        val l = llm
        if (l == null || !l.ready) {
            speak(if (SamApp.llmFile(this) == null)
                "Per parlare con un'altra intelligenza artificiale mi serve il cervello IA: importalo dalla configurazione."
            else "Sto ancora caricando il mio cervello, un momento.", endSession = true)
            return
        }
        dialogue = true; dialogueTurns = 0; dialogueQuiet = 0
        partner = name ?: "Altra IA"
        l.preface = Llm.dialoguePrompt(name, topic) to Llm.dialogueExamplesJson()
        sessionActive = true
        out.onStatus("Conversazione con ${name ?: "l'altra IA"} · di' “SAM basta” per finire")
        val saluto = if (name != null) "Ciao $name!" else "Ciao!"
        val apertura = topic?.let { "Mi piacerebbe parlare di $it. Tu che ne pensi?" } ?: "Di cosa ti va di parlare?"
        speak("$saluto Sono SAM, un'intelligenza artificiale che vive in un telefono e funziona senza internet. $apertura")
    }

    /** Una frase sentita durante la conversazione: è l'altra IA che parla (o tu che dici "SAM basta"). */
    private fun onDialogueUtterance(text: String) {
        if (state != St.LISTENING) return
        val rest = Lang.stripWake(text)
        if (rest != null && Lang.rx("^(basta|stop|fine|smetti|smettila|smettetela|termina|ferma|fermati|chiudi|silenzio)\\b").containsMatchIn(rest)) {
            endDialogue("Ok, fine della conversazione.")
            return
        }
        // "SAM, apri la fotocamera": è il proprietario che dà un comando, non l'altra IA. Si esce e lo si esegue.
        brain.commandDuringDialogue(text)?.let { cmd ->
            endDialogue(null)
            sessionActive = true
            process(cmd)
            return
        }
        dialogueQuiet = 0
        collect(text, 1800, "$partner: ") { t -> dialogueTurn(t) }
    }

    private fun dialogueTurn(text: String) {
        out.onLine(partner, text)
        if (++dialogueTurns > 40) { endDialogue("È stata una bella chiacchierata, ma per oggi basta. Ciao!"); return }
        sessionActive = true
        askLlm(text)
    }

    /** Fine della conversazione con l'altra IA: SAM torna alle istruzioni normali. */
    private fun endDialogue(msg: String?) {
        if (!dialogue) return
        dialogue = false
        llm?.preface = null
        stopCollecting()
        if (msg != null) speak(msg, endSession = true)
    }

    /** Zittisce SAM e ferma la risposta in corso, poi si mette in ascolto. */
    private fun interrupt() {
        endDialogue(null)
        interruptQuietly()
        resumeMic()
        beginListening()
    }

    private fun interruptQuietly() {
        // 2.0.2: se stavo lavorando me lo segno (con quello che avevo già scritto): «continua» riprende da lì
        if (workOn && (llmActive || writing || drawing || webbing || reasoning)) {
            workOn = false
            try { work.interrupt(workPartial?.invoke()) } catch (e: Throwable) {}
            workPartial = null
        }
        llmTurn++                                   // le callback della risposta interrotta vengono ignorate
        SamAccessibilityService.cancelRead()        // e anche la lettura del meteo in corso
        lookQuestion = null; lookTurn++             // e la foto che si stava aspettando
        reasoning = false                           // e il ragionamento ("pensaci bene") in corso
        if (llmActive) { llmActive = false; llmThen = null; llm?.cancel(); OnlineBrain.cancel() }
        if (writing) { writing = false; llm?.cancel() }             // e il documento che stava scrivendo
        if (drawing) drawing = false                                // e il disegno (si ferma alla fine del passo in corso)
        if (webbing) { webbing = false; llm?.cancel() }             // e la ricerca su internet
        utterances.clear()
        try { if (ttsReady) tts.stop() } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ voce
    private fun speak(text: String, endSession: Boolean = false) {
        out.onLine("SAM", text)
        // in modalità conversazione si resta in ascolto anche dopo un comando
        if (endSession && !chatMode) sessionActive = false
        speakRaw(text)
    }

    private fun speakRaw(text: String) {
        val c = clean(Identity.say(text))
        if (c.isEmpty()) { if (utterances.isEmpty() && !llmActive) afterSpeaking(); return }
        state = St.SPEAKING
        pauseMic()
        val id = "sam-${System.nanoTime()}"
        utterances.add(id)
        if (quiet) {
            // risposta scritta: sullo schermo c'è già, niente voce (si fa finta di averla detta, per il resto del flusso)
            main.postDelayed({ onSpokenOne(id) }, 40)
            return
        }
        out.onMode(OrbView.Mode.SPEAKING)
        if (ttsReady) tts.speak(c, TextToSpeech.QUEUE_ADD, null, id)
        else main.postDelayed({ onSpokenOne(id) }, 300)
    }

    private fun speakThenStop(text: String) { stopAfterSpeak = true; speak(text) }

    /** Alla fine di ogni frase detta: quando non resta altro, si torna ad ascoltare. */
    private fun onSpokenOne(id: String?) {
        if (id == null || !utterances.remove(id)) return   // frase interrotta o sconosciuta
        if (utterances.isNotEmpty()) return
        if (llmActive) { out.onMode(OrbView.Mode.THINKING); return }   // la risposta sta ancora arrivando
        afterSpeaking()
    }

    private fun afterSpeaking() {
        if (stopAfterSpeak) { stopSelf(); return }
        if (lookQuestion != null || visionSwitching) return   // si aspetta la foto, o il cervello che riparte
        if (capturing) return                                  // la fotocamera di SAM sta scattando o filmando
        if (reasoning) { out.onMode(OrbView.Mode.THINKING); return }   // sta ancora ragionando
        if (drawing) { out.onMode(OrbView.Mode.THINKING); return }     // sta ancora disegnando
        if (webbing) { out.onMode(OrbView.Mode.THINKING); return }     // sta ancora cercando / scrivendo il rapporto
        // 2.0.2: sta ancora scrivendo un programma, un documento o le slide: si resta al lavoro (prima tornava in ascolto,
        // dopo un po' chiedeva «ci sei ancora?» e la scrittura si interrompeva)
        if (writing) { state = St.THINKING; out.onMode(OrbView.Mode.THINKING); return }
        resumeMic()
        if (quiet && !chatMode) { finishQuiet(); return }
        if (sessionActive || chatMode) keepListening() else endSession()
    }

    /** Fine di uno scambio scritto: si torna in attesa, senza "ti ascolto" né vibrazione. */
    private fun finishQuiet() {
        scheduleReflect()
        sessionActive = false
        touchSession = false
        state = St.IDLE
        stopCollecting()
        main.removeCallbacks(silenceTimeout)
        out.onMode(OrbView.Mode.IDLE)
        if (brain.hasPending) {
            // SAM ha fatto una domanda: la risposta si scrive (o si lascia cadere dopo tre minuti)
            out.onStatus("Aspetto la tua risposta: scrivila qui sotto")
            main.removeCallbacks(expirePending)
            main.postDelayed(expirePending, 3 * 60_000L)
        } else out.onStatus(idleStatus())
    }

    /** Una frase scritta arrivata dalla schermata (o, se SAM era spento, insieme alla sua accensione). */
    private fun typedArrived(t: String) {
        if (state == St.LOADING) {
            if (t.isNotBlank()) { out.onLine("Tu", Skills.decode(t)?.let { Skills.shown(it) } ?: t.trim()); out.onLine("Info", "Sto ancora partendo: appena sono pronto rispondo."); typedWhenReady = t }
        } else onTyped(t)
    }

    /** Hai scritto a SAM: è come se l'avessi detto, ma risponde solo sullo schermo. */
    private fun onTyped(raw: String) {
        Skills.decode(raw)?.let { runSkill(it); return }
        val text = raw.trim()
        if (text.isEmpty()) return
        if (enrolling) {
            out.onLine("Info", "Sto imparando la tua voce: le frasi vanno ripetute a voce. Per lasciar perdere di' «annulla».")
            return
        }
        main.removeCallbacks(expirePending); main.removeCallbacks(silenceTimeout)
        quiet = true
        if (dialogue) endDialogue(null)
        if (state == St.SPEAKING || state == St.THINKING) interruptQuietly()   // scrivi mentre risponde: lo zittisce
        stopCollecting()
        resumeMic()
        when (val h = Hearing.decide(text, true, brain.hasPending)) {
            Heard.Ignore, Heard.WakeOnly -> {
                out.onLine("Tu", text)
                out.onLine("Info", "Scrivimi un comando o una domanda, per esempio: che ore sono?")
                finishQuiet()
            }
            Heard.Stop -> {
                out.onLine("Tu", text)
                if (chatMode) endChat("Ok, fine della conversazione.")
                else { brain.cancelPending(); interruptQuietly(); out.onLine("SAM", "Ok."); finishQuiet() }
            }
            is Heard.Command -> {
                sessionActive = true
                chatQuiet = 0
                process(h.text)
            }
        }
    }

    private val emoji = Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val think = Regex("(?s)<think>.*?</think>")
    private fun clean(t: String): String =
        emoji.replace(think.replace(t, ""), "")
            .replace("<think>", "").replace("</think>", "")
            .replace(Regex("[*#_`>|~\\[\\]]"), "")
            .replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------ util
    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sam:voice").apply {
            setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "SAM", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CH)
            .setContentTitle("${Identity.name} è in ascolto")
            .setContentText("Di' “${Identity.name}” per parlare")
            .setSmallIcon(R.drawable.ic_stat_sam)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NID, notif)
            try { nm.cancel(NID_STOPPED) } catch (e: Exception) {}
        } catch (e: Exception) {
            // Android può rifiutare il primo piano (es. riavvio in background o permesso microfono mancante):
            // una notifica dice di toccarla per riattivare SAM (toccandola, Android lo lascia ripartire)
            out.onStatus("Apri SAM per riattivarlo.")
            notifyUser(NID_STOPPED, "SAM si è fermato", "Android l'ha chiuso per liberare memoria: tocca qui per riattivarlo.")
            stopSelf()
        }
    }

    /** Una notifica che, toccata, apre SAM (da lì Android lascia ripartire l'ascolto e il microfono). */
    private fun notifyUser(id: Int, title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_STOPPED, "SAM fermo", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(id, Notification.Builder(this, CH_STOPPED)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_sam)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {}
    }

    companion object {
        const val CH = "sam_voice"
        const val NID = 42
        private const val CH_STOPPED = "sam_fermo"
        private const val NID_STOPPED = 43
        private const val NID_MIC = 44
        const val ACTION_WAKE = "com.essentialapp.sam.WAKE"
        const val ACTION_STOP = "com.essentialapp.sam.STOP"
        const val ACTION_RELOAD_BRAIN = "com.essentialapp.sam.RELOAD_BRAIN"
        const val ACTION_DOCUMENT = "com.essentialapp.sam.DOCUMENT"
        const val ACTION_PHOTO = "com.essentialapp.sam.PHOTO"
        const val ACTION_MEDIA = "com.essentialapp.sam.MEDIA"
        const val ACTION_MEDIA_ALLOWED = "com.essentialapp.sam.MEDIA_ALLOWED"
        const val EXTRA_DURATION = "duration"
        const val ACTION_ENROLL = "com.essentialapp.sam.ENROLL"
        const val ACTION_VOICE_CHANGED = "com.essentialapp.sam.VOICE_CHANGED"
        const val ACTION_ENCYCLOPEDIA = "com.essentialapp.sam.ENCYCLOPEDIA"
        const val ACTION_VOICE_SETTINGS = "com.essentialapp.sam.VOICE_SETTINGS"
        const val ACTION_DOWNLOADS = "com.essentialapp.sam.DOWNLOADS"
        const val ACTION_TYPED = "com.essentialapp.sam.TYPED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_ERROR = "error"

        @Volatile var ui: UiListener? = null
        @Volatile var running = false; private set
        /** il servizio acceso (per passargli le frasi scritte senza riavviarlo in primo piano) */
        @Volatile private var self: VoiceService? = null
        /** l'ultima scritta di stato e l'ultimo aspetto della sfera, per la schermata che si riapre */
        @Volatile var lastStatus: String = "Di' “SAM” quando vuoi"; private set
        @Volatile var lastMode: OrbView.Mode = OrbView.Mode.IDLE; private set
        private val shown = ArrayList<Pair<String, String>>()
        /** le righe sullo schermo si salvano su file: se il telefono chiude SAM, riaprendolo si rivedono */
        private var shownFile: File? = null
        private var shownLoaded = false
        private val ui0 = Handler(Looper.getMainLooper())
        private val saveShown = Runnable { saveShownNow(background = true) }

        private fun keepLine(speaker: String, text: String, replace: Boolean) {
            synchronized(shown) {
                val i = if (replace) shown.indexOfLast { it.first == speaker } else -1
                if (i >= 0) shown[i] = speaker to text else shown.add(speaker to text)
                while (shown.size > 60) shown.removeAt(0)
            }
            ui0.removeCallbacks(saveShown); ui0.postDelayed(saveShown, 2000)
        }

        /** Carica (una volta) le righe salvate l'ultima volta. */
        fun ensureShown(ctx: Context) = synchronized(shown) {
            if (shownLoaded) return@synchronized
            shownLoaded = true
            val f = File(ctx.filesDir, "schermo.tsv")
            shownFile = f
            try { if (f.exists()) shown.addAll(0, Transcript.parse(f.readText(Charsets.UTF_8))) } catch (e: Exception) {}
            while (shown.size > 60) shown.removeAt(0)
        }

        private fun saveShownNow(background: Boolean = false) {
            val f = shownFile ?: return
            val text = synchronized(shown) { Transcript.toText(shown) }
            if (background) Thread { writeSafely(f, text) }.start() else writeSafely(f, text)
        }

        /** Le ultime righe della conversazione (per ridisegnarle quando la schermata si riapre, anche dopo un riavvio). */
        fun recentLines(ctx: Context): List<Pair<String, String>> { ensureShown(ctx); return synchronized(shown) { shown.toList() } }

        /** Scrittura sicura: prima un file temporaneo, poi lo scambio (se il telefono si spegne a metà, il file vecchio resta). */
        @Synchronized
        fun writeSafely(f: File, text: String) {
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(f)) { f.writeText(text, Charsets.UTF_8); tmp.delete() }
            } catch (e: Exception) {}
        }

        /** quando si è aperta la schermata della fotocamera di SAM (per capire se Android l'ha bloccata) */
        @Volatile var lookOpenedAt = 0L
        /** ricordi cambiati dalla configurazione (cancellati o ripristinati): il cervello IA si aggiorna subito */
        @Volatile var memoryEdited = false

        private fun intent(ctx: Context, action: String? = null) =
            Intent(ctx, VoiceService::class.java).also { if (action != null) it.action = action }

        fun start(ctx: Context) { ctx.startForegroundService(intent(ctx)) }
        fun wakeNow(ctx: Context) { ctx.startForegroundService(intent(ctx, ACTION_WAKE)) }
        /** Una frase scritta: SAM la tratta come se l'avessi detta, e risponde solo sullo schermo. */
        fun typed(ctx: Context, text: String) {
            // SAM è già acceso: la frase gli si passa direttamente. Prima passava da startForegroundService, che a ogni
            // messaggio rimetteva SAM "in primo piano col microfono" e HyperOS mostrava di nuovo l'avviso del microfono
            val s = self
            if (s != null && running) { s.main.post { s.typedArrived(text) }; return }
            ctx.startForegroundService(intent(ctx, ACTION_TYPED).putExtra(EXTRA_TEXT, text))
        }
        fun reloadBrain(ctx: Context) { ctx.startForegroundService(intent(ctx, ACTION_RELOAD_BRAIN)) }
        fun stop(ctx: Context) { if (running) ctx.startService(intent(ctx, ACTION_STOP)) }

        /** Un documento condiviso con SAM (già copiato nella sua memoria). */
        fun openDocument(ctx: Context, path: String, name: String, mime: String?) {
            val i = intent(ctx, ACTION_DOCUMENT).putExtra(EXTRA_PATH, path).putExtra(EXTRA_NAME, name).putExtra(EXTRA_MIME, mime)
            // SAM già acceso: il file gli arriva direttamente (senza rimetterlo "in primo piano col microfono": niente avviso)
            val s = self
            if (s != null && running) { s.main.post { if (s.state == St.LOADING) s.docWhenReady = i else s.onDocumentIntent(i) }; return }
            ctx.startForegroundService(i)
        }

        /** La foto scattata dalla schermata della vista ([path] null = non riuscita, con il motivo in [error]). */
        fun photoTaken(ctx: Context, path: String?, error: String?) {
            if (!running) return
            ctx.startForegroundService(intent(ctx, ACTION_PHOTO).putExtra(EXTRA_PATH, path).putExtra(EXTRA_ERROR, error))
        }

        /** 1.23: il pannello della mente (markdown): cosa sa, impegni, abitudini, diario. Anche con SAM spento (dal file). */
        fun mindReport(ctx: Context): String {
            self?.let { s -> return s.mind.report(s.mem.memos(), s.mem.ownerName(), System.currentTimeMillis()) }
            val m = Mind()
            try { File(ctx.filesDir, "mente.txt").takeIf { it.exists() }?.let { m.load(it.readText(Charsets.UTF_8)) } } catch (e: Exception) {}
            val mem = FileMemory(ctx)
            return m.report(mem.memos(), mem.ownerName(), System.currentTimeMillis())
        }

        /** «Riordina adesso» dal pannello: il sonno della mente subito (le conversazioni finite vanno nel diario). */
        fun sleepNow(ctx: Context): Int = self?.let { s -> try { s.mind.sleep(s.talk.before(null, within = Talk.RECALL_MS), s.mem.ownerName(),
            System.currentTimeMillis(), quietMs = 0L) } catch (e: Throwable) { 0 } } ?: 0

        /** 2.0: dai pulsanti dello Stato e della configurazione (stesse cose che a voce). Fuori dal thread principale. */
        fun repairFromUi(ctx: Context): String {
            val s = self
            if (s == null || !running) {
                val now = System.currentTimeMillis(); val done = ArrayList<String>()
                val f = PhoneCare.clean(ctx, now, olderMs = 3_600_000L); if (f > 0) done.add("ho liberato ${Health.size(f)} di file temporanei")
                if (Health.fixMemoryFile(File(ctx.filesDir, "ricordi.tsv")) > 0) { FileMemory.dropCache(); done.add("ho tolto le righe rovinate dai ricordi") }
                if (PhoneCare.backups(ctx).last()?.let { now - it.at > 3 * 86_400_000L } != false && PhoneCare.backups(ctx).make(now, Backups.MANUAL) != null) done.add("ho fatto una copia di sicurezza")
                return if (done.isEmpty()) "Non c'era niente che potessi riparare da fermo: apri SAM e riprova." else "Fatto: " + done.joinToString("; ") + "."
            }
            // le riparazioni toccano microfono e cervello: si fanno sul thread principale del servizio
            val task = java.util.concurrent.FutureTask { s.repairNow() }
            s.main.post(task)
            val (done, left) = try { task.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) { return "Non ci sono riuscito: riprova tra poco." }
            return ((if (done.isNotEmpty()) "Fatto: " + done.joinToString("; ") + ". " else "") +
                (if (left.isNotEmpty()) "Resta da fare a te: " + left.joinToString(" ") { it.trim().trimEnd('.') + "." } else if (done.isEmpty()) "Non c'era niente da riparare." else "")).trim()
        }
        fun backupFromUi(ctx: Context): String {
            self?.let { s -> val task = java.util.concurrent.FutureTask { s.saveNow() }; s.main.post(task); try { task.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) {} }
            val now = System.currentTimeMillis(); val b = PhoneCare.backups(ctx)
            val i = b.make(now, Backups.MANUAL) ?: return "Non sono riuscito a fare la copia: controlla lo spazio."
            PhoneCare.journal(ctx).did("copia di sicurezza chiesta da te", now)
            return "Copia fatta: è quella ${b.whenText(i, now)}."
        }
        fun restoreFromUi(ctx: Context, i: Backups.Info): String {
            self?.let { s ->
                val task = java.util.concurrent.FutureTask { s.restoreBackup(i) }
                s.main.post(task)
                return try { task.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) { "Non ci sono riuscito: riprova tra poco." }
            }
            val now = System.currentTimeMillis(); val b = PhoneCare.backups(ctx)
            if (b.restore(i, ctx.filesDir, now).isEmpty()) return "Quella copia è rovinata: non ho toccato niente."
            FileMemory.dropCache()
            return "Fatto: sono tornato alla copia ${b.whenText(i, now)}."
        }

        /** 1.22: il nome è cambiato dalle impostazioni: la notifica si aggiorna. */
        fun nameChanged(ctx: Context) { if (running) try { self?.updateNotification() } catch (e: Exception) {} }

        /** 1.21: la foto o il video fatto dalla fotocamera di SAM è nella Galleria ([saved] null = non riuscito: [error]). */
        fun mediaTaken(ctx: Context, saved: FileOut.Saved?, durationMs: Long, error: String?) {
            if (!running) return
            ctx.startForegroundService(intent(ctx, ACTION_MEDIA).putExtra(EXTRA_PATH, saved?.uri?.toString()).putExtra(EXTRA_MIME, saved?.mime)
                .putExtra(EXTRA_NAME, saved?.name).putExtra(EXTRA_DURATION, durationMs).putExtra(EXTRA_ERROR, error))
        }

        /** 1.21: hai risposto alla richiesta del permesso per foto e video ([ok] = concesso). */
        fun mediaAllowed(ctx: Context, ok: Boolean) {
            if (running) ctx.startForegroundService(intent(ctx, ACTION_MEDIA_ALLOWED).putExtra(EXTRA_ENGINE, ok))
        }

        /** Dalla configurazione: comincia a imparare la tua voce (accende SAM se serve). */
        fun enroll(ctx: Context) { ctx.startForegroundService(intent(ctx, ACTION_ENROLL)) }

        /** Dalla configurazione: l'impronta della voce è cambiata (cancellata), il servizio la rilegge. */
        fun voiceChanged(ctx: Context) { if (running) ctx.startService(intent(ctx, ACTION_VOICE_CHANGED)) }
        /** L'enciclopedia è cambiata nella configurazione: SAM (se è acceso) la riapre. */
        fun encyclopediaChanged(ctx: Context) { if (running) ctx.startService(intent(ctx, ACTION_ENCYCLOPEDIA)) }
        /** La voce è cambiata nella configurazione ([engine] = anche il motore di sintesi): SAM (se è acceso) la riapplica. */
        fun voiceSettingsChanged(ctx: Context, engine: Boolean) {
            if (running) ctx.startService(intent(ctx, ACTION_VOICE_SETTINGS).putExtra(EXTRA_ENGINE, engine))
        }
        /** Uno scaricamento degli aggiornamenti è finito (SAM acceso): lo sistema lui. */
        fun downloadsDone(ctx: Context) { if (running) try { ctx.startService(intent(ctx, ACTION_DOWNLOADS)) } catch (e: Exception) {} }
    }
}
