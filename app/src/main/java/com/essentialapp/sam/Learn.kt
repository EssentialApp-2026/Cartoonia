package com.essentialapp.sam

/**
 * Autoapprendimento (1.12): quando SAM è libero, il suo cervello rilegge le frasi che gli hai detto e si segna da solo
 * le cose durature sulla tua vita (famiglia, lavoro, gusti, abitudini, posti, animali, progetti) che le regole fisse
 * di [Remember.selfFact] non colgono ("mia figlia fa danza il martedì", "il sabato vado sempre a pescare").
 *
 * I pesi del modello non cambiano (sul telefono non si possono riaddestrare): quello che impara diventa un ricordo,
 * che il cervello rilegge a ogni conversazione. Per non inventare niente ogni cosa imparata deve stare nelle tue parole:
 * le parole che contano devono comparire in una frase che hai detto davvero. Kotlin puro: si collauda senza telefono.
 */
object SelfLearn {
    /** al massimo tante cose imparate per volta */
    const val MAX_PER_ROUND = 3
    /** le frasi da rileggere: le ultime, non di più (il telefono ci mette tempo a leggerle) */
    const val MAX_LINES = 14
    /** dopo quanto tempo senza parlare SAM si mette a rileggere */
    const val IDLE_MS = 3 * 60_000L
    /** la chiave dei ricordi imparati così (per elencarli: "cosa hai imparato da solo?") */
    const val KEY = "auto:"

    // cose che passano: non vale la pena ricordarle per sempre
    private val TRANSIENT = Lang.rx(
        "\\b(?:oggi|domani|dopodomani|ieri|stasera|stamattina|stamani|stanotte|adesso|ora|poco fa|tra poco|fra poco|in questo momento|" +
        "per ora|questa settimana|questo pomeriggio|stanco|stanca|fame|sete|sonno|annoiato|annoiata)\\b"
    )
    // non è una cosa detta dal proprietario su di sé, ma una descrizione da fuori o dell'assistente
    private val NOT_FIRST_PERSON = Lang.rx("^(?:l'utente|utente|lui|lei|egli|sam|l'assistente|assistente|il proprietario|tu|sei)\\b|\\butente\\b")
    private val LINE = Lang.rx("^\\s*(?:[-*•]\\s*)?(?:\\d+[.)]\\s*)?(?:fatto|cosa imparata|ricordo)\\s*[:：]\\s*(.+)$")

    /** Le istruzioni per il cervello. */
    fun prompt(owner: String?): String {
        val who = owner?.takeIf { it.isNotBlank() } ?: "l'utente"
        return "Leggi le frasi che $who ha detto al suo assistente vocale. Trova solo le cose durature che $who ha detto di sé o " +
            "della sua vita: famiglia, lavoro, gusti, abitudini, luoghi, animali, progetti. Ignora le domande, i comandi, le cose " +
            "passeggere di oggi o di domani e tutto quello che non ha detto. Non aggiungere niente di tuo. Scrivi ogni cosa su una " +
            "riga che comincia con «Fatto:», in prima persona come la direbbe $who, con le sue parole, al massimo dodici parole. " +
            "Se non c'è niente da ricordare scrivi solo: Niente."
    }

    /** Due esempi in JSON: insegnano il formato delle righe e che spesso non c'è niente da imparare. */
    fun examplesJson(): String = listOf(
        "Frasi:\n1. «mia figlia si chiama Giulia e fa danza il martedì»\n2. «che tempo fa domani»\n3. «oggi sono stanco morto»" to
            "Fatto: Mia figlia si chiama Giulia\nFatto: Mia figlia fa danza il martedì",
        "Frasi:\n1. «accendi la torcia»\n2. «chi era Garibaldi»" to "Niente"
    ).joinToString(",", "[", "]") { (q, a) ->
        "{\"role\":\"user\",\"content\":${Json.quote(q)}},{\"role\":\"assistant\",\"content\":${Json.quote(a)}}"
    }

    /** Le frasi da rileggere, numerate. */
    fun question(lines: List<String>): String =
        "Frasi:\n" + lines.mapIndexed { i, l -> "${i + 1}. «${l.replace('«', '"').replace('»', '"').trim()}»" }.joinToString("\n")

    /**
     * Tra le tue frasi [said] (dalla più vecchia), quelle da rileggere: abbastanza lunghe da contenere un fatto,
     * non comandi ("accendi la torcia"). Al massimo [MAX_LINES], le più recenti.
     */
    fun candidates(said: List<String>): List<String> =
        said.map { it.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim() }
            .filter { s -> s.split(' ').count { it.isNotBlank() } >= 4 && !Remember.isCommand(s) }
            .distinct()
            .takeLast(MAX_LINES)

    /** "lavoro" ~ "lavora", "figlia" ~ "figlie": stessa radice (le prime 4 lettere) e lunghezza simile. */
    private fun near(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && a.take(4) == b.take(4) && Math.abs(a.length - b.length) <= 2 &&
            a.take(minOf(a.length, b.length) - 1) == b.take(minOf(a.length, b.length) - 1))

    /**
     * true se [fact] sta nelle tue parole: almeno 3 parole su 4 di quelle che contano (e almeno 2) compaiono in una
     * frase sola tra [said]. Così il cervello non può "imparare" una cosa che non hai detto.
     */
    fun grounded(fact: String, said: List<String>, owner: String? = null): Boolean {
        val skip = setOfNotNull(owner?.let { Remember.norm(it) }, "sam")
        val w = Remember.words(fact).distinct().filter { it !in skip }
        if (w.size < 2) return false
        val need = maxOf(2, (w.size * 3 + 3) / 4)
        return said.any { s ->
            val sw = Remember.words(s)
            w.count { q -> sw.any { near(q, it) } } >= need
        }
    }

    /**
     * Le cose imparate dalla risposta [raw] del cervello: solo righe "Fatto: …" che stanno nelle tue parole [said],
     * durature, non già nei ricordi [memos] e non doppie. Al massimo [MAX_PER_ROUND].
     */
    fun parse(raw: String?, said: List<String>, memos: List<Memo>, owner: String? = null, now: Long = 0L): List<Memo> {
        if (raw.isNullOrBlank()) return emptyList()
        val clean = raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace("<think>", " ").replace("</think>", " ")
        val known = memos.filter { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        val out = ArrayList<Memo>()
        for (row in clean.lines()) {
            val m = LINE.find(row.trim()) ?: continue
            var t = m.groupValues[1].trim().trim('«', '»', '"', '“', '”', '\'', '*', ' ').trimEnd('.', ';', ',', ':', '!').trim()
            if (t.isEmpty() || t.contains('?')) continue
            val words = t.split(Regex("\\s+")).filter { w -> w.any { it.isLetter() } }
            if (words.size < 3 || words.size > 16 || t.length > 120) continue
            if (Lang.rx("^niente\\b").containsMatchIn(t)) continue
            if (NOT_FIRST_PERSON.containsMatchIn(t) || TRANSIENT.containsMatchIn(t) || Remember.isCommand(t)) continue
            if (!grounded(t, said, owner)) continue
            t = t.replaceFirstChar { it.uppercase() }
            val q = Remember.words(t).distinct()
            if (q.isEmpty()) continue
            // già saputo (detto in altre parole) o già imparato in questo giro
            if (known.any { Remember.score(q, it) >= 75 } || out.any { Remember.score(q, it) >= 75 }) continue
            out.add(Memo(Remember.FATTO, t, KEY + q.take(3).joinToString(" "), now))
            if (out.size >= MAX_PER_ROUND) break
        }
        return out
    }

    /** Le cose imparate così, dalla più recente: per "cosa hai imparato da solo?". */
    fun learned(memos: List<Memo>): List<Memo> = memos.filter { it.kind == Remember.FATTO && it.key.startsWith(KEY) }.asReversed()

    /** La risposta a "cosa hai imparato da solo?". */
    fun describe(memos: List<Memo>, on: Boolean): String {
        val l = learned(memos)
        val off = if (on) "" else " Adesso però l'autoapprendimento è spento: per riaccenderlo di' impara da solo."
        if (l.isEmpty()) return "Da solo non ho ancora imparato niente. Quando sono libero rileggo le cose che mi hai detto e mi segno " +
            "quelle importanti sulla tua vita, con le tue parole." + off
        val shown = l.take(6).asReversed()
        return (if (l.size == 1) "Da solo ho imparato una cosa: " else "Da solo ho imparato ${l.size} cose. Le ultime: ") +
            shown.joinToString(". ") { it.text.trimEnd('.') } + ". Se una è sbagliata dimmi: dimentica, e la cosa." + off
    }
}
