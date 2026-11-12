package com.essentialapp.sam

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * PDF senza librerie esterne: il testo lo estrae Android (da Android 15, come sul Redmi Note 14 Pro);
 * le pagine scansionate si trasformano in immagini, che legge il cervello IA con la vista.
 */
object PdfText {

    class Result(val text: String, val pages: Int)

    /** Il testo delle prime [maxPages] pagine. Testo vuoto = PDF fatto di immagini (scansione) o Android troppo vecchio. */
    fun extract(file: File, maxPages: Int = 80): Result {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                val n = r.pageCount
                val sb = StringBuilder()
                if (Build.VERSION.SDK_INT >= 35) {
                    // Page.getTextContents() esiste da Android 15: si chiama per riflessione così l'app gira anche prima
                    val get = try { PdfRenderer.Page::class.java.getMethod("getTextContents") } catch (e: Exception) { null }
                    if (get != null) for (i in 0 until minOf(n, maxPages)) {
                        val p = r.openPage(i)
                        try {
                            val list = get.invoke(p) as? List<*> ?: emptyList<Any>()
                            for (c in list) {
                                val t = c?.javaClass?.getMethod("getText")?.invoke(c) as? String
                                if (!t.isNullOrBlank()) sb.append(t.trim()).append('\n')
                            }
                        } catch (e: Exception) {
                        } finally { p.close() }
                        sb.append('\n')
                    }
                }
                return Result(sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim(), n)
            } finally { r.close() }
        } finally { pfd.close() }
    }

    /** La pagina [page] come JPEG (lato lungo [maxSide] pixel), per farla leggere al cervello con la vista. */
    fun renderPage(file: File, page: Int, out: File, maxSide: Int = 1400): Boolean {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                if (page !in 0 until r.pageCount) return false
                val p = r.openPage(page)
                try {
                    val scale = maxSide.toFloat() / maxOf(p.width, p.height)
                    val w = maxOf(1, (p.width * scale).toInt()); val h = maxOf(1, (p.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    return true
                } finally { p.close() }
            } finally { r.close() }
        } catch (e: Exception) {
            return false
        } finally { pfd.close() }
    }
}
