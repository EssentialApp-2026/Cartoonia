package com.essentialapp.sam

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * L'unico uso di internet di SAM (dalla 1.10): controllare e scaricare, col Wi-Fi, la Wikipedia nuova (da Kiwix) e il
 * cervello nuovo (da Hugging Face). Le risposte e le tue frasi non passano mai da qui: si chiedono solo elenchi di file
 * e file. Questa parte è Kotlin/JVM puro: si collauda sul PC con un piccolo server finto.
 */
class Http(private val userAgent: String = "SAM-aggiornamenti/1.10 (Android)", private val timeoutMs: Int = 20_000) {

    class Response(val code: Int, val headers: Map<String, String>, val body: String, val url: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** GET di una pagina, seguendo i rinvii (al massimo 5); legge al massimo [maxBytes]. */
    fun get(url: String, maxBytes: Int = 8_000_000): Response = request("GET", url, followOtherSites = true, maxBytes = maxBytes)

    /**
     * HEAD di un file. Con [followOtherSites] = false si seguono solo i rinvii sullo stesso sito e ci si ferma al primo
     * verso un altro (come vuole Hugging Face: lì ci sono l'impronta e la dimensione del file pubblicato).
     */
    fun head(url: String, followOtherSites: Boolean): Response = request("HEAD", url, followOtherSites, 0)

    private fun request(method: String, url0: String, followOtherSites: Boolean, maxBytes: Int): Response {
        var url = url0
        for (hop in 0..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = method
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.useCaches = false
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                val headers = HashMap<String, String>()
                for ((k, v) in c.headerFields) if (k != null && !v.isNullOrEmpty()) headers[k.lowercase()] = v[0]
                if (code in 300..399) {
                    val loc = headers["location"]
                    if (loc != null && hop < 5) {
                        val next = URL(URL(url), loc).toString()
                        if (followOtherSites || URL(next).host.equals(URL(url).host, ignoreCase = true)) { url = next; continue }
                    }
                    return Response(code, headers, "", url)
                }
                val body = if (method == "HEAD" || maxBytes <= 0) "" else readBody(c, code, maxBytes)
                return Response(code, headers, body, url)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("troppi rinvii")
    }

    private fun readBody(c: HttpURLConnection, code: Int, maxBytes: Int): String {
        val ins = (if (code >= 400) c.errorStream else c.inputStream) ?: return ""
        ins.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val n = s.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }
}

/**
 * I file .zim di Kiwix: "wikipedia_it_all_nopic_2026-06.zim" = famiglia "wikipedia_it_all_nopic" (Wikipedia in italiano,
 * tutte le voci, senza immagini) + mese della copia. Una copia più nuova della stessa famiglia è l'aggiornamento.
 */
object Kiwix {
    const val BASE = "https://download.kiwix.org/zim/"
    /** il server principale, se un mirror non va */
    const val MASTER = "https://master.download.kiwix.org/zim/"
    /** la Wikipedia consigliata se non ce n'è ancora una: tutte le voci, senza immagini */
    const val DEFAULT_FAMILY = "wikipedia_it_all_nopic"
    /** la più leggera: solo l'introduzione di ogni voce (a SAM basta quella) */
    const val LIGHT_FAMILY = "wikipedia_it_all_mini"

    private val FILE = Regex("^(.+)_(\\d{4}-\\d{2})\\.zim$", RegexOption.IGNORE_CASE)
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** "wikipedia_it_all_nopic_2026-06.zim" -> ("wikipedia_it_all_nopic", "2026-06"); null se il nome non è quello di Kiwix. */
    fun split(fileName: String?): Pair<String, String>? {
        val m = FILE.find(fileName?.trim() ?: return null) ?: return null
        val fam = m.groupValues[1]
        return if (SAFE.matches(fam)) fam to m.groupValues[2] else null
    }

    /**
     * La famiglia del file: dal nome del file, se è quello di Kiwix; altrimenti dai metadati Name ("wikipedia_it_all")
     * e Flavour ("nopic"). null = non si sa (niente aggiornamenti automatici per questo file).
     */
    fun family(fileName: String?, name: String?, flavour: String?): String? {
        split(fileName)?.let { return it.first }
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || !SAFE.matches(n)) return null
        val f = flavour?.trim().orEmpty().trimStart('_')
        return when {
            f.isEmpty() || !SAFE.matches(f) -> n
            n.endsWith("_$f", ignoreCase = true) -> n
            else -> "${n}_$f"
        }
    }

    /** La cartella sul sito di Kiwix: "wikipedia" per "wikipedia_it_all_nopic". */
    fun project(family: String): String = family.substringBefore('_').lowercase()

    /** Il mese della copia, "2026-06": dal metadato Date ("2026-06-15") o, se manca, dal nome del file. */
    fun month(date: String?, fileName: String?): String? {
        date?.trim()?.let { d -> Regex("^(\\d{4}-\\d{2})").find(d)?.let { return it.groupValues[1] } }
        return split(fileName)?.second
    }

    /** I file della famiglia nell'elenco della cartella (la pagina HTML del sito), con il loro mese, dal più nuovo. */
    fun listing(html: String, family: String): List<Pair<String, String>> {
        val rx = Regex("href=[\"']?(?:[^\"'>\\s]*/)?(" + Regex.escape(family) + "_(\\d{4}-\\d{2})\\.zim)[\"'>\\s]", RegexOption.IGNORE_CASE)
        return rx.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.distinct()
            .sortedWith(Comparator { a, b -> b.second.compareTo(a.second) }).toList()
    }

    /** Il file più nuovo della famiglia, se è più nuovo del mese [current] ("2026-06"); null = niente di nuovo. */
    fun newer(html: String, family: String, current: String?): Pair<String, String>? {
        val best = listing(html, family).firstOrNull() ?: return null
        return if (current == null || best.second > current) best else null
    }

    fun url(family: String, file: String, base: String = BASE) = base + project(family) + "/" + file

    /** "2026-10" -> "ottobre 2026". */
    fun monthWords(month: String?): String? = Encyclopedia.monthYear(month)
}

/** Il cervello su Hugging Face: impronta (sha256) e dimensione del file pubblicato, dalle intestazioni della risposta. */
object HfFile {
    class Meta(val sha256: String?, val size: Long, val commit: String?)

    /** W/"abc…" -> abc…; null se vuota. */
    fun normalizeEtag(e: String?): String? {
        var t = e?.trim() ?: return null
        if (t.startsWith("W/")) t = t.substring(2)
        t = t.trim('"', ' ')
        return t.ifEmpty { null }
    }

    private val SHA = Regex("^[0-9a-fA-F]{64}$")

    /** L'impronta sha256 (X-Linked-Etag, o ETag se è un sha256) e la dimensione (X-Linked-Size o Content-Length). */
    fun meta(headers: Map<String, String>): Meta {
        val h = headers.mapKeys { it.key.lowercase() }
        val sha = normalizeEtag(h["x-linked-etag"])?.takeIf { SHA.matches(it) }
            ?: normalizeEtag(h["etag"])?.takeIf { SHA.matches(it) }
        val size = (h["x-linked-size"] ?: h["content-length"])?.trim()?.toLongOrNull() ?: -1L
        return Meta(sha?.lowercase(), size, h["x-repo-commit"])
    }
}

/** Impronte dei file (per controllare che lo scaricamento sia arrivato intero). */
object Digest {
    /** L'impronta sha256 di un file, in esadecimale. [stop] = interrompi (lancia InterruptedException). */
    fun sha256(f: File, stop: (() -> Boolean)? = null): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (stop?.invoke() == true) throw InterruptedException("interrotto")
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Le decisioni degli aggiornamenti, senza Android: cosa c'è di nuovo su internet (letto con [Http]). */
object UpdateCheck {
    class ZimNews(val family: String, val file: String, val month: String, val url: String, val size: Long)

    /**
     * La Wikipedia più nuova della stessa famiglia, se c'è ([currentMonth] null = non ce n'è ancora una: la più nuova).
     * [base] = il sito di Kiwix (uno finto nei collaudi). Lancia IOException se il sito non risponde.
     */
    fun encyclopedia(http: Http, family: String, currentMonth: String?, base: String = Kiwix.BASE): ZimNews? {
        val dir = base + Kiwix.project(family) + "/"
        val r = http.get(dir, 16_000_000)
        if (r.code != 200) throw IOException("il sito di Kiwix risponde ${r.code}")
        val (file, month) = Kiwix.newer(r.body, family, currentMonth) ?: return null
        val url = dir + file
        // la dimensione: la dice il mirror a cui il sito rimanda
        val size = try { http.head(url, followOtherSites = true).header("content-length")?.trim()?.toLongOrNull() ?: -1L }
                   catch (e: Exception) { -1L }
        return ZimNews(family, file, month, url, size)
    }

    class BrainNews(val file: String, val url: String, val sha256: String, val size: Long)

    /**
     * Il file del cervello pubblicato su Hugging Face, se è diverso da quello sul telefono ([installedSha]; null = non
     * si sa, allora è "nuovo"). null = uguale, oppure il sito non dice l'impronta. Lancia IOException se non risponde.
     */
    fun brain(http: Http, url: String, file: String, installedSha: String?): BrainNews? {
        val r = http.head(url, followOtherSites = false)
        if (r.code !in 200..399) throw IOException("Hugging Face risponde ${r.code}")
        val m = HfFile.meta(r.headers)
        val sha = m.sha256 ?: return null
        if (installedSha != null && sha.equals(installedSha, ignoreCase = true)) return null
        return BrainNews(file, url, sha, m.size)
    }

    /** Lo spazio che serve per scaricare un file di [size] byte (con un margine); se la dimensione non si sa, 4 GB. */
    fun spaceNeeded(size: Long): Long = (if (size > 0) size else 4_000_000_000L) + 800_000_000L
}
