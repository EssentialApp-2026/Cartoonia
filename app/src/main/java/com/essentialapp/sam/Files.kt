package com.essentialapp.sam

/**
 * File che SAM prepara per te (1.13): la sua ultima risposta, la conversazione, il testo di un documento aperto, oppure
 * un documento nuovo scritto dal cervello ("scrivimi un documento sulla pesca a spinning"). Finiscono nella cartella
 * Download › SAM del telefono, in testo semplice o in PDF. Qui le frasi, i nomi dei file e la pulizia del testo:
 * Kotlin puro, si collauda senza telefono. Il salvataggio vero sta in [FileOut].
 */
object SamFiles {
    /** cosa salvare: answer | chat | doc | write (scritto apposta su [topic]) | open | share (l'ultimo file salvato) */
    private fun ci(p: String) = Lang.rx(p)
    private const val FILL = "(?:(?:sam|ehi|ok|allora|senti|per favore|perfavore|dai|adesso|ora)[,]?\\s+)*(?:(?:mi |ti )?(?:puoi |potresti |riesci a |vorrei che )?)?"
    private const val TAIL = "(?:\\s+(?:per favore|perfavore|grazie|sam|adesso|ora|subito))*[.!?]*$"
    private const val AS_FILE = "(?:in|su|come|dentro)\\s+(?:un |una |il |la )?(?:file|documento|pdf|file di testo|file pdf|txt)(?: di testo| pdf)?"
    private const val VERB_SAVE = "(?:salva|salvami|scarica|scaricami|esporta|esportami|metti|mettimi|copia|copiami|trasforma|converti|" +
        "salvare|salvarmi|scaricare|scaricarmi|esportare|mettere|mettermi|copiare|trasformare|convertire)"

    private val SAVE_ANSWER = ci(
        "^$FILL(?:$VERB_SAVE)\\s+(?:la tua |l'ultima |la |questa )?(?:(?:ultima |tua )?risposta|quello che (?:mi )?hai (?:appena )?detto|quello che hai scritto|" +
        "questo|questa cosa|tutto questo)\\s*(?:$AS_FILE)?$TAIL|" +
        // "salvalo" da solo resta quello di sempre (un ricordo): per un file serve dirlo ("salvalo in un file"); "scaricalo" basta
        "^$FILL(?:salvalo|salvala|salvamelo|salvamela|esportalo|esportala|mettilo|mettila|mettimelo|mettimela|salvarlo|salvarla|salvarmelo|" +
        "esportarlo|metterlo|mettermelo)\\s+$AS_FILE$TAIL|" +
        "^$FILL(?:scaricalo|scaricala|scaricamelo|scaricamela|scaricarlo|scaricarla|scaricarmelo)(?:\\s+$AS_FILE)?$TAIL|" +
        "^$FILL(?:fammi|fai|crea|creami|prepara|preparami)\\s+(?:un |una |il )?(?:file|pdf|documento|file di testo)(?: pdf)?\\s+(?:con|di|da)\\s+" +
        "(?:questo|questa risposta|quello che hai detto|la tua risposta|la risposta|l'ultima risposta)$TAIL"
    )
    private val SAVE_CHAT = ci(
        "^$FILL$VERB_SAVE\\s+(?:tutta |l'intera )?(?:la |questa |la nostra |quella )?(?:conversazione|chiacchierata|chat|cronologia|discussione)" +
        "(?:\\s+(?:di oggi|che abbiamo fatto|che abbiamo avuto))?(?:\\s+$AS_FILE)?$TAIL"
    )
    private val SAVE_DOC = ci(
        "^$FILL$VERB_SAVE\\s+(?:il testo (?:del|dal) |il |questo )?(?:documento|file|pdf)(?: aperto| che ti ho (?:dato|mandato|caricato|condiviso))?\\s+" +
        "(?:in|come)\\s+(?:un |una )?(?:file di testo|testo|txt|pdf|file pdf)$TAIL|" +
        "^$FILL$VERB_SAVE\\s+il testo (?:del|dal|di questo) (?:documento|file|pdf)(?: aperto)?(?:\\s+$AS_FILE)?$TAIL"
    )
    private val WRITE = ci(
        "^$FILL(?:scrivimi|scrivi|creami|crea|fammi|fai|preparami|prepara|generami|genera|redigi|redigimi|scrivermi|scrivere|crearmi|creare|" +
        "farmi|prepararmi|preparare|generarmi|generare)\\s+(?:un |una |il |la |un')?" +
        "(file|documento|pdf|file di testo|file pdf|relazione|lettera)(?: in pdf| pdf| di testo)?\\s+" +
        "(?:su|sul|sulla|sullo|sui|sulle|sugli|sull'|con|per|di|del|della|che|riguardo|riguardo a|riguardo al|riguardo alla|dove|in cui|a proposito di)\\s+(.+)$"
    )
    private val OPEN = ci(
        "^$FILL(?:apri|aprimi|fammi vedere|mostrami|fammelo vedere)\\s+(?:il |l'ultimo |quel )?(?:file|pdf)(?:\\s+(?:che hai (?:salvato|creato|scritto|fatto|preparato)|" +
        "salvato|nuovo|di prima))?$TAIL"
    )
    private val SHARE = ci(
        "^$FILL(?:condividi|condividimi|mandami|inviami|manda|invia|spedisci)\\s+(?:il |l'ultimo |quel )?(?:file|pdf)(?:\\s+(?:che hai (?:salvato|creato|scritto|fatto|preparato)|" +
        "salvato|nuovo|di prima))?(?:\\s+(?:su|con|a|per)\\s+.+)?$TAIL"
    )
    private val PDF = ci("\\bpdf\\b")
    // codice (1.14): "scrivimi un programma in python che…", "crea una pagina web con…", "fammi un expert advisor mql5 che…"
    private val CODE_NOUN = "(?:programma|programmino|codice|script|scriptino|funzione|classe|pagina web|pagina html|sito|sito web|app|applicazione|" +
        "web app|expert advisor|indicatore|robot di trading|query|macro|algoritmo|calcolatrice)"
    private val CODE = ci(
        "^$FILL(?:scrivimi|scrivi|creami|crea|fammi|fai|preparami|prepara|generami|genera|programmami|programma|sviluppami|sviluppa|" +
        "scrivermi|scrivere|crearmi|creare|farmi|prepararmi|preparare|programmare|sviluppare)\\s+(?:un |una |uno |il |la |lo |l'|un')?" +
        "(?:(?:piccolo|piccola|semplice) )?$CODE_NOUN\\b.*$|" +
        "^$FILL(?:scrivimi|scrivi|scrivermi|scrivere|fammi|farmi)\\s+(?:in|con)\\s+(?:python|javascript|java|kotlin|html|php|c\\+\\+|c#|bash|sql|mql5|mql4|rust|go|swift|dart)\\b.+$"
    )
    // "fammi un pdf con questo": non è un documento nuovo, è l'ultima risposta
    private val THIS = ci("^(?:questo|questa|questa risposta|quello che hai detto|quello che mi hai detto|la tua risposta|la risposta|l'ultima risposta|" +
        "la tua ultima risposta|tutto questo|quello)$")

    // modalità agente (1.16): "fammi una presentazione sulle energie rinnovabili", "crea 10 slide su…"
    private val SLIDES = ci(
        "^$FILL(?:fammi|fai|crea|creami|prepara|preparami|genera|generami|realizza|realizzami|scrivimi|scrivi|farmi|crearmi|prepararmi|fare|creare|preparare)\\s+" +
        "(?:una |delle |le |un |(\\d+|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|dodici|quindici) )?" +
        "(?:presentazione|presentazioni|slide|slides|diapositive|powerpoint|power point|ppt)" +
        "((?:\\s+(?:in powerpoint|in power point|in pdf|in ppt|di \\d+ slide|con \\d+ slide|da \\d+ slide|con (?:dati|informazioni) (?:da internet|aggiornat[ei])|" +
        "cercando su internet|con le fonti))*)\\s+(?:su|sul|sulla|sullo|sui|sulle|sugli|sull'|per|di|del|della|dei|delle|riguardo a|riguardo|che spiega|che parla di)\\s+(.+)$"
    )
    private val SLIDE_N = Regex("(\\d+)")
    private val WEB_HINT = ci("\\b(?:da internet|su internet|cercando|aggiornat[aeio]|con le fonti|dati recenti|ultime notizie)\\b")
    private val NUM_WORDS = mapOf("due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9,
        "dieci" to 10, "dodici" to 12, "quindici" to 15)

    /** Il comando dei file nella frase [t], o null. */
    fun command(t: String): Outcome.SaveFile? {
        val s = t.trim()
        val pdf = PDF.containsMatchIn(s)
        SLIDES.find(s)?.let { m ->
            val n = m.groupValues[1].let { NUM_WORDS[it.lowercase()] ?: it.toIntOrNull() }
                ?: SLIDE_N.find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var topic = m.groupValues[3].trim().trimEnd('.', '!', '?', ' ')
            val web = WEB_HINT.containsMatchIn(s)
            topic = ci("\\s+(?:cercando su internet|con (?:dati|informazioni) (?:da internet|aggiornat[ei])|con le fonti|in pdf|in powerpoint)$").replace(topic, "")
            if (Remember.words(topic).isNotEmpty()) return Outcome.SaveFile("slides", PDF.containsMatchIn(m.groupValues[2]), topic, n.coerceIn(0, Slides.MAX_COUNT), web)
        }
        if (CODE.containsMatchIn(s) && !PDF.containsMatchIn(s)) return Outcome.SaveFile("code", false, s.trimEnd('.', '!', '?', ' '), web = WEB_HINT.containsMatchIn(s))
        WRITE.find(s)?.takeIf { m -> !THIS.matches(m.groupValues[2].trim().trimEnd('.', '!', '?', ' ')) }?.let { m ->
            val topic = m.groupValues[2].trim().trimEnd('.', '!', '?', ' ')
            if (Remember.words(topic).isEmpty()) return null
            val kind = m.groupValues[1].lowercase()
            val what = if (kind == "lettera" || kind == "relazione") "$kind $topic" else topic
            return Outcome.SaveFile("write", pdf || kind.contains("pdf"), what)
        }
        if (OPEN.containsMatchIn(s)) return Outcome.SaveFile("open", false)
        if (SHARE.containsMatchIn(s)) return Outcome.SaveFile("share", false)
        if (SAVE_CHAT.containsMatchIn(s)) return Outcome.SaveFile("chat", pdf)
        if (SAVE_DOC.containsMatchIn(s)) return Outcome.SaveFile("doc", pdf)
        if (SAVE_ANSWER.containsMatchIn(s)) return Outcome.SaveFile("answer", pdf)
        return null
    }

    /** Il testo del cervello pulito per un file: niente ragionamento, niente segni del markdown, paragrafi ordinati. */
    fun clean(raw: String): String {
        var t = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "")
        t = t.replace("\r", "").replace(Regex("```[a-zA-Z]*"), "")
        t = t.lines().joinToString("\n") { l ->
            var x = l.trimEnd()
            x = x.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")           // titoli
            x = x.replace(Regex("^(\\s*)[*+]\\s+"), "$1• ")            // elenchi
            x = x.replace(Regex("^(\\s*)-\\s+"), "$1• ")
            x = x.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("__(.+?)__"), "$1")
            x = x.replace(Regex("(?<![\\p{L}\\p{N}])\\*(\\S.*?\\S|\\S)\\*(?![\\p{L}\\p{N}])"), "$1")
            x.replace("`", "")
        }
        t = t.replace(Regex("\n{3,}"), "\n\n").trim()
        return t
    }

    /** Il titolo: la prima riga se è corta (come la scrive il cervello), altrimenti [fallback]. */
    fun titleOf(text: String, fallback: String): String {
        val first = text.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('•', ' ', ':', '.')?.removePrefix("Titolo:")?.trim()
        val f = fallback.trim().replaceFirstChar { it.uppercase() }
        return if (first != null && first.length in 3..70 && first.split(' ').size <= 12) first else f.take(60)
    }

    /** Le prime parole di una frase, per il titolo ("La pesca a spinning si fa…" -> "La pesca a spinning si fa"). */
    fun firstWords(text: String, n: Int = 6): String =
        text.replace(Regex("\\s+"), " ").trim().split(' ').take(n).joinToString(" ").trimEnd(',', '.', ':', ';', '!', '?')

    /** Il nome del file: "SAM - Titolo - 2026-10-18 11.53.pdf", senza caratteri che Android non accetta. */
    fun fileName(title: String, ext: String, now: java.time.LocalDateTime): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trim().ifEmpty { "documento" }
        val stamp = String.format(java.util.Locale.ITALY, "%04d-%02d-%02d %02d.%02d", now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
        return "SAM - $safe - $stamp.$ext"
    }

    /** La conversazione per il file: "[11:53] Sam: … / SAM: …", dalla battuta più vecchia. */
    fun chatText(lines: List<Talk.Line>, owner: String?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val who = owner?.takeIf { it.isNotBlank() } ?: "Tu"
        val f = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
        return lines.joinToString("\n\n") { l ->
            val at = java.time.Instant.ofEpochMilli(l.at).atZone(zone).format(f)
            "[$at] $who: ${l.you.trim()}" + (if (l.sam.isNotBlank()) "\nSAM: ${l.sam.trim()}" else "")
        }
    }

    /** Le istruzioni per scrivere un documento da salvare (non da leggere a voce: può essere lungo e ordinato). */
    fun writePrompt(): String =
        "Scrivi in italiano il testo completo di un documento su quello che ti viene chiesto. Sulla prima riga metti solo il titolo, " +
        "poi paragrafi chiari e ordinati; usa elenchi puntati quando servono. Scrivi solo il documento: niente saluti, niente commenti " +
        "prima o dopo. Non inventare dati personali, nomi, date o numeri che non conosci: dove servono lascia uno spazio da " +
        "compilare tra parentesi quadre, per esempio [data]."

    fun writeQuestion(topic: String): String = "Documento richiesto: ${topic.trim()}"

    /** Un linguaggio di programmazione: nome per le istruzioni, estensione e tipo del file. */
    data class CodeLang(val name: String, val ext: String, val mime: String = "text/plain")

    private val LANGS: List<Pair<Regex, CodeLang>> = listOf(
        ci("\\bmql5\\b|\\bexpert advisor\\b|\\bmetatrader(?: 5)?\\b|\\brobot di trading\\b|\\bindicatore\\b") to CodeLang("MQL5 (MetaTrader 5)", "mq5"),
        ci("\\bmql4\\b|\\bmetatrader 4\\b") to CodeLang("MQL4 (MetaTrader 4)", "mq4"),
        ci("\\btypescript\\b") to CodeLang("TypeScript", "ts"),
        ci("\\bjavascript\\b|\\bnode(?:\\.?js)?\\b|\\bjs\\b") to CodeLang("JavaScript", "js", "text/javascript"),
        ci("\\bkotlin\\b") to CodeLang("Kotlin", "kt"),
        ci("\\bjava\\b") to CodeLang("Java", "java"),
        ci("\\bc\\+\\+|\\bcpp\\b") to CodeLang("C++", "cpp"),
        ci("\\bc#|\\bcsharp\\b") to CodeLang("C#", "cs"),
        ci("\\bphp\\b") to CodeLang("PHP", "php"),
        ci("\\bcss\\b") to CodeLang("CSS", "css", "text/css"),
        ci("\\bsql\\b|\\bquery\\b") to CodeLang("SQL", "sql"),
        ci("\\bpowershell\\b") to CodeLang("PowerShell", "ps1"),
        ci("\\bbash\\b|\\bshell\\b|\\btermux\\b|\\bterminale\\b|\\blinux\\b") to CodeLang("Bash", "sh"),
        ci("\\bbatch\\b|\\bfile \\.?bat\\b|\\bprompt dei comandi\\b") to CodeLang("Batch di Windows", "bat"),
        ci("\\bvba\\b|\\bmacro\\b|\\bexcel\\b") to CodeLang("VBA (macro di Excel)", "bas"),
        ci("\\barduino\\b") to CodeLang("Arduino (C++)", "ino"),
        ci("\\brust\\b") to CodeLang("Rust", "rs"),
        ci("\\bswift\\b") to CodeLang("Swift", "swift"),
        ci("\\bdart\\b|\\bflutter\\b") to CodeLang("Dart", "dart"),
        ci("\\bgolang\\b|\\bin go\\b") to CodeLang("Go", "go"),
        ci("\\bin c\\b|\\blinguaggio c\\b") to CodeLang("C", "c"),
        ci("\\bhtml\\b|\\bpagina web\\b|\\bsito\\b|\\bweb app\\b|\\bpwa\\b|\\bapp\\b|\\bapplicazione\\b|\\bcalcolatrice\\b") to
            CodeLang("HTML in un file unico (con CSS e JavaScript dentro)", "html", "text/html"),
        ci("\\bpython\\b|\\bpy\\b") to CodeLang("Python", "py", "text/x-python")
    )

    val HTML = CodeLang("HTML in un file unico (con CSS e JavaScript dentro)", "html", "text/html")

    /**
     * Il linguaggio chiesto nella frase. 2.0.3: se non lo dice, HTML in un file unico (si apre subito nel browser, su telefono
     * e PC); prima era Python. Chi vuole altro lo dice: «in Python», «in MQL5»…
     */
    fun codeLang(request: String): CodeLang = LANGS.firstOrNull { it.first.containsMatchIn(request) }?.second ?: HTML

    private val NO_BRACES = setOf("py", "sh", "bat", "sql", "bas", "html", "css", "ps1")

    /**
     * 2.0.3: il file è rimasto a metà? (Il cervello scrive fino a un certo numero di parole e poi si ferma: i file lunghi
     * venivano salvati tronchi.) Per il codice: il blocco ``` non è chiuso, a una pagina HTML manca </html>, oppure ci sono
     * graffe aperte e mai chiuse. Per un documento: non finisce con un punto.
     */
    fun unfinished(raw: String, code: CodeLang?): Boolean {
        val t = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("\r", "").trim()
        if (t.length < 40) return false
        if (code == null) return !Regex("[.!?»”\")\\]…]$").containsMatchIn(t.replace("```", "").trim())
        val fences = Regex("```").findAll(t).count()
        if (fences % 2 == 1) return true
        val body = extractCode(t)
        // 2.0.4: dentro il codice non devono esserci i ragionamenti del cervello (finivano nel file e la pagina restava bianca)
        if (code.ext == "html" && Regex("(?i)<style[^>]*>").findAll(body).count() > Regex("(?i)</style>").findAll(body).count()) return true
        if (code.ext == "html" && Regex("(?i)<script[^>]*>").findAll(body).count() > Regex("(?i)</script>").findAll(body).count()) return true
        if (code.ext == "html") return !body.contains("</html>", ignoreCase = true)
        if (code.ext !in NO_BRACES) return body.count { it == '{' } > body.count { it == '}' }
        return false
    }

    /** Il testo da cui continuare: senza il ``` di chiusura (se c'è) e senza spazi in fondo. */
    fun openEnd(raw: String): String { val t = raw.trimEnd(); return if (t.endsWith("```")) t.removeSuffix("```").trimEnd(' ', '\t') else raw }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 2.0.3: un documento come pagina HTML in un file unico (si apre nel browser, si legge bene anche sul telefono, si stampa).
     * La prima riga è il titolo; le righe con «• » diventano elenchi; una riga corta senza punto, seguita da testo, un titoletto.
     */
    fun docHtml(title: String, text: String): String {
        val lines = text.replace("\r", "").trim().lines()
        val body = StringBuilder()
        var inList = false
        fun closeList() { if (inList) { body.append("</ul>\n"); inList = false } }
        var first = true
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (l.isEmpty()) { closeList(); continue }
            if (first) { first = false; if (l.trim('•', ' ', ':', '.').equals(title.trim(), ignoreCase = true)) continue }
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            when {
                l.startsWith("• ") || l.startsWith("- ") -> { if (!inList) { body.append("<ul>\n"); inList = true }; body.append("  <li>").append(esc(l.drop(2).trim())).append("</li>\n") }
                l.length <= 70 && !Regex("[.!?;,]$").containsMatchIn(l) && (l.endsWith(":") || next.isEmpty() || next.length > l.length) && lines.size > 3 ->
                    { closeList(); body.append("<h2>").append(esc(l.trimEnd(':'))).append("</h2>\n") }
                else -> { closeList(); body.append("<p>").append(esc(l)).append("</p>\n") }
            }
        }
        closeList()
        return "<!DOCTYPE html>\n<html lang=\"it\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>" + esc(title) + "</title>\n<style>\n" +
            ":root{--bg:#ffffff;--fg:#1c2230;--muted:#5b6475;--line:#e3e7ee;--accent:#2563eb}\n" +
            "@media (prefers-color-scheme:dark){:root{--bg:#0d0f16;--fg:#e2e7f0;--muted:#8c96aa;--line:#2c3244;--accent:#60a5fa}}\n" +
            "body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.65 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}\n" +
            "main{max-width:720px;margin:0 auto;padding:28px 20px 60px}\n" +
            "h1{font-size:1.9em;line-height:1.2;margin:0 0 .6em;border-bottom:2px solid var(--accent);padding-bottom:.35em}\n" +
            "h2{font-size:1.25em;margin:1.6em 0 .4em}\np{margin:0 0 1em}\nul{margin:0 0 1em;padding-left:1.3em}\nli{margin:.25em 0}\n" +
            "footer{margin-top:3em;padding-top:1em;border-top:1px solid var(--line);color:var(--muted);font-size:.85em}\n" +
            "@media print{body{font-size:12pt}main{max-width:none;padding:0}}\n</style>\n</head>\n<body>\n<main>\n<h1>" + esc(title) + "</h1>\n" +
            body + "</main>\n</body>\n</html>\n"
    }

    /** Le istruzioni per scrivere codice da salvare (per un sito: un sito completo e curato, in un file solo). */
    fun codePrompt(lang: CodeLang, request: String = ""): String =
        if (lang.ext == "html" && ci("\\b(?:sito|pagina web|landing|web app|app|portfolio|negozio|blog)\\b").containsMatchIn(request))
            "Sei un web designer esperto. Crea il sito richiesto in UN SOLO file HTML completo, con CSS e JavaScript dentro il file e " +
            "nessuna risorsa esterna (niente immagini da internet: usa colori, gradienti, emoji e SVG disegnati nel codice). Deve essere " +
            "moderno, curato e adatto al telefono: menu in alto, sezione principale d'impatto, 3-5 sezioni con contenuti veri in italiano " +
            "(niente lorem ipsum), pulsanti, piè di pagina. Rispondi solo con il codice, in un unico blocco tra tre apici inversi."
        else
        "Sei un programmatore esperto. Scrivi in ${lang.name} il codice completo e funzionante che ti viene chiesto. " +
        "Rispondi solo con il codice, in un unico blocco tra tre apici inversi, con brevi commenti in italiano dentro il codice. " +
        "Niente spiegazioni prima o dopo. Il codice deve funzionare così com'è, senza parti lasciate da completare."

    fun codeQuestion(request: String): String = "Richiesta: ${request.trim()}"

    /**
     * Il codice dalla risposta del cervello: il blocco più lungo tra ``` (senza la riga del linguaggio); se non ci sono
     * blocchi, tutto il testo senza il ragionamento.
     */
    fun extractCode(raw: String): String {
        val t = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "").replace("\r", "")
        val blocks = Regex("(?s)```[^\\n`]*\\n(.*?)(?:```|$)").findAll(t).map { it.groupValues[1] }.toList()
        val best = blocks.maxByOrNull { it.length }
        return (best ?: t).trim('\n').trimEnd() + "\n"
    }

    /** Il titolo del file di codice: le parole che contano della richiesta ("calcolatrice percentuali"). */
    fun codeTitle(request: String): String {
        val skip = setOf("scrivimi", "scrivi", "creami", "crea", "fammi", "fai", "preparami", "prepara", "generami", "genera", "programma",
            "programmami", "sviluppami", "sviluppa", "codice", "script", "python", "javascript", "html", "kotlin", "java", "puoi", "sam",
            "semplice", "piccolo", "piccola", "scrivermi", "farmi", "crearmi")
        val w = Remember.words(request).filter { it !in skip }.take(5)
        return if (w.isEmpty()) "codice" else w.joinToString(" ")
    }

    /** I tipi di file che si possono caricare con 📎 (gli stessi di "Condividi → SAM"). */
    val OPENABLE = arrayOf(
        "text/*", "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/msword", "image/*"
    )
}
