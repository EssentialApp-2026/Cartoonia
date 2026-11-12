package com.essentialapp.sam

/**
 * Ripulisce le risposte del cervello IA prima di mostrarle e leggerle ad alta voce:
 *  - niente ragionamenti nascosti (<think>…</think>) né marcatori di turno (<|im_end|>, <turn|>…);
 *  - niente presentazioni non richieste ("Sono SAM…", "…il telefono di Sam");
 *  - niente saluti a vuoto in testa alle risposte ("Ciao Sam!" quando non l'hai salutato);
 *  - niente frasi ripetute.
 * Divide la risposta in frasi, così SAM può cominciare a parlare appena la prima è pronta,
 * e decide quando conviene fermare una risposta che sta andando fuori strada.
 * Kotlin puro: si collauda senza telefono.
 */
object Reply {

    /** Oltre questa lunghezza una risposta parlata stanca: ci si ferma alla fine della frase. */
    const val MAX_CHARS = 520

    private val TURN = Regex(
        "<\\|im_end\\|>|<\\|im_start\\|>|<\\|endoftext\\|>|<end_of_turn>|<start_of_turn>|<\\|eot_id\\|>|" +
        "<\\|end\\|>|<eos>|<turn\\|>|<\\|turn>|<\\|channel>|<channel\\|>|<\\|think\\|>"
    )
    private val LEAD_LABEL = Lang.rx("^\\s*(?:sam|sem|assistente|assistant|model|risposta|ai)\\s*:\\s*")
    private val ROLE_LINE = Lang.rx("\\n\\s*(?:user|utente|tu|io|domanda|sam|sem|assistente|assistant|model)\\s*:")
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val MARKDOWN = Regex("[*#_`>|~\\[\\]]")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val ENDS_SENTENCE = Regex("[.!?…][\"'»)]?\\s+$")

    // domande in cui parlare di sé è giusto
    private val ASKS_IDENTITY = Lang.rx("\\b(come ti chiami|chi sei|tuo nome|ti chiami|chi ti ha|di chi è|come mi chiamo|presentati)\\b")
    private val GREETING = Lang.rx("^\\W*(ciao|salve|ehi|hey|buongiorno|buonasera|buonanotte|ben ?tornato|eccomi)\\b")
    // "…il telefono di Sam": frase da togliere tutta
    private val PHONE_OF = Lang.rx("\\b(?:telefono|cellulare|smartphone)\\s+di\\s+(?:sam|sem|samuele)\\b")
    // "sono SAM", "io sono Sam, il tuo assistente…", "mi chiamo Sem": inciso da togliere
    private val NAME_INTRO = Lang.rx(
        "(?:\\bio\\s+)?\\b(?:sono|mi chiamo|qui è|qui c'è)\\s+(?:sam|sem)\\b" +
        "(?:\\s*,?\\s*(?:il tuo|l'|un|una|la tua)\\s*assistente[^,.!?]*)?\\s*[,;:]?\\s*"
    )
    // una frase che è SOLO "sono il tuo assistente (vocale)…"
    private val ONLY_ASSISTANT = Lang.rx(
        "^\\W*(?:ciao\\W*)?(?:io\\s+)?sono\\s+(?:il tuo|la tua|un|una|l')\\s*assistente(?:\\s+[\\p{L}']+){0,4}\\W*$"
    )
    // "Sono un modello linguistico, qui per aiutarti…": presentazione non richiesta (si tiene quello che viene dopo un "ma")
    private val MODEL_INTRO = Lang.rx(
        "^\\W*(?:ciao\\W*)?(?:io\\s+)?sono\\s+(?:solo\\s+|soltanto\\s+|semplicemente\\s+)?(?:un|una|un')\\s*" +
        "(?:modello|programma|intelligenza artificiale|ia|sistema|assistente virtuale|chatbot|bot)\\b"
    )
    private val BUT = Lang.rx("\\b(?:ma|però|comunque)\\b[\\s,]*")
    // presentazione vuota ("…qui per aiutarti con le tue domande"); "…quindi non provo emozioni" invece è una risposta vera
    private val EMPTY_INTRO = Lang.rx(
        "\\b(?:per aiutarti|ad aiutarti|al tuo servizio|a tua disposizione|qui per te|pront[oa] (?:ad?|per) aiutarti|creat[oa] (?:da|per)|" +
        "addestrat[oa]|progettat[oa] per|sviluppat[oa] da|con le tue domande)\\b"
    )
    // una frase che è SOLO un saluto: "Ciao!", "Ciao Sam!", "Eccomi!"
    private val ONLY_GREETING = Lang.rx(
        "^\\W*(?:ciao|salve|ehi|hey|eccomi|buongiorno|buonasera)(?:\\s*,?\\s*[\\p{L}']+)?\\W*$"
    )

    /** Testo pulito, pronto per lo schermo e per la voce. Può essere vuoto. */
    fun tidy(raw: String, question: String = "", owner: String? = null, scrub: Boolean = true, chat: Boolean = true): String =
        sentences(raw, question, owner, final = true, scrub0 = scrub, chat = chat).joinToString(" ")

    /**
     * Le frasi pulite della risposta. Con [final] = false (risposta ancora in arrivo)
     * l'ultima frase viene tenuta fuori finché non è finita. [chat] = chiacchierata normale, senza foto:
     * lì "non posso vedere" è falso (quando guarda una foto, "non riesco a vedere bene" invece è vero).
     */
    fun sentences(raw: String, question: String = "", owner: String? = null, final: Boolean = true, scrub0: Boolean = true,
                  chat: Boolean = true): List<String> {
        var t = cut(raw)
        t = EMOJI.replace(t, "")
        t = MARKDOWN.replace(t, "")
        val complete = final || ENDS_SENTENCE.containsMatchIn(t)
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return emptyList()

        val pieces = t.split(SENTENCE_END).toMutableList()
        if (!complete) pieces.removeAt(pieces.size - 1)   // frase ancora a metà

        val scrub = scrub0 && !ASKS_IDENTITY.containsMatchIn(question)
        val greeted = GREETING.containsMatchIn(question)
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for ((i, p) in pieces.withIndex()) {
            var s = p.trim()
            if (s.isEmpty()) continue
            if (scrub) {
                if (PHONE_OF.containsMatchIn(s)) continue
                if (ONLY_ASSISTANT.matches(s)) continue
                if (MODEL_INTRO.containsMatchIn(s)) {
                    val after = BUT.find(s)?.let { s.substring(it.range.last + 1).trim() }
                    when {
                        after != null && after.length >= 8 -> s = after.replaceFirstChar { it.uppercase() }
                        EMPTY_INTRO.containsMatchIn(s) || s.split(' ').size <= 6 -> continue
                        else -> {}   // "Sono un assistente virtuale, quindi non provo emozioni": resta
                    }
                }
                if (NAME_INTRO.containsMatchIn(s)) {
                    s = NAME_INTRO.replace(s, "")
                    s = s.replace(Regex("^[\\s,;:.!?-]+"), "").replace(Regex("\\s+([,.!?])"), "$1").trim()
                    if (s.replace(Regex("[\\W_]+"), "").isEmpty()) continue
                    s = s.replaceFirstChar { it.uppercase() }
                }
            }
            // "Ciao Sam!" in testa a una risposta quando non l'hai salutato: si salta
            if (scrub0 && i == 0 && !greeted && question.isNotBlank() && ONLY_GREETING.matches(s)) continue
            // "non posso memorizzare": è falso, SAM ha una memoria (il servizio vocale dice invece come si usa)
            if (scrub0 && DENIES_MEMORY.containsMatchIn(s)) continue
            // "non posso vedere": falso anche questo, SAM guarda con la fotocamera
            if (scrub0 && chat && DENIES_SIGHT.containsMatchIn(s)) continue
            // "non posso cercare su internet": SAM la ricerca la apre nel browser (e subito dopo la propone)
            if (scrub0 && chat && DENIES_SEARCH.containsMatchIn(s)) continue
            // "non posso leggere i file", "copialo e incollalo qui": SAM i file li legge (e spiega come)
            if (scrub0 && chat && DENIES_FILES.containsMatchIn(s)) continue
            // "Regola: rispondi in breve": la regola che il cervello propone per sé (in fondo alla risposta) non si legge,
            // la chiede SAM a parte ("Vuoi che me lo segni come regola?")
            if (scrub0 && chat && RULE_LINE.containsMatchIn(s)) break
            val key = norm(s)
            if (key.isNotEmpty() && !seen.add(key)) continue   // frase già detta
            out.add(s)
        }
        return out
    }

    /**
     * true = conviene fermare subito la generazione: il modello ha iniziato un altro turno,
     * ripete una frase già detta, oppure ha già detto abbastanza per una risposta a voce.
     */
    fun shouldStop(raw: String, maxChars: Int = MAX_CHARS): Boolean {
        if (derailed(raw)) return true
        val trimmed = LEAD_LABEL.replace(raw.substringAfterLast("</think>"), "").trimEnd()
        return trimmed.length > maxChars && trimmed.isNotEmpty() && trimmed.last() in ".!?…"
    }

    /** Testo da mostrare così com'è (il ragionamento): senza simboli di formattazione, segni del modello e righe vuote. */
    fun plain(raw: String): String =
        MARKDOWN.replace(TURN.replace(raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace("<think>", "").replace("</think>", ""), " "), "")
            .lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()

    /**
     * true se la risposta è andata fuori strada (scrive anche la battuta dell'utente, ripete le frasi): dopo averla
     * fermata, il cervello riparte da una conversazione pulita. Se invece era solo lunga, il discorso continua.
     */
    fun derailed(raw: String): Boolean {
        if (TURN.containsMatchIn(raw)) return true
        val body = LEAD_LABEL.replace(raw.substringAfterLast("</think>"), "")
        if (ROLE_LINE.containsMatchIn(body)) return true
        val sentences = body.split(SENTENCE_END).map { norm(it) }.filter { it.length >= 6 }
        return sentences.size != sentences.toSet().size
    }

    private val CANT_KNOW = Lang.rx(
        "\\b(non posso saperlo|non posso sapere|non posso saperl[oa]|non ho accesso|non sono collegat[oa]|senza internet|" +
        "non ho (informazioni|dati|notizie) aggiornat|non conosco (le notizie|i risultati|il meteo|gli ultimi)|" +
        "non ho modo di saperlo|non posso controllare|non posso verificare|non posso cercare|non ho la possibilità di)"
    )

    /** true se la risposta dice che il cervello non può saperlo (notizie, risultati, prezzi…): SAM propone di cercare. */
    fun cantKnow(answer: String): Boolean = CANT_KNOW.containsMatchIn(answer)

    // "non posso memorizzare", "non ho una memoria", "ogni conversazione riparte da zero": i modelli sono addestrati
    // a dirlo, ma SAM la memoria ce l'ha (la tiene l'app sul telefono)
    private val DENIES_MEMORY = Lang.rx(
        "\\bnon (?:posso|potrò|riesco a|riuscirò a|sono in grado di|ho la capacità di|ho la possibilità di|ho modo di|sono capace di|" +
        "sono progettat[oa] per|mi è possibile) (?:\\S+ )?(?:memorizzare|memorizzarl[oaie]|ricordare|ricordarl[oaie]|ricordarmi|" +
        "ricordarmel[oaie]|tenere a mente|tenerl[oaie] a mente)\\b|" +
        // "non posso salvare informazioni personali" (ma "non posso salvare file sul telefono" resta)
        "\\bnon (?:posso|riesco a|sono in grado di|ho la capacità di|ho la possibilità di|ho modo di) (?:salvare|conservare|archiviare|" +
        "registrare|mantenere|trattenere) (?:\\S+ ){0,2}?(?:informazion|dati|ricord|conversazion|cose che|quello che|ciò che|niente|nulla)|" +
        "\\bnon (?:ho|possiedo|dispongo di) (?:una |alcuna |la |nessuna )?memoria\\b|" +
        "\\bnon (?:memorizzo|conservo|salvo|archivio|mantengo|trattengo|registro|ricordo) (?:\\S+ ){0,3}?" +
        "(?:informazion|dati|conversazion|chat|ricordi|cose che|nulla tra|niente tra)|" +
        "\\b(?:ogni|ciascuna) (?:nuova )?(?:conversazione|sessione|chat)\\b[^.!?]{0,40}\\b(?:da zero|da capo|nuova|indipendente)|" +
        "\\bnon\\b[^.!?]{0,30}\\bmemoria (?:a lungo termine|permanente|persistente)"
    )
    // "me lo ricorderò", "l'ho memorizzato", "lo terrò a mente" (non "non me lo ricordo")
    private val PROMISE = Lang.rx(
        "(?<!non )(?<!non me )\\b(?:me lo|me la|me li|me le|lo|la|me ne) (?:ricorderò|terrò a mente|terrò presente|memorizzo|" +
        "memorizzerò|segno|annoto|salvo|ricordo|tengo a mente|tengo presente)\\b|" +
        "\\b(?:l'ho|ho) (?:memorizzat[oa]|segnat[oa]|salvat[oa]|annotat[oa]|preso nota)\\b|" +
        "\\bme l'?(?:ho|sono) (?:segnat[oa]|annotat[oa])\\b|\\bme lo sono (?:segnat[oa]|annotat[oa])\\b|" +
        "\\bnon (?:me )?(?:lo|la|ne) dimenticherò\\b|\\bterrò (?:a mente|presente)\\b|\\bne terrò conto\\b"
    )

    // "non posso vedere", "non ho accesso alla fotocamera", "non vedo le immagini"
    private val DENIES_SIGHT = Lang.rx(
        "\\bnon (?:posso|potrò|riesco a|sono in grado di|ho la capacità di|ho la possibilità di|ho modo di|sono capace di|mi è possibile) " +
        "(?:\\S+ )?(?:vedere|vederti|vederl[oaie]|guardare|guardarti|guardarl[oaie]|osservare|" +
        "accedere alla (?:tua )?(?:fotocamera|camera|videocamera|telecamera))\\b|" +
        "\\bnon (?:ho|dispongo di|possiedo) (?:accesso alla (?:tua )?(?:fotocamera|camera|videocamera|telecamera)|(?:gli |degli )?occhi|" +
        "la (?:capacità di )?vista)\\b|" +
        "\\bnon (?:vedo|posso vedere|riesco a vedere) (?:le |delle )?(?:immagini|foto|fotografie)\\b|" +
        "\\b(?:sono|essendo) (?:solo |soltanto )?un(?:a)? (?:modello|programma|assistente|intelligenza artificiale)\\b[^.!?]{0,50}\\b(?:non (?:posso|riesco a) vedere|senza (?:occhi|vista))"
    )

    // "non ho la possibilità di cercare su internet"
    private val DENIES_SEARCH = Lang.rx(
        "\\bnon (?:posso|potrò|riesco a|sono in grado di|ho la capacità di|ho la possibilità di|ho modo di|mi è possibile) " +
        "(?:\\S+ )?(?:cercare|navigare|fare (?:una |delle )?ricerch[ae]|effettuare (?:una |delle )?ricerch[ae]|accedere a internet|" +
        "collegarmi a internet|andare su internet|consultare internet|usare internet)\\b"
    )

    // "non ho la capacità di leggere file", "non posso aprire documenti", "copialo e incollalo qui": SAM i file li legge
    // (condivisi con "Leggi con SAM") e, se c'è, ha l'enciclopedia sul telefono
    private val DENIES_FILES = Lang.rx(
        "\\bnon (?:posso|potrò|riesco a|sono in grado di|ho la capacità di|ho la possibilità di|ho modo di|sono capace di|mi è possibile) " +
        "(?:\\S+ ){0,2}?(?:leggere|aprire|accedere a|consultare|caricare|elaborare|analizzare|ricevere) (?:\\S+ ){0,3}?" +
        "(?:file|documenti|documento|pdf|allegat|archivi|pacchett|enciclopedi|wikipedia|testi che|contenuti che)|" +
        "\\bnon (?:ho|dispongo di|possiedo) (?:l')?accesso (?:\\S+ ){0,3}?(?:file|documenti|enciclopedi|wikipedia|tuo telefono|tuoi file)|" +
        "\\b(?:copia(?:re|rlo|rla|rli|rle|lo|la|li|le)?|incolla(?:re|rlo|rla|rli|rle|lo|la|li|le)?)\\b[^.!?]{0,40}" +
        "\\b(?:incoll|qui|nella chat|in chat|messaggio)"
    )

    // la riga con cui il cervello propone una regola per sé: "Regola: rispondi in modo breve"
    private val RULE_LINE = Lang.rx("^\\W*(?:nuova\\s+)?regola\\s*:")
    private val RULE_AT = Lang.rx("(?:^|[.!?…]\\s*)\\W*(?:nuova\\s+)?regola\\s*:\\s*(.+)$")

    /**
     * La regola che il cervello propone per sé in fondo alla risposta ("Regola: rispondi in modo breve"), ripulita;
     * null = nessuna. SAM chiede se tenerla: così il cervello cambia da solo il proprio comportamento, con il tuo sì.
     */
    fun selfRule(raw: String): String? {
        val t = MARKDOWN.replace(cut(raw), "").replace(Regex("\\s+"), " ").trim()
        val m = RULE_AT.find(t) ?: return null
        return Llm.parseSelfRule(m.groupValues[1])
    }

    // una frase che chiede a SAM di cambiare modo di fare ("potresti essere meno pesante?", "smettila di…", "parlami in…")
    private val ASKS_CHANGE = Lang.rx(
        "\\b(?:regola|regole|d'ora|da ora|da adesso|da oggi|in poi|sempre|mai più|smetti|smettila|evita|cerca di|vorrei che|voglio che|" +
        "mi piacerebbe che|potresti|puoi|dovresti|devi|cambia|cambiare|modo|tono|carattere|sii|diventa|parlami|rispondimi|chiamami|dammi del|" +
        "troppo|troppe|non mi piace|mi dà fastidio|mi da fastidio)\\b"
    )

    /** true se la frase parla del modo di fare di SAM: solo allora vale una regola proposta dal cervello ([selfRule]). */
    fun asksChange(question: String): Boolean = ASKS_CHANGE.containsMatchIn(question)

    /** true se la risposta dice di non poter leggere file o l'enciclopedia: falso, SAM spiega invece come si fa. */
    fun deniesFiles(answer: String): Boolean = DENIES_FILES.containsMatchIn(cut(answer))

    /** true se la risposta dice di non poter cercare su internet: SAM propone invece di aprire la ricerca. */
    fun deniesSearch(answer: String): Boolean = DENIES_SEARCH.containsMatchIn(cut(answer))

    /** true se la risposta dice di non poter vedere: quelle frasi non si dicono, SAM spiega invece come farlo guardare. */
    fun deniesSight(answer: String): Boolean = DENIES_SIGHT.containsMatchIn(cut(answer))

    /** true se la risposta nega di avere una memoria: quelle frasi non si dicono, SAM spiega invece come ricordare. */
    fun deniesMemory(answer: String): Boolean = DENIES_MEMORY.containsMatchIn(cut(answer))

    /** true se la risposta promette di ricordare ("me lo ricorderò"): SAM salva davvero la cosa che hai detto. */
    fun promisesMemory(answer: String): Boolean = PROMISE.containsMatchIn(answer)

    /** Toglie ragionamento nascosto, turni successivi ed etichette di ruolo. */
    private fun cut(raw: String): String {
        var t = raw
        if (t.contains("</think>")) t = t.substringAfterLast("</think>")
        if (t.contains("<think>")) t = t.substringBefore("<think>")
        TURN.find(t)?.let { t = t.substring(0, it.range.first) }
        t = LEAD_LABEL.replace(t, "")
        ROLE_LINE.find(t)?.let { t = t.substring(0, it.range.first) }
        // 1.20: le azioni chieste dal cervello («AZIONE: …») non si dicono e non si mostrano: le fa SAM
        t = Agent.strip(t)
        return t
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /**
     * true se [a] e [b] sono la stessa frase (a parte maiuscole e punteggiatura) o quasi: da quattro parole in su,
     * se quasi tutte le parole coincidono. Serve a capire quando il cervello ridà la stessa risposta a domande diverse.
     */
    fun sameAnswer(a: String, b: String?): Boolean {
        if (b == null) return false
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val wa = words(a); val wb = words(b)
        if (wa.size < 4 || wb.size < 4) return false
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size >= 0.8
    }
}
