package com.essentialapp.sam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * "Condividi → SAM" (o "Apri con SAM"): copia il file nella memoria di SAM e lo passa al servizio vocale,
 * che te lo legge, te lo riassume o risponde alle tue domande. Niente esce dal telefono.
 * Va bene anche il testo selezionato e condiviso da un'altra app.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        Thread {
            val r = try { receive(i) } catch (e: Exception) { null }
            runOnUiThread {
                if (r == null) Toast.makeText(this, "SAM non riesce ad aprire questo file.", Toast.LENGTH_LONG).show()
                else {
                    if (ModelStore.voskReady(this)) {
                        VoiceService.openDocument(this, r.first, r.second, r.third)
                        Toast.makeText(this, "SAM apre «${r.second}»", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "Prima configura SAM: manca la voce italiana.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }

    /** Copia il file condiviso: (percorso, nome, tipo). null = niente da aprire. */
    private fun receive(i: Intent): Triple<String, String, String?>? {
        val dir = Imports.dir(this)
        @Suppress("DEPRECATION")
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri == null) {
            // solo testo (una frase, un articolo copiato)
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            val f = File(dir, "testo_condiviso.txt")
            f.writeText(text, Charsets.UTF_8)
            return Triple(f.absolutePath, (subject ?: "testo condiviso") + ".txt", "text/plain")
        }
        return Imports.copy(this, uri, i.type)
    }
}

/** Copia nella memoria di SAM un file scelto da te (tasto 📎) o condiviso da un'altra app. */
object Imports {
    /** La cartella dei documenti ricevuti: si tengono solo gli ultimi. */
    fun dir(ctx: Context): File {
        val dir = File(ctx.filesDir, "documenti").apply { mkdirs() }
        dir.listFiles()?.let { old ->
            val sorted = old.sortedWith(Comparator { a, b -> b.lastModified().compareTo(a.lastModified()) })
            for (f in sorted.drop(4)) f.delete()
        }
        return dir
    }

    /** (percorso, nome, tipo) del file copiato; null = non si riesce a leggerlo. Lancia un errore se è troppo grande. */
    fun copy(ctx: Context, uri: Uri, mimeHint: String?): Triple<String, String, String?>? {
        val dir = dir(ctx)
        val cr = ctx.contentResolver
        val name = displayName(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        val mime = mimeHint?.takeIf { it != "*/*" } ?: cr.getType(uri)
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(90).ifBlank { "documento" }
        val f = File(dir, safe)
        val inp = cr.openInputStream(uri) ?: return null
        inp.use { s ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 60_000_000) throw IllegalStateException("file troppo grande")
                    out.write(buf, 0, n)
                }
            }
        }
        return Triple(f.absolutePath, name, mime)
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }
}
