package com.essentialapp.sam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.method.LinkMovementMethod
import android.text.util.Linkify

/** Schermata principale: la sfera di SAM, la trascrizione e i pulsanti. */
class MainActivity : Activity(), VoiceService.UiListener {

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private var title: TextView? = null
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val lines = ArrayList<Pair<String, String>>()
    /** il fumetto di ogni riga (stesso ordine di [lines]) */
    private val bubbles = ArrayList<View>()
    /** 2.0: il pallino della salute nell'intestazione (verde, giallo, rosso) */
    private var healthDot: android.graphics.drawable.GradientDrawable? = null
    /** 2.0: il benvenuto con le proposte, finché la conversazione è vuota */
    private var welcome: View? = null

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ModelStore.voskReady(this)) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            // spazio per barra di stato e tasti di navigazione (i pulsanti non finiscono sotto)
            padForSystemBars((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }

        // intestazione (1.16, in stile app di chat): il nome, lo stato e i tasti Ferma e ⚙︎
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        TextView(this).apply {
            text = Identity.name; setTextColor(Color.WHITE); textSize = 24f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            titles.addView(this); title = this
        }
        status = TextView(this).apply {
            text = "Di' “${Identity.name}” quando vuoi, oppure scrivimi"; setTextColor(MUTED); textSize = 13f; maxLines = 2
        }
        // 2.0: lo stato sta su una riga sua sotto l'intestazione (accanto ai cinque tasti non ci stava)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 2.0: i tasti dell'intestazione sono icone disegnate (prima erano emoji, diverse da telefono a telefono)
        fun side() = LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt()).also { it.leftMargin = (5 * dp).toInt() }
        // le skill (1.17): funzioni pronte da toccare
        // 2.0.1: le Skill tornano con la scritta (la sola icona non si capiva)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), round(ME, 20f), null)
            setPadding((10 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_bolt); setColorFilter(Color.WHITE) },
                LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).also { it.rightMargin = (4 * dp).toInt() })
            addView(TextView(this@MainActivity).apply { text = "Skill"; setTextColor(Color.WHITE); textSize = 14.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
            contentDescription = "Skill"
            setOnClickListener { showSkills() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).also { it.leftMargin = (6 * dp).toInt() })
        // la mente (1.23): cosa sa di te, i tuoi impegni, le abitudini, il diario
        header.addView(round(R.drawable.ic_mind, "La mente", TEXT, CARD) { showMind() }, side())
        // lo stato (2.0): il pallino dice come sta SAM, toccandolo si vedono i controlli
        header.addView(healthButton(), side())
        header.addView(round(R.drawable.ic_stop, "Ferma", TEXT, CARD) { VoiceService.stop(this) }, side())
        header.addView(round(R.drawable.ic_gear, "Configurazione", TEXT, CARD) { startActivity(Intent(this, SetupActivity::class.java)) }, side())
        root.addView(header, lp(matchW = true))
        root.addView(status, lp(matchW = true).also { it.topMargin = (2 * dp).toInt() })

        orb = OrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (130 * dp).toInt())
            .also { it.topMargin = (4 * dp).toInt() })

        // la conversazione, a fumetti
        scroll = ScrollView(this).apply { isFillViewport = true }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            .also { it.topMargin = (4 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() })

        // la barra in basso: 📎, il campo per scrivere, 🎙️ (parla) e ➤ (invia)
        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(CARD, 26f)
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        }
        input = EditText(this).apply {
            hint = "Scrivi a ${Identity.name}…"; setHintTextColor(MUTED)
            setTextColor(Color.WHITE); textSize = 16f
            background = null
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false); maxLines = 4
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        // 📎: dai un file a SAM (documenti, PDF, Word, Excel, foto): te lo legge, te lo riassume, risponde alle domande
        fun small() = LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt())
        typeRow.addView(round(R.drawable.ic_attach, "Allega un file", MUTED, Color.TRANSPARENT) { pickFile() }, small())
        typeRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        typeRow.addView(round(R.drawable.ic_mic, "Parla", TEXT, Color.TRANSPARENT) { VoiceService.wakeNow(this) }, small())
        typeRow.addView(round(R.drawable.ic_send, "Invia", Color.WHITE, ME) { sendTyped() }, small().also { it.leftMargin = (4 * dp).toInt() })
        root.addView(typeRow, lp(matchW = true))
        // con la tastiera aperta la sfera si toglie, così resta spazio per la conversazione
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val kb = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            val want = if (kb) View.GONE else View.VISIBLE
            if (orb.visibility != want) orb.visibility = want
        }

        setContentView(root)
        askMediaIfWanted(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); askMediaIfWanted(intent) }

    /** 1.21: SAM vuole vedere le tue foto e non ha il permesso: lo si chiede qui (il servizio non può). */
    private fun askMediaIfWanted(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_ASK_MEDIA, false) != true) return
        i.removeExtra(EXTRA_ASK_MEDIA)
        val want = Gallery.permissions().toMutableList()
        if (android.os.Build.VERSION.SDK_INT >= 34) want += "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        requestPermissions(want.toTypedArray(), ASK_MEDIA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_MEDIA) VoiceService.mediaAllowed(this, Gallery.allowed(this))
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing || !::orb.isInitialized) return
        VoiceService.ui = this
        // 1.22: il nome scelto (cambiato a voce o dalle impostazioni)
        title?.text = Identity.name; input.hint = "Scrivi a ${Identity.name}…"
        // la schermata si riapre: si mostra lo stato vero di SAM (non l'ultima scritta che aveva lei)
        if (VoiceService.running) {
            status.text = VoiceService.lastStatus
            orb.setMode(VoiceService.lastMode)
        }
        // e la conversazione di prima, anche se nel frattempo il telefono aveva chiuso SAM
        lines.clear(); lines.addAll(VoiceService.recentLines(this)); render()
        refreshHealth()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoiceService.start(this)
        } else {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onStop() { if (VoiceService.ui === this) VoiceService.ui = null; super.onStop() }

    // ---- UiListener (le callback arrivano dal thread principale) -----------
    override fun onMode(m: OrbView.Mode) { runOnUiThread { orb.setMode(m) } }
    override fun onStatus(s: String) { runOnUiThread { status.text = s; if (title?.text != Identity.name) title?.text = Identity.name } }
    override fun onLine(speaker: String, text: String) = runOnUiThread {
        lines.add(speaker to text)
        welcome?.let { chat.removeView(it); welcome = null }
        val v = bubble(speaker, text); bubbles.add(v); chat.addView(v)
        if (speaker == "Stato") refreshHealth()
        while (lines.size > 80) { lines.removeAt(0); chat.removeView(bubbles.removeAt(0)) }
        toBottom(force = true)
    }
    override fun onReplace(speaker: String, text: String) = runOnUiThread {
        val i = lines.indexOfLast { it.first == speaker }
        if (i < 0) { onLine(speaker, text); return@runOnUiThread }
        lines[i] = speaker to text
        val near = isNearBottom()
        val old = bubbles[i]
        val tv = old.tag as? TextView
        if (tv != null && kindOf(speaker) == Kind.AI) setMarkdown(tv, text)
        else { val v = bubble(speaker, text); val pos = chat.indexOfChild(old); chat.removeView(old); chat.addView(v, pos); bubbles[i] = v }
        if (near) toBottom(force = false)
    }

    /** Sceglie un file dal telefono (o da Drive) da dare a SAM. */
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, SamFiles.OPENABLE)
        try { startActivityForResult(i, PICK_FILE) }
        catch (e: Exception) { android.widget.Toast.makeText(this, "Non trovo l'app per scegliere i file.", android.widget.Toast.LENGTH_LONG).show() }
    }

    @Deprecated("API di Activity: qui basta")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val type = data.type ?: contentResolver.getType(uri)
        Thread {
            val r = try { Imports.copy(this, uri, type) } catch (e: Exception) { null }
            runOnUiThread {
                if (r == null) android.widget.Toast.makeText(this, "SAM non riesce ad aprire questo file (troppo grande o non leggibile).",
                    android.widget.Toast.LENGTH_LONG).show()
                else VoiceService.openDocument(this, r.first, r.second, r.third)
            }
        }.start()
    }

    /** Manda a SAM quello che hai scritto: lo tratta come se l'avessi detto, e risponde sullo schermo. */
    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        VoiceService.typed(this, t)
    }

    private fun render() {
        chat.removeAllViews(); bubbles.clear(); welcome = null
        for ((who, what) in lines) { val v = bubble(who, what); bubbles.add(v); chat.addView(v) }
        if (lines.isEmpty()) welcome = welcomeCard().also { chat.addView(it) }
        toBottom(force = true)
    }

    // ---- i fumetti --------------------------------------------------------
    private enum class Kind { ME, AI, SOURCES, FILE, THOUGHT, CARD, NOTE, MEDIA }

    private fun kindOf(speaker: String): Kind = when (speaker) {
        "Tu" -> Kind.ME
        "Fonti" -> Kind.SOURCES
        "File" -> Kind.FILE
        "Media" -> Kind.MEDIA
        "Ragionamento" -> Kind.THOUGHT
        "Piano", "Ricerca", "Disegno", "Stato" -> Kind.CARD
        "Info", "Dettaglio", "Memoria", "Enciclopedia", "Vista", "Azione" -> Kind.NOTE
        else -> Kind.AI     // SAM, o l'altra intelligenza artificiale con cui parla
    }

    private fun isNearBottom(): Boolean = chat.height - (scroll.scrollY + scroll.height) < (160 * dp)

    private fun toBottom(force: Boolean) { scroll.post { if (force || isNearBottom()) scroll.fullScroll(View.FOCUS_DOWN) } }

    /** Un fumetto: il tag della vista è il TextView del testo (per aggiornarlo mentre la risposta arriva). */
    private fun bubble(speaker: String, text: String): View {
        val kind = kindOf(speaker)
        if (kind == Kind.MEDIA) return mediaBubble(text)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (kind == Kind.ME) Gravity.END else Gravity.START
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val maxW = (resources.displayMetrics.widthPixels * (if (kind == Kind.ME) 0.8 else 0.92)).toInt()
        val tv = TextView(this).apply {
            textSize = 15.5f; setLineSpacing(0f, 1.2f); maxWidth = maxW
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        when (kind) {
            Kind.ME -> { tv.text = text; tv.setTextColor(Color.WHITE); tv.background = round(ME, 18f) }
            Kind.AI -> {
                tv.setTextColor(TEXT); tv.background = round(CARD, 18f)
                if (speaker != "SAM") wrap.addView(label(speaker))      // l'altra IA: il suo nome sopra
                setMarkdown(tv, text)
            }
            Kind.SOURCES -> {
                wrap.addView(label("Fonti"))
                tv.text = text; tv.textSize = 13f; tv.setTextColor(MUTED); tv.background = round(SOFT, 14f)
                Linkify.addLinks(tv, Linkify.WEB_URLS); tv.setLinkTextColor(ACCENT); tv.movementMethod = LinkMovementMethod.getInstance()
            }
            Kind.FILE -> {
                tv.text = text; tv.textSize = 13.5f; tv.setTextColor(ACCENT); tv.background = round(SOFT, 14f)
                tv.setOnClickListener { FileOut.last(this)?.let { f -> if (!FileOut.open(this, f)) toast("Non trovo un'app per aprirlo.") } ?: toast("Il file non c'è più.") }
            }
            Kind.THOUGHT -> {
                // il ragionamento si apre e si chiude toccandolo, come nelle app di chat
                tv.textSize = 13f; tv.setTextColor(MUTED); tv.background = round(SOFT, 14f)
                var open = false
                fun show() { tv.text = if (open) "Ragionamento ▴\n\n$text" else "Ragionamento (tocca per vedere) ▾" }
                show(); tv.setOnClickListener { open = !open; show() }
            }
            Kind.CARD -> {
                wrap.addView(label(when (speaker) { "Piano" -> "Piano"; "Ricerca" -> "Ricerche"; "Stato" -> "Cura di sé"; else -> "Disegno" }))
                tv.textSize = 13.5f; tv.setTextColor(TEXT); tv.background = round(SOFT, 14f)
                if (speaker == "Stato") setMarkdown(tv, text) else tv.text = text
            }
            Kind.MEDIA -> {}     // (fatto sopra, in mediaBubble)
            Kind.NOTE -> {
                tv.text = "$speaker · $text"; tv.textSize = 12.5f; tv.setTextColor(MUTED)
                tv.setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            }
        }
        if (kind == Kind.ME || kind == Kind.AI || kind == Kind.CARD) tv.setOnLongClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SAM", lines.getOrNull(bubbles.indexOf(wrap))?.second ?: tv.text))
            toast("Copiato"); true
        }
        wrap.addView(tv)
        wrap.tag = tv
        return wrap
    }

    // ---- 1.21: foto e video nella chat ---------------------------------------
    private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Le foto e i video (una riga di [MediaCommands.line]): una grande se è sola, altrimenti a griglia da 3. Tocchi e si apre. */
    private fun mediaBubble(text: String): View {
        val items = MediaCommands.items(text)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        val avail = resources.displayMetrics.widthPixels - (24 * dp).toInt()
        val gap = (4 * dp).toInt()
        if (items.size == 1) {
            val side = (avail * 0.75).toInt()
            wrap.addView(mediaCell(items[0], side, single = true), LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            val cell = (avail - 2 * gap) / 3
            for (row in items.chunked(3)) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((j, it) in row.withIndex()) r.addView(mediaCell(it, cell, single = false),
                    LinearLayout.LayoutParams(cell, cell).also { lp -> if (j > 0) lp.leftMargin = gap })
                wrap.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = gap })
            }
        }
        return wrap
    }

    private fun mediaCell(it: MediaCommands.Item, side: Int, single: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { background = round(SOFT, 12f); clipToOutline = true }
        val img = android.widget.ImageView(this).apply {
            scaleType = if (single) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = single
            if (single) { maxWidth = side; maxHeight = (resources.displayMetrics.heightPixels * 0.55).toInt(); minimumHeight = (120 * dp).toInt() }
        }
        frame.addView(img, android.widget.FrameLayout.LayoutParams(
            if (single) android.widget.FrameLayout.LayoutParams.WRAP_CONTENT else android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            if (single) android.widget.FrameLayout.LayoutParams.WRAP_CONTENT else android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        if (it.isVideo) frame.addView(TextView(this).apply {
            text = "▶ " + (if (it.durationMs > 0) MediaCommands.duration(it.durationMs) else "video")
            setTextColor(Color.WHITE); textSize = 12f; background = round(Color.argb(150, 0, 0, 0), 8f)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).also { lp -> lp.setMargins((6 * dp).toInt(), 0, 0, (6 * dp).toInt()) })
        val uri = android.net.Uri.parse(it.ref)
        val key = "${it.ref}@$side"
        val cached = thumbs.get(key)
        if (cached != null) img.setImageBitmap(cached)
        else thumbPool.execute {
            val bmp = try { contentResolver.loadThumbnail(uri, android.util.Size(side, side), null) } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp != null) { thumbs.put(key, bmp); img.setImageBitmap(bmp) }
                else { img.setImageDrawable(null); img.contentDescription = "non c'è più"; img.minimumHeight = (60 * dp).toInt() }
            }
        }
        frame.setOnClickListener { _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, it.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (e: Exception) { toast("Non trovo un'app per aprirlo, o non c'è più.") }
        }
        frame.setOnLongClickListener { _ ->
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(it.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Condividi")) } catch (e: Exception) {}
            true
        }
        return frame
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(MUTED); setPadding((6 * dp).toInt(), 0, 0, (3 * dp).toInt())
    }

    /** Il testo formattato ([Md]) dentro un fumetto: titoli, grassetto, codice, elenchi, numeri delle fonti, link. */
    private fun setMarkdown(tv: TextView, text: String) {
        val r = Md.render(text)
        val sp = android.text.SpannableStringBuilder(r.text)
        val flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        for (st in r.styles) {
            when (st.kind) {
                Md.Kind.BOLD -> sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st.start, st.end, flags)
                Md.Kind.ITALIC -> sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), st.start, st.end, flags)
                Md.Kind.CODE -> {
                    sp.setSpan(android.text.style.TypefaceSpan("monospace"), st.start, st.end, flags)
                    sp.setSpan(android.text.style.BackgroundColorSpan(CODE_BG), st.start, st.end, flags)
                }
                Md.Kind.CODE_BLOCK -> {
                    sp.setSpan(android.text.style.TypefaceSpan("monospace"), st.start, st.end, flags)
                    sp.setSpan(android.text.style.BackgroundColorSpan(CODE_BG), st.start, st.end, flags)
                    sp.setSpan(android.text.style.RelativeSizeSpan(0.85f), st.start, st.end, flags)
                    sp.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0xA5, 0xD6, 0xA7)), st.start, st.end, flags)
                }
                Md.Kind.H1, Md.Kind.H2, Md.Kind.H3 -> {
                    sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st.start, st.end, flags)
                    sp.setSpan(android.text.style.RelativeSizeSpan(when (st.kind) { Md.Kind.H1 -> 1.3f; Md.Kind.H2 -> 1.18f; else -> 1.08f }), st.start, st.end, flags)
                    sp.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), st.start, st.end, flags)
                }
                Md.Kind.CITE -> {
                    sp.setSpan(android.text.style.ForegroundColorSpan(ACCENT), st.start, st.end, flags)
                    sp.setSpan(android.text.style.RelativeSizeSpan(0.8f), st.start, st.end, flags)
                }
                Md.Kind.BULLET -> {}
            }
        }
        tv.setText(sp, TextView.BufferType.SPANNABLE)
        if (Linkify.addLinks(tv, Linkify.WEB_URLS)) { tv.setLinkTextColor(ACCENT); tv.movementMethod = LinkMovementMethod.getInstance() }
    }

    private fun round(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, if (t.length > 40) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()

    // ---- 2.0: icone, benvenuto e stato -------------------------------------
    /** Un tasto tondo con un'icona disegnata; [desc] è per chi usa la lettura dello schermo e compare tenendo premuto. */
    private fun round(iconRes: Int, desc: String, tint: Int, bg: Int, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(iconRes); setColorFilter(tint)
        val shape = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(bg) }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape,
            android.graphics.drawable.GradientDrawable().apply { this.shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) })
        val p = (9 * dp).toInt(); setPadding(p, p, p, p)
        contentDescription = desc; tooltipText = desc
        setOnClickListener { onClick() }
    }

    /** Il tasto dello stato: un cerchio col pallino colorato in mezzo. */
    private fun healthButton(): View {
        val dot = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(MUTED) }
        healthDot = dot
        val ring = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(CARD) }
        val inset = (13 * dp).toInt()
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply { setLayerInset(1, inset, inset, inset, inset) }
        return View(this).apply {
            background = layers; contentDescription = "Stato di ${Identity.name}"; tooltipText = "Stato"
            setOnClickListener { showHealth() }
        }
    }

    private fun levelColor(l: Health.Level) = when (l) { Health.Level.OK -> GOOD; Health.Level.WARN -> WARN; Health.Level.FAIL -> BAD }

    /** Aggiorna il pallino (i controlli leggono file: fuori dal thread principale). */
    private fun refreshHealth() {
        Thread {
            val worst = try { Health.worst(PhoneCare.checks(this)) } catch (e: Throwable) { return@Thread }
            runOnUiThread { healthDot?.setColor(levelColor(worst)) }
        }.start()
    }

    /** La conversazione è vuota: due righe di benvenuto e qualche frase da toccare per provare. */
    private fun welcomeCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(SOFT, 20f)
        setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
        val owner = try { FileMemory(this@MainActivity).ownerName() } catch (e: Throwable) { null }
        addView(TextView(this@MainActivity).apply { text = if (owner != null) "Ciao $owner" else "Ciao"; setTextColor(Color.WHITE); textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD })
        addView(TextView(this@MainActivity).apply { text = "Di' «${Identity.name}» e parla, oppure scrivimi qui sotto. Tutto resta sul telefono."
            setTextColor(MUTED); textSize = 14f; setLineSpacing(0f, 1.15f); setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt()) })
        for (pairOf in listOf("Cosa sai fare?", "Controllati", "Cosa c'è di nuovo?", "Che ore sono?").chunked(2)) {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            for (h in pairOf) row.addView(TextView(this@MainActivity).apply {
                text = h; textSize = 14f; setTextColor(TEXT); gravity = Gravity.CENTER; background = round(CARD, 14f)
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { VoiceService.typed(this@MainActivity, h) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) })
            addView(row)
        }
    }

    private var healthDialog: android.app.Dialog? = null

    /** Lo stato: i controlli uno per uno, e i tasti per riparare, fare una copia, leggere il registro. */
    private fun showHealth(message: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt()) }
        val head = TextView(this).apply { text = message ?: "Controllo…"; setTextColor(MUTED); textSize = 13.5f; setLineSpacing(0f, 1.15f); setPadding((4 * dp).toInt(), 0, 0, (8 * dp).toInt()) }
        box.addView(head)
        val d = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Stato di ${Identity.name}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Ripara") { _, _ ->
                toast("Sistemo quello che posso…")
                Thread { val t = try { VoiceService.repairFromUi(this) } catch (e: Throwable) { "Non ci sono riuscito: ${e.message}" }
                    runOnUiThread { if (!isFinishing) showHealth(t) } }.start()
            }
            .setNeutralButton("Altro…") { _, _ -> healthMore() }
            .setNegativeButton("Chiudi", null)
            .show()
        healthDialog = d
        Thread {
            val cs = try { PhoneCare.checks(this) } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                if (!d.isShowing) return@runOnUiThread
                val worst = Health.worst(cs); healthDot?.setColor(levelColor(worst))
                if (message == null) head.text = Health.headline(cs) + (if (worst == Health.Level.OK) ". Mi controllo da solo ogni giorno." else ". Quello che posso lo sistemo io: tocca Ripara.")
                for (c in cs.sortedBy { -it.level.ordinal }) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(SOFT, 14f)
                        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt()) }
                    row.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(levelColor(c.level)) } },
                        LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()).also { it.topMargin = (5 * dp).toInt(); it.rightMargin = (10 * dp).toInt() })
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(TextView(this).apply { text = c.title; setTextColor(Color.WHITE); textSize = 14.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
                    col.addView(TextView(this).apply { text = c.detail; setTextColor(MUTED); textSize = 13f })
                    if (c.level != Health.Level.OK && c.fix != null) col.addView(TextView(this).apply { text = c.fix; setTextColor(WARN); textSize = 13f })
                    row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (6 * dp).toInt() })
                }
            }
        }.start()
    }

    /** Dallo stato: copia adesso, ripristina, registro, pulizia. */
    private fun healthMore() {
        val items = arrayOf("Fai una copia di sicurezza adesso", "Ripristina una copia…", "Leggi il registro", "Fai pulizia dei file temporanei")
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Manutenzione")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Thread { val t = VoiceService.backupFromUi(this); runOnUiThread { toast(t); refreshHealth() } }.start()
                    1 -> pickBackup()
                    2 -> showText("Il registro", "## Registro\n" + PhoneCare.journal(this).markdown(50))
                    3 -> Thread { val f = PhoneCare.clean(this, olderMs = 3_600_000L)
                        runOnUiThread { toast(if (f > 0) "Liberati ${Health.size(f)} di file temporanei." else "Era già pulito.") } }.start()
                }
            }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun pickBackup() {
        val now = System.currentTimeMillis(); val b = PhoneCare.backups(this); val all = b.list()
        if (all.isEmpty()) { toast("Non c'è ancora nessuna copia."); return }
        val names = all.map { "Copia " + b.whenText(it, now) + when (it.kind) { Backups.MANUAL -> " (fatta da te)"; Backups.BEFORE -> " (prima di un ripristino)"; else -> "" } }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Quale copia rimetto?")
            .setItems(names) { _, which ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Ripristino")
                    .setMessage("Rimetto ricordi e mente com'erano nella ${names[which].replaceFirstChar { it.lowercase() }}? Quello che c'è adesso lo salvo prima in un'altra copia.")
                    .setPositiveButton("Ripristina") { _, _ -> Thread { val t = VoiceService.restoreFromUi(this, all[which]); runOnUiThread { toast(t); refreshHealth() } }.start() }
                    .setNegativeButton("Annulla", null).show()
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun showText(title: String, md: String) {
        val tv = TextView(this).apply { setTextColor(TEXT); textSize = 13.5f; setLineSpacing(0f, 1.2f)
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt()) }
        setMarkdown(tv, md)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Chiudi", null).show()
    }

    // ---- la mente (1.23) ---------------------------------------------------
    private fun showMind() {
        val tv = TextView(this).apply {
            setTextColor(TEXT); textSize = 14.5f; setLineSpacing(0f, 1.2f)
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
        }
        setMarkdown(tv, VoiceService.mindReport(this))
        val box = ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("La mente di ${Identity.name}")
            .setView(box)
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Riordina adesso") { _, _ ->
                val n = VoiceService.sleepNow(this)
                toast(if (n > 0) "Ho scritto $n pagine nuove nel diario." else "Niente di nuovo da riordinare.")
            }
            .show()
    }

    // ---- le skill ---------------------------------------------------------
    private var skillsDialog: android.app.Dialog? = null

    /** Il pannello delle skill: schede a due colonne con emoji, nome e cosa fanno. */
    private fun showSkills() {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        }
        TextView(this).apply {
            text = "Tocca una skill: scrivi cosa ti serve e SAM fa il resto, sul telefono."
            textSize = 13f; setTextColor(MUTED); setPadding(0, 0, 0, (8 * dp).toInt())
            grid.addView(this)
        }
        var row: LinearLayout? = null
        for ((i, sk) in Skills.ALL.withIndex()) {
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(CARD, 14f)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                addView(TextView(this@MainActivity).apply { text = "${sk.emoji}  ${sk.name}"; textSize = 15f; setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD })
                addView(TextView(this@MainActivity).apply { text = sk.desc; textSize = 12f; setTextColor(MUTED) })
                setOnClickListener { skillsDialog?.dismiss(); if (sk.kind == Skills.Kind.FILE) pickFile() else askSkill(sk) }
            }
            row!!.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also {
                it.setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) })
        }
        if (Skills.ALL.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val sv = ScrollView(this).apply { addView(grid) }
        skillsDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Skill di ${Identity.name}").setView(sv).setNegativeButton("Chiudi", null).show()
    }

    /** Il testo per una skill (e il secondo campo, se serve: la lingua, il tono). */
    private fun askSkill(sk: Skills.Skill) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (6 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val extra = sk.extraLabel?.let { label ->
            box.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(MUTED) })
            EditText(this).apply { setText(sk.extraDefault); setTextColor(Color.WHITE); textSize = 15f; setSingleLine(true) }.also { box.addView(it) }
        }
        val text = EditText(this).apply {
            hint = sk.hint; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START
            if (sk.code) typeface = android.graphics.Typeface.MONOSPACE
        }
        box.addView(text)
        val d = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${sk.emoji} ${sk.name}").setView(box)
            .setPositiveButton("Vai") { _, _ ->
                val t = text.text.toString()
                if (t.isBlank()) toast("Scrivi prima cosa ti serve.")
                else VoiceService.typed(this, Skills.encode(sk.id, t, extra?.text?.toString() ?: ""))
            }
            .setNegativeButton("Annulla", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        text.requestFocus()
    }

    private fun pill(label: String, color: Int = TEXT, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(color); background = round(CARD, 16f); gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        setOnClickListener { onClick() }
    }

    private fun icon(label: String, color: Int = TEXT, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(color); gravity = Gravity.CENTER
        setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        setOnClickListener { onClick() }
    }

    // ---- piccoli aiuti UI --------------------------------------------------
    private fun lp(matchW: Boolean = false) = LinearLayout.LayoutParams(
        if (matchW) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    private fun wlp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        .also { it.setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }

    companion object {
        private const val PICK_FILE = 7
        private const val ASK_MEDIA = 8
        /** 1.21: aprire la schermata chiedendo il permesso di vedere foto e video */
        const val EXTRA_ASK_MEDIA = "chiedi_galleria"
        // i colori (scuri, come le app di chat)
        private val BG = Color.rgb(13, 15, 22)
        private val CARD = Color.rgb(30, 34, 48)
        private val SOFT = Color.rgb(22, 26, 38)
        private val ME = Color.rgb(37, 99, 235)
        private val TEXT = Color.rgb(226, 231, 240)
        private val MUTED = Color.rgb(140, 150, 170)
        private val ACCENT = Color.rgb(96, 165, 250)
        private val CODE_BG = Color.rgb(15, 18, 28)
        private val GOOD = Color.rgb(52, 211, 153)
        private val WARN = Color.rgb(251, 191, 36)
        private val BAD = Color.rgb(248, 113, 113)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}
