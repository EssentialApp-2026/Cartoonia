package com.essentialapp.sam

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Disegnare sul telefono (1.14): i tre modelli di Stable Diffusion (circa 1 GB) si scaricano una volta sola col Wi-Fi da
 * Hugging Face, poi tutto gira sul telefono, senza internet. Le immagini vanno nella Galleria, cartella "SAM".
 */
object Painter {
    private const val REPO = "https://huggingface.co/sayakpaul/kerascv_sd_tflite/resolve/main"
    /** i tre pezzi: (nome del file, dimensione indicativa per lo spazio e per dire quanto manca) */
    val FILES = listOf("text_encoder.tflite" to 124_000_000L, "diffusion_model.tflite" to 863_000_000L, "image_decoder.tflite" to 50_100_000L)
    val TOTAL = FILES.sumOf { it.second }

    fun dir(ctx: Context): File = File(SamApp.modelsDir(ctx), "immagini").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)

    /** true = i tre modelli ci sono (e non sono troppo piccoli: uno scaricamento interrotto). */
    fun ready(ctx: Context): Boolean = FILES.all { (n, size) -> File(dir(ctx), n).let { it.isFile && it.length() > size * 9 / 10 } }

    /** Gli scaricamenti in corso (id di Android), vuoto = nessuno. */
    private fun ids(ctx: Context): List<Long> = prefs(ctx).getString("img_ids", "")!!.split(',').mapNotNull { it.trim().toLongOrNull() }

    fun downloading(ctx: Context): Boolean = ids(ctx).isNotEmpty()

    /** Comincia a scaricare i modelli che mancano (solo col Wi-Fi). Ritorna la frase da dire. */
    fun startDownload(ctx: Context): String {
        if (ready(ctx)) return "Il modello per disegnare ce l'ho già: chiedimi per esempio, disegna un gatto astronauta."
        if (downloading(ctx)) return progress(ctx) ?: "Lo sto già scaricando."
        val missing = FILES.filter { (n, size) -> File(dir(ctx), n).let { !it.isFile || it.length() <= size * 9 / 10 } }
        val need = missing.sumOf { it.second } + 500_000_000L
        val free = try { android.os.StatFs(dir(ctx).path).availableBytes } catch (e: Exception) { -1L }
        if (free in 0 until need) return "Per disegnare servono ${Brains.gb(need)} liberi sul telefono, e adesso non ci sono."
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val got = ArrayList<Long>()
        for ((n, _) in missing) {
            File(SamApp.downloadsDir(ctx), "img_$n").delete()
            val r = DownloadManager.Request(Uri.parse("$REPO/$n"))
                .setTitle("SAM: modello per disegnare ($n)")
                .setDescription("Stable Diffusion, una volta sola (solo col Wi-Fi)")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false).setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(ctx, null, "scaricamenti/img_$n")
            try { got.add(dm.enqueue(r)) } catch (e: Exception) {}
        }
        if (got.isEmpty()) return "Non riesco a far partire lo scaricamento del modello per disegnare."
        prefs(ctx).edit().putString("img_ids", got.joinToString(",")).apply()
        return "Ok, scarico il modello per disegnare: circa ${Brains.gb(missing.sumOf { it.second })}, solo col Wi-Fi. " +
            "Quando è pronto te lo dico; intanto tutto il resto funziona."
    }

    /** A che punto è lo scaricamento ("al 40 per cento"), null = non si sta scaricando. */
    fun progress(ctx: Context): String? {
        val ids = ids(ctx); if (ids.isEmpty()) return null
        var done = 0L; var total = 0L
        val dm = ctx.getSystemService(DownloadManager::class.java)
        try {
            dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                while (c.moveToNext()) {
                    done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    total += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {}
        if (total <= 0) return "Sto scaricando il modello per disegnare: aspetto il Wi-Fi."
        return "Sto scaricando il modello per disegnare: sono al ${done * 100 / total} per cento."
    }

    /**
     * Gli scaricamenti finiti: i file vanno al loro posto. Ritorna la novità da dire (null = niente di nuovo).
     * Uno scaricamento fallito si toglie: il prossimo "scarica il modello per disegnare" lo riprende.
     */
    fun processDownloads(ctx: Context): String? = synchronized(this) {
        val ids = ids(ctx); if (ids.isEmpty()) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val left = ArrayList<Long>()
        var failed = false
        for (id in ids) {
            val st = try {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c -> if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -2 } ?: -1
            } catch (e: Exception) { -1 }
            when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val name = try {
                        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                            if (c.moveToFirst()) Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))).lastPathSegment else null }
                    } catch (e: Exception) { null }
                    val src = name?.let { File(SamApp.downloadsDir(ctx), it) }
                    val target = name?.removePrefix("img_")
                    if (src != null && target != null && src.isFile) {
                        val dst = File(dir(ctx), target)
                        dst.delete()
                        if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
                    } else failed = true
                    try { dm.remove(id) } catch (e: Exception) {}
                }
                DownloadManager.STATUS_FAILED, -2 -> { failed = true; try { dm.remove(id) } catch (e: Exception) {} }
                -1 -> left.add(id)              // Android non risponde adesso: si riprova dopo
                else -> left.add(id)            // ancora in corso
            }
        }
        prefs(ctx).edit().putString("img_ids", left.joinToString(",")).apply()
        return when {
            left.isNotEmpty() -> null
            ready(ctx) -> "Il modello per disegnare è pronto! Chiedimi per esempio: disegna un faro sul mare al tramonto."
            failed -> "Lo scaricamento del modello per disegnare non è riuscito: riprova con «scarica il modello per disegnare», col Wi-Fi."
            else -> null
        }
    }

    // ---- il disegno
    @Volatile private var sd: StableDiffusion? = null
    @Volatile private var tok: ClipTokenizer? = null

    /** Carica i modelli (la prima volta qualche secondo). Thread di background. */
    fun load(ctx: Context, lib: String, threads: Int): StableDiffusion = synchronized(this) {
        sd?.let { return it }
        if (tok == null) tok = (try { ctx.assets.open("clip/bpe_simple_vocab_16e6.txt.gz") }
            catch (e: java.io.FileNotFoundException) { ctx.assets.open("clip/bpe_simple_vocab_16e6.txt") }).use { ClipTokenizer(it) }
        val d = dir(ctx)
        val s = StableDiffusion(lib, File(d, FILES[0].first).absolutePath, File(d, FILES[1].first).absolutePath, File(d, FILES[2].first).absolutePath, threads)
        sd = s
        s
    }

    fun tokenizer(): ClipTokenizer? = tok

    /** Libera la memoria (circa 1-2 GB) quando non si disegna più. */
    fun release() = synchronized(this) { try { sd?.close() } catch (t: Throwable) {}; sd = null }

    /** Salva i pixel RGB 512×512 come PNG nella Galleria (Immagini › SAM). */
    fun savePng(ctx: Context, rgb: ByteArray, title: String): FileOut.Saved? {
        val px = IntArray(512 * 512) { i -> 0xFF shl 24 or ((rgb[3 * i].toInt() and 0xff) shl 16) or ((rgb[3 * i + 1].toInt() and 0xff) shl 8) or (rgb[3 * i + 2].toInt() and 0xff) }
        val bmp = Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888)
        val name = SamFiles.fileName(title, "png", java.time.LocalDateTime.now())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SAM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: throw IllegalStateException()
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            FileOut.Saved(uri, name, "image/png")
        } catch (e: Exception) { try { r.delete(uri, null, null) } catch (x: Exception) {}; null }
        finally { bmp.recycle() }
    }
}
