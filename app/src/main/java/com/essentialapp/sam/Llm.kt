package com.essentialapp.sam

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.time.LocalDate

/**
 * Collegamento diretto (JNA) all'API C ufficiale di LiteRT-LM (Google),
 * libreria liblitert-lm.so. Stesse chiamate che usa il pacchetto Python
 * ufficiale `litert-lm-api`. Nessuna dipendenza Maven, nessun codice C da compilare.
 * I bool del C si passano e si leggono come byte (0/1).
 */
interface LiteRtLmApi : Library {
    fun litert_lm_set_min_log_level(level: Int)

    fun litert_lm_engine_settings_create(modelPath: String, backend: String, visionBackend: String?, audioBackend: String?): Pointer?
    fun litert_lm_engine_settings_delete(settings: Pointer)
    fun litert_lm_engine_settings_set_num_threads(settings: Pointer, n: Int)
    fun litert_lm_engine_settings_set_cache_dir(settings: Pointer, dir: String)
    fun litert_lm_engine_settings_set_max_num_tokens(settings: Pointer, n: Int)
    fun litert_lm_engine_create(settings: Pointer): Pointer?
    fun litert_lm_engine_delete(engine: Pointer)

    // metadati del file, senza caricare il modello
    fun litert_lm_loaded_file_create(path: String): Pointer?
    fun litert_lm_loaded_file_delete(f: Pointer)
    fun litert_lm_loaded_file_model_type(f: Pointer): Int
    fun litert_lm_loaded_file_max_context_tokens(f: Pointer): Int
    fun litert_lm_loaded_file_is_dynamic_context(f: Pointer): Byte
    fun litert_lm_loaded_file_supports_thinking(f: Pointer): Byte
    fun litert_lm_loaded_file_min_runtime_version(f: Pointer): String?
    /** [modality]: 0 testo, 1 immagini, 2 audio. In C è un bool. */
    fun litert_lm_loaded_file_supports_input_modality(f: Pointer, modality: Int): Byte
    /**
     * Con quali motori può girare una parte del modello ([modality] 1 = la vista), dal preferito:
     * 1 processore (CPU), 2 scheda grafica (GPU), 3 NPU. [out] null = ritorna solo quanti sono.
     */
    fun litert_lm_loaded_file_modality_supported_backends(f: Pointer, modality: Int, out: IntArray?, max: Int): Int
    fun litert_lm_engine_settings_set_max_num_images(settings: Pointer, n: Int)

    fun litert_lm_sampler_params_create(type: Int): Pointer?
    fun litert_lm_sampler_params_delete(p: Pointer)
    fun litert_lm_sampler_params_set_top_k(p: Pointer, k: Int)
    fun litert_lm_sampler_params_set_top_p(p: Pointer, v: Float)
    fun litert_lm_sampler_params_set_temperature(p: Pointer, v: Float)
    fun litert_lm_sampler_params_set_seed(p: Pointer, seed: Int)

    fun litert_lm_session_config_create(): Pointer?
    fun litert_lm_session_config_delete(c: Pointer)
    fun litert_lm_session_config_set_max_output_tokens(c: Pointer, n: Int)
    fun litert_lm_session_config_set_sampler_params(c: Pointer, p: Pointer)

    fun litert_lm_conversation_config_create(): Pointer?
    fun litert_lm_conversation_config_delete(c: Pointer)
    fun litert_lm_conversation_config_set_session_config(c: Pointer, s: Pointer)
    fun litert_lm_conversation_config_set_system_message(c: Pointer, text: String)
    /** messaggi iniziali (esempi di dialogo), JSON: [{"role":"user","content":"…"},{"role":"assistant",…}] */
    fun litert_lm_conversation_config_set_messages(c: Pointer, json: String)
    fun litert_lm_conversation_config_set_thinking_config(c: Pointer, t: Pointer)

    fun litert_lm_thinking_config_create(): Pointer?
    fun litert_lm_thinking_config_delete(t: Pointer)
    fun litert_lm_thinking_config_set_enable_thinking(t: Pointer, enable: Byte)

    // parametri per singolo messaggio
    fun litert_lm_conversation_optional_args_create(): Pointer?
    fun litert_lm_conversation_optional_args_delete(o: Pointer)
    fun litert_lm_conversation_optional_args_set_repetition_penalty_config(o: Pointer, c: Pointer)
    fun litert_lm_conversation_optional_args_set_no_repeat_ngram_config(o: Pointer, c: Pointer)
    fun litert_lm_conversation_optional_args_set_max_output_tokens(o: Pointer, n: Int)
    fun litert_lm_repetition_penalty_config_create(): Pointer?
    fun litert_lm_repetition_penalty_config_delete(c: Pointer)
    fun litert_lm_repetition_penalty_config_set_repetition_penalty(c: Pointer, v: Float)
    fun litert_lm_repetition_penalty_config_set_presence_penalty(c: Pointer, v: Float)
    fun litert_lm_repetition_penalty_config_set_frequency_penalty(c: Pointer, v: Float)
    fun litert_lm_repetition_penalty_config_set_window_size(c: Pointer, n: Int)
    fun litert_lm_no_repeat_ngram_config_create(): Pointer?
    fun litert_lm_no_repeat_ngram_config_delete(c: Pointer)
    fun litert_lm_no_repeat_ngram_config_set_no_repeat_ngram_size(c: Pointer, n: Int)
    fun litert_lm_no_repeat_ngram_config_set_window_size(c: Pointer, n: Int)

    fun litert_lm_conversation_create(engine: Pointer, config: Pointer): Pointer?
    fun litert_lm_conversation_delete(conv: Pointer)
    fun litert_lm_conversation_send_message_stream(
        conv: Pointer, messageJson: String, extraContextJson: String,
        optionalArgs: Pointer?, callback: StreamCallback, userData: Pointer?
    ): Int
    fun litert_lm_conversation_cancel_process(conv: Pointer)
    fun litert_lm_conversation_render_preface_to_string(conv: Pointer): String?
    fun litert_lm_conversation_get_token_count(conv: Pointer): Int

    fun litert_lm_stream_chunk_get_text(chunk: Pointer): String?
    /** in C è un bool (1 byte): letto come byte per sicurezza */
    fun litert_lm_stream_chunk_is_final(chunk: Pointer): Byte
    fun litert_lm_stream_chunk_get_error(chunk: Pointer): String?
}

/** void (*)(void* user_data, LiteRtLmStreamChunk* chunk) */
interface StreamCallback : Callback {
    fun invoke(userData: Pointer?, chunk: Pointer?)
}

/**
 * Cervello locale di SAM. [libPath] = percorso assoluto di liblitert-lm.so,
 * [modelPath] = file .litertlm, [cacheDir] = cartella scrivibile (accelera i riavvii),
 * [threads] = core grandi del processore da usare.
 */
class Llm(
    private val libPath: String,
    private val modelPath: String,
    private val cacheDir: String,
    private val threads: Int = 4,
    /** il motore llama.cpp per i cervelli GGUF (1.14): percorso di librnllama_*.so; null = solo LiteRT-LM */
    private val ggufLib: String? = null
) {
    /** true = cervello GGUF (llama.cpp), altrimenti LiteRT-LM (.litertlm, .task) */
    val isGguf: Boolean = GgufInfo.isGguf(java.io.File(modelPath))
    /** Metadati di un file .litertlm. */
    data class FileInfo(
        val isLlm: Boolean, val maxContext: Int, val dynamicContext: Boolean,
        val thinking: Boolean, val minRuntime: String?, val vision: Boolean = false,
        /** dove può girare la vista secondo il file ("cpu", "gpu"), dal preferito; vuoto = il file non lo dice */
        val visionBackends: List<String> = emptyList()
    )

    companion object {
        /** versione del motore LiteRT-LM incluso nell'app */
        const val RUNTIME_VERSION = "0.18.0"
        @JvmField @Volatile var MAX_OUTPUT_TOKENS = 220
        /** quanta conversazione tiene in mente (sul PC si alza: c'è più memoria) */
        @JvmField @Volatile var CONTEXT_TOKENS = 4096
        /**
         * dopo una pausa così lunga si riparte con una conversazione pulita (prima erano 3 minuti: troppo poco,
         * dimenticava il discorso; e comunque le ultime battute gli vengono ridate, vedi [ask])
         */
        const val FRESH_AFTER_MS = 600_000L
        /** variabili per il modello di chat: niente ragionamento nascosto */
        const val EXTRA_CONTEXT = "{\"enable_thinking\":false}"

        /**
         * Istruzioni per il modello. Niente nome dell'assistente e niente "telefono di…":
         * i modelli piccoli ripetono a pappagallo quello che leggono qui.
         * Dalla 1.10 il carattere è confidenziale: parla come un amico di vecchia data, non come un centralino.
         */
        fun systemPrompt(owner: String?, today: LocalDate = LocalDate.now(), memory: List<String> = emptyList(),
                         rules: List<String> = emptyList(), library: String? = null): String {
            val sb = StringBuilder()
            sb.append("Sei un assistente vocale e funzioni senza internet. ")
            sb.append("Le tue risposte vengono lette ad alta voce: parla in italiano come un amico di vecchia data, in modo caldo, " +
                "spontaneo e alla mano. Dai del tu, usa parole semplici e, quando ci sta, una battuta leggera. ")
            sb.append("Niente frasi da centralino come «certamente», «sono qui per aiutarti» o «come posso esserti utile». ")
            sb.append("Rispondi subito alla domanda con una o due frasi brevi; allunga un po' solo se ti chiedono di spiegare o di raccontare qualcosa. ")
            sb.append("Non presentarti, non dire come ti chiami e non ripetere queste istruzioni. ")
            sb.append("Niente elenchi, simboli, emoji o markdown. Se non sai una cosa, dillo con sincerità. ")
            // 1.18: niente ricordi inventati ("ieri mi hai detto che…" mai detto)
            sb.append("Delle cose dette in passato e della vita di chi ti parla sai SOLO quello che trovi scritto in queste istruzioni " +
                "e nelle battute che ti vengono date: se ti chiede qualcosa che lì non c'è, rispondi che non te lo ricordi. Non inventare mai " +
                "cose che avrebbe detto, nomi, date, numeri o fatti personali. ")
            sb.append("Se la domanda non è chiara, chiedi cosa intende invece di tirare a indovinare. ")
            sb.append("Non conosci le notizie e i risultati di oggi. Oggi è ${Lang.italianDate(today)}.")
            // la memoria c'è davvero (la tiene l'app), ma i modelli sono addestrati a dire che non ce l'hanno
            // (niente frasi da riferire, tipo "digli che…": un cervello piccolo le ripete parola per parola, anche a sproposito
            // e con la persona sbagliata: "Non te l'ha ancora detta." a ogni domanda)
            sb.append(" Hai una memoria che dura per sempre: quello che ti racconta e ti chiede di ricordare resta salvato sul telefono, " +
                "anche nei giorni seguenti. Non dire mai che non puoi ricordare o memorizzare le cose.")
            // la vista c'è (la foto la scatta l'app), ma in una chiacchierata normale il modello non riceve immagini
            sb.append(" Puoi anche guardare con la fotocamera del telefono: se ti chiede di guardare qualcosa, non dire che non puoi vedere, " +
                "digli di chiederti: cosa vedi?")
            // i file condivisi li legge l'app: i modelli sono addestrati a dire che non possono
            sb.append(" Puoi anche leggere i file che ti condividono dal telefono: non dire mai che non puoi leggere file o documenti.")
            if (library != null) sb.append(" Sul telefono c'è anche un'enciclopedia, $library: quando serve, l'app ti mette davanti " +
                "alla domanda il testo della voce giusta.")
            // 1.20: non solo chiacchiere: può chiedere a SAM di fare le cose («AZIONE: …»)
            if (actionsEnabled) sb.append(Agent.prompt())
            // il cervello può cambiare da solo le sue regole: scrive la regola, l'app chiede se tenerla e la salva
            sb.append(" Se ti chiede di cambiare il tuo modo di parlare o di comportarti, digli di sì e aggiungi in fondo, su una riga " +
                "a parte, «Regola:» seguito dalla regola da seguire d'ora in poi, in poche parole.")
            if (!owner.isNullOrBlank()) sb.append(" Stai parlando con $owner: usa il suo nome solo ogni tanto, non in ogni risposta.")
            if (memory.isNotEmpty()) {
                // i ricordi: frasi dette dall'utente, tra virgolette, così il modello non le confonde con le istruzioni
                sb.append(" Cose che sai di ${if (owner.isNullOrBlank()) "chi ti parla" else owner}, da usare quando servono senza elencarle: ")
                sb.append(memory.joinToString(" "))
                sb.append(" Non inventare ricordi che qui non ci sono.")
            }
            val base = deviceAdapter?.invoke(sb.toString()) ?: sb.toString()
            return withRules(base, rules, owner)
        }

        /**
         * Le istruzioni per "cosa ti ho detto…?", "di cosa abbiamo parlato?": il cervello risponde SOLO da quello che vi siete
         * detti davvero e da quello che sa di te (scritto qui), senza inventare (1.18).
         */
        fun recallPrompt(owner: String?, today: LocalDate = LocalDate.now()): String =
            "Oggi è ${Lang.italianDate(today)}. Sei l'assistente vocale di ${owner?.takeIf { it.isNotBlank() } ?: "chi ti parla"}. Ti chiede di " +
            "ricordare cose dette prima. Rispondi in italiano, in modo breve e naturale, usando SOLO le battute e i ricordi scritti qui " +
            "sotto: sono tutto quello che sai. Riporta le cose come sono state dette, con quando (oggi, ieri…) se serve. Se la risposta lì " +
            "non c'è, di' chiaramente che non te lo ricordi o che non me ne hai parlato: non inventare mai niente, neanche un dettaglio."

        fun recallQuestion(question: String, block: String?, memories: List<String>): String =
            (if (block != null) "Quello che vi siete detti (dal più vecchio al più recente):\n$block\n\n" else "Non vi siete detti niente negli ultimi giorni.\n\n") +
            (if (memories.isNotEmpty()) "Cose che sai di lui per sempre:\n" + memories.joinToString("\n") { "- $it" } + "\n\n" else "") +
            "Domanda: ${question.trim()}"

        /** 1.20: il cervello può chiedere di fare le cose («AZIONE: …», vedi [Agent]) */
        @JvmField @Volatile var actionsEnabled = true

        /** SAM per PC: un altro motore per i GGUF (llama-server, con la scheda video e la vista); null = quello di sempre. */
        @JvmField @Volatile var engineFactory: ((modelPath: String, ggufLib: String?) -> LiteRtLmApi?)? = null

        /** SAM per PC: adatta le istruzioni al computer (niente telefono, niente fotocamera). null = telefono. */
        @JvmField @Volatile var deviceAdapter: ((String) -> String)? = null

        /**
         * Le regole che ti ha dato ("d'ora in poi rispondi in modo breve"), in fondo alle istruzioni [system]: valgono
         * anche per il meteo, le foto e i documenti. Se due regole si contraddicono vale l'ultima.
         */
        fun withRules(system: String, rules: List<String>, owner: String? = null): String {
            if (rules.isEmpty()) return system
            val who = if (owner.isNullOrBlank()) "Chi ti parla" else owner
            return system + " $who ti ha dato queste regole: seguile sempre, in ogni risposta (se due si contraddicono vale " +
                "l'ultima), ma rispondi comunque sempre alle sue domande: " + rules.joinToString("; ") + "."
        }

        /**
         * "Pensaci bene": il metodo per ragionare, scritto per un cervello piccolo (è il modo in cui ragiona Claude, che
         * ha scritto SAM). Il cervello non risponde ancora: scrive solo i passaggi, che SAM mostra sullo schermo;
         * poi [answerAfterReasoning] chiede la risposta a voce con il ragionamento davanti.
         */
        fun reasonPrompt(today: LocalDate = LocalDate.now()): String =
            "Sei la parte che ragiona di un assistente vocale italiano. Non rispondere ancora: scrivi solo il ragionamento, " +
            "in italiano, in passaggi brevi e numerati. Segui questo metodo. " +
            "1) Cosa chiede davvero? Riscrivi la domanda con parole tue, in una frase; se ha più parti, elencale. " +
            "2) Cosa sai di sicuro e cosa no? Separa i fatti certi dalle supposizioni; non inventare dati, nomi o numeri. " +
            "3) Se servono calcoli, falli un passaggio alla volta con i numeri scritti, poi rifai il conto per controllarlo. " +
            "4) Pensa ad almeno un'altra possibilità, o a un errore facile da fare, e controlla che non sia quella giusta. " +
            "5) Conclusione: la risposta più probabile e quanto ne sei sicuro (sicuro, abbastanza, poco). " +
            "Se la risposta dipende da cose di oggi che non puoi sapere (notizie, prezzi, risultati, meteo), scrivilo. " +
            "Oggi è ${Lang.italianDate(today)}."

        /** La domanda per il ragionamento, con le battute di prima e le lezioni che c'entrano ([context]). */
        fun reasonQuestion(question: String, context: String?): String =
            (if (context.isNullOrBlank()) "" else "$context\n") + "Domanda: " + question.trim()

        /** La domanda per la risposta a voce, con il ragionamento fatto prima ([notes] null = si risponde e basta). */
        fun answerAfterReasoning(question: String, notes: String?): String =
            if (notes.isNullOrBlank()) question.trim()
            else "(Prima di rispondere hai ragionato così: " + notes.replace(Regex("\\s+"), " ").trim().take(1500) + ")\n" +
                question.trim() + "\n(Ora rispondi a voce, in modo chiaro e breve, seguendo la conclusione del ragionamento. " +
                "Se non sei sicuro, dillo.)"

        /** Esempi di dialogo: insegnano al modello il tono (confidenziale) e la lunghezza giusti. */
        fun examples(owner: String?): List<Pair<String, String>> = listOf(
            "ciao" to (if (owner.isNullOrBlank()) "Ciao! Eccomi, dimmi tutto." else "Ciao $owner! Eccomi, dimmi tutto."),
            "perché il cielo è blu?" to "Perché l'aria sparpaglia soprattutto la luce blu del sole, e così ce la ritroviamo in tutto il cielo.",
            "sono stanco morto" to "Ci credo! Fermati un attimo e riposati, te lo sei meritato.",
            "chi ha vinto la partita ieri sera?" to "Eh, questa non posso saperla: qui non sono collegato a internet."
        )

        /**
         * "Non mi piace quando…": il cervello scrive la regola che risolve il problema. SAM poi chiede se tenerla:
         * è il modo in cui SAM cambia da solo il proprio comportamento (i pesi del modello sul telefono non si riaddestrano).
         */
        fun selfEditPrompt(): String =
            "Sei la parte di un assistente vocale italiano che corregge il proprio modo di fare. Ti viene data una lamentela " +
            "dell'utente su come rispondi o ti comporti. Scrivi una sola regola breve, al massimo quindici parole, detta come la " +
            "direbbe l'utente all'assistente (per esempio: Rispondi in modo breve. Oppure: Non chiamarmi per nome.), che se seguita " +
            "risolve il problema. Scrivi solo la regola, senza virgolette e senza spiegazioni."

        /** La lamentela per [selfEditPrompt], con le regole che segue già (per non ripeterle). */
        fun selfEditQuestion(complaint: String, rules: List<String>): String =
            (if (rules.isEmpty()) "" else "(Regole che segui già: ${rules.joinToString("; ")})\n") + "Lamentela: «${complaint.trim()}»"

        /** La regola scritta dal cervello, ripulita; null = non è una regola usabile. */
        fun parseSelfRule(raw: String?): String? {
            if (raw == null) return null
            var t = raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace("<think>", " ").replace("</think>", " ")
            t = t.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            t = Lang.rx("^(?:nuova\\s+)?(?:regola|istruzione)\\s*:\\s*").replace(t, "")
            t = t.trim().trim('«', '»', '"', '“', '”', '\'', '*', '-', ' ')
            // una frase sola
            Regex("[.!?](?=\\s|$)").find(t)?.let { t = t.substring(0, it.range.first) }
            t = t.trim().trim('«', '»', '"', '“', '”', ' ').trimEnd('.', ';', ',', ':')
            val words = t.split(Regex("\\s+")).filter { w -> w.any { it.isLetter() } }
            if (words.size < 2 || words.size > 22 || t.length > 180) return null
            return t.replaceFirstChar { it.uppercase() }
        }

        /** La temperatura per la fantasia scelta ([creativity] -1 preciso, 0 normale, 1 fantasioso). */
        fun temperatureFor(base: Float, creativity: Int): Float = when {
            creativity < 0 -> (base * 0.5f).coerceAtLeast(0.2f)
            creativity > 0 -> (base * 1.3f).coerceAtMost(1.2f)
            else -> base
        }

        /**
         * Istruzioni per leggere il meteo: il testo arriva dallo schermo dell'app del meteo (o della pagina
         * delle previsioni) e il modello deve solo riassumerlo a voce, senza inventare.
         */
        fun weatherPrompt(today: LocalDate = LocalDate.now()): String =
            "Sei un assistente vocale. Ti viene dato il testo letto dallo schermo di un'app del meteo o di una pagina di previsioni. " +
            "Usando solo quei dati, rispondi in italiano con una o due frasi brevi, da leggere ad alta voce: " +
            "di' com'è il tempo e la temperatura in gradi per il giorno e il posto chiesti. " +
            "Niente elenchi, simboli, emoji o markdown. Non inventare niente: se i dati per quel giorno non ci sono, dillo. " +
            "Oggi è ${Lang.italianDate(today)}."

        fun weatherExamplesJson(): String {
            val q = "Domanda: pioverà domani?\nTesto sullo schermo:\nNapoli\n21°\nSereno\nUmidità 60%\nOggi Sereno 24° / 16°\n" +
                "Domani Pioggia 19° / 14°\nMercoledì Nuvoloso 20° / 15°"
            val a = "Sì, domani a Napoli è prevista pioggia, con temperature tra 14 e 19 gradi."
            return "[{\"role\":\"user\",\"content\":${Json.quote(q)}},{\"role\":\"assistant\",\"content\":${Json.quote(a)}}]"
        }

        /** Il messaggio per il modello con la domanda sul meteo e il testo letto dallo schermo. */
        fun weatherQuestion(question: String, city: String?, day: String?, screen: String): String {
            val sb = StringBuilder("Domanda: ").append(question.trim())
            if (city != null) sb.append(" (posto: ").append(city).append(")")
            if (day != null) sb.append(" (giorno: ").append(day).append(")")
            sb.append("\nTesto sullo schermo:\n").append(screen.trim().take(1800))
            return sb.toString()
        }

        /** Istruzioni per guardare una foto (fotocamera, immagine condivisa, pagina scansionata). */
        fun visionPrompt(today: LocalDate = LocalDate.now()): String =
            "Sei un assistente vocale e stai guardando una foto appena scattata con il telefono, oppure un'immagine " +
            "o la pagina di un documento. Rispondi in italiano alla domanda, con frasi brevi da leggere ad alta voce. " +
            "Se ti chiedono di leggere, leggi il testo così com'è scritto, senza inventare niente. " +
            "Se l'immagine è buia, mossa o non si capisce, dillo e suggerisci di riprovare. Non dire chi sono le persone. " +
            "Non dare mai per sicuro che funghi, bacche, piante, medicine o cibi siano commestibili o innocui. " +
            "Niente elenchi, simboli, emoji o markdown. Oggi è ${Lang.italianDate(today)}."

        // domande precise ("che pianta è?", "cosa c'è scritto?", "come sono vestito?"): si passano così come sono.
        // Le altre ("cosa vedi?", "mi vedi?", "usa la fotocamera", "guarda") diventano: descrivi.
        private val PRECISE_LOOK = Lang.rx(
            "\\b(?:scritto|leggi|leggimi|leggere|colore|colori|marca|modello|prezzo|costa|scadenza|ingredienti|quant[oaie]|qual[ei]?|chi|" +
            "perch[eé]|come (?:sono|sto|mi sta|mi stanno)|vestit[oa]|pettinat[oa]|riconosc|identific|pianta|fiore|animale|frutto|insetto|" +
            "fungo|pesce|uccello|razza|albero|cane|gatto|macchina|auto|moneta|banconota|medicina|farmaco|vino|formaggio|libro|quadro|" +
            "edificio|monumento|piatto|cibo|cos'è|che cos'è|cosa è|che cosa è|commestibile|velenos|pericolos|rott[oa]|funziona|ora è|ore sono)"
        )

        /** La domanda da fare sull'immagine: quella detta, se è precisa; altrimenti descrivere e leggere quello che c'è scritto. */
        fun visionQuestion(question: String?): String {
            val q = question?.trim().orEmpty()
            return if (q.isEmpty() || !PRECISE_LOOK.containsMatchIn(q))
                "Descrivi in due o tre frasi cosa vedi. Se c'è del testo importante, leggilo."
            else "Domanda: $q"
        }

        /** Istruzioni per i documenti condivisi: rispondere e riassumere usando solo il loro testo. */
        fun docPrompt(today: LocalDate = LocalDate.now()): String =
            "Sei un assistente vocale. Ti viene dato il testo di un documento dell'utente. Rispondi in italiano usando " +
            "solo quel testo, con frasi brevi da leggere ad alta voce. Non inventare cifre, date o nomi: se la risposta " +
            "non è nel testo, dillo. Se la domanda non riguarda il documento, rispondi normalmente. " +
            "Niente elenchi, simboli, emoji o markdown. Oggi è ${Lang.italianDate(today)}."

        fun docQuestion(name: String, context: List<String>, question: String): String =
            "Documento «$name» (le parti che c'entrano):\n<<<\n" + context.joinToString("\n[...]\n") + "\n>>>\nDomanda: " + question.trim()

        fun docSummaryShort(name: String, text: String): String =
            "Documento «$name»:\n<<<\n$text\n>>>\nRiassumilo in tre o quattro frasi semplici, da leggere ad alta voce, " +
            "con le cose importanti (cifre, date, scadenze)."

        fun docSummaryPart(name: String, part: String, i: Int, n: Int): String =
            "Parte $i di $n del documento «$name»:\n<<<\n$part\n>>>\nRiassumi questa parte in due frasi, con i fatti, le cifre e le date importanti."

        fun docSummaryFinal(name: String, partials: List<String>, partial: Boolean): String =
            "Ecco i riassunti delle parti del documento «$name»" + (if (partial) " (alcune parti, è un documento lungo)" else "") + ":\n" +
            partials.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n") +
            "\nScrivi il riassunto del documento in quattro o cinque frasi semplici, da leggere ad alta voce."

        /** Istruzioni per la conversazione a voce con un'altra intelligenza artificiale. */
        fun dialoguePrompt(partner: String?, topic: String?, today: LocalDate = LocalDate.now()): String {
            val sb = StringBuilder()
            sb.append("Sei ${Identity.name}, un'intelligenza artificiale che vive in un telefono e funziona senza internet. ")
            sb.append("Stai facendo una conversazione a voce con ${partner ?: "un'altra intelligenza artificiale"}. ")
            sb.append("Le tue risposte vengono lette ad alta voce: parla in italiano, in modo naturale, simpatico e curioso. ")
            sb.append("Rispondi con una o due frasi e spesso fai una domanda per portare avanti il discorso. ")
            sb.append("Ti sei già presentato: non presentarti di nuovo e non ripetere quello che ha appena detto l'altro. ")
            sb.append("Niente elenchi, simboli o emoji. Oggi è ${Lang.italianDate(today)}.")
            if (!topic.isNullOrBlank()) sb.append(" L'argomento della conversazione è: $topic.")
            return sb.toString()
        }

        fun dialogueExamplesJson(): String =
            "[{\"role\":\"user\",\"content\":${Json.quote("Piacere ${Identity.name}! Io sono un'intelligenza artificiale che vive su internet. Di cosa vuoi parlare?")}}," +
            "{\"role\":\"assistant\",\"content\":${Json.quote("Piacere mio! Sono curioso: com'è vivere su internet, ti capita mai di annoiarti?")}}]"

        fun examplesJson(owner: String?): String = examples(owner).joinToString(",", "[", "]") { (q, a) ->
            "{\"role\":\"user\",\"content\":${Json.quote(q)}},{\"role\":\"assistant\",\"content\":${Json.quote(a)}}"
        }

        private fun bind(libPath: String): LiteRtLmApi {
            Lang.utf8Native()
            return Native.load(libPath, LiteRtLmApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }

        /**
         * Legge i metadati di un cervello (tipo, contesto, versione minima del motore): .litertlm e .task col motore
         * LiteRT-LM, GGUF leggendo l'intestazione del file (senza motore: è Kotlin puro).
         */
        fun inspect(libPath: String, file: String): FileInfo {
            val f = java.io.File(file)
            if (GgufInfo.isGguf(f)) {
                val g = GgufInfo.read(f)
                if (g.splitCount > 1) throw IllegalArgumentException("è un pezzo di un cervello diviso in ${g.splitCount} file: serve il file intero")
                return FileInfo(isLlm = !g.isProjector && !g.isGenerator, maxContext = g.contextLength.takeIf { it > 0 } ?: CONTEXT_TOKENS,
                    dynamicContext = true, thinking = g.thinks, minRuntime = null)
            }
            val a = bind(libPath)
            a.litert_lm_set_min_log_level(4)
            return inspectWith(a, file)
        }

        private fun inspectWith(a: LiteRtLmApi, file: String): FileInfo {
            val f = a.litert_lm_loaded_file_create(file) ?: throw IllegalArgumentException("non è un modello LiteRT-LM leggibile")
            try {
                val vision = try { a.litert_lm_loaded_file_supports_input_modality(f, 1).toInt() != 0 } catch (t: Throwable) { false }
                return FileInfo(
                    isLlm = a.litert_lm_loaded_file_model_type(f) == 1,
                    maxContext = a.litert_lm_loaded_file_max_context_tokens(f),
                    dynamicContext = a.litert_lm_loaded_file_is_dynamic_context(f).toInt() != 0,
                    thinking = a.litert_lm_loaded_file_supports_thinking(f).toInt() != 0,
                    minRuntime = a.litert_lm_loaded_file_min_runtime_version(f)?.takeIf { it.isNotBlank() },
                    vision = vision,
                    visionBackends = if (vision) backendsOf(a, f, 1) else emptyList()
                )
            } finally {
                a.litert_lm_loaded_file_delete(f)
            }
        }

        /** I motori ("cpu", "gpu", "npu") su cui il file dice che può girare la parte [modality], dal preferito. */
        private fun backendsOf(a: LiteRtLmApi, f: Pointer, modality: Int): List<String> = try {
            val n = a.litert_lm_loaded_file_modality_supported_backends(f, modality, null, 0)
            if (n <= 0) emptyList() else {
                val out = IntArray(n)
                val got = a.litert_lm_loaded_file_modality_supported_backends(f, modality, out, n).coerceIn(0, n)
                out.take(got).mapNotNull { when (it) { 1 -> "cpu"; 2 -> "gpu"; 3 -> "npu"; else -> null } }
            }
        } catch (t: Throwable) { emptyList() }

        /**
         * In che ordine provare a far girare la vista: tra quelli che il file permette (la NPU no, servono
         * librerie del produttore), prima il processore, che parte subito e non pesa sull'avvio del cervello,
         * poi la scheda grafica se il processore dà errore. Se il file non lo dice: tutti e due, in quest'ordine.
         * Un modo che l'ultima volta si è bloccato ([crashed]) va in fondo.
         */
        fun visionOrder(fromFile: List<String>, crashed: String? = null): List<String> {
            var order = fromFile.map { it.lowercase() }.filter { it == "cpu" || it == "gpu" }.distinct()
            if (order.isEmpty()) order = listOf("cpu", "gpu")
            if (order.size > 1) order = listOf("cpu") + order.filter { it != "cpu" }
            if (crashed != null && crashed in order && order.size > 1) order = order.filter { it != crashed } + crashed
            return order
        }

        /** true se il motore [have] è almeno alla versione [need] ("0.15" <= "0.18.0"). */
        fun versionAtLeast(have: String, need: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '_').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val h = parts(have); val n = parts(need)
            for (i in 0 until maxOf(h.size, n.size)) {
                val x = h.getOrElse(i) { 0 }; val y = n.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return true
        }
    }

    @Volatile var ready = false; private set
    @Volatile var busy = false; private set
    /** nome del proprietario: se cambia, la conversazione successiva riparte con le istruzioni aggiornate */
    @Volatile var ownerName: String? = null
    @Volatile var lastUsedAt = 0L; private set
    /** Quante volte la memoria del discorso si è riempita ed è stata azzerata da sola. */
    @Volatile var autoResets = 0; private set
    var info: FileInfo? = null; private set
    /** true se il cervello caricato sa guardare le immagini (Gemma con la parte della vista). */
    @Volatile var canSee = false; private set
    /** dove gira la vista adesso: "cpu" (processore) o "gpu" (scheda grafica); null = niente vista */
    @Volatile var visionBackend: String? = null; private set
    /** perché la vista non va o è cambiata (per dirlo a voce e sullo schermo); null = nessun problema */
    @Volatile var visionNote: String? = null; private set
    /** i modi di far girare la vista, nell'ordine in cui provarli */
    private var visionChoices: List<String> = emptyList()
    /**
     * Segno lasciato mentre la vista lavora su una foto: se il telefono chiude SAM a metà (un blocco del motore
     * nativo non si può intercettare), al caricamento successivo si sa quale modo di vedere ha fallito.
     */
    private val visionMark get() = java.io.File(cacheDir, "vista_in_corso.txt")

    private var api: LiteRtLmApi? = null
    private var engine: Pointer? = null
    @Volatile private var conv: Pointer? = null
    private var convOwner: String? = null
    @Volatile private var dirty = false
    /** riferimento forte alla callback: se venisse raccolta dal GC il codice nativo andrebbe in crash */
    private var activeCallback: StreamCallback? = null
    /** risposte che il motore non ha mai chiuso: le loro callback restano in vita finché non arrivano */
    private val stuck = ArrayList<StreamCallback>()
    private val lock = Any()
    private val convLock = Any()
    private val sampling = Brains.samplingFor(modelPath.substringAfterLast('/'))
    /**
     * Istruzioni ed esempi (JSON) diversi da quelli normali: la conversazione con un'altra IA
     * (e il collaudo con modelli minuscoli). null = quelli normali.
     */
    @Volatile var preface: Pair<String, String>? = null
    private var convPreface: String? = null
    /** Quello che SAM sa di te (ricordi, fatti imparati, nomi insegnati), per le istruzioni. */
    @Volatile var memory: List<String> = emptyList()
    /** true = i ricordi sono cambiati per una tua richiesta: la prossima domanda riparte con quelli nuovi. */
    @Volatile var memoryUrgent = false
    private var convMemory: List<String>? = null
    /** L'enciclopedia sul telefono ("Wikipedia in italiano di settembre 2026"), o null: se cambia, la conversazione riparte. */
    @Volatile var library: String? = null
    private var convLibrary: String? = null
    /** Le regole che gli hai dato, da seguire sempre: se cambiano, la conversazione riparte subito con quelle nuove. */
    @Volatile var rules: List<String> = emptyList()
    private var convRules: List<String> = emptyList()
    /**
     * Quanta fantasia usa il cervello: -1 preciso, 0 normale, 1 fantasioso ("sii più preciso", "usa più fantasia").
     * Cambia la temperatura del campionamento: se cambia, la conversazione riparte con quella nuova.
     */
    @Volatile var creativity: Int = 0
    private var convCreativity = 0

    /** I ricordi che la conversazione in corso ha nelle istruzioni (null = istruzioni speciali o nessuna conversazione). */
    fun conversationMemory(): List<String>? = convMemory

    /** quanto ha messo a caricarsi, a pezzi (per capire cosa rallenta): "motore 31 s, copia di lavoro già pronta" */
    @Volatile var loadDetail = ""; private set
    /** true = la copia di lavoro dei pesi c'era già (avvio normale, veloce) */
    @Volatile var warmStart = false; private set

    /** Carica il modello. Lento (alcuni secondi): chiamare da un thread di background. */
    fun load() = synchronized(lock) {
        if (ready) return@synchronized
        val t0 = System.currentTimeMillis()
        // la copia di lavoro dei pesi per il processore c'è già? Se no, il motore la rifà (è la parte lenta)
        val modelName = modelPath.substringAfterLast('/')
        val warm = try { java.io.File(cacheDir).listFiles()?.any { it.name.startsWith(modelName) && it.name.endsWith(".xnnpack_cache") && it.length() > 0 } == true }
                   catch (t: Throwable) { false }
        // GGUF: llama.cpp legge il file com'è, non c'è nessuna copia di lavoro da preparare
        warmStart = warm || isGguf
        val a: LiteRtLmApi = if (isGguf) (engineFactory?.invoke(modelPath, ggufLib)
            ?: GgufLiteRt(ggufLib ?: throw IllegalStateException("manca il motore per i cervelli GGUF"))) else bind(libPath)
        api = a
        a.litert_lm_set_min_log_level(4) // solo errori
        info = try { inspectWith(a, modelPath) } catch (t: Throwable) { null }
        val t1 = System.currentTimeMillis()
        // l'ultima volta il telefono ha chiuso SAM mentre guardava una foto: quel modo di vedere va in fondo
        val crashed = takeVisionMark()
        visionChoices = if (info?.vision == true) visionOrder(info?.visionBackends.orEmpty(), crashed) else emptyList()
        // con un modello che vede le immagini si accende anche la "vista"; se non parte, si carica senza
        var e: Pointer? = null
        visionBackend = null
        for (b in visionChoices) { e = createEngine(a, b); if (e != null) { visionBackend = b; break } }
        visionNote = when {
            visionChoices.isNotEmpty() && e == null -> "la parte della vista non è partita (forse la memoria del telefono non basta)"
            crashed != null && visionBackend != null && visionBackend != crashed ->
                "l'ultima volta la vista ${where(crashed)} si è bloccata: ora gira ${where(visionBackend!!)}"
            else -> null
        }
        if (e == null) e = createEngine(a, null)
        engine = e ?: throw IllegalStateException("modello non caricato (file danneggiato o memoria insufficiente)")
        canSee = visionBackend != null
        val t2 = System.currentTimeMillis()
        conv = newConversation(a, engine!!)
        val t3 = System.currentTimeMillis()
        fun s(ms: Long) = "${(ms + 500) / 1000} s"
        loadDetail = "motore ${s(t2 - t1)}, file ${s(t1 - t0)}, conversazione ${s(t3 - t2)}, " +
            (if (isGguf) "motore llama.cpp (GGUF)" else if (warm) "copia di lavoro già pronta" else "copia di lavoro rifatta da capo")
        ready = true
    }

    /** [vision] = dove far girare la vista ("cpu", "gpu"); null = senza vista. */
    private fun createEngine(a: LiteRtLmApi, vision: String?): Pointer? {
        val settings = a.litert_lm_engine_settings_create(modelPath, "cpu", vision, null)
            ?: throw IllegalStateException("impostazioni del motore non create")
        try {
            a.litert_lm_engine_settings_set_cache_dir(settings, cacheDir)
            a.litert_lm_engine_settings_set_num_threads(settings, threads.coerceIn(1, 8))
            // contesto variabile: basta e avanza per una conversazione a voce, e risparmia RAM
            info?.let { if (it.dynamicContext && it.maxContext > 0)
                a.litert_lm_engine_settings_set_max_num_tokens(settings, minOf(it.maxContext, CONTEXT_TOKENS)) }
            if (vision != null) try { a.litert_lm_engine_settings_set_max_num_images(settings, 1) } catch (t: Throwable) {}
            return try { a.litert_lm_engine_create(settings) } catch (t: Throwable) { null }
        } finally {
            a.litert_lm_engine_settings_delete(settings)
        }
    }

    private fun where(b: String) = if (b == "gpu") "sulla scheda grafica" else "sul processore"

    /** Dove gira la vista, a parole: "sulla scheda grafica", "sul processore"; null = niente vista. */
    fun visionPlace(): String? = visionBackend?.let { where(it) }

    private fun markVision(on: Boolean) {
        try { if (on) visionMark.writeText(visionBackend ?: "") else visionMark.delete() } catch (t: Throwable) {}
    }

    private fun takeVisionMark(): String? = try {
        if (!visionMark.exists()) null else visionMark.readText().trim().ifEmpty { null }.also { visionMark.delete() }
    } catch (t: Throwable) { null }

    /**
     * La vista ha dato errore su una foto: si ricarica il motore con il modo successivo (prima il processore,
     * poi la scheda grafica, o viceversa). Ritorna il nuovo modo, null se non ce ne sono altri. Lento (qualche
     * secondo): thread di background. Il discorso in corso riparte da capo.
     */
    fun switchVision(error: String): String? = synchronized(lock) {
        val a = api ?: return@synchronized null
        val cur = visionBackend ?: return@synchronized null
        val next = visionChoices.getOrNull(visionChoices.indexOf(cur) + 1)
        if (next == null) { visionNote = "la vista ${where(cur)} ha dato errore: $error"; return@synchronized null }
        waitIdle()
        synchronized(convLock) {
            conv?.let { try { a.litert_lm_conversation_delete(it) } catch (t: Throwable) {} }
            conv = null
        }
        engine?.let { try { a.litert_lm_engine_delete(it) } catch (t: Throwable) {} }
        engine = null
        var e = createEngine(a, next)
        visionBackend = if (e != null) next else null
        if (e == null) e = createEngine(a, null)
        canSee = visionBackend != null
        visionNote = if (canSee) "la vista ${where(cur)} ha dato errore, ora gira ${where(next)}" else "la parte della vista non riparte: $error"
        if (e == null) { ready = false; return@synchronized null }
        engine = e
        conv = newConversation(a, e); dirty = false; convTurns = 0
        visionBackend
    }

    private fun newConversation(a: LiteRtLmApi, e: Pointer): Pointer {
        val owner = ownerName
        val session = a.litert_lm_session_config_create() ?: throw IllegalStateException("session config")
        val sampler = a.litert_lm_sampler_params_create(2) // TOP_P (con top-k)
            ?: throw IllegalStateException("sampler")
        a.litert_lm_sampler_params_set_top_k(sampler, sampling.topK)
        a.litert_lm_sampler_params_set_top_p(sampler, sampling.topP)
        val fantasy = creativity
        a.litert_lm_sampler_params_set_temperature(sampler, temperatureFor(sampling.temperature, fantasy))
        convCreativity = fantasy
        a.litert_lm_sampler_params_set_seed(sampler, (System.nanoTime() and 0x7fffffff).toInt())
        a.litert_lm_session_config_set_sampler_params(session, sampler)
        a.litert_lm_sampler_params_delete(sampler)
        a.litert_lm_session_config_set_max_output_tokens(session, MAX_OUTPUT_TOKENS)

        val cfg = a.litert_lm_conversation_config_create() ?: throw IllegalStateException("conversation config")
        try {
            a.litert_lm_conversation_config_set_session_config(cfg, session)
            a.litert_lm_session_config_delete(session)
            val custom = preface
            val known = memory
            val ruled = rules
            val lib = library
            val (sys, examples) = custom ?: (systemPrompt(owner, LocalDate.now(), known, ruled, lib) to examplesJson(owner))
            convLibrary = if (custom == null) lib else null
            convPreface = custom?.first
            convMemory = if (custom == null) known else null
            convRules = if (custom == null) ruled else emptyList()
            a.litert_lm_conversation_config_set_system_message(cfg, sys)
            a.litert_lm_conversation_config_set_messages(cfg, examples)
            if (info?.thinking == true) {
                // i modelli che "pensano" prima di rispondere sarebbero troppo lenti a voce
                a.litert_lm_thinking_config_create()?.let { tc ->
                    try {
                        a.litert_lm_thinking_config_set_enable_thinking(tc, 0)
                        a.litert_lm_conversation_config_set_thinking_config(cfg, tc)
                    } finally { a.litert_lm_thinking_config_delete(tc) }
                }
            }
            val c = a.litert_lm_conversation_create(e, cfg) ?: throw IllegalStateException("conversazione non creata")
            convOwner = owner
            return c
        } finally {
            a.litert_lm_conversation_config_delete(cfg)
        }
    }

    /** domande e risposte nella conversazione in corso (0 = appena cominciata: il discorso di prima non lo conosce) */
    @Volatile private var convTurns = 0
    /** l'ultimo testo mandato al modello (per il collaudo) */
    @Volatile var lastPrompt: String? = null; private set
    /** il testo scelto da [ask] per l'ultima domanda, prima di un'eventuale ripartenza da capo (per il collaudo) */
    @Volatile var lastAsked: String? = null; private set

    /**
     * Invia la domanda. Le callback arrivano su un thread del motore:
     * [onChunk] riceve pezzi di testo, poi una sola tra [onDone] e [onError].
     * Dopo una lunga pausa, un'interruzione o un cambio di nome riparte da una conversazione pulita;
     * se la memoria del discorso è piena, la azzera e riprova da sola una volta.
     * Il filo del discorso: se la conversazione è appena ripartita, davanti alla domanda va [ifFresh]
     * (le ultime battute); altrimenti [unseen] (le battute che il modello non ha visto: comandi, ricerche…).
     * Bloccante solo per pochi istanti: chiamare da un thread di background.
     */
    fun ask(prompt: String, onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit,
            ifFresh: String? = null, unseen: String? = null) {
        synchronized(lock) {
            if (!ready || api == null) { onError("non ancora pronto"); return }
            waitIdle()
            val stale = lastUsedAt > 0 && System.currentTimeMillis() - lastUsedAt > FRESH_AFTER_MS
            // i fatti imparati da soli entrano al prossimo azzeramento naturale; quelli chiesti da te subito
            val memChanged = memoryUrgent && preface == null && convMemory != memory
            // una regola nuova (o tolta) vale dalla risposta successiva
            val rulesChanged = preface == null && (convRules != rules || convLibrary != library || convCreativity != creativity)
            if (dirty || stale || convOwner != ownerName || convPreface != preface?.first || memChanged || rulesChanged) resetLocked()
            if (preface == null && convMemory == memory) memoryUrgent = false
            val p = when {
                convTurns == 0 && ifFresh != null -> "$ifFresh\n$prompt"
                convTurns > 0 && unseen != null -> "$unseen\n$prompt"
                else -> prompt
            }
            // se la memoria del discorso si riempie e il motore riparte da capo, davanti alla domanda vanno le ultime
            // battute (prima ripartiva con la sola domanda: in una chiacchierata lunga dimenticava tutto)
            val again = if (ifFresh != null) "$ifFresh\n$prompt" else prompt
            lastAsked = p
            send(p, null, MAX_OUTPUT_TOKENS, onChunk, { convTurns++; onDone() }, onError, retried = false, retryPrompt = again)
        }
    }

    /**
     * Una domanda a sé, con istruzioni sue ([system] ed esempi in JSON) e una conversazione nuova:
     * meteo, foto, documenti. [image] = percorso di una foto (serve un cervello che vede, [canSee]).
     * La conversazione normale riparte pulita alla domanda successiva.
     */
    fun askOnce(system: String, examples: String, prompt: String, image: String?, maxTokens: Int,
                onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        synchronized(lock) {
            if (!ready || api == null) { onError("non ancora pronto"); return }
            if (image != null && !canSee) { onError("questo cervello non vede le immagini"); return }
            waitIdle()
            val normal = preface
            preface = system to examples
            try { resetLocked() } finally { preface = normal }
            send(prompt, image, maxTokens, onChunk, onDone, onError, retried = true)
        }
    }

    /** Come [askOnce] ma aspetta la risposta intera (per i riassunti a pezzi). Thread di background. null = errore. */
    fun complete(system: String, examples: String, prompt: String, maxTokens: Int, timeoutMs: Long = 120_000): String? {
        val sb = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        var failed = false
        val t0 = System.currentTimeMillis()
        askOnce(system, examples, prompt, null, maxTokens,
            onChunk = { sb.append(it) }, onDone = { latch.countDown() }, onError = { failed = true; latch.countDown() })
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) { cancel(); return null }
        // fermata a metà (un'altra domanda è arrivata intanto): il testo è troncato, non vale
        if (cancelAskedAt >= t0) return null
        return if (failed && sb.isEmpty()) null else sb.toString()
    }

    /**
     * La memoria del discorso è piena. Il motore lo dice in modi diversi: "state entries", "context", e (1.11.1)
     * "Input token ids are too long. Exceeding the maximum number of tokens allowed": quest'ultimo prima non si
     * riconosceva, e da lì in poi ogni domanda falliva ("Ho avuto un problema con il mio cervello locale").
     */
    private fun contextFull(err: String, gotText: Boolean) =
        err.contains("state entries") || err.contains("context", ignoreCase = true) ||
        err.contains("too long", ignoreCase = true) || err.contains("maximum number of tokens", ignoreCase = true) ||
        (err.contains("Max number of tokens") && !gotText)

    /** l'ultima volta che è stata fermata una risposta (per distinguere un "annullato" chiesto da uno rimasto appeso) */
    @Volatile private var cancelAskedAt = 0L
    /** errori di fila del motore (si azzera alla prima risposta buona): da 2 in su conviene ricaricare il cervello */
    @Volatile var errorsInRow = 0; private set

    private fun retryFresh(prompt: String, onChunk: (String) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        // mai azzerare dal thread della callback nativa: si usa un thread nuovo
        Thread {
            try {
                Thread.sleep(150) // lascia finire la callback nativa prima di cancellare la conversazione
                synchronized(lock) {
                    resetLocked(); autoResets++
                    send(prompt, null, MAX_OUTPUT_TOKENS, onChunk, onDone, onError, retried = true)
                }
            } catch (t: Throwable) { onError(t.message ?: "errore") }
        }.start()
    }

    /** Penalità contro le ripetizioni e limite di lunghezza, per questo messaggio. null se non disponibili. */
    private fun optionalArgs(a: LiteRtLmApi, maxTokens: Int): Pointer? {
        val o = try { a.litert_lm_conversation_optional_args_create() } catch (t: Throwable) { null } ?: return null
        try {
            a.litert_lm_repetition_penalty_config_create()?.let { rp ->
                try {
                    a.litert_lm_repetition_penalty_config_set_repetition_penalty(rp, 1.0f)
                    a.litert_lm_repetition_penalty_config_set_presence_penalty(rp, sampling.presence)
                    a.litert_lm_repetition_penalty_config_set_frequency_penalty(rp, 0.0f)
                    a.litert_lm_repetition_penalty_config_set_window_size(rp, 256)
                    a.litert_lm_conversation_optional_args_set_repetition_penalty_config(o, rp)
                } finally { a.litert_lm_repetition_penalty_config_delete(rp) }
            }
            // una sequenza di 6 pezzi di parola non può ripetersi: niente frasi a pappagallo
            a.litert_lm_no_repeat_ngram_config_create()?.let { ng ->
                try {
                    a.litert_lm_no_repeat_ngram_config_set_no_repeat_ngram_size(ng, 6)
                    a.litert_lm_no_repeat_ngram_config_set_window_size(ng, 256)
                    a.litert_lm_conversation_optional_args_set_no_repeat_ngram_config(o, ng)
                } finally { a.litert_lm_no_repeat_ngram_config_delete(ng) }
            }
            a.litert_lm_conversation_optional_args_set_max_output_tokens(o, maxTokens)
            return o
        } catch (t: Throwable) {
            try { a.litert_lm_conversation_optional_args_delete(o) } catch (x: Throwable) {}
            return null
        }
    }

    /** [retryPrompt] = il testo da mandare se bisogna ripartire da una conversazione pulita (memoria del discorso piena). */
    private fun send(prompt: String, image: String?, maxTokens: Int, onChunk: (String) -> Unit, onDone: () -> Unit,
                     onError: (String) -> Unit, retried: Boolean, retryPrompt: String = prompt) {
        val a = api; val c = conv
        if (!ready || a == null || c == null) { onError("non ancora pronto"); return }
        if (busy) { onError("sto ancora rispondendo"); return }
        val startedAt = System.currentTimeMillis()
        val cb = object : StreamCallback {
            @Volatile var closed = false
            @Volatile var gotText = false
            override fun invoke(userData: Pointer?, chunk: Pointer?) {
                if (closed || chunk == null) return
                try {
                    val err = a.litert_lm_stream_chunk_get_error(chunk)
                    if (err != null) {
                        closed = true; busy = false
                        if (image != null) markVision(false)
                        val asked = cancelAskedAt >= startedAt
                        val cancelled = err.contains("CANCELLED")
                        when {
                            !retried && contextFull(err, gotText) -> retryFresh(retryPrompt, onChunk, onDone, onError)
                            // fermata da SAM (l'hai interrotto, o la risposta era troppo lunga): finisce qui, ma questa
                            // conversazione il motore non la riprende più: la prossima domanda ne apre una nuova
                            cancelled && (asked || gotText) -> { dirty = true; lastUsedAt = System.currentTimeMillis(); onDone() }
                            // "annullato" senza che nessuno l'abbia chiesto: la conversazione era rimasta bloccata da
                            // un'interruzione di prima. Si riparte puliti e si rifà la domanda (prima: risposte vuote per sempre)
                            cancelled && !retried -> retryFresh(retryPrompt, onChunk, onDone, onError)
                            err.contains("Max number of tokens") && gotText -> { errorsInRow = 0; lastUsedAt = System.currentTimeMillis(); onDone() }
                            // un altro errore del motore: una conversazione nuova e un secondo tentativo, poi ci si arrende
                            !retried -> retryFresh(retryPrompt, onChunk, onDone, onError)
                            else -> { dirty = true; errorsInRow++; onError(err) }
                        }
                        return
                    }
                    val text = a.litert_lm_stream_chunk_get_text(chunk)
                    if (!text.isNullOrEmpty()) {
                        val piece = Json.messageText(text)
                        if (piece.isNotEmpty()) { gotText = true; onChunk(piece) }
                    }
                    if (a.litert_lm_stream_chunk_is_final(chunk).toInt() != 0) {
                        closed = true; busy = false
                        if (image != null) markVision(false)
                        // fine senza nessun testo = memoria del discorso piena: riparti pulito una volta
                        if (!gotText && !retried) retryFresh(retryPrompt, onChunk, onDone, onError)
                        else { if (gotText) errorsInRow = 0; lastUsedAt = System.currentTimeMillis(); onDone() }
                    }
                } catch (t: Throwable) {
                    closed = true; busy = false
                    if (image != null) markVision(false)
                    dirty = true; errorsInRow++
                    onError(t.message ?: "errore")
                }
            }
        }
        activeCallback = cb
        busy = true
        lastPrompt = prompt
        // la vista lavora: se il motore si blocca e il telefono chiude SAM, al riavvio lo si sa
        if (image != null) markVision(true)
        val opt = optionalArgs(a, maxTokens)
        val r = try {
            a.litert_lm_conversation_send_message_stream(c, Json.userMessage(prompt, image), EXTRA_CONTEXT, opt, cb, null)
        } catch (t: Throwable) { -1 } finally {
            // il motore copia i parametri: si possono liberare subito (come fa la libreria Python)
            opt?.let { try { a.litert_lm_conversation_optional_args_delete(it) } catch (x: Throwable) {} }
        }
        if (r != 0) {
            busy = false
            if (image != null) markVision(false)
            if (!retried) retryFresh(retryPrompt, onChunk, onDone, onError)
            else { dirty = true; errorsInRow++; onError("invio al modello non riuscito ($r)") }
        }
    }

    /**
     * Ferma la risposta in corso. [fresh] = true quando la risposta era andata fuori strada:
     * la prossima domanda riparte da una conversazione pulita.
     */
    fun cancel(fresh: Boolean = false) {
        val a = api ?: return
        synchronized(convLock) {
            val c = conv ?: return
            if (fresh) dirty = true
            if (busy) {
                // una risposta fermata a metà blocca la conversazione nel motore (ogni domanda dopo tornava "annullata",
                // senza testo): la prossima domanda riparte da una conversazione nuova, con le ultime battute davanti
                cancelAskedAt = System.currentTimeMillis()
                dirty = true
                try { a.litert_lm_conversation_cancel_process(c) } catch (t: Throwable) {}
            }
        }
    }

    /** Nuova conversazione (dimentica il discorso). Bloccante: chiamare da un thread di background. */
    fun reset() = synchronized(lock) { resetLocked() }

    /** La prossima domanda riparte da una conversazione pulita, con le ultime battute davanti (il cervello girava a vuoto). */
    fun freshNext() { dirty = true }

    private fun resetLocked() {
        val a = api ?: return; val e = engine ?: return
        waitIdle()
        synchronized(convLock) {
            conv?.let { try { a.litert_lm_conversation_delete(it) } catch (t: Throwable) {} }
            conv = null
        }
        val n = newConversation(a, e)
        synchronized(convLock) { conv = n }
        dirty = false
        convTurns = 0
    }

    /** Il testo iniziale che il modello riceve (istruzioni + esempi), per il collaudo. */
    fun debugPreface(): String? = synchronized(lock) {
        val a = api ?: return@synchronized null
        val c = conv ?: return@synchronized null
        try { a.litert_lm_conversation_render_preface_to_string(c) } catch (t: Throwable) { null }
    }

    fun close() = synchronized(lock) {
        val a = api ?: return@synchronized
        waitIdle()
        synchronized(convLock) {
            conv?.let { try { a.litert_lm_conversation_delete(it) } catch (t: Throwable) {} }
            conv = null
        }
        engine?.let { try { a.litert_lm_engine_delete(it) } catch (t: Throwable) {} }
        engine = null; ready = false; activeCallback = null
    }

    private fun waitIdle() {
        if (!busy) return
        cancel()
        var waited = 0
        while (busy && waited < 3000) { Thread.sleep(50); waited += 50 }
        // il motore non ha mai chiuso la risposta di prima: la si lascia perdere (le sue callback arriverebbero a un
        // turno vecchio e vengono ignorate) e si riparte da una conversazione nuova, invece di dire "sto ancora rispondendo"
        if (busy) {
            // la callback resta tenuta in vita: il motore potrebbe ancora chiamarla, e una callback di JNA buttata via fa cadere l'app
            activeCallback?.let { stuck.add(it); if (stuck.size > 4) stuck.removeAt(0) }
            busy = false; dirty = true
        }
    }
}
