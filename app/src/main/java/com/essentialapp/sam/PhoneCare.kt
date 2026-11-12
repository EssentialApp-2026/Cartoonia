package com.essentialapp.sam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import java.io.File

/**
 * 2.0: la cura di sé sul telefono: i fatti per il controllo di salute (permessi, modelli, spazio), le copie di
 * sicurezza di ogni giorno, la pulizia dei file temporanei, il registro e la guardia degli avvii.
 * I file: registro.tsv e avvii.txt nella memoria privata, le copie in Android/data/…/files/copie.
 */
object PhoneCare {
    @Volatile private var j: Journal? = null
    fun journal(ctx: Context): Journal = j ?: synchronized(this) { j ?: Journal(File(ctx.applicationContext.filesDir, "registro.tsv")).also { j = it } }
    fun guard(ctx: Context) = CrashGuard(File(ctx.applicationContext.filesDir, "avvii.txt"))
    /** tre avvii di fila finiti male: il cervello non si carica da solo (i comandi funzionano) */
    @Volatile var safeMode = false
    /** per il controllo: cosa dice il servizio vocale del cervello e della voce (null = a posto) */
    @Volatile var brainProblem: String? = null
    @Volatile var brainLoaded = false
    @Volatile var ttsProblem: String? = null

    private val skip = setOf("registro.tsv", "registro.tsv.tmp", "avvii.txt")
    /** i file piccoli di SAM: ricordi, mente, conversazione, il tuo nome, la tua voce */
    private fun dataFiles(ctx: Context): List<File> = ctx.filesDir.listFiles()?.filter {
        it.isFile && it.length() < 3_000_000L && it.name !in skip && !it.name.endsWith(".tmp") && !it.name.endsWith(".ripristino")
    } ?: emptyList()

    fun backupsDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "copie")
    fun backups(ctx: Context): Backups { val app = ctx.applicationContext; return Backups(backupsDir(app), { dataFiles(app) }) }

    fun version(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "2.0" } catch (e: Exception) { "2.0" }

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun checks(ctx: Context, now: Long = System.currentTimeMillis()): List<Health.Check> {
        val out = ArrayList<Health.Check>()
        // microfono e ascolto
        out.add(when {
            !has(ctx, Manifest.permission.RECORD_AUDIO) -> Health.Check("microfono", "Microfono", Health.Level.FAIL, "Manca il permesso del microfono: non ti sento", "Dammelo dalla configurazione, con l'ingranaggio")
            !ModelStore.voskReady(ctx) -> Health.Check("microfono", "Ascolto", Health.Level.FAIL, "Manca la voce italiana per capirti", "Importala dalla configurazione")
            !VoiceService.running -> Health.Check("microfono", "Ascolto", Health.Level.WARN, "Sono fermo: non sto ascoltando", "Apri SAM o tocca Parla")
            else -> Health.Check("microfono", "Ascolto", Health.Level.OK, "Il permesso c'è e ti ascolto")
        })
        // cervello
        val f = SamApp.llmFile(ctx)
        val known = f?.let { Brains.byFile(it.name) }
        var c = Health.brainFile(f, known?.name ?: f?.nameWithoutExtension ?: "", known?.bytes?.let { it * 9 / 10 } ?: -1L)
        if (c.level == Health.Level.OK && f != null) c = when {
            safeMode -> Health.Check("cervello", "Cervello", Health.Level.WARN, "Sono in modalità sicura: il cervello non l'ho caricato", "Lo carico se dici riparati", auto = true)
            brainProblem != null -> Health.Check("cervello", "Cervello", Health.Level.WARN, "Il cervello c'è ma non si è caricato (${brainProblem})", "Riprovo se dici riparati", auto = true)
            else -> Health.Check("cervello", "Cervello", Health.Level.OK, c.detail + (if (brainLoaded) ", carico" else ""))
        }
        out.add(c)
        // la voce con cui parlo
        out.add(ttsProblem?.let { Health.Check("voce", "Voce", Health.Level.WARN, it, "Scegli una voce dalla configurazione, sezione La voce di SAM") }
            ?: Health.Check("voce", "Voce", Health.Level.OK, "La sintesi vocale risponde"))
        // i permessi che servono per lavorare in sottofondo
        val missing = ArrayList<String>()
        try {
            if (!Hardware.accessibilityOn(ctx)) missing.add("accessibilità")
            if (!android.provider.Settings.canDrawOverlays(ctx)) missing.add("mostra sopra altre app")
            if (!NotificationReader.isEnabled(ctx)) missing.add("notifiche")
            if (Hardware.isXiaomi() && Hardware.xiaomiBackgroundPopups(ctx) == false) missing.add("pop-up in background")
        } catch (e: Throwable) {}
        out.add(if (missing.isEmpty()) Health.Check("permessi", "Permessi speciali", Health.Level.OK, "Ci sono tutti")
            else Health.Check("permessi", "Permessi speciali", Health.Level.WARN, "Mancano: ${missing.joinToString(", ")}. Senza, alcune cose in sottofondo non le posso fare", "Attivali dalla configurazione, sezione Permessi speciali"))
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        out.add(if (try { pm.isIgnoringBatteryOptimizations(ctx.packageName) } catch (e: Throwable) { true }) Health.Check("batteria", "Batteria", Health.Level.OK, "Android non mi chiude per risparmiare")
            else Health.Check("batteria", "Batteria", Health.Level.WARN, "Android può chiudermi per risparmiare batteria", "Togli il risparmio batteria dalla configurazione"))
        out.add(Health.memoryFile(File(ctx.filesDir, "ricordi.tsv")))
        out.add(Health.backups(backups(ctx), now))
        out.add(Health.storage(try { (ctx.getExternalFilesDir(null) ?: ctx.filesDir).usableSpace } catch (e: Throwable) { -1L }, "telefono"))
        out.add(Health.journal(journal(ctx), now))
        return out
    }

    /** I file temporanei di SAM (la sua cache): quelli più vecchi di [olderMs]. Mai i modelli, mai i tuoi file. */
    fun clean(ctx: Context, now: Long = System.currentTimeMillis(), olderMs: Long = 2 * 86_400_000L): Long {
        var n = Health.clean(ctx.cacheDir, now, olderMs)
        ctx.externalCacheDir?.let { n += Health.clean(it, now, olderMs) }
        return n
    }

    fun space(ctx: Context): String {
        val models = Health.dirSize(SamApp.modelsDir(ctx)) + Health.dirSize(SamApp.engineCacheDir(ctx))
        val enc = Health.dirSize(SamApp.encyclopediaDir(ctx))
        val cache = Health.dirSize(ctx.cacheDir) + (ctx.externalCacheDir?.let { Health.dirSize(it) } ?: 0L)
        val free = try { (ctx.getExternalFilesDir(null) ?: ctx.filesDir).usableSpace } catch (e: Throwable) { -1L }
        return "I modelli occupano ${Health.size(models)}" + (if (enc > 0) ", l'enciclopedia ${Health.size(enc)}" else "") + ", i file temporanei ${Health.size(cache)}." +
            (if (free >= 0) " Sul telefono restano ${Health.size(free)} liberi." else "")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)
    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("cura_auto", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("cura_auto", on).apply()

    /**
     * La manutenzione di ogni giorno (quando SAM è fermo): copia, pulizia, controllo in silenzio.
     * Ritorna cosa dire alla prossima occasione (null = niente da dire o già fatta oggi).
     */
    fun daily(ctx: Context, now: Long = System.currentTimeMillis()): String? {
        val today = java.time.LocalDate.now().toString()
        if (!autoOn(ctx) || prefs(ctx).getString("cura_giorno", "") == today) return null
        prefs(ctx).edit().putString("cura_giorno", today).apply()
        val b = backups(ctx)
        if (b.due(now)) b.make(now, Backups.AUTO)?.let { journal(ctx).did("copia di sicurezza automatica (${Health.size(it.bytes)})", now) }
        val freed = clean(ctx, now)
        if (freed > 20_000_000L) journal(ctx).did("pulizia: liberati ${Health.size(freed)}", now)
        val bad = checks(ctx, now).filter { it.level == Health.Level.FAIL }
        return if (bad.isEmpty()) null else "Mi sono controllato e c'è qualcosa che non va: " + bad.joinToString(" ") { it.detail.trimEnd('.') + "." } + " Di': riparati, e provo a sistemare."
    }
}
