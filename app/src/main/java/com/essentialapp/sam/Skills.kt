package com.essentialapp.sam

/**
 * Le skill (1.17, come in Spinta): funzioni pronte da toccare, tutte dentro SAM e senza dipendenze.
 *  - TEXT: il cervello del telefono fa un lavoro preciso sul testo che scrivi (riassumere, tradurre, correggere…), con
 *    istruzioni fatte apposta;
 *  - ACTION: diventano un comando che SAM sa già fare (ricerca sul web, presentazione, codice, disegno, video…);
 *  - FILE: apre la scelta del file da far leggere a SAM.
 * Kotlin puro: si collauda senza telefono.
 */
object Skills {
    enum class Kind { TEXT, ACTION, FILE }

    class Skill(
        val id: String, val emoji: String, val name: String, val desc: String, val kind: Kind,
        val hint: String = "",
        /** un secondo campo (es. la lingua per tradurre): etichetta e valore già scritto */
        val extraLabel: String? = null, val extraDefault: String = "",
        val system: String = "", val maxTokens: Int = 500,
        /** il comando per le ACTION: «%s» = quello che hai scritto */
        val command: String = "",
        val code: Boolean = false,
    )

    val ALL: List<Skill> = listOf(
        Skill("riassumi", "📝", "Riassumi", "Il punto principale e i punti chiave", Kind.TEXT,
            hint = "Incolla qui il testo da riassumere",
            system = "Riassumi in italiano il testo dell'utente. Prima una frase con il punto principale, poi i punti chiave in un breve " +
                "elenco puntato. Non aggiungere niente che non ci sia nel testo.", maxTokens = 450),
        Skill("traduci", "🌍", "Traduci", "In qualsiasi lingua", Kind.TEXT,
            hint = "Il testo da tradurre", extraLabel = "In che lingua?", extraDefault = "inglese",
            system = "Sei un traduttore esperto. Traduci il testo nella lingua richiesta in modo naturale e fedele. " +
                "Scrivi SOLO la traduzione, senza commenti né spiegazioni.", maxTokens = 700),
        Skill("correggi", "✍️", "Correggi e migliora", "Grammatica, chiarezza, scorrevolezza", Kind.TEXT,
            hint = "Il testo da correggere",
            system = "Correggi grammatica, ortografia e punteggiatura del testo dell'utente e rendilo più chiaro e scorrevole, senza " +
                "cambiarne il senso né il tono. Scrivi SOLO il testo corretto, senza commenti.", maxTokens = 700),
        Skill("messaggio", "✉️", "Scrivi un messaggio", "Email, WhatsApp, lettere", Kind.TEXT,
            hint = "A chi e cosa vuoi dire (es. email al condominio per la perdita d'acqua)", extraLabel = "Tono", extraDefault = "cordiale",
            system = "Scrivi in italiano il messaggio o l'email richiesta dall'utente, con il tono indicato. Se è un'email metti " +
                "l'oggetto sulla prima riga («Oggetto: …»). Deve essere pronto da copiare e inviare: niente spiegazioni.", maxTokens = 550),
        Skill("spiega", "💡", "Spiega facile", "Qualsiasi argomento, in parole semplici", Kind.TEXT,
            hint = "Cosa vuoi capire (es. come funziona l'inflazione)",
            system = "Spiega l'argomento in italiano in modo semplice, come a una persona intelligente che non lo conosce: " +
                "l'idea principale, un esempio concreto della vita di tutti i giorni, al massimo 8 frasi.", maxTokens = 500),
        Skill("idee", "🧠", "Idee", "Brainstorming: 8 proposte diverse", Kind.TEXT,
            hint = "Per cosa ti servono idee (es. nomi per una pizzeria)",
            system = "Proponi 8 idee diverse e concrete per la richiesta dell'utente, in un elenco numerato: ognuna su una riga con " +
                "una breve spiegazione. Varia: alcune semplici e sicure, alcune originali.", maxTokens = 550),
        Skill("piano", "🗂️", "Piano passo passo", "Da un obiettivo a una lista di passi", Kind.TEXT,
            hint = "Il tuo obiettivo (es. organizzare un trasloco in due settimane)",
            system = "Trasforma l'obiettivo dell'utente in un piano pratico: passi numerati in ordine, ognuno breve e concreto, con " +
                "tempi indicativi quando servono. Alla fine un consiglio utile.", maxTokens = 600),
        Skill("procontro", "⚖️", "Pro e contro", "Per decidere con calma", Kind.TEXT,
            hint = "La scelta da valutare (es. comprare o affittare casa)",
            system = "Analizza la scelta dell'utente: un elenco di pro, un elenco di contro, poi una conclusione equilibrata in due " +
                "frasi su cosa conviene considerare. Non decidere al posto dell'utente.", maxTokens = 550),
        Skill("spiegacodice", "🔍", "Spiega o correggi codice", "Cosa fa, errori e correzioni", Kind.TEXT,
            hint = "Incolla qui il codice (Python, MQL5, JavaScript…)", code = true,
            system = "Sei un programmatore esperto. Spiega in italiano cosa fa il codice, passo per passo e in modo chiaro. Poi " +
                "segnala gli errori o i rischi che vedi e scrivi la correzione in un blocco di codice.", maxTokens = 900),
        Skill("web", "🔎", "Cerca sul web", "Risposta con le fonti", Kind.ACTION,
            hint = "Cosa cercare (es. chi ha vinto ieri il Napoli)", command = "cerca su internet %s"),
        Skill("ricerca", "📚", "Ricerca approfondita", "Rapporto in PDF con le fonti", Kind.ACTION,
            hint = "L'argomento (es. auto elettriche in Italia)", command = "fai una ricerca approfondita su %s"),
        Skill("slide", "📊", "Presentazione", "PowerPoint e PDF", Kind.ACTION,
            hint = "L'argomento (es. le energie rinnovabili)", command = "fammi una presentazione su %s"),
        Skill("codice", "💻", "Scrivi codice", "Programmi e script, salvati in un file", Kind.ACTION,
            hint = "Cosa deve fare (es. in Python, calcola le rate di un mutuo)", command = "scrivimi un programma %s"),
        Skill("sito", "🌐", "Sito web", "Una pagina HTML completa", Kind.ACTION,
            hint = "Per cosa (es. la mia pizzeria a Casoria)", command = "fammi un sito web per %s"),
        Skill("ea", "📈", "Expert Advisor MQL5", "Per MetaTrader 5", Kind.ACTION,
            hint = "La strategia (es. incrocio di due medie mobili con stop loss)", command = "scrivimi un expert advisor in mql5 con %s"),
        Skill("disegno", "🎨", "Disegna", "Un'immagine, sul telefono", Kind.ACTION,
            hint = "Cosa disegnare (es. un faro sul mare al tramonto)", command = "disegna %s"),
        Skill("video", "🎬", "Video", "La scena disegnata e animata, in MP4", Kind.ACTION,
            hint = "Cosa mostrare (es. una città futuristica di notte)", command = "fammi un video di %s"),
        Skill("file", "📎", "Analizza un file", "PDF, Word, Excel, foto", Kind.FILE),
    )

    fun byId(id: String): Skill? = ALL.firstOrNull { it.id == id }

    // ---- 1.20: le skill in automatico, quando le chiedi a parole ("traduci in inglese: …", "correggimi questa email: …")
    private fun ci(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val PRE = "^(?:(?:sam|ehi|ok|allora|senti|per favore|dai)[,]?\\s+)*(?:(?:mi |ti )?(?:puoi |potresti |riesci a |vorrei che |devi )?)?"
    private val AUTO: List<Pair<Regex, (MatchResult) -> Request?>> = listOf(
        ci("${PRE}(?:traduci|traducimi|tradurre|traducilo|traduce)\\s+(?:in|nel|nella|all'|al)\\s*([a-zàèéìòù]+)\\s*[:,]?\\s+(.{3,})$") to { m ->
            Request(byId("traduci")!!, m.groupValues[2].trim(), m.groupValues[1].lowercase()) },
        ci("${PRE}(?:traduci|traducimi)\\s+(?:(?:questo|questa frase|questo testo|la frase|il testo)\\s*[:,]?\\s*)?(.{3,}?)\\s+(?:in|nel)\\s+([a-zàèéìòù]+)$") to { m ->
            Request(byId("traduci")!!, m.groupValues[1].trim(), m.groupValues[2].lowercase()) },
        ci("${PRE}(?:correggi|correggimi|sistema|sistemami|migliora|migliorami|rivedi)\\s+(?:questo|questa|il|la|l'|mio|mia)?\\s*(?:testo|email|mail|lettera|messaggio|frase|post|articolo|curriculum|cv)?\\s*[:,]\\s*(.{10,})$") to { m ->
            Request(byId("correggi")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:riassumi|riassumimi|fammi un riassunto di|fai un riassunto di|sintetizza)\\s*(?:questo|questa|il|la|questo testo|questo articolo)?\\s*[:,]\\s*(.{40,})$") to { m ->
            Request(byId("riassumi")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:scrivi|scrivimi|prepara|preparami|componi|buttami giù)\\s+(?:un'|una |un )?(email|mail|messaggio|lettera|whatsapp|sms|risposta)\\s+(.{5,})$") to { m ->
            Request(byId("messaggio")!!, "${m.groupValues[1]} ${m.groupValues[2]}".trim(), "") },
        ci("${PRE}(?:dammi|dimmi|proponimi|trovami|suggeriscimi|mi servono)\\s+(?:qualche |delle |un po' di |\\d+ )?idee?\\s+(?:per|su|di)\\s+(.{3,})$") to { m ->
            Request(byId("idee")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:fammi|preparami|fai|crea|creami|organizzami|dammi)\\s+(?:un |il )?piano\\s+(?:per|di|su)\\s+(.{3,})$") to { m ->
            Request(byId("piano")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:quali sono |dimmi |fammi )?(?:i )?pro e (?:i )?contro\\s+(?:di|del|della|dello|dei|delle|su|sul)\\s+(.{3,})$") to { m ->
            Request(byId("procontro")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:spiegami|spiega)\\s+(?:in modo semplice|in parole semplici|facile|come a un bambino|semplicemente)\\s+(.{3,})$") to { m ->
            Request(byId("spiega")!!, m.groupValues[1].trim(), "") },
        ci("${PRE}(?:spiegami|spiega|correggi|correggimi|controlla)\\s+(?:questo|il)\\s+codice\\s*[:,]?\\s*(```.+|.{20,})$") to { m ->
            Request(byId("spiegacodice")!!, m.groupValues[1].trim().removePrefix("```").removeSuffix("```"), "") },
    )

    /** La skill chiesta a parole, o null. [text] = la frase com'è (con maiuscole e a capo). */
    fun autoDetect(text: String): Request? {
        val t = text.trim()
        if (t.startsWith(MARK)) return null
        for ((re, make) in AUTO) re.find(t)?.let { m -> make(m)?.takeIf { it.text.isNotBlank() }?.let { return it } }
        return null
    }

    // ---- il passaggio dalla schermata al servizio: "⚡id␟extra␟testo" (non si scrive mai a mano)
    private const val MARK = "⚡skill\u001F"
    private const val SEP = '\u001F'

    fun encode(id: String, text: String, extra: String = ""): String = MARK + id + SEP + extra.replace(SEP, ' ') + SEP + text

    class Request(val skill: Skill, val text: String, val extra: String)

    fun decode(s: String): Request? {
        if (!s.startsWith(MARK)) return null
        val parts = s.removePrefix(MARK).split(SEP, limit = 3)
        if (parts.size < 3) return null
        val sk = byId(parts[0]) ?: return null
        return Request(sk, parts[2].trim(), parts[1].trim().ifEmpty { sk.extraDefault })
    }

    /** Il comando che SAM capisce già (per le ACTION). */
    fun command(r: Request): String = r.skill.command.replace("%s", r.text.trim().trimEnd('.', '!', '?'))

    /** La domanda per il cervello (per le TEXT). */
    fun prompt(r: Request): String = when (r.skill.id) {
        "traduci" -> "Lingua: ${r.extra.ifBlank { "inglese" }}\n\nTesto da tradurre:\n${r.text}"
        "messaggio" -> "Tono: ${r.extra.ifBlank { "cordiale" }}\n\nRichiesta: ${r.text}"
        "riassumi", "correggi" -> "Testo:\n${r.text}"
        "spiegacodice" -> "Codice:\n```\n${r.text}\n```"
        else -> r.text
    }

    /** Come appare il tuo messaggio nella conversazione. */
    fun shown(r: Request): String = "${r.skill.emoji} ${r.skill.name}" +
        (if (r.skill.extraLabel != null && r.extra.isNotBlank()) " · ${r.extra}" else "") + "\n" + r.text.trim()
}
