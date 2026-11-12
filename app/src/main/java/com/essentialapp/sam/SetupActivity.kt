package com.essentialapp.sam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Configurazione guidata: permessi, nome, voce italiana, cervello IA scelto in base al telefono, avvio. */
class SetupActivity : Activity() {

    private lateinit var nameField: EditText
    private lateinit var nameStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var voiceBar: ProgressBar
    private lateinit var voiceBtn: Button
    private lateinit var phoneInfo: TextView
    private lateinit var advice: TextView
    private lateinit var downloadBtn: Button
    private lateinit var brainStatus: TextView
    private lateinit var brainBar: ProgressBar
    private lateinit var brainBtn: Button
    private lateinit var startBtn: Button
    private lateinit var permStatus: TextView
    private lateinit var memStatus: TextView
    private lateinit var voiceIdStatus: TextView
    private lateinit var onlyMeBtn: Button
    private lateinit var encStatus: TextView
    private lateinit var encPickBtn: Button
    private lateinit var encRemoveBtn: Button
    private lateinit var encGetBtn: Button
    private lateinit var brainList: LinearLayout
    private lateinit var ttsStatus: TextView
    private lateinit var engineBtn: Button
    private lateinit var updStatus: TextView
    private lateinit var autoBtn: Button
    private lateinit var careStatus: TextView
    private lateinit var careAutoBtn: Button
    private var todo: TextView? = null
    private var topStart: Button? = null
    /** la sintesi vocale della configurazione: per sentire le voci prima di sceglierle */
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private val dp get() = resources.displayMetrics.density
    private val PICK_BRAIN = 7
    private val PICK_VOICE = 8
    private val SAVE_MEM = 9
    private val LOAD_MEM = 10
    private val PICK_ZIM = 11
    private var recommended: BrainModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.0: ogni sezione è una scheda; [root] è la scheda in cui si sta scrivendo
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding((14 * dp).toInt(), pad(), (14 * dp).toInt(), pad())
        }
        var root: LinearLayout = page

        title("Configura ${Identity.name}", root)
        note("Tutto funziona sul telefono: ascoltare, capire e rispondere. Internet SAM lo usa solo col Wi-Fi, per scaricare gli " +
             "aggiornamenti (la Wikipedia nuova e il cervello nuovo): le tue domande e le risposte non ci passano mai.", root)

        // 2.0: in cima, cosa manca per partire (si aggiorna da sola)
        root = card("Per cominciare", page)
        todo = status(root)
        topStart = btn("Avvia ${Identity.name}") { if (ModelStore.voskReady(this)) { startActivity(Intent(this, MainActivity::class.java)); finish() } }.also { accent(it) }
        root.addView(topStart, w())

        root = card("1 · Permessi", page)
        note("Microfono, contatti, telefono, SMS, notifiche e fotocamera: servono per capirti, eseguire i comandi e guardare quando glielo chiedi.", root)
        root.addView(btn("Concedi i permessi") { askPermissions() }, w())

        root = card("2 · Il tuo nome", page)
        note("SAM te lo chiede anche a voce la prima volta che si accende. Qui puoi scriverlo o correggerlo.", root)
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nameField = EditText(this).apply {
            hint = "Come ti chiami?"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 130, 150))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { saveName(); true } else false }
        }
        nameRow.addView(nameField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(btn("Salva") { saveName() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(nameRow, w())
        nameStatus = status(root)

        // 1.22: il nome dell'assistente (di base SAM): lo chiami così, si presenta così, lo vedi scritto così
        root = card("Il nome dell'assistente", page)
        note("Di base si chiama SAM. Puoi dargli il nome che vuoi (una o due parole): lo chiamerai con quel nome. Puoi anche dirglielo " +
             "a voce: «da ora ti chiami Jarvis». Dopo il cambio, digli il nome una volta: impara come lo sente.", root)
        val asstRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val asstField = EditText(this).apply {
            hint = "SAM"; setText(if (Identity.isDefault) "" else Identity.name)
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 130, 150))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
        }
        val asstStatus = TextView(this)
        fun saveAssistant() {
            val raw = asstField.text.toString().trim()
            val n = if (raw.isEmpty()) Identity.DEFAULT else Identity.clean(raw)
            if (n == null) { asstStatus.text = "«$raw» non va bene come nome: una o due parole, solo lettere."; return }
            IdentityStore.save(this, n, emptyList())
            VoiceService.nameChanged(this)
            asstStatus.text = if (Identity.isDefault) "✓ Si chiama SAM." else "✓ Da ora si chiama ${Identity.name}: chiamalo così. La prima volta digli il nome " +
                "dopo «impara come dico il tuo nome», così impara come lo sente."
        }
        asstRow.addView(asstField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        asstRow.addView(btn("Salva") { saveAssistant() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(asstRow, w())
        root.addView(btn("Metti l'icona con questo nome nella schermata Home") {
            asstStatus.text = if (IdentityStore.pinShortcut(this)) "Conferma sul telefono: l'icona «${Identity.name}» va nella schermata Home. " +
                "Quella vecchia (SAM) lasciala o mettila in una cartella: su Xiaomi trascinarla via può voler dire disinstallare l'app." else "Questo telefono non lascia aggiungere icone: il nome sotto l'icona resta SAM."
        }, w())
        asstStatus.apply { setTextColor(Color.rgb(200, 230, 210)); textSize = 14f; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        root.addView(asstStatus, w())

        root = card("3 · Voce italiana (obbligatoria, una volta sola)", page)
        note("Seleziona INSIEME i due file SAM-voce-italiana.part1 e .part2 (tieni premuto sul primo, " +
             "poi tocca il secondo). Va bene anche lo zip intero vosk-model-small-it-0.22.zip.", root)
        voiceBtn = btn("Importa la voce italiana") { pickVoice() }
        root.addView(voiceBtn, w())
        voiceBar = bar(root)
        voiceStatus = status(root)

        root = card("4 · Cervello IA, scelto per il tuo telefono", page)
        note("I comandi funzionano anche senza. Per chiacchierare SAM usa un cervello che gira sul telefono: " +
             "lo scegliamo in base alla memoria e al processore.", root)
        phoneInfo = status(root)
        advice = note("", root)
        downloadBtn = btn("Scarica il cervello consigliato") { downloadRecommended() }
        root.addView(downloadBtn, w())
        note("Lo scarica SAM col Wi-Fi (mai con i dati) e appena è pronto lo controlla e lo usa. Se preferisci, puoi " +
             "scaricarlo con il browser e importarlo qui sotto.", root)
        root.addView(btn("Scaricalo con il browser") { downloadWithBrowser() }, w())
        brainBtn = btn("Importa il file del cervello (.gguf, .litertlm, .task)") { pickBrain() }
        root.addView(brainBtn, w())
        brainBar = bar(root)
        brainStatus = status(root)
        brainList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(brainList, w())

        // 2.1: i cervelli online (Groq, Gemini, OpenRouter…), con più account che si danno il cambio da soli
        root = card("4+ · Cervelli online (facoltativi, più account)", page)
        note("Oltre al cervello del telefono SAM può usare i cervelli online con i tuoi account (chiavi API). Li prova " +
             "in ordine: quando uno finisce i token passa da solo al successivo e, se finiscono tutti o manca internet, " +
             "risponde il cervello del telefono.", root)
        val onlineStatus = status(root)
        AccountStore.init(this)
        val ready = AccountStore.usable().size
        onlineStatus.text = if (AccountStore.accounts.isEmpty()) "Nessun account online: SAM usa solo il cervello del telefono."
            else "${AccountStore.accounts.size} account, $ready pronti · ${AccountStore.mode.label}"
        root.addView(btn("Gestisci gli account online") {
            startActivity(android.content.Intent(this, OnlineActivity::class.java))
        }.also { accent(it) }, w())

        root = card("5 · Permessi speciali (importanti su Xiaomi)", page)
        note("Servono perché SAM, mentre lavora in sottofondo, possa aprire le app, chiamare, scrivere su " +
             "WhatsApp, andare su internet, leggere le notifiche e regolare lo schermo. ✓ = attivo, ✗ = da attivare.", root)
        permStatus = status(root)
        if (Hardware.isXiaomi())
            root.addView(btn("Xiaomi: finestre pop-up in background (apre app, chiamate, WhatsApp, internet)") { openXiaomiPerms() }, w())
        root.addView(btn("Accesso alle notifiche") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, w())
        root.addView(btn("Servizio di accessibilità (apre app in sottofondo, invia su WhatsApp, chiude app)") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, w())
        root.addView(btn("Modifica impostazioni di sistema (luminosità)") { openWithPkg(Settings.ACTION_MANAGE_WRITE_SETTINGS) }, w())
        root.addView(btn("Mostra sopra altre app (apre app in background)") { openWithPkg(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }, w())
        root.addView(btn("Togli il risparmio batteria") { askNoBatteryLimits() }, w())

        root = card("6 · Ricordi di SAM", page)
        note("SAM ricorda per sempre quello che gli chiedi («SAM, ricordati che…»), impara da solo quello che racconti di te " +
             "e le scorciatoie che gli insegni. Li cancella solo quando glielo chiedi: a voce («SAM, dimentica…») oppure qui.", root)
        memStatus = status(root)
        root.addView(btn("Vedi e cancella i ricordi") { showMemories() }, w())
        root.addView(btn("Salva una copia dei ricordi") { saveMemories() }, w())
        root.addView(btn("Ripristina i ricordi da una copia") { loadMemories() }, w())
        note("Gli aggiornamenti di SAM non toccano i ricordi. La copia (un file sul telefono) serve solo se reinstalli SAM o cambi telefono.", root)

        root = card("7 · La tua voce", page)
        note("SAM può imparare la tua voce: gli ripeti sei frasi e da lì ti riconosce, e migliora con l'uso. " +
             "Poi, se vuoi, risponde solo a te (gli altri e la TV li ignora). Se un giorno non ti riconosce " +
             "(raffreddore, rumore, telefono più lontano), tocca Parla: ti ascolta lo stesso e ti propone di imparare " +
             "di nuovo la tua voce. Non è una serratura: una voce molto simile o una registrazione possono ingannarlo.", root)
        voiceIdStatus = status(root)
        root.addView(btn("Insegna la tua voce a SAM") { teachVoice() }, w())
        onlyMeBtn = btn("") { toggleOnlyMe() }
        root.addView(onlyMeBtn, w())
        root.addView(btn("Dimentica la mia voce") { forgetVoice() }, w())

        root = card("8 · Enciclopedia (facoltativa)", page)
        note("Wikipedia in italiano sul telefono, senza internet: a domande come \"chi era Garibaldi?\" o \"dove si trova Matera?\" " +
             "SAM risponde partendo dalla voce dell'enciclopedia invece che dalla memoria del cervello, e \"cosa dice l'enciclopedia " +
             "su…\" te la legge. Il file è quello di Kiwix (circa 10 GB senza immagini): lo scarica SAM col Wi-Fi, oppure lo scegli tu " +
             "se ce l'hai già. Una volta alla settimana SAM guarda se ce n'è una più nuova e la mette al posto della vecchia da solo.", root)
        encStatus = status(root)
        encGetBtn = btn("Scarica Wikipedia in italiano (Wi-Fi)") { downloadEncyclopedia(light = false) }
        root.addView(encGetBtn, w())
        root.addView(btn("Scarica la versione leggera (solo l'inizio di ogni voce: a SAM basta)") { downloadEncyclopedia(light = true) }, w())
        encPickBtn = btn("Scegli il file dell'enciclopedia (.zim)") { pickEncyclopedia() }
        root.addView(encPickBtn, w())
        encRemoveBtn = btn("Togli l'enciclopedia") { removeEncyclopedia() }
        root.addView(encRemoveBtn, w())

        root = card("9 · La voce di SAM", page)
        note("SAM parla con le voci italiane del telefono che funzionano senza internet. Scegli quella che ti piace di più e " +
             "regola velocità e tono. Si fa anche a voce: «SAM, cambia voce», «parla più piano», «voce più grave».", root)
        ttsStatus = status(root)
        root.addView(btn("Scegli la voce (le senti una per una)") { chooseTtsVoice() }, w())
        root.addView(pair(btn("Più lenta") { tuneVoice("rate", -1) }, btn("Più veloce") { tuneVoice("rate", 1) }), w())
        root.addView(pair(btn("Più grave") { tuneVoice("pitch", -1) }, btn("Più acuta") { tuneVoice("pitch", 1) }), w())
        root.addView(pair(btn("Prova") { sample() }, btn("Normale") { tuneVoice("reset", 0) }), w())
        engineBtn = btn("Motore della sintesi vocale") { chooseEngine() }
        root.addView(engineBtn, w())

        root = card("10 · Aggiornamenti (solo col Wi-Fi)", page)
        note("Internet SAM lo usa solo qui, e solo col Wi-Fi: per scaricare la Wikipedia nuova (da Kiwix) e il cervello nuovo " +
             "(da Hugging Face). Le tue domande e le risposte non passano mai da internet. L'enciclopedia nuova la mette al posto " +
             "della vecchia da solo; il cervello nuovo prima te lo chiede. A voce: «SAM, ci sono aggiornamenti?».", root)
        updStatus = status(root)
        root.addView(btn("Controlla adesso") { checkUpdatesNow() }, w())
        autoBtn = btn("") { toggleAuto() }
        root.addView(autoBtn, w())

        // ---- 2.0: manutenzione
        root = card("11 · Manutenzione", page)
        note("${Identity.name} si controlla da solo ogni giorno: fa una copia di sicurezza di ricordi e mente, pulisce i suoi file temporanei e guarda che " +
             "tutto funzioni. A voce: «controllati», «riparati», «cosa è andato storto?», «ripristina la copia di ieri».", root)
        careStatus = status(root)
        root.addView(pair(btn("Controlla adesso") { runCare("check") }, btn("Ripara") { runCare("repair") }), w())
        root.addView(pair(btn("Fai una copia") { runCare("backup") }, btn("Ripristina…") { pickBackup() }), w())
        root.addView(pair(btn("Registro") { showRegister() }, btn("Fai pulizia") { runCare("clean") }), w())
        careAutoBtn = btn("") { PhoneCare.setAuto(this, !PhoneCare.autoOn(this)); refresh() }
        root.addView(careAutoBtn, w())

        // ---- 2.0: informazioni
        root = card("Informazioni", page)
        note("${Identity.name}, versione ${PhoneCare.version(this)}. Assistente vocale che gira tutto sul telefono.", root)
        note("Novità:\n" + About.NEWS.filter { !it.contains("computer") }.joinToString("\n") { "• $it" }, root)
        note("Riservatezza: quello che dici resta sul telefono. Internet serve solo col Wi-Fi per gli aggiornamenti, e quando chiedi tu di cercare sul web.", root)

        root = card("12 · Avvia", page)
        startBtn = btn("Avvia ${Identity.name}") {
            if (ModelStore.voskReady(this)) { startActivity(Intent(this, MainActivity::class.java)); finish() }
        }
        accent(startBtn)
        root.addView(startBtn, w())

        val sv = ScrollView(this); sv.addView(page); sv.setBackgroundColor(BG)
        // spazio per barra di stato, tasti di navigazione e tastiera: l'ultimo pulsante resta raggiungibile
        sv.clipToPadding = false
        sv.padForSystemBars(0, 0, 0, 0)
        setContentView(sv)

        FileMemory(this).ownerName()?.let { nameField.setText(it) }
        startPreviewTts()
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        tts = null
        super.onDestroy()
    }

    private fun refresh() {
        startBtn.isEnabled = ModelStore.voskReady(this)
        // 2.0: i passi per cominciare
        run {
            val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val steps = listOf("Permesso del microfono" to mic, "Voce italiana" to ModelStore.voskReady(this),
                "Cervello IA (facoltativo: senza, solo comandi)" to (ModelStore.llmFile(this) != null), "Il tuo nome (facoltativo)" to (FileMemory(this).ownerName() != null))
            val done = steps.count { it.second }
            todo?.text = steps.joinToString("\n") { (if (it.second) "✓  " else "○  ") + it.first } +
                "\n\n" + (if (mic && ModelStore.voskReady(this)) (if (done == steps.size) "Tutto pronto." else "Puoi già partire; il resto quando vuoi.") else "Fai i passi 1 e 3 qui sotto, poi parti.")
            topStart?.isEnabled = mic && ModelStore.voskReady(this)
        }
        careAutoBtn.text = if (PhoneCare.autoOn(this)) "Non fare la manutenzione da solo" else "Fai la manutenzione da solo ogni giorno"
        if (careStatus.text.isNullOrEmpty()) Thread { val cs = try { PhoneCare.checks(this) } catch (e: Throwable) { emptyList() }
            val b = PhoneCare.backups(this); val now = System.currentTimeMillis()
            val t = "Stato: " + Health.headline(cs) + ".  " + (b.last()?.let { "Ultima copia: " + b.whenText(it, now).removePrefix("di ").removePrefix("del ") + "." } ?: "Nessuna copia ancora.")
            runOnUiThread { careStatus.text = t } }.start()
        nameStatus.text = FileMemory(this).ownerName()?.let { "SAM ti chiama $it ✓" } ?: "Nome non ancora salvato."
        if (!voiceBar.isShown)
            voiceStatus.text = if (ModelStore.voskReady(this)) "Voce italiana pronta ✓" else "Voce non ancora importata."

        // hardware -> cervello consigliato
        val specs = try { Hardware.specs(this) } catch (e: Throwable) { null }
        recommended = specs?.let { Brains.recommend(it) }
        phoneInfo.text = specs?.let { "Il tuo telefono: " + Brains.describe(it) } ?: ""
        val rec = recommended
        if (specs == null || rec == null) {
            advice.text = "Questo telefono ha poca memoria per un cervello IA: SAM userà solo i comandi."
            downloadBtn.visibility = View.GONE
        } else {
            val altri = Brains.compatible(specs).filter { it != rec }
            advice.text = "Consigliato: ${rec.name} (${Brains.gb(rec.bytes)}), ${rec.note}." +
                (if (altri.isNotEmpty()) " Compatibili anche: " + altri.joinToString(", ") { "${it.name} (${Brains.gb(it.bytes)})" } + "." else "") +
                (if (specs.freeBytes > 0 && specs.freeBytes < rec.bytes * 2.2) " Libera un po' di spazio prima di scaricarlo." else "")
            downloadBtn.text = "Scarica ${rec.name} (${Brains.gb(rec.bytes)})"
            downloadBtn.visibility = View.VISIBLE
        }

        permStatus.text = permissionsText()
        memStatus.text = memorySummary()
        val vs = VoiceStore(this)
        val known = vs.load() != null
        voiceIdStatus.text = if (!known) "SAM non conosce ancora la tua voce."
            else "SAM conosce la tua voce ✓" + (if (vs.onlyOwner) "  ·  risponde solo a te" else "  ·  risponde a tutti")
        onlyMeBtn.text = if (vs.onlyOwner) "Rispondi a tutti" else "Rispondi solo a me"
        onlyMeBtn.isEnabled = known
        refreshEncyclopedia()
        refreshVoice()
        updStatus.text = Updater.setupText(this)
        autoBtn.text = if (Updater.autoOn(this)) "Non aggiornarti da solo" else "Aggiornati da solo (una volta alla settimana, col Wi-Fi)"
        refreshBrains()

        if (!brainBar.isShown) {
            val f = ModelStore.llmFile(this)
            brainStatus.text = when {
                f == null -> "Nessun cervello importato: SAM eseguirà i comandi ma non chiacchiererà."
                else -> {
                    val known = Brains.byFile(f.name)
                    val nome = known?.name ?: f.nameWithoutExtension
                    val fit = specs?.let { Brains.fit(f.length(), it) } ?: Brains.Fit.OK
                    "Cervello installato: $nome (${Brains.gb(f.length())}) ✓" + when {
                        fit == Brains.Fit.HEAVY -> "  È pesante per questo telefono: può essere lento."
                        rec != null && known == rec -> "  È quello consigliato per il tuo telefono."
                        rec != null && known != null && Brains.rank(known) > Brains.rank(rec) ->
                            "  Funziona, ma per conversare meglio ti consiglio ${rec.name}."
                        else -> ""
                    }
                }
            }
        }
    }

    // ---- nome del proprietario ------------------------------------------------
    private fun saveName() {
        val raw = nameField.text.toString().trim()
        if (raw.isEmpty()) { nameStatus.text = "Scrivi il tuo nome."; return }
        val name = raw.split(Regex("\\s+")).take(3).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        FileMemory(this).setOwnerName(name)
        nameField.setText(name)
        nameStatus.text = "SAM ti chiama $name ✓"
    }

    // ---- voce italiana: importata da file (anche in più parti) -----------
    private fun pickVoice() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, PICK_VOICE) }
        catch (e: Exception) { voiceStatus.text = "Selettore file non disponibile: ${e.message}" }
    }

    private fun importVoice(uris: List<Uri>) {
        voiceBtn.isEnabled = false
        voiceBar.visibility = View.VISIBLE; voiceBar.progress = 0
        voiceStatus.text = "Importo la voce italiana (${uris.size} file)…"
        Thread {
            try {
                ModelStore.importVosk(this, uris) { p -> runOnUiThread { voiceBar.progress = p } }
                runOnUiThread {
                    voiceBar.visibility = View.GONE; voiceBtn.isEnabled = true
                    voiceStatus.text = "Voce italiana pronta ✓  Puoi cancellare i file importati."
                    refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    voiceBar.visibility = View.GONE; voiceBtn.isEnabled = true
                    voiceStatus.text = "Import non riuscito: ${e.message}"
                }
            }
        }.start()
    }

    // ---- cervello IA ------------------------------------------------------------
    /** Il cervello consigliato lo scarica SAM, col Wi-Fi; appena è pronto lo controlla e lo usa. */
    private fun downloadRecommended() {
        val rec = recommended ?: return
        if (!Updater.onWifi(this)) {
            brainStatus.text = "SAM scarica solo col Wi-Fi: collegati al Wi-Fi e tocca di nuovo. Oppure scaricalo con il browser."
            return
        }
        downloadBtn.isEnabled = false
        brainStatus.text = "Preparo lo scaricamento…"
        Thread {
            val msg = try { Updater.startBrainDownload(this, rec, switchWhenDone = true) } catch (e: Throwable) { "Non riesco a scaricarlo: ${e.message}" }
            runOnUiThread {
                downloadBtn.isEnabled = true
                brainStatus.text = "$msg L'avanzamento lo vedi nella tendina delle notifiche e qui sotto, al punto 10."
                updStatus.text = Updater.setupText(this)
            }
        }.start()
    }

    /** Come prima della 1.10: il browser scarica il file, poi lo importi tu. */
    private fun downloadWithBrowser() {
        val rec = recommended ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rec.url))) }
        catch (e: Exception) { brainStatus.text = "Non trovo un browser per scaricarlo: ${e.message}" }
    }

    /** I cervelli sul telefono, se sono più d'uno: quale usare e quali togliere. */
    private fun refreshBrains() {
        brainList.removeAllViews()
        val all = SamApp.llmFiles(this)
        if (all.isEmpty()) return
        val cur = SamApp.llmFile(this)
        // 1.22: la lista c'è sempre, anche con un cervello solo; quelli importati a mano come quelli scaricati
        brainList.addView(TextView(this).apply { text = "I cervelli sul telefono:"; setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt()) }, w())
        for (f in all) {
            val name = (Brains.byFile(f.name)?.name ?: f.nameWithoutExtension) + if (Brains.byFile(f.name) == null) " · caricato da te" else ""
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                text = "$name (${Brains.gb(f.length())})" + if (f.name == cur?.name) "  · in uso ✓" else ""
                setTextColor(Color.rgb(200, 230, 210)); textSize = 14f
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (f.name != cur?.name && all.size >= 1) {
                row.addView(btn("Usa") { useBrain(f) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(btn("Togli") { removeBrain(f) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            brainList.addView(row, w())
        }
    }

    private fun useBrain(f: File) {
        getSharedPreferences("sam", MODE_PRIVATE).edit().putString("cervello_prima", SamApp.llmFile(this)?.name).apply()
        SamApp.setActiveBrain(this, f.name)
        if (VoiceService.running) VoiceService.reloadBrain(this)
        refresh()
        brainStatus.text = "Ora SAM usa ${Brains.byFile(f.name)?.name ?: f.nameWithoutExtension}: lo sta caricando (la prima volta qualche minuto)."
    }

    private fun removeBrain(f: File) {
        val name = Brains.byFile(f.name)?.name ?: f.nameWithoutExtension
        android.app.AlertDialog.Builder(this)
            .setMessage("Tolgo $name dal telefono? Liberi ${Brains.gb(f.length())}. Se ti servirà di nuovo, SAM lo riscarica col Wi-Fi.")
            .setPositiveButton("Toglilo") { _, _ -> SamApp.removeBrain(this, f.name); refresh() }
            .setNegativeButton("No", null)
            .show()
    }

    /** Dopo un import: il cervello di prima resta sul telefono? (Serve per passare dall'uno all'altro a voce.) */
    private fun askKeepOthers(r: ModelStore.Imported) {
        val files = r.others.map { File(SamApp.modelsDir(this), it) }.filter { it.exists() }
        if (files.isEmpty()) return
        val names = files.joinToString(", ") { Brains.byFile(it.name)?.name ?: it.nameWithoutExtension }
        android.app.AlertDialog.Builder(this)
            .setTitle("Il cervello di prima")
            .setMessage("Sul telefono c'è ancora $names (${Brains.gb(files.sumOf { it.length() })}). Lo tengo? Così puoi tornarci " +
                "quando vuoi, anche a voce: «SAM, usa l'altro cervello». Se lo togli liberi spazio.")
            .setPositiveButton("Tienilo", null)
            .setNegativeButton("Toglilo") { _, _ -> files.forEach { SamApp.removeBrain(this, it.name) }; refresh() }
            .show()
    }

    private fun pickBrain() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try { startActivityForResult(i, PICK_BRAIN) }
        catch (e: Exception) { brainStatus.text = "Selettore file non disponibile: ${e.message}" }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PICK_VOICE) {
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            else data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importVoice(uris)
            return
        }
        if (requestCode == SAVE_MEM || requestCode == LOAD_MEM) { data.data?.let { memoryFile(requestCode, it) }; return }
        if (requestCode == PICK_ZIM) { data.data?.let { chooseEncyclopedia(it) }; return }
        if (requestCode != PICK_BRAIN) return
        val uri: Uri = data.data ?: return
        brainBtn.isEnabled = false
        brainBar.visibility = View.VISIBLE; brainBar.progress = 0
        brainStatus.text = "Controllo e importo il cervello… (qualche secondo)"
        Thread {
            try {
                val r = ModelStore.importLlm(this, uri) { p -> runOnUiThread { brainBar.progress = p } }
                runOnUiThread {
                    brainBar.visibility = View.GONE; brainBtn.isEnabled = true
                    refresh()
                    brainStatus.text = "Cervello importato: ${r.name} ✓  Ora puoi cancellare il file originale per liberare spazio." +
                        (r.warning?.let { "\n$it" } ?: "")
                    // se SAM è acceso, carica subito il nuovo cervello
                    if (VoiceService.running) VoiceService.reloadBrain(this)
                    askKeepOthers(r)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    brainBar.visibility = View.GONE; brainBtn.isEnabled = true
                    brainStatus.text = "Import non riuscito: ${e.message}"
                }
            }
        }.start()
    }

    // ---- enciclopedia ---------------------------------------------------------
    /**
     * L'enciclopedia la scarica SAM col Wi-Fi: la stessa versione di quella che hai (se ce l'hai, cerca la più nuova),
     * altrimenti Wikipedia in italiano senza immagini; [light] = la versione leggera, solo l'inizio di ogni voce.
     */
    private fun downloadEncyclopedia(light: Boolean) {
        if (!Updater.onWifi(this)) {
            encStatus.text = "SAM scarica solo col Wi-Fi: collegati al Wi-Fi e tocca di nuovo."
            return
        }
        encStatus.text = "Guardo sul sito di Kiwix…"
        Thread {
            val family = if (light) Kiwix.LIGHT_FAMILY else currentFamily() ?: Kiwix.DEFAULT_FAMILY
            val msg = try { Updater.downloadEncyclopedia(this, family) } catch (e: Throwable) { "Non riesco: ${e.message}" }
            runOnUiThread { encStatus.text = msg; updStatus.text = Updater.setupText(this) }
        }.start()
    }

    /** La famiglia (per Kiwix) dell'enciclopedia che hai già, o null. Thread di background. */
    private fun currentFamily(): String? {
        val u = EncyclopediaFile.uri(this) ?: return null
        return try { EncyclopediaFile.open(this, u).use { e -> Kiwix.family(EncyclopediaFile.fileName(this, u), e.zimName, e.flavour) } }
               catch (e: Throwable) { null }
    }

    private fun pickEncyclopedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, PICK_ZIM) }
        catch (e: Exception) { encStatus.text = "Selettore file non disponibile: ${e.message}" }
    }

    private fun chooseEncyclopedia(uri: Uri) {
        encPickBtn.isEnabled = false
        encStatus.text = "Controllo il file…"
        Thread {
            val old = EncyclopediaFile.uri(this)
            val msg = try {
                // si tiene il permesso prima di aprirlo: così vale anche dopo il riavvio del telefono
                EncyclopediaFile.set(this, uri)
                val t = EncyclopediaFile.open(this, uri.toString()).use { e ->
                    e.check()
                    "Enciclopedia pronta ✓  ${EncyclopediaFile.describe(e)}"
                }
                // quella di prima, se l'aveva scaricata SAM nella sua cartella, non serve più
                if (old != null && old != uri.toString() && EncyclopediaFile.isOwn(this, old)) File(old).delete()
                t
            } catch (e: Throwable) {
                // il file scelto non va: resta quella di prima
                if (old != null && old != uri.toString()) EncyclopediaFile.restore(this, old) else EncyclopediaFile.clear(this)
                "Questo file non va bene come enciclopedia: ${e.message ?: "non si legge"}. Serve un file .zim di Kiwix (per esempio " +
                    "Wikipedia in italiano, versione nopic)."
            }
            runOnUiThread {
                encPickBtn.isEnabled = true
                refresh()
                encStatus.text = msg
                VoiceService.encyclopediaChanged(this)
            }
        }.start()
    }

    private fun removeEncyclopedia() {
        val u = EncyclopediaFile.uri(this) ?: return
        EncyclopediaFile.clear(this)
        VoiceService.encyclopediaChanged(this)
        refresh()
        if (EncyclopediaFile.isOwn(this, u)) {
            // l'aveva scaricata SAM, nella sua cartella: la cancella lui
            File(u).delete()
            encStatus.text = "Enciclopedia tolta, e cancellato il file che SAM aveva scaricato."
        } else encStatus.text = "Enciclopedia tolta. Il file resta sul telefono: se non ti serve, cancellalo dalla cartella Download."
    }

    // ---- la voce di SAM (sintesi vocale) ------------------------------------
    /** Una sintesi vocale solo per la configurazione, con lo stesso motore di SAM: per sentire le voci. */
    private fun startPreviewTts() {
        try { tts?.shutdown() } catch (e: Exception) {}
        ttsOk = false
        val engine = VoiceSettings.engine(this)
        val onInit = TextToSpeech.OnInitListener { st ->
            runOnUiThread {
                ttsOk = st == TextToSpeech.SUCCESS
                tts?.let { t -> if (ttsOk) { try { t.setLanguage(java.util.Locale.ITALIAN) } catch (e: Exception) {}; VoiceSettings.apply(this, t) } }
                refreshVoice()
            }
        }
        tts = try { if (engine != null) TextToSpeech(this, onInit, engine) else TextToSpeech(this, onInit) } catch (e: Exception) { null }
    }

    private fun refreshVoice() {
        val t = tts
        if (t == null || !ttsOk) { ttsStatus.text = "Preparo la sintesi vocale…"; engineBtn.visibility = View.GONE; return }
        val list = VoiceSettings.italianVoices(t)
        val v = try { t.voice } catch (e: Exception) { null }
        val engine = VoiceSettings.engineLabel(t, VoiceSettings.engine(this))
        ttsStatus.text = if (list.isEmpty()) "Sul telefono non c'è una voce italiana che funziona senza internet: nelle impostazioni del telefono " +
                "cerca Sintesi vocale e installa i dati vocali in italiano."
            else "Voce: ${VoiceSettings.describe(v, list)}" + (engine?.let { " ($it)" } ?: "") +
                " · velocità ${Tuning.rateWords(VoiceSettings.rate(this))} · tono ${Tuning.pitchWords(VoiceSettings.pitch(this))}"
        val engines = try { t.engines } catch (e: Exception) { emptyList() }
        engineBtn.visibility = if (engines.size > 1) View.VISIBLE else View.GONE
    }

    private fun sample(text: String = VoiceSettings.SAMPLE) {
        val t = tts ?: return
        if (!ttsOk) return
        try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "prova") } catch (e: Exception) {}
    }

    /** L'elenco delle voci: toccandone una la senti; "Usa questa" la sceglie per SAM. */
    private fun chooseTtsVoice() {
        val t = tts
        if (t == null || !ttsOk) { ttsStatus.text = "La sintesi vocale non è ancora pronta: riprova tra un attimo."; return }
        val list = VoiceSettings.italianVoices(t)
        if (list.size < 2) {
            ttsStatus.text = "Sul telefono c'è " + (if (list.isEmpty()) "nessuna voce italiana" else "una sola voce italiana") +
                " che funziona senza internet. Per averne altre: impostazioni del telefono › Sintesi vocale › motore di Google › installa i dati vocali in italiano."
            return
        }
        val cur = try { t.voice?.name } catch (e: Exception) { null }
        var picked = list.indexOfFirst { it.name == cur }.coerceAtLeast(0)
        val items = list.map { VoiceSettings.describe(it, list).replaceFirstChar { c -> c.uppercase() } }.toTypedArray<CharSequence>()
        android.app.AlertDialog.Builder(this)
            .setTitle("Scegli la voce di SAM")
            .setSingleChoiceItems(items, picked) { _, i ->
                picked = i
                try { t.voice = list[i] } catch (e: Exception) {}
                sample()
            }
            .setPositiveButton("Usa questa") { _, _ ->
                VoiceSettings.setVoiceName(this, list[picked].name, cur)
                VoiceService.voiceSettingsChanged(this, engine = false)
                refreshVoice()
            }
            .setNegativeButton("Annulla") { _, _ -> VoiceSettings.apply(this, t); refreshVoice() }
            .show()
    }

    /** Velocità e tono: un passo alla volta, e lo senti subito. */
    private fun tuneVoice(what: String, step: Int) {
        when (what) {
            "rate" -> VoiceSettings.setRate(this, Tuning.stepped(VoiceSettings.rate(this), step, Tuning.RATE_MIN, Tuning.RATE_MAX))
            "pitch" -> VoiceSettings.setPitch(this, Tuning.stepped(VoiceSettings.pitch(this), step, Tuning.PITCH_MIN, Tuning.PITCH_MAX))
            else -> { VoiceSettings.setRate(this, Tuning.NORMAL); VoiceSettings.setPitch(this, Tuning.NORMAL) }
        }
        tts?.let { if (ttsOk) VoiceSettings.apply(this, it) }
        VoiceService.voiceSettingsChanged(this, engine = false)
        refreshVoice()
        sample("Così va bene?")
    }

    /** Il motore di sintesi (se sul telefono ce n'è più d'uno, per esempio Google e quello di Xiaomi). */
    private fun chooseEngine() {
        val t = tts ?: return
        val engines = try { t.engines } catch (e: Exception) { emptyList() }
        if (engines.isEmpty()) return
        val cur = VoiceSettings.engine(this) ?: try { t.defaultEngine } catch (e: Exception) { null }
        val items = engines.map { it.label }.toTypedArray<CharSequence>()
        android.app.AlertDialog.Builder(this)
            .setTitle("Motore della sintesi vocale")
            .setSingleChoiceItems(items, engines.indexOfFirst { it.name == cur }) { d, i ->
                VoiceSettings.setEngine(this, engines[i].name)
                VoiceService.voiceSettingsChanged(this, engine = true)
                startPreviewTts()
                d.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ---- aggiornamenti ------------------------------------------------------
    private fun checkUpdatesNow() {
        if (!Updater.onWifi(this)) { updStatus.text = "SAM controlla e scarica solo col Wi-Fi: collegati al Wi-Fi e tocca di nuovo."; return }
        updStatus.text = "Controllo…"
        Thread {
            val msg = try { Updater.check(this, force = true) } catch (e: Throwable) { "Non riesco a controllare: ${e.message}" }
            runOnUiThread {
                updStatus.text = Updater.setupText(this) + if (msg.isBlank()) "\nNessuna novità: hai le versioni più recenti." else ""
                refreshEncyclopedia()
            }
        }.start()
    }

    private fun toggleAuto() {
        Updater.setAuto(this, !Updater.autoOn(this))
        refresh()
    }

    private fun refreshEncyclopedia() {
        val u = EncyclopediaFile.uri(this)
        encRemoveBtn.isEnabled = u != null
        encGetBtn.text = if (u == null) "Scarica Wikipedia in italiano (Wi-Fi, circa 10 GB)" else "Cerca una Wikipedia più nuova (Wi-Fi)"
        if (u == null) { encStatus.text = "Nessuna enciclopedia per ora."; return }
        encStatus.text = "Controllo l'enciclopedia…"
        Thread {
            val t = try { EncyclopediaFile.open(this, u).use { "Enciclopedia: ${EncyclopediaFile.describe(it)} ✓" } }
                    catch (e: Throwable) { "L'enciclopedia scelta non si apre più (${e.message}): sceglila di nuovo." }
            runOnUiThread { encStatus.text = t }
        }.start()
    }

    // ---- ricordi ------------------------------------------------------------
    private fun memorySummary(): String {
        val all = FileMemory(this).memos()
        if (all.isEmpty()) return "Nessun ricordo per ora."
        val n = all.count { it.kind == Remember.NOTA }; val f = all.count { it.kind == Remember.FATTO }
        val s = all.count { it.kind == Remember.ALIAS }; val c = all.count { it.kind == Remember.SCELTA }
        val r = all.count { it.kind == Remember.REGOLA }; val l = all.count { it.kind == Remember.LEZIONE }
        return "SAM ricorda ${all.size} cose: $n che gli hai chiesto, $f imparate da solo, $r regole da seguire, $l lezioni, " +
            "$s scorciatoie, $c scelte di contatti."
    }

    private fun memoLabel(m: Memo): String = when (m.kind) {
        Remember.REGOLA -> "Regola: ${m.text}"
        Remember.LEZIONE -> "Lezione: «${m.key}» → ${m.text}"
        Remember.NOTA -> "Da ricordare: ${m.text}"
        Remember.FATTO -> "Imparato: ${m.text}"
        Remember.ALIAS -> "Scorciatoia «${m.key}»: ${m.text}"
        Remember.SCELTA -> "Contatto: «${m.key.removePrefix("contatto:")}» = ${m.text}"
        else -> m.text
    }

    /** L'elenco dei ricordi: si spuntano quelli da cancellare (con una conferma). */
    private fun showMemories() {
        val fm = FileMemory(this)
        val all = fm.memos()
        if (all.isEmpty()) { memStatus.text = "Nessun ricordo per ora. Di' «SAM, ricordati che…»."; return }
        val items = all.map { memoLabel(it) }.toTypedArray<CharSequence>()
        val sel = BooleanArray(items.size)
        android.app.AlertDialog.Builder(this)
            .setTitle("Ricordi di SAM (${all.size})")
            .setMultiChoiceItems(items, sel) { _, i, on -> sel[i] = on }
            .setPositiveButton("Cancella i selezionati") { _, _ ->
                val del = all.filterIndexed { i, _ -> sel[i] }
                if (del.isNotEmpty()) android.app.AlertDialog.Builder(this)
                    .setMessage(if (del.size == 1) "Cancello per sempre questo ricordo?\n\n${memoLabel(del[0])}" else "Cancello per sempre questi ${del.size} ricordi?")
                    .setPositiveButton("Cancella") { _, _ ->
                        del.forEach { fm.removeMemo(it) }
                        VoiceService.memoryEdited = true
                        refresh()
                        memStatus.text = (if (del.size == 1) "Ricordo cancellato. " else "${del.size} ricordi cancellati. ") + memorySummary()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun saveMemories() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "SAM-ricordi.txt")
        try { startActivityForResult(i, SAVE_MEM) } catch (e: Exception) { memStatus.text = "Selettore file non disponibile: ${e.message}" }
    }

    private fun loadMemories() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, LOAD_MEM) } catch (e: Exception) { memStatus.text = "Selettore file non disponibile: ${e.message}" }
    }

    private fun memoryFile(requestCode: Int, uri: Uri) {
        val fm = FileMemory(this)
        try {
            if (requestCode == SAVE_MEM) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(fm.exportText().toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("file non scrivibile")
                memStatus.text = "Copia salvata ✓ (${fm.memos().size} ricordi)."
            } else {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("file non leggibile")
                val n = fm.importText(text)
                VoiceService.memoryEdited = true
                refresh()
                memStatus.text = (if (n == 0) "Nessun ricordo nuovo nella copia. " else "Ripristinati $n ricordi ✓ ") + memorySummary()
            }
        } catch (e: Exception) {
            memStatus.text = "Non riuscito: ${e.message}"
        }
    }

    // ---- la tua voce --------------------------------------------------------
    private fun teachVoice() {
        if (!ModelStore.voskReady(this)) { voiceIdStatus.text = "Prima importa la voce italiana (punto 3)."; return }
        VoiceService.enroll(this)
        voiceIdStatus.text = "SAM ti sta dicendo le frasi da ripetere: ascoltalo e ripetile una alla volta."
    }

    private fun toggleOnlyMe() {
        val vs = VoiceStore(this)
        if (vs.load() == null) return
        vs.onlyOwner = !vs.onlyOwner
        refresh()
    }

    private fun forgetVoice() {
        val vs = VoiceStore(this)
        if (vs.load() == null) { voiceIdStatus.text = "SAM non conosce ancora la tua voce."; return }
        android.app.AlertDialog.Builder(this)
            .setMessage("SAM dimentica la tua voce e torna a rispondere a tutti. Confermi?")
            .setPositiveButton("Dimentica") { _, _ -> vs.delete(); VoiceService.voiceChanged(this); refresh() }
            .setNegativeButton("No", null)
            .show()
    }

    // ---- permessi ----------------------------------------------------------
    private fun askPermissions() {
        val want = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA
        )
        want += Gallery.permissions()       // 1.21: le foto e i video della Galleria
        requestPermissions(want.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        voiceStatus.post { refresh() }
    }

    /** Elenco dei permessi con ✓ / ✗, per capire subito cosa manca. */
    private fun permissionsText(): String {
        fun ok(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "?" }
        fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val sb = StringBuilder()
        sb.append("Microfono ${ok(has(Manifest.permission.RECORD_AUDIO))}  ·  Contatti ${ok(has(Manifest.permission.READ_CONTACTS))}  ·  ")
        sb.append("Telefono ${ok(has(Manifest.permission.CALL_PHONE))}  ·  SMS ${ok(has(Manifest.permission.SEND_SMS))}  ·  ")
        sb.append("Fotocamera ${ok(has(Manifest.permission.CAMERA))}  ·  Foto e video ${ok(Gallery.allowed(this))}\n")
        sb.append("Accessibilità ${ok(Hardware.accessibilityOn(this))}  ·  Mostra sopra altre app ${ok(Settings.canDrawOverlays(this))}")
        if (Hardware.isXiaomi()) sb.append("  ·  Pop-up in background ${ok(Hardware.xiaomiBackgroundPopups(this))}")
        sb.append("\nNotifiche ${ok(NotificationReader.isEnabled(this))}  ·  Batteria senza limiti ${ok(pm.isIgnoringBatteryOptimizations(packageName))}")
        sb.append("  ·  Luminosità ${ok(Settings.System.canWrite(this))}")
        return sb.toString()
    }

    /** Chiede direttamente "SAM può funzionare sempre in sottofondo?"; se non si può, apre l'elenco. */
    private fun askNoBatteryLimits() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
    }

    /** La pagina Xiaomi "Altre autorizzazioni" di SAM (lì c'è "Mostra finestre pop-up in background"). */
    private fun openXiaomiPerms() {
        val tries = listOf(
            "com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
        )
        for (cls in tries) {
            try {
                startActivity(Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", cls)
                    .putExtra("extra_pkgname", packageName))
                return
            } catch (e: Exception) {}
        }
        openWithPkg(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    // ---- aperture impostazioni --------------------------------------------
    private fun open(action: String) = try { startActivity(Intent(action)) } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
    private fun openWithPkg(action: String) = try {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    } catch (e: Exception) { open(Settings.ACTION_SETTINGS) }

    // ---- 2.0: manutenzione -------------------------------------------------
    private fun runCare(what: String) {
        careStatus.text = "Un momento…"
        Thread {
            val now = System.currentTimeMillis()
            val t = try { when (what) {
                "check" -> { val cs = PhoneCare.checks(this, now); Health.headline(cs) + ".\n" + cs.filter { it.level != Health.Level.OK }.joinToString("\n") { "• " + it.detail + (it.fix?.let { f -> " $f." } ?: "") }.ifEmpty { "Ho controllato ${cs.size} cose." } }
                "repair" -> VoiceService.repairFromUi(this)
                "backup" -> VoiceService.backupFromUi(this)
                else -> { val f = PhoneCare.clean(this, now, olderMs = 3_600_000L); (if (f > 0) "Liberati ${Health.size(f)} di file temporanei. " else "Era già pulito. ") + PhoneCare.space(this) }
            } } catch (e: Throwable) { "Non ci sono riuscito: ${e.message}" }
            runOnUiThread { careStatus.text = t }
        }.start()
    }

    private fun pickBackup() {
        val now = System.currentTimeMillis(); val b = PhoneCare.backups(this); val all = b.list()
        if (all.isEmpty()) { careStatus.text = "Non c'è ancora nessuna copia."; return }
        val names = all.map { "Copia " + b.whenText(it, now) + when (it.kind) { Backups.MANUAL -> " (fatta da te)"; Backups.BEFORE -> " (prima di un ripristino)"; else -> "" } }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Quale copia rimetto?")
            .setItems(names) { _, which ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Ripristino")
                    .setMessage("Rimetto ricordi e mente com'erano in quella copia? Quello che c'è adesso lo salvo prima in un'altra copia.")
                    .setPositiveButton("Ripristina") { _, _ -> Thread { val t = VoiceService.restoreFromUi(this, all[which]); runOnUiThread { careStatus.text = t; refresh() } }.start() }
                    .setNegativeButton("Annulla", null).show()
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun showRegister() {
        val tv = TextView(this).apply { setTextColor(Color.rgb(226, 231, 240)); textSize = 13.5f; setLineSpacing(0f, 1.2f)
            setPadding(pad(), (12 * dp).toInt(), pad(), (12 * dp).toInt())
            text = PhoneCare.journal(this@SetupActivity).markdown(60).replace("**", "") }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Il registro").setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Chiudi", null).show()
    }

    // ---- UI helpers --------------------------------------------------------
    private val BG = Color.rgb(13, 15, 22)
    private val CARD_BG = Color.rgb(22, 26, 38)
    private val BTN_BG = Color.rgb(40, 46, 64)
    private val BLUE = Color.rgb(37, 99, 235)

    private fun shape(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius * dp }

    /** Una scheda con il suo titolo: tutto quello che segue ci finisce dentro. */
    private fun card(t: String, page: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD_BG, 18f)
        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        addView(TextView(this@SetupActivity).apply {
            text = t; setTextColor(Color.WHITE); textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (2 * dp).toInt())
        })
        page.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (12 * dp).toInt() })
    }

    /** Il tasto principale di una scheda (blu). */
    private fun accent(b: Button) { b.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape(BLUE, 14f), null) }

    private fun pad() = (18 * dp).toInt()
    private fun w() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (6 * dp).toInt() }

    private fun title(t: String, root: LinearLayout) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        root.addView(this, w())
    }
    private fun header(t: String, root: LinearLayout) = TextView(this).apply {
        text = t; setTextColor(Color.rgb(120, 190, 255)); textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt())
        root.addView(this, w())
    }
    private fun note(t: String, root: LinearLayout) = TextView(this).apply {
        text = t; setTextColor(Color.rgb(170, 180, 200)); textSize = 14f
        setLineSpacing(0f, 1.15f)
        root.addView(this, w())
    }
    private fun status(root: LinearLayout) = TextView(this).apply {
        setTextColor(Color.rgb(200, 230, 210)); textSize = 14f
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        root.addView(this, w())
    }
    private fun bar(root: LinearLayout) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; visibility = View.GONE
        root.addView(this, w())
    }
    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
        // 2.0: tasti piatti e arrotondati, uguali su tutti i telefoni
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape(BTN_BG, 14f), null)
        stateListAnimator = null; textSize = 14.5f; minHeight = (46 * dp).toInt(); minimumHeight = (46 * dp).toInt()
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(110, 118, 138), Color.rgb(226, 231, 240))))
    }
    /** Due pulsanti affiancati, larghi uguali. */
    private fun pair(a: Button, b: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = (3 * dp).toInt() })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = (3 * dp).toInt() })
    }
}
