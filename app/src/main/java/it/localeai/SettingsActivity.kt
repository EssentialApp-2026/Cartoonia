package it.localeai

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var importing = false
    private var importProgress: ProgressBar? = null
    private var importStatus: TextView? = null

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SamBrain.init(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        scroll.applySystemInsets(dp(16))
        setContentView(scroll)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(INK)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEachIndexed { i, v ->
            if (i > 0) addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
            addView(v)
        }
    }

    private fun rebuild() {
        content.removeAllViews()

        val back = pillButton("← Indietro") { finish() }
        content.addView(row(back))

        // --- Modalità ---------------------------------------------------------
        content.addView(heading("Motore"))
        val group = RadioGroup(this)
        EngineMode.values().forEach { mode ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = mode.label
                textSize = 15f
                isChecked = AccountStore.mode == mode
                setOnClickListener {
                    AccountStore.mode = mode
                    SamBrain.refreshStatus()
                }
            })
        }
        content.addView(group)

        // --- Account online ---------------------------------------------------
        content.addView(heading("Account online"))
        content.addView(note(
            "SAM usa gli account dall'alto verso il basso. Quando uno finisce i token " +
                "o dà errore, va in pausa e SAM passa da solo al successivo; " +
                "se finiscono tutti usa il modello locale."
        ))
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.ITALIAN)
        AccountStore.accounts.forEachIndexed { i, a ->
            val state = when {
                !a.enabled -> "✗ disattivato"
                a.apiKey.isBlank() -> "✗ chiave mancante"
                a.cooldownUntil > now -> "⏸ in pausa fino alle ${time.format(Date(a.cooldownUntil))}: ${a.lastError}"
                a.lastError.isNotBlank() -> "✓ pronto (ultimo errore: ${a.lastError})"
                else -> "✓ pronto · ${a.requests} risposte"
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            card.addView(TextView(this).apply {
                text = "${i + 1}. ${a.name}"
                textSize = 16f
                setTextColor(INK)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(note("${a.provider.label} · ${a.model}\n$state"))
            card.addView(row(
                pillButton("↑") { AccountStore.move(a, -1); rebuild() },
                pillButton("↓") { AccountStore.move(a, 1); rebuild() },
                pillButton("Modifica") { editAccount(a) },
                pillButton("Prova") { testAccount(a) }
            ))
            content.addView(card)
        }
        content.addView(row(
            pillButton("+ Aggiungi account", primary = true) { editAccount(null) },
            pillButton("Riattiva tutti") {
                AccountStore.resetCooldowns()
                SamBrain.refreshStatus()
                rebuild()
            }
        ))

        // --- Modello locale ---------------------------------------------------
        content.addView(heading("Modello locale (offline)"))
        val file = LocalModel.file(this)
        val localText = if (file.exists()) {
            "${LocalModel.displayName(this)} · ${file.length() / 1_048_576} MB" +
                (if (LlmEngine.loadedPath != null) " · caricato" else " · si carica quando serve")
        } else "Nessun modello. Consigliato: un GGUF Q4_K_M da 1–3 miliardi di parametri (es. Qwen2.5 1.5B Instruct)."
        importStatus = note(localText)
        content.addView(importStatus)
        importProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = if (importing) View.VISIBLE else View.GONE
        }
        content.addView(importProgress, LinearLayout.LayoutParams(-1, -2))
        val localButtons = mutableListOf<View>(
            pillButton(if (file.exists()) "Sostituisci GGUF" else "Importa GGUF", primary = !file.exists()) {
                if (!importing && !SamBrain.busy) pickModel.launch(arrayOf("*/*"))
            }
        )
        if (file.exists()) {
            localButtons += pillButton("Rimuovi") {
                if (importing || SamBrain.busy) return@pillButton
                LocalModel.unloadAsync()
                LlmEngine.executor.execute { file.delete() }
                file.delete()
                SamBrain.refreshStatus()
                rebuild()
            }
        }
        content.addView(row(*localButtons.toTypedArray()))

        // --- Voce -------------------------------------------------------------
        content.addView(heading("Voce"))
        content.addView(switchRow("Leggi le risposte ad alta voce", AccountStore.speakReplies) {
            AccountStore.speakReplies = it
        })
        content.addView(note("Velocità della voce"))
        content.addView(SeekBar(this).apply {
            max = 15
            progress = ((AccountStore.speechRate - 0.5f) * 10).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) AccountStore.speechRate = 0.5f + p / 10f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    SamBrain.voice.stop()
                    SamBrain.voice.speak("Ciao, sono SAM.")
                }
            })
        })
        content.addView(switchRow("Silenzia i bip durante l'ascolto di «SAM»", AccountStore.muteBeeps) {
            AccountStore.muteBeeps = it
        })
        content.addView(note("Mentre aspetta «SAM» il volume multimediale resta a zero per non sentire i bip del microfono."))

        // --- Permessi ---------------------------------------------------------
        content.addView(heading("Permessi"))
        content.addView(note(
            "Microfono per parlare, contatti e telefono per «chiama…» e «manda un messaggio a…». " +
                "«Mostra sopra altre app» permette a SAM di aprire app e fare chiamate anche a schermo spento."
        ))
        content.addView(row(
            pillButton("Concedi permessi") {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.CALL_PHONE
                    ) + (if (android.os.Build.VERSION.SDK_INT >= 33)
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
                    1
                )
            },
            pillButton(if (Settings.canDrawOverlays(this)) "Sopra altre app ✓" else "Sopra altre app") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        ))

        content.addView(heading("Comandi vocali"))
        content.addView(note(
            "«SAM, che ore sono» · «chiama Mario» · «manda un messaggio a Anna che dice arrivo» · " +
                "«manda un whatsapp a Luca dicendo ok» · «sveglia alle 7 e 30» · «timer di 5 minuti» · " +
                "«accendi la torcia» · «alza il volume» · «apri WhatsApp» · «portami a Napoli Centrale» · " +
                "«cerca meteo Casoria» · «metti Pino Daniele» · «ricordami di chiamare il medico domani alle 10» · " +
                "«batteria» · «modalità offline» · «stop». Tutto il resto lo chiedi all'IA."
        ))
    }

    private fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        SwitchCompat(this).apply {
            text = label
            textSize = 15f
            isChecked = value
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    // --- Account ----------------------------------------------------------------

    private fun editAccount(existing: Account?) {
        val providers = Provider.values()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.label }
            )
            setSelection(providers.indexOf(existing?.provider ?: Provider.GROQ))
        }
        val name = EditText(this).apply { hint = "Nome (es. Groq personale)"; setText(existing?.name ?: "") }
        val key = EditText(this).apply {
            hint = "Chiave API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.apiKey ?: "")
        }
        val model = EditText(this).apply { hint = "Modello"; setText(existing?.model ?: "") }
        val baseUrl = EditText(this).apply {
            hint = "URL base (es. https://…/v1)"
            setText(existing?.baseUrl ?: "")
        }
        val enabled = SwitchCompat(this).apply { text = "Attivo"; isChecked = existing?.enabled ?: true }
        val keyLink = note("")

        fun selected() = providers[spinner.selectedItemPosition]

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val pr = providers[pos]
                baseUrl.visibility = if (pr == Provider.CUSTOM) View.VISIBLE else View.GONE
                keyLink.text = if (pr.keyUrl.isNotBlank()) "Chiave gratuita o a pagamento: ${pr.keyUrl}" else ""
                if (first && existing != null) { first = false; return }
                first = false
                if (pr.defaultModel.isNotBlank()) model.setText(pr.defaultModel)
                if (name.text.isBlank() || providers.any { it.label == name.text.toString() }) name.setText(pr.label)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        keyLink.setOnClickListener {
            val url = selected().keyUrl
            if (url.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        val listModels = pillButton("Scegli dall'elenco") {
            val probe = Account(
                name = "probe", provider = selected(), apiKey = key.text.toString(),
                model = "x", baseUrl = baseUrl.text.toString()
            )
            if (probe.apiKey.isBlank()) {
                Toast.makeText(this, "Inserisci prima la chiave", Toast.LENGTH_SHORT).show()
                return@pillButton
            }
            Toast.makeText(this, "Carico l'elenco dei modelli…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { OnlineClient.listModels(probe) } }
                result.onSuccess { models ->
                    if (models.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "Nessun modello trovato", Toast.LENGTH_LONG).show()
                    } else {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Modelli disponibili")
                            .setItems(models.toTypedArray()) { _, which -> model.setText(models[which]) }
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Errore: ${AiRouter.classify(it).reason}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        box.addView(note("Servizio"))
        box.addView(spinner)
        box.addView(name)
        box.addView(key)
        box.addView(keyLink)
        box.addView(model)
        box.addView(listModels)
        box.addView(baseUrl)
        box.addView(enabled)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo account" else "Modifica account")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salva") { _, _ ->
                val pr = selected()
                val acc = Account(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { pr.label },
                    provider = pr,
                    apiKey = key.text.toString().trim(),
                    model = model.text.toString().trim().ifEmpty { pr.defaultModel },
                    baseUrl = baseUrl.text.toString().trim(),
                    enabled = enabled.isChecked,
                    requests = existing?.requests ?: 0
                )
                AccountStore.upsert(acc)
                SamBrain.refreshStatus()
                rebuild()
            }
            .setNegativeButton("Annulla", null)
        if (existing != null) {
            dialog.setNeutralButton("Elimina") { _, _ ->
                AccountStore.remove(existing)
                SamBrain.refreshStatus()
                rebuild()
            }
        }
        dialog.show()
    }

    private fun testAccount(account: Account) {
        Toast.makeText(this, "Provo ${account.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val reply = StringBuilder()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    OnlineClient.stream(
                        account, "Rispondi in italiano con una sola frase breve.",
                        listOf(Turn("user", "Presentati in cinque parole.")), CallHandle()
                    ) { reply.append(it) }
                }
            }
            result.onSuccess { AccountStore.markOk(account) }
                .onFailure {
                    val f = AiRouter.classify(it)
                    AccountStore.markFailed(account, f.cooldownMs, f.reason)
                }
            val body = result.fold(
                onSuccess = { "Funziona ✓\n\n${reply.toString().trim()}" },
                onFailure = {
                    val detail = (it as? ProviderError)?.body?.take(400) ?: (it.message ?: "")
                    "Non funziona: ${AiRouter.classify(it).reason}\n\n$detail"
                }
            )
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(account.name)
                .setMessage(body)
                .setPositiveButton("Ok", null)
                .show()
            SamBrain.refreshStatus()
            rebuild()
        }
    }

    // --- Modello locale -------------------------------------------------------

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "modello.gguf"

    private fun importModel(uri: Uri) {
        if (importing) return
        importing = true
        importProgress?.visibility = View.VISIBLE
        importProgress?.isIndeterminate = true
        importStatus?.text = "Importazione del modello…"
        LocalModel.unloadAsync()

        val target = LocalModel.file(this)
        val tmp = File(LocalModel.dir(this), "import.tmp")
        val label = displayName(uri).removeSuffix(".gguf")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (total > 0) {
                        val free = StatFs(filesDir.path).availableBytes + (if (target.exists()) target.length() else 0L)
                        if (free < total + 64L * 1024 * 1024) {
                            error("spazio insufficiente: servono ${total / 1_048_576} MB liberi")
                        }
                    }
                    val source = contentResolver.openInputStream(uri) ?: error("impossibile leggere il file")
                    source.use { stream ->
                        val magic = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            val n = stream.read(magic, read, 4 - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read < 4 || String(magic, Charsets.US_ASCII) != "GGUF") error("il file non è un modello GGUF")
                        tmp.outputStream().buffered(1 shl 20).use { out ->
                            out.write(magic)
                            val buf = ByteArray(1 shl 20)
                            var copied = 4L
                            var last = -1
                            while (true) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                    if (pct != last) {
                                        last = pct
                                        withContext(Dispatchers.Main) {
                                            importProgress?.isIndeterminate = false
                                            importProgress?.progress = pct
                                        }
                                    }
                                }
                            }
                        }
                    }
                    target.delete()
                    if (!tmp.renameTo(target)) error("impossibile salvare il modello")
                }
                LocalModel.setDisplayName(this@SettingsActivity, label)
                importStatus?.text = "Caricamento di prova…"
                importProgress?.isIndeterminate = true
                val ok = withContext(Dispatchers.IO) { LocalModel.loadBlocking(target) }
                importing = false
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "Modello locale pronto" else "Il modello non si carica: incompatibile o RAM insufficiente",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Throwable) {
                tmp.delete()
                importing = false
                Toast.makeText(this@SettingsActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
            }
            SamBrain.refreshStatus()
            rebuild()
        }
    }
}
