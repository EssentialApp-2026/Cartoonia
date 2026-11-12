package com.essentialapp.sam

/**
 * Nucleo indipendente da Android: tipi, interfacce e aiuti per la lingua.
 * Tutto ciò che sta qui è Kotlin puro, così può essere testato senza telefono.
 */

/** Un contatto trovato in rubrica. */
data class Contact(val name: String, val number: String)

/** Cosa deve fare il servizio vocale dopo aver interpretato una frase. */
sealed class Outcome {
    /** Dillo ad alta voce. endSession=true chiude la conversazione e torna in attesa di "SAM". */
    data class Speak(val text: String, val endSession: Boolean = false) : Outcome()
    /** Gestito, niente da dire. */
    data class Handled(val endSession: Boolean = false) : Outcome()
    /**
     * Passa la domanda all'IA locale. [recall] = la domanda riguarda quello che vi siete detti ("di cosa abbiamo
     * parlato?", "ti ricordi cosa ti ho detto prima?"): il cervello riceve le battute di prima anche se crede di
     * conoscerle. [fallback] = cosa dire se il cervello non c'è o non sa rispondere (le battute ripetute da SAM).
     */
    data class NeedLlm(val prompt: String, val recall: Boolean = false, val fallback: String? = null,
                       /** "pensaci bene": prima un ragionamento passo per passo (che si vede sullo schermo), poi la risposta */
                       val reason: Boolean = false) : Outcome()
    /** Azzera la memoria della conversazione con l'IA. */
    object NewConversation : Outcome()
    /** Conversazione a voce con un'altra intelligenza artificiale (ChatGPT, Gemini… su un altro dispositivo). */
    data class Dialogue(val partner: String?, val topic: String?) : Outcome()
    /** Meteo: apre l'app del meteo (o la ricerca) e legge lo schermo. [city] null = dove sei, [day] null = oggi. */
    data class Weather(val city: String?, val day: String?, val question: String) : Outcome()
    /** Più esiti di fila (una scorciatoia che fa più cose): prima si dicono le frasi, poi l'ultimo esito. */
    data class Many(val parts: List<Outcome>) : Outcome()
    /** La tua voce: enroll | recognize | check | only_on | only_off | forget */
    data class VoiceCmd(val action: String) : Outcome()
    /** Il documento condiviso con SAM: read | next | restart | summary | ask | close ([question] per ask) */
    data class Doc(val action: String, val question: String = "") : Outcome()
    /** Guardare con la fotocamera e rispondere a [question] ("cosa vedi?", "leggimi questo"). */
    data class Look(
        val question: String,
        /** la fotocamera davanti: "guardami", "mi vedi?", "come sono vestito?" */
        val front: Boolean = false,
        /** non guardare: spiegare come si fa ("hai gli occhi?", "perché non vedi?") */
        val explain: Boolean = false,
        /**
         * "apri la fotocamera": se SAM non può guardare (nessun cervello che vede), si apre almeno l'app Fotocamera.
         * Con "guarda", "cosa vedi?" invece no: senza vista SAM spiega perché.
         */
        val openIfBlind: Boolean = false
    ) : Outcome()
    /**
     * Modalità conversazione con SAM ([on] = comincia, altrimenti finisce): ascolta di continuo senza bisogno
     * di dire "SAM", segue il discorso e i comandi funzionano lo stesso. (Non è la conversazione con un'altra IA.)
     */
    data class Chat(val on: Boolean, /** "parliamo di calcio": la prima frase da dare al cervello */ val opener: String? = null) : Outcome()
    /** Spegni del tutto SAM. */
    object Quit : Outcome()
    /** "Cosa dice l'enciclopedia su Napoli?": la voce dell'enciclopedia sul telefono, letta a voce. */
    data class Wiki(val subject: String) : Outcome()
    /**
     * Cambiare come parla o come pensa SAM. [what]: "voice" (un'altra voce: [step] 1 la prossima, -1 quella di prima),
     * "rate" (velocità), "pitch" (tono), "creativity" (fantasia del cervello): [step] +1 di più, -1 di meno, 0 = normale.
     * "voice_which" = quale voce usa.
     */
    data class Tune(val what: String, val step: Int) : Outcome()
    /** Il cervello IA: which (quale usa) | fast (il più veloce) | smart (il più bravo) | other (l'altro) | install (quello nuovo scaricato) */
    data class BrainCmd(val action: String) : Outcome()
    /** Aggiornamenti da internet (solo col Wi-Fi): check | status | auto_on | auto_off | get:<file del cervello> */
    data class Update(val action: String) : Outcome()
    /** Una lamentela su come risponde ("non mi piace quando…"): il cervello la trasforma in una regola, SAM chiede se tenerla. */
    data class SelfEdit(val complaint: String) : Outcome()
    /**
     * Un file per te, nella cartella Download › SAM. [what]: answer (l'ultima risposta) | chat (la conversazione) |
     * doc (il testo del documento aperto) | write (un documento nuovo scritto dal cervello su [topic]) |
     * open | share (l'ultimo file salvato). [pdf] = in PDF invece che in testo semplice.
     */
    data class SaveFile(val what: String, val pdf: Boolean, val topic: String? = null,
                        /** slide: quante (0 = normale) */ val count: Int = 0,
                        /** slide e siti: prima cerca su internet (dati aggiornati, con le fonti) */ val web: Boolean = false) : Outcome()
    /**
     * Disegnare con Stable Diffusion sul telefono. [action]: draw ([prompt] = cosa disegnare, [steps] = passi: più passi,
     * più qualità e più tempo) | download (scarica il modello per disegnare) | can ("sai disegnare?").
     */
    data class Draw(val prompt: String?, val action: String, val steps: Int = 15) : Outcome()
    /**
     * 1.16, come Kimi: cerca su internet [query], legge le pagine e risponde con le fonti. [deep] = ricerca approfondita: più
     * ricerche e più pagine, e alla fine un rapporto in PDF. Internet si usa solo così, quando lo chiedi.
     */
    data class Web(val query: String, val deep: Boolean = false) : Outcome()
    /** 1.20: "leggimi la chat", "riassumi la chat": quello che c'è scritto nella conversazione */
    data class ReadChat(val summary: Boolean, val count: Int = 6) : Outcome()
    /**
     * 1.21, foto e video ([MediaCommands]). [action]: show (mostrarli nella chat) | describe (il cervello guarda l'ultima foto) |
     * photo (scattarne una; [seconds] = autoscatto) | record (filmare [seconds] secondi) | screenrec (registrare lo schermo) |
     * reel (un video con le foto). [kind]: photo | video | any | screenshot. [count] 0 = quante ne vengono; [from]..[to] = il
     * periodo (0 = tutto), detto [period] ("di ieri"); [mine] = quelle fatte da SAM (i disegni); [front] = fotocamera davanti;
     * [open] = aprirla subito; [ask] = era una domanda ("che foto ho fatto ieri?").
     */
    /**
     * 1.22: il nome dell'assistente. [name] = il nome nuovo (già pulito da [Identity.clean]); null con [action] "ask" =
     * chiederlo. [action]: set | reset (torna «SAM») | learn (impara come il riconoscitore scrive il nome) | ask.
     */
    data class Rename(val name: String?, val action: String = "set") : Outcome()
    /**
     * 2.0: la cura di sé. [action]: check | repair | log | backup | backups | restore ([arg] = «ieri», «3 novembre», «» = l'ultima) |
     * clean | space | version | news.
     */
    data class Care(val action: String, val arg: String = "") : Outcome()
    data class Media(val action: String, val kind: String = "photo", val count: Int = 0, val from: Long = 0, val to: Long = 0,
                     val period: String = "", val front: Boolean = false, val seconds: Int = 0, val mine: Boolean = false,
                     val open: Boolean = false, val ask: Boolean = false) : Outcome()
}

/**
 * Un ricordo, salvato per sempre finché non chiedi di cancellarlo. [kind]:
 *  - "nota": gli hai chiesto tu di ricordarla ("ricordati che…");
 *  - "fatto": l'ha imparato da solo da quello che dici di te ("mi piace la pizza");
 *  - "alias": scorciatoia insegnata ([key] = cosa dici, [text] = cosa intendi o cosa deve fare);
 *  - "scelta": quale contatto scegli quando ce ne sono più con lo stesso nome ([key] = nome detto);
 *  - "regola": un'istruzione su come comportarsi, da seguire sempre ("d'ora in poi rispondi in modo breve");
 *  - "lezione": una domanda a cui hai corretto la risposta, o detto "esatto" ([key] = la domanda, [text] = la risposta giusta).
 * [key] serve anche ad aggiornare un fatto che cambia ("non mi piace più la pizza").
 */
data class Memo(val kind: String, val text: String, val key: String = "", val at: Long = 0L)

/** Ricordi e nome del proprietario, salvati per sempre sul telefono (nell'implementazione Android). */
interface Memory {
    /** Tutti i ricordi, dal più vecchio al più recente. */
    fun memos(): List<Memo>
    fun addMemo(m: Memo)
    fun removeMemo(m: Memo)
    fun clearMemos()
    /** Il nome del proprietario del telefono, o null se non l'ha ancora detto. */
    fun ownerName(): String?
    fun setOwnerName(name: String)
}

/**
 * Memoria di SAM: trova i ricordi che parlano di una cosa, impara da solo quello che dici di te
 * (gusti, famiglia, lavoro, squadra…) e aggiorna i fatti che cambiano. Kotlin puro.
 */
object Remember {
    const val NOTA = "nota"
    const val FATTO = "fatto"
    const val ALIAS = "alias"
    const val SCELTA = "scelta"
    const val REGOLA = "regola"
    const val LEZIONE = "lezione"
    /** oltre queste, le lezioni più vecchie lasciano il posto alle nuove */
    const val MAX_LESSONS = 200

    // parole che non dicono di cosa si parla
    private val STOP = listOf(
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "della", "dei", "degli", "delle", "dello",
        "a", "ad", "al", "alla", "ai", "agli", "alle", "allo", "da", "dal", "dalla", "in", "nel", "nella", "nei", "con",
        "su", "sul", "sulla", "per", "tra", "fra", "e", "ed", "o", "che", "chi", "mi", "ti", "si", "ci", "vi", "me", "te",
        "se", "ne", "ma", "non", "è", "sono", "sei", "era", "ho", "hai", "ha", "abbiamo", "mio", "mia", "miei", "mie",
        "tuo", "tua", "tuoi", "tue", "cosa", "quando", "come", "dove", "perché", "quale", "qual", "quanto", "quanti",
        "sempre", "anche", "poi", "già", "più", "molto", "questo", "questa", "quello", "quella", "ricordi", "ricordati",
        "ricorda", "ricordami", "ricordare", "dimentica", "detto", "dire", "dici", "dimmi", "sai", "so", "sapere",
        "fatto", "ricordo", "devo", "deve", "devi", "posso", "puoi", "voglio", "vuoi", "fare", "fa", "sta", "sto",
        "stai", "tutto", "tutti", "tutte", "qualcosa", "cose", "allora", "ora", "ore", "quella", "cosa", "po",
        "all", "dell", "nell", "sull", "dall", "l", "c", "d", "un'", "proprio", "ancora", "solo",
        "io", "tu", "lui", "lei", "noi", "voi", "loro"
    )
    private val STOP_N: Set<String> by lazy { STOP.map { norm(it) }.toSet() }

    fun norm(s: String): String = ContactMatch.norm(s)

    /** Le parole che contano (senza articoli, preposizioni…). */
    fun words(s: String): List<String> = norm(s).split(' ').filter { it.length >= 2 && it !in STOP_N }

    /** "pizza" ~ "pizze", "cane" ~ "cani", "figlio" ~ "figli": stessa parola al plurale o al femminile. */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val m = minOf(a.length, b.length)
        val x = maxOf(a.length, b.length)
        if (m < 4 || x - m > 1) return false
        var p = 0
        while (p < m && a[p] == b[p]) p++
        return p >= x - 1
    }

    /**
     * 0..100: quanto il ricordo parla delle parole cercate. Una parola uguale vale il doppio di una
     * simile ("giulia" trova prima Giulia che Giulio). Serve almeno metà delle parole cercate.
     */
    fun score(query: List<String>, m: Memo): Int {
        if (query.isEmpty()) return 0
        val w = words(m.text + " " + m.key)
        var pts = 0; var hit = 0
        for (q in query) {
            when {
                w.contains(q) -> { pts += 2; hit++ }
                w.any { similar(q, it) } -> { pts += 1; hit++ }
            }
        }
        return if (hit * 2 >= query.size) pts * 50 / query.size else 0
    }

    /** I ricordi che parlano di [query], dal più pertinente. */
    fun find(query: String, memos: List<Memo>): List<Memo> {
        val q = words(query).distinct()
        if (q.isEmpty()) return emptyList()
        return memos.map { it to score(q, it) }.filter { it.second > 0 }
            .sortedWith(Comparator { a, b -> b.second - a.second }).map { it.first }
    }

    /** I ricordi a pari merito con il primo (più di uno = bisogna chiedere quale). */
    fun ties(query: String, found: List<Memo>): List<Memo> {
        if (found.isEmpty()) return found
        val q = words(query).distinct()
        val top = score(q, found[0])
        return found.filter { score(q, it) == top }
    }

    /** true se il ricordo risponde con sicurezza a [query]: tutte le parole uguali (o una sola parola lunga). */
    fun sure(query: String, m: Memo): Boolean {
        val q = words(query).distinct()
        return q.isNotEmpty() && score(q, m) == 100 && (q.size >= 2 || q[0].length >= 5)
    }

    // ------------------------------------------------------------------ fatti imparati da soli
    // di chi o di cosa si può imparare: persone, animali, cose tue
    private val SUBJECTS = Lang.rx(
        "^(?:moglie|marito|compagna|compagno|fidanzata|fidanzato|ragazza|ragazzo|figlio|figlia|figli|figlie|madre|mamma|" +
        "padre|papà|papa|fratello|sorella|fratelli|sorelle|nonno|nonna|nonni|zio|zia|cugino|cugina|amico|amica|" +
        "migliore amico|migliore amica|cane|gatto|gatta|capo|collega|squadra|squadra del cuore|compleanno|anniversario|" +
        "lavoro|mestiere|soprannome|città|paese|macchina|auto|moto|numero|indirizzo|[\\p{L}' ]+ preferit[oaie])$"
    )
    // cose che hanno un solo valore: il fatto nuovo sostituisce il vecchio
    private val THINGS = Lang.rx(
        "^(?:squadra|squadra del cuore|compleanno|anniversario|lavoro|mestiere|soprannome|città|paese|numero|indirizzo|[\\p{L}' ]+ preferit[oaie])$"
    )
    // persone che di solito sono una sola: se ne aggiornano nome, età, lavoro e casa
    private val ONE_PERSON = Lang.rx(
        "^(?:moglie|marito|compagna|compagno|fidanzata|fidanzato|ragazza|ragazzo|madre|mamma|padre|papà|papa|capo|migliore amico|migliore amica)$"
    )
    private val JOBS = setOf(
        "trader", "medico", "avvocato", "avvocata", "ingegnere", "insegnante", "infermiere", "infermiera", "programmatore",
        "programmatrice", "sviluppatore", "sviluppatrice", "operaio", "operaia", "impiegato", "impiegata", "commerciante",
        "imprenditore", "imprenditrice", "studente", "studentessa", "pensionato", "pensionata", "cuoco", "cuoca", "chef",
        "pizzaiolo", "barista", "cameriere", "cameriera", "autista", "camionista", "tassista", "idraulico", "elettricista",
        "meccanico", "muratore", "architetto", "geometra", "commercialista", "ragioniere", "poliziotto", "poliziotta",
        "carabiniere", "militare", "vigile", "farmacista", "dentista", "veterinario", "veterinaria", "giornalista",
        "fotografo", "fotografa", "musicista", "artigiano", "agricoltore", "contadino", "parrucchiere", "parrucchiera",
        "estetista", "commesso", "commessa", "segretario", "segretaria", "dottore", "dottoressa", "professore",
        "professoressa", "maestro", "maestra", "informatico", "consulente", "rappresentante", "venditore", "venditrice",
        "broker", "investitore", "investitrice", "agente", "manager", "direttore", "direttrice", "falegname", "fabbro",
        "panettiere", "pasticciere", "macellaio", "infermiere", "badante", "casalinga", "disoccupato", "disoccupata",
        "psicologo", "psicologa", "fisioterapista", "attore", "attrice", "cantante", "artista", "designer", "grafico",
        "grafica", "giardiniere", "bidello", "bidella", "postino", "postina", "pilota", "marinaio", "pescatore"
    )
    private val NOT_ORIGIN = setOf(
        "fretta", "corsa", "buon", "buonumore", "cattivo", "malumore", "turno", "ritorno", "passaggio", "nuovo", "nuova",
        "sopra", "sotto", "là", "qua", "qui", "lì", "fuori", "casa", "strada", "notte", "giorno", "sera", "mattina",
        "pessimo", "ottimo", "cattivo", "parola", "compagnia", "moda", "troppo", "poco"
    )
    // da qui in poi la frase è una domanda, non un fatto: "il mio cane è malato, cosa devo fare?"
    private val QUESTION_TAIL = Lang.rx(
        "\\s+(?:cosa|che cosa|perché|perche|puoi|potresti|secondo te|che ne pensi|mi consigli|dimmi|sai)\\b"
    )
    private val SINGULAR = mapOf(
        "figli" to "figlio", "figlie" to "figlia", "cani" to "cane", "gatti" to "gatto", "gatte" to "gatta",
        "bambini" to "bambino", "bambine" to "bambina", "nipoti" to "nipote", "fratelli" to "fratello",
        "sorelle" to "sorella", "nonni" to "nonno"
    )

    private fun core(s: String) = words(s).take(3).joinToString(" ")

    // "ho" e "o" si pronunciano uguali: il riconoscitore scrive spesso "o parcheggiato", "domani o il dentista".
    // Si corregge solo a inizio frase o dopo parole come "domani", "che", "non" ("dolce o salato" resta com'è).
    private val HO = Lang.rx(
        "(^|\\b(?:oggi|ieri|domani|dopodomani|stasera|stamattina|stanotte|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|" +
        "domenica|io|che|non|anche|già|sempre|mai|appena|ancora|poi)\\s)o (?=(?:il|lo|la|i|gli|le|un|uno|una|l'|\\d+|due|tre|" +
        "quattro|cinque|sei|sette|otto|nove|dieci|bisogno|fame|sete|sonno|freddo|caldo|paura|voglia|tempo)\\b|\\S+\\s+anni\\b|" +
        "[\\p{L}]+(?:ato|uto|ito|esso|otto|atto|etto|itto|isto|esto|eso|uso|erto)\\b)"
    )
    /** Corregge "o" in "ho" dove il senso è chiaro (verbo avere). */
    fun fixHo(text: String): String = HO.replace(text) { m -> m.groupValues[1] + "ho " }

    /**
     * Un fatto su di te detto in una frase normale ("mi piace la pizza", "mia moglie si chiama Anna",
     * "abito a Casoria"). La chiave serve ad aggiornarlo: "non mi piace la pizza" sostituisce "mi piace la pizza",
     * "la mia squadra è il Milan" sostituisce "la mia squadra è il Napoli". null = nessun fatto da ricordare.
     */
    fun selfFact(text: String, now: Long = 0L): Memo? {
        var t = fixHo(text.trim().lowercase().replace(Regex("\\s+"), " ").trim(' ', '.', ',', '!', '?'))
        t = Lang.rx("^(?:io|beh|allora|sai|sai che|comunque)\\s+").replace(t, "")
        QUESTION_TAIL.find(t)?.let { if (it.range.first >= 6) t = t.substring(0, it.range.first).trim() }
        if (t.length < 5 || t.split(' ').size > 16) return null
        fun memo(key: String) = Memo(FATTO, t.replaceFirstChar { it.uppercase() }, key, now)

        Lang.rx("^(?:non )?mi (?:piace|piacciono|piaceva|piacevano) (?:più |tanto |molto |proprio |da morire |un sacco |troppo )?(.+)").find(t)?.let {
            val c = core(it.groupValues[1]); return if (c.isEmpty()) null else memo("gusto:$c")
        }
        Lang.rx("^(?:adoro|amo|odio|detesto)\\s+(.+)").find(t)?.let {
            val c = core(it.groupValues[1]); return if (c.isEmpty()) null else memo("gusto:$c")
        }
        Lang.rx("^preferisco\\s+(.+)").find(t)?.let {
            val c = core(it.groupValues[1]); return if (c.isEmpty()) null else memo("preferisco:$c")
        }
        Lang.rx("^(?:il mio|la mia|i miei|le mie|mio|mia|miei|mie)\\s+(.+?)\\s+(è|e|sono|si chiama|si chiamano|ha|a|hanno|fa|lavora|vive|abita|compie)\\s+(.+)").find(t)?.let {
            var subj = it.groupValues[1].trim()
            if (!SUBJECTS.containsMatchIn(subj)) return null
            subj = when (subj) { "madre" -> "mamma"; "padre", "papà" -> "papa"; "mestiere" -> "lavoro"; "squadra del cuore" -> "squadra"; else -> subj }
            val v = when (it.groupValues[2]) {
                "è", "e", "sono" -> "e"; "si chiama", "si chiamano" -> "nome"; "vive", "abita" -> "casa"; "ha", "a", "hanno" -> "ha"
                else -> it.groupValues[2]
            }
            val s = norm(subj)
            val key = when {
                subj == "lavoro" && v == "e" -> "lavoro"
                THINGS.containsMatchIn(subj) && v == "e" -> "mio:$s"
                ONE_PERSON.containsMatchIn(subj) && v in setOf("nome", "compie", "lavora", "casa") -> "mio:$s:$v"
                else -> "mio:$s:$v:" + core(it.groupValues[3])
            }
            return memo(key)
        }
        Lang.rx("^(?:abito|vivo)\\s+(?:a|ad|in|al|alla|allo|nel|nella|vicino a)\\s+(.+)").find(t)?.let { return memo("casa") }
        Lang.rx("^lavoro\\s+(?:come|presso|per|in|nel|nella|nello|all'|alla|al)\\s*(.+)").find(t)?.let { return memo("lavoro") }
        Lang.rx("^(?:di lavoro|di mestiere|per lavoro|nella vita)\\s+faccio\\s+(.+)").find(t)?.let { return memo("lavoro") }
        Lang.rx("^(?:faccio|sono)\\s+(?:il|la|l'|un|una|un')\\s*([\\p{L}]+)").find(t)?.let {
            if (it.groupValues[1] in JOBS) return memo("lavoro")
        }
        Lang.rx("^tifo\\s+(?:per\\s+)?(.+)").find(t)?.let { return memo("mio:squadra") }
        Lang.rx("^sono\\s+(allergic[oa]|intollerante|vegetarian[oa]|vegan[oa]|celiac[oa]|diabetic[oa]|astemi[oa])\\b").find(t)?.let {
            return memo("salute:" + core(t.removePrefix("sono ")))
        }
        Lang.rx("^sono nat[oa]\\s+(.+)").find(t)?.let { return memo("nascita") }
        Lang.rx("^sono (?:di|originario di|originaria di)\\s+([\\p{L}' ]+)$").find(t)?.let {
            val w = it.groupValues[1].trim().split(' ')
            return if (w.size <= 3 && w[0] !in NOT_ORIGIN) memo("origine") else null
        }
        Lang.rx("^ho\\s+(\\S+)\\s+anni(?:\\s+e\\s+\\S+\\s+mesi)?$").find(t)?.let {
            return if (Num.parse(it.groupValues[1]) != null) memo("eta") else null
        }
        Lang.rx("^ho\\s+(?:un|una|un'|uno|due|tre|quattro|cinque|sei|\\d+)\\s+(cane|cani|gatto|gatti|gatta|gatte|figlio|figlia|figli|figlie|bambino|bambina|bambini|bambine|nipote|nipoti|fratello|fratelli|sorella|sorelle|nonni)\\b").find(t)?.let {
            val n = norm(it.groupValues[1]); return memo("ho:" + (SINGULAR[n] ?: n))
        }
        return null
    }

    // ------------------------------------------------------------------ scorciatoie
    private val LEAD = Lang.rx(
        "^(?:(?:il|lo|la|i|gli|le|un|uno|una|mio|mia|miei|mie|a|ad|al|alla|allo|ai|alle|da|dal|dalla|di|del|della|su|sul|sulla)\\s+|l'|un'|all'|dall'|dell'|sull')"
    )
    /** La forma con cui si confronta una scorciatoia: "la mia ragazza" e "ragazza" sono la stessa. */
    fun aliasKey(s: String): String {
        var x = s.trim().lowercase()
        repeat(4) { x = LEAD.replace(x, "") }
        return norm(x)
    }

    private val COMMAND_START = Lang.rx(
        "^(?:accendi|spegni|apri|chiudi|chiama|telefona|metti|alza|abbassa|scrivi|manda|invia|leggi|cerca|cercami|" +
        "imposta|attiva|disattiva|ferma|togli|avvia|riproduci|suona|fammi|dimmi|blocca|torna|vai|portami|naviga|fai|" +
        "scatta|ricordami|riprendi|pausa|svegliami|sveglia|timer|volume|luminosità|torcia|musica|che ore|che tempo|" +
        "meteo|quanto fa|calcola|copia|esci|silenzia|parla|modalità|prossima|leggimi|controlla|guarda|guardami)\\b"
    )
    /** true se la scorciatoia è un comando da eseguire ("spegni la torcia"), false se è un nome ("giulia"). */
    fun isCommand(v: String): Boolean = COMMAND_START.containsMatchIn(v.trim())

    /** Dove abiti, se l'hai detto ("abito a Casoria" -> "casoria"), per "portami a casa" e il meteo di casa. */
    fun home(memos: List<Memo>): String? {
        val m = memos.lastOrNull { it.key == "casa" && (it.kind == FATTO || it.kind == NOTA) } ?: return null
        return Lang.rx("^(?:io )?(?:abito|vivo)\\s+(?:a|ad|in|al|alla|allo|nel|nella|vicino a)\\s+(.+)").find(m.text.lowercase())
            ?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    /**
     * I ricordi da dare al cervello IA: le note che gli hai chiesto, i fatti imparati e i nomi insegnati,
     * i più recenti, entro [budget] caratteri: il modello li rilegge a ogni conversazione nuova e sul
     * telefono ogni parola in più è attesa in più. Tutti i ricordi restano comunque salvati e cercabili.
     */
    fun forPrompt(memos: List<Memo>, maxNotes: Int = 20, maxFacts: Int = 15, budget: Int = 1100): List<String> {
        val out = ArrayList<String>()
        var left = budget
        fun pick(list: List<Memo>, max: Int, share: Int, show: (Memo) -> String): List<String> {
            val chosen = ArrayList<String>()
            var room = minOf(left, share)
            for (m in list.asReversed().take(max)) {      // dal più recente
                val s = show(m).let { if (it.length > 160) it.take(157) + "…»" else it }
                if (s.length + 2 > room) break
                chosen.add(0, s); room -= s.length + 2
            }
            left -= chosen.sumOf { it.length + 2 }
            return chosen
        }
        val notes = pick(memos.filter { it.kind == NOTA }, maxNotes, budget * 5 / 10) { "«${it.text}»" }
        val facts = pick(memos.filter { it.kind == FATTO }, maxFacts, budget * 3 / 10 + maxOf(0, left - budget * 8 / 10)) { "«${it.text}»" }
        val names = pick(memos.filter { it.kind == ALIAS && !isCommand(it.text) }, 10, left) { "«${it.key}» intende «${it.text}»" }
        if (notes.isNotEmpty()) out.add("Ti ha chiesto di ricordare: " + notes.joinToString("; ") + ".")
        if (facts.isNotEmpty()) out.add("Ti ha raccontato di sé: " + facts.joinToString("; ") + ".")
        if (names.isNotEmpty()) out.add("Quando dice " + names.joinToString("; ") + ".")
        return out
    }

    /**
     * Le regole che gli hai dato ("d'ora in poi rispondi in modo breve", "non chiamarmi per nome"), le più recenti
     * entro [budget] caratteri: vanno nelle istruzioni del cervello IA, che le deve seguire sempre.
     */
    fun rulesForPrompt(memos: List<Memo>, max: Int = 12, budget: Int = 900): List<String> {
        val out = ArrayList<String>()
        var room = budget
        for (m in memos.filter { it.kind == REGOLA }.asReversed().take(max)) {
            val s = "«" + m.text.let { if (it.length > 200) it.take(197) + "…" else it } + "»"
            if (s.length + 2 > room) break
            out.add(0, s); room -= s.length + 2
        }
        return out
    }

    /**
     * Le lezioni che c'entrano con la domanda: domande simili a cui l'hai corretto ("sbagliato, è Canberra") o detto
     * "esatto". Vanno davanti alla domanda, così il cervello risponde come gli hai insegnato. null = nessuna.
     * Una lezione vale se la domanda ha in comune con quella di allora almeno metà delle parole che contano.
     */
    fun lessons(question: String, memos: List<Memo>, max: Int = 2): String? {
        val q = words(question).distinct()
        if (q.size < 2) return null
        val ranked = memos.filter { it.kind == LEZIONE }
            .map { it to score(q, Memo(LEZIONE, it.key)) }
            .filter { it.second >= 60 }
            .sortedWith(Comparator { a, b -> b.second - a.second })
            .take(max).map { it.first }
        if (ranked.isEmpty()) return null
        return "(Lezione da una vostra conversazione passata: " +
            ranked.joinToString("; ") { "alla domanda «${it.key}» la risposta giusta è «${it.text}»" } + ")"
    }

    /**
     * I ricordi più vecchi che c'entrano con la domanda e non stanno già nelle istruzioni [inPrompt]:
     * si mettono davanti alla domanda, così il cervello IA li usa anche se sono tanti. null = nessuno.
     */
    fun context(question: String, memos: List<Memo>, inPrompt: List<String>): String? {
        val all = memos.filter { it.kind == NOTA || it.kind == FATTO }
        val extra = find(question, all).take(3).filter { m -> inPrompt.none { it.contains("«${m.text}»") } }
        return if (extra.isEmpty()) null else "(Ricordi che c'entrano: " + extra.joinToString("; ") { "«${it.text}»" } + ")"
    }
}

/** Lettura del meteo dal testo dello schermo, quando il cervello IA non c'è (o per capire se la pagina è pronta). */
object WeatherText {
    private val TEMP = Regex("(-?\\d{1,2})\\s*°")
    private val COND = Lang.rx(
        "\\b(sereno|soleggiato|poco nuvoloso|parzialmente nuvoloso|molto nuvoloso|nuvoloso|coperto|variabile|" +
        "pioggia debole|pioggia forte|pioggia|piovoso|pioviggine|rovesci|acquazzoni|temporale|temporali|neve|nevischio|" +
        "nebbia|foschia|vento forte|ventoso|grandine|sole|nubi sparse|nuvole sparse)\\b"
    )
    private val DAYS = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")

    /** true se nel testo c'è almeno una temperatura (la schermata del meteo è caricata). */
    fun hasTemperature(screen: String): Boolean = TEMP.containsMatchIn(screen)

    /**
     * "Adesso ci sono 22 gradi, sereno." oppure, per [day] = "domani" o un giorno della settimana,
     * "Domani: pioggia, tra 13 e 19 gradi." se la riga di quel giorno si trova. null se non c'è una temperatura.
     */
    fun quick(screen: String, day: String? = null): String? {
        if (day != null && day != "oggi") {
            val lines = screen.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val words = if (day == "domani") listOf("domani") else DAYS.filter { day.contains(it) }.flatMap { listOf(it, it.take(3)) }
            val i = lines.indexOfFirst { l -> words.any { w -> Lang.rx("^$w\\b").containsMatchIn(l) } }
            if (i >= 0) {
                val block = lines.subList(i, minOf(lines.size, i + 5)).joinToString(" ")
                val temps = TEMP.findAll(block).map { it.groupValues[1].toInt() }.toList()
                val cond = COND.find(block)?.value?.lowercase()
                if (temps.isNotEmpty()) {
                    val quando = day.replaceFirstChar { it.uppercase() }
                    val gradi = if (temps.size >= 2) "tra ${minOf(temps[0], temps[1])} e ${maxOf(temps[0], temps[1])} gradi" else "${temps[0]} gradi"
                    return "$quando: " + (if (cond != null) "$cond, " else "") + "$gradi."
                }
            }
        }
        val temp = TEMP.find(screen)?.groupValues?.get(1) ?: return null
        val cond = COND.find(screen)?.value?.lowercase()
        val now = "Adesso ci sono $temp gradi" + (if (cond != null) ", $cond." else ".")
        // il giorno chiesto non si trova nel testo: si dice com'è adesso e dove guardare, senza inventare
        return if (day != null && day != "oggi" && day != "stasera") "$now Per $day guarda le previsioni sullo schermo." else now
    }
}

/** Tutte le azioni sul telefono. Implementate da AndroidActions; finte nei test. */
interface Actions {
    fun openApp(name: String): Boolean
    fun openSettings(): Boolean
    fun openCamera(selfie: Boolean): Boolean

    fun batteryText(): String

    fun torch(on: Boolean): Boolean
    fun vibrate(ms: Long)

    fun currentVolumePercent(ring: Boolean): Int
    fun setVolumePercent(percent: Int, ring: Boolean)

    fun setBrightnessPercent(percent: Int)
    fun setBrightnessAuto()

    fun setAlarm(hour: Int, minute: Int): Boolean
    fun setTimer(seconds: Int): Boolean

    /** I contatti che somigliano al nome detto, dal più probabile (vuota = nessuno). */
    fun contacts(name: String): List<Contact>
    /** true se SAM può far partire la chiamata da solo (permesso Telefono concesso). */
    fun canCallDirectly(): Boolean
    fun call(number: String): Boolean
    fun sendSms(number: String, body: String): Boolean
    /** Apre la chat WhatsApp con il messaggio già scritto (il tasto invia lo preme l'utente). */
    fun openWhatsApp(number: String, body: String): Boolean
    /** true se SAM può premere da solo "invia" su WhatsApp (servizio di accessibilità attivo). */
    fun canAutoSend(): Boolean
    /** Apre la chat con il messaggio e preme invia da solo; l'esito arriva poi a voce. */
    fun sendWhatsApp(number: String, body: String): Boolean

    fun navigateTo(dest: String): Boolean
    fun whereAmI(): Boolean

    /**
     * Apre il meteo: l'app del meteo per dove sei ([city] null), altrimenti la ricerca "meteo città giorno".
     * Ritorna il pacchetto dell'app aperta ("" se non si sa quale), oppure null se non si è aperto niente.
     */
    fun openWeather(city: String?, day: String?): String?

    fun openBrowser(): Boolean
    fun openUrl(url: String): Boolean
    fun webSearch(query: String): Boolean
    fun youtubeSearch(query: String): Boolean
    fun playMusic(): Boolean
    /** cerca e fa partire una canzone, un artista o un album nell'app di musica */
    fun playSearch(query: String): Boolean

    /** pause | play | next | previous: tasti multimediali per ciò che sta suonando */
    fun media(cmd: String): Boolean
    /** dismiss | snooze | alarms | timers: spegne o posticipa la sveglia, oppure apre l'elenco */
    fun alarmScreen(which: String): Boolean

    fun readNotifications(): String
    /** back | home | recents | notifications | quicksettings | lock | power | dismiss_shade */
    fun globalAction(which: String): Boolean
    /** apre il pannello rapido di sistema (wifi, bluetooth, dati, aereo, nfc...) */
    fun openSystemPanel(which: String): Boolean

    fun clipboardGet(): String
    fun clipboardSet(text: String)

    /**
     * Apre la schermata con cui SAM guarda (fotocamera, scatto da solo); la foto arriva poi al servizio vocale.
     * [front] = la fotocamera davanti, per guardare te.
     */
    fun look(front: Boolean): Boolean
}

/**
 * Numeri detti a parole, come li scrive il riconoscitore vocale: "venticinque", "trentatré",
 * "duecentocinquanta", "duemilaventisei". Il riconoscitore non scrive mai le cifre.
 */
object Num {
    private val UNITS = linkedMapOf(
        "zero" to 0, "uno" to 1, "un" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12,
        "tredici" to 13, "quattordici" to 14, "quindici" to 15, "sedici" to 16, "diciassette" to 17,
        "diciotto" to 18, "diciannove" to 19
    )
    private val TENS = linkedMapOf(
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50, "sessanta" to 60,
        "settanta" to 70, "ottanta" to 80, "novanta" to 90
    )
    /** parole che sono anche altro ("chi sei", "una sveglia", "un quarto"): si convertono solo dove serve */
    private val AMBIGUOUS = setOf("un", "uno", "una", "sei")

    /** Il valore di una parola-numero (o di cifre), null se non è un numero. */
    fun parse(word: String): Int? {
        val s = word.lowercase().trim().replace('é', 'e').replace('è', 'e')
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.take(9).toIntOrNull()
        if (s == "mille") return 1000
        if (s.startsWith("mille")) return below1000(s.removePrefix("mille"))?.let { 1000 + it }
        val k = s.indexOf("mila")
        if (k > 0) {
            val head = below1000(s.substring(0, k)) ?: return null
            val tail = s.substring(k + 4)
            return head * 1000 + (if (tail.isEmpty()) 0 else below1000(tail) ?: return null)
        }
        return below1000(s)
    }

    private fun below1000(s: String): Int? {
        if (s.isEmpty()) return null
        val c = s.indexOf("cento")
        if (c >= 0) {
            val h = if (c == 0) 1 else UNITS[s.substring(0, c)]?.takeIf { it in 2..9 } ?: return null
            var tail = s.substring(c + 5)
            if (tail.startsWith("tto")) tail = "o$tail"          // "centotto"
            return h * 100 + (if (tail.isEmpty()) 0 else below100(tail) ?: return null)
        }
        return below100(s)
    }

    private fun below100(s: String): Int? {
        UNITS[s]?.let { return it }
        TENS[s]?.let { return it }
        for ((t, v) in TENS) {
            if (s.startsWith(t)) UNITS[s.removePrefix(t)]?.let { u -> if (u in 1..9) return v + u }
            val stem = t.dropLast(1)                                  // "ventuno", "trentotto"
            if (s.startsWith(stem)) {
                val tail = s.removePrefix(stem)
                if (tail == "uno" || tail == "un" || tail == "una" || tail == "otto") return v + UNITS.getValue(tail)
            }
        }
        return null
    }

    /**
     * Sostituisce le parole-numero con le cifre ("timer di venticinque minuti" -> "timer di 25 minuti").
     * Con [all] = false lascia stare "un", "una", "uno" e "sei", che spesso non sono numeri.
     */
    fun wordsToDigits(text: String, all: Boolean = false): String =
        text.split(' ').joinToString(" ") { w ->
            val lw = w.lowercase()
            if (!all && lw in AMBIGUOUS) w else parse(lw)?.toString() ?: w
        }

    /** Le cifre di un numero di telefono dettato: "tre tre tre uno due" -> "33312"; null se non lo è. */
    fun phoneDigits(text: String): String? {
        val sb = StringBuilder()
        for (w in text.lowercase().split(Regex("[\\s,.-]+")).filter { it.isNotBlank() }) {
            when {
                (w == "più" || w == "piu" || w == "+") && sb.isEmpty() -> sb.append('+')
                w.startsWith("+") && w.drop(1).all { it.isDigit() } && sb.isEmpty() -> sb.append(w)
                w.all { it.isDigit() } -> sb.append(w)
                else -> sb.append(parse(w)?.toString() ?: return null)
            }
        }
        return if (sb.count { it.isDigit() } >= 3) sb.toString() else null
    }
}

/**
 * Trova il contatto giusto anche quando il riconoscitore sbaglia una lettera, quando dici
 * solo il nome o il cognome, o quando in rubrica ci sono accenti ed emoji. Kotlin puro.
 */
object ContactMatch {

    fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    /** Quanto il nome detto somiglia al nome in rubrica: 100 = uguale, 0 = per niente. */
    fun score(query: String, name: String): Int {
        val q = norm(query); val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0
        if (q == n) return 100
        val words = n.split(' ')
        val qWords = q.split(' ')
        if (qWords.size > 1 && qWords.all { qw -> words.contains(qw) }) return 95     // "mario rossi" e "rossi mario"
        if (words.contains(q)) return 90                                              // solo il nome o il cognome
        if (n.startsWith("$q ") || n.startsWith(q) && q.length >= 4) return 80
        // errori del riconoscitore: una o due lettere diverse
        var best = 0
        for (w in words + n) {
            val d = distance(q, w)
            val s = when {
                q.length >= 4 && d == 1 -> 70
                q.length >= 7 && d == 2 -> 60
                else -> 0
            }
            if (s > best) best = s
        }
        if (best > 0) return best
        if (q.length >= 4 && n.contains(q)) return 50
        return 0
    }

    /** I contatti più somiglianti, dal migliore; a parità vince il nome più corto. */
    fun rank(query: String, all: List<Contact>): List<Contact> {
        val scored = all.map { it to score(query, it.name) }.filter { it.second >= 50 }
        return scored.sortedWith(Comparator { a, b ->
            if (a.second != b.second) b.second - a.second else a.first.name.length - b.first.name.length
        }).map { it.first }
    }

    /** I contatti a pari merito con il migliore (più di uno = bisogna chiedere quale). */
    fun ties(query: String, ranked: List<Contact>): List<Contact> {
        if (ranked.isEmpty()) return ranked
        val top = score(query, ranked[0].name)
        return ranked.filter { score(query, it.name) == top }
    }

    /** Distanza di modifica (Levenshtein) tra due parole. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

/** Cosa fare di una frase finita sentita dal riconoscitore. */
sealed class Heard {
    /** rumore, riempitivi o frase senza "SAM" mentre si è in attesa */
    object Ignore : Heard()
    /** ha detto solo "SAM": mettiti in ascolto */
    object WakeOnly : Heard()
    /** "basta", "grazie"… durante la conversazione */
    object Stop : Heard()
    /** una richiesta da eseguire */
    data class Command(val text: String) : Heard()
}

/** Logica di ascolto, uguale sul telefono e nei test. */
object Hearing {
    /**
     * [listening] = la conversazione è aperta (dopo "SAM").
     * [pending] = SAM aspetta una risposta (testo di un messaggio, conferma).
     */
    fun decide(text: String, listening: Boolean, pending: Boolean): Heard {
        if (!listening) {
            val rem = Lang.stripWake(text) ?: return Heard.Ignore
            val cmd = Utterance.command(rem)
            return if (cmd.isEmpty()) Heard.WakeOnly else Heard.Command(cmd)
        }
        val cmd = if (pending) text.trim() else Utterance.command(text)
        if (cmd.isEmpty()) return Heard.Ignore
        if (!pending && Lang.isStop(cmd)) return Heard.Stop
        return Heard.Command(cmd)
    }

    /** Sul risultato parziale si scatta solo per un "sam" sicuro (reazione immediata). */
    fun wakeOnPartial(partial: String): Boolean = Lang.hasWake(partial)
}

/** Pulizia della frase sentita dal riconoscitore prima di passarla al cervello. */
object Utterance {
    private val FILLERS = setOf("a", "ah", "eh", "e", "o", "oh", "ehm", "mh", "uh", "ehi", "hey", "ok", "okay")

    /** Toglie "sam" e ciò che lo precede, i riempitivi iniziali e il rumore ([unk]). "" = niente da fare. */
    fun command(heard: String): String {
        var t = heard.replace("[unk]", " ").trim()
        Lang.stripWake(t)?.let { t = it }
        val words = t.split(' ').filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && words[0].lowercase() in FILLERS) words.removeAt(0)
        val out = words.joinToString(" ")
        return if (out.length < 2) "" else out
    }
}

/**
 * 1.22 · Il nome dell'assistente: di base «SAM», ma si può cambiare quando vuoi («da ora ti chiami Jarvis», o dalle
 * impostazioni). Cambia la parola per chiamarlo, come si presenta e quello che si legge sullo schermo.
 * Il riconoscitore della voce non conosce tutti i nomi (scrive quello che gli somiglia di più: «Jarvis» → «giar vis»),
 * quindi: confronto per somiglianza di suono all'inizio della frase, e i modi in cui l'ha sentito ([heard], imparati
 * quando gli dici il nome dopo averlo cambiato). Kotlin puro.
 */
object Identity {
    const val DEFAULT = "SAM"
    @Volatile var name: String = DEFAULT; private set
    /** come il riconoscitore scrive il nome (imparato), già normalizzato */
    @Volatile var heard: List<String> = emptyList(); private set
    val isDefault: Boolean get() = name == DEFAULT

    fun set(n: String?, heardForms: List<String> = emptyList()) {
        name = n?.let { clean(it) } ?: DEFAULT
        heard = heardForms.map { norm(it) }.filter { it.length >= 2 }.distinct().take(8)
    }

    fun addHeard(form: String) { val f = norm(form); if (f.length >= 2 && f !in heard) heard = (heard + f).takeLast(8) }

    private val NOT_NAMES = setOf("dopo", "domani", "stasera", "subito", "tra", "fra", "più", "piu", "io", "tu", "me", "te", "lui", "lei",
        "come", "cosa", "chi", "niente", "nulla", "così", "cosi", "bene", "male", "adesso", "ora", "il", "la", "lo", "un", "una", "uno",
        "bello", "bellissimo", "strano", "carino", "simpatico", "buffo", "brutto", "corto", "lungo", "perfetto", "giusto", "sbagliato",
        "fantastico", "difficile", "facile", "originale", "particolare", "troppo", "molto", "proprio", "davvero", "cambiato", "nuovo")

    /** Il nome scritto bene: "jarvis" → "Jarvis", "sam" → "SAM", "KITT" resta così; null = non va bene come nome. */
    fun clean(raw: String): String? {
        val t = raw.trim().trim('"', '«', '»', '.', '!', '?', ',', ';', ':', ' ').replace(Regex("\\s+"), " ")
        if (t.length !in 2..24 || t.split(' ').size > 3) return null
        if (!t.all { it.isLetter() || it == ' ' || it == '\'' || it == '-' }) return null
        if (t.lowercase() in NOT_NAMES || t.split(' ').first().lowercase() in NOT_NAMES) return null
        if (t.equals(DEFAULT, true)) return DEFAULT
        return if (t != t.lowercase()) t else t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** minuscolo, senza accenti, solo lettere e spazi */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Come suona, all'italiana (per confrontare il nome con quello che ha scritto il riconoscitore). */
    fun sound(s: String): String {
        var t = norm(s).replace(" ", "")
        t = t.replace("ph", "f").replace("sh", "sc").replace("ck", "k").replace("ch", "k").replace("gh", "g")
            .replace("j", "gi").replace("y", "i").replace("w", "u").replace("x", "cs").replace("q", "k").replace("h", "")
        t = t.replace(Regex("c(?=[aou]|$|[^aeiouei])"), "k").replace(Regex("(?<=[cg])i(?=[aou])"), "")
        t = t.replace("v", "b").replace("z", "s").replace("e", "i").replace("o", "u")
        return t.replace(Regex("(.)\\1+"), "$1")
    }

    private fun dist(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val cur = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = cur }
        }
        return d[b.length]
    }

    private val LEAD = setOf("ehi", "hey", "ei", "ok", "okay", "ciao", "senti", "allora", "e", "oh", "o", "ah", "eh")

    /**
     * Se nella frase c'è il nome ritorna quello che segue (anche ""), altrimenti null. All'inizio della frase (dopo
     * «ehi», «ok», «ciao») basta che suoni simile; più avanti deve essere proprio il nome (o un modo in cui l'ha sentito).
     */
    fun strip(text: String): String? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val target = sound(name)
        val n = norm(name).split(' ').size
        val tol = when { target.length <= 3 -> 0; target.length <= 6 -> 1; else -> 2 }
        var start = 0
        while (start < words.size - 1 && norm(words[start]) in LEAD) start++
        for (i in 0 until words.size) {
            for (len in 1..minOf(3, words.size - i)) {
                val win = norm(words.subList(i, i + len).joinToString(" "))
                if (win.isEmpty()) continue
                val exact = win == norm(name) || win in heard
                val near = i == start && len <= n + 1 && sound(win).let { it.length >= 2 && dist(it, target) <= tol }
                if (exact || near) return words.drop(i + len).joinToString(" ").trim(' ', ',', '.', '!', '?', ':')
            }
        }
        return null
    }

    /** I testi per lo schermo e per la voce: «SAM» diventa il nome scelto (non le cartelle e i nomi dei file, che restano «SAM»). */
    fun say(text: String): String {
        if (isDefault || !text.contains(DEFAULT)) return text
        return Regex("(?<![\\p{L}\\p{N}_])SAM(?![\\p{L}\\p{N}_])").replace(text) { m ->
            val before = text.substring(maxOf(0, m.range.first - 14), m.range.first)
            val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 10))
            val keep = before.endsWith("cartella ") || before.endsWith("› ") || before.endsWith("\\") || before.endsWith("/") ||
                before.endsWith("Leggi con ") || before.endsWith("Apri con ") || before.endsWith("→ ") ||
                after.startsWith(" per PC") || after.startsWith(".") && after.length > 1 && after[1].isLetter() || after.startsWith(" -") ||
                after.startsWith("\\") || after.startsWith("/") || after.startsWith(" ›")
            if (keep) m.value else name
        }
    }
}

/** Riconoscimento della lingua: parola di attivazione, "basta", "sì". */
object Lang {
    /**
     * Confine di parola che riconosce anche le lettere accentate (sì, metà, più…)
     * e si comporta allo stesso modo sulla JVM e sul motore ICU di Android.
     * (Il flag (?U) di Java non è supportato da Android: qui non si usa.)
     */
    const val WB = "(?:(?<=[\\p{L}\\p{N}_])(?![\\p{L}\\p{N}_])|(?<![\\p{L}\\p{N}_])(?=[\\p{L}\\p{N}_]))"

    /**
     * Testi da e verso le librerie native (Vosk, LiteRT-LM) sempre in UTF-8: JNA altrimenti usa la
     * codifica di sistema (su un PC può essere ASCII e le accentate diventano "?").
     */
    fun utf8Native() { System.setProperty("jna.encoding", "UTF-8") }

    /** Regex senza distinzione maiuscole/minuscole, con \\b sostituito dal confine portabile. */
    fun rx(p: String) = Regex(p.replace("\\b", WB), RegexOption.IGNORE_CASE)

    // "SAM" e le storpiature che il riconoscitore italiano produce davvero (provate sul modello).
    private val WAKE = rx("\\b(?:ehi |hey |ok |ciao )?(?:sam|sem|sammy|samu|zam)\\b[\\s,.!?:]*(.*)")

    // "sam" sentito come "san": valido SOLO a inizio frase e se non segue il nome di un santo/luogo
    private val SAN_START = rx("^(?:ehi |hey |ok |ciao )?san\\b[\\s,.!?:]*(.*)")
    private val SANTI = setOf(
        "gennaro", "giuseppe", "francesco", "paolo", "pietro", "giovanni", "marco", "luca", "matteo",
        "antonio", "valentino", "siro", "remo", "marino", "lorenzo", "michele", "nicola", "domenico",
        "gregorio", "biagio", "giorgio", "vito", "rocco", "salvatore", "carlo", "raffaele", "sebastiano",
        "martino", "benedetto", "donato", "gimignano", "severo", "felice", "vittore", "babila", "zeno",
        "petronio", "ambrogio", "frediano", "miniato", "leucio", "gaetano", "tommaso", "filippo",
        "giacomo", "andrea", "bartolomeo", "luigi", "pio", "cataldo", "agostino", "ciro", "gennarino",
        "valentin", "silvestro", "stefano", "mauro", "leone", "simone", "daniele", "giuliano", "germano"
    )

    private val STOP = rx("\\b(basta|stop|fine|grazie|niente|nulla|nient'?altro|lascia stare|a dopo|ciao ciao|arrivederci|termina)\\b")
    // se c'è un oggetto o un verbo d'azione non è un congedo ma un comando: "basta musica", "grazie, spegni la torcia"
    private val COMMANDISH = rx(
        "\\b(musica|canzone|brano|video|radio|torcia|luce|sveglia|timer|allarme|volume|audio|fotocamera|" +
        "accendi|spegni|apri|chiudi|chiama|metti|alza|abbassa|scrivi|manda|leggi|cerca|imposta|attiva|disattiva|" +
        "ferma|togli|pausa|riprendi)\\b"
    )
    // da solo, in risposta a una domanda, il riconoscitore a volte scrive "sei", "se" o "sci" al posto di "sì"
    private val YES = rx("\\b(s[iì]|certo|conferma|confermo|vai|ok|okay|esatto|procedi|giusto|va bene|perfetto|corretto)\\b|" +
        "^\\W*(?:sei|se|sci|sii|ssì)\\W*$")
    private val NO = rx("^\\W*(no|non|sbagliato|niente affatto)\\b")
    private val THANKS = rx("\\bgrazie\\b")

    // parole che non possono essere un nome
    private val NOT_A_NAME = setOf(
        "no", "si", "sì", "boh", "niente", "nulla", "grazie", "ciao", "e", "ed", "ok", "okay", "il", "la", "lo",
        "mi", "io", "sono", "chiamo", "nome", "è", "di", "che", "come", "piacere", "tu", "te", "allora", "eh",
        "ehm", "ah", "beh", "basta", "stop", "un", "una", "uno", "per", "favore", "non", "so", "chiami", "ti", "pure",
        "così", "anche", "ancora", "sempre", "proprio", "solo", "davvero",
        // verbi di comando: "sam accendi la torcia" detto al posto del nome non è un nome
        "accendi", "spegni", "apri", "chiudi", "chiama", "metti", "alza", "abbassa", "scrivi", "manda", "leggi",
        "cerca", "imposta", "attiva", "disattiva", "ferma", "togli", "dimmi", "fammi", "vai", "torna",
        "quanto", "dove", "cosa", "perché", "quando", "qual", "quale"
    )

    /** Se c'è la parola di attivazione ritorna ciò che segue (può essere ""), altrimenti null. */
    fun stripWake(text: String): String? {
        val t = text.trim()
        if (!Identity.isDefault) return Identity.strip(t)
        WAKE.find(t)?.let { return it.groupValues[1].trim() }
        SAN_START.find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val next = rest.substringBefore(' ').lowercase()
            if (next !in SANTI) return rest
        }
        return null
    }

    /** Controllo veloce sui risultati parziali: solo "sam" sicuro (il caso "san" si decide a frase finita). */
    fun hasWake(text: String): Boolean = if (Identity.isDefault) WAKE.containsMatchIn(text) else Identity.strip(text) != null

    fun isStop(text: String): Boolean {
        val t = text.trim()
        return STOP.containsMatchIn(t) && t.split(Regex("\\s+")).size <= 4 && !COMMANDISH.containsMatchIn(t)
    }

    private val GIORNI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    private val MESI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")

    /** "sabato 10 ottobre 2026" */
    fun italianDate(d: java.time.LocalDate): String =
        "${GIORNI[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MESI[d.monthValue - 1]} ${d.year}"

    fun isYes(text: String): Boolean = YES.containsMatchIn(text) && !NO.containsMatchIn(text)
    fun isNo(text: String): Boolean = NO.containsMatchIn(text)
    fun isThanks(text: String): Boolean = THANKS.containsMatchIn(text)

    /**
     * Il nome detto in risposta a "come ti chiami?": "mi chiamo Vincenzo", "sono Sam",
     * "Maria Grazia", "il mio nome è Luca". Al massimo due parole, con le iniziali maiuscole.
     * null se nella frase non c'è un nome plausibile.
     */
    fun extractName(text: String): String? {
        var t = text.replace("[unk]", " ").trim().lowercase()
        t = rx("^(?:(?:ciao|salve|allora|ehm|eh|beh|ok|okay|sì|si|no)[,!. ]+)+").replace(t, "")
        t = rx("^(?:(?:io )?mi chiamo|il mio nome (?:è|e)|(?:io )?sono|chiamami|puoi chiamarmi|mi puoi chiamare|mi chiamano)\\s+").replace(t, "")
        val words = t.replace(Regex("[^\\p{L}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val name = ArrayList<String>()
        for (w in words) {
            if (w in NOT_A_NAME || w.length < 2) { if (name.isEmpty()) continue else break }
            name.add(w)
            if (name.size == 2) break
        }
        if (name.isEmpty()) return null
        // "Sam" detto da solo il riconoscitore lo sente spesso come "san" o "sem"
        if (name.size == 1 && (name[0] == "san" || name[0] == "sem")) return "Sam"
        return name.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

/**
 * Le ultime battute della conversazione, anche quelle dei comandi che SAM esegue da solo ("Vuoi che lo cerchi
 * su internet?", "Torcia accesa"): il cervello IA non le vede, e senza di loro "sì", "cercalo", "e domani?"
 * restano senza filo. Si ridanno al cervello quando non le conosce (anche dopo che il telefono ha chiuso SAM:
 * si salvano su file). Kotlin puro: si collauda senza telefono.
 */
class Talk(private val max: Int = 300, private val keepMs: Long = KEEP_MS) {
    class Line(val you: String, val at: Long) {
        var sam = ""
        /** domanda e risposta sono nella conversazione del cervello IA */
        var chat = false
    }

    private val lines = ArrayList<Line>()

    /** Chiamata a ogni cambiamento: il servizio vocale salva la conversazione su file. */
    var onChange: (() -> Unit)? = null

    /** Una frase tua appena sentita: comincia una battuta nuova. */
    fun start(you: String, now: Long = System.currentTimeMillis()): Line {
        val l = Line(you.trim(), now)
        lines.add(l)
        while (lines.size > max) lines.removeAt(0)
        onChange?.invoke()
        return l
    }

    /** Aggiunge alla battuta [l] quello che SAM ha risposto (anche in più pezzi). */
    fun reply(l: Line?, text: String) {
        if (l == null || text.isBlank()) return
        l.sam = if (l.sam.isEmpty()) text.trim() else l.sam + " " + text.trim()
        onChange?.invoke()
    }

    /** Le battute finite prima di [current], non più vecchie di [within] (di solito 3 ore). */
    fun before(current: Line?, now: Long = System.currentTimeMillis(), within: Long = keepMs): List<Line> =
        lines.filter { it !== current && it.sam.isNotEmpty() && now - it.at <= within }

    /** Le ultime [n] battute che il cervello non ha visto: quelle dopo la sua ultima risposta. */
    fun unseen(current: Line?, n: Int = 3, now: Long = System.currentTimeMillis()): List<Line> =
        before(current, now).takeLastWhile { !it.chat }.takeLast(n)

    /** L'ultima cosa detta da SAM, prima di [current] (per "ripeti"). null = ancora niente. */
    fun lastSaid(current: Line? = null): String? = lines.lastOrNull { it !== current && it.sam.isNotEmpty() }?.sam

    fun clear() { lines.clear(); onChange?.invoke() }

    /**
     * Toglie dal filo del discorso le battute in cui SAM ha risposto [answer] (il cervello che si ripete a vuoto):
     * il cervello non le rivedrà nelle ultime battute, e non ci ricade.
     */
    fun forgetAnswer(answer: String) {
        var changed = false
        for (l in lines) if (l.sam.isNotEmpty() && Reply.sameAnswer(l.sam, answer)) { l.sam = ""; changed = true }
        if (changed) onChange?.invoke()
    }

    /** Per il file: una battuta per riga (quando, tu, SAM), separate da tab. */
    fun toText(): String = lines.filter { it.sam.isNotEmpty() }.joinToString("") { "${it.at}\t${flat(it.you)}\t${flat(it.sam)}\n" }

    /**
     * Rimette le battute salvate su file (quelle di più di un giorno fa non servono più). Il cervello IA è
     * ripartito da capo, quindi non ne ha vista nessuna: le riceverà davanti alla prima domanda.
     */
    fun load(text: String, now: Long = System.currentTimeMillis()) {
        lines.clear()
        for (row in text.lines()) {
            val p = row.split('\t', limit = 3)
            if (p.size < 3 || p[1].isBlank() || p[2].isBlank()) continue
            val at = p[0].toLongOrNull() ?: continue
            if (now - at > RECALL_MS || at > now + 60_000L) continue
            lines.add(Line(p[1], at).also { it.sam = p[2] })
        }
        while (lines.size > max) lines.removeAt(0)
    }

    companion object {
        /** per quanto le battute di prima si ridanno al cervello davanti a una domanda nuova */
        const val KEEP_MS = 3 * 3_600_000L
        /** per quanto si possono richiamare ("di cosa abbiamo parlato stamattina?", "cosa ti ho detto martedì?"): una settimana */
        const val RECALL_MS = 7 * 24 * 3_600_000L

        private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

        private fun cut(s: String, max: Int) = flat(s).let { if (it.length > max) it.take(max - 1).trimEnd() + "…" else it }

        /** Quanto tempo fa, a parole: null = poco fa (meno di 20 minuti). */
        fun ago(ms: Long): String? {
            val min = ms / 60_000L
            return when {
                min < 20 -> null
                min < 55 -> "circa ${((min + 2) / 5) * 5} minuti fa"
                min < 100 -> "circa un'ora fa"
                min < 22 * 60 -> "circa ${(min + 30) / 60} ore fa"
                else -> "ieri"
            }
        }

        /**
         * Le battute per il cervello: "(titolo Sam: «…» Tu: «…» …)". "Tu" è il cervello. null = niente da dire.
         * Se l'ultima battuta è di un po' fa, il titolo lo dice ("di circa 2 ore fa").
         */
        fun recap(lines: List<Line>, owner: String?, title: String, now: Long = System.currentTimeMillis(), cutAt: Int = 240): String? {
            if (lines.isEmpty()) return null
            val who = owner?.takeIf { it.isNotBlank() } ?: "L'utente"
            val age = ago(now - lines.last().at)
            val head = if (age != null) title.removeSuffix(":") + " (l'ultima $age):" else title
            return "($head " + lines.joinToString(" ") { "$who: «${cut(it.you, cutAt)}» Tu: «${cut(it.sam, cutAt)}»" } + ")"
        }

        /**
         * Le ultime battute dette da SAM stesso, quando il cervello non c'è o non sa rispondere:
         * "Ecco le ultime cose che ci siamo detti. Tu: «…». Io: «…»." null = nessuna battuta.
         */
        fun retell(lines: List<Line>, n: Int = 3, now: Long = System.currentTimeMillis()): String? {
            val last = lines.filter { it.sam.isNotEmpty() }.takeLast(n)
            if (last.isEmpty()) return null
            val age = ago(now - last.last().at)
            val sb = StringBuilder(if (age != null) "L'ultima volta che abbiamo parlato, $age, ci siamo detti questo." else "Ecco le ultime cose che ci siamo detti.")
            for (l in last) sb.append(" Tu: «").append(cut(l.you, 140)).append("». Io: «").append(cut(l.sam, 180)).append("».")
            return sb.toString()
        }

        /**
         * Le battute per rispondere a "cosa ti ho detto…?": le ultime [last] più quelle (anche vecchie) che c'entrano con
         * [question], in ordine di tempo, ognuna con quando ("oggi alle 10:12", "ieri alle 18:40", "martedì alle 9:05").
         */
        fun recallBlock(lines: List<Line>, question: String, owner: String?, last: Int = 8, relevant: Int = 10,
                        now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
            val said = lines.filter { it.sam.isNotEmpty() }
            if (said.isEmpty()) return null
            val q = Remember.words(question).map { it.lowercase() }.filter { it.length > 2 }.toSet()
            val picked = LinkedHashSet<Line>()
            if (q.isNotEmpty()) said.map { l -> l to Remember.words(l.you + " " + l.sam).map { it.lowercase() }.count { it in q } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(relevant).forEach { picked.add(it.first) }
            said.takeLast(last).forEach { picked.add(it) }
            val who = owner?.takeIf { it.isNotBlank() } ?: "L'utente"
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
            return picked.sortedBy { it.at }.joinToString("\n") { l ->
                val t = java.time.Instant.ofEpochMilli(l.at).atZone(zone)
                val d = t.toLocalDate()
                val day = when (java.time.temporal.ChronoUnit.DAYS.between(d, today)) { 0L -> "oggi"; 1L -> "ieri"; else -> days[d.dayOfWeek.value - 1] + " ${d.dayOfMonth}" }
                "- $day alle ${"%d:%02d".format(t.hour, t.minute)} — $who: «${cut(l.you, 300)}» — tu: «${cut(l.sam, 300)}»"
            }
        }

        const val FRESH_TITLE = "Per seguire il discorso, ecco le ultime battute tra voi:"
        const val UNSEEN_TITLE = "Intanto, prima di questa domanda, vi siete detti:"
        const val RECALL_TITLE = "Ecco quello che vi siete detti finora, dalla battuta più vecchia alla più recente:"
    }
}

/** Le righe sullo schermo (chi parla, cosa dice): si salvano su file e si ridisegnano quando SAM riparte. */
object Transcript {
    private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    fun toText(lines: List<Pair<String, String>>): String = lines.joinToString("") { "${flat(it.first)}\t${flat(it.second)}\n" }
    fun parse(text: String): List<Pair<String, String>> = text.lines().mapNotNull { r ->
        val p = r.split('\t', limit = 2)
        if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0] to p[1] else null
    }
}
