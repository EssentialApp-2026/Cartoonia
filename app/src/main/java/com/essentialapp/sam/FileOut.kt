package com.essentialapp.sam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * I file che SAM prepara per te: si salvano nella cartella Download › SAM del telefono (la vedi dall'app File),
 * in testo semplice o in PDF. Niente permessi da chiedere: Android lascia scrivere lì a ogni app.
 * Poi una notifica: toccandola il file si apre.
 */
object FileOut {
    const val FOLDER = "SAM"
    private const val CH = "sam_file"
    private const val NID = 46

    class Saved(val uri: Uri, val name: String, val mime: String)

    /**
     * Salva [text] col titolo [title]: PDF se [pdf], altrimenti testo. [code] = un file di codice ([SamFiles.CodeLang]):
     * si salva così com'è, con la sua estensione (.py, .html, .mq5…). null = non è riuscito.
     */
    fun save(ctx: Context, title: String, text: String, pdf: Boolean, code: SamFiles.CodeLang? = null): Saved? {
        val now = java.time.LocalDateTime.now()
        val name = SamFiles.fileName(title, code?.ext ?: if (pdf) "pdf" else "txt", now)
        val mime = code?.mime ?: if (pdf) "application/pdf" else "text/plain"
        val bytes = when {
            code != null -> text.toByteArray(Charsets.UTF_8)
            pdf -> pdfBytes(title, text)
            else -> (title + "\n\n" + text.trim() + "\n").toByteArray(Charsets.UTF_8)
        }
        return saveBytes(ctx, name, mime, bytes)
    }

    /** Salva [bytes] col nome [name] in Download › SAM. null = non è riuscito. */
    fun saveBytes(ctx: Context, name: String, mime: String, bytes: ByteArray): Saved? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("non si apre")
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Saved(uri, name, mime)
        } catch (e: Exception) {
            try { r.delete(uri, null, null) } catch (x: Exception) {}
            null
        }
    }

    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    /** Le slide in PDF (16:9), con lo stesso aspetto del PowerPoint: copertina scura, poi una pagina per slide. */
    fun slidesPdf(d: Slides.Deck): ByteArray {
        val w = 960; val h = 540
        val doc = PdfDocument()
        fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; if (bold) typeface = Typeface.DEFAULT_BOLD }
        fun layout(t: String, p: TextPaint, width: Int) = StaticLayout.Builder.obtain(t, 0, t.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
        val dark = Color.rgb(0x1F, 0x2A, 0x44); val accent = Color.rgb(0x3B, 0x82, 0xF6)
        try {
            // copertina
            var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            var c = page.canvas
            c.drawColor(dark)
            val tl = layout(d.title, tp(40f, Color.WHITE, bold = true), w - 140)
            c.save(); c.translate(70f, 300f - tl.height); tl.draw(c); c.restore()
            c.drawRect(70f, 318f, 270f, 323f, Paint().apply { color = accent })
            val sl = layout(d.subtitle.ifEmpty { "Presentazione preparata da SAM" }, tp(20f, Color.rgb(0xC7, 0xD2, 0xFE)), w - 140)
            c.save(); c.translate(70f, 340f); sl.draw(c); c.restore()
            doc.finishPage(page)
            for ((i, s) in d.slides.withIndex()) {
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 2).create())
                c = page.canvas
                c.drawColor(Color.WHITE)
                c.drawRect(0f, 0f, 18f, h.toFloat(), Paint().apply { color = accent })
                val t = layout(s.title, tp(30f, dark, bold = true), w - 120)
                c.save(); c.translate(56f, 40f); t.draw(c); c.restore()
                var y = 50f + t.height + 28f
                val size = when { s.bullets.size >= 6 -> 17f; s.bullets.size == 5 -> 19f; else -> 21f }
                for (b in s.bullets) {
                    val bl = layout(b, tp(size, Color.rgb(0x1F, 0x29, 0x37)), w - 160)
                    if (y + bl.height > h - 40) break
                    c.drawCircle(68f, y + size * 0.6f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
                    c.save(); c.translate(84f, y); bl.draw(c); c.restore()
                    y += bl.height + 14f
                }
                c.drawText("${i + 1} / ${d.slides.size}", w - 80f, h - 20f, tp(11f, Color.rgb(0x9C, 0xA3, 0xAF)))
                doc.finishPage(page)
            }
            val out = ByteArrayOutputStream(); doc.writeTo(out); return out.toByteArray()
        } finally { doc.close() }
    }

    /** Un PDF A4 semplice: il titolo in grassetto, poi il testo che va a capo da solo e continua nelle pagine dopo. */
    fun pdfBytes(title: String, body0: String): ByteArray {
        val body = body0.trim().ifEmpty { " " }
        val w = 595; val h = 842; val m = 50f
        val width = (w - 2 * m).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val titlePaint = TextPaint(paint).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val footPaint = TextPaint(paint).apply { textSize = 8f; color = Color.GRAY }
        fun layout(t: String, p: TextPaint) = StaticLayout.Builder.obtain(t, 0, t.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).setIncludePad(false).build()
        val head = layout(title, titlePaint)
        val text = layout(body, paint)
        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            while (true) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                val c = page.canvas
                var y = m
                if (pageNo == 1) {
                    c.save(); c.translate(m, y); head.draw(c); c.restore()
                    y += head.height + 16f
                }
                val avail = h - m - 14f - y
                val top = text.getLineTop(line)
                var end = line
                while (end < text.lineCount && text.getLineBottom(end) - top <= avail) end++
                if (end == line) end = line + 1                      // una riga più alta della pagina: va comunque
                val bottom = text.getLineBottom(end - 1)
                c.save()
                c.translate(m, y - top)
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                text.draw(c)
                c.restore()
                c.drawText("SAM · pagina $pageNo", m, h - m / 2, footPaint)
                doc.finishPage(page)
                line = end
                if (line >= text.lineCount || pageNo >= 400) break
                pageNo++
            }
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally { doc.close() }
    }

    private fun viewIntent(s: Saved) = Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Apre il file con l'app giusta (lettore PDF, editor di testo). false = nessuna app, o Android non lo lascia fare adesso. */
    fun open(ctx: Context, s: Saved): Boolean = try {
        ctx.startActivity(Intent.createChooser(viewIntent(s), "Apri «${s.name}»").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    /** Condivide il file (WhatsApp, email, Drive…). */
    fun share(ctx: Context, s: Saved): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).setType(s.mime).putExtra(Intent.EXTRA_STREAM, s.uri)
            .putExtra(Intent.EXTRA_SUBJECT, s.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Condividi «${s.name}»").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    /** La notifica "File pronto": toccandola si apre. */
    fun notifySaved(ctx: Context, s: Saved, where: String = "Download › $FOLDER") {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "File di SAM", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, NID, viewIntent(s), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NID, Notification.Builder(ctx, CH)
                .setContentTitle("File pronto: ${s.name}")
                .setContentText("In $where. Tocca per aprirlo.")
                .setSmallIcon(R.drawable.ic_stat_sam)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {}
    }

    // l'ultimo file salvato (per "apri il file", "condividi il file")
    fun remember(ctx: Context, s: Saved) {
        ctx.getSharedPreferences("sam", Context.MODE_PRIVATE).edit()
            .putString("file_uri", s.uri.toString()).putString("file_nome", s.name).putString("file_tipo", s.mime).apply()
    }

    fun last(ctx: Context): Saved? {
        val p = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)
        val u = p.getString("file_uri", null) ?: return null
        val s = Saved(Uri.parse(u), p.getString("file_nome", null) ?: "file", p.getString("file_tipo", null) ?: "text/plain")
        // cancellato dall'app File nel frattempo?
        val exists = try { ctx.contentResolver.openInputStream(s.uri)?.use { true } ?: false } catch (e: Exception) { false }
        return if (exists) s else null
    }
}
