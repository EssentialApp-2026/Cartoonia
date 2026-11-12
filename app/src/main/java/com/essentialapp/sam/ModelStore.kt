package com.essentialapp.sam

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

/**
 * Modelli di SAM, tutto in locale (internet serve solo per scaricare gli aggiornamenti, col Wi-Fi).
 * Entrambi si importano da file presenti sul telefono:
 *  - voce italiana (Vosk): lo zip del modello, anche diviso in più parti (.part1, .part2…);
 *  - cervello IA (.litertlm).
 */
object ModelStore {

    private const val READY_MARK = ".pronto"

    private data class Picked(val uri: Uri, val name: String, val size: Long)

    private fun describe(ctx: Context, uri: Uri): Picked {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Picked(uri, name, size)
    }

    // ---------------------------------------------------------------- voce
    fun voskReady(ctx: Context): Boolean = File(SamApp.voskDir(ctx), READY_MARK).exists()

    // ---------------------------------------------------------------- modello delle voci (dentro l'app)
    private const val SPK_ASSETS = "vosk-spk"

    /**
     * Il modello delle voci di Vosk (riconoscere chi parla) viaggia dentro l'app: alla prima accensione
     * si copia nella memoria dell'app, perché la libreria lo legge da una cartella. null = non disponibile.
     */
    @Synchronized
    fun speakerModel(ctx: Context): File? = try {
        val dir = File(SamApp.modelsDir(ctx), SPK_ASSETS)
        val mark = File(dir, READY_MARK)
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toString() } catch (e: Exception) { "?" }
        if (!mark.exists() || mark.readText() != version) {
            dir.deleteRecursively(); dir.mkdirs()
            val names = ctx.assets.list(SPK_ASSETS) ?: emptyArray()
            if (names.isEmpty()) throw IllegalStateException("modello delle voci assente")
            for (n in names) ctx.assets.open("$SPK_ASSETS/$n").use { inp -> File(dir, n).outputStream().use { inp.copyTo(it) } }
            mark.writeText(version)
        }
        if (File(dir, "final.ext.raw").exists()) dir else null
    } catch (e: Throwable) { null }

    /**
     * Importa la voce italiana dai file scelti: lo zip intero oppure le sue parti,
     * che vengono rimesse in ordine di nome e lette di seguito. Thread di background.
     */
    @Synchronized
    fun importVosk(ctx: Context, uris: List<Uri>, onPct: (Int) -> Unit) {
        if (uris.isEmpty()) throw IllegalArgumentException("nessun file scelto")
        val parts = uris.map { describe(ctx, it) }.sortedWith(Comparator { a, b -> a.name.compareTo(b.name) })
        val total = parts.sumOf { if (it.size > 0) it.size else 0L }
        val dir = SamApp.voskDir(ctx)
        if (dir.exists()) dir.deleteRecursively()
        val streams = parts.map { ctx.contentResolver.openInputStream(it.uri) ?: throw IllegalStateException("file non leggibile: ${it.name}") }
        try {
            SequenceInputStream(Collections.enumeration<InputStream>(streams)).use { all ->
                Unzip.stripTop(all, total, dir, onPct)
            }
            if (!File(dir, "am/final.mdl").exists() || !File(dir, "conf/model.conf").exists())
                throw IllegalArgumentException("questi file non sono il modello vocale (servono tutte le parti)")
            File(dir, READY_MARK).writeText("ok")
            onPct(100)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw if (e is java.util.zip.ZipException || e is java.io.EOFException)
                IllegalArgumentException("modello incompleto: seleziona insieme tutte le parti della voce")
            else e
        } finally {
            streams.forEach { try { it.close() } catch (x: Exception) {} }
        }
    }

    // ---------------------------------------------------------------- cervello IA
    fun llmFile(ctx: Context): File? = SamApp.llmFile(ctx)

    /**
     * Esito dell'import: nome del file, se serve un avvertimento da mostrare, e gli altri cervelli rimasti sul telefono
     * (dalla 1.10 restano: si passa dall'uno all'altro a voce, oppure si tolgono dalla configurazione).
     */
    data class Imported(val name: String, val warning: String?, val others: List<String> = emptyList())

    /**
     * Copia nell'app il file del cervello scelto, dopo aver controllato che il telefono lo regga
     * (RAM e spazio) e che sia davvero un modello LiteRT-LM compatibile con il motore di SAM.
     * Thread di background.
     */
    fun importLlm(ctx: Context, uri: Uri, onPct: (Int) -> Unit): Imported {
        val p = describe(ctx, uri)
        var name = p.name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        val specs = Hardware.specs(ctx)
        val best = Brains.recommend(specs)
        val consiglio = if (best != null) " Per il tuo telefono va bene ${best.name}." else ""
        if (p.size > 0) {
            if (Brains.fit(p.size, specs) == Brains.Fit.TOO_BIG)
                throw IllegalArgumentException("questo cervello (${Brains.gb(p.size)}) è troppo pesante per un telefono con " +
                    "${Brains.nominalRamGb(specs.ramBytes)} GB di RAM: si bloccherebbe.$consiglio")
            if (specs.freeBytes > 0 && specs.freeBytes < p.size + 200_000_000L)
                throw IllegalArgumentException("spazio insufficiente: servono almeno ${Brains.gb(p.size + 200_000_000L)} liberi " +
                    "(ora ce ne sono ${Brains.gb(specs.freeBytes)}).")
        }

        val models = SamApp.modelsDir(ctx)
        val tmp = File(models, "importo_cervello.part")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("file non leggibile")
        var info: Llm.FileInfo? = null
        val kind: ModelFile.Kind
        try {
            // il formato si riconosce dal contenuto: il nome del file prende l'estensione giusta (.litertlm, .task, .gguf)
            kind = input.use { ins -> ModelFile.copyChecked(ins, p.size, tmp, onPct, p.name) }
            if (!name.endsWith("." + kind.ext, ignoreCase = true)) name = name.substringBeforeLast('.').ifEmpty { "cervello" } + "." + kind.ext
            if (p.size <= 0 && Brains.fit(tmp.length(), specs) == Brains.Fit.TOO_BIG)
                throw IllegalArgumentException("questo cervello (${Brains.gb(tmp.length())}) è troppo pesante per questo telefono.$consiglio")
            // il file dice di che tipo è e quale versione del motore richiede
            val lib = File(ctx.applicationInfo.nativeLibraryDir, "liblitert-lm.so").absolutePath
            info = try { Llm.inspect(lib, tmp.absolutePath) } catch (e: Throwable) { null }
            // (i .task di MediaPipe non dicono sempre il tipo: li prova il motore al caricamento)
            if (info != null && !info.isLlm && kind != ModelFile.Kind.TASK)
                throw IllegalArgumentException((if (kind == ModelFile.Kind.GGUF) "questo file GGUF è il pezzo della vista (mmproj), non il cervello. "
                    else "questo file non è un modello per conversare.") + consiglio)
            val need = info?.minRuntime
            if (need != null && !Llm.versionAtLeast(Llm.RUNTIME_VERSION, need))
                throw IllegalArgumentException("questo cervello richiede un motore più nuovo ($need) di quello di SAM " +
                    "(${Llm.RUNTIME_VERSION}).$consiglio")
        } catch (e: Throwable) {
            tmp.delete()   // niente file a metà se l'import fallisce
            throw e
        }
        // il cervello nuovo diventa quello in uso; gli altri restano sul telefono (si passa dall'uno all'altro a voce),
        // la configurazione chiede se toglierli. Un file con lo stesso nome viene sostituito, con la sua copia di lavoro
        val dest = File(models, name)
        val others = SamApp.llmFiles(ctx).map { it.name }.filter { it != name }
        SamApp.clearEngineCache(ctx, name)
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        SamApp.setActiveBrain(ctx, name)
        onPct(100)
        val heavy = if (Brains.fit(dest.length(), specs) == Brains.Fit.HEAVY)
            "È pesante per questo telefono: funziona, ma può essere lento.$consiglio" else null
        // detto subito, così poi "cosa vedi?" non sorprende
        val blind = if (info != null && !info.vision)
            "Questo cervello non ha la vista: per guardare con la fotocamera serve Gemma 4 E2B o E4B." else null
        val format = if (kind != ModelFile.Kind.LITERTLM) "Formato ${kind.label}: lo faccio girare col motore " +
            (if (kind == ModelFile.Kind.GGUF) "llama.cpp." else "LiteRT-LM.") else null
        val warning = listOfNotNull(format, heavy, blind).joinToString(" ").ifEmpty { null }
        return Imported(name, warning, others)
    }

    fun deleteLlm(ctx: Context) {
        SamApp.modelsDir(ctx).listFiles()?.filter { ModelFile.isBrainName(it.name) }?.forEach { it.delete() }
        SamApp.clearEngineCache(ctx)
    }

    /**
     * Controlla un cervello scaricato da SAM: che sia davvero un modello per conversare e che il motore di SAM lo sappia
     * far girare. Ritorna null se va bene, altrimenti il motivo.
     */
    fun checkBrainFile(ctx: Context, f: File): String? {
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "liblitert-lm.so").absolutePath
        val info = try { Llm.inspect(lib, f.absolutePath) } catch (e: Throwable) { return "il file non è un modello leggibile" }
        if (!info.isLlm) return "il file non è un modello per conversare"
        val need = info.minRuntime
        if (need != null && !Llm.versionAtLeast(Llm.RUNTIME_VERSION, need))
            return "richiede un motore più nuovo ($need) di quello di SAM (${Llm.RUNTIME_VERSION}): serve un SAM.apk aggiornato"
        return null
    }
}
