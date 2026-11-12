package com.essentialapp.sam

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lettore dei file .zim (il formato di Kiwix: Wikipedia senza internet), in Kotlin puro.
 * Legge direttamente dal file, senza copiarlo: cerca una voce per titolo (ricerca binaria sull'indice dei titoli),
 * segue i rinvii ("Garibaldi" -> "Giuseppe Garibaldi") e decomprime solo il pezzo che serve (zstd).
 */
class Zim(private val ch: FileChannel) : Closeable {

    class ZimException(msg: String) : Exception(msg)

    /** Una voce dell'indice: [ns] lo spazio ('C' contenuti, 'M' metadati, 'A' nei file vecchi), [redirect] >= 0 se è un rinvio. */
    class Entry(val index: Int, val ns: Char, val path: String, val title: String, val mime: Int,
                val cluster: Int, val blob: Int, val redirect: Int) {
        val isRedirect get() = redirect >= 0
    }

    val entryCount: Int
    private val major: Int
    private val minor: Int
    private val clusterCount: Int
    private val urlPtrPos: Long
    private val titlePtrPos: Long
    private val clusterPtrPos: Long
    private val checksumPos: Long
    val mimeTypes: List<String>
    private val size: Long = ch.size()

    init {
        val h = read(0, 80)
        if (h.int(0) != 72173914) throw ZimException("non è un file .zim")
        major = (h[4].toInt() and 0xFF) or ((h[5].toInt() and 0xFF) shl 8)
        minor = (h[6].toInt() and 0xFF) or ((h[7].toInt() and 0xFF) shl 8)
        entryCount = h.int(24)
        clusterCount = h.int(28)
        urlPtrPos = h.long(32)
        titlePtrPos = h.long(40)
        clusterPtrPos = h.long(48)
        val mimeListPos = h.long(56)
        checksumPos = h.long(72)
        if (entryCount <= 0 || urlPtrPos <= 0 || urlPtrPos >= size || clusterPtrPos >= size) throw ZimException("file .zim rovinato")
        val mb = read(mimeListPos, 4096)
        val list = ArrayList<String>()
        var p = 0
        while (p < mb.size) {
            var e = p
            while (e < mb.size && mb[e] != 0.toByte()) e++
            if (e == p) break
            list.add(String(mb, p, e - p, Charsets.UTF_8))
            p = e + 1
        }
        mimeTypes = list
    }

    override fun close() { try { ch.close() } catch (e: Exception) {} }

    // ------------------------------------------------------------------ lettura
    private fun read(pos: Long, len: Int): ByteArray {
        val n = minOf(len.toLong(), size - pos).toInt().coerceAtLeast(0)
        val b = ByteBuffer.allocate(n)
        var at = pos
        while (b.hasRemaining()) { val r = ch.read(b, at); if (r <= 0) break; at += r }
        return b.array()
    }

    private fun ByteArray.int(p: Int): Int =
        (this[p].toInt() and 0xFF) or ((this[p + 1].toInt() and 0xFF) shl 8) or ((this[p + 2].toInt() and 0xFF) shl 16) or ((this[p + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.long(p: Int): Long = (int(p).toLong() and 0xFFFFFFFFL) or (int(p + 4).toLong() shl 32)

    private fun urlPtr(i: Int): Long = read(urlPtrPos + 8L * i, 8).long(0)

    /** La voce numero [i] (nell'ordine dei percorsi). */
    fun entry(i: Int): Entry {
        val pos = urlPtr(i)
        var b = read(pos, 512)
        fun strEnd(from: Int): Int { var e = from; while (e < b.size && b[e] != 0.toByte()) e++; return e }
        val mime = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val ns = (b[3].toInt() and 0xFF).toChar()
        val redirect = mime == 0xFFFF
        var p = if (redirect) 12 else 16
        var e = strEnd(p)
        if (e >= b.size) { b = read(pos, 8192); e = strEnd(p) }
        val path = String(b, p, e - p, Charsets.UTF_8)
        p = e + 1
        e = strEnd(p)
        if (e >= b.size) { b = read(pos, 16384); e = strEnd(p) }
        val title = if (e > p) String(b, p, e - p, Charsets.UTF_8) else path
        return if (redirect) Entry(i, ns, path, title, mime, -1, -1, b.int(8))
        else Entry(i, ns, path, title, mime, b.int(8), b.int(12), -1)
    }

    /**
     * L'indice dei titoli: nei file vecchi è nell'intestazione, in quelli nuovi è la voce X/listing/titleOrdered
     * (v0 tutte le voci, v1 solo le voci vere). Elenco di numeri di voce in ordine di titolo.
     */
    private class TitleList(val count: Int, val at: (Int) -> Int)

    private val titles: TitleList? by lazy {
        if (titlePtrPos > 0 && titlePtrPos + 4L * entryCount <= size) return@lazy TitleList(entryCount) { k -> read(titlePtrPos + 4L * k, 4).int(0) }
        for (name in listOf("listing/titleOrdered/v0", "listing/titleOrdered/v1")) {
            val e = findByPath('X', name) ?: continue
            if (e.isRedirect || e.cluster < 0) continue
            blobInFile(e)?.let { (pos, len) -> return@lazy TitleList((len / 4).toInt()) { k -> read(pos + 4L * k, 4).int(0) } }
            val b = try { content(e) } catch (x: Exception) { continue }
            return@lazy TitleList(b.size / 4) { k -> b.int(4 * k) }
        }
        null
    }

    /** Dove sta nel file il contenuto di [e], se il suo blocco non è compresso (gli indici di solito non lo sono). */
    private fun blobInFile(e: Entry): Pair<Long, Long>? {
        val start = read(clusterPtrPos + 8L * e.cluster, 8).long(0)
        val info = read(start, 1)[0].toInt() and 0xFF
        if ((info and 0x0F) > 1) return null
        val ext = info and 0x10 != 0
        val w = if (ext) 8 else 4
        val o = read(start + 1 + w.toLong() * e.blob, 2 * w)
        val a = if (ext) o.long(0) else (o.int(0).toLong() and 0xFFFFFFFFL)
        val b = if (ext) o.long(8) else (o.int(4).toLong() and 0xFFFFFFFFL)
        return (start + 1 + a) to (b - a)
    }

    private fun cmp(ns1: Char, s1: String, ns2: Char, s2: String): Int {
        if (ns1 != ns2) return ns1.compareTo(ns2)
        // come libzim: confronto byte per byte in UTF-8
        val a = s1.toByteArray(Charsets.UTF_8); val b = s2.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** La voce con questo titolo esatto (rinvii compresi), o null. */
    fun findByTitle(ns: Char, title: String): Entry? {
        val t = titles ?: return null
        var lo = 0; var hi = t.count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val idx = t.at(mid)
            if (idx < 0 || idx >= entryCount) return null
            val e = entry(idx)
            val c = cmp(e.ns, e.title, ns, title)
            if (c == 0) return e
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    /** La voce con questo percorso esatto ("Giuseppe_Garibaldi"), o null. */
    fun findByPath(ns: Char, path: String): Entry? {
        var lo = 0; var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entry(mid)
            val c = cmp(e.ns, e.path, ns, path)
            if (c == 0) return e
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    /** Segue i rinvii fino alla voce vera. */
    fun resolve(e: Entry): Entry {
        var x = e
        var hops = 0
        while (x.isRedirect && hops++ < 8) x = entry(x.redirect)
        return x
    }

    // ------------------------------------------------------------------ contenuti (cluster compressi)
    private val cache = LinkedHashMap<Int, Pair<ByteArray, Boolean>>()

    private fun clusterData(n: Int): Pair<ByteArray, Boolean> {
        synchronized(cache) { cache[n]?.let { return it } }
        val start = read(clusterPtrPos + 8L * n, 8).long(0)
        val end = if (n + 1 < clusterCount) read(clusterPtrPos + 8L * (n + 1), 8).long(0) else checksumPos
        val len = end - start
        if (len <= 1 || len > 128L * 1024 * 1024) throw ZimException("blocco del file non valido")
        val raw = read(start, len.toInt())
        val info = raw[0].toInt() and 0xFF
        val extended = info and 0x10 != 0
        val data = when (info and 0x0F) {
            0, 1 -> raw.copyOfRange(1, raw.size)
            5 -> Zstd.decompress(raw, 1, raw.size - 1)
            4 -> throw ZimException("questo file .zim è di un formato vecchio (compressione xz): scaricane uno più recente")
            else -> throw ZimException("compressione non supportata")
        }
        val r = data to extended
        synchronized(cache) {
            cache[n] = r
            while (cache.size > 3) cache.remove(cache.keys.first())
        }
        return r
    }

    /** Il contenuto (di solito l'HTML della voce). */
    fun content(e0: Entry): ByteArray {
        val e = resolve(e0)
        if (e.isRedirect || e.cluster < 0) throw ZimException("voce senza contenuto")
        val (data, ext) = clusterData(e.cluster)
        val w = if (ext) 8 else 4
        fun off(i: Int): Long = if (ext) data.long(i * 8) else (data.int(i * 4).toLong() and 0xFFFFFFFFL)
        val nBlobs = (off(0) / w).toInt() - 1
        if (e.blob < 0 || e.blob >= nBlobs) throw ZimException("contenuto mancante")
        val a = off(e.blob).toInt(); val b = off(e.blob + 1).toInt()
        return data.copyOfRange(a, b)
    }

    /**
     * Controlla tutto il file con la sua impronta MD5 (scritta in fondo al file): false = file rovinato o incompleto.
     * Lento (legge tutti i GB): serve dopo uno scaricamento. [onPct] = avanzamento 0..100, [stop] = interrompi.
     */
    fun verify(onPct: ((Int) -> Unit)? = null, stop: (() -> Boolean)? = null): Boolean {
        if (checksumPos <= 0 || checksumPos + 16 > size) return false
        val md = java.security.MessageDigest.getInstance("MD5")
        val buf = ByteBuffer.allocate(4 shl 20)
        var pos = 0L
        var lastPct = -1
        while (pos < checksumPos) {
            if (stop?.invoke() == true) throw InterruptedException("interrotto")
            buf.clear()
            if (checksumPos - pos < buf.capacity()) buf.limit((checksumPos - pos).toInt())
            val n = ch.read(buf, pos)
            if (n <= 0) return false
            buf.flip()
            md.update(buf)
            pos += n
            val pct = (pos * 100 / checksumPos).toInt()
            if (pct != lastPct) { lastPct = pct; onPct?.invoke(pct) }
        }
        return md.digest().contentEquals(read(checksumPos, 16))
    }

    /** Un metadato ("Date", "Title", "Language"…) o null. */
    fun metadata(name: String): String? = try {
        findByPath('M', name)?.let { String(content(it), Charsets.UTF_8).trim() }
    } catch (e: Exception) { null }

    /** Lo spazio dei nomi delle voci: 'C' nei file nuovi (versione 6.1 in su), 'A' nei vecchi. */
    val articleNs: Char get() = if (major > 6 || (major == 6 && minor >= 1)) 'C' else 'A'

    /** La voce con questo titolo: prima come percorso ("Giuseppe_Garibaldi"), poi nell'indice dei titoli. */
    fun findArticle(title: String): Entry? =
        findByPath(articleNs, title.replace(' ', '_')) ?: findByPath(articleNs, title) ?: findByTitle(articleNs, title)

    companion object {
        fun open(path: String): Zim = Zim(java.io.RandomAccessFile(path, "r").channel)
    }
}

/** Dall'HTML di una voce di Wikipedia al testo dell'introduzione, da leggere o da dare al cervello. */
object WikiText {
    private val TOKEN = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--.*?-->|([^<]+)", RegexOption.DOT_MATCHES_ALL)
    private val SKIP = setOf("table", "sup", "style", "script", "figure", "math", "audio", "video", "noscript", "figcaption", "ol", "ul", "dl")
    private val SKIP_CLASS = Regex("hatnote|noprint|thumb|navbox|metadata|sinottico|infobox|mw-editsection|reference|nota|coord|geo|mw-empty-elt|vedi-anche")
    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "wbr", "source", "col", "area", "base", "track", "embed", "param")

    /** Il testo dei paragrafi dell'introduzione (fino al primo titolo di sezione), al massimo [max] caratteri. */
    fun intro(html: String, max: Int = 1400): String {
        var h = html
        h.indexOf("<body").let { if (it >= 0) h = h.substring(it) }
        Regex("<h2[\\s>]").find(h)?.let { h = h.substring(0, it.range.first) }
        val out = StringBuilder()
        var skipName: String? = null
        var skipDepth = 0
        var pDepth = 0
        for (m in TOKEN.findAll(h)) {
            val text = m.groupValues[4]
            if (text.isNotEmpty()) {
                if (skipName == null && pDepth > 0) out.append(text)
                continue
            }
            val name = m.groupValues[2].lowercase()
            if (name.isEmpty()) continue     // commento
            val closing = m.groupValues[1] == "/"
            val attrs = m.groupValues[3]
            val selfClosing = attrs.trimEnd().endsWith("/") || name in VOID
            if (skipName != null) {
                if (name == skipName && !selfClosing) { if (closing) { if (--skipDepth == 0) skipName = null } else skipDepth++ }
                continue
            }
            if (!closing && !selfClosing) {
                val cls = Regex("class\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""
                if (name in SKIP || (name in setOf("div", "span", "p") && SKIP_CLASS.containsMatchIn(cls))) {
                    skipName = name; skipDepth = 1; continue
                }
                if (name == "p") pDepth++
            } else if (closing && name == "p") {
                if (pDepth > 0) { pDepth--; out.append('\n') }
            } else if (name == "br" && pDepth > 0) out.append(' ')
        }
        var t = decode(out.toString())
        t = t.replace(Regex("\\[\\s*(?:\\d+|nota \\d+|senza fonte|citazione necessaria|[a-z])\\s*]"), "")
        t = t.replace(Regex("\\(\\s*[;,]?\\s*\\)"), "").replace(Regex("\\(\\s+"), "(").replace(Regex("\\s+\\)"), ")")
            .replace(Regex("\\s+([,.;:])"), "$1")
        t = t.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.length > 1 }.joinToString("\n")
        return cut(t, max)
    }

    /** Taglia a fine frase, senza superare [max] caratteri. */
    fun cut(t: String, max: Int): String {
        if (t.length <= max) return t
        val part = t.substring(0, max)
        val end = maxOf(part.lastIndexOf(". "), part.lastIndexOf(".\n"), part.lastIndexOf("; "))
        return if (end > max / 3) part.substring(0, end + 1) else part.substringBeforeLast(' ') + "…"
    }

    /** Le prime [n] frasi (per leggerle a voce). */
    fun sentences(t: String, n: Int): String {
        val flat = t.replace('\n', ' ')
        val parts = Regex("(?<=[.!?])\\s+(?=[A-ZÀ-Ý«\"])").split(flat)
        return parts.take(n).joinToString(" ").trim()
    }

    /** È una pagina di disambiguazione ("Garibaldi può riferirsi a: …")? */
    fun isDisambiguation(title: String, intro: String): Boolean =
        title.contains("(disambigua)") || Regex("\\bpu[òo] (?:riferirsi|indicare|significare|essere)\\b|\\bpossono riferirsi\\b|\\bdisambigua").containsMatchIn(intro.take(300))

    /** I collegamenti degli elenchi (la prima voce di una disambiguazione è di solito la più famosa). */
    fun listLinks(html: String): List<String> {
        val out = ArrayList<String>()
        for (li in Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val a = Regex("<a\\s[^>]*href\\s*=\\s*\"([^\"#?]+)[^\"]*\"", RegexOption.IGNORE_CASE).find(li.groupValues[1]) ?: continue
            var href = a.groupValues[1]
            if (href.startsWith("http") || href.contains("://")) continue
            href = href.removePrefix("./")
            while (href.startsWith("../")) href = href.removePrefix("../")
            href = try { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { href }
            if (href.isNotBlank()) out.add(href)
            if (out.size >= 10) break
        }
        return out
    }

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "laquo" to "«", "raquo" to "»", "egrave" to "è", "eacute" to "é", "agrave" to "à", "ograve" to "ò",
        "ugrave" to "ù", "igrave" to "ì", "rsquo" to "’", "lsquo" to "‘", "hellip" to "…", "deg" to "°", "times" to "×", "middot" to "·")

    fun decode(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val v = m.groupValues[1]
        when {
            v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[v] ?: m.value
        }
    }
}
