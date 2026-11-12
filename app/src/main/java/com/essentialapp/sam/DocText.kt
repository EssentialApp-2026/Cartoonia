package com.essentialapp.sam

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Testo dei file che condividi con SAM, tutto sul telefono e senza librerie esterne:
 * testo semplice, HTML, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), OpenDocument (.odt/.ods/.odp).
 * I PDF e le immagini li gestisce la parte Android (testo del PDF, vista del cervello IA). Kotlin puro.
 */
object DocText {

    sealed class Content {
        /** testo estratto; [pages] = pagine o fogli, se si sanno */
        data class Text(val text: String, val pages: Int = 0) : Content()
        object Pdf : Content()
        object Image : Content()
        /** formato che SAM non sa leggere: [why] da dire a voce */
        data class Unsupported(val why: String) : Content()
    }

    private val TEXT_EXT = setOf("txt", "md", "csv", "tsv", "json", "xml", "log", "srt", "ini", "cfg", "yaml", "yml",
        "mq5", "mq4", "mqh", "set", "kt", "java", "py", "js", "c", "cpp", "h", "sh", "bat", "cmd", "sql", "vtt", "text")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    /** Che tipo di file è e il suo testo. [name] serve per l'estensione, [mime] può mancare. */
    fun extract(bytes: ByteArray, name: String, mime: String?): Content {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = (mime ?: "").lowercase()
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            ext == "pdf" || m == "application/pdf" || startsWith(bytes, "%PDF") -> Content.Pdf
            ext in IMAGE_EXT || m.startsWith("image/") -> Content.Image
            ext == "docx" || m.contains("wordprocessingml") -> office(bytes, "docx")
            ext == "xlsx" || m.contains("spreadsheetml") -> office(bytes, "xlsx")
            ext == "pptx" || m.contains("presentationml") -> office(bytes, "pptx")
            ext in setOf("odt", "ods", "odp") || m.contains("opendocument") -> office(bytes, "odf")
            ext == "doc" || m == "application/msword" -> Content.Unsupported("È un file Word vecchio (.doc): aprilo e salvalo come .docx o come PDF, poi mandamelo di nuovo.")
            ext == "xls" || m == "application/vnd.ms-excel" -> Content.Unsupported("È un file Excel vecchio (.xls): salvalo come .xlsx e mandamelo di nuovo.")
            ext in setOf("htm", "html") || m.contains("html") -> Content.Text(html(decode(bytes)))
            isZip -> office(bytes, "?")
            ext in TEXT_EXT || m.startsWith("text/") || m.contains("json") || m.contains("xml") || looksLikeText(bytes) ->
                Content.Text(decode(bytes).replace("\r\n", "\n").trim())
            else -> Content.Unsupported("Questo tipo di file non lo so leggere. Mandamelo come PDF, Word o testo.")
        }
    }

    private fun startsWith(b: ByteArray, s: String) = b.size >= s.length && s.indices.all { b[it] == s[it].code.toByte() }

    /** Testo in UTF-8; se non lo è, nella codifica di Windows (lettere accentate dei vecchi file). */
    fun decode(b: ByteArray): String {
        var start = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) start = 3
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(b, start, b.size - start)).toString() }
        catch (e: Exception) { String(b, start, b.size - start, charset("windows-1252")) }
    }

    private fun looksLikeText(b: ByteArray): Boolean {
        val n = minOf(b.size, 4000)
        if (n == 0) return false
        var bad = 0
        for (i in 0 until n) { val c = b[i].toInt() and 0xFF; if (c == 0 || (c < 9) || (c in 14..31)) bad++ }
        return bad * 100 / n < 2
    }

    // ------------------------------------------------------------------ file Office e OpenDocument (zip con XML dentro)
    private fun unzip(bytes: ByteArray, want: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            var total = 0L
            while (e != null) {
                if (!e.isDirectory && want(e.name)) {
                    val data = z.readBytes()
                    total += data.size
                    if (total > 60_000_000) break          // file enorme o malformato: ci si ferma
                    out[e.name] = String(data, Charsets.UTF_8)
                }
                e = z.nextEntry
            }
        }
        return out
    }

    private fun office(bytes: ByteArray, kind: String): Content = try {
        val files = unzip(bytes) { n ->
            n == "word/document.xml" || n == "xl/sharedStrings.xml" || n == "xl/workbook.xml" ||
                (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) ||
                (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) || n == "content.xml"
        }
        when {
            "word/document.xml" in files -> Content.Text(docx(files.getValue("word/document.xml")))
            files.keys.any { it.startsWith("xl/worksheets/") } -> xlsx(files)
            files.keys.any { it.startsWith("ppt/slides/") } -> pptx(files)
            "content.xml" in files -> Content.Text(odf(files.getValue("content.xml")))
            else -> Content.Unsupported("Dentro questo file non trovo del testo che so leggere.")
        }
    } catch (e: Exception) {
        Content.Unsupported("Il file sembra rovinato: non riesco ad aprirlo.")
    }

    private val TAG = Regex("<[^>]+>")

    /** Entità XML/HTML più comuni. */
    fun entities(s: String): String {
        var t = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&nbsp;", " ").replace("&egrave;", "è").replace("&eacute;", "é").replace("&agrave;", "à")
            .replace("&ograve;", "ò").replace("&ugrave;", "ù").replace("&igrave;", "ì").replace("&euro;", "€")
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return t.replace("&amp;", "&")
    }

    private fun tidy(s: String): String =
        s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Word: un paragrafo per riga, tabulazioni e a capo rispettati. */
    fun docx(xml: String): String {
        var t = xml
        t = t.replace(Regex("<w:tab/>"), "\t").replace(Regex("<w:br[^>]*/>"), "\n").replace(Regex("<w:cr/>"), "\n")
        t = t.replace(Regex("</w:p>"), "\n").replace(Regex("</w:tc>"), "\t")
        t = t.replace(Regex("<w:instrText[^>]*>.*?</w:instrText>", RegexOption.DOT_MATCHES_ALL), "")
        return tidy(entities(TAG.replace(t, "")))
    }

    /** OpenDocument: paragrafi e titoli. */
    fun odf(xml: String): String {
        var t = xml
        t = t.replace(Regex("<text:tab/>"), "\t").replace(Regex("<text:line-break/>"), "\n")
            .replace(Regex("<text:s(?: text:c=\"(\\d+)\")?/>")) { m -> " ".repeat(m.groupValues[1].toIntOrNull() ?: 1) }
        t = t.replace(Regex("</text:p>|</text:h>"), "\n").replace(Regex("</table:table-cell>"), "\t")
        return tidy(entities(TAG.replace(t, "")))
    }

    /** PowerPoint: il testo di ogni diapositiva, in ordine. */
    private fun pptx(files: Map<String, String>): Content {
        val slides = files.keys.filter { it.startsWith("ppt/slides/slide") }
            .sortedWith(Comparator { a, b -> num(a) - num(b) })
        val sb = StringBuilder()
        for ((i, k) in slides.withIndex()) {
            val x = files.getValue(k).replace(Regex("</a:p>"), "\n")
            val texts = Regex("<a:t>(.*?)</a:t>|\\n", RegexOption.DOT_MATCHES_ALL).findAll(x)
                .joinToString("") { if (it.value == "\n") "\n" else entities(it.groupValues[1]) }
            sb.append("Diapositiva ${i + 1}.\n").append(tidy(texts)).append("\n\n")
        }
        return Content.Text(sb.toString().trim(), slides.size)
    }

    private fun num(path: String) = Regex("(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Excel: ogni foglio con il suo nome, una riga per riga, celle separate da " · ". */
    private fun xlsx(files: Map<String, String>): Content {
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { x ->
            for (si in Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(x))
                shared.add(entities(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] }))
        }
        val names = files["xl/workbook.xml"]?.let { w -> Regex("<sheet [^>]*name=\"([^\"]*)\"").findAll(w).map { entities(it.groupValues[1]) }.toList() } ?: emptyList()
        val sheets = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedWith(Comparator { a, b -> num(a) - num(b) })
        val sb = StringBuilder()
        for ((i, k) in sheets.withIndex()) {
            val rows = ArrayList<String>()
            for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(files.getValue(k))) {
                val cells = ArrayList<String>()
                for (c in Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    val value = when (type) {
                        "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { entities(it.groupValues[1]) }
                        "b" -> if (v == "1") "vero" else "falso"
                        else -> v?.let { number(it) } ?: ""
                    }
                    if (value.isNotBlank()) cells.add(value.trim())
                }
                if (cells.isNotEmpty()) rows.add(cells.joinToString(" · "))
            }
            if (rows.isEmpty()) continue
            sb.append("Foglio ").append(names.getOrNull(i) ?: "${i + 1}").append(".\n")
            rows.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return Content.Text(sb.toString().trim(), sheets.size)
    }

    /** "1234.5" -> "1234,5"; i numeri interi senza decimali. */
    private fun number(v: String): String {
        val d = v.trim().toDoubleOrNull() ?: return entities(v.trim())
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString().replace('.', ',')
    }

    /** HTML: via script, stili e tag; paragrafi e righe al loro posto. */
    fun html(s: String): String {
        var t = s.replace(Regex("<(script|style|head)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        t = t.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|h[1-6]|tr|table|section|article)>", RegexOption.IGNORE_CASE), "\n")
        return tidy(entities(TAG.replace(t, " ")))
    }

    // ------------------------------------------------------------------ pezzi da leggere, riassumere, cercare
    /** Il testo diviso in pezzi di circa [size] caratteri, senza spezzare paragrafi e frasi se si può. */
    fun chunks(text: String, size: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotBlank()) out.add(sb.toString().trim()); sb.setLength(0) }
        for (para in text.split(Regex("\\n\\s*\\n|\\n"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (sb.length + p.length + 1 <= size) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(p); continue }
            flush()
            if (p.length <= size) { sb.append(p); continue }
            // paragrafo lunghissimo: si divide per frasi, e se serve per parole
            for (s in p.split(Regex("(?<=[.!?;:])\\s+"))) {
                if (sb.length + s.length + 1 > size) flush()
                if (s.length > size) { s.chunked(size).forEach { piece -> out.add(piece.trim()) } }
                else { if (sb.isNotEmpty()) sb.append(' '); sb.append(s) }
            }
        }
        flush()
        return out
    }

    /** I [k] pezzi che parlano di più della domanda, nell'ordine del documento. */
    fun best(chunks: List<String>, question: String, k: Int = 2): List<String> {
        if (chunks.size <= k) return chunks
        val q = Remember.words(question).distinct()
        if (q.isEmpty()) return chunks.take(k)
        val scored = chunks.mapIndexed { i, c ->
            val w = Remember.words(c)
            val hits = q.sumOf { qw -> w.count { it == qw || (qw.length >= 5 && it.length >= 5 && it.take(5) == qw.take(5)) } }
            Triple(i, c, hits)
        }
        val top = scored.sortedWith(Comparator { a, b -> if (a.third != b.third) b.third - a.third else a.first - b.first }).take(k)
        if (top.all { it.third == 0 }) return chunks.take(k)
        return top.sortedWith(Comparator { a, b -> a.first - b.first }).map { it.second }
    }

    /** Pezzi sparsi in modo uniforme nel documento, al massimo [max] (per riassumere i documenti lunghi). */
    fun spread(chunks: List<String>, max: Int): List<String> {
        if (chunks.size <= max) return chunks
        return (0 until max).map { chunks[it * (chunks.size - 1) / (max - 1)] }
    }

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.any { c -> c.isLetterOrDigit() } }
}
