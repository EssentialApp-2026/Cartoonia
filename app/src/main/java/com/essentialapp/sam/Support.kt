package com.essentialapp.sam

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.WindowInsets
import java.io.File

/**
 * Android 15 disegna le app anche sotto la barra di stato e i tasti di navigazione: si aggiunge
 * lo spazio giusto (barre di sistema, notch e tastiera) così nessun pulsante resta coperto.
 */
fun View.padForSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
    setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(left + b.left, top + b.top, right + b.right, bottom + b.bottom)
        insets
    }
    requestApplyInsets()
}

/** Application: punto unico per sapere dove stanno i modelli. */
class SamApp : Application() {
    /** 1.22: il nome dell'assistente scelto da te, appena l'app parte */
    override fun onCreate() {
        super.onCreate()
        IdentityStore.load(this)
        // 2.0: un errore che nessuno ha gestito finisce nel registro («cosa è andato storto?»), poi Android fa il suo corso
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { PhoneCare.journal(this).crash(t.name, e) } catch (x: Throwable) {}
            before?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Cartella dei modelli, nella memoria privata dell'app (niente permessi, spazio ampio). */
        fun modelsDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

        fun voskDir(ctx: Context) = File(modelsDir(ctx), "vosk-it")

        /**
         * La copia di lavoro del motore del cervello (i pesi già preparati per il processore, circa 2 GB).
         * Non sta nella cache: Android e la pulizia di Xiaomi la svuotano, e il cervello doveva rifarla
         * da capo ogni volta (minuti di attesa). Si cancella solo cambiando o togliendo il cervello.
         */
        fun engineCacheDir(ctx: Context): File = File(ctx.filesDir, "cervello_lavoro").apply { mkdirs() }

        /** Toglie la copia di lavoro dei pesi: di un cervello solo ([model] = nome del file) o di tutti (null). */
        fun clearEngineCache(ctx: Context, model: String? = null) {
            try { engineCacheDir(ctx).listFiles()?.filter { model == null || it.name.startsWith(model) }?.forEach { it.delete() } } catch (e: Exception) {}
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)

        /** I cervelli sul telefono (dalla 1.10 possono essere più d'uno), dal più bravo del catalogo. */
        fun llmFiles(ctx: Context): List<File> =
            (modelsDir(ctx).listFiles()?.filter { it.isFile && ModelFile.isBrainName(it.name) } ?: emptyList())
                .sortedWith(Comparator { a, b ->
                    val ra = Brains.byFile(a.name)?.let { Brains.rank(it) } ?: 99
                    val rb = Brains.byFile(b.name)?.let { Brains.rank(it) } ?: 99
                    if (ra != rb) ra - rb else a.name.compareTo(b.name)
                })

        /** Il cervello in uso: quello scelto (a voce o nella configurazione) se c'è ancora, altrimenti il più bravo. */
        fun llmFile(ctx: Context): File? {
            val all = llmFiles(ctx)
            val chosen = prefs(ctx).getString("cervello_attivo", null)
            return all.firstOrNull { it.name == chosen } ?: all.firstOrNull()
        }

        /** Sceglie il cervello da usare (nome del file nella cartella dei modelli). */
        fun setActiveBrain(ctx: Context, fileName: String) { prefs(ctx).edit().putString("cervello_attivo", fileName).apply() }

        /** Toglie un cervello dal telefono (il file e la sua copia di lavoro). Quello in uso passa a un altro. */
        fun removeBrain(ctx: Context, fileName: String) {
            File(modelsDir(ctx), fileName).delete()
            clearEngineCache(ctx, fileName)
            if (prefs(ctx).getString("cervello_attivo", null) == fileName) prefs(ctx).edit().remove("cervello_attivo").apply()
        }

        /** La cartella dove SAM tiene le enciclopedie che scarica da solo (dentro la sua memoria: le gestisce lui). */
        fun encyclopediaDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "enciclopedia").apply { mkdirs() }

        /** Dove arrivano gli scaricamenti in corso (poi i file vanno al loro posto). */
        fun downloadsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "scaricamenti").apply { mkdirs() }
    }
}

/**
 * Ricordi e nome del proprietario, su file di testo nella memoria privata dell'app: restano per sempre
 * (anche dopo gli aggiornamenti di SAM) finché non chiedi di cancellarli. Si perdono solo disinstallando
 * SAM o cancellandone i dati dalle impostazioni di Android.
 * Formato di ricordi.tsv, una riga per ricordo: tipo, chiave, data (millisecondi), testo, separati da tab.
 */
class FileMemory(private val dir: File) : Memory {
    constructor(ctx: Context) : this(ctx.filesDir)

    private val file = File(dir, "ricordi.tsv")
    private val oldNotes = File(dir, "note.txt")        // le note della versione 1.4
    private val owner = File(dir, "proprietario.txt")

    companion object {
        // un solo elenco in memoria per tutto il processo: servizio vocale e configurazione vedono gli stessi ricordi
        private val lock = Any()
        private val caches = HashMap<String, MutableList<Memo>>()
        /** Dimentica l'elenco in memoria (non i file): come se l'app ripartisse. Usato nei collaudi. */
        fun dropCache() = synchronized(lock) { caches.clear() }
    }

    private fun load(): MutableList<Memo> {
        caches[file.absolutePath]?.let { return it }
        val list = ArrayList<Memo>()
        try {
            if (file.exists()) file.readLines(Charsets.UTF_8).forEach { line -> parse(line)?.let { list.add(it) } }
        } catch (e: Exception) {}
        // passaggio dalla 1.4: le vecchie note diventano ricordi, il file vecchio resta come copia
        if (oldNotes.exists()) {
            try {
                val now = System.currentTimeMillis()
                oldNotes.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.forEach { n ->
                    if (list.none { it.text.equals(n, ignoreCase = true) })
                        list.add(Memo(Remember.NOTA, n.replaceFirstChar { it.uppercase() }, "", now))
                }
                save(list)
                oldNotes.renameTo(File(dir, "note_vecchie.txt"))
            } catch (e: Exception) {}
        }
        caches[file.absolutePath] = list
        return list
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun parse(line: String): Memo? {
        val p = line.split('\t', limit = 4)
        if (p.size < 4 || p[3].isBlank()) return null
        return Memo(p[0], p[3], p[1], p[2].toLongOrNull() ?: 0L)
    }

    /** Scrittura sicura: prima un file temporaneo, poi lo scambio. Se il telefono si spegne a metà, i ricordi restano. */
    private fun save(list: List<Memo>) {
        val tmp = File(dir, "ricordi.tmp")
        tmp.writeText(list.joinToString("") { "${clean(it.kind)}\t${clean(it.key)}\t${it.at}\t${clean(it.text)}\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }

    override fun memos(): List<Memo> = synchronized(lock) { load().toList() }

    override fun addMemo(m: Memo) = synchronized(lock) {
        val l = load()
        l.add(m.copy(kind = clean(m.kind), key = clean(m.key), text = clean(m.text)))
        save(l)
    }

    override fun removeMemo(m: Memo) = synchronized(lock) {
        val l = load()
        if (l.remove(m)) save(l)
    }

    override fun clearMemos() = synchronized(lock) {
        val l = load()
        l.clear()
        save(l)
    }

    override fun ownerName(): String? =
        if (owner.exists()) owner.readText().trim().takeIf { it.isNotEmpty() } else null
    override fun setOwnerName(name: String) { owner.writeText(name.trim()) }

    /** Una copia di tutti i ricordi (e del nome), da salvare dove vuoi: serve se reinstalli SAM o cambi telefono. */
    fun exportText(): String = synchronized(lock) {
        val sb = StringBuilder("# Ricordi di SAM: tipo, chiave, data, testo (separati da tab)\n")
        ownerName()?.let { sb.append("proprietario\t\t0\t").append(clean(it)).append('\n') }
        for (m in load()) sb.append("${clean(m.kind)}\t${clean(m.key)}\t${m.at}\t${clean(m.text)}\n")
        sb.toString()
    }

    /** Aggiunge i ricordi di una copia (quelli che ci sono già non si duplicano). Ritorna quanti ne ha aggiunti. */
    fun importText(text: String): Int = synchronized(lock) {
        val l = load()
        var n = 0
        for (line in text.lines()) {
            if (line.startsWith("#")) continue
            val m = parse(line.trimEnd('\r')) ?: continue
            if (m.kind == "proprietario") { if (ownerName() == null) setOwnerName(m.text); continue }
            if (m.kind !in setOf(Remember.NOTA, Remember.FATTO, Remember.ALIAS, Remember.SCELTA, Remember.REGOLA, Remember.LEZIONE)) continue
            if (l.any { it.kind == m.kind && it.key == m.key && it.text.equals(m.text, ignoreCase = true) }) continue
            l.add(m); n++
        }
        if (n > 0) save(l)
        n
    }
}

/**
 * L'impronta della tua voce (voce.txt) e la scelta "rispondi solo a me", nella memoria privata dell'app.
 * Restano finché non dici "SAM, dimentica la mia voce".
 */
class VoiceStore(private val dir: File) {
    constructor(ctx: Context) : this(ctx.filesDir)

    private val file = File(dir, "voce.txt")
    private val only = File(dir, "voce_solo_me.txt")

    fun load(): VoiceProfile? = try { if (file.exists()) VoiceProfile.fromText(file.readText(Charsets.UTF_8)) else null } catch (e: Exception) { null }

    fun save(p: VoiceProfile) {
        val tmp = File(dir, "voce.tmp")
        tmp.writeText(p.toText(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(p.toText(), Charsets.UTF_8); tmp.delete() }
    }

    fun delete() { file.delete(); only.delete() }

    var onlyOwner: Boolean
        get() = only.exists()
        set(v) { if (v) only.writeText("1") else only.delete() }
}

/**
 * Il file dell'enciclopedia (.zim di Kiwix) scelto nella configurazione: SAM lo legge dove sta (nella cartella
 * Download o dove l'hai messo), con il permesso che Android dà quando lo scegli. Niente copie: sono molti GB.
 */
object EncyclopediaFile {
    private const val KEY = "enciclopedia_uri"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)

    fun uri(ctx: Context): String? = prefs(ctx).getString(KEY, null)

    /** true se l'enciclopedia è un file scaricato da SAM (un percorso, non un file scelto da te). */
    fun isPath(u: String): Boolean = u.startsWith("/")

    /** true se il file è uno di quelli che SAM ha scaricato da solo, nella sua cartella (lo può cancellare lui). */
    fun isOwn(ctx: Context, u: String?): Boolean =
        u != null && isPath(u) && File(u).absoluteFile.parentFile?.absolutePath == SamApp.encyclopediaDir(ctx).absolutePath

    /** Apre l'enciclopedia (un file scelto da te, o un percorso se l'ha scaricata SAM). Lancia un'eccezione con il motivo se non si legge. */
    fun open(ctx: Context, uri: String): Encyclopedia {
        if (isPath(uri)) {
            if (!File(uri).exists()) throw IllegalStateException("il file non c'è più")
            return Encyclopedia(Zim.open(uri))
        }
        val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")
            ?: throw IllegalStateException("non riesco ad aprire il file")
        val ch = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
        return try { Encyclopedia(Zim(ch)) } catch (e: Throwable) { try { ch.close() } catch (x: Exception) {}; throw e }
    }

    /** Il nome del file ("wikipedia_it_all_nopic_2026-06.zim"), per capire di che famiglia è. null = non si sa. */
    fun fileName(ctx: Context, u: String): String? {
        if (isPath(u)) return File(u).name
        return try {
            ctx.contentResolver.query(android.net.Uri.parse(u), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /** Ricorda il file scelto (e tiene il permesso di leggerlo anche dopo il riavvio del telefono). */
    fun set(ctx: Context, uri: android.net.Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {}
        prefs(ctx).edit().putString(KEY, uri.toString()).apply()
    }

    /** Usa un'enciclopedia scaricata da SAM (un file nella sua cartella). */
    fun setPath(ctx: Context, path: String) {
        val old = uri(ctx)
        if (old != null && !isPath(old)) release(ctx, old)
        prefs(ctx).edit().putString(KEY, path).apply()
    }

    private fun release(ctx: Context, u: String) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(u), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {}
    }

    fun clear(ctx: Context) {
        uri(ctx)?.let { u -> if (!isPath(u)) release(ctx, u) }
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** Rimette l'enciclopedia di prima (il file nuovo scelto non andava bene). */
    fun restore(ctx: Context, u: String) {
        uri(ctx)?.let { cur -> if (cur != u && !isPath(cur)) release(ctx, cur) }
        prefs(ctx).edit().putString(KEY, u).apply()
    }

    /** "Wikipedia (ita), giugno 2026 · 1.912.345 voci". */
    fun describe(e: Encyclopedia): String {
        val lang = e.language?.let { " ($it)" } ?: ""
        val whenTxt = Encyclopedia.monthYear(e.date)?.let { ", $it" } ?: ""
        val n = String.format(java.util.Locale.ITALY, "%,d", e.articles)
        return "${e.name}$lang$whenTxt · $n voci"
    }
}

/** Ascolta le notifiche di sistema e tiene le ultime per leggerle a voce. */
class NotificationReader : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = ex.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        synchronized(store) {
            store.add(0, title to text)
            while (store.size > 20) store.removeAt(store.size - 1)
        }
    }

    companion object {
        private val store = mutableListOf<Pair<String, String>>()
        fun latest(): List<Pair<String, String>> = synchronized(store) { store.toList() }
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ctx.packageName)
        }
    }
}
