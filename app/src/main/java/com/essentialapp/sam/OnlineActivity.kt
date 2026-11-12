package com.essentialapp.sam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * SAM 2.1 · ⚙︎ › Cervelli online: gli account (Groq, Gemini, OpenRouter…) in ordine di uso.
 * Quando uno finisce i token SAM passa da solo al successivo, poi al cervello del telefono.
 */
class OnlineActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    /** servizio di cui hai aperto la pagina delle chiavi: al ritorno su SAM si guarda negli appunti */
    private var pendingProvider: Provider? = null

    private val BG = Color.rgb(14, 17, 24)
    private val CARD_BG = Color.rgb(24, 28, 40)
    private val BTN_BG = Color.rgb(40, 46, 64)
    private val BLUE = Color.rgb(37, 99, 235)
    private val TEXT = Color.rgb(226, 231, 240)
    private val MUTED = Color.rgb(170, 180, 200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountStore.init(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        rebuild()
    }

    override fun onResume() { super.onResume(); rebuild() }

    private fun shape(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius * dp }

    private fun text(t: String, size: Float = 14f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
        setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
    }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(TEXT)
        background = shape(if (primary) BLUE else BTN_BG, 14f)
        stateListAnimator = null
        minHeight = (44 * dp).toInt(); minimumHeight = (44 * dp).toInt()
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginStart = (3 * dp).toInt(); it.marginEnd = (3 * dp).toInt(); it.topMargin = (6 * dp).toInt()
            })
        }
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD_BG, 18f)
        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        addView(text(title, 17f, Color.WHITE, bold = true))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (12 * dp).toInt() })
    }

    private fun rebuild() {
        content.removeAllViews()
        content.addView(row(btn("← Indietro") { finish() }))
        content.addView(text("Cervelli online", 24f, Color.WHITE, bold = true))

        // --- quale cervello usare -----------------------------------------------------
        val modeCard = card("Quale cervello usare")
        val group = RadioGroup(this)
        EngineMode.values().forEach { mode ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = mode.label; textSize = 15f; setTextColor(TEXT)
                isChecked = AccountStore.mode == mode
                setOnClickListener { AccountStore.mode = mode }
            })
        }
        modeCard.addView(group)

        // --- accesso -------------------------------------------------------------------
        val loginCard = card("Accedi ai tuoi account")
        loginCard.addView(text(
            "OpenRouter: login vero. Si apre il browser, entri con il tuo account (Google, GitHub o email) e SAM riceve la " +
                "chiave da solo, con un modello gratuito già scelto."
        ))
        loginCard.addView(row(btn("Accedi con OpenRouter", primary = true) { loginOpenRouter() }))
        loginCard.addView(text(
            "Gli altri servizi non hanno un login per le app: SAM apre la loro pagina, tu entri con il tuo account, crei la " +
                "chiave e la copi. Torna su SAM: la trova da sola negli appunti e la aggiunge."
        ))
        val quick = listOf(Provider.GROQ, Provider.GEMINI, Provider.CEREBRAS, Provider.MISTRAL,
            Provider.OPENAI, Provider.ANTHROPIC, Provider.DEEPSEEK)
        quick.chunked(2).forEach { pair ->
            loginCard.addView(row(*pair.map { pr -> btn(pr.label) { openKeyPage(pr) } }.toTypedArray()))
        }

        // --- account ------------------------------------------------------------------
        val accCard = card("Account online, in ordine di uso")
        accCard.addView(text(
            "SAM prova gli account dall'alto verso il basso. Quando uno finisce i token o dà errore va in pausa " +
                "(pochi secondi per i limiti al minuto, almeno un'ora per quelli del giorno) e SAM passa da solo al " +
                "successivo. Se sono tutti in pausa, o non c'è internet, risponde il cervello del telefono. " +
                "Le chiavi restano sul telefono, cifrate."
        ))
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.ITALIAN)
        if (AccountStore.accounts.isEmpty()) {
            accCard.addView(text("Nessun account. Le chiavi gratuite più comode: Groq e Google Gemini (tocca «+ Aggiungi»).", 14f, TEXT))
        }
        AccountStore.accounts.forEachIndexed { i, a ->
            val state = when {
                !a.enabled -> "✗ disattivato"
                a.apiKey.isBlank() -> "✗ chiave mancante"
                a.cooldownUntil > now -> "⏸ in pausa fino alle ${time.format(Date(a.cooldownUntil))}: ${a.lastError}"
                a.lastError.isNotBlank() -> "✓ pronto (ultimo errore: ${a.lastError})"
                else -> "✓ pronto · ${a.requests} risposte"
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(Color.rgb(32, 37, 52), 14f)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            }
            box.addView(text("${i + 1}. ${a.name}", 16f, Color.WHITE, bold = true))
            box.addView(text("${a.provider.label} · ${a.model}\n$state"))
            box.addView(row(
                btn("↑") { AccountStore.move(a, -1); rebuild() },
                btn("↓") { AccountStore.move(a, 1); rebuild() },
                btn("Modifica") { edit(a) },
                btn("Prova") { test(a) }
            ))
            accCard.addView(box, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() })
        }
        accCard.addView(row(
            btn("+ Aggiungi", primary = true) { edit(null) },
            btn("Riattiva tutti") { AccountStore.resetCooldowns(); rebuild() }
        ))

        val note = card("Da sapere")
        note.addView(text(
            "• Con gli account online le domande escono dal telefono e arrivano al servizio scelto.\n" +
                "• Comandi, sveglie, chiamate, ricordi e voce restano sul telefono come prima.\n" +
                "• Foto («cosa vedi?») e conversazione con un'altra IA usano sempre il cervello del telefono.\n" +
                "• Usa account tuoi su servizi diversi: creare tanti account gratuiti sullo stesso servizio " +
                "per aggirare i limiti è vietato dalle loro regole e può portare al blocco."
        ))
    }

    private fun edit(existing: Account?) {
        val providers = Provider.values()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@OnlineActivity, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label })
            setSelection(providers.indexOf(existing?.provider ?: Provider.GROQ))
        }
        val name = EditText(this).apply { hint = "Nome (es. Groq personale)"; setText(existing?.name ?: "") }
        val key = EditText(this).apply {
            hint = "Chiave API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.apiKey ?: "")
        }
        val model = EditText(this).apply { hint = "Modello"; setText(existing?.model ?: "") }
        val baseUrl = EditText(this).apply { hint = "URL base (es. https://…/v1)"; setText(existing?.baseUrl ?: "") }
        val enabled = CheckBox(this).apply { text = "Attivo"; isChecked = existing?.enabled ?: true }
        val keyLink = TextView(this).apply { textSize = 13f; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }

        fun selected() = providers[spinner.selectedItemPosition]

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val pr = providers[pos]
                baseUrl.visibility = if (pr == Provider.CUSTOM) View.VISIBLE else View.GONE
                keyLink.text = if (pr.keyUrl.isNotBlank()) "Prendi la chiave qui (tocca): ${pr.keyUrl}" else ""
                if (first && existing != null) { first = false; return }
                first = false
                if (pr.defaultModel.isNotBlank()) model.setText(pr.defaultModel)
                if (name.text.isBlank() || providers.any { it.label == name.text.toString() }) name.setText(pr.label)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        keyLink.setOnClickListener {
            val url = selected().keyUrl
            if (url.isNotBlank()) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
        }
        val listBtn = Button(this).apply {
            text = "Scegli il modello dall'elenco"; isAllCaps = false
            setOnClickListener {
                val probe = Account(name = "probe", provider = selected(), apiKey = key.text.toString(),
                    model = "x", baseUrl = baseUrl.text.toString())
                if (probe.apiKey.isBlank()) { toast("Inserisci prima la chiave"); return@setOnClickListener }
                toast("Carico l'elenco dei modelli…")
                Thread {
                    val r = runCatching { OnlineClient.listModels(probe) }
                    main.post {
                        if (isFinishing) return@post
                        r.onSuccess { models ->
                            if (models.isEmpty()) toast("Nessun modello trovato")
                            else AlertDialog.Builder(this@OnlineActivity).setTitle("Modelli disponibili")
                                .setItems(models.toTypedArray()) { _, w -> model.setText(models[w]) }.show()
                        }.onFailure { toast("Errore: ${OnlineBrain.classify(it).reason}") }
                    }
                }.start()
            }
        }

        box.addView(TextView(this).apply { text = "Servizio" })
        box.addView(spinner)
        box.addView(name)
        box.addView(key)
        box.addView(keyLink)
        box.addView(model)
        box.addView(listBtn)
        box.addView(baseUrl)
        box.addView(enabled)

        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo account" else "Modifica account")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salva") { _, _ ->
                val pr = selected()
                AccountStore.upsert(Account(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { pr.label },
                    provider = pr,
                    apiKey = key.text.toString().trim(),
                    model = model.text.toString().trim().ifEmpty { pr.defaultModel },
                    baseUrl = baseUrl.text.toString().trim(),
                    enabled = enabled.isChecked,
                    requests = existing?.requests ?: 0
                ))
                rebuild()
            }
            .setNegativeButton("Annulla", null)
        if (existing != null) d.setNeutralButton("Elimina") { _, _ -> AccountStore.remove(existing); rebuild() }
        d.show()
    }

    private fun test(a: Account) {
        toast("Provo ${a.name}…")
        Thread {
            val reply = StringBuilder()
            val r = runCatching {
                OnlineClient.stream(a, "Rispondi in italiano con una sola frase breve.",
                    listOf(Turn("user", "Presentati in cinque parole.")), CallHandle()) { reply.append(it) }
            }
            r.onSuccess { AccountStore.markOk(a) }.onFailure {
                val f = OnlineBrain.classify(it); AccountStore.markFailed(a, f.cooldownMs, f.reason)
            }
            main.post {
                if (isFinishing) return@post
                val body = r.fold(
                    onSuccess = { "Funziona ✓\n\n${reply.toString().trim()}" },
                    onFailure = {
                        val detail = (it as? ProviderError)?.body?.take(400) ?: (it.message ?: "")
                        "Non funziona: ${OnlineBrain.classify(it).reason}\n\n$detail"
                    }
                )
                AlertDialog.Builder(this).setTitle(a.name).setMessage(body).setPositiveButton("Ok", null).show()
                rebuild()
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // --- accesso -------------------------------------------------------------------------

    private fun loginOpenRouter() {
        toast("Si apre il browser: entra con il tuo account OpenRouter e conferma.")
        OpenRouterLogin.start(this) { result, error ->
            if (result == null) {
                if (!isFinishing && !isDestroyed) toast("OpenRouter: ${error ?: "accesso annullato"}")
                return@start
            }
            val existing = AccountStore.accounts.firstOrNull { it.provider == Provider.OPENROUTER && it.apiKey == result.key }
            val acc = existing ?: Account(name = "OpenRouter", provider = Provider.OPENROUTER, apiKey = result.key,
                model = result.model, baseUrl = "")
            AccountStore.upsert(acc)                       // salvato anche se la schermata nel frattempo è stata chiusa
            if (isFinishing || isDestroyed) return@start
            rebuild()
            AlertDialog.Builder(this).setTitle("OpenRouter collegato ✓")
                .setMessage("Modello scelto: ${acc.model}\n\nPuoi cambiarlo con Modifica › Scegli il modello dall'elenco " +
                    "(quelli che finiscono con «:free» sono gratuiti).")
                .setPositiveButton("Prova adesso") { _, _ -> test(acc) }
                .setNegativeButton("Ok", null).show()
        }
    }

    private fun openKeyPage(p: Provider) {
        if (p.keyUrl.isBlank()) return
        pendingProvider = p
        toast("Entra con il tuo account, crea una chiave API e copiala: poi torna su SAM.")
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }
        catch (e: Exception) { pendingProvider = null; toast("Non trovo un browser") }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android legge gli appunti solo quando l'app ha il focus: per questo qui e non in onResume
        val p = pendingProvider ?: return
        if (!hasFocus) return
        main.postDelayed({ checkClipboard(p) }, 300)
    }

    private fun checkClipboard(p: Provider) {
        if (pendingProvider != p || isFinishing) return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val clip = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (e: Exception) { null }
        val key = KeySniffer.match(p, clip) ?: return
        if (AccountStore.accounts.any { it.apiKey == key }) { pendingProvider = null; toast("Questa chiave c'è già."); return }
        pendingProvider = null
        AlertDialog.Builder(this).setTitle("Chiave di ${p.label} trovata")
            .setMessage("Negli appunti c'è una chiave di ${p.label} (${key.take(8)}…). La aggiungo e la provo?")
            .setPositiveButton("Sì") { _, _ ->
                val n = AccountStore.accounts.count { it.provider == p }
                val acc = Account(name = if (n == 0) p.label else "${p.label} ${n + 1}", provider = p, apiKey = key,
                    model = p.defaultModel, baseUrl = "")
                AccountStore.upsert(acc)
                // per sicurezza la chiave non resta negli appunti
                try { if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() } catch (e: Exception) {}
                rebuild()
                test(acc)
            }
            .setNegativeButton("No", null).show()
    }

    override fun onDestroy() {
        if (isFinishing) OpenRouterLogin.cancel()
        super.onDestroy()
    }
}
