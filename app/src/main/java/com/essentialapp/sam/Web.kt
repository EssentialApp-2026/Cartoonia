package com.essentialapp.sam

import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Ricerca sul web con le fonti (1.16, come Kimi): SAM cerca su DuckDuckGo (niente chiavi, niente account; se non risponde,
 * Wikipedia), legge le pagine migliori e il cervello risponde citando le fonti [1], [2]. Internet si usa SOLO quando lo
 * chiedi ("cerca su internet…", "fai una ricerca su…"). Qui la parte senza Android e senza rete: Kotlin puro, collaudabile.
 */
object WebSearch {
    class Hit(val title: String, val url: String, val snippet: String) {
        val host: String get() = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
        override fun toString() = "$title <$url>"
    }

    /** Una pagina letta: [n] è il numero della fonte. */
    class Source(val n: Int, val title: String, val url: String, val text: String) {
        val host: String get() = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
    }

    const val UA = "Mozilla/5.0 (Linux; Android 15; Redmi Note 14 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun ddgUrl(q: String) = "https://html.duckduckgo.com/html/?q=${enc(q)}&kl=it-it"
    fun ddgLiteUrl(q: String) = "https://lite.duckduckgo.com/lite/?q=${enc(q)}&kl=it-it"
    fun wikiSearchUrl(q: String, lang: String = "it") =
        "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=${enc(q)}"
    fun wikiTextUrl(title: String, lang: String = "it") =
        "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&redirects=1&titles=${enc(title)}"

    // ---- risultati
    private val TAG = Regex("<[^>]+>")
    private val A_TAG = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
    private val ATTR_HREF = Regex("(?i)\\bhref\\s*=\\s*([\"'])(.*?)\\1")
    private val ATTR_CLASS = Regex("(?i)\\bclass\\s*=\\s*([\"'])(.*?)\\1")
    private val TD_SNIPPET = Regex("(?is)<td[^>]*class\\s*=\\s*[\"']result-snippet[\"'][^>]*>(.*?)</td>")

    private val INLINE_TAG = Regex("(?i)</?(?:b|i|em|strong|span|u|mark|small|sup|sub|abbr|code)\\b[^>]*>")
    fun stripTags(s: String): String = decodeEntities(TAG.replace(INLINE_TAG.replace(s, ""), " ")).replace(Regex("\\s+"), " ").trim()

    /** Il link vero dietro quello di DuckDuckGo ("//duckduckgo.com/l/?uddg=https%3A…"); null = pubblicità o link interno. */
    fun realUrl(href0: String): String? {
        val href = decodeEntities(href0).trim()
        if (href.contains("duckduckgo.com/y.js") || href.contains("ad_provider") || href.contains("/aclk?")) return null
        val uddg = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
        val u = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else if (href.startsWith("//")) "https:$href" else href
        if (!u.startsWith("http")) return null
        if (Regex("^https?://(?:[a-z]+\\.)?duckduckgo\\.com", RegexOption.IGNORE_CASE).containsMatchIn(u)) return null
        return u
    }

    /** I risultati della pagina di DuckDuckGo (versione html o lite), senza pubblicità e senza doppioni. */
    fun parseDdg(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        val anchors = A_TAG.findAll(html).toList()
        val liteSnippets = TD_SNIPPET.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        var liteIdx = 0
        for ((i, a) in anchors.withIndex()) {
            val attrs = a.groupValues[1]
            val cls = ATTR_CLASS.find(attrs)?.groupValues?.get(2) ?: ""
            val isHtml = cls.split(Regex("\\s+")).contains("result__a")
            val isLite = cls.split(Regex("\\s+")).contains("result-link")
            if (!isHtml && !isLite) continue
            val href = ATTR_HREF.find(attrs)?.groupValues?.get(2) ?: continue
            val url = realUrl(href)
            if (url == null) { if (isLite) liteIdx++; continue }
            val title = stripTags(a.groupValues[2])
            var snippet = ""
            if (isHtml) {
                // il riassunto è il prossimo <a class="result__snippet"> prima del risultato dopo
                for (b in anchors.drop(i + 1)) {
                    val c2 = ATTR_CLASS.find(b.groupValues[1])?.groupValues?.get(2) ?: ""
                    if (c2.contains("result__a")) break
                    if (c2.contains("result__snippet")) { snippet = stripTags(b.groupValues[2]); break }
                }
            } else {
                snippet = liteSnippets.getOrNull(liteIdx) ?: ""
                liteIdx++
            }
            val key = url.substringBefore('#').trimEnd('/')
            if (title.isEmpty() || !seen.add(key)) continue
            out.add(Hit(title, url, snippet))
        }
        return out
    }

    /** I risultati della ricerca di Wikipedia (JSON dell'API). */
    fun parseWikiSearch(json: String, lang: String = "it"): List<Hit> {
        val root = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return emptyList()
        val list = ((root["query"] as? Map<*, *>)?.get("search") as? List<*>) ?: return emptyList()
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val title = m["title"] as? String ?: return@mapNotNull null
            Hit(title, "https://$lang.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("+", "_").replace("%2F", "/"),
                stripTags(m["snippet"] as? String ?: ""))
        }
    }

    /** Il testo di una voce di Wikipedia (JSON di prop=extracts). */
    fun parseWikiText(json: String): String? {
        val root = try { Json.parse(json) as? Map<*, *> } catch (e: Exception) { null } ?: return null
        val pages = (root["query"] as? Map<*, *>)?.get("pages") as? Map<*, *> ?: return null
        return pages.values.mapNotNull { (it as? Map<*, *>)?.get("extract") as? String }.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Pagine che non vale la pena leggere (video, social, file). */
    fun skip(url: String): Boolean {
        val u = url.lowercase()
        return Regex("\\.(pdf|docx?|xlsx?|pptx?|zip|mp4|mp3|jpg|png)(\\?|$)").containsMatchIn(u) ||
            listOf("youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "x.com/", "twitter.com", "pinterest.").any { u.contains(it) }
    }

    // ---- testo delle pagine
    private val DROP_BLOCKS = Regex("(?is)<(script|style|noscript|svg|nav|header|footer|aside|form|iframe|template|button|select)\\b.*?</\\1\\s*>")
    private val COMMENTS = Regex("(?s)<!--.*?-->")
    private val MAIN = Regex("(?is)<(article|main)\\b[^>]*>(.*?)</\\1\\s*>")
    private val BLOCK_END = Regex("(?i)</(p|div|li|h[1-6]|tr|section|article|blockquote|dd|dt|td)\\s*>|<br\\s*/?>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")

    fun pageTitle(html: String): String? = TITLE.find(html)?.groupValues?.get(1)?.let { stripTags(it) }?.takeIf { it.isNotBlank() }

    /** Il testo leggibile di una pagina: senza menu, script e pubblicità; i paragrafi su righe separate. */
    fun htmlToText(html0: String): String {
        var html = COMMENTS.replace(html0, " ")
        html = DROP_BLOCKS.replace(html, " ")
        // se c'è un <article> o un <main>, il testo vero sta lì
        val main = MAIN.findAll(html).map { it.groupValues[2] }.maxByOrNull { it.length }
        if (main != null && stripTags(main).length > 400) html = main
        html = BLOCK_END.replace(html, "\n")
        val text = decodeEntities(TAG.replace(html, " "))
        return text.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.length >= 25 || (it.isNotEmpty() && it.last() in ".:!?") }   // via le righe di menu ("Home", "Contatti")
            .joinToString("\n")
    }

    /**
     * Le parti del testo che c'entrano con la domanda, entro [maxChars]: i paragrafi con più parole della domanda,
     * rimessi nell'ordine della pagina (il telefono non può leggere pagine intere: il cervello ha poca memoria).
     */
    fun focus(text: String, query: String, maxChars: Int): String {
        val paras = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return ""
        if (text.length <= maxChars) return paras.joinToString("\n")
        val q = Remember.words(query).toSet()
        val scored = paras.mapIndexed { i, p ->
            val w = Remember.words(p)
            val hits = w.count { it in q }
            Triple(i, p, hits * 10.0 / Math.sqrt(w.size.coerceAtLeast(1).toDouble()) + (if (i < 3) 1.0 else 0.0))
        }
        val chosen = ArrayList<Pair<Int, String>>()
        var used = 0
        for ((i, p, _) in scored.sortedByDescending { it.third }) {
            val piece = if (p.length > maxChars / 2) p.take(maxChars / 2).substringBeforeLast(' ') + "…" else p
            if (used + piece.length + 1 > maxChars) continue
            chosen.add(i to piece); used += piece.length + 1
            if (used >= maxChars * 0.9) break
        }
        return chosen.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "egrave" to "è",
        "eacute" to "é", "agrave" to "à", "ograve" to "ò", "ugrave" to "ù", "igrave" to "ì", "Egrave" to "È", "laquo" to "«", "raquo" to "»",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "hellip" to "…", "ndash" to "–", "mdash" to "—", "euro" to "€",
        "deg" to "°", "copy" to "©", "middot" to "·", "times" to "×")

    fun decodeEntities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTITIES[e] ?: m.value
        }
    }

    // ---- istruzioni per il cervello
    fun answerPrompt(today: java.time.LocalDate = java.time.LocalDate.now()): String =
        "Oggi è ${today.dayOfMonth}/${today.monthValue}/${today.year}. Rispondi in italiano alla domanda usando SOLO le fonti trovate su " +
        "internet qui sotto. Dopo ogni informazione metti il numero della fonte tra parentesi quadre, per esempio [1] o [2]. " +
        "Se le fonti non bastano per rispondere, dillo. Rispondi in modo chiaro, al massimo sei frasi; se serve usa un breve elenco."

    fun answerQuestion(question: String, sources: List<Source>): String =
        sources.joinToString("\n\n") { "[${it.n}] ${it.title} (${it.host})\n${it.text}" } + "\n\nDomanda: ${question.trim()}"

    /** La riga delle fonti per lo schermo: "[1] Titolo — sito\nhttps://…". */
    fun sourcesText(sources: List<Source>): String = sources.joinToString("\n") { "[${it.n}] ${it.title} — ${it.host}\n${it.url}" }

    /** Il testo da leggere a voce: senza i numeri delle fonti né i segni del markdown. */
    fun forSpeech(text: String): String =
        text.replace(Regex("\\s*\\[(?:\\d+(?:\\s*[,–-]\\s*\\d+)*)\\]"), "").replace(Regex("[*#_`>|~]"), "").replace(Regex("[ \\t]+"), " ")

    /** I numeri delle fonti citate nella risposta. */
    fun cited(text: String): Set<Int> =
        Regex("\\[(\\d+(?:\\s*[,–-]\\s*\\d+)*)\\]").findAll(text).flatMap { it.groupValues[1].split(Regex("\\s*[,–-]\\s*")).mapNotNull { n -> n.toIntOrNull() } }.toSet()
}

/**
 * La ricerca approfondita (1.16, come Kimi Researcher): SAM si fa più domande, cerca e legge molte pagine, si appunta i
 * fatti con la loro fonte e poi scrive un rapporto con le fonti, salvato in PDF. Qui istruzioni e letture: Kotlin puro.
 */
object Research {
    const val MAX_QUERIES = 4
    const val PAGES_PER_QUERY = 3
    const val MAX_SOURCES = 10

    fun queriesPrompt(): String =
        "Devi preparare una ricerca su internet su un argomento. Scrivi da 3 a 4 ricerche diverse da fare su un motore di ricerca per " +
        "coprirlo bene (aspetti diversi, dati recenti, pro e contro). Una ricerca per riga, brevi, senza numeri e senza spiegazioni."

    /** Le ricerche dalla risposta del cervello (righe), pulite; l'argomento stesso va sempre per primo. */
    fun parseQueries(raw: String?, topic: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(topic.trim())
        raw?.replace(Regex("(?s)<think>.*?</think>"), " ")?.lines()?.forEach { l ->
            val q = l.trim().replace(Regex("^(?:[-*•]|\\d+[.)])\\s*"), "").trim('"', '«', '»', ' ', '.')
            if (q.length in 4..120 && Remember.words(q).isNotEmpty() && !q.endsWith(":")) out.add(q)
        }
        return out.take(MAX_QUERIES + 1).toList()
    }

    fun notesPrompt(): String =
        "Leggi il testo di una pagina web e scrivi i fatti utili per l'argomento della ricerca: da 2 a 5 punti brevi, uno per riga, " +
        "che cominciano con «- ». Solo fatti scritti nel testo, con numeri e date se ci sono. Se il testo non c'entra scrivi solo: Niente."

    fun notesQuestion(topic: String, s: WebSearch.Source): String = "Argomento: $topic\nPagina: ${s.title}\n${s.text}"

    /** I punti dalla risposta del cervello; vuoto = la pagina non c'entrava. */
    fun parseNotes(raw: String?): List<String> =
        raw?.replace(Regex("(?s)<think>.*?</think>"), " ")?.lines()?.map { it.trim() }
            ?.filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
            ?.map { it.trimStart('-', '•', '*', ' ').trim() }?.filter { it.length >= 8 && !it.startsWith("Niente", true) }
            ?.take(6) ?: emptyList()

    fun reportPrompt(today: java.time.LocalDate = java.time.LocalDate.now()): String =
        "Oggi è ${today.dayOfMonth}/${today.monthValue}/${today.year}. Scrivi in italiano un rapporto completo sull'argomento usando SOLO gli " +
        "appunti qui sotto, che vengono da fonti numerate. Dopo ogni informazione metti il numero della fonte tra parentesi quadre, " +
        "per esempio [3]. Struttura: un titolo sulla prima riga; poi «Sintesi» (3-4 frasi); poi 3-5 sezioni con un titolo ciascuna; " +
        "infine «Conclusioni». Non inventare niente che non sia negli appunti."

    fun reportQuestion(topic: String, notes: List<Pair<WebSearch.Source, List<String>>>): String =
        "Argomento: $topic\n\nAppunti:\n" + notes.joinToString("\n") { (s, ns) -> ns.joinToString("\n") { "- $it [${s.n}]" } }

    /** Il testo del rapporto con le fonti in fondo (per il PDF). */
    fun withSources(report: String, sources: List<WebSearch.Source>): String =
        report.trim() + "\n\nFonti\n" + sources.joinToString("\n") { "[${it.n}] ${it.title} — ${it.url}" }
}

/** La rete per la ricerca: cerca (DuckDuckGo, poi la versione leggera, poi Wikipedia) e legge le pagine. Thread di background. */
object WebFetch {
    private fun http(timeoutMs: Int = 12_000) = Http(WebSearch.UA, timeoutMs)

    /** I risultati per [q]; vuoto = nessun motore ha risposto. [log] riceve cosa è andato storto (per il dettaglio sullo schermo). */
    fun search(q: String, log: (String) -> Unit = {}): List<WebSearch.Hit> {
        for (url in listOf(WebSearch.ddgUrl(q), WebSearch.ddgLiteUrl(q))) {
            try {
                val r = http().get(url, 1_500_000)
                if (r.code == 200) { val hits = WebSearch.parseDdg(r.body); if (hits.isNotEmpty()) return hits }
                log("DuckDuckGo: risposta ${r.code}")
            } catch (e: Exception) { log("DuckDuckGo: ${e.javaClass.simpleName}") }
        }
        for (lang in listOf("it", "en")) {
            try {
                val r = http().get(WebSearch.wikiSearchUrl(q, lang), 500_000)
                if (r.code == 200) { val hits = WebSearch.parseWikiSearch(r.body, lang); if (hits.isNotEmpty()) return hits }
            } catch (e: Exception) { log("Wikipedia: ${e.javaClass.simpleName}") }
        }
        return emptyList()
    }

    /** Il testo di una pagina (Wikipedia: dal suo servizio, più pulito). null = non leggibile. */
    fun pageText(hit: WebSearch.Hit): String? = try {
        val wiki = Regex("^https://([a-z]+)\\.wikipedia\\.org/wiki/(.+)$").find(hit.url)
        if (wiki != null) {
            val title = java.net.URLDecoder.decode(wiki.groupValues[2], "UTF-8").replace('_', ' ')
            WebSearch.parseWikiText(http().get(WebSearch.wikiTextUrl(title, wiki.groupValues[1]), 2_000_000).body)
        } else {
            val r = http(10_000).get(hit.url, 2_500_000)
            val type = r.header("content-type") ?: "text/html"
            if (r.code != 200 || !type.contains("html")) null else WebSearch.htmlToText(r.body).takeIf { it.length > 200 }
        }
    } catch (e: Exception) { null }

    /**
     * Legge le prime [max] pagine buone tra [hits] (in parallelo, al massimo ~15 secondi) e ne tiene le parti che c'entrano con
     * [query] ([perChars] caratteri ciascuna). Le fonti partono dal numero [firstN]. Se una pagina non si legge, vale il riassunto
     * del motore di ricerca.
     */
    fun sources(query: String, hits: List<WebSearch.Hit>, max: Int, perChars: Int, firstN: Int = 1,
                skipUrls: Set<String> = emptySet(), stop: () -> Boolean = { false }): List<WebSearch.Source> {
        val picked = hits.filter { !WebSearch.skip(it.url) && it.url !in skipUrls }.take(max)
        if (picked.isEmpty()) return emptyList()
        val texts = arrayOfNulls<String>(picked.size)
        val threads = picked.mapIndexed { i, h -> Thread { texts[i] = pageText(h) }.apply { isDaemon = true; start() } }
        val until = System.currentTimeMillis() + 15_000
        for (t in threads) { val left = until - System.currentTimeMillis(); if (left > 0 && !stop()) t.join(left) }
        return picked.mapIndexed { i, h ->
            val body = texts[i]?.let { WebSearch.focus(it, query, perChars) }?.takeIf { it.length > 80 } ?: h.snippet
            WebSearch.Source(firstN + i, h.title, h.url, body)
        }.filter { it.text.isNotBlank() }.mapIndexed { i, s -> WebSearch.Source(firstN + i, s.title, s.url, s.text) }
    }
}
