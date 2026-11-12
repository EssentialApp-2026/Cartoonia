package com.essentialapp.sam

import java.time.LocalDateTime

/**
 * Interpreta la frase italiana e decide cosa fare.
 * Kotlin puro (nessun import Android) così è testabile a tavolino.
 *
 * Alcuni comandi hanno bisogno di una seconda frase (il testo di un messaggio,
 * una conferma, il nome del proprietario): in quel caso Brain memorizza una
 * "continuazione" in [pending] che consuma la frase successiva.
 *
 * Memoria: ricorda per sempre ciò che gli chiedi ("ricordati che…"), impara da solo quello che
 * racconti di te ("mi piace la pizza"), impara le scorciatoie che gli insegni ("quando dico
 * buonanotte…") e quale contatto scegli tra due omonimi. Dimentica solo quando glielo chiedi.
 */
class Brain(private val a: Actions, private val mem: Memory) {
    /** 2.0.2: c'è un lavoro lasciato a metà? (lo dicono telefono e PC: serve per capire «continua») */
    var hasWork: () -> Boolean = { false }

    private var pending: ((String) -> Outcome)? = null

    val hasPending: Boolean get() = pending != null

    /** SAM aspetta un testo da dettare (un messaggio): il servizio vocale lo raccoglie anche se fai delle pause. */
    var awaitingText = false; private set

    /** La conversazione è finita (silenzio, "grazie"…): una domanda rimasta in sospeso non vale più. */
    fun cancelPending() { pending = null; awaitingText = false }

    // la memoria è cambiata per una tua richiesta: il cervello IA deve saperlo subito
    private var memoryUrgent = false
    // …e qualcosa è stato tolto o sostituito, oppure c'è una regola nuova: il cervello riparte con le istruzioni nuove.
    // Una cosa aggiunta da ricordare invece la conosce già dal discorso: niente ripartenza, così il filo non si perde.
    private var memoryDropped = false
    // l'ultimo fatto imparato da solo, da mostrare sullo schermo
    private var learnedNow: String? = null

    /** true (una volta sola) se hai fatto ricordare, dimenticare o insegnare qualcosa: l'IA va aggiornata subito. */
    fun takeMemoryChange(): Boolean { val r = memoryUrgent; memoryUrgent = false; return r }

    /** true (una volta sola) se la conversazione del cervello va rifatta: ricordi tolti o cambiati, regole nuove. */
    fun takeMemoryDropped(): Boolean { val r = memoryDropped; memoryDropped = false; return r }

    /** Le ultime battute (le tiene il servizio vocale, anche su file): per "di cosa abbiamo parlato?" e "ripeti". */
    var talk: Talk? = null
    /** 1.23: la mente (diario, impegni, abitudini, emozioni…): la tiene il servizio, che la salva su file */
    var mind: Mind? = null
    /** cose che la mente si è segnata da sola in questa frase («me lo segno: domani hai il dentista»): il servizio le mostra */
    private val mindNotes = ArrayList<String>()
    fun takeMindNotes(): List<String> { val r = mindNotes.toList(); mindNotes.clear(); return r }

    /** Il fatto su di te imparato da solo con l'ultima frase (per mostrarlo sullo schermo), oppure null. */
    fun takeLearned(): String? { val r = learnedNow; learnedNow = null; return r }

    private val giorni = listOf(
        "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"
    )
    private val mesi = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )
    private val numeri = linkedMapOf(
        "zero" to 0, "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3,
        "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7, "otto" to 8,
        "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13,
        "quattordici" to 14, "quindici" to 15, "sedici" to 16, "diciassette" to 17,
        "diciotto" to 18, "diciannove" to 19, "venti" to 20, "trenta" to 30,
        "quaranta" to 40, "quarantacinque" to 45, "cinquanta" to 50, "sessanta" to 60,
        "novanta" to 90
    )
    private val numPattern = "(\\d+|" + numeri.keys.sortedWith(Comparator { x, y -> y.length - x.length })
        .joinToString("|") + ")"

    // parole di riempimento davanti a una richiesta: "allora", "senti", "ok"… (per la vista e per la memoria)
    private val FILL = "(?:(?:sam|sem|ok|okay|ehi|ehm|eh|ah|allora|senti|ascolta|ascoltami|dai|ecco|bene|beh|ora|adesso|e|poi|" +
        "guarda|comunque|ciao|sì|si|certo|esatto|giusto|perfetto|bravo|ottimo|grazie|benissimo|" +
        "per favore|per piacere|ti prego|mi raccomando)[,\\s]+)*"

    // verbi per spegnere, chiudere, fermare: valgono per torcia, musica, sveglia, volume…
    private val OFF = "spegni|spegnila|spegnilo|spegnere|disattiva|disattivala|disattivalo|togli|toglila|toglilo|" +
        "leva|chiudi|chiudila|chiudilo|chiudere|ferma|fermala|fermalo|fermare|stop|basta|annulla|cancella|" +
        "elimina|interrompi|off"
    // ciò che sta suonando
    private val MEDIA = "musica|canzone|canzoni|brano|brani|traccia|pezzo|video|riproduzione|radio|podcast|" +
        "audiolibro|spotify|playlist"

    private val aiuto =
        "Mi gestisco da solo: di' controllati per sapere se funziona tutto, riparati per farmi sistemare, cosa è andato storto per il registro, " +
        "e ogni giorno metto al sicuro i ricordi: per tornare indietro di' ripristina la copia di ieri. " +
        "Posso accendere la torcia, dirti l'ora e la batteria, mettere e spegnere sveglie e timer, " +
        "cambiare volume e luminosità, aprire e chiudere le app, far partire una canzone, metterla in pausa o passare alla prossima, " +
        "chiamare e scrivere ai tuoi contatti, mandare messaggi su WhatsApp, dirti dove sei, portarti in un posto, " +
        "dirti che tempo fa, aprire la fotocamera, leggere le notifiche, fare calcoli e chiacchierare con te. " +
        "Con: modalità conversazione, parliamo senza che tu debba dire SAM ogni volta, finché non dici basta. " +
        "Ricordo per sempre quello che mi chiedi con: ricordati che, oppure dicendomi una cosa e poi: ricordatelo. " +
        "Lo dimentico solo se mi dici: dimentica, e la cosa. " +
        "Imparo da solo quello che mi racconti di te, e puoi insegnarmi delle scorciatoie: quando dico buonanotte, spegni la torcia. " +
        "Puoi darmi delle regole da seguire sempre: d'ora in poi rispondi in modo breve. Se capisco male, correggimi: no, intendevo, " +
        "e il comando giusto; poi ti chiedo se devo impararlo. Mi ricordo anche quello che ci siamo detti: chiedimi di cosa abbiamo parlato. " +
        "Per una risposta ragionata di': pensaci bene, e la domanda. Se ti rispondo male, dimmi: sbagliato, la risposta giusta è, e poi " +
        "la risposta: me lo segno e la volta dopo rispondo giusto. " +
        "Posso imparare la tua voce e rispondere solo a te, leggerti i file che condividi con me e guardare con la fotocamera: di' cosa vedi. " +
        "Calcolo esatti date e feste, l'ora nel mondo, conversioni, IVA e sconti: chiedimi quanto manca a Natale, o che ore sono a New York. " +
        "Se metti l'enciclopedia nella configurazione, rispondo partendo da Wikipedia: chiedimi cosa dice l'enciclopedia su Napoli. " +
        "Puoi cambiare la mia voce: cambia voce, parla più piano, voce più grave. E il mio carattere: sii più spiritoso; se qualcosa non " +
        "ti piace dimmelo, e ti propongo io la regola per correggermi. Col Wi-Fi scarico da solo la Wikipedia nuova e il cervello nuovo: " +
        "chiedimi ci sono aggiornamenti?"

    private fun n2i(s: String): Int? = s.trim().lowercase().let { it.toIntOrNull() ?: numeri[it] }

    private fun now() = System.currentTimeMillis()

    // confine di parola portabile: funziona con le accentate su JVM e su Android (ICU)
    private fun ci(p: String) = Lang.rx(p)
    private fun off(t: String) = ci("\\b($OFF)\\b").containsMatchIn(t)

    /** Punto di ingresso: una frase già riconosciuta. */
    fun handle(raw: String): Outcome {
        var t = raw.trim().lowercase()
        t = ci("^(per favore|per piacere|puoi|potresti|mi puoi|senti|allora)\\s+").replace(t, "")
        t = t.trim(' ', '.', ',', '!', '?')
        if (t.isEmpty()) return Outcome.Handled()

        // Se stavamo aspettando una risposta (testo del messaggio / conferma / nome)...
        pending?.let { cont ->
            pending = null
            awaitingText = false
            if (ci("^(lascia stare|annulla|niente|fa niente|stop|lascia perdere)\\b").containsMatchIn(t))
                return Outcome.Speak("Va bene, annullo.", endSession = true)
            return cont(t).also { noteTopic(t, it) }
        }

        return try {
            route(t).also { noteTopic(t, it); noteHeard(t, it); noteSource(t, it) }
        } catch (e: Exception) {
            Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.")
        }
    }

    // ------------------------------------------------------------------ nome del proprietario
    private val giveUpName = "Va bene, nessun problema. Puoi scrivere il tuo nome nella configurazione, con l'ingranaggio."

    /** Prima accensione (o nome sconosciuto): chiede come si chiama il proprietario. Ritorna la domanda da dire. */
    fun askOwnerName(first: Boolean): String {
        pending = { captureName(it, 1) }
        return if (first) "Ciao! Sono SAM, il tuo assistente. Prima di cominciare: come ti chiami?"
        else "Non me l'hai ancora detto: come ti chiami?"
    }

    private fun captureName(ans: String, attempt: Int): Outcome {
        val name = Lang.extractName(ans)
        if (name == null) {
            if (attempt >= 3) return Outcome.Speak(giveUpName, endSession = true)
            pending = { captureName(it, attempt + 1) }
            return Outcome.Speak("Scusa, non ho capito. Dimmi solo il tuo nome.")
        }
        pending = { confirmName(name, it, attempt) }
        return Outcome.Speak("Ho capito $name. È giusto?")
    }

    private fun confirmName(name: String, answer: String, attempt: Int): Outcome {
        if (Lang.isYes(answer)) {
            mem.setOwnerName(name)
            memoryUrgent = true
            return Outcome.Speak("Piacere, $name! Me lo ricorderò. Quando ti serve qualcosa, di' SAM.", endSession = true)
        }
        if (attempt >= 3) return Outcome.Speak(giveUpName, endSession = true)
        // "no, mi chiamo Luca": il nome giusto è già nella risposta
        val rest = ci("^\\W*no\\b[\\s,.!]*").replace(answer, "").trim()
        if (rest.isNotEmpty() && Lang.extractName(rest) != null) return captureName(rest, attempt + 1)
        pending = { captureName(it, attempt + 1) }
        return Outcome.Speak("Scusa! Ripetimi il tuo nome, solo il nome.")
    }

    // ------------------------------------------------------------------ comandi
    /** [depth] > 0 quando la frase arriva da una scorciatoia insegnata (evita i giri infiniti). */
    private fun route(raw: String, depth: Int = 0): Outcome {
        // solo per i comandi, mai sul testo dettato di un messaggio:
        // correzioni del riconoscitore e numeri detti a parole ("venticinque" -> "25")
        val t = Num.wordsToDigits(fixAsr(raw))

        // --- 1.23: chi dice di voler farsi del male: subito una risposta vicina, con chi può aiutare (prima di tutto il resto)
        if (Feelings.crisis(raw)) return crisisReply()

        // --- "no, intendevo accendi la torcia": si fa la cosa giusta e, se la frase di prima non l'avevo capita,
        //     propongo di impararla (così la prossima volta la capisco da solo)
        correction(t, depth)?.let { return it }

        // --- il nome del proprietario (prima della memoria: "ti ricordi come mi chiamo?")
        if (ci("\\b(come mi chiamo|qual è il mio nome|sai il mio nome|ti ricordi il mio nome|ti ricordi come mi chiamo)\\b").containsMatchIn(t)) {
            val n = mem.ownerName()
            return if (n != null) Outcome.Speak("Ti chiami $n.") else Outcome.Speak(askOwnerName(first = false))
        }

        // --- 2.0: la cura di sé: "controllati", "riparati", "cosa è andato storto?", "fai una copia", "ripristina la copia di ieri"
        // («continua» da solo vale come «riprendi il lavoro» solo se c'è un lavoro in sospeso: altrimenti è la lettura o la musica)
        CareCommands.parse(t)?.let { if (it.arg != "soft" || hasWork()) return it }

        // --- la tua voce (prima della memoria: "memorizza la mia voce", "dimentica la mia voce")
        voiceCommand(t)?.let { return it }
        // --- domande su quello che SAM sa fare ("hai l'enciclopedia?", "puoi leggere i file?", "perché non rispondi?"):
        //     risponde SAM con come stanno davvero le cose (il cervello diceva "non posso leggere file")
        abilityQuestion(t)?.let { return it }
        // --- come parla e come pensa SAM: la voce, la fantasia del cervello, quale cervello usare, gli aggiornamenti,
        //     e le lamentele su come risponde (SAM propone da solo la regola per correggersi)
        selfCommand(t)?.let { return it }
        // --- 1.20: la chat: "leggimi la chat", "rileggi gli ultimi messaggi", "riassumi la conversazione"
        readChat(t)?.let { return it }
        // --- 1.23: la mente: "buongiorno", "cosa devo fare domani?", "le mie abitudini", "il tuo diario", "come lo sai?",
        //     "facciamo conoscenza", "pensa bene alle domande difficili"
        mindCommand(t, raw)?.let { return it }
        // --- 1.21: foto e video: "mostrami le foto di ieri", "scatta una foto", "registra un video", "fammi un video con le foto"
        //     (prima dei disegni: "fammi un video con le foto di ieri" non è un disegno da animare)
        MediaCommands.parse(t, now())?.let { return it }
        // --- disegnare: "disegna un gatto sulla luna", "crea un'immagine di…", "scarica il modello per disegnare"
        DrawCommands.parse(t)?.let { return it }
        // --- i file per te: "salvalo in un file", "salva la conversazione", "scrivimi un documento su…", "apri il file"
        fileCommand(t)?.let { return it }
        // --- "ripeti", "cosa hai detto?", "non ho capito": l'ultima cosa che ha detto SAM
        if (REPEAT.containsMatchIn(t)) return repeatLast()
        // --- "pensaci bene": prima ragiona passo per passo, poi risponde (anche sulla domanda di prima)
        reasonRequest(t)?.let { return it }
        // --- "sbagliato, la risposta giusta è…", "esatto": una lezione per la prossima volta
        feedback(t)?.let { return it }
        // --- memoria: ricordare, cercare, dimenticare, insegnare scorciatoie e regole, quello che ci siamo detti
        memoryCommand(raw)?.let { return it }
        // --- scorciatoie insegnate: "buonanotte" -> "spegni la torcia e metti la sveglia alle 7"
        aliasCommand(raw, depth)?.let { return it }
        // --- il documento che hai condiviso con SAM: leggi, riassumi, domande
        docCommand(t, raw)?.let { return it }
        // --- guardare con la fotocamera: "cosa vedi?", "leggimi questo", "guarda con la fotocamera", "mi vedi?"
        sightCommand(t, raw)?.let { return it }

        // --- controllo di SAM
        if (ci("\\b(spegniti|chiuditi|esci dal programma|vai a dormire)\\b").containsMatchIn(t))
            return Outcome.Quit
        if (ci("\\b(nuova conversazione|dimentica (la|questa)? ?conversazione|ricominciamo|azzera)\\b").containsMatchIn(t))
            return Outcome.NewConversation
        if (ci("\\b(cosa sai fare|cosa puoi fare|che cosa sai fare|aiuto|che comandi|come funzioni)\\b").containsMatchIn(t))
            return Outcome.Speak(aiuto)

        // --- 1.22: il nome dell'assistente: "da ora ti chiami Jarvis", "torna a chiamarti SAM", "impara come dico il tuo nome"
        renameCommand(t, raw)?.let { return it }
        // --- chi è SAM, chi è il proprietario
        if (ci("\\b(come ti chiami|qual è il tuo nome|il tuo nome|chi sei|tu chi sei)\\b").containsMatchIn(t))
            return Outcome.Speak("Mi chiamo SAM! Vivo qui nel tuo telefono: ti ascolto e ti rispondo senza bisogno di internet.")
        ci("^(?:io )?(?:mi chiamo|il mio nome è|il mio nome e|chiamami pure|da ora chiamami|d'ora in poi chiamami)\\s+(.+)").find(t)?.let {
            val n = Lang.extractName(it.groupValues[1]) ?: return Outcome.Speak(askOwnerName(first = false))
            mem.setOwnerName(n)
            memoryUrgent = true
            return Outcome.Speak("Ok, da ora ti chiamo $n.")
        }

        // --- modalità conversazione con SAM: "modalità conversazione", "parliamo", "basta conversazione"
        if (CHAT_OFF.containsMatchIn(t)) return Outcome.Chat(false)
        CHAT_ON.find(t)?.let { m ->
            // "parliamo di calcio": si comincia subito dall'argomento
            val tema = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
            return Outcome.Chat(true, opener = if (tema != null && Remember.words(tema).isNotEmpty()) raw else null)
        }
        // --- conversazione con un'altra intelligenza artificiale: "parla con ChatGPT di calcio"
        dialogueRequest(t)?.let { (chi, tema) -> return Outcome.Dialogue(chi, tema) }

        // --- torcia / vibrazione
        if (isTorch(t)) {
            val on = !off(t)
            return if (a.torch(on)) Outcome.Speak(if (on) "Torcia accesa." else "Torcia spenta.")
            else Outcome.Speak("Non riesco ad accendere la torcia su questo telefono.")
        }
        if (ci("^$FILL(?:(?:fai|fammi|puoi)\\s+)?vibra(?:re)?(?:\\s+(?:il telefono|un po'|un attimo|per favore))*$").containsMatchIn(t)) {
            a.vibrate(800); return Outcome.Handled()
        }

        // --- musica e video in riproduzione: pausa, riprendi, avanti, indietro (in silenzio)
        mediaCommand(t)?.let { cmd ->
            return if (a.media(cmd)) Outcome.Handled(endSession = true)
            else Outcome.Speak("Non riesco a controllare la musica.")
        }

        // --- strumenti esatti: date e ricorrenze, che ore sono nel mondo, conversioni, lettere, IVA e sconti, testa o croce.
        //     Il cervello IA piccolo li sbaglia spesso: qui si calcolano. Il cambio delle valute invece cambia ogni giorno
        Tools.answer(t)?.let { return Outcome.Speak(it) }
        if (Tools.isCurrency(t)) return Outcome.Speak("Il cambio delle valute cambia ogni giorno e senza internet non lo conosco. " + offerSearch(raw))

        // --- meteo: SAM non va su internet, ma apre l'app del meteo e te la legge
        weatherRequest(raw)?.let { return it }

        // --- batteria
        if ((t.contains("batteria") || t.contains("quanta carica")) &&
            !ci("\\bbatteri[ae] (?:della|dell'|del|delle|dei|di) (?!telefono|cellulare|smartphone|telefonino)").containsMatchIn(t))
            return Outcome.Speak(a.batteryText())

        // --- ora e data ("che ore sono a New York" l'hanno già presa gli strumenti)
        if (TIME_Q.containsMatchIn(t)) {
            val n = LocalDateTime.now()
            if (t.contains("giorno") || t.contains("data")) {
                val (quando, d) = when {
                    ci("\\bdopodomani\\b").containsMatchIn(t) -> "Dopodomani sarà" to n.plusDays(2)
                    ci("\\bdomani\\b").containsMatchIn(t) -> "Domani sarà" to n.plusDays(1)
                    ci("\\bl'altro ieri\\b").containsMatchIn(t) -> "L'altro ieri era" to n.minusDays(2)
                    ci("\\bieri\\b").containsMatchIn(t) -> "Ieri era" to n.minusDays(1)
                    else -> "Oggi è" to n
                }
                return Outcome.Speak("$quando ${Tools.dateText(d.toLocalDate())}.")
            }
            return Outcome.Speak(Tools.timeNow(n.hour, n.minute))
        }

        // --- sveglia: spegnere, posticipare, togliere (prima di "metti la sveglia alle…")
        if (ci("\\b(sveglia|sveglie|allarme|allarmi)\\b").containsMatchIn(t)) {
            if (ci("\\b(posticipa|rimanda|ritarda|ancora cinque minuti|snooze)\\b").containsMatchIn(t))
                return if (a.alarmScreen("snooze")) Outcome.Handled(endSession = true)
                else Outcome.Speak("Non riesco a posticipare la sveglia.")
            if (off(t)) {
                // una sveglia precisa o tutte: si apre l'elenco e la togli tu; altrimenti si spegne quella che suona o la prossima
                val precisa = ci("\\b(delle|alle|ore|tutte|domani|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica)\\b|\\d").containsMatchIn(t)
                if (!precisa && a.alarmScreen("dismiss")) return Outcome.Speak("Ok, sveglia spenta.", endSession = true)
                return if (a.alarmScreen("alarms")) Outcome.Speak("Ti ho aperto le sveglie: la togli con un tocco.", endSession = true)
                else Outcome.Speak("Non riesco ad aprire le sveglie.")
            }
        }
        if (off(t) && ci("\\b(timer|conto alla rovescia)\\b").containsMatchIn(t))
            return if (a.alarmScreen("timers")) Outcome.Speak("Ti ho aperto i timer: lo fermi con un tocco.", endSession = true)
            else Outcome.Speak("Non riesco ad aprire i timer.")

        // --- sveglia
        if (t.contains("sveglia") && ci("\\b(alle|per le|alle ore)\\b").containsMatchIn(t)) {
            val m = ci("\\b(?:alle|per le)\\s+(?:ore\\s+)?$numPattern(?:\\s*(?:[:.]|e)\\s*(\\d{1,2}|mezza|mezzo|un quarto|quarto|tre quarti|3 quarti))?").find(t)
            val ora = m?.let { n2i(it.groupValues[1]) }
            if (ora != null) {
                var h = ora
                val minTok = m.groupValues[2]
                val minuti = when (minTok) {
                    "" -> 0
                    "mezza", "mezzo" -> 30
                    "un quarto", "quarto" -> 15
                    "tre quarti", "3 quarti" -> 45
                    else -> minTok.toIntOrNull() ?: 0
                }.let { mm ->
                    // "alle sette meno un quarto" = 6:45, "alle otto meno dieci" = 7:50
                    val meno = ci("\\bmeno\\s+(un quarto|\\d{1,2})\\b").find(t.substring(m.range.first))
                    if (meno != null && mm == 0) {
                        h -= 1
                        60 - (if (meno.groupValues[1] == "un quarto") 15 else meno.groupValues[1].toInt().coerceIn(1, 59))
                    } else mm
                }
                if (h < 12 && ci("\\b(sera|pomeriggio|di notte|di sera)\\b").containsMatchIn(t)) h += 12
                return if (a.setAlarm(h, minuti))
                    Outcome.Speak("Sveglia impostata alle $h e ${"%02d".format(minuti)}.", endSession = true)
                else Outcome.Speak("Non riesco a impostare la sveglia.")
            }
        }

        // --- timer ("ricordami tra dieci minuti…" è un timer, non un ricordo)
        if (ci("\\b(timer|conto alla rovescia|avvisami tra|avvisami fra|promemoria tra|ricordami tra|ricordami fra|chiamami tra|chiamami fra|" +
               "svegliami tra|svegliami fra)\\b").containsMatchIn(t)) {
            var sec = 0
            if (t.contains("mezz'ora") || t.contains("mezzora")) sec = 1800
            for (mm in ci("$numPattern\\s*(or[ae]|minut\\w*|second\\w*)").findAll(t)) {
                val q = n2i(mm.groupValues[1]) ?: 0
                val u = mm.groupValues[2]
                sec += q * when {
                    u.startsWith("or") -> 3600
                    u.startsWith("minut") -> 60
                    else -> 1
                }
            }
            if (ci("\\bun'ora\\b").containsMatchIn(t)) sec += 3600
            if (sec > 0) {
                if (!a.setTimer(sec)) return Outcome.Speak("Non riesco ad avviare il timer.")
                val mi = sec / 60; val se = sec % 60
                val detta = when {
                    mi > 0 && se > 0 -> "$mi minuti e $se secondi"
                    mi > 0 -> "$mi minuti"
                    else -> "$se secondi"
                }
                return Outcome.Speak("Timer di $detta avviato.")
            }
        }

        // --- volume ("parla più forte", "alza la voce": la voce di SAM è il volume del telefono)
        val voce = VOICE_VOLUME.find(t)?.groupValues?.get(1)
        val tv = when {
            voce == null -> t
            ci("\\b(?:alza|forte|alt[ao]|non ti sento)\\b").containsMatchIn(voce) -> "alza il volume"
            else -> "abbassa il volume"
        }
        if ((tv.contains("volume") || ci("\\b(audio|muto|silenzioso|silenzia)\\b").containsMatchIn(tv)) &&
            !ci("\\b(?:volume|audio) (?:della|dell'|del|dello|delle|dei|di) (?!telefono|cellulare|suoneria|musica|chiamat|sveglia|notific|media|video|sam)").containsMatchIn(tv)) {
            @Suppress("NAME_SHADOWING") val t = tv
            val ring = t.contains("suoneria") || t.contains("suonerie")
            if (ci("\\b(togli|disattiva|spegni|leva)\\s+(il |la |l')?(muto|silenzioso|modalità silenziosa)\\b").containsMatchIn(t)) {
                a.setVolumePercent(50, ring)
                return Outcome.Speak("Volume al 50 per cento.")
            }
            val p: Int = when {
                ci("\\b(massimo|al massimo|tutto)\\b").containsMatchIn(t) -> 100
                ci("\\b(minimo|zero|muto|silenzioso|silenzia|togli il volume)\\b").containsMatchIn(t) || off(t) -> 0
                ci("\\b(met[àa]|medio|mezzo)\\b").containsMatchIn(t) -> 50
                ci("\\b(alza|aumenta|più alto|piu alto|su)\\b").containsMatchIn(t) ->
                    (a.currentVolumePercent(ring) + 20).coerceAtMost(100)
                ci("\\b(abbassa|diminuisci|più basso|piu basso|giù|giu)\\b").containsMatchIn(t) ->
                    (a.currentVolumePercent(ring) - 20).coerceAtLeast(0)
                else -> ci("\\b(?:al|a)\\s+(\\d{1,3})").find(t)?.groupValues?.get(1)?.toInt()?.coerceAtMost(100)
                    ?: return Outcome.Speak("Il volume è al ${a.currentVolumePercent(ring)} per cento.")
            }
            a.setVolumePercent(p, ring)
            return Outcome.Speak(if (p == 0) "Volume azzerato." else "Volume al $p per cento.")
        }

        // --- luminosità
        if (t.contains("luminosità") || t.contains("luminosita") || t.contains("schermo più") || t.contains("schermo piu")) {
            if (t.contains("automatic")) { a.setBrightnessAuto(); return Outcome.Speak("Luminosità automatica.") }
            val p = when {
                t.contains("massim") -> 100
                t.contains("minim") -> 5
                ci("met[àa]").containsMatchIn(t) -> 50
                ci("(\\d{1,3})").containsMatchIn(t) -> ci("(\\d{1,3})").find(t)!!.groupValues[1].toInt().coerceAtMost(100)
                ci("\\b(alza|aumenta|più|piu)\\b").containsMatchIn(t) -> 85
                ci("\\b(abbassa|diminuisci|meno|riduci)\\b").containsMatchIn(t) -> 30
                else -> 60
            }
            a.setBrightnessPercent(p)
            return Outcome.Speak("Luminosità al $p per cento.")
        }

        // --- l'enciclopedia sul telefono: "cosa dice l'enciclopedia su Napoli?", "cerca Garibaldi su Wikipedia".
        //     Senza enciclopedia "cerca su Wikipedia" resta una ricerca con il browser
        Encyclopedia.readRequest(t)?.let { subj ->
            if (encyclopedia) return Outcome.Wiki(subj)
            if (!ci("\\bwikipedia\\b").containsMatchIn(t))
                return Outcome.Speak("Non ho ancora l'enciclopedia sul telefono: la puoi mettere da ⚙︎, alla voce Enciclopedia.")
        }

        // --- internet (lo apre il browser: SAM non si collega mai)
        (ci("\\b(?:cerca|metti|guarda|fammi vedere|trova)\\s+(?:su\\s+)?youtube\\s+(.+)").find(t)
            ?: ci("\\b(?:cerca|metti|guarda)\\s+(.+?)\\s+su\\s+youtube").find(t))?.let {
            val q = it.groupValues[1].trim()
            return if (a.youtubeSearch(q)) Outcome.Speak("Cerco $q su YouTube.", endSession = true)
            else Outcome.Speak("Non riesco ad aprire YouTube.")
        }
        if (ci("^(?:apri|vai su|vai in|entra su|entra in|naviga su|naviga in|collegati a|apri il|vai sul)\\s+(?:internet|il browser|browser|chrome|google|la rete|il web|web)$").containsMatchIn(t) ||
            ci("^(?:internet|browser|vai online|naviga)$").containsMatchIn(t))
            return if (a.openBrowser()) Outcome.Speak("Apro internet.", endSession = true)
            else Outcome.Speak("Non trovo un browser sul telefono.")
        ci("^(?:apri|vai su|vai sul|vai al|entra nel|entra su|apri il)\\s+(?:sito\\s+(?:di\\s+|del\\s+)?)?([a-z0-9-]+(?:\\s*(?:punto|\\.)\\s*[a-z0-9-]+)+)$").find(t)?.let {
            val host = it.groupValues[1].replace(Regex("\\s*(?:punto|\\.)\\s*"), ".")
            return if (a.openUrl("https://$host")) Outcome.Speak("Apro $host.", endSession = true)
            else Outcome.Speak("Non trovo un browser sul telefono.")
        }
        // "cercalo su internet", "cercalo": l'argomento di prima, non la frase "cercalo"
        if (SEARCH_IT.containsMatchIn(t)) {
            val q = recentTopic()
            if (q == null) {
                pending = { ans -> if (Remember.words(ans).isEmpty()) Outcome.Speak("Va bene.") else searchFor(ans) }
                return Outcome.Speak("Cosa devo cercare?")
            }
            return searchFor(q)
        }
        // "fai una ricerca approfondita su…", "fammi un rapporto su…": come Kimi Researcher (rapporto in PDF con le fonti)
        deepQuery(t)?.let { q -> return Outcome.Web(q, deep = true) }
        searchQuery(t)?.let { q ->
            // "su google", "nel browser": si apre il browser come prima; altrimenti SAM cerca e risponde con le fonti
            if (BROWSER.containsMatchIn(t)) return if (a.webSearch(q)) Outcome.Speak("Cerco $q.", endSession = true)
                else Outcome.Speak("Non trovo un browser sul telefono.")
            return Outcome.Web(q)
        }

        // --- interruttori di sistema (Android non li fa cambiare da soli: apre il pannello)
        if (ci("\\b(accendi|attiva|spegni|disattiva|togli|metti|apri|chiudi)\\b").containsMatchIn(t) ||
            ci("^$FILL(?:la |il |le |i )?(?:wifi|wi-fi|wi fi|bluetooth|modalità aereo|aereo|gps|nfc|dati mobili|hotspot|non disturbare)$").containsMatchIn(t)) {
            val pannelli = listOf(
                Triple(listOf("wifi", "wi-fi", "wi fi", "wai fai"), "wifi", "il Wi-Fi"),
                Triple(listOf("bluetooth", "blutut", "blue tooth"), "bluetooth", "il Bluetooth"),
                Triple(listOf("dati mobili", "dati", "internet", "connessione", "hotspot"), "dati", "la connessione"),
                Triple(listOf("modalità aereo", "aereo"), "aereo", "la modalità aereo"),
                Triple(listOf("nfc"), "nfc", "l'NFC"),
                Triple(listOf("non disturbare"), "silenzioso", "il non disturbare"),
                Triple(listOf("posizione", "gps", "localizzazione"), "gps", "la posizione"),
            )
            for ((parole, key, nome) in pannelli) {
                if (parole.any { ci("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }) {
                    return if (a.openSystemPanel(key))
                        Outcome.Speak("Android non mi lascia cambiare $nome da solo: ti ho aperto il comando, basta un tocco.", endSession = true)
                    else Outcome.Speak("Non riesco ad aprire le impostazioni per ${nome.substringAfter(' ').substringAfter("'")}.")
                }
            }
        }

        // --- spegnere o riavviare il telefono: si apre il menu di spegnimento
        if (ci("\\b(spegni|spegnere|riavvia|riavviare)\\s+(il |lo )?(telefono|cellulare|smartphone)\\b").containsMatchIn(t))
            return if (a.globalAction("power")) Outcome.Speak("Ti ho aperto il menu di spegnimento.", endSession = true)
            else needAccessibility()

        // --- chiudere app e schermate: "chiudi la fotocamera", "esci da WhatsApp", "chiudi tutto"
        ci("$CMD_HEAD(?:chiudi|chiudere|chiudimi|chiudila|chiudilo|esci|uscire|esci fuori)\\b\\s*(.*)$").find(t)?.let { return close(it.groupValues[1]) }
        if (ci("$CMD_HEAD(?:$OFF)\\s+(?:la |l')?(?:fotocamera|camera)\\b").containsMatchIn(t)) return close("fotocamera")

        // --- schermo / navigazione di sistema (servizio di accessibilità)
        if (ci("\\b(blocca (lo schermo|il telefono)|schermo spento|spegni lo schermo|spegni schermo)\\b").containsMatchIn(t))
            return if (a.globalAction("lock")) Outcome.Handled(endSession = true)
            else Outcome.Speak("Per bloccare lo schermo devo attivare il servizio di accessibilità di SAM.")
        if (ci("^$FILL(?:(?:torna|tornare|vai|andare|torniamo)\\s+)?indietro(?:\\s+(?:di una pagina|di una schermata|per favore))?$").containsMatchIn(t))
            return if (a.globalAction("back")) Outcome.Handled(endSession = true) else needAccessibility()
        if (ci("\\b(schermata (home|principale)|vai alla home|torna alla home)\\b").containsMatchIn(t))
            return if (a.globalAction("home")) Outcome.Handled(endSession = true) else needAccessibility()
        if (ci("\\b(app recenti|applicazioni recenti|multitasking)\\b").containsMatchIn(t))
            return if (a.globalAction("recents")) Outcome.Handled(endSession = true) else needAccessibility()

        // --- posizione
        if (ci("^$FILL(?:(?:dimmi|sai dirmi|mi dici|sai)\\s+)?(?:dove sono|dove mi trovo|dove siamo)(?:\\s+(?:adesso|ora|esattamente|di preciso|in questo momento))*$|" +
               "\\b(?:la mia posizione|posizione attuale)\\b").containsMatchIn(t)) {
            return if (a.whereAmI()) Outcome.Speak("Ti apro la mappa sulla tua posizione.", endSession = true)
            else Outcome.Speak("Non riesco ad aprire la mappa.")
        }

        // --- navigazione ("portami a casa": dove abiti, se me l'hai detto)
        ci("(?:$CMD_HEAD|^$FILL(?:dimmi |sai |mi dici |mi dai |dammi )?(?:le )?)(?:portami|portarmi|naviga verso|naviga a|naviga fino|indicazioni per|indicazioni stradali per|" +
           "la strada per|strada per|come arrivo|come si arriva|come ci arrivo)\\s+(?:a |ad |al |alla |allo |in |verso |da )?(.+)").find(t)?.let {
            val dest = resolvePlace(it.groupValues[1].trim())
            return if (a.navigateTo(dest)) Outcome.Speak("Avvio la navigazione verso $dest.", endSession = true)
            else Outcome.Speak("Non riesco ad avviare la navigazione.")
        }

        // --- messaggi: WhatsApp (anche "scrivi a Mario che arrivo") e SMS
        parseMessage(t)?.let { (canale, chi, testo) -> return message(canale, chi, testo) }

        // --- chiamate (evita "si chiama", "come mi chiamo", "ti chiamo dopo")
        ci("$CMD_HEAD(?:chiama|chiamo|chiami|chiamare|richiama|richiamare|telefona|telefonare|fai una chiamata|fai una telefonata|" +
           "fare una chiamata|fare una telefonata)\\s+(?:a |ad |al |alla |allo |all')?(?:il numero\\s+|al numero\\s+)?(.+)").find(t)?.let {
            var chi = it.groupValues[1]
            val viaWhatsApp = ci("\\s+(?:su|con|via) whatsapp\\b").containsMatchIn(chi)
            chi = ci("\\s+(?:su|con|via) whatsapp\\b.*").replace(chi, "")
            return withContact(chi) { c -> askCall(c, viaWhatsApp) }
        }

        // --- foto dello schermo
        if (SCREENSHOT.containsMatchIn(t))
            return if (a.globalAction("screenshot")) Outcome.Handled(endSession = true) else needAccessibility()
        // --- foto con l'app Fotocamera del telefono ("scatta una foto", "fammi un selfie"). Per guardare SAM usa la sua,
        //     che si chiude da sola; l'app Fotocamera invece resta aperta finché non la chiudi, e intanto SAM può non sentirti.
        if (PHOTO_APP.containsMatchIn(t)) {
            val selfie = t.contains("selfie") || ci("\\b(?:frontale|anteriore)\\b").containsMatchIn(t)
            return if (a.openCamera(selfie)) Outcome.Speak("Ho aperto la fotocamera. Finché resta aperta potrei non sentirti: quando hai finito, chiudila.", endSession = true)
            else Outcome.Speak("Non riesco ad aprire la fotocamera.")
        }

        // --- notifiche
        if (t.contains("notifiche") || t.contains("notifica") ||
            ci("\\b(leggi i messaggi|ho (dei |nuovi )?messaggi|messaggi nuovi|nuovi messaggi|chi mi ha scritto|chi mi ha cercato)\\b").containsMatchIn(t))
            return Outcome.Speak(a.readNotifications())

        // --- appunti
        if (ci("\\b(leggi gli appunti|leggi appunti|cosa ho copiato|cosa c'è negli appunti)\\b").containsMatchIn(t)) {
            val c = a.clipboardGet()
            return Outcome.Speak(if (c.isBlank()) "Gli appunti sono vuoti." else "Negli appunti c'è: ${c.take(400)}")
        }
        ci("^copia\\s+(.+)").find(t)?.let { a.clipboardSet(it.groupValues[1]); return Outcome.Speak("Copiato negli appunti.") }

        // --- musica
        if (ci("\\b(metti|fai partire|avvia|riproduci)\\s+(la\\s+)?musica\\s*$").containsMatchIn(t) ||
            ci("^(metti|fai partire|avvia|riproduci)\\s+(un po' di|della)\\s+musica\\s*$").containsMatchIn(t)) {
            return if (a.playMusic()) Outcome.Speak("Apro la musica.", endSession = true)
            else Outcome.Speak("Non trovo un'app di musica.")
        }
        // "riproduci Albachiara", "fammi sentire Vasco Rossi", "metti la canzone Volare": la cerca e la suona
        (ci("^(?:riproduci|suona|fammi sentire|fammi ascoltare|voglio sentire|voglio ascoltare)\\s+(?:la canzone |il brano |una canzone di |le canzoni di |la musica di |qualcosa di |l'album |la playlist )?(.+)").find(t)
            ?: ci("^(?:metti|fai partire)\\s+(?:la canzone|il brano|una canzone di|le canzoni di|la musica di|qualcosa di|l'album|la playlist|musica di)\\s+(.+)").find(t))?.let {
            val q = it.groupValues[1].trim()
            return if (a.playSearch(q)) Outcome.Speak("Metto $q.", endSession = true)
            else if (a.youtubeSearch(q)) Outcome.Speak("Cerco $q su YouTube.", endSession = true)
            else Outcome.Speak("Non trovo un'app per la musica.")
        }

        // --- calcoli
        if (ci("\\b(quanto fa|calcola|quanto è|quanto e|quanto vale|quant'è)\\b").containsMatchIn(t)) {
            val r = Calc.eval(raw)
            if (r != null) return Outcome.Speak("Fa ${Calc.say(r)}.")
        }

        // --- apertura app (anche con un nome insegnato: "quando dico banca intendo Intesa")
        ci("$CMD_HEAD(?:apri|aprire|aprimi|avvia|avviare|lancia|lanciare|lanciami|vai su|fammi aprire)\\s+(?:l'app\\s+|l'applicazione\\s+|la\\s+|il\\s+|lo\\s+|le\\s+)?(.+)").find(t)?.let {
            val nome = resolveName(ci("(?:\\s+(?:per favore|per piacere|grazie|subito|adesso|ora))+$").replace(it.groupValues[1].trim(), ""))
            return if (a.openApp(nome)) Outcome.Speak("Apro $nome.", endSession = true)
            else Outcome.Speak("Non trovo l'app $nome.")
        }

        // --- "devi spegnere la torcia", "voglio aprire whatsapp": il comando all'infinito, detto come imperativo
        if (depth < 2) MODAL_INF.find(t)?.let { m ->
            INF[m.groupValues[1].lowercase()]?.let { v ->
                val o = route(v + m.groupValues[2], depth + 1)
                if (o !is Outcome.NeedLlm) return o
            }
        }

        // --- domande a cui risponde un ricordo: "dove ho parcheggiato?", "quando ho il dentista?"
        memoryAnswer(fixAsr(raw))?.let { return it }

        // --- tutto il resto: all'IA locale (e intanto impara quello che dici di te).
        // Con un documento aperto la domanda va al documento (l'IA risponde normalmente se non c'entra).
        // Una cosa raccontata resta a portata per qualche minuto: "ricordatelo" la salva.
        if (isStatement(raw)) { lastTold = raw; lastToldAt = now() }
        learn(raw)
        // 1.23: la mente coglie gli impegni detti di passaggio ("domani ho il dentista alle 16") e lo stato d'animo
        mind?.perceive(raw, now())?.let { p ->
            for (pl in p.plans) mindNotes.add("me lo segno: ${mind!!.relDay(pl.day, java.time.Instant.ofEpochMilli(now()).atZone(mind!!.zone).toLocalDate())} " +
                Plans.you(pl.text) + (pl.time?.let { " alle ${Plans.time(it)}" } ?: ""))
        }
        if (docOpen()) return Outcome.Doc("ask", raw)
        // 1.23: pensiero lento: alle domande difficili prima ci pensa (ragionamento passo per passo), poi risponde
        if (mind?.thinkAuto == true && Thinking.hard(raw)) return Outcome.NeedLlm(raw, reason = true)
        return Outcome.NeedLlm(raw)
    }

    // ------------------------------------------------------------------ la tua voce
    private val VOICE_ENROLL = ci(
        "^(?:impara|imparare|imparati|registra|memorizza|memorizzati|salva|insegnati|studia|ascolta bene)\\s+" +
        "(?:la mia voce|alla mia voce|alla voce|la voce|la voce di \\S+|come parlo)$"
    )
    private val VOICE_RECOGNIZE = ci("^(?:riconosci|riconoscere|riconoscimi dalla) (?:la mia )?voce$|^riconosci la mia voce$")
    private val VOICE_ONLY = ci(
        "^(?:da ora |d'ora in poi |adesso |ora )?(?:ascolta|rispondi|obbedisci|ubbidisci|dai retta)\\s+solo\\s+" +
        "(?:a me|me|alla mia voce|al proprietario|a \\S+)$|^solo la mia voce$|^(?:attiva|metti) (?:la modalità )?solo (?:la mia voce|me)$"
    )
    private val VOICE_ALL = ci(
        "^(?:da ora |d'ora in poi |adesso |ora )?(?:ascolta|rispondi|obbedisci|ubbidisci|dai retta)\\s+" +
        "(?:a tutti|anche (?:agli altri|a tutti|alle altre persone))$|^(?:disattiva|togli) (?:la modalità )?solo (?:la mia voce|me)$"
    )
    /**
     * "Mi riconosci?", "sai chi sono?", "ora mi riconosci?", "con chi stai parlando?": chi sei (il nome) e se ti riconosce
     * dalla voce. Prima andavano al cervello IA, che non sa chi parla e rispondeva a vuoto. "Mi riconosco" è
     * "mi riconosci" sentito male dal riconoscitore.
     */
    private val VOICE_CHECK = ci(
        "^(?:(?:ma|e|allora|adesso|ora|quindi|ok|okay|dimmi|senti|ehi|dai)\\s+)*" +
        "(?:chi sta parlando|chi ti sta parlando|chi ti parla|chi sono(?: io)?|(?:lo )?sai chi (?:sono(?: io)?|ti sta parlando|ti parla)|" +
        "(?:lo )?sai con chi (?:stai parlando|parli)|con chi (?:stai parlando|parli)|" +
        "mi (?:riconosci|riconosco|hai riconosciuto|riconoscerai)|sono io|riconosci se sono io|riconosci chi sono|" +
        "(?:indovina|capisci|hai capito) chi sono(?: io)?)" +
        "(?:\\s+(?:ora|adesso|ancora|io|sam|stavolta|questa volta|dalla (?:mia )?voce|o no|oppure no|vero|allora|almeno))*$"
    )
    /** "Mi senti?", "ci sei?": la risposta la dà SAM, subito (il cervello IA rispondeva a caso). */
    private val HEAR_Q = ci(
        "^(?:(?:ma|e|allora|adesso|ora|ok|okay|ehi|dai|pronto)\\s+)*" +
        "(?:mi senti|mi stai sentendo|mi ascolti|mi stai ascoltando|ci sei|sei (?:lì|li|qui|qua|sveglio|sveglia|attivo|attiva))" +
        "(?:\\s+(?:ora|adesso|ancora|sam|bene|o no|oppure no|vero))*$"
    )
    private val VOICE_FORGET = ci("^(?:dimentica|cancella|elimina|scordati|resetta)\\s+(?:la mia voce|l'impronta della (?:mia )?voce)$")

    private fun voiceCommand(t: String): Outcome? {
        if (HEAR_Q.containsMatchIn(t))
            return Outcome.Speak(if (ci("\\b(?:ci sei|sei)\\b").containsMatchIn(t)) "Sì, ci sono. Dimmi pure." else "Sì, ti sento. Dimmi pure.")
        if (VOICE_RECOGNIZE.containsMatchIn(t)) return Outcome.VoiceCmd("recognize")
        if (VOICE_ENROLL.containsMatchIn(t)) return Outcome.VoiceCmd("enroll")
        if (VOICE_ONLY.containsMatchIn(t)) return Outcome.VoiceCmd("only_on")
        if (VOICE_ALL.containsMatchIn(t)) return Outcome.VoiceCmd("only_off")
        if (VOICE_CHECK.containsMatchIn(t)) return Outcome.VoiceCmd("check")
        if (VOICE_FORGET.containsMatchIn(t)) {
            pending = { ans ->
                if (Lang.isYes(ans)) Outcome.VoiceCmd("forget")
                else Outcome.Speak("Va bene, la tengo.", endSession = true)
            }
            return Outcome.Speak("Vuoi che dimentichi la tua voce? Poi dovrai insegnarmela di nuovo. Confermi?")
        }
        return null
    }

    private val ENC_ABOUT = ci("\\b(?:enciclopedia|wikipedia|wiki)\\b")
    private val ENC_ASK = ci("\\b(?:hai|ce l'hai|c'è|c'e|leggi|la leggi|leggerla|leggila|usi|la usi|usarla|vedi|la vedi|trovi|la trovi|funziona|" +
        "caricat[oa]|ho caricato|ti ho caricato|messo|messa|ho messo|ti ho messo|installat[oa]|scaricat[oa]|ho scaricato|a disposizione|" +
        "riesci|sai|consulti|consultarla|accesso|pronta|è pronta|attiva)\\b")
    // "puoi leggere i file?" arriva senza "puoi" (lo toglie handle): l'infinito è la domanda, "leggi il documento" invece è un comando
    private val FILES_ASK = ci("^(?:sai |riesci a |sei capace di |hai la possibilità di )?(?:leggere|aprire|leggermi)\\s+" +
        "(?:i |un |dei |il |le |gli |delle |una )?(?:file|documenti|documento|pdf|libri|libro|word|excel|testi)(?:\\s+\\S+){0,3}$")
    private val WHY_SILENT = ci("\\b(?:perché non (?:mi )?(?:rispondi|hai risposto|rispondevi|senti|sentivi|ascolti)|non mi (?:rispondi|hai risposto|" +
        "rispondevi|senti|sentivi|ascolti)|ti stavo chiamando|ti ho chiamato(?: e)? non|ti chiamavo e non|non rispondevi)\\b")

    /** Le domande su quello che SAM sa fare: la risposta vera, non quella del cervello. null = non lo è. */
    private fun abilityQuestion(t: String): Outcome? {
        if (ENC_ABOUT.containsMatchIn(t) && ENC_ASK.containsMatchIn(t) && Encyclopedia.readRequest(t) == null &&
            !ci("\\b(?:più nuova|più recente|nuova versione|aggiorn\\p{L}*)\\b").containsMatchIn(t)) {
            val info = encyclopediaInfo
            return Outcome.Speak(
                if (encyclopedia && info != null)
                    "Sì, ce l'ho: $info. La uso da solo quando mi chiedi chi era qualcuno, cos'è una cosa o dove si trova un posto. " +
                    "Se vuoi che te ne legga una voce, di': cosa dice l'enciclopedia su, e l'argomento."
                else "Non la vedo ancora. Nella configurazione, con l'ingranaggio, al punto 8 Enciclopedia, tocca: scarica Wikipedia " +
                    "in italiano, e la scarico io col Wi-Fi. Se l'hai già scaricata tu, tocca invece: scegli il file dell'enciclopedia, " +
                    "quello che finisce con punto zim. Poi chiedimelo di nuovo."
            )
        }
        if (!docOpen() && FILES_ASK.containsMatchIn(t))
            return Outcome.Speak("Sì. Da qualsiasi app, per esempio File, WhatsApp o Gmail, tocca Condividi e scegli: Leggi con SAM. " +
                "Poi dimmi se leggerlo, riassumerlo o cosa vuoi sapere: rispondo con quello che c'è scritto.")
        if (WHY_SILENT.containsMatchIn(t) && ci("\\b(?:fotocamera|camera|foto)\\b").containsMatchIn(t))
            return Outcome.Speak("Quando l'app Fotocamera è aperta tiene lei il microfono, e io non ti sento: chiudila quando hai finito. " +
                "Per farmi guardare qualcosa di' invece: guarda, oppure: cosa vedi? Uso la mia fotocamera e la chiudo da solo.")
        if (WHY_SILENT.containsMatchIn(t))
            return Outcome.Speak("Scusa, adesso ti sento. Mentre penso a una risposta o parlo non posso sentirti: per interrompermi tocca Parla. " +
                "Se ti capita ancora che non rispondo, guarda la scritta sotto il cerchio: dice se un'altra app tiene il microfono.")
        return null
    }

    // ------------------------------------------------------------------ come parla e come pensa SAM (dalla 1.10)
    // la voce: un'altra voce del telefono (solo quelle che funzionano senza internet), più lenta o veloce, più grave o acuta
    private val VOICE_NEXT = ci(
        "^$FILL(?:(?:mi |ti )?(?:puoi |potresti |vuoi )?)?(?:cambia|cambiami|cambiare|prova|provane|provare|dammi|darmi|usa|usare|" +
        "fammi sentire|fammene sentire)\\s+(?:(?:la tua|la|una|un'altra|un altra|l'altra|altra)\\s*)?voce(?:\\s+(?:diversa|nuova))?" +
        "(?:\\s+(?:per favore|sam|dai|grazie))*$|^$FILL(?:un'altra|un altra|altra) voce(?:\\s+(?:per favore|sam|dai))*$|" +
        "^$FILL(?:non mi piace|odio|detesto|mi dà fastidio|mi da fastidio) (?:la tua voce|questa voce|come suona la tua voce)\\b.*$|" +
        "^$FILL(?:la tua voce|questa voce) (?:è|e|mi sembra|sembra) (?:troppo |un po' )?(?:brutta|metallica|robotica|fastidiosa|strana|finta|" +
        "antipatica|da robot)\\b.*$|^$FILL(?:hai|ha) (?:una|la) voce (?:troppo |un po' )?(?:brutta|metallica|robotica|da robot|strana|finta|antipatica)\\b.*$"
    )
    private val VOICE_PREV = ci(
        "^$FILL(?:torna|tornare|rimetti|rimettere|ridammi|riprendi|usa)\\s+(?:alla|la|a) (?:tua )?voce (?:di prima|precedente|vecchia|che avevi)" +
        "(?:\\s+(?:per favore|sam))*$"
    )
    private val VOICE_RESET = ci(
        "^$FILL(?:(?:rimetti|torna (?:a|alla)|usa|ripristina|rimettiti)\\s+(?:la tua |la )?voce (?:normale|di sempre|originale|predefinita|standard)|" +
        "voce normale)(?:\\s+(?:per favore|sam))*$"
    )
    private val VOICE_WHICH = ci("^$FILL(?:(?:che|quale) voce (?:usi|hai|stai usando)|(?:quante|quali|che) voci (?:hai|conosci|ci sono|puoi usare))$")
    private val SLOW_WORDS = setOf("lentamente", "lento", "lenta", "piano", "adagio", "con calma", "rallenta")
    private val SPEED_WORDS = "lentamente|lento|lenta|piano|adagio|con calma|velocemente|veloce|in fretta|svelto|svelta|rapido|rapida|rapidamente"
    private val RATE = ci(
        "^$FILL(?:(?:mi |ti )?(?:puoi |potresti )?(?:parla|parlami|parlare|parlarmi|leggi|leggere|leggimi)\\s+)?" +
        "(?:un po' |un pochino |ancora )?(?:più|piu) ($SPEED_WORDS)(?:\\s+(?:per favore|sam|dai|grazie|quando parli|che non ti capisco|che non capisco))*$|" +
        "^$FILL(?:parli|vai|leggi) (?:troppo|un po' troppo) ($SPEED_WORDS)(?:\\s+(?:sam|per me))*$|" +
        "^$FILL(rallenta|accelera|(?:parla|parlami|leggi|leggimi) con (?:più |un po' più di )?calma)(?:\\s+(?:un po'|per favore|sam|quando parli))*$"
    )
    // "sei troppo lento", "ci metti troppo a rispondere": c'è un cervello più veloce?
    private val SLOW_COMPLAINT = ci(
        "^$FILL(?:ma |però )?(?:sei (?:sempre |un po' |davvero )?(?:troppo |così )?(?:lento|lenta)(?: a rispondere)?|" +
        "ci metti (?:sempre |davvero )?troppo(?: tempo)?(?: a rispondere)?|rispondi (?:troppo |così )?lentamente)(?:\\s+(?:sam|eh|però|oggi))*$"
    )
    private val PITCH = ci(
        "^$FILL(?:(?:usa|fai|fammi|metti|mettimi|parla con|parlami con)\\s+(?:una |la )?)?voce (?:un po' )?più " +
        "(grave|profonda|calda|bassa di tono|acuta|squillante|sottile|alta di tono)(?:\\s+(?:per favore|sam))*$|" +
        "^$FILL(abbassa|alza) il tono(?: della (?:tua )?voce)?(?:\\s+(?:per favore|sam))*$"
    )
    // la fantasia del cervello (la temperatura con cui sceglie le parole)
    private val CREATIVE = ci(
        "^$FILL(?:(?:sii|diventa|essere|cerca di essere|prova a essere|prova ad essere|devi essere|dovresti essere)\\s+)?(?:un po' |ancora )?" +
        "(più|meno) (creativo|creativa|fantasioso|fantasiosa|inventivo|inventiva|preciso|precisa|accurato|accurata|rigoroso|rigorosa)" +
        "(?:\\s+(?:nelle risposte|quando rispondi|per favore|sam|con me))*$|" +
        "^$FILL(?:usa|metti|mettici|usa un po')\\s+(più|meno) fantasia(?:\\s+(?:nelle risposte|quando rispondi|per favore))*$|" +
        "^$FILL((?:attieniti|stai|resta|rimani|tieniti) ai fatti|non inventare(?: (?:le cose|niente|nulla|cose|risposte))?|smettila di inventare(?: le cose)?)$|" +
        "^$FILL((?:fantasia|creatività) normale|(?:rimetti|torna (?:a|alla)) (?:la )?(?:fantasia|creatività) (?:normale|di prima|come prima))$"
    )
    // quale cervello usare, tra quelli sul telefono (o da scaricare col Wi-Fi)
    private val BRAIN_NOUN = "(?:cervello|modello|cervello ia|cervello artificiale)"
    private val BRAIN_WHICH = ci(
        "^$FILL(?:(?:che|quale|qual è il|quale è il) $BRAIN_NOUN (?:usi|hai|stai usando|c'è dentro|hai adesso)|" +
        "(?:quanti|quali|che) (?:cervelli|modelli) (?:hai|ci sono|conosci|puoi usare)|" +
        "(?:come si chiama|qual è|quale è) il tuo $BRAIN_NOUN|(?:su che|con che|con quale) $BRAIN_NOUN (?:funzioni|giri|ragioni|pensi))$"
    )
    private val BRAIN_USE = "(?:usa|usare|passa a|passa al|passare al|metti|mettiti|prendi|carica|caricare|torna a|torna al|scarica|scaricare|scaricami|" +
        "installa|installare)"
    private val BRAIN_FAST = ci("^$FILL(?:(?:mi |ti )?(?:puoi |potresti )?)?$BRAIN_USE\\s+(?:il |un |l')?$BRAIN_NOUN (?:più )?" +
        "(?:veloce|leggero|rapido|piccolo|svelto)(?:\\s+(?:per favore|sam))*$")
    private val BRAIN_SMART = ci("^$FILL(?:(?:mi |ti )?(?:puoi |potresti )?)?$BRAIN_USE\\s+(?:il |un |l')?$BRAIN_NOUN (?:più )?" +
        "(?:intelligente|bravo|potente|grande|migliore|completo|forte|sveglio)(?:\\s+(?:per favore|sam))*$")
    private val BRAIN_OTHER = ci(
        "^$FILL(?:(?:cambia|cambiare|cambiami)\\s+(?:il |di )?$BRAIN_NOUN|(?:usa|usare|passa all'|passa al|torna al|rimetti|metti)\\s+" +
        "(?:l'|il )?(?:altro $BRAIN_NOUN|$BRAIN_NOUN (?:di prima|precedente|vecchio)))(?:\\s+(?:per favore|sam))*$"
    )
    // il cervello per programmare (GGUF): "usa il cervello per programmare", "scarica il cervello per il codice"
    private val BRAIN_CODER = ci(
        "^$FILL(?:(?:mi |ti )?(?:puoi |potresti )?)?$BRAIN_USE\\s+(?:il |un |l')?$BRAIN_NOUN\\s+(?:per (?:programmare|il codice|scrivere (?:il )?codice|la programmazione)|" +
        "(?:da |del )?programmatore|coder|che programma)(?:\\s+(?:per favore|sam))*$"
    )
    // 1.18: la memoria condivisa con SAM per PC (stessa rete Wi-Fi)
    private val PC_PAIR = ci("^$FILL(?:collega|collegati a|abbina|abbinati a|associa)\\s+(?:il |al |col |con il )?(?:computer|pc|portatile)" +
        "(?:\\s+(?:con |usando )?(?:il )?codice)?\\s*[:,]?\\s*((?:\\d|zero|uno|due|tre|quattro|cinque|sei|sette|otto|nove|\\s|,|-)+)$")
    private val PC_SYNC = ci("^$FILL(?:sincronizza|sincronizzare|allinea|aggiorna|condividi|manda|passa)\\s+(?:la |le |i )?(?:memoria|ricordi|cose che sai)" +
        "(?:\\s+(?:con|col|al|sul|nel|tra|fra)\\s+(?:il |l')?(?:computer|pc|portatile|telefono e (?:il )?(?:computer|pc)))$|" +
        "^$FILL(?:sincronizzati|sincronizza(?:ti)?) (?:con|col) (?:il )?(?:computer|pc)$")
    private val DIGITS = mapOf("zero" to '0', "uno" to '1', "due" to '2', "tre" to '3', "quattro" to '4', "cinque" to '5', "sei" to '6', "sette" to '7', "otto" to '8', "nove" to '9')

    /** "uno due tre 4 5 6" → "123456" */
    fun spokenDigits(s: String): String = s.split(Regex("[\\s,-]+")).filter { it.isNotBlank() }.joinToString("") { w -> DIGITS[w.lowercase()]?.toString() ?: w.filter { it.isDigit() } }

    // 1.15: SAM sceglie da solo il cervello per ogni lavoro (si può spegnere)
    private val MODELS_AUTO_ON = ci(
        // con "tu" basta il verbo ("gestisci tu i modelli"); senza, serve il come ("…da solo", "…in base a quello che devi fare"):
        // "cambia il cervello" da solo resta il comando di sempre (passa all'altro)
        "^$FILL(?:gestisci|gestire|scegli|scegliere|cambia|cambiare) tu (?:da sol[oa] )?(?:i |il |l')?(?:modell[oi]|cervell[oi])" +
        "(?: (?:da sol[oa]|in automatico|automaticamente|giust[oi]|in base a (?:quello che|cosa|ciò che) (?:devi fare|fai|serve|ti chiedo)))*$|" +
        "^$FILL(?:gestisci|gestire|scegli|scegliere|cambia|cambiare) (?:da sol[oa] )?(?:i |il |l')?(?:modell[oi]|cervell[oi])" +
        "(?: (?:da sol[oa]|in automatico|automaticamente|tu|giust[oi]|in base a (?:quello che|cosa|ciò che) (?:devi fare|fai|serve|ti chiedo)))+$|" +
        "^$FILL(?:scegli|decidi) tu (?:quale|che) (?:cervello|modello) usare$"
    )
    private val MODELS_AUTO_OFF = ci(
        "^$FILL(?:non (?:cambiare|scegliere|gestire|scaricare) (?:più )?(?:il |i )?(?:cervell[oi]|modell[oi])(?: da sol[oa]| in automatico)?|" +
        "usa sempre lo stesso (?:cervello|modello)|smetti di cambiare (?:il )?(?:cervello|modello)(?: da sol[oa])?)$"
    )
    private val BRAIN_INSTALL = ci(
        "^$FILL(?:(?:installa|installare|installami|metti|usa|prova)\\s+(?:il |al |l')?$BRAIN_NOUN nuovo|(?:aggiorna|aggiornare|aggiornami) " +
        "(?:il tuo |il )?$BRAIN_NOUN)(?:\\s+(?:adesso|ora|per favore|sam))*$"
    )
    // "puoi modificare il tuo cervello?", "migliorati da solo", "riaddestrati": la risposta vera, con quello che SAM può cambiare
    private val BRAIN_SELF = ci(
        "\\b(?:modificare|modifica|modificarti|modificati|modifichi|cambia|cambiare|cambiarti|migliorare|migliora|migliorarti|migliorati|" +
        "riaddestrare|riaddestrarti|riaddestrati|addestrare|addestrarti|addestrati|allenare|allenarti|allenati|potenziare|potenziarti|potenziati|" +
        "evolvere|evolverti|evolvi|riprogrammare|riprogrammarti|riprogrammati|riscrivere|riscriverti)\\b(?:\\s+\\S+){0,2}?\\s+" +
        "(?:il tuo cervello|il cervello|tuo cervello|te stesso|te stessa|i tuoi pesi|il tuo modello|la tua intelligenza|il tuo codice|il tuo programma)\\b|" +
        "^$FILL(?:(?:puoi|sai|riesci a|potresti) )?(?:imparare|migliorare|migliorarti|evolvere|evolverti|crescere|riaddestrarti|addestrarti) " +
        "(?:cose )?da sol[oa](?:\\s+\\S+){0,2}$|^$FILL(?:migliorati|evolvi|evolviti) da sol[oa]$|" +
        "^$FILL(?:riaddestrati|addestrati|riprogrammati|riaddestrarti|addestrarti)(?: da sol[oa])?$|" +
        "^$FILL(?:ti |te )?(?:puoi|sai|riesci a|potresti) (?:migliorare|modificare|evolvere|riaddestrare|addestrare|riprogrammare|cambiare)(?: da sol[oa])?$"
    )
    // l'autoapprendimento: SAM rilegge da solo le tue frasi e si segna le cose importanti
    private val LEARN_WHAT = ci(
        "^$FILL(?:(?:cosa|che cosa|che|quali cose) (?:hai imparato|hai capito|sai) (?:da sol[oa]|tu da sol[oa]|di me da sol[oa]|da sol[oa] su di me|" +
        "da sol[oa] di me|(?:oggi )?(?:da sol[oa] )?rileggendo(?: le nostre chiacchierate)?)|cos'hai imparato da sol[oa](?: su di me| di me)?|" +
        "(?:dimmi|elenca|leggimi) (?:cosa|le cose che) hai imparato da sol[oa])$"
    )
    private val LEARN_OFF = ci(
        "^$FILL(?:smetti di imparare(?: (?:cose|niente))? da sol[oa]|non imparare (?:più |niente |nulla )?(?:più )?da sol[oa]|" +
        "(?:spegni|disattiva|togli|ferma) (?:l'autoapprendimento|l'apprendimento automatico|l'imparare da sol[oa])|niente (?:più )?autoapprendimento)" +
        "(?:\\s+(?:per favore|sam))*$"
    )
    private val LEARN_ON = ci(
        "^$FILL(?:(?:ri)?(?:comincia|inizia|torna|riprendi) (?:a |ad )?imparare da sol[oa]|impara da sol[oa](?: (?:dalle nostre chiacchierate|su di me|di me))?|" +
        "(?:accendi|attiva|riattiva) (?:l'autoapprendimento|l'apprendimento automatico))(?:\\s+(?:per favore|sam))*$"
    )
    // gli aggiornamenti da internet (solo col Wi-Fi): la Wikipedia nuova e il cervello nuovo
    private val UPD_ON = ci(
        "^$FILL(?:aggiornati (?:da solo|in automatico|automaticamente)|(?:attiva|riattiva|accendi) gli aggiornamenti(?: automatici)?)" +
        "(?:\\s+(?:per favore|sam))*$"
    )
    private val UPD_OFF = ci(
        "^$FILL(?:non aggiornarti (?:più )?(?:da solo|in automatico)|smetti di aggiornarti(?: da solo)?|(?:disattiva|spegni|togli) gli aggiornamenti" +
        "(?: automatici)?|niente (?:più )?aggiornamenti(?: automatici)?|non scaricare più niente(?: da solo)?)(?:\\s+(?:per favore|sam))*$"
    )
    private val UPD_STATUS = ci(
        "^$FILL(?:a che punto (?:è|sei con|siamo con) (?:l'aggiornamento|lo scaricamento|il download|lo scarico)|" +
        "come (?:va|procede) (?:l'aggiornamento|lo scaricamento|il download)|quanto manca (?:all'aggiornamento|allo scaricamento|alla fine del download)|" +
        "(?:cosa )?stai scaricando(?: qualcosa)?|hai finito di scaricare(?:\\s+\\S+){0,3})$"
    )
    private val UPD_CHECK = ci(
        "^$FILL(?:(?:controlla|cerca|verifica|guarda)(?: se ci sono)? (?:gli |degli |nuovi |i nuovi )?aggiornamenti|ci sono (?:degli |nuovi )?aggiornamenti|" +
        "aggiornati|aggiorna(?:re)? (?:tutto|l'enciclopedia|wikipedia|la wikipedia)|c'è (?:una )?(?:wikipedia|enciclopedia) (?:più )?nuova|" +
        "scarica (?:la )?(?:wikipedia|enciclopedia) (?:più )?nuova)(?:\\s+(?:adesso|ora|per favore|sam))*$"
    )
    // le lamentele su come risponde: SAM propone da solo la regola per correggersi (la tiene solo se dici sì)
    private val COMPLAINTS: List<Pair<Regex, String>> = listOf(
        ci("\\b(?:parli|chiacchieri|ti dilunghi) (?:sempre |un po' |davvero )?troppo\\b|" +
           "\\b(?:risposte|frasi|discorsi|spiegazioni) (?:sono )?(?:sempre |un po' |davvero )?troppo (?:lunghe|lunghi)\\b|" +
           "\\bsei (?:sempre |un po' |davvero )?troppo (?:prolisso|prolissa|logorroico|logorroica|lungo|lunga)\\b|" +
           "\\bsei (?:un |una )?(?:prolisso|prolissa|logorroico|logorroica)\\b|\\b(?:dici|usi) (?:sempre )?troppe parole\\b|" +
           "\\bla fai (?:sempre )?troppo lunga\\b") to "Rispondi in modo breve, con poche frasi",
        ci("\\b(?:rispondi|sei) (?:sempre |un po' |davvero )?troppo (?:secco|secca|sbrigativo|sbrigativa|brusco|brusca|stringato|stringata|" +
           "corto|corta)\\b|\\brisposte (?:sono )?(?:sempre |un po' )?troppo (?:corte|secche|stringate|brevi)\\b") to
            "Spiegami le cose con un po' più di calma e di dettagli",
        ci("\\bsei (?:sempre |un po' |davvero |così )?(?:troppo )?(?:formale|distaccato|distaccata|impostato|impostata|ingessato|ingessata)\\b|" +
           "\\bsei (?:sempre |un po' |davvero )?(?:troppo|così) (?:freddo|fredda|rigido|rigida)\\b|" +
           "\\b(?:parli|rispondi|sembri) (?:proprio )?(?:come )?(?:un robot|una macchina|un centralino|un libro stampato|un risponditore)\\b|" +
           "\\bsei (?:proprio )?(?:un robot|una macchina)\\b") to "Parlami in modo caldo e spontaneo, come un amico",
        ci("\\bsei (?:sempre |un po' |davvero |così )?(?:troppo )?(?:noioso|noiosa|monotono|monotona|barboso|barbosa)\\b|" +
           "\\bsei (?:sempre |un po' |davvero )?(?:troppo|così) (?:serio|seria|pesante)\\b|" +
           "\\bnon (?:sei|sei mai) (?:per niente )?(?:simpatico|simpatica|spiritoso|spiritosa|divertente)\\b|\\bnon (?:mi )?fai (?:mai )?ridere\\b") to
            "Sii più allegro e spiritoso, con qualche battuta",
        ci("\\b(?:mi )?fai (?:sempre |troppe |sempre troppe |un sacco di )domande\\b|\\bsmettila di (?:farmi|fare) (?:sempre )?domande\\b|" +
           "\\bbasta (?:con le|fare|farmi) domande\\b") to "Non farmi domande alla fine delle risposte, se non servono",
        ci("\\b(?:mi chiami|ripeti|dici|usi) (?:sempre |troppo |troppe volte |in continuazione |ogni volta )(?:per nome|il mio nome)\\b|" +
           "\\bsmettila di (?:chiamarmi per nome|ripetere il mio nome|dire il mio nome)\\b") to "Non chiamarmi per nome, se non serve",
        ci("\\b(?:ti scusi|chiedi scusa) (?:troppo|sempre|in continuazione|ogni volta)\\b|\\bsmettila di (?:scusarti|chiedere scusa)\\b") to
            "Non scusarti in continuazione"
    )
    // "non mi piace quando mi rispondi così": la regola la scrive il cervello (poi SAM chiede se tenerla)
    private val COMPLAINT_GENERIC = ci(
        "^$FILL(?:però |ma |sai |sai che )?(?:non mi piace|non mi piacciono|mi dà fastidio|mi da fastidio|mi danno fastidio|odio|detesto|non sopporto|" +
        "mi irrita|mi infastidisce|mi urta) (?:(?:quando|come|il modo in cui|che|se) (?:tu )?(?:mi |ti |me lo |me la )?(\\p{L}+)\\b|" +
        "(?:il tuo|la tua|i tuoi|le tue) (modo|tono|carattere|risposte|battute|atteggiamento|stile|parole|frasi)\\b).*$"
    )
    private val YOU_VERBS = setOf(
        "rispondi", "parli", "dici", "fai", "ripeti", "usi", "chiami", "scrivi", "sei", "hai", "spieghi", "interrompi", "continui", "cambi",
        "leggi", "ridi", "scherzi", "insisti", "chiedi", "metti", "aggiungi", "racconti", "sbagli", "inventi", "capisci", "ascolti", "urli",
        "gridi", "blocchi", "fermi", "dimentichi", "rispondete", "parlate", "saluti", "elenchi", "correggi", "ignori", "esageri", "ti"
    )
    // "cambia il tuo carattere: più dolce", "diventa più simpatico": una regola su com'è SAM
    private val CHARACTER_CHANGE = ci(
        "^$FILL(?:cambia|cambiare|cambiami|modifica|modificare) (?:un po' )?(?:il tuo |il |la tua |la )?(?:carattere|personalità|modo di fare|" +
        "atteggiamento|modo di parlare|tono)(?:\\s*[:,]?\\s*(.*))?$"
    )
    private val BECOME = ci(
        "^$FILL(?:diventa|cerca di essere|prova a essere|prova ad essere|devi essere|dovresti essere|vorrei che fossi|voglio che tu sia|voglio che sia|" +
        "mi piacerebbe che fossi) ((?:un po' |ancora )?(?:più|meno) \\p{L}+.*)$"
    )

    /** La voce, la fantasia, il cervello, gli aggiornamenti e le lamentele su come risponde. null = non è niente di questo. */
    private fun selfCommand(t: String): Outcome? {
        // la voce
        if (VOICE_WHICH.containsMatchIn(t)) return Outcome.Tune("voice_which", 0)
        if (VOICE_RESET.containsMatchIn(t)) return Outcome.Tune("voice", 0)
        if (VOICE_PREV.containsMatchIn(t)) return Outcome.Tune("voice", -1)
        if (VOICE_NEXT.containsMatchIn(t)) return Outcome.Tune("voice", 1)
        RATE.find(t)?.let { m ->
            val w = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.lowercase() ?: return@let
            val slow = w in SLOW_WORDS || w.contains("calma")
            // "parli troppo veloce": più lento
            val step = if (m.groupValues[2].isNotEmpty()) (if (slow) 1 else -1) else (if (slow) -1 else 1)
            return Outcome.Tune(if (step < 0 && w == "piano") "rate_piano" else "rate", step)
        }
        PITCH.find(t)?.let { m ->
            val w = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.lowercase() ?: return@let
            val down = w in setOf("grave", "profonda", "calda", "bassa di tono", "abbassa")
            return Outcome.Tune("pitch", if (down) -1 else 1)
        }
        // la fantasia del cervello
        CREATIVE.find(t)?.let { m ->
            val g = m.groupValues
            val step = when {
                g[1].isNotEmpty() -> {
                    val creative = ci("^(?:creativ|fantasios|inventiv)").containsMatchIn(g[2])
                    if ((g[1].lowercase() == "più") == creative) 1 else -1
                }
                g[3].isNotEmpty() -> if (g[3].lowercase() == "più") 1 else -1
                g[4].isNotEmpty() -> -1
                else -> 0
            }
            return Outcome.Tune("creativity", step)
        }
        // 1.22 / PC 1.7: OpenJarvis (Stanford) come cervello di SAM per PC
        if (OJ_OFF.containsMatchIn(t)) return Outcome.BrainCmd("openjarvis_off")
        if (OJ_STATUS.containsMatchIn(t)) return Outcome.BrainCmd("openjarvis_status")
        if (OJ_ON.containsMatchIn(t)) return Outcome.BrainCmd("openjarvis_on")
        // il cervello
        if (BRAIN_WHICH.containsMatchIn(t)) return Outcome.BrainCmd("which")
        if (BRAIN_CODER.containsMatchIn(t)) return Outcome.BrainCmd("coder")
        if (MODELS_AUTO_OFF.containsMatchIn(t)) return Outcome.BrainCmd("models_auto_off")
        if (MODELS_AUTO_ON.containsMatchIn(t)) return Outcome.BrainCmd("models_auto_on")
        PC_PAIR.find(t)?.let { m -> val code = spokenDigits(m.groupValues[1]); if (code.length == 6) return Outcome.BrainCmd("pc_pair:$code")
            return Outcome.Speak("Il codice deve avere sei cifre: lo trovi su SAM per PC, in Impostazioni, sezione Telefono. Dimmele una per una.") }
        if (PC_SYNC.containsMatchIn(t)) return Outcome.BrainCmd("pc_sync")
        if (BRAIN_INSTALL.containsMatchIn(t)) return Outcome.BrainCmd("install")
        if (BRAIN_FAST.containsMatchIn(t)) return Outcome.BrainCmd("fast")
        if (BRAIN_SMART.containsMatchIn(t)) return Outcome.BrainCmd("smart")
        if (BRAIN_OTHER.containsMatchIn(t)) return Outcome.BrainCmd("other")
        if (SLOW_COMPLAINT.containsMatchIn(t)) return Outcome.BrainCmd("slow")
        if (LEARN_WHAT.containsMatchIn(t)) return Outcome.BrainCmd("learned")
        if (LEARN_OFF.containsMatchIn(t)) return Outcome.BrainCmd("learn_off")
        if (LEARN_ON.containsMatchIn(t)) return Outcome.BrainCmd("learn_on")
        if (BRAIN_SELF.containsMatchIn(t)) return Outcome.Speak(SELF_EXPLAIN)
        // gli aggiornamenti
        if (UPD_ON.containsMatchIn(t)) return Outcome.Update("auto_on")
        if (UPD_OFF.containsMatchIn(t)) return Outcome.Update("auto_off")
        if (UPD_STATUS.containsMatchIn(t)) return Outcome.Update("status")
        if (UPD_CHECK.containsMatchIn(t)) return Outcome.Update("check")
        // il carattere: "cambia il tuo carattere", "diventa più simpatico"
        CHARACTER_CHANGE.find(t)?.let { m ->
            val how = m.groupValues[1].trim().trim(',', '.', ':', ' ')
            if (Remember.words(how).isNotEmpty()) return addRule(characterRule(how))
            pending = { ans -> if (Remember.words(ans).isEmpty()) Outcome.Speak("Va bene, resto come sono.", endSession = true) else addRule(characterRule(ans)) }
            awaitingText = true
            return Outcome.Speak("Come vuoi che diventi? Dimmi per esempio: più spiritoso, più breve, più dolce, oppure parlami in napoletano.")
        }
        BECOME.find(t)?.let { m -> return addRule("Sii " + m.groupValues[1].trim()) }
        // le lamentele: una regola già pronta per i casi più comuni, scritta dal cervello per gli altri
        if (t.split(' ').size <= 18 && !ci("^(?:come|cosa|che cosa|quando|dove|chi|quale|quali|quanto|quanti)\\b").containsMatchIn(t)) {
            for ((rx, rule) in COMPLAINTS) if (rx.containsMatchIn(t)) {
                if (hasRule(rule)) return Outcome.Speak("Hai ragione, scusa. È già una delle mie regole: «$rule». Da adesso ci sto più attento.")
                return Outcome.Speak(proposeRule(rule, "Hai ragione. Vuoi che d'ora in poi segua questa regola: «$rule»?"))
            }
            COMPLAINT_GENERIC.find(t)?.let { m ->
                val verb = m.groupValues[1].lowercase()
                if (m.groupValues[2].isNotEmpty() || verb in YOU_VERBS) return Outcome.SelfEdit(t)
            }
        }
        return null
    }

    /** "più dolce" -> "Sii più dolce"; "parlami in napoletano" resta com'è. */
    private fun characterRule(how0: String): String {
        val how = how0.trim().trim(',', '.', ':', ' ')
        return if (ci("^(?:un po' |ancora )?(?:più|meno)\\b").containsMatchIn(how)) "Sii $how" else how
    }

    private fun hasRule(text: String) = mem.memos().any { it.kind == Remember.REGOLA && Remember.norm(it.text) == Remember.norm(text) }

    private val SELF_EXPLAIN =
        "Il mio cervello vero, cioè i miliardi di numeri del modello, non lo posso riaddestrare qui sul telefono: servirebbero computer " +
        "con schede grafiche potenti, per ore. Quello che posso cambiare da solo è il modo in cui lo uso: il mio carattere e le regole " +
        "che seguo. Dimmi per esempio: sii più spiritoso, oppure: d'ora in poi rispondimi in breve. Se mi correggi imparo la risposta " +
        "giusta. E imparo da solo: quando sono libero rileggo le cose che mi hai detto e mi segno quelle importanti sulla tua vita; per sentirle " +
        "di': cosa hai imparato da solo? Posso anche passare a un altro cervello, di': usa il cervello veloce, e quando ne esce uno più nuovo " +
        "lo scarico col Wi-Fi."

    /**
     * Una domanda sì/no di SAM: "sì" dà [yes], "no" dà [no], qualsiasi altra frase è un comando come sempre.
     * Ritorna la domanda da dire.
     */
    fun confirm(question: String, yes: () -> Outcome, no: () -> Outcome): String {
        pending = { ans ->
            when {
                Lang.isYes(ans) -> yes()
                Lang.isNo(ans) -> no()
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return question
    }

    /**
     * Una regola proposta da SAM stesso (dopo una lamentela, o scritta dal cervello in fondo a una risposta):
     * si tiene solo se dici sì. Ritorna la domanda da dire.
     */
    fun proposeRule(rule0: String, question: String? = null): String {
        val rule = rule0.trim().trimEnd('.', ' ').replaceFirstChar { it.uppercase() }
        return confirm(question ?: "Vuoi che me lo segni come regola, così vale sempre: «$rule»?",
            yes = { addRule(rule) }, no = { Outcome.Speak("Va bene, lascio com'è.", endSession = true) })
    }

    /** true se [rule] è già tra le regole (non serve proporla). */
    fun knowsRule(rule: String): Boolean = hasRule(rule.trim().trimEnd('.', ' ').replaceFirstChar { it.uppercase() })

    /** Dopo aver cambiato voce: "Ti piace?". Sì la tiene, no (o "un'altra") ne prova un'altra. Ritorna la domanda. */
    fun askVoiceLike(question: String): String {
        pending = { ans ->
            when {
                ci("^\\W*(?:no\\b.*|cambia\\b.*|un'altra\\b.*|un altra\\b.*|altra\\b.*|prossima\\b.*|avanti\\b.*|neanche\\b.*|nemmeno\\b.*)$").containsMatchIn(ans) &&
                    !ci("^\\W*no\\W*(?:grazie|basta|va bene|così va bene)\\W*$").containsMatchIn(ans) -> Outcome.Tune("voice", 1)
                ci("^\\W*(?:quella di prima|torna (?:a|alla) (?:quella|voce) di prima|la precedente)\\W*$").containsMatchIn(ans) -> Outcome.Tune("voice", -1)
                Lang.isYes(ans) || ci("^\\W*(?:mi piace|bella|va bene|perfetta|ottima|tienila|questa)\\b").containsMatchIn(ans) ->
                    Outcome.Speak("Perfetto, tengo questa.", endSession = true)
                Lang.isNo(ans) -> Outcome.Speak("Va bene, la tengo così.", endSession = true)
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return question
    }

    /**
     * "mi riconosci?", "chi sta parlando?": domande innocue sulla voce (SAM dice solo quanto somiglia alla tua),
     * a cui risponde anche quando non riconosce chi parla: così si scopre perché non ti riconosce.
     */
    fun isVoiceCheck(raw: String): Boolean {
        var t = raw.lowercase().replace(Regex("[,.!?;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        t = ci("^(per favore|per piacere|puoi|potresti|mi puoi|senti|allora)\\s+").replace(t, "")
        t = t.trim(' ', '.', ',', '!', '?')
        return t.isNotEmpty() && VOICE_CHECK.containsMatchIn(t)
    }

    /**
     * Dopo che non ti ha riconosciuto, appena tocchi "Parla" SAM propone di imparare di nuovo la tua voce:
     * "sì" comincia, "no" lascia stare, qualsiasi altra frase è un comando come sempre. Ritorna la domanda da dire.
     */
    fun offerVoiceRelearn(): String {
        pending = { ans ->
            when {
                Lang.isYes(ans) -> Outcome.VoiceCmd("enroll")
                Lang.isNo(ans) && ans.split(' ').size <= 3 -> Outcome.Speak("Va bene. Dimmi pure.")
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return "Poco fa non ho riconosciuto la tua voce. Vuoi che la impari di nuovo? Ci vuole un minuto."
    }

    // ------------------------------------------------------------------ documento condiviso
    /** C'è l'enciclopedia sul telefono (la imposta il servizio vocale): "cerca su Wikipedia" la usa invece del browser. */
    @Volatile var encyclopedia = false
    /** Com'è l'enciclopedia, a parole ("Wikipedia in italiano di settembre 2026, con 1.912.345 voci"), se c'è. */
    @Volatile var encyclopediaInfo: String? = null

    /** Il documento condiviso con SAM (il suo nome) finché resta aperto; null = nessuno. */
    var document: String? = null
        set(v) { field = v; docAt = now() }
    private var docAt = 0L
    /** un documento resta "aperto" per 20 minuti dall'ultima volta che ne parli */
    private fun docOpen(): Boolean = document != null && now() - docAt < 20 * 60_000L

    private val DOC_READ = ci(
        "^(?:sì\\s+|si\\s+)?(?:leggilo|leggila|leggimelo|leggimela|leggimi|leggi|leggere)" +
        "(?:\\s+(?:il|tutto il|questo|quel) (?:documento|file|testo|pdf|foglio|libro|articolo))?" +
        "(?:\\s+(?:tutto|dall'inizio|ad alta voce|per favore))*$"
    )
    private val DOC_SUMMARY = ci("\\b(?:riassumi|riassumilo|riassumila|riassumimelo|riassunto|sintesi|di cosa parla|di che parla|cosa dice|che dice|in breve|fammi il riassunto)\\b")
    private val DOC_NEXT = ci("^(?:continua|continua a leggere|vai avanti|prosegui|avanti|ancora|il resto|leggi ancora|vai)$")
    private val DOC_RESTART = ci("^(?:ricomincia|ricomincia da capo|dall'inizio|rileggi|rileggilo|rileggimelo|torna all'inizio)$")
    private val DOC_CLOSE = ci("^(?:chiudi|chiudere|basta con|dimentica|lascia stare) (?:il|questo|quel|lo) (?:documento|file|testo|pdf|foglio)$")
    private val DOC_ASK = ci(
        "^(?:nel|secondo il|dal|nel testo del|in questo|dentro il) (?:documento|file|testo|pdf|foglio)\\b|" +
        "\\b(?:cosa|che cosa|che) dice (?:il|questo) (?:documento|file|testo|pdf) (?:su|sul|sulla|di|del|della|riguardo|a proposito)"
    )

    /** I file che SAM prepara per te (vedi [SamFiles]); il testo del documento solo se ce n'è uno aperto. */
    private fun fileCommand(t: String): Outcome? {
        val o = SamFiles.command(t) ?: return null
        if (o.what == "doc" && !docOpen())
            return Outcome.Speak("Non ho nessun documento aperto. Per darmene uno tocca la graffetta accanto a dove scrivi, oppure condividilo con SAM.")
        return o
    }

    private fun docCommand(t: String, raw: String): Outcome? {
        if (!docOpen()) {
            if (ci("\\b(?:documento|file|pdf)\\b").containsMatchIn(t) &&
                (DOC_READ.containsMatchIn(t) || DOC_SUMMARY.containsMatchIn(t) || DOC_ASK.containsMatchIn(t)))
                return Outcome.Speak("Non ho nessun documento aperto. Per farmene leggere uno tocca la graffetta accanto a dove scrivi, oppure condividilo con SAM dal tasto Condividi.")
            return null
        }
        docAt = now()
        return when {
            DOC_CLOSE.containsMatchIn(t) -> Outcome.Doc("close")
            DOC_RESTART.containsMatchIn(t) -> Outcome.Doc("restart")
            DOC_NEXT.containsMatchIn(t) -> Outcome.Doc("next")
            DOC_ASK.containsMatchIn(t) -> Outcome.Doc("ask", raw)
            DOC_SUMMARY.containsMatchIn(t) -> Outcome.Doc("summary")
            DOC_READ.containsMatchIn(t) -> Outcome.Doc("read")
            else -> null
        }
    }

    /** Un documento appena aperto: la domanda da dire, e la risposta (leggi, riassumi, domanda) si aspetta. */
    fun openDocument(name: String, words: Int, pages: Int): String {
        document = name
        pending = { ans -> docAnswer(ans) }
        val size = when {
            pages > 1 -> "$pages pagine"
            words > 0 -> "circa ${roundWords(words)} parole"
            else -> "breve"
        }
        return "Ho aperto «$name», $size. Vuoi che te lo legga, che te lo riassuma, o hai una domanda?"
    }

    private fun roundWords(n: Int) = when { n < 100 -> n; n < 1000 -> (n + 5) / 10 * 10; else -> (n + 50) / 100 * 100 }

    private fun docAnswer(ans: String): Outcome {
        val t = ans.trim()
        if (ci("^(?:sì|si|ok|va bene|certo)$").containsMatchIn(t) ||
            ci("^(?:sì |si |ok |va bene |certo )?(?:leggi|leggilo|leggila|leggimelo|leggimi|tutto|dall'inizio)\\b").containsMatchIn(t))
            return Outcome.Doc("read")
        if (DOC_SUMMARY.containsMatchIn(t)) return Outcome.Doc("summary")
        if (Lang.isNo(t)) return Outcome.Speak("Va bene. Il documento resta aperto: quando vuoi chiedimi di leggerlo, di riassumerlo o cosa dice.", endSession = true)
        return try { route(t) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
    }

    /** Dopo un pezzo letto ad alta voce: "Continuo?" */
    fun askContinue(): String {
        pending = { ans ->
            when {
                Lang.isYes(ans) || DOC_NEXT.containsMatchIn(ans.trim()) -> Outcome.Doc("next")
                Lang.isNo(ans) || Lang.isStop(ans) -> Outcome.Speak("Va bene, mi fermo qui. Per andare avanti di': SAM, continua.", endSession = true)
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return "Continuo?"
    }

    // ------------------------------------------------------------------ comandi a inizio frase
    // Davanti a un comando che fa qualcosa (chiudere, chiamare, aprire): "allora", "ok"… e "puoi", "devi", "voglio che", "mi".
    // Questi comandi valgono solo così, all'inizio: "non riesco a chiudere l'app", "domani chiamo Marco", "quando apri
    // la fotocamera non mi senti" sono chiacchiere (prima chiudevano l'app, telefonavano, aprivano la fotocamera).
    private val CMD_HEAD = "^$FILL(?:(?:mi |ti )?(?:puoi|potresti|riesci a|devi|dovresti|vuoi)\\s+|(?:voglio|vorrei) che (?:tu )?|" +
        "(?:voglio|vorrei|ti chiedo di|ti prego di)\\s+)?(?:mi |me l[oa] |ce l[oa] )?"

    // "che ore sono?", "che giorno è oggi?" (ma "non vedo l'ora di partire" e "che giorno è nato Verdi?" no)
    private val TIME_Q = ci(
        "\\b(?:che ore sono|che ora è|che ora e|dimmi l'ora|mi dici l'ora|sai l'ora|mi sai dire l'ora)\\b|" +
        "^$FILL(?:l'ora|l'orario|l'ora esatta|ora esatta)$|" +
        "\\b(?:che giorno|che data|in che giorno|quale giorno)(?: (?:è|e|sarà|sara|era|siamo|abbiamo|è stato))?" +
        "(?: (?:oggi|domani|ieri|dopodomani|l'altro ieri|adesso))?$|" +
        "^$FILL(?:oggi|domani|ieri|dopodomani) (?:che giorno|che data|quale giorno)(?: (?:è|e|sarà|sara|era))?$"
    )

    // "ripeti", "cosa hai detto?", "non ho capito": l'ultima cosa che ha detto SAM
    private val REPEAT = ci(
        "^$FILL(?:scusa\\s+|scusami\\s+)?(?:" +
        "(?:mi |me l[oa] )?(?:puoi |potresti |riesci a )?(?:ripeti|ripetimi|ripetere|ripetermi|ripetilo|ripetila|ripetimelo|ripetimela|" +
        "ridimmi|ridillo|ridimmelo|me lo ripeti|me la ripeti|me lo ridici)|" +
        "(?:cosa|che cosa|che|come) (?:hai detto|mi hai detto|hai risposto|mi hai risposto|dicevi)|" +
        "non (?:ho capito|ti ho capito|ho sentito|ti ho sentito)(?: bene)?" +
        ")(?:\\s+(?:per favore|di nuovo|ancora|un'altra volta|l'ultima cosa|l'ultima frase|quello che hai detto|cosa hai detto|" +
        "più piano|più lentamente|scusa|bene|niente|sam))*$"
    )

    // "parla più forte", "alza la voce": la voce di SAM è il volume del telefono
    // ("parla più piano" dalla 1.10 vuol dire più lentamente: lo prende prima la velocità della voce, che ricorda "abbassa la voce")
    private val VOICE_VOLUME = ci(
        "^$FILL(?:(?:puoi|potresti)\\s+)?(alza la voce|alza un po' la voce|parla più forte|parla piu forte|più forte|piu forte|" +
        "parla più alto|voce più alta|non ti sento bene|non ti sento|abbassa la voce|abbassa un po' la voce|" +
        "parla più basso|voce più bassa|parla a voce bassa|parla sottovoce)(?:\\s+(?:per favore|che non ti sento|un po'|sam))*$"
    )

    // "fai uno screenshot", "cattura lo schermo"
    private val SCREENSHOT = ci(
        "$CMD_HEAD(?:fai|fammi|fare|scatta|cattura|prendi|salva)\\s+(?:uno |lo |un |una )?(?:screenshot|screen shot|schermata|" +
        "foto dello schermo|foto allo schermo|cattura dello schermo)\\b|^$FILL(?:screenshot|cattura (?:lo )?schermo)$"
    )

    // l'app Fotocamera del telefono: solo per fare foto ("scatta una foto", "fammi un selfie", "apri l'app della fotocamera")
    private val PHOTO_APP = ci(
        "$CMD_HEAD(?:scatta|scattare|scattami|fai|fare|fammi|farmi|fatti)\\s+(?:un[oa]? |una |la |il |delle |qualche |due |2 )?" +
        "(?:foto|selfie|fotografia|fotografie)\\b|" +
        "$CMD_HEAD(?:apri|aprire|avvia|avviare|lancia|usa|usare)\\s+(?:l'app|l'applicazione|app|applicazione)\\s+(?:della |di |dell')?" +
        "(?:fotocamera|camera|foto)\\b|" +
        "$CMD_HEAD(?:apri|aprire|accendi|avvia|usa)\\s+(?:la )?(?:fotocamera|camera)\\s+(?:e |per |e poi )(?:scatta|scattare|fai|fare|fammi|fatti)\\b|" +
        "^$FILL(?:voglio|vorrei|devo|dobbiamo|facciamo) (?:fare |scattare |farmi |farci )?(?:un[oa]? |una |delle )?(?:foto|selfie|fotografia)\\b"
    )

    // "devi spegnere la torcia", "voglio aprire whatsapp", "mi devi chiamare mamma": il verbo all'infinito
    private val MODAL_INF = ci(
        "^$FILL(?:(?:mi |ti )?(?:devi|dovresti|puoi|potresti|riesci a|vuoi)|voglio|vorrei|ti chiedo di|ti prego di)\\s+" +
        "(?:(?:mi|me lo|me la)\\s+)?(\\p{L}+)(\\s+.*)?$"
    )

    // ------------------------------------------------------------------ correggermi e insegnarmi
    // "no, intendevo accendi la torcia", "no, volevo dire chiama mamma", "no, ti ho detto di aprire whatsapp"
    private val CORRECTION = ci(
        "^(?:(?:no|nono)\\b[,\\s]*(?:no\\b[,\\s]*)*|non (?=intendevo|volevo|dicevo|ti chiedevo))(?:sam[,\\s]+)?(?:" +
        "(?:io )?(?:intendevo dire|intendevo|volevo dire|volevo|dicevo|ti ho detto|ho detto|ti avevo detto|ti stavo dicendo|ti chiedevo)|" +
        "non (?:quello|questo|così)[,\\s]+(?:(?:io )?(?:intendevo dire|intendevo|volevo dire|volevo)\\s*)?" +
        ")\\s*(?:di |:\\s*)?(.+)$"
    )

    // l'ultima frase che non ho capito come comando (è andata al cervello come chiacchiera): se subito dopo dici
    // "no, intendevo accendi la torcia", la faccio e propongo di impararla
    private var misheard: String? = null
    private var misheardAt = 0L

    private fun noteHeard(t: String, o: Outcome) {
        misheard = if (o is Outcome.NeedLlm && !o.recall && t.split(' ').size in 1..8 && !CORRECTION.containsMatchIn(t)) t else null
        misheardAt = now()
    }

    private fun correction(t: String, depth: Int): Outcome? {
        if (depth > 0) return null
        val m = CORRECTION.find(t) ?: return null
        val y = imperative(m.groupValues[1].trim().trim(',', '.', ' '))
        if (Remember.words(y).isEmpty()) return null
        val prev = misheard?.takeIf { now() - misheardAt <= 3 * 60_000L }
        misheard = null
        val o = try { route(y, depth = 1) } catch (e: Exception) { return null }
        // nemmeno questo è un comando: è una precisazione per il cervello, che segue il discorso
        if (o is Outcome.NeedLlm) return Outcome.NeedLlm(t)
        if (prev == null || pending != null || Remember.aliasKey(prev).isEmpty() || Remember.aliasKey(prev) == Remember.aliasKey(y)) return o
        // la frase di prima non l'avevo capita: chiedo se d'ora in poi vuol dire questo
        val ask = "Vuoi che d'ora in poi, quando dici «$prev», faccia questo?"
        val learn = { ans: String ->
            when {
                Lang.isYes(ans) -> teach(prev, y)
                Lang.isNo(ans) -> Outcome.Speak("Va bene, non lo imparo.", endSession = true)
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return when (o) {
            is Outcome.Speak -> { pending = learn; Outcome.Speak(o.text.trimEnd() + " " + ask) }
            is Outcome.Handled -> { pending = learn; Outcome.Speak(ask) }
            else -> o
        }
    }

    /** "Ripeti": l'ultima cosa detta da SAM. */
    private fun repeatLast(): Outcome {
        val last = talk?.lastSaid() ?: return Outcome.Speak("Non ho ancora detto niente.")
        return Outcome.Speak(last)
    }

    // ------------------------------------------------------------------ ragionare
    // "pensaci bene: quanto fa…", "ragionaci", "pensaci meglio" (sulla domanda di prima): prima un ragionamento
    // passo per passo, che si vede sullo schermo, poi la risposta. Ci mette di più: solo quando lo chiedi.
    private val REASON = ci(
        "^$FILL(?:(?:mi |ti )?(?:puoi|potresti|devi|dovresti)\\s+)?(?:ragiona ci bene|ragiona ci su|ragiona ci|pensa ci bene|pensa ci su|pensa ci|" +
        "rifletti ci|pensaci bene|pensaci meglio|pensaci su|pensaci un attimo|pensaci un po'|" +
        "pensaci|ragionaci bene|ragionaci meglio|ragionaci su|ragionaci sopra|ragionaci|ragiona bene|ragiona meglio|ragiona|riflettici bene|" +
        "riflettici|rifletti bene|rifletti meglio|rifletti|pensa bene|ripensaci|pensarci bene|pensarci meglio|pensarci su|pensarci|" +
        "ragionarci bene|ragionarci su|ragionarci|rifletterci bene|rifletterci|riflettere bene)(?:\\s+(?:bene|meglio|un attimo|un po'|tu|per favore))*" +
        "(?:[,:;]?\\s+(?:e\\s+)?(?:poi\\s+)?(?:(?:dimmi|rispondi|rispondimi|su questo|su questa domanda|su questa)[,:]?\\s+)?(.*))?$|" +
        "^(.+?)[,\\s]+(?:pensaci bene|ragionaci bene|ragionaci su|pensaci su|ragiona bene|rifletti bene|pensaci meglio|ragionaci)$"
    )

    private fun reasonRequest(t: String): Outcome? {
        val m = REASON.find(t) ?: return null
        val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        if (Remember.words(q).isNotEmpty()) return Outcome.NeedLlm(q, reason = true)
        // "pensaci meglio" dopo una risposta: la stessa domanda, questa volta ragionata
        val said = talk?.before(null).orEmpty()
        val last = said.lastOrNull { it.chat } ?: said.lastOrNull()
        if (last != null) return Outcome.NeedLlm(questionOf(last.you), reason = true)
        pending = { ans -> if (Remember.words(ans).isEmpty()) Outcome.Speak("Va bene.") else Outcome.NeedLlm(ans, reason = true) }
        awaitingText = true
        return Outcome.Speak("Certo: su quale domanda devo ragionare?")
    }

    /** La domanda vera di una battuta: senza "pensaci bene" davanti ("pensaci bene quanto fa 17 per 23" -> "quanto fa 17 per 23"). */
    private fun questionOf(you: String): String {
        val t = Num.wordsToDigits(you.trim().lowercase())
        val m = REASON.find(t) ?: return you.trim()
        return m.groupValues[1].ifBlank { m.groupValues[2] }.trim().takeIf { Remember.words(it).isNotEmpty() } ?: you.trim()
    }

    // ------------------------------------------------------------------ imparare dalle correzioni
    // "sbagliato, la capitale dell'Australia è Canberra", "non è vero, è Canberra", "hai sbagliato": la risposta giusta
    // diventa una lezione e la volta dopo, a una domanda simile, il cervello la riceve davanti alla domanda.
    // ("Non è vero che i gatti odiano l'acqua" invece è una frase normale: va al cervello.)
    private val FEEDBACK_BAD = ci(
        "^$FILL(?:no[,\\s]+)?(?:(?:hai sbagliato|ti sei sbagliato|ti sei sbagliata|ti sbagli|sbagliato|sbagliata|risposta sbagliata|errore|falso)" +
        "(?![\\s,]+(?:che|di|a|ad|perché|quando|se)\\b)|" +
        // "non è vero che…", "non è giusto il prezzo…" sono frasi normali: qui ci vuole una pausa, o la risposta giusta dopo
        "(?:è sbagliato|e sbagliato|è sbagliata|non è vero|non è così|non è giusto|non è corretto|non è esatto)" +
        "(?![\\s,]+(?:che|di|a|ad|perché|quando|se|il|lo|la|le|gli|i|l'|un|una|uno|del|della)\\b))" +
        "(?:[,.:;!\\s]+(?:(?:la risposta (?:giusta|corretta|esatta|vera)|quella giusta|la verità|la cosa giusta) è[,:\\s]+|" +
        "in realtà[,:\\s]+|invece[,:\\s]+|è[,:\\s]+|sono[,:\\s]+)?(.*))?$"
    )
    // "esatto", "bravo", "giusto", "perfetto": la risposta era buona, si tiene come esempio
    private val FEEDBACK_GOOD = ci(
        "^(?:(?:sì|si|ok|okay|ecco|sam)[,\\s]+)?(?:esatto|esattamente|giusto|giustissimo|bravo|brava|bravissimo|bravissima|perfetto|perfetta|" +
        "ottimo|ottima risposta|risposta giusta|risposta esatta|risposta perfetta|ben detto|proprio così|corretto|è giusto|è corretto|" +
        "è proprio così|hai ragione|è vero)(?:[,!.\\s]+(?:esatto|giusto|bravo|brava|perfetto|sam|così|davvero|proprio così|hai ragione|è così))*$"
    )
    // una domanda vera (non un saluto o una frase raccontata): solo da queste si impara un esempio buono
    private val QUESTION_LIKE = ci(
        "^(?:chi|che|cosa|cos'|com'|come|dove|quando|perché|perche|quanto|quanta|quanti|quante|quale|quali|qual|sai|mi sai dire|" +
        "secondo te|è vero che|esiste|mi spieghi|spiegami|dimmi|raccontami|in che|di che|a che|da che)\\b"
    )

    private fun feedback(t: String): Outcome? {
        val bad = FEEDBACK_BAD.find(t)
        if (bad != null) {
            val last = talk?.before(null)?.lastOrNull()
            if (last == null || !last.chat)
                return Outcome.Speak("Cosa ho sbagliato? Se ho capito male un comando, dimmi: no, intendevo, e il comando giusto. " +
                    "Se era una risposta, dimmi: sbagliato, la risposta giusta è, e poi la risposta.")
            val right = bad.groupValues[1].trim().trim(',', '.', ' ')
            if (meaningful(right)) return saveLesson(questionOf(last.you), right, good = false)
            pending = { ans ->
                if (!meaningful(ans)) Outcome.Speak("Va bene, lasciamo stare.", endSession = true)
                else saveLesson(questionOf(last.you), ci("^(?:la risposta (?:giusta|corretta|esatta) è|è)[,:\\s]+").replace(ans.trim(), ""), good = false)
            }
            awaitingText = true
            return Outcome.Speak("Hai ragione, scusa. Qual è la risposta giusta? Così me la segno.")
        }
        if (FEEDBACK_GOOD.containsMatchIn(t)) {
            val last = talk?.before(null)?.lastOrNull() ?: return null
            // "esatto" detto per rispondere a una domanda di SAM fa parte del discorso: ci pensa il cervello
            if (!last.chat || last.sam.trimEnd().endsWith("?")) return null
            val q = questionOf(last.you)
            if (last.sam.length > 400 || Remember.words(q).size < 2 || !QUESTION_LIKE.containsMatchIn(q)) return Outcome.Speak("Grazie!")
            return saveLesson(q, last.sam, good = true)
        }
        return null
    }

    /** Una risposta vera, anche cortissima ("il Po", "42"): non solo "è" o una lettera sentita per sbaglio. */
    private fun meaningful(s: String): Boolean = s.trim().split(Regex("\\s+")).any { w -> w.count { it.isLetterOrDigit() } >= 2 }

    /**
     * Una lezione: alla domanda [question] la risposta giusta è [answer0]. Ne resta una per domanda (l'ultima vale);
     * oltre [Remember.MAX_LESSONS] le più vecchie lasciano il posto. Il cervello la riceve davanti alle domande simili.
     */
    private fun saveLesson(question: String, answer0: String, good: Boolean): Outcome {
        val answer = answer0.trim().trim(',', '.', ' ').replaceFirstChar { it.uppercase() }.take(400)
        if (!meaningful(answer)) return Outcome.Speak("Non ho capito la risposta giusta: dimmela di nuovo, con calma.")
        val key = question.trim().take(200)
        val lessons = mem.memos().filter { it.kind == Remember.LEZIONE }
        val same = lessons.filter { Remember.norm(it.key) == Remember.norm(key) }
        // "esatto" non cancella una correzione: la risposta corretta da te resta quella
        if (good && same.isNotEmpty()) return Outcome.Speak("Grazie!")
        same.forEach { mem.removeMemo(it) }
        val others = lessons - same.toSet()
        if (others.size >= Remember.MAX_LESSONS) others.take(others.size - Remember.MAX_LESSONS + 1).forEach { mem.removeMemo(it) }
        mem.addMemo(Memo(Remember.LEZIONE, answer, key, now()))
        memoryUrgent = true
        return Outcome.Speak(if (good) "Grazie! Me la segno come risposta giusta."
                             else "Hai ragione, grazie della correzione. Me lo segno: la prossima volta rispondo così.")
    }

    // ------------------------------------------------------------------ OpenJarvis (PC 1.7)
    private val OJ = "(?:open ?jarvis|open ?giarvis|open ?jarvi|openjarvis)"
    private val OJ_ON = ci("^$FILL(?:usa|usare|attiva|collega|collegati a|collegati con|passa a|vai su|accendi|prova)\\s+(?:il cervello di |l'ia di )?$OJ(?:\\s+(?:come cervello|per rispondere|adesso|ora))?$")
    private val OJ_OFF = ci("^$FILL(?:lascia|scollega|stacca|disattiva|spegni|togli|basta con|smetti di usare)\\s+$OJ$|" +
        "^$FILL(?:torna|ritorna) (?:al|a usare il) tuo cervello$|^$FILL(?:usa|rimetti) il tuo cervello$")
    private val OJ_STATUS = ci("^$FILL(?:$OJ (?:funziona|è acceso|è collegato|c'è|risponde)|(?:controlla|verifica|com'è|come sta) $OJ)\\??$")

    // ------------------------------------------------------------------ la mente (1.23)
    private val DIGEST = ci("^$FILL(?:buongiorno|buon giorno|buondì|buon pomeriggio|buonasera|buona sera)(?:\\s+(?:sam|a te|anche a te|amico mio))?$|" +
        "^$FILL(?:fammi |dammi |dimmi )?(?:la )?(?:rassegna|briefing|il punto)(?: del mattino| mattutina| della giornata| di oggi)?$|" +
        "^$FILL(?:com'è|come sarà|com'è messa) (?:la mia|questa) giornata$|^$FILL(?:il mio programma|i miei programmi) (?:di|per) oggi$")
    private val PLANS_Q = ci("^$FILL(?:(?:cosa|che cosa) (?:devo fare|ho da fare|ho in programma|c'è in programma|mi aspetta|ho)|che (?:impegni|programmi|appuntamenti) ho|" +
        "(?:quali sono )?i miei (?:impegni|programmi|appuntamenti)|(?:ho|c'è) (?:qualche |degli )?(?:impegni|appuntamenti)|cosa mi hai segnato)" +
        "(?:\\s+(oggi|domani|dopodomani|stasera|questa settimana|la settimana prossima|nel weekend|questo weekend|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica|nei prossimi giorni))?$")
    private val HABITS_Q = ci("^$FILL(?:(?:quali sono |dimmi )?le mie abitudini|che abitudini ho|(?:cosa|che cosa) faccio di solito|hai capito (?:le mie abitudini|cosa faccio di solito))$")
    private val DIARY = ci("^$FILL(?:leggimi |mostrami |fammi vedere |dimmi |apri )?(?:il )?(?:tuo |nostro )?diario(?: (di oggi|di ieri|di questa settimana|della settimana|della settimana scorsa|di questo mese|del mese scorso|di \\S+))?$")
    private val SOURCE = ci("^$FILL(?:e )?(?:come (?:lo |la )?sai|come fai a saperlo|da dove (?:l'hai|lo hai|la hai|l'avete) (?:preso|presa|saputo|letto|tirato fuori)|" +
        "chi te l'ha detto|qual è la (?:tua )?fonte|da dove viene(?: questa (?:cosa|informazione))?|dove l'hai letto|sei sicur[oa]|ne sei sicur[oa]|sei cert[oa]|davvero)\\??$")
    private val KNOW = ci("^$FILL(?:facciamo conoscenza|conosciamoci(?: meglio)?|(?:fammi|fai) (?:qualche |delle |un po' di )?domande su di me|vuoi conoscermi(?: meglio)?|" +
        "conoscimi(?: meglio)?|chiedimi (?:qualcosa|delle cose) (?:di|su) me|impara a conoscermi)$")
    private val THINK_ON = ci("^$FILL(?:pensa|ragiona|pensaci) (?:sempre |bene |da solo |da sola )*(?:prima di rispondere )?(?:alle|sulle|per le) domande difficili$|" +
        "^$FILL(?:alle domande difficili )?(?:pensaci|ragiona) (?:sempre )?prima di rispondere$")
    private val THINK_OFF = ci("^$FILL(?:rispondi (?:sempre )?subito|non pensarci (?:troppo|tanto)|smetti di ragionare|non ragionare)(?: (?:alle domande difficili|senza pensarci(?: troppo)?))?$")
    private val PROPOSE_OFF = ci("^$FILL(?:non (?:propormi|chiedermi|farmi) più (?:niente|nulla|le abitudini|domande|com'è andata)|smetti(?:la)? di (?:propormi|chiedermi) (?:le )?(?:cose|abitudini|com'è andata)|" +
        "non salutarmi più (?:la mattina|al mattino)|niente (?:più )?proposte)$")
    private val PROPOSE_ON = ci("^$FILL(?:puoi (?:di nuovo )?propormi (?:le cose|le abitudini)|propormi (?:pure )?le (?:cose|abitudini)|torna a propormi (?:le cose|le abitudini)|" +
        "(?:salutami|chiedimi) di nuovo (?:la mattina|com'è andata))$")
    private val MIND_WHAT = ci("^$FILL(?:come funziona (?:la tua mente|il tuo cervello|la tua memoria)|cosa c'è nella tua mente|(?:mostrami |fammi vedere )?la tua mente|" +
        "hai una mente|come ragioni|come pensi)\\??$")
    private val MOOD_Q = ci("^$FILL(?:come mi (?:vedi|senti|trovi)(?: oggi)?|come ti sembro(?: oggi)?)\\??$")

    private fun today(): java.time.LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(mind?.zone ?: java.time.ZoneId.systemDefault()).toLocalDate()

    /** I comandi della mente. null = la frase non è per la mente. */
    private fun mindCommand(t: String, raw: String): Outcome? {
        val m = mind ?: return null
        if (DIGEST.containsMatchIn(t)) {
            val d = m.digest(mem.ownerName(), mem.memos(), now())
            askAfter(d.ask)
            return Outcome.Speak(d.text, endSession = d.ask == null)
        }
        PLANS_Q.find(t)?.let { mt -> return Outcome.Speak(plansAnswer(mt.groupValues[1].lowercase()), endSession = true) }
        if (HABITS_Q.containsMatchIn(t)) {
            val hs = m.habits(now())
            return Outcome.Speak(if (hs.isEmpty()) "Non ho ancora capito abitudini fisse: le imparo quando mi chiedi le stesse cose alla stessa ora per qualche giorno."
                else "Ho notato che: " + Mind.join(hs.map { Habits.describe(it) }) + ".", endSession = true)
        }
        DIARY.find(t)?.let { mt -> return Outcome.Speak(diaryAnswer(mt.groupValues[1].ifBlank { "" }), endSession = true) }
        if (SOURCE.containsMatchIn(t) && !(t.trim() == "davvero" && m.lastSource == null)) return sourceAnswer(t)
        if (KNOW.containsMatchIn(t)) return getToKnow(0, first = true)
        if (THINK_ON.containsMatchIn(t)) { m.thinkAuto = true; return Outcome.Speak("Va bene: alle domande difficili prima ci penso, poi ti rispondo. Ci metto qualche secondo in più.", endSession = true) }
        if (THINK_OFF.containsMatchIn(t)) { m.thinkAuto = false; return Outcome.Speak("Ok, rispondo subito. Se vuoi che ci pensi bene, dimmelo: pensaci bene.", endSession = true) }
        if (PROPOSE_OFF.containsMatchIn(t)) { m.proactive = false; return Outcome.Speak("Va bene, non ti propongo più niente da solo: rispondo e basta.", endSession = true) }
        if (PROPOSE_ON.containsMatchIn(t)) { m.proactive = true; return Outcome.Speak("Va bene: ti saluto la mattina, ti propongo le cose che fai di solito e ti chiedo com'è andata.", endSession = true) }
        if (MIND_WHAT.containsMatchIn(t)) return Outcome.Speak(mindExplain(), endSession = true)
        if (MOOD_Q.containsMatchIn(t)) return Outcome.Speak(m.mood(now())?.let { (v, l) ->
            if (v < 0) "Da quello che mi hai detto poco fa ti sento $l. Se vuoi parlarne, sono qui." else "Mi sembri $l, e mi fa piacere!" }
            ?: "Non mi hai detto niente di come stai: com'è che ti senti?")
        return null
    }

    /** Dopo il saluto o un'offerta: la domanda finale (com'è andata, un'abitudine). */
    private fun askAfter(ask: Mind.Ask?) {
        val m = mind ?: return
        when (ask) {
            is Mind.Ask.FollowUp -> {
                m.setPlanState(ask.plan, "asked")
                pending = { ans ->
                    when {
                        Lang.isNo(ans) && ans.split(' ').size <= 2 -> Outcome.Speak("Va bene, non importa.", endSession = true)
                        Remember.isCommand(ans) -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
                        else -> {
                            m.setPlanState(ask.plan, "done")
                            val f = Feelings.read(ans) ?: Feelings.read("sono " + ans)
                            val good = Lang.rx("\\b(?:bene|benissimo|alla grande|ottimo|ottimamente|perfetto|bellissimo|bello|fantastico|super|tutto ok|tutto a posto|abbiamo vinto|ho vinto|promosso|promossa|passato|passata|preso|presa)\\b").containsMatchIn(ans)
                            val bad = Lang.rx("\\b(?:male|malissimo|uno schifo|da schifo|non bene|non benissimo|abbiamo perso|ho perso|bocciato|bocciata|non è andata|così così|insomma)\\b").containsMatchIn(ans)
                            Outcome.Speak(when {
                                bad || (f != null && f.valence < 0) -> "Mi dispiace. Se ti va di parlarne, sono qui."
                                good || (f != null && f.valence > 0) -> "Che bello, mi fa proprio piacere!"
                                else -> "Grazie di avermelo raccontato."
                            }, endSession = !bad)
                        }
                    }
                }
            }
            is Mind.Ask.DoHabit -> {
                m.markOffered(ask.habit, now())
                pending = { ans ->
                    when {
                        Lang.isYes(ans) -> try { route(ask.habit.command) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto.") }
                        Lang.isNo(ans) -> Outcome.Speak("Va bene.", endSession = true)
                        else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
                    }
                }
            }
            null -> {}
        }
    }

    /**
     * Quando lo chiami e basta («SAM»): il saluto del mattino, «com'è andata?», un'abitudine. Imposta la risposta
     * che aspetta e ritorna cosa dire; null = niente (allora «ti ascolto» come sempre).
     */
    fun offerMind(): String? {
        val m = mind ?: return null
        if (pending != null) return null
        val d = m.wakeOffer(mem.ownerName(), mem.memos(), now()) ?: return null
        askAfter(d.ask)
        return d.text
    }

    private fun plansAnswer(whenTxt: String): String {
        val m = mind!!
        val today = today()
        val memos = mem.memos()
        fun dayList(d: java.time.LocalDate, label: String) = m.dayText(d, memos, label)
        return when {
            whenTxt.isEmpty() || whenTxt == "oggi" || whenTxt == "stasera" -> dayList(today, "Oggi") ?: "Per oggi non mi hai detto impegni."
            whenTxt == "domani" -> dayList(today.plusDays(1), "Domani") ?: "Per domani non mi hai detto impegni."
            whenTxt == "dopodomani" -> dayList(today.plusDays(2), "Dopodomani") ?: "Per dopodomani non mi hai detto impegni."
            whenTxt in Plans.DAYS -> { val d = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(Plans.DAYS.indexOf(whenTxt) + 1)))
                dayList(d, whenTxt.replaceFirstChar { it.uppercase() }) ?: "Per $whenTxt non mi hai detto impegni." }
            else -> {
                val (a, b) = when (whenTxt) {
                    "la settimana prossima" -> today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
                    "nel weekend", "questo weekend" -> today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY)).let { it to it.plusDays(1) }
                    else -> today to today.plusDays(7)
                }
                val days = generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.mapNotNull { d -> dayList(d, m.relDay(d, today).replaceFirstChar { it.uppercase() }) }.toList()
                if (days.isEmpty()) "Non mi hai detto impegni per ${whenTxt.ifEmpty { "i prossimi giorni" }}." else days.joinToString(" ")
            }
        }
    }

    private fun diaryAnswer(period: String): String {
        val m = mind!!
        val q = if (period.isEmpty()) "" else period
        val eps = if (q.isEmpty()) m.episodes().takeLast(4) else m.recall(q, now(), 6)
        if (eps.isEmpty()) return if (q.isEmpty()) "Il diario è ancora vuoto: lo scrivo quando sono fermo, riordinando le nostre conversazioni."
            else "Nel diario non trovo niente $q."
        return "Dal mio diario. " + eps.joinToString(" ") { m.episodeLine(it, now()).replaceFirstChar { c -> c.uppercase() } }
    }

    /** «Come lo sai?», «sei sicuro?»: da dove viene l'ultima cosa detta. */
    private fun sourceAnswer(t: String): Outcome {
        val src = mind?.lastSource ?: return Outcome.Speak("Non ho ancora detto niente da verificare.")
        val unsure = ci("\\b(?:sicur[oa]|cert[oa]|davvero)\\b").containsMatchIn(t)
        return when (src.kind) {
            "memoria" -> {
                val (txt, at) = src.detail.split('\u0000').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
                val whenTxt = if (at > 0) java.time.Instant.ofEpochMilli(at).atZone(mind!!.zone).toLocalDate().let { d -> " il ${d.dayOfMonth} ${Plans.MONTHS[d.monthValue - 1]}" } else ""
                Outcome.Speak("Me l'hai detto tu$whenTxt: «$txt».", endSession = true)
            }
            "enciclopedia" -> Outcome.Speak("L'ho letto nell'enciclopedia che ho sul dispositivo, alla voce «${src.detail}». Di solito è affidabile.", endSession = true)
            "web" -> Outcome.Speak("L'ho trovato su internet" + (if (src.detail.isNotBlank()) ": ${src.detail}" else "") + ". Le fonti le vedi sotto la risposta.", endSession = true)
            "conversazione" -> Outcome.Speak("Da quello che ci siamo detti: rileggo le nostre conversazioni e il mio diario.", endSession = true)
            "comando" -> Outcome.Speak("È un dato che leggo direttamente dal dispositivo, quindi è sicuro.", endSession = true)
            else -> {
                val base = "Viene da quello che sa il mio cervello" + (if (src.detail.isNotBlank()) ", ${src.detail}" else "") + ": di solito ci prende, ma può sbagliare, soprattutto su numeri, date e cose recenti."
                if (unsure && src.question.isNotBlank()) Outcome.Speak(confirm("$base Vuoi che controlli su internet?",
                    yes = { Outcome.Web(src.question) }, no = { Outcome.Speak("Va bene.", endSession = true) }))
                else Outcome.Speak(base, endSession = true)
            }
        }
    }

    /** L'ora, la data, la batteria, i calcoli: dati del dispositivo (per «come lo sai?»). */
    private fun noteSource(t: String, o: Outcome) {
        val m = mind ?: return
        if (o is Outcome.Speak && ci("^(?:che ore|che giorno|quanta batteria|che data|quanto fa|che giorno è)").containsMatchIn(t)) m.source("comando", "", t, now())
    }

    // curiosità: "facciamo conoscenza" → qualche domanda, le risposte diventano ricordi
    private val KNOW_TOPICS = listOf(
        Triple("lavoro", "Che lavoro fai?", "Il mio lavoro: "),
        Triple("casa", "In che zona abiti?", "Abito: "),
        Triple("famiglia", "Chi c'è nella tua famiglia?", "La mia famiglia: "),
        Triple("tempo", "Cosa ti piace fare nel tempo libero?", "Nel tempo libero mi piace: "),
        Triple("cibo", "Qual è il tuo piatto preferito?", "Il mio piatto preferito: "),
        Triple("musica", "Che musica ti piace?", "La musica che mi piace: "),
        Triple("obiettivo", "C'è un obiettivo a cui tieni in questo periodo?", "Un mio obiettivo: ")
    )

    private fun getToKnow(from: Int, first: Boolean = false): Outcome {
        val known = mem.memos().map { it.key }.toSet()
        val i = (from until KNOW_TOPICS.size).firstOrNull { "conosci:${KNOW_TOPICS[it].first}" !in known && !(KNOW_TOPICS[it].first == "casa" && "casa" in known) }
        if (i == null) return Outcome.Speak(if (first) "Ci conosciamo già bene! Se vuoi dirmi qualcos'altro di te, raccontamelo pure." else
            "Grazie! Mi è piaciuto conoscerti un po' di più. Me lo ricorderò.", endSession = true)
        val (topic, question, prefix) = KNOW_TOPICS[i]
        pending = { ans ->
            when {
                ci("^\\W*(?:basta|stop|fine|finiamo|smettiamo|ok basta|basta così|per ora basta)\\W*$").containsMatchIn(ans) ->
                    Outcome.Speak("Va bene, per ora basta. Grazie! Quando vuoi continuiamo: di' facciamo conoscenza.", endSession = true)
                ci("^\\W*(?:passo|salta|la salto|non lo so|non so|preferisco non dirlo|non te lo dico|niente|boh|prossima|un'altra)\\W*$").containsMatchIn(ans) -> getToKnow(i + 1)
                Remember.isCommand(ans) -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
                else -> {
                    val txt = ans.trim().trimEnd('.', '!').take(120)
                    mem.memos().filter { it.key == "conosci:$topic" }.forEach { mem.removeMemo(it) }
                    mem.addMemo(Memo(Remember.FATTO, prefix + txt, "conosci:$topic", now()))
                    memoryUrgent = true
                    mindNotes.add("ho imparato «$prefix$txt»")
                    val next = getToKnow(i + 1)
                    if (next is Outcome.Speak) Outcome.Speak(listOf("Bello!", "Interessante!", "Me lo segno.", "Capito!").random() + " " + next.text, endSession = next.endSession)
                    else next
                }
            }
        }
        return Outcome.Speak((if (first) "Volentieri! Ti faccio qualche domanda: rispondi come vuoi, di' passo per saltare e basta per finire. " else "") + question)
    }

    private fun mindExplain(): String {
        val m = mind!!
        val memos = mem.memos()
        val facts = memos.count { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        return "La mia mente funziona un po' come la tua. Ho una memoria per i fatti su di te, $facts in tutto: quelli che usiamo restano vivi, gli altri sbiadiscono ma non spariscono. " +
            "Tengo un diario delle nostre conversazioni, ${m.episodes().size} episodi, che scrivo quando sono fermo, come quando si dorme. " +
            "Mi segno da solo gli impegni che mi dici e poi ti chiedo com'è andata; imparo le tue abitudini, ${m.habits(now()).size} per ora; " +
            "capisco come ti senti e cambio tono; alle domande difficili ci penso prima di rispondere; e so dirti da dove viene quello che dico. " +
            "Tutto resta qui sul dispositivo. Per vedere tutto apri il pannello della mente."
    }

    private fun crisisReply(): Outcome = Outcome.Speak(confirm(Feelings.crisisReply(),
        yes = { if (a.call("0223272327")) Outcome.Handled(endSession = true) else Outcome.Speak("Non riesco a chiamare da qui: il numero è 02 2327 2327. Io resto qui con te.") },
        no = { Outcome.Speak("Va bene. Io resto qui: se vuoi dirmi cosa ti fa stare così, ti ascolto. E se sei in pericolo, chiama il 112.") }))

    // ------------------------------------------------------------------ il nome dell'assistente (1.22)
    private val RENAME = ci(
        "^$FILL(?:(?:allora|ok|bene|senti)\\s+)?(?:da ora(?: in poi)?|d'ora in poi|da adesso(?: in poi)?|da oggi(?: in poi)?|adesso|ora)?\\s*,?\\s*" +
        "(?:(?:tu )?ti chiami|ti chiamerai|ti chiamerò|chiamati|il tuo nome (?:è|sarà|e)|il tuo nuovo nome (?:è|sarà)|" +
        "cambia(?:ti)? (?:il )?(?:tuo )?nome in|cambia(?:ti)? (?:il )?(?:tuo )?nome e chiamati|voglio chiamarti|ti voglio chiamare|vorrei chiamarti|" +
        "ti rinomino|rinominati|ti do il nome(?: di)?|ti metto nome|ti metto il nome(?: di)?)\\s+(.+)$"
    )
    private val RENAME_ASK = ci("^$FILL(?:(?:voglio|vorrei|posso|si può|puoi|potrei|devo)\\s+)?(?:cambia(?:ti|rti|rgli|re)?|cambiarti|cambiargli)\\s+(?:il )?(?:tuo |suo )?nome\\??$")
    private val RENAME_RESET = ci("^$FILL(?:torna a chiamarti|richiamati|rimettiti il nome|torna al nome|riprendi il nome|ritorna a chiamarti)\\s+(?:sam|sem|il tuo nome di prima|come prima)$|" +
        "^$FILL(?:torna|ritorna) (?:a essere |ad essere )?(?:sam|come prima|il nome di prima)$")
    private val RENAME_LEARN = ci("^$FILL(?:impara|ascolta|senti) come (?:dico|pronuncio|chiamo) (?:il tuo nome|il tuo nuovo nome|te)$|^$FILL(?:non )?(?:mi )?(?:senti|capisci) quando (?:ti chiamo|dico il tuo nome)\\??$")

    /** "Da ora ti chiami Jarvis", "chiamati Luna", "torna a chiamarti SAM", "voglio cambiarti nome". null = non è per il nome. */
    private fun renameCommand(t: String, raw: String): Outcome? {
        if (RENAME_RESET.containsMatchIn(t)) return Outcome.Rename(Identity.DEFAULT, "reset")
        if (RENAME_LEARN.containsMatchIn(t)) return Outcome.Rename(Identity.name, "learn")
        if (RENAME_ASK.containsMatchIn(t)) {
            pending = { ans ->
                val n = Identity.clean(ans.replace(ci("^(?:(?:chiamati|ti chiami|voglio chiamarti|il nome è|mettiti)\\s+)"), ""))
                if (n == null) Outcome.Speak("Non mi sembra un nome. Quando vuoi di': da ora ti chiami, e il nome.", endSession = true)
                else Outcome.Rename(n)
            }
            return Outcome.Speak("Certo! Come vuoi chiamarmi?")
        }
        val m = RENAME.find(t) ?: return null
        // il nome come l'hai scritto (con le maiuscole), se c'è
        val said = m.groupValues[1].trim()
        val orig = Regex("(?i)" + Regex.escape(said) + "\\W*$").find(raw.trim())?.value ?: said
        val n = Identity.clean(orig) ?: return Outcome.Speak("«$said» non mi sembra un nome: usa una o due parole, solo lettere. Per esempio: da ora ti chiami Jarvis.")
        if (n == Identity.name) return Outcome.Speak("Mi chiamo già $n!")
        return Outcome.Rename(n)
    }

    // ------------------------------------------------------------------ guardare con la fotocamera
    // Come per la memoria: una frase per guardare che sfugge va al cervello IA (che risponde "non posso vedere")
    // oppure, se contiene "fotocamera", apre l'app Fotocamera e SAM non guarda niente.
    private val CAM = "(?:(?:la|nella|dalla|con la|attraverso la|sulla|alla|tua|la tua) )?(?:fotocamera|camera|telecamera|videocamera)"
    private val SEE_THING = "pianta|fiore|animale|oggetto|frutto|insetto|fungo|pesce|uccello|marca|modello|razza|colore|tipo|cosa|albero|" +
        "cane|gatto|macchina|auto|moneta|banconota|medicina|farmaco|vino|formaggio|libro|quadro|edificio|monumento|piatto|cibo"
    private val LOOK2 = ci(
        // "dimmi cosa vedi", "che cosa riesci a vedere", "cosa vedi dalla fotocamera"
        "^$FILL(?:(?:dimmi|descrivimi|raccontami|spiegami|fammi sapere)\\s+)?(?:cosa|che cosa|che|quello che|ciò che)\\s+" +
        "(?:vedi|stai vedendo|riesci a vedere|puoi vedere|inquadri|vede la fotocamera|si vede(?=\\s*$|\\s+(?:qui|qua|$CAM)))" +
        "(?:\\s+(?:qui|qua|adesso|ora|davanti a me|davanti|intorno|intorno a me|in giro|bene|di fronte|$CAM|attraverso $CAM|" +
        "in questa foto|nella foto))*(?:\\s+e\\s+.*)?$|" +
        "^$FILL(?:dimmi|descrivimi)\\s+(?:cosa|che cosa) c'è (?:qui|qua|davanti|davanti a me|intorno|intorno a me|in giro|in mano|nella foto)$|" +
        // "mi vedi?", "riesci a vedere?", "vedi qualcosa?", "ci vedi?", "vedi la fotocamera?"
        "^$FILL(?:(?:mi |ti |ci )?(?:riesci a|puoi|sai|ce la fai a)\\s+)?(?:mi |ci )?(?:vedi|vedere|vedermi|guardare|guardarmi)" +
        "(?:\\s+(?:qualcosa|bene|niente|me|questo|questa|qui|qua|adesso|ora|ancora|$CAM|cosa ho in mano|cosa c'è))*$|" +
        // "guarda con la fotocamera", "guarda questo e dimmi cos'è", "inquadra questo"
        "^$FILL(?:guarda|guardami|osserva|dai un'occhiata|inquadra)(?:\\s+(?:qui|qua|bene|un po'|attentamente|$CAM|" +
        "questo|questa|questi|queste|quello|quella|quest'oggetto|questo oggetto|davanti|intorno|" +
        "cosa (?:ho|c'è|tengo)(?: in mano| qui| qua| davanti)?))*(?:\\s+e\\s+(?:dimmi|descrivi|descrivimi|leggi|leggimi|spiegami|riconosci)\\b.*)?$|" +
        // "apri la fotocamera e dimmi cosa vedi", "usa la fotocamera per guardare"
        "^$FILL(?:apri|accendi|usa|attiva|prendi|avvia)\\s+$CAM(?:\\s+(?:posteriore|davanti|frontale))?\\s+(?:(?:e|per|e poi|poi)\\s+)?" +
        "(?:dimmi|guarda|guardare|vedi|vedere|descrivi|descrivere|descrivimi|leggi|leggere|leggimi|riconosci|riconoscere|scopri|capisci)\\b|" +
        // "usa la fotocamera", "puoi usare gli occhi?"
        "^$FILL(?:usa|usare|attiva|attivare)\\s+(?:$CAM|gli occhi|i tuoi occhi|la vista|la tua vista)$|" +
        // "riconosci questo", "identifica questa pianta"
        "^$FILL(?:riconosci|riconoscimi|identifica|identificami)\\s+(?:(?:il|la|l'|lo|i|le|gli|questo|questa|questi|queste|quest'|quello|quella)\\s*)?" +
        "(?:questo|questa|questi|queste|quello|quella|cosa ho in mano|cosa c'è|$SEE_THING)\\b" +
        "(?!\\s+(?:canzone|musica|brano|voce|suono|rumore|melodia|pezzo|verso|numero))|" +
        // "dimmi che pianta è questa", "di che colore è questo"
        "^$FILL(?:dimmi\\s+|sai dirmi\\s+|mi dici\\s+)?(?:che|quale|quali|di che) (?:$SEE_THING) (?:è|sono|ha|hanno) " +
        "(?:questo|questa|quello|quella|questi|queste|quest')|" +
        // "dimmi cosa c'è scritto", "dimmi cos'è questo"
        "^$FILL(?:dimmi|leggimi|sai dirmi|mi dici)\\s+(?:cosa|che cosa|quello che) c'è scritto\\b|" +
        "^$FILL(?:dimmi|sai dirmi|mi dici)\\s+(?:cos'è|che cos'è|cosa è|che cosa è) (?:questo|questa|quello|quella)\\b|" +
        // "come sono vestito?", "di che colore è la mia maglietta?"
        "^$FILL(?:come sono vestit[oa]|come sto vestit[oa]|come sono pettinat[oa]|come mi sta(?:nno)? (?:questo|questa|questi|queste|il|la|lo|i|le|gli)\\b.*|" +
        "di che colore (?:è|sono) (?:la mia|il mio|i miei|le mie)\\b.*)$|" +
        // "scatta una foto e dimmi cosa vedi", "fammi un selfie e dimmi come sto"
        "^$FILL(?:fai|scatta|fammi)\\s+(?:una |un )?(?:foto|selfie|fotografia)\\s+e\\s+(?:dimmi|descrivi|descrivimi|leggi|leggimi|guarda|spiegami)\\b"
    )
    // guardare te: la fotocamera davanti
    private val SEE_ME = ci(
        "\\b(?:mi vedi|vedermi|guardarmi|mi riesci a vedere|mi puoi vedere|guardami|guarda me|come sono vestit[oa]|come sto vestit[oa]|" +
        "come mi sta|come sono pettinat[oa]|la mia faccia|il mio viso|la mia maglietta|la mia camicia|la mia felpa|la mia giacca|" +
        "il mio vestito|i miei capelli|i miei occhi|selfie|fotocamera (?:davanti|frontale|anteriore))\\b"
    )
    // "hai gli occhi?", "come faccio a farti vedere una cosa?", "perché non vedi la fotocamera?": spiegare
    private val SIGHT_INFO = ci(
        "^$FILL(?:ma |e |però )?(?:" +
        "come (?:faccio a|posso|si fa a|devo fare per|devo fare a) (?:farti |fartelo |fartela )?(?:vedere|guardare|leggere)" +
        "(?: (?:qualcosa|una cosa|le cose|una foto|un oggetto|un foglio|con la fotocamera))?|" +
        "come funziona la (?:tua )?(?:vista|fotocamera)|come (?:fai a|riesci a) vedere|" +
        "(?:tu )?(?:hai|ce l'hai) (?:gli occhi|la vista|una vista|una fotocamera)|" +
        "(?:(?:sai|puoi|riesci a) )?(?:guardare|vedere|leggere|capire) (?:le |delle )?(?:foto|immagini|fotografie)|" +
        "(?:vedi|guardi|capisci) (?:le |delle )?(?:foto|immagini|fotografie)|" +
        "(?:perch[eé] )?(?:mi )?(?:dici|hai detto|rispondi) che non (?:puoi|riesci a|sai) (?:vedere|guardare)(?: (?:niente|nulla|$CAM))?|" +
        "(?:perch[eé] )?non (?:puoi|riesci a|sai) (?:vedere|guardare)(?: (?:niente|nulla|$CAM))?|" +
        "(?:perch[eé] )?non (?:vedi|ci vedi|guardi)(?: (?:niente|nulla|$CAM))?" +
        ")$"
    )

    /** "Cosa vedi?" e tutti gli altri modi di chiedere di guardare. null = la frase non chiede di guardare. */
    private fun sightCommand(t: String, raw: String): Outcome? {
        if (SIGHT_INFO.containsMatchIn(t)) return Outcome.Look(raw, explain = true)
        if (LOOK.containsMatchIn(t) || LOOK2.containsMatchIn(t)) return Outcome.Look(raw, front = SEE_ME.containsMatchIn(t))
        // "apri la fotocamera": SAM guarda con la sua, che si richiude da sola (l'app Fotocamera resta aperta
        // e intanto SAM non ti sente). Se SAM non ci vede, si apre l'app Fotocamera.
        if (CAM_OPEN.containsMatchIn(t))
            return Outcome.Look(raw, front = ci("\\b(?:davanti|frontale|anteriore|selfie)\\b").containsMatchIn(t), openIfBlind = true)
        return null
    }

    private val CAM_OPEN = ci(
        "^$FILL(?:(?:mi |ti )?(?:puoi|potresti|riesci a|devi|dovresti)\\s+|(?:voglio|vorrei) che (?:tu )?)?(?:mi )?" +
        "(?:apri|aprire|aprimi|accendi|accendere|attiva|attivare|avvia|avviare|prendi|fai partire)\\s+" +
        "(?:la |una |tua |la tua )?(?:fotocamera|camera|telecamera|videocamera)" +
        "(?:\\s+(?:posteriore|dietro|davanti|frontale|anteriore|del telefono|per favore|adesso|ora|subito|un attimo|un momento))*$|" +
        "^$FILL(?:la )?(?:fotocamera|camera)(?:\\s+(?:posteriore|davanti|frontale|anteriore))?$"
    )

    private val LOOK = ci(
        "^(?:cosa|che cosa) (?:vedi|stai vedendo)(?:\\s+(?:qui|qua|adesso|ora|davanti a me|davanti|intorno|in giro|con la fotocamera))?$|" +
        "^(?:cosa|che cosa|che) (?:c'è|ho) (?:qui|qua|davanti a me|davanti|in mano|in questa mano|nella mano|sul tavolo|in questa foto)$|" +
        "^(?:guarda|guardami|dai un'occhiata|osserva)(?:\\s+(?:qui|qua|questo|questa|bene|un po'|cosa ho in mano|cosa c'è))*$|" +
        "^(?:leggimi|leggi|puoi leggere|mi leggi)\\s+(?:questo|questa|qui|qua|cosa c'è scritto|quello che c'è scritto|il cartello|l'etichetta|" +
        "lo scontrino|la ricetta|il foglio|la pagina|il biglietto|la lettera|lo schermo|il display|il prezzo|la targa|il menu)\\b|" +
        "^(?:cosa|che cosa) c'è scritto\\b|" +
        "^(?:cos'è|che cos'è|cosa è|che cosa è) (?:questo|questa|quello|quella|qui|qua)$|" +
        "^descrivi(?:mi)?\\s+(?:quello che vedi|cosa vedi|cosa c'è|la scena|la stanza|questo|questa|intorno|il posto|la foto)\\b|" +
        "^(?:di che colore|che colore) (?:è|ha|sono|hanno)\\s+(?:questo|questa|quello|quella|questi|queste)\\b|" +
        "^(?:che|quale|quali) (?:pianta|fiore|animale|oggetto|frutto|insetto|fungo|pesce|uccello|marca|modello|razza) (?:è|sono) (?:questo|questa|quello|quella|questi|queste)\\b|" +
        "^(?:fai|scatta) una foto e (?:dimmi|descrivi|leggi)\\b"
    )

    // ------------------------------------------------------------------ memoria
    // Tutti i modi di chiedere di ricordare: se uno sfugge, la frase va al cervello IA, che per abitudine
    // risponde "non posso memorizzare" (i modelli sono addestrati così) e il ricordo non viene salvato.

    // "puoi…", "riesci a…": chiedono se SAM ne è capace (con "le cose" è una domanda, non una cosa da ricordare)
    // ("vuoi": il riconoscitore a volte sente così "puoi")
    private val ABLE = "puoi|potresti|vuoi|riesci a|riusciresti a|ce la fai a|sei capace di|sei in grado di|saresti capace di|sai"
    private val MODAL = "(?:(?:mi |ti )?(?:$ABLE|devi|dovresti|cerca di|prova a|ti chiedo di|ti prego di)\\s+|" +
        "(?:voglio|vorrei|mi piacerebbe|desidero) che (?:tu )?)"
    // pronomi davanti al verbo: "te lo ricordi", "lo memorizzi", "ti ricordi" ("i": "ti" sentito a metà dal riconoscitore)
    private val CLITIC = "(?:(?:te|ti|me|lo|la|li|le|ce|se|i)\\s+)*"
    // con il pronome attaccato ("ricordatelo", "memorizzalo"): la cosa da ricordare è quella appena detta
    private val V_PRON = "ricordatel[oaie]|ricordal[oaie]|ricordarl[oaie]|ricordartel[oaie]|memorizzatel[oaie]|memorizzal[oaie]|" +
        "memorizzarl[oaie]|memorizzartel[oaie]|segnatel[oaie]|segnarl[oaie]|segnartel[oaie]|annotatel[oaie]|annotal[oaie]|" +
        "annotarl[oaie]|annotartel[oaie]|appuntatel[oaie]|appuntal[oaie]|appuntartel[oaie]|salvatel[oaie]|salval[oaie]|" +
        "salvarl[oaie]|salvartel[oaie]|tienil[oaie] (?:a mente|presente)|tenerl[oaie] (?:a mente|presente)|" +
        "tenertel[oaie] (?:a mente|presente)|non dimenticartel[oaie]|non dimenticarl[oaie]|non scordartel[oaie]|non scordarl[oaie]"
    // imperativi: la cosa da ricordare segue
    private val V_IMP = "ricordati|ricordatevi|ricordami|ricorda|memorizzati|memorizza|segnati|annotati|annota|appuntati|" +
        "prendi nota|prendine nota|tieni a mente|tieni presente|tieni in mente|fissati in mente|non dimenticarti|non dimenticare|" +
        "non scordarti|non scordare|salvati|salva (?:nella|in) (?:tua )?memoria|salva nei (?:tuoi )?ricordi|" +
        "metti (?:nella|in) (?:tua )?memoria|metti nei (?:tuoi )?ricordi|scrivi (?:nella|in) (?:tua )?memoria|" +
        "scrivi nei (?:tuoi )?ricordi|aggiungi (?:alla (?:tua )?memoria|ai (?:tuoi )?ricordi|una nota|alle note)"
    // valgono solo con "che": "segna che…", "sappi che…" ("salva la foto" non è un ricordo)
    private val V_CHE = "segna|salva|appunta|sappi|tieni conto|ti ricordo|impara"
    // all'infinito: "puoi ricordare…", "devi memorizzare…" (oppure da solo, perché "puoi" viene tolto prima)
    private val V_INF = "ricordare|ricordarti|memorizzare|segnare|segnarti|annotare|annotarti|appuntare|appuntarti|" +
        "salvare|salvarti|tenere a mente|tenere presente|imparare"
    // coniugati: solo dopo "voglio che", "devi"… ("ricordi dove ho parcheggiato?" è una domanda)
    private val V_FIN = "ricordi|ricorderai|ricordassi|memorizzi|memorizzerai|memorizzassi|segni|segnassi|annoti|salvi|salverai|" +
        "terrai a mente|tenga a mente|tenessi a mente|impari|imparassi"
    private val REQ = ci(
        "^$FILL($MODAL)?($CLITIC)($V_PRON|$V_IMP|$V_CHE|$V_INF|$V_FIN)\\b" +
        "(?:\\s+(?:per sempre|bene|sempre|tutto|mi raccomando))?\\s*" +
        "(?:(?:anche\\s+)?(che|di)\\s+|:\\s*|(questo|questa cosa|una cosa|la cosa)\\b\\s*:?\\s*)?(.*)$"
    )
    // il riconoscitore a volte stacca il pronome: "memorizza lo", "ricorda te lo" (solo a fine frase: "ricordati la spesa" è un'altra cosa)
    private val PRON_ALONE = ci(
        "^$FILL($MODAL)?(?:memorizza|ricorda|ricordati|segna|salva|annota|appunta|tieni) (?:te )?l[oaie](?: a mente)?" +
        "(?:\\s+(?:per sempre|bene|mi raccomando|per favore))*$"
    )
    // "il mio cane si chiama Rex, ricordatelo": la cosa da ricordare è all'inizio
    private val TRAIL_FORMS = "ricordatel[oa]|ricordal[oa]|memorizzal[oa]|memorizza l[oa]|memorizzatel[oa]|memorizzarl[oa]|ricordarl[oa]|" +
        "ricordartel[oa]|tienil[oa] (?:a mente|presente)|segnatel[oa]|salval[oa]|salvatel[oa]|annotatel[oa]|appuntatel[oa]|" +
        "non dimenticartel[oa]|non dimenticarl[oa]|non scordartel[oa]|non scordarl[oa]|ricordati (?:questa cosa|questo)|" +
        "memorizza (?:questa cosa|questo)|(?:te|me) l[oa] (?:ricorderai|ricordi)|ti ricorderai|" +
        "(?:(?:mi |ti )?(?:puoi|potresti|riesci a|devi|dovresti) )(?:ricordartel[oa]|ricordarl[oa]|memorizzarl[oa]|segnartel[oa]|tenerl[oa] a mente)|" +
        "l[oa] (?:puoi|potresti|devi|riesci a) (?:ricordare|memorizzare|segnare|tenere a mente)"
    private val TRAIL = ci(
        "^(.+?)[,\\s]+(?:e\\s+|mi raccomando\\s+)?(?:$TRAIL_FORMS)(?:\\s+(?:per sempre|bene|mi raccomando|eh|ok|per favore|grazie|davvero))*$"
    )
    // "hai una memoria?", "ti ricorderai?", "perché dici che non puoi memorizzare?": come funziona la memoria
    private val ABILITY = ci(
        "^$FILL(?:ma |e |però |quindi )?(?:" +
        "(?:tu )?(?:hai|ce l'hai|avresti|possiedi) (?:una |la |della |un po' di |anche (?:una )?)?memoria(?: (?:permanente|lunga|tua|anche tu))?|" +
        "(?:tu )?hai (?:dei |i )?ricordi|" +
        "(?:ti |te |me )?(?:ne |lo |la |li |le )?(?:ricorderai|ricorderesti|memorizzerai|terrai a mente)" +
        "(?: (?:di me|di tutto|di questo|di quello|di questa cosa|le cose|tutto|questo|quello|questa cosa|quello che ti (?:dico|ho detto|racconto)|" +
        "domani|dopo|anche domani|anche dopo|per sempre|davvero|veramente))*|" +
        "(?:te|me) (?:lo|la) ricordi(?: (?:dopo|domani|per sempre|davvero))?|" +
        "(?:ti )?ricordi (?:le cose|tutto|quello che ti dico|cosa ti dico|le cose che ti dico|quello che ti racconto|le cose che ti racconto|" +
        "le nostre conversazioni|le conversazioni)(?: (?:per sempre|dopo|domani|anche dopo|davvero))?|" +
        "(?:memorizzi|impari|salvi|conservi|tieni a mente) (?:le cose|tutto|quello che ti dico|cosa ti dico|le cose che ti dico|quello che ti racconto)|" +
        "come (?:faccio a|posso|si fa a|devo fare per|devo fare a) (?:farti |fartelo |fartela )?(?:ricordare|memorizzare|salvare)" +
        "(?: (?:qualcosa|una cosa|le cose|delle cose))?|" +
        "come ti (?:faccio|posso far) (?:ricordare|memorizzare)(?: (?:qualcosa|una cosa|le cose))?|" +
        "come funziona la (?:tua )?memoria|" +
        "dove (?:salvi|tieni|metti|conservi) (?:i ricordi|le cose|quello che ti dico|la memoria|i tuoi ricordi)|" +
        "(?:perch[eé] )?(?:mi )?(?:dici|hai detto|rispondi) che non (?:puoi|riesci a|sai|sei capace di) " +
        "(?:ricordare|ricordarti|memorizzare|tenere a mente|salvare)(?: (?:le cose|niente|nulla|quello che ti dico|cosa ti dico))?|" +
        "(?:perch[eé] )?non (?:puoi|riesci a|sai|vuoi|sei capace di) (?:ricordare|ricordarti|memorizzare|tenere a mente|salvare)" +
        "(?: (?:le cose|niente|nulla|quello che ti dico|cosa ti dico))?|" +
        "(?:è vero che )?non hai (?:una |la )?memoria|" +
        "(?:perch[eé] )?non ti ricordi (?:mai )?(?:niente|nulla)" +
        ")$"
    )
    // "ti ricordi quello che ti ho detto ieri?": la lista dei ricordi
    private val TOLD_BEFORE = ci(
        "^(?:ti |te )?ricordi (?:(?:tutto )?quello|cosa|che cosa|le cose|tutte le cose) che ti ho (?:detto|raccontato|chiesto)" +
        "(?: (?:di ricordare|ieri|oggi|prima|stamattina|stasera|l'altro giorno|la settimana scorsa|l'altra volta|ieri sera|tempo fa))*$"
    )
    // "ti devo dire una cosa da ricordare"
    private val TO_TELL = ci(
        "^$FILL(?:ti |te )?(?:devo|voglio|vorrei|posso) dir(?:ti|e|tela|telo)? (?:una cosa|qualcosa|delle cose|un paio di cose)" +
        "(?: importanti?)? (?:da ricordare|da memorizzare|da segnare|da tenere a mente|che devi ricordare|che devi ricordarti)$"
    )
    // dopo il verbo: "le cose", "quello che ti dico" = domanda su come funziona; "quello che ti ho detto" = la frase di prima
    private val GENERIC = ci(
        "^(?:tutte |tutti )?(?:le cose|cose|tutto|quello che ti dico|cosa ti dico|ciò che ti dico|le cose che ti dico|quello che ti racconto|" +
        "le cose che ti racconto|le informazioni|informazioni|i dati|dati|le conversazioni|la conversazione|le nostre conversazioni|" +
        "i miei dati|nomi|i nomi|numeri|i numeri|delle cose|le cose importanti)(?: che ti (?:dico|racconto|chiedo|dirò))?" +
        "(?: (?:per sempre|in futuro|dopo|domani|nel tempo|a lungo))?$"
    )
    private val SOMETHING = ci("^(?:qualcosa|una cosa|un'altra cosa|altre cose|un paio di cose|qualche cosa)(?: per me| importante| di importante)?$")
    private val PREV = ci(
        "^(?:(?:tutto )?(?:quello|questo|ciò|quanto)(?: che ti ho (?:appena )?detto| che ho detto| appena detto| di prima)?|" +
        "questa cosa|quella cosa|la cosa di prima|l'ultima cosa(?: che ti ho detto)?|quello di prima)$"
    )
    private val EMPTYISH = ci("^(?:(?:in memoria|nella (?:tua )?memoria|nei (?:tuoi )?ricordi|per sempre|bene|mi raccomando|ok|eh|per favore|grazie)\\s*)*$")

    // l'ultima cosa che mi hai raccontato (non un comando, non una domanda): per "ricordatelo", "te lo ricorderai?"
    private var lastTold: String? = null
    private var lastToldAt = 0L
    private val TOLD_FOR_MS = 5 * 60_000L
    private fun recentTold(): String? = lastTold?.takeIf { now() - lastToldAt <= TOLD_FOR_MS }

    private val NOT_STATEMENT = ci(
        "^(?:chi|che|cosa|cos'|cos|come|com'|dove|quando|perché|perche|quanto|quanta|quanti|quante|quale|quali|qual|è vero|sai|" +
        "secondo te|mi dici|dimmi|spiegami|raccontami|parlami|dammi|fammi|consigliami|aiutami|puoi|potresti|vuoi|hai|sei|" +
        "ti piace|conosci|esiste|ciao|grazie|buongiorno|buonasera|buonanotte|ok|okay|sì|si|no|va bene|bene|perfetto|scusa)\\b"
    )

    /** true se è una cosa raccontata (non una domanda né un saluto): si può ricordare. */
    fun isStatement(raw: String): Boolean {
        val t = raw.trim().lowercase().trim(' ', '.', ',', '!')
        if (t.endsWith("?") || Remember.words(t).size < 2) return false
        return !NOT_STATEMENT.containsMatchIn(t)
    }
    // "cosa ricordi?", "cosa sai di me?", "leggi le note"
    private val LIST = ci(
        "^(?:cosa|che cosa|che|quali cose)\\s+(?:ricordi|ti ricordi|sai di me|sai su di me|hai memorizzato|hai in memoria|" +
        "ti ho chiesto di ricordare|ti ho detto di ricordare|devo ricordare|c'è nella tua memoria|c'è in memoria|hai imparato|" +
        "hai imparato su di me|hai imparato di me)$|" +
        "^(?:leggi|leggimi|elenca|dimmi|ripeti|ripetimi)\\s+(?:tutte\\s+|tutti\\s+)?(?:le note|le mie note|i ricordi|i tuoi ricordi|" +
        "la memoria|la tua memoria|cosa ricordi|le cose che ricordi|cosa sai di me|le scorciatoie|le tue scorciatoie)$|" +
        "^(?:le mie note|i tuoi ricordi|la tua memoria|le scorciatoie|quali scorciatoie conosci|che scorciatoie conosci|quali scorciatoie hai)$|" +
        "^(?:quali|che) (?:regole|istruzioni) (?:ti ho dato|hai|conosci|segui|devi seguire|ti ho insegnato)$|" +
        "^(?:dimmi|leggimi|elenca|ripetimi) (?:le|tutte le) (?:mie |tue )?(?:regole|istruzioni)(?: che ti ho dato)?$|^(?:le )?(?:tue |mie )?regole$|" +
        "^(?:quali|che) lezioni (?:hai imparato|hai|conosci|ti ho dato|ti ho insegnato)$|^(?:dimmi|leggimi|elenca) (?:le|tutte le) (?:tue )?lezioni$|" +
        "^(?:cosa|che cosa) (?:hai imparato dalle mie correzioni|ti ho insegnato)$|^cos'hai (?:imparato|memorizzato|in memoria)(?: su di me| di me)?$"
    )
    // "ti ricordi del dentista?", "ricordi dove ho parcheggiato?"
    private val ASK_MEM = ci("^(?:ti |te )?ricordi\\s+(?:(?:che|di|del|della|dello|dei|delle|degli|quando|dove|come|cosa|se|il|la|lo|l'|i|gli|le)\\s+)?(.+)$")
    // "cosa ti ho detto di Marco?", "cosa sai di mia moglie?"
    private val ASK_ABOUT = ci(
        "^(?:cosa|che cosa|che)\\s+(ti ho detto|ricordi|ti ricordi|hai memorizzato|sai)\\s+" +
        "(?:di|su|sul|sulla|sullo|sui|sulle|riguardo a|riguardo|a proposito di|del|della|dello|dei|delle|degli|dell'|sull')\\s*(.+)$"
    )
    // "ricordami dove ho parcheggiato": una domanda, non una cosa da ricordare
    private val REMIND_Q = ci("^(?:ricordami|ricordarmi|mi ricordi|me lo ricordi)\\s+((?:cosa|che cosa|chi|dove|quando|come|quanto|quanti|qual|quale|il nome|a che ora)\\b.*)$")
    // "dimentica tutto", "cancella tutti i ricordi" (con conferma)
    private val FORGET_ALL = ci(
        "^(?:dimenticati|dimentica|scordati|cancella|elimina|svuota|azzera|resetta|formatta|pulisci)\\s+(?:di\\s+)?(?:proprio\\s+)?" +
        "(?:tutto|tutta la (?:tua )?memoria|la (?:tua )?memoria|tutti i (?:tuoi )?ricordi|i (?:tuoi )?ricordi|tutte le (?:mie )?note|" +
        "le (?:mie )?note|tutto quello che (?:sai|ricordi|ti ho detto)(?: di me| su di me)?|tutto di me|tutto su di me|" +
        "tutte le cose che ti ho detto)$"
    )
    // "dimentica l'ultima cosa", "cancella quello che ti ho appena detto"
    private val FORGET_LAST = ci(
        "^(?:dimenticati|dimentica|scordati|cancella|elimina)\\s+(?:l'ultima cosa(?: che ti ho detto)?|l'ultimo ricordo|l'ultima nota|" +
        "quello che ti ho appena detto|cosa ti ho appena detto|quello che ti ho detto|questa cosa|questo)$"
    )
    // "dimentica il dentista", "scordati di Marco", "non ricordare più il codice del cancello"
    private val FORGET = ci(
        "^(?:dimenticati|dimentica|scordati|scorda|non ricordarti più|non ricordare più|non devi più ricordare)\\s+(?:di\\s+)?" +
        "(?:(?:il ricordo|la nota|la scorciatoia|il comando|la cosa|quello che ti ho detto|quella cosa|la regola|l'istruzione|la lezione)\\s+)?" +
        "(?:(?:di|del|della|dello|dei|delle|degli|che|su|sul|sulla|sullo|sui|sulle|riguardo a|per)\\s+|dell'|sull')?(.+)$"
    )
    // "cancella dalla memoria il dentista", "elimina la nota del dentista", "togli la scorciatoia buonanotte"
    private val FORGET_MEM = ci(
        "^(?:cancella|elimina|togli|rimuovi|leva)\\s+(?:dalla (?:tua )?memoria\\s+|dai (?:tuoi )?ricordi\\s+)?" +
        "(?:(?:il ricordo|i ricordi|la nota|le note|la scorciatoia|la regola|l'istruzione|la lezione)\\s+)?" +
        "(?:(?:di|del|della|dello|dei|delle|degli|che|su|sul|sulla|sullo|sui|sulle|riguardo a|per)\\s+|dell'|sull')?" +
        "(.+?)(?:\\s+(?:dalla (?:tua )?memoria|dai (?:tuoi )?ricordi))?$"
    )
    private val CONVERSATION = ci("^(?:la |questa |tutta la |quella |l'ultima )?(?:conversazione|chiacchierata|discussione|discorso|chat)\\b|^quello che abbiamo detto")
    // "quando dico buonanotte intendo spegni la torcia", "se ti dico amore intendo Giulia"
    // i comandi con cui si spiega una scorciatoia anche senza "intendo": "quando dico buonanotte spegni la torcia"
    private val TEACH_VERBS = "accendi|spegni|apri|chiudi|chiama|telefona|metti|alza|abbassa|scrivi|manda|invia|leggi|cerca|imposta|" +
        "attiva|disattiva|ferma|togli|avvia|riproduci|suona|fammi|dimmi|blocca|torna|vai|portami|naviga|fai|scatta|riprendi|" +
        "svegliami|leggimi|guarda|guardami|rispondi|rispondimi|salutami|chiamami"
    // gruppi: 1 = dico/chiedo, 2 = cosa dici, 3 = come lo spieghi ("intendo", "devi"… vuoto se c'è subito il comando), 4 = cosa fare
    private val TEACH = ci(
        "^(?:d'ora in poi |da ora in poi |da adesso |da oggi |allora )?(?:quando|se|ogni volta che)\\s+(?:io\\s+)?(?:ti\\s+|le\\s+|gli\\s+)?" +
        "(dico|dirò|diro|chiedo|chiederò|chiedero)\\s+(.+?)\\s*,?\\s+(?:(intendo dire|intendo|voglio dire|vuol dire|significa|vorrei che tu|" +
        "vorrei che|voglio che tu|voglio che|tu devi|devi|deve|dovrai|dovrà|dovresti|mi devi|esegui|mi riferisco a|parlo di)\\s+|" +
        "(?=(?:$TEACH_VERBS)\\b))(.+)$"
    )
    private val TEACH2 = ci("^(?:d'ora in poi|da ora in poi|da adesso|da oggi|impara che)\\s+(.+?)\\s+(vuol dire|significa|sta per|equivale a)\\s+(.+)$")

    // ------------------------------------------------------------------ regole da seguire sempre
    // "d'ora in poi rispondi in modo breve", "da adesso non chiamarmi per nome", "devi sempre darmi del lei",
    // "non devi mai dire che sei un modello", "voglio che tu mi risponda sempre in italiano", "sii più breve"
    private val RULE_TIME = "d'ora in poi|da ora in poi|da adesso in poi|da oggi in poi|d'ora in avanti|da adesso in avanti|" +
        "da questo momento in poi|da questo momento|da ora|da adesso|da oggi"
    // dopo "d'ora in poi" ci vuole un'istruzione per SAM ("da oggi sono in ferie" è una chiacchiera, non una regola)
    private val RULE_BODY = "(?:tu |mi |ti |me lo |me la )?(?:rispondi|rispondimi|rispondere|parla|parlami|parlare|usa|usare|di'|dì|dire|dimmi|" +
        "dirmi|dammi|darmi|sii|essere|fai|fammi|fare|cerca di|ricordati|ricorda|evita|evitare|devi|dovrai|dovresti|voglio che|vorrei che|" +
        "ti chiedo di|saluta|salutami|salutare|scrivi|scrivimi|chiedimi|chiedi|aggiungi|tieni|avvisami|chiamarmi|continua|" +
        "non (?:\\p{L}+(?:are|ere|ire|rre|(?:ar|er|ir|rr)(?:mi|ti|lo|la|li|le|ci|ne|gli|melo|mela|telo|tela|celo))|mi|ti|lo|la|ci|ne))\\b"
    // regole che vorrebbero zittire SAM del tutto: valgono per adesso, non per sempre
    private val RULE_SILENCE = "(?!(?:più |mai )?(?:niente|nulla|una parola|altro|parlare|rispondere|rispondermi|parlarmi|dire niente|dire nulla|" +
        "fare niente|fare nulla)\\s*$)"
    private val RULE = ci(
        "^$FILL(?:e |allora |quindi |però |ma )?(?:" +
        "(?:$RULE_TIME)[,\\s]+(?!(?:chiamami|mi chiamo|il mio nome|quando|se|ogni volta)\\b)(?:$RULE_BODY).*|" +
        "(?:$RULE_TIME)[,\\s]+(?:\\S+\\s+){0,3}?(?:in modo|con frasi|con risposte|con parole|usando parole)\\s+(?:più |meno )?" +
        "(?:breve|brevi|corto|corte|corti|semplice|semplici|chiaro|chiare|formale|informale|sintetic[oaie]|dettagliat[oaie])\\b.*|" +
        "(?:tu )?(?:devi|dovrai|dovresti) (?:sempre|mai|smettere di|evitare di)\\s+$RULE_SILENCE\\S.*|" +
        "non (?:devi|dovrai|dovresti) (?:mai|più)\\s+$RULE_SILENCE\\S.*|" +
        "(?:voglio|vorrei) che (?:tu )?(?:mi |ti |me lo |me la |non )?\\p{L}*[iae]\\s+(?:\\S+\\s+)*?(?:sempre|mai|più)\\b.*|" +
        "(?:nuova )?(?:regola|istruzione)[:,\\s]+\\S.*|(?:impara|segui|ricordati) (?:questa|la|una) (?:regola|istruzione)[:,\\s]+\\S.*|" +
        "(?:dammi|darmi) del (?:lei|tu|voi)\\b.*|" +
        "non (?:chiamarmi|chiamarmi più|chiamarmi mai) (?:per nome|col mio nome|con il mio nome)\\b.*|non usare (?:più |mai )?il mio nome\\b.*|" +
        "non (?:dire|dirmi|ripetere|ripetermi|fare|farmi|chiedere|chiedermi|usare|chiamarmi|salutarmi|parlarmi di|parlare di|rispondermi) " +
        "(?:mai )?più\\s+$RULE_SILENCE\\S.*|" +
        "(?:rispondimi|rispondi|parlami|parla|scrivimi) (?:sempre )?(?:in modo |con frasi |con risposte |usando frasi |usando parole )?(?:più |meno )?" +
        "(?:breve|brevi|corto|corte|corti|semplice|semplici|chiaro|chiare|chiari|formale|informale|precis[oae]|sintetic[oaie]|" +
        "lung[oaie]|lunghe|dettagliat[oaie]|in italiano|in dialetto|in napoletano|in inglese)\\b.*|" +
        "sii (?:sempre |più |meno )*(?:breve|sintetico|sintetica|preciso|precisa|gentile|simpatico|simpatica|serio|seria|diretto|diretta|" +
        "formale|chiaro|chiara|educato|educata|allegro|allegra|spiritoso|spiritosa)\\b.*" +
        ")$"
    )
    // un'istruzione per come rispondere, non un comando da eseguire ("quando ti saluto, rispondi ciao capo")
    private val BEHAVIOR = ci("^(?:rispondi|rispondimi|rispondere|di'|dì|dimmi anche|dimmi sempre|aggiungi|non dire|non dirmi|" +
        "non rispondere|usa|parla|parlami|chiamami|salutami|chiedimi|dammi del|sii|fai finta)\\b")
    private val FORGET_RULES = ci(
        "^(?:dimenticati|dimentica|scordati|cancella|elimina|togli|rimuovi|azzera|non seguire più)\\s+(?:tutte\\s+)?(?:le|tutte le) " +
        "(?:(?:tue|mie)\\s+)?(regole|istruzioni|lezioni)(?: che ti ho dato| che hai imparato)?$"
    )

    /** "D'ora in poi rispondi in modo breve": una regola da seguire sempre. null = la frase non è una regola. */
    private fun ruleRequest(raw: String): Outcome? {
        if (!RULE.containsMatchIn(raw)) return null
        // "da adesso guarda la fotocamera", "d'ora in poi accendi la torcia": un comando detto con calma, non una regola
        val body = ci("^$FILL(?:e |allora |quindi |però |ma )?(?:$RULE_TIME)[,\\s]+").replace(raw, "").trim()
        if (body != raw.trim() && Remember.isCommand(body) && !BEHAVIOR.containsMatchIn(body)) return null
        return addRule(raw)
    }

    private fun addRule(phrase: String): Outcome {
        var c = ci("^$FILL").replace(phrase.trim(), "").trim().trim(',', '.', ':', ';', ' ')
        c = ci("^(?:(?:nuova )?(?:regola|istruzione)|(?:impara|segui|ricordati) (?:questa|la|una) (?:regola|istruzione))[:,\\s]+").replace(c, "").trim()
        if (Remember.words(c).isEmpty()) {
            pending = { ans -> addRule(ans) }
            return Outcome.Speak("Quale regola devo seguire?")
        }
        val text = c.replaceFirstChar { it.uppercase() }
        val rules = mem.memos().filter { it.kind == Remember.REGOLA }
        if (rules.any { Remember.norm(it.text) == Remember.norm(text) }) return Outcome.Speak("Lo so già: è una delle mie regole, la seguo sempre.")
        mem.addMemo(Memo(Remember.REGOLA, text, "", now()))
        memoryUrgent = true; memoryDropped = true
        val said = ci("^(?:$RULE_TIME)[,\\s]+").replace(text, "").trim().trimEnd('.')
        return Outcome.Speak("Va bene, da ora seguo questa regola: «$said»." +
            if (rules.isEmpty()) " Se cambi idea, di': dimentica la regola, e quale." else "")
    }

    private fun forgetLessons(): Outcome {
        val lessons = mem.memos().filter { it.kind == Remember.LEZIONE }
        if (lessons.isEmpty()) return Outcome.Speak("Non ho ancora imparato nessuna lezione dalle tue correzioni.")
        pending = { ans ->
            if (Lang.isYes(ans)) {
                lessons.forEach { mem.removeMemo(it) }; memoryUrgent = true
                Outcome.Speak("Fatto: ho dimenticato tutte le lezioni.", endSession = true)
            } else Outcome.Speak("Va bene, le tengo.", endSession = true)
        }
        return Outcome.Speak(if (lessons.size == 1) "Dimentico l'unica lezione che ho imparato? Di' sì per confermare."
                             else "Dimentico tutte le ${lessons.size} lezioni che ho imparato? Di' sì per confermare.")
    }

    private fun forgetRules(): Outcome {
        val rules = mem.memos().filter { it.kind == Remember.REGOLA }
        if (rules.isEmpty()) return Outcome.Speak("Non mi hai dato nessuna regola.")
        pending = { ans ->
            if (Lang.isYes(ans)) {
                rules.forEach { mem.removeMemo(it) }; memoryUrgent = true; memoryDropped = true
                Outcome.Speak("Fatto: ho dimenticato tutte le regole.", endSession = true)
            } else Outcome.Speak("Va bene, le tengo.", endSession = true)
        }
        return Outcome.Speak(if (rules.size == 1) "Dimentico l'unica regola che mi hai dato? Di' sì per confermare."
                             else "Dimentico tutte le ${rules.size} regole che mi hai dato? Di' sì per confermare.")
    }

    // ------------------------------------------------------------------ la chat (1.20)
    private val CHAT_WORD = "(?:la |questa |la nostra |tutta la )?(?:chat|conversazione|cronologia|chiacchierata)|(?:gli |i )?ultimi (?:\\d+ |due |tre |quattro |cinque |dieci )?messaggi(?: della chat)?|(?:i )?messaggi della chat|quello che ci siamo (?:detti|scritti)|cosa ci siamo (?:detti|scritti)"
    private val READ_CHAT = ci("^$FILL(?:leggi|leggimi|rileggi|rileggimi|ripetimi|dimmi|fammi sentire|fammi vedere|mostrami)\\s+(?:cosa c'è (?:scritto )?(?:nel|nella) )?($CHAT_WORD)(?: di (?:prima|oggi|ieri|stamattina))?$|" +
        "^$FILL(?:cosa c'è scritto|cosa c'è) (?:nella|in) chat\\??$")
    private val SUM_CHAT = ci("^$FILL(?:riassumi|riassumimi|fammi un riassunto (?:di|della|del)|fai un riassunto (?:di|della|del)|sintetizza)\\s+(?:di )?($CHAT_WORD)(?: di (?:prima|oggi|ieri|stamattina))?$")
    private val NUM = mapOf("due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "dieci" to 10)

    private fun readChat(t: String): Outcome? {
        SUM_CHAT.find(t)?.let { return Outcome.ReadChat(summary = true, count = 30) }
        READ_CHAT.find(t)?.let { m ->
            val n = Regex("(\\d+|due|tre|quattro|cinque|dieci) (?:ultimi )?messaggi").find(m.value)?.groupValues?.get(1)?.let { NUM[it] ?: it.toIntOrNull() } ?: 6
            return Outcome.ReadChat(summary = false, count = n.coerceIn(1, 30))
        }
        return null
    }

    // ------------------------------------------------------------------ quello che ci siamo detti
    // "ti ricordi cosa abbiamo detto prima?", "di cosa stavamo parlando?", "cosa mi hai detto prima?", "dove eravamo rimasti?":
    // la risposta sta nella conversazione, non nei ricordi per sempre (prima rispondeva "su questo non ho ricordi")
    private val CONV_REF = ci(
        "\\b(?:abbiamo|avevamo|stavamo|eravamo)\\s+(?:(?:appena|già|prima|oggi|stamattina|ieri|insieme|poco fa)\\s+)?" +
        "(?:detto|parlato|discusso|fatto|chiacchierato|parlando|discutendo|dicendo|rimasti|deciso|visto)\\b|" +
        "\\bti (?:ho|avevo)\\s+(?:(?:appena|già|prima|poco fa|oggi|stamattina|ieri)\\s+)?(?:detto|chiesto|domandato|raccontato|parlato|spiegato)\\b|" +
        "\\bmi (?:hai|avevi)\\s+(?:(?:appena|già|prima|poco fa|oggi|stamattina|ieri)\\s+)?" +
        "(?:detto|risposto|raccontato|spiegato|consigliato|chiesto|parlato|suggerito|proposto|letto|descritto)\\b|" +
        "\\b(?:la|questa|quella|l'ultima|la nostra|nostra) (?:conversazione|chiacchierata|discussione)\\b|" +
        "\\b(?:il|quel|questo|nostro|del|al|nel|sul) discorso\\b|\\bdi cosa (?:stavamo|parlavamo|si parlava)\\b|\\bil filo del discorso\\b"
    )
    private val CONV_Q = ci(
        "^$FILL(?:ma |e |allora |quindi )?(?:(?:ti |te |i )?ricordi|te lo ricordi|ricordami|cosa|che cosa|che|cos'|di cosa|di che cosa|di che|" +
        "quale|qual|quali|come|dove|quando|chi|perch[eé]|quanto|quanti|sai|mi sai dire|sapresti|mi dici|dimmi|mi ripeti|ripetimi|" +
        "riassumi|riassumimi|fammi un riassunto|torniamo|continua|continuiamo|riprendiamo|riprendi|prima|dove eravamo|a che punto)\\b"
    )
    // riferimenti così chiari che valgono anche in mezzo a una frase sentita male ("coro di cosa abbiamo detto prima")
    private val CONV_STRONG = ci(
        "\\bdi (?:cosa|che cosa|che) (?:abbiamo|avevamo|stavamo|stiamo|si parlava)\\b|\\b(?:cosa|che cosa) (?:abbiamo|avevamo) " +
        "(?:detto|fatto|parlato|deciso|discusso)\\b|\\bdove eravamo rimasti\\b|\\bdi cosa (?:stavamo|parlavamo)\\b|" +
        "\\b(?:la|questa|quella|l'ultima|la nostra) (?:conversazione|chiacchierata) di (?:prima|poco fa|oggi|ieri|stamattina)\\b"
    )
    private val CONV_KEEP = ci("\\b(?:di ricordare|da ricordare|di memorizzare|da memorizzare|di ricordarti|di segnare|di salvare|di ricordarlo)\\b")

    /**
     * Una domanda su quello che ci siamo detti: il cervello riceve tutte le battute di prima (anche se il telefono
     * ha chiuso SAM nel frattempo) e, se non c'è o non sa rispondere, SAM le ripete da sé. null = nessuna battuta.
     */
    private fun recallTalk(raw: String): Outcome? {
        val lines = talk?.before(null, within = Talk.RECALL_MS).orEmpty()
        if (lines.isEmpty()) return null
        return Outcome.NeedLlm(raw, recall = true, fallback = Talk.retell(lines))
    }

    /** "cosa ti ho detto di Marco?": se c'è un ricordo su Marco risponde la memoria, non la conversazione. */
    private fun memoryHit(raw: String): Boolean {
        val q = ASK_ABOUT.find(raw)?.groupValues?.get(2)
            ?: ci("\\b(?:di|del|della|dello|dei|delle|degli|dell'|su|sul|sulla|riguardo a|a proposito di)\\s+(.+)$").find(raw)?.groupValues?.get(1)
            ?: return false
        if (ci("^(?:prima|poco fa|oggi|ieri|stamattina|stasera|stanotte|questo|quello|cosa)$").containsMatchIn(q.trim())) return false
        return Remember.find(q, mem.memos().filter { it.kind == Remember.NOTA || it.kind == Remember.FATTO }).isNotEmpty()
    }

    /** true se di [q] si è parlato nella conversazione (per "ti ricordi del film?" detto di una cosa di poco fa). */
    private fun talkMentions(q: String): Boolean {
        val words = Remember.words(q)
        if (words.isEmpty()) return false
        val said = talk?.before(null, within = Talk.RECALL_MS).orEmpty()
        if (said.isEmpty()) return false
        val heard = Remember.words(said.joinToString(" ") { it.you + " " + it.sam }).toSet()
        return words.any { it in heard }
    }

    /** I comandi della memoria. null = la frase non riguarda la memoria. */
    private fun memoryCommand(raw: String): Outcome? {
        TEACH.find(raw)?.let { return teach(it.groupValues[2], it.groupValues[4], verb = it.groupValues[1], connector = it.groupValues[3]) }
        TEACH2.find(raw)?.let { return teach(it.groupValues[1], it.groupValues[3], connector = it.groupValues[2]) }
        FORGET_RULES.find(raw)?.let { m -> return if (m.groupValues[1] == "lezioni") forgetLessons() else forgetRules() }

        if (FORGET_ALL.containsMatchIn(raw)) return forgetAll()
        if (FORGET_LAST.containsMatchIn(raw)) return forgetLast()
        FORGET.find(raw)?.let { m ->
            val target = m.groupValues[1].trim()
            if (CONVERSATION.containsMatchIn(target)) return Outcome.NewConversation
            return forget(target)
        }
        if (ci("\\b(memoria|ricordo|ricordi|nota|note|scorciatoia|regola|istruzione|lezione)\\b").containsMatchIn(raw))
            FORGET_MEM.find(raw)?.let { return forget(it.groupValues[1].trim()) }

        // come funziona la memoria: "hai una memoria?", "perché dici che non puoi memorizzare?"
        ABILITY.find(raw)?.let { m ->
            // "te lo ricorderai?" subito dopo avermi raccontato una cosa: sì, e la salvo
            if (ABOUT_IT.containsMatchIn(m.value) && recentTold() != null) return savePrevious(asked = true)
            return memoryAbility(apology = ci("\\bnon\\b").containsMatchIn(m.value))
        }
        // una regola da seguire sempre: "d'ora in poi rispondi in modo breve"
        ruleRequest(raw)?.let { return it }
        // quello che ci siamo detti: "ti ricordi cosa abbiamo detto prima?", "di cosa stavamo parlando?"
        if (((CONV_Q.containsMatchIn(raw) && CONV_REF.containsMatchIn(raw)) || CONV_STRONG.containsMatchIn(raw)) &&
            !CONV_KEEP.containsMatchIn(raw) && !memoryHit(raw)) {
            recallTalk(raw)?.let { return it }
            // nessuna battuta nelle ultime ore: lo dico, invece di "su questo non ho ricordi"
            if (!TOLD_BEFORE.containsMatchIn(raw) && !LIST.containsMatchIn(raw)) {
                val n = mem.memos().count { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
                return Outcome.Speak("Nelle ultime ore non abbiamo parlato d'altro: questa conversazione è appena cominciata." +
                    if (n > 0) " Però ricordo per sempre le cose che mi hai chiesto di ricordare: per sentirle, chiedimi cosa ricordi." else "")
            }
        }
        if (TOLD_BEFORE.containsMatchIn(raw)) return listMemory()
        if (TO_TELL.containsMatchIn(raw)) return askWhat()
        // "il mio cane si chiama Rex, ricordatelo": la cosa da ricordare è all'inizio della frase
        TRAIL.find(raw)?.let { m ->
            val s = m.groupValues[1].trim()
            if (isStatement(s)) return addNote(s)
            // "dove ho parcheggiato, te lo ricordi?": una domanda sui ricordi
            if (QUESTION_START.containsMatchIn(s) && Remember.words(s).isNotEmpty()) {
                val found = Remember.find(s, mem.memos())
                if (found.isEmpty() && talkMentions(s)) recallTalk(raw)?.let { return it }
                return Outcome.Speak(if (found.isNotEmpty()) recall(Remember.ties(s, found)) else "No, su questo non ho ricordi.")
            }
        }

        if (LIST.containsMatchIn(raw)) {
            // "quali regole ti ho dato?", "quali lezioni hai imparato?": solo quelle
            if (ci("\\b(?:regole|istruzioni)\\b").containsMatchIn(raw)) {
                val rules = mem.memos().filter { it.kind == Remember.REGOLA }
                if (rules.isEmpty())
                    return Outcome.Speak("Non mi hai ancora dato nessuna regola. Per darmene una di', per esempio: d'ora in poi rispondimi in modo breve.")
                return Outcome.Speak((if (rules.size == 1) "La regola che seguo: " else "Le regole che seguo: ") +
                    rules.joinToString(". ") { it.text.trimEnd('.') } + ".")
            }
            if (ci("\\blezioni\\b").containsMatchIn(raw)) {
                val lessons = mem.memos().filter { it.kind == Remember.LEZIONE }
                if (lessons.isEmpty()) return Outcome.Speak("Non ho ancora imparato nessuna lezione. Se ti rispondo male, dimmi: sbagliato, " +
                    "la risposta giusta è, e poi la risposta.")
                return Outcome.Speak((if (lessons.size == 1) "Ho imparato una lezione: " else "Ho imparato ${lessons.size} lezioni. Le ultime: ") +
                    lessons.takeLast(3).joinToString("; ") { "alla domanda «${it.key}», ${it.text.trimEnd('.')}" } + ".")
            }
            return listMemory()
        }

        ASK_ABOUT.find(raw)?.let { m ->
            val q = m.groupValues[2].trim()
            if (ci("^(?:di )?me(?: stesso| stessa)?$|^su di me$").containsMatchIn(q)) return listMemory()
            val found = Remember.find(q, mem.memos())
            if (found.isNotEmpty()) return Outcome.Speak(recall(Remember.ties(q, found)))
            if (m.groupValues[1] != "sai" && talkMentions(q)) recallTalk(raw)?.let { return it }
            // "cosa sai di Napoleone?": non è un ricordo, è una domanda per l'IA
            return if (m.groupValues[1] == "sai") null else Outcome.Speak("No, su questo non ho ricordi.")
        }
        REMIND_Q.find(raw)?.let { m ->
            val found = Remember.find(m.groupValues[1], mem.memos())
            // "ricordami come si fa la carbonara": niente ricordi, ci pensa l'IA
            return if (found.isNotEmpty()) Outcome.Speak(recall(Remember.ties(m.groupValues[1], found))) else null
        }
        ASK_MEM.find(raw)?.let { m ->
            val q = m.groupValues[1].trim()
            if (ci("^(?:di )?me$").containsMatchIn(q)) return aboutMe()
            if (Remember.words(q).isEmpty()) return listMemory()
            val found = Remember.find(q, mem.memos())
            if (found.isEmpty() && talkMentions(q)) recallTalk(raw)?.let { return it }
            return Outcome.Speak(if (found.isNotEmpty()) "Sì. " + recall(Remember.ties(q, found)) else "No, su questo non ho ricordi.")
        }

        if (PRON_ALONE.containsMatchIn(raw)) return savePrevious(asked = false)
        return memoryRequest(raw)
    }

    private val IS_V_PRON = ci("^(?:$V_PRON)$")
    private val IS_V_CHE = ci("^(?:$V_CHE)$")
    private val IS_V_INF = ci("^(?:$V_INF)$")
    private val IS_V_FIN = ci("^(?:$V_FIN)$")
    private val IS_ABLE = ci("^(?:mi |ti )?(?:$ABLE)$")
    // infinito con il pronome ("ricordarlo", "memorizzarlo"): arriva da "puoi ricordarlo?", con "puoi" già tolto
    private val IS_INF_PRON = ci("^(?:ricordarl|ricordartel|memorizzarl|memorizzartel|segnarl|segnartel|annotarl|annotartel|appuntartel|salvarl|salvartel|tenerl|tenertel)")
    private val HAS_PRON = ci("\\b(?:lo|la|li|le)\\b")
    private val TIMER_LIKE = ci("^(?:tra|fra)\\s+(?:\\S+\\s+)?(?:un'ora|mezz'ora|mezzora|minut|or[ae]\\b|second)")
    // "te lo ricorderai?", "ti ricorderai di questo?": parla della cosa appena detta
    private val ABOUT_IT = ci(
        "\\b(?:te|me|ti) (?:lo|la|li|le|ne)\\b|^$FILL(?:ma |e |però |quindi )?(?:lo|la|li|le|ne) \\S+|" +
        "\\b(?:di questo|di quello|di questa cosa|questo|quello|questa cosa|quello che ti ho detto)$"
    )
    private val QUESTION_START = ci("^(?:dove|quando|come|chi|cosa|che cosa|qual|quale|quanto|quanti|a che ora)\\b")

    /** "ricordati che…", "puoi ricordare…", "memorizzalo"… null = la frase non chiede di ricordare. */
    private fun memoryRequest(raw: String): Outcome? {
        val m = REQ.find(raw) ?: return null
        val modal = m.groupValues[1].trim()
        val verb = m.groupValues[3].lowercase()
        if (IS_V_CHE.matches(verb) && m.groupValues[4] != "che") return null
        if (IS_V_FIN.matches(verb) && modal.isEmpty()) return null
        var content = m.groupValues[6].trim().trim(',', '.', ':', ';', ' ')
        if (EMPTYISH.matches(content)) content = ""
        // "ricordami tra dieci minuti di spegnere il forno": è un timer
        if (TIMER_LIKE.containsMatchIn(content)) return null
        val whole = (m.groupValues[5] + " " + content).trim()
        val pron = IS_V_PRON.matches(verb) || HAS_PRON.containsMatchIn(m.groupValues[2])
        // "puoi ricordare?", "riesci a ricordarti le cose?": chiede se ne sono capace
        val able = IS_ABLE.matches(modal) || (modal.isEmpty() && (IS_V_INF.matches(verb) || IS_INF_PRON.containsMatchIn(verb)))
        return when {
            whole.isEmpty() && pron -> savePrevious(asked = able)
            whole.isEmpty() && able -> memoryAbility(apology = false)
            whole.isEmpty() -> addNote("")
            PREV.matches(whole) -> savePrevious(asked = able)
            GENERIC.matches(whole) -> memoryAbility(apology = false)
            SOMETHING.matches(whole) -> askWhat()
            else -> addNote(content.ifEmpty { whole })
        }
    }

    /** "ricordatelo", "te lo ricorderai?": la cosa da ricordare è quella che mi hai appena raccontato. */
    private fun savePrevious(asked: Boolean): Outcome {
        val prev = recentTold() ?: return addNote("")
        return addNote(prev, yes = asked)
    }

    private fun askWhat(): Outcome {
        pending = { ans -> addNote(ans) }
        awaitingText = true
        return Outcome.Speak("Certo, dimmi pure: cosa devo ricordare?")
    }

    /** Come funziona la memoria: la risposta giusta a "hai una memoria?" (il cervello IA direbbe di no). */
    private fun memoryAbility(apology: Boolean): Outcome {
        val n = mem.memos().count { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        val head = if (apology) "Scusa, mi sono spiegato male: ho una memoria che dura per sempre, anche se spegni il telefono."
                   else "Sì, ho una memoria che dura per sempre, anche se spegni il telefono."
        val how = " Per farmi ricordare una cosa di': ricordati che, e poi la cosa. Oppure dimmela e poi di': ricordatelo."
        val tail = when (n) {
            0 -> " Per sentire cosa ricordo, chiedimi: cosa ricordi?"
            1 -> " Adesso ricordo una cosa: per sentirla chiedimi cosa ricordi."
            else -> " Adesso ricordo $n cose: per sentirle chiedimi cosa ricordi."
        }
        return Outcome.Speak(head + how + tail)
    }

    /** Dopo una risposta del cervello IA che negava di avere una memoria: cosa dire al suo posto. */
    fun memoryHint(said: String): String =
        if (isStatement(said)) "Se vuoi che me lo ricordi per sempre, di': ricordatelo."
        else "Comunque ho una memoria che dura per sempre: per farmi ricordare una cosa di': ricordati che, e poi la cosa."

    /**
     * Il cervello IA ha risposto "me lo ricorderò" a una cosa che mi hai raccontato: la salvo davvero,
     * così la promessa è vera. Ritorna il testo salvato, null se non c'era niente da salvare.
     */
    fun keepPromise(said: String): String? {
        val t = said.trim().lowercase().trim(' ', '.', ',', '!')
        if (!isStatement(t)) return null
        val c = Remember.fixHo(t)
        if (mem.memos().any { it.kind == Remember.NOTA && Remember.norm(it.text) == Remember.norm(c) }) return null
        addNote(c)
        return c.replaceFirstChar { it.uppercase() }
    }

    /** Salva una cosa da ricordare per sempre. Un fatto che cambia ("il mio colore preferito è…") sostituisce il vecchio. */
    private fun addNote(content0: String, yes: Boolean = false): Outcome {
        var c = Remember.fixHo(content0.trim().trim(',', '.', ':', ';', ' '))
        c = ci("\\s+(?:per sempre|per favore|grazie)$").replace(c, "").trim()
        if (Remember.words(c).isEmpty()) {
            pending = { ans -> addNote(ans) }
            awaitingText = true
            return Outcome.Speak("Cosa devo ricordare?")
        }
        lastTold = null
        // "ricordati che mi chiamo Luca": è il nome del proprietario
        ci("^(?:io )?mi chiamo\\s+(.+)").find(c)?.let { m ->
            Lang.extractName(m.groupValues[1])?.let { n ->
                mem.setOwnerName(n); memoryUrgent = true
                return Outcome.Speak("Ok, da ora ti chiamo $n.")
            }
        }
        val all = mem.memos()
        val text = c.replaceFirstChar { it.uppercase() }
        val same = all.firstOrNull { (it.kind == Remember.NOTA || it.kind == Remember.FATTO) && Remember.norm(it.text) == Remember.norm(c) }
        val saved = if (yes) "Sì, me lo ricordo per sempre." else "Ok, me lo ricordo."
        if (same != null) {
            if (same.kind == Remember.FATTO) {
                // l'avevo imparato da solo: ora diventa una cosa da ricordare per sempre
                mem.removeMemo(same); mem.addMemo(Memo(Remember.NOTA, same.text, same.key, now())); memoryUrgent = true
                // imparato un attimo fa, dalla frase che mi chiedi di ricordare: è semplicemente salvato
                if (now() - same.at <= TOLD_FOR_MS) return Outcome.Speak(saved)
            }
            return Outcome.Speak("Lo so già, me l'avevi detto. Lo tengo a mente.")
        }
        val key = Remember.selfFact(c)?.key ?: ""
        val replaced = if (key.isEmpty()) emptyList() else all.filter { (it.kind == Remember.NOTA || it.kind == Remember.FATTO) && it.key == key }
        replaced.forEach { mem.removeMemo(it) }
        mem.addMemo(Memo(Remember.NOTA, text, key, now()))
        memoryUrgent = true
        if (replaced.isNotEmpty()) memoryDropped = true
        // un fatto imparato da solo un attimo fa è la stessa frase detta in altre parole: niente "aggiornato"
        val old = replaced.lastOrNull { !(it.kind == Remember.FATTO && now() - it.at <= TOLD_FOR_MS) }
        var reply = if (old != null) "Ok, aggiornato. Prima sapevo: ${old.text}." else saved
        if (ci("\\b(?:alle|tra|fra)\\s+\\d").containsMatchIn(Num.wordsToDigits(c)))
            reply += " Se vuoi che ti avvisi all'ora giusta, chiedimi anche una sveglia."
        return Outcome.Speak(reply)
    }

    private fun listMemory(): Outcome {
        val all = mem.memos()
        if (all.isEmpty())
            return Outcome.Speak("Non ho ancora nessun ricordo. Per farmi ricordare una cosa per sempre, di': ricordati che, e poi la cosa.")
        val notes = all.filter { it.kind == Remember.NOTA }
        val facts = all.filter { it.kind == Remember.FATTO }
        val alias = all.filter { it.kind == Remember.ALIAS }
        val picks = all.filter { it.kind == Remember.SCELTA }
        val rules = all.filter { it.kind == Remember.REGOLA }
        val lessons = all.filter { it.kind == Remember.LEZIONE }
        val out = ArrayList<String>()
        if (rules.isNotEmpty())
            out.add((if (rules.size == 1) "La regola che seguo: " else "Le regole che seguo: ") +
                rules.joinToString(". ") { it.text.trimEnd('.') } + ".")
        if (notes.isNotEmpty()) {
            out.add("Mi hai chiesto di ricordare: " + notes.takeLast(8).joinToString(". ") { it.text.trimEnd('.') } + ".")
            if (notes.size > 8) out.add("E altre ${notes.size - 8} cose più vecchie.")
        }
        if (facts.isNotEmpty())
            out.add("Ho imparato che: " + facts.takeLast(6).joinToString(". ") { it.text.trimEnd('.') } + "." +
                (if (facts.size > 6) " E altre ${facts.size - 6} cose." else ""))
        if (alias.isNotEmpty())
            out.add("Conosco " + (if (alias.size == 1) "una scorciatoia" else "${alias.size} scorciatoie") + ": " +
                alias.joinToString(", ") { "«${it.key}»" } + ".")
        if (picks.isNotEmpty())
            out.add("E so quale contatto scegli quando dici " + picks.joinToString(", ") { "«${it.key.removePrefix("contatto:")}»" } + ".")
        if (lessons.isNotEmpty()) {
            val l = lessons.last()
            out.add((if (lessons.size == 1) "Ho imparato una lezione" else "Ho imparato ${lessons.size} lezioni") +
                " dalle tue correzioni. L'ultima: alla domanda «${l.key}», la risposta giusta è: ${l.text.trimEnd('.')}.")
        }
        return Outcome.Speak(out.joinToString(" "))
    }

    private fun aboutMe(): Outcome {
        val n = mem.ownerName()
        val k = mem.memos().count { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        val head = if (n != null) "Certo, sei $n!" else "Certo! Però non mi hai ancora detto come ti chiami."
        return Outcome.Speak(head + (if (k > 0) " E ricordo $k cose che mi hai detto." else ""))
    }

    /** Come si dice un ricordo ad alta voce. */
    private fun describe(m: Memo): String = when (m.kind) {
        Remember.ALIAS -> if (Remember.isCommand(m.text)) "la scorciatoia «${m.key}», che fa: ${m.text}" else "la scorciatoia «${m.key}», che vuol dire ${m.text}"
        Remember.SCELTA -> "che quando dici «${m.key.removePrefix("contatto:")}» intendi ${m.text}"
        Remember.REGOLA -> "la regola «${m.text.trimEnd('.')}»"
        Remember.LEZIONE -> "la lezione sulla domanda «${m.key}»"
        else -> "«${m.text.trimEnd('.')}»"
    }

    private fun recall(found: List<Memo>): String {
        val parts = found.take(3).map { m ->
            when (m.kind) {
                Remember.ALIAS -> if (Remember.isCommand(m.text)) "quando dici «${m.key}» faccio: ${m.text}" else "quando dici «${m.key}» intendi «${m.text}»"
                Remember.SCELTA -> "quando dici «${m.key.removePrefix("contatto:")}» intendi ${m.text}"
                else -> "«${m.text.trimEnd('.')}»"
            }
        }
        return "Mi hai detto: " + parts.joinToString("; ") + "."
    }

    /** Domande normali a cui risponde con sicurezza un ricordo: "dove ho parcheggiato?" null = all'IA. */
    private val IMPLICIT_Q = ci(
        "^(?:dove|quando|a che ora|che giorno|qual è|qual'è|quale è|com'è|come si chiama|come si chiamano|chi è|" +
        "quanti anni ha|quanti anni hanno|cosa devo|che cosa devo|devo|cos'ho|che cosa ho|cosa ho)\\b"
    )
    private fun memoryAnswer(raw: String): Outcome? {
        if (!IMPLICIT_Q.containsMatchIn(raw)) return null
        val all = mem.memos().filter { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        if (all.isEmpty()) return null
        val found = Remember.find(raw, all).filter { Remember.sure(raw, it) }
        if (found.isEmpty()) return null
        mind?.touch(found.take(2), now())
        mind?.source("memoria", found.first().text + "\u0000" + found.first().at, raw, now())
        return Outcome.Speak(recall(found.take(2)))
    }

    private val ORD = listOf("uno", "due", "tre")

    private fun forget(target0: String): Outcome {
        val target = target0.trim().trim(',', '.', ' ')
        if (target.isEmpty() || Remember.words(target).isEmpty()) {
            pending = { ans -> forget(ans) }
            return Outcome.Speak("Cosa devo dimenticare?")
        }
        if (ci("^(?:il mio nome|come mi chiamo)$").containsMatchIn(target))
            return Outcome.Speak("Il tuo nome lo tengo. Se vuoi cambiarlo di': chiamami, e il nome nuovo.")
        val all = mem.memos()
        if (all.isEmpty()) return Outcome.Speak("Non ho nessun ricordo da cancellare.")
        val found = Remember.find(target, all)
        if (found.isEmpty()) return Outcome.Speak("Non trovo niente su $target nei miei ricordi.")
        val pari = Remember.ties(target, found)
        if (pari.size == 1) return confirmForget(pari[0])
        if (pari.size > 3) return Outcome.Speak("Ho trovato ${pari.size} ricordi su $target. Dimmi meglio quale, con qualche parola in più.")
        pending = { ans -> chooseForget(ans, pari) }
        return Outcome.Speak("Ho trovato ${pari.size} ricordi: " +
            pari.mapIndexed { i, m -> "${ORD[i]}, ${describe(m)}" }.joinToString("; ") + ". Quale dimentico?")
    }

    private fun confirmForget(m: Memo): Outcome {
        pending = { ans ->
            if (Lang.isYes(ans)) {
                mem.removeMemo(m); memoryUrgent = true; memoryDropped = true
                Outcome.Speak("Fatto, l'ho dimenticato.", endSession = true)
            } else Outcome.Speak("Va bene, lo tengo.", endSession = true)
        }
        return Outcome.Speak("Dimentico ${describe(m)}. Confermi?")
    }

    private fun chooseForget(ans: String, opts: List<Memo>): Outcome {
        if (ci("\\b(tutti|tutte|entrambi|entrambe)\\b").containsMatchIn(ans)) {
            opts.forEach { mem.removeMemo(it) }; memoryUrgent = true; memoryDropped = true
            return Outcome.Speak(if (opts.size == 2) "Fatto, li ho dimenticati tutti e due." else "Fatto, li ho dimenticati tutti.", endSession = true)
        }
        val ordinali = listOf("^(?:il |la )?(?:prim[oa]|uno|1)\\b", "^(?:il |la )?(?:second[oa]|due|2)\\b", "^(?:il |la )?(?:terz[oa]|tre|3)\\b")
        var m: Memo? = null
        for ((i, o) in ordinali.withIndex()) if (i < opts.size && ci(o).containsMatchIn(ans)) { m = opts[i]; break }
        if (m == null) m = Remember.find(ans, opts).firstOrNull()
        if (m == null) return Outcome.Speak("Non ho capito quale: non ho cancellato niente.", endSession = true)
        mem.removeMemo(m); memoryUrgent = true; memoryDropped = true
        return Outcome.Speak("Fatto, ho dimenticato ${describe(m)}.", endSession = true)
    }

    private fun forgetAll(): Outcome {
        val n = mem.memos().size
        if (n == 0) return Outcome.Speak("La mia memoria è già vuota.")
        pending = { ans ->
            if (Lang.isYes(ans)) {
                mem.clearMemos(); memoryUrgent = true; memoryDropped = true
                Outcome.Speak("Fatto: ho dimenticato tutto. Il tuo nome però lo ricordo.", endSession = true)
            } else Outcome.Speak("Va bene, non cancello niente.", endSession = true)
        }
        return Outcome.Speak(if (n == 1) "Vuoi che dimentichi per sempre l'unico ricordo che ho? Di' sì per confermare."
            else "Vuoi davvero che dimentichi per sempre tutti i $n ricordi? Di' sì per confermare.")
    }

    private fun forgetLast(): Outcome {
        val m = mem.memos().lastOrNull { it.kind != Remember.SCELTA } ?: return Outcome.Speak("Non ho nessun ricordo da cancellare.")
        return confirmForget(m)
    }

    /** Impara da solo un fatto che racconti di te. Una cosa che mi hai chiesto di ricordare vale più di un fatto imparato. */
    private fun learn(raw: String) {
        val f = Remember.selfFact(raw, now()) ?: return
        val all = mem.memos()
        if (all.any { it.kind == Remember.NOTA && (it.key == f.key || Remember.norm(it.text) == Remember.norm(f.text)) }) return
        val same = all.filter { it.kind == Remember.FATTO && it.key == f.key }
        if (same.any { Remember.norm(it.text) == Remember.norm(f.text) }) return
        same.forEach { mem.removeMemo(it) }
        mem.addMemo(f)
        learnedNow = f.text
    }

    // ------------------------------------------------------------------ scorciatoie insegnate
    // l'infinito detto spiegando una scorciatoia ("devi spegnere la torcia") diventa il comando ("spegni la torcia")
    private val INF = mapOf(
        "aprire" to "apri", "accendere" to "accendi", "spegnere" to "spegni", "chiamare" to "chiama", "telefonare" to "telefona",
        "mettere" to "metti", "alzare" to "alza", "abbassare" to "abbassa", "scrivere" to "scrivi", "mandare" to "manda",
        "inviare" to "invia", "leggere" to "leggi", "cercare" to "cerca", "impostare" to "imposta", "attivare" to "attiva",
        "disattivare" to "disattiva", "fermare" to "ferma", "togliere" to "togli", "avviare" to "avvia", "riprodurre" to "riproduci",
        "bloccare" to "blocca", "tornare" to "torna", "portarmi" to "portami", "dirmi" to "dimmi", "farmi" to "fammi",
        "fare" to "fai", "chiudere" to "chiudi", "scattare" to "scatta", "suonare" to "suona", "riprendere" to "riprendi",
        "leggermi" to "leggimi", "svegliarmi" to "svegliami", "navigare" to "naviga"
    )
    private fun imperative(s: String): String {
        val w = s.trim().split(' ')
        if (w.isEmpty()) return s
        return (listOf(INF[w[0].lowercase()] ?: w[0]) + w.drop(1)).joinToString(" ")
    }

    private val SEQ_VERBS = "accendi|spegni|apri|chiudi|chiama|telefona|metti|alza|abbassa|scrivi|manda|invia|leggi|cerca|imposta|" +
        "attiva|disattiva|ferma|togli|avvia|riproduci|suona|fammi|dimmi|blocca|torna|vai|portami|naviga|fai|scatta|riprendi|" +
        "svegliami|leggimi|" + INF.keys.joinToString("|")
    // dove una scorciatoia passa al comando successivo: "e poi", "poi", oppure "e" seguito da un verbo o da una domanda
    private val SEQ_NEXT = "$SEQ_VERBS|che ore|che tempo|che giorno|che data|quanta batteria|com'è il tempo|il meteo|meteo|le notifiche"
    private val SEQ = ci("\\s*,?\\s+(?:e poi|poi|e dopo|quindi|infine)\\s+|\\s*,\\s+(?=(?:$SEQ_NEXT)\\b)|\\s+e\\s+(?=(?:$SEQ_NEXT)\\b)")
    private val BAD_KEYS = setOf("si", "no", "ok", "okay", "basta", "stop", "grazie", "fine", "annulla", "niente", "sam", "sem", "san", "aiuto", "nulla")

    /**
     * "Quando dico [x0], [connector] [y0]": una scorciatoia ("buonanotte" -> "spegni la torcia e metti la sveglia"), un nome
     * ("amore" -> "Giulia"), la vista di SAM ("guarda" -> "apri la fotocamera, guarda e chiudila") oppure una regola su come
     * rispondere ("quando ti saluto, rispondi ciao capo"). [verb] = "dico" o "chiedo".
     */
    private fun teach(x0: String, y0: String, verb: String = "dico", connector: String = ""): Outcome {
        val x = x0.trim().trim('"', '«', '»', ',', '.', ' ')
        var y = y0.trim().trim(',', '.', ' ')
        y = ci("^(?:(?:che|di|dire|a dire|che devi|che tu|che vuoi|che voglio)\\s+)+").replace(y, "").trim()
        y = ci("\\s+(?:per favore|grazie)$").replace(y, "").trim()
        val key = Remember.aliasKey(x)
        if (key.isEmpty() || y.isEmpty())
            return Outcome.Speak("Non ho capito. Prova così: quando dico buonanotte, intendo spegni la torcia.")
        if (key in BAD_KEYS || Lang.hasWake(x))
            return Outcome.Speak("La parola $x mi serve già per altro: scegline un'altra.")
        val value = fixAsr(imperative(y))
        // guardare con la fotocamera: è la vista di SAM (la sua fotocamera, che si richiude da sola)
        teachLook(x, key, value)?.let { return it }
        // non un comando né un nome, ma come rispondere: "quando ti chiedo il meteo, dimmi anche la temperatura"
        val naming = ci("^(?:intendo dire|intendo|voglio dire|vuol dire|significa|mi riferisco a|parlo di|sta per|equivale a)$").matches(connector)
        if (!naming && (BEHAVIOR.containsMatchIn(value) || !Remember.isCommand(value)))
            return addRule("Quando ti ${if (verb.startsWith("chied")) "chiedo" else "dico"} «$x», $value")
        val old = mem.memos().filter { it.kind == Remember.ALIAS && it.key == key }
        old.forEach { mem.removeMemo(it) }
        if (old.isNotEmpty()) memoryDropped = true
        mem.addMemo(Memo(Remember.ALIAS, value, key, now()))
        memoryUrgent = true
        return Outcome.Speak(
            if (Remember.isCommand(value)) "Ok! Quando mi dici «$x», faccio questo: $value."
            else "Ok! Quando mi dici «$x», capisco «$value»."
        )
    }

    // ------------------------------------------------------------------ guardare, insegnato
    private val CAMWORD = ci("\\b(?:fotocamera|camera|telecamera|videocamera)\\b")
    private val CAM_OPEN_VERB = ci("\\b(?:apri|aprire|accendi|accendere|attiva|attivare|usa|usare|avvia|avviare|prendi)\\b")
    private val PHOTOISH = ci("\\b(?:scatta|scattare|fai|fare|fammi|farmi)\\s+(?:un[oa]? |una |la |il |delle )?(?:foto|selfie|fotografia)\\b")
    private val LOOKISH = ci("\\b(?:guarda|guardare|guardi|guardami|guardarmi|vedere|vedi|osserva|osservare|inquadra|inquadrare|" +
        "descrivi|descrivere|descrivimi|riconosci|riconoscere)\\b")
    private val FRONTISH = ci("\\b(?:davanti|frontale|anteriore|selfie)\\b")

    /** Un comando che vuol dire guardare con la fotocamera ("apri la fotocamera, guarda e poi chiudila", "cosa vedi"). */
    private fun lookValue(v: String): Boolean {
        if (CAMWORD.containsMatchIn(v)) return LOOKISH.containsMatchIn(v) || (CAM_OPEN_VERB.containsMatchIn(v) && !PHOTOISH.containsMatchIn(v))
        return (sightCommand(v, v) as? Outcome.Look)?.explain == false
    }

    /** Un comando che vuol dire fare una foto con l'app Fotocamera ("apri la fotocamera e scatta una foto"). */
    private fun photoValue(v: String): Boolean = CAMWORD.containsMatchIn(v) && PHOTOISH.containsMatchIn(v) && !LOOKISH.containsMatchIn(v)

    /** La parola insegnata fa già guardare SAM da sola ("guarda", "cosa vedi", "apri la fotocamera"), con la fotocamera [front]? */
    private fun looksAlready(key: String, front: Boolean): Boolean {
        val l = sightCommand(key, key) as? Outcome.Look ?: return false
        return !l.explain && l.front == front
    }

    /**
     * Insegnare a guardare: "quando ti dico guarda, apri la fotocamera, guarda e poi chiudi in automatico". È la vista di
     * SAM: apre la sua fotocamera, scatta, la richiude da sola e dice cosa vede. (Fino alla 1.6.4 diventava una scorciatoia
     * che apriva l'app Fotocamera: restava aperta e SAM non ti sentiva più finché non la chiudevi tu.) null = non è guardare.
     */
    private fun teachLook(x: String, key: String, value: String): Outcome? {
        val v = value.lowercase()
        val look = lookValue(v)
        val photo = !look && photoValue(v)
        if (!look && !photo) return null
        val old = mem.memos().filter { it.kind == Remember.ALIAS && it.key == key }
        old.forEach { mem.removeMemo(it) }
        if (old.isNotEmpty()) { memoryUrgent = true; memoryDropped = true }
        val front = SEE_ME.containsMatchIn(v) || FRONTISH.containsMatchIn(v)
        if (photo) {
            mem.addMemo(Memo(Remember.ALIAS, if (front) "fai un selfie" else "scatta una foto", key, now())); memoryUrgent = true
            return Outcome.Speak("Va bene! Quando mi dici «$x», apro l'app Fotocamera per fare la foto. " +
                "Finché resta aperta potrei non sentirti: quando hai finito, chiudila.")
        }
        val said = "Va bene! Quando mi dici «$x», " + (if (front) "apro la fotocamera davanti, ti guardo" else "apro la mia fotocamera, guardo") +
            ", la richiudo da sola e ti dico cosa vedo."
        // "guarda", "cosa vedi": lo faccio già così, non serve una scorciatoia
        if (looksAlready(key, front)) return Outcome.Speak(said)
        mem.addMemo(Memo(Remember.ALIAS, if (front) "guardami" else "guarda", key, now())); memoryUrgent = true
        return Outcome.Speak(said)
    }

    /**
     * Le scorciatoie vecchie per guardare diventano la vista di SAM (una volta, all'avvio): "guarda" insegnato come
     * "apri la fotocamera, guarda e chiudi" apriva l'app Fotocamera, che restava aperta e non lasciava sentire SAM.
     */
    private fun cleanLookAliases() {
        try {
            for (m in mem.memos().filter { it.kind == Remember.ALIAS && Remember.isCommand(it.text) }) {
                val v = m.text.lowercase().trim()
                if (v in setOf("guarda", "guardami", "scatta una foto", "fai un selfie")) continue
                val look = lookValue(v)
                if (!look && !photoValue(v)) continue
                mem.removeMemo(m)
                val front = SEE_ME.containsMatchIn(v) || FRONTISH.containsMatchIn(v)
                when {
                    !look -> mem.addMemo(m.copy(text = if (front) "fai un selfie" else "scatta una foto"))
                    !looksAlready(m.key, front) -> mem.addMemo(m.copy(text = if (front) "guardami" else "guarda"))
                }
                memoryDropped = true
            }
        } catch (e: Exception) {}
    }

    /** Una scorciatoia detta da sola ("buonanotte", "fai buonanotte"): esegue i comandi insegnati. */
    private fun aliasCommand(raw: String, depth: Int): Outcome? {
        val aliases = mem.memos().filter { it.kind == Remember.ALIAS && Remember.isCommand(it.text) }
        if (aliases.isEmpty()) return null
        val k1 = Remember.aliasKey(raw)
        val k2 = Remember.aliasKey(ci("^(?:fai|esegui|attiva|avvia)\\s+(?:la |il )?(?:modalità |scorciatoia |comando )?").replace(raw.trim(), ""))
        val m = aliases.lastOrNull { it.key == k1 } ?: aliases.lastOrNull { it.key == k2 } ?: return null
        // una scorciatoia per guardare su una parola che fa già guardare SAM ("guarda"): ci pensa la sua vista
        if (lookValue(m.text.lowercase()) && looksAlready(m.key, m.text.lowercase() == "guardami" || FRONTISH.containsMatchIn(m.text))) return null
        if (depth >= 3) return Outcome.Speak("Questa scorciatoia richiama sé stessa: insegnamela di nuovo.")
        return runSequence(m.text, depth + 1)
    }

    /** Esegue uno o più comandi di fila: "spegni la torcia e metti la sveglia alle 7". */
    private fun runSequence(value: String, depth: Int): Outcome {
        val parts = value.split(SEQ).map { imperative(it.trim()) }.filter { it.isNotEmpty() }
        val said = ArrayList<String>()
        var end = false
        for (p in parts) {
            val o = try { route(p.lowercase(), depth) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            when (o) {
                is Outcome.Speak -> {
                    said.add(o.text); end = o.endSession
                    if (pending != null) return Outcome.Speak(said.joinToString(" "))   // serve una risposta: ci si ferma qui
                }
                is Outcome.Handled -> end = o.endSession
                else -> return if (said.isEmpty()) o else Outcome.Many(listOf(Outcome.Speak(said.joinToString(" ")), o))
            }
        }
        return if (said.isEmpty()) Outcome.Handled(end) else Outcome.Speak(said.joinToString(" "), end)
    }

    /** Un nome insegnato ("amore" -> "giulia"): vale per contatti, app e posti. */
    private fun resolveName(s: String): String {
        val k = Remember.aliasKey(s)
        if (k.isEmpty()) return s
        return mem.memos().lastOrNull { it.kind == Remember.ALIAS && it.key == k && !Remember.isCommand(it.text) }?.text ?: s
    }

    /** Un posto: nome insegnato, oppure "casa" = dove abiti (se me l'hai detto). */
    private fun resolvePlace(dest: String): String {
        val r = resolveName(dest)
        if (r != dest) return Num.wordsToDigits(r)        // "via roma dieci" -> "via roma 10"
        if (ci("^(?:a )?(?:casa|casa mia|a casa mia)$").containsMatchIn(dest)) Remember.home(mem.memos())?.let { return it }
        return dest
    }

    // ------------------------------------------------------------------ meteo
    private val WEATHER = ci(
        "\\b(?:che tempo|com'è il tempo|come è il tempo|come sarà il tempo|com'è il meteo|meteo|previsioni del tempo|previsioni meteo|" +
        "le previsioni|pioverà|piove|nevicherà|nevica|grandinerà|l'ombrello|ombrello|farà caldo|" +
        // "quanti gradi ci sono", "che temperatura c'è a Roma" (non "che temperatura ha il sole")
        "(?:quanti gradi|che temperatura)(?=\\s*$|\\s+(?:ci sono|c'è|ci sarà|ci saranno|fa|farà|avremo|abbiamo|sono|è|sarà|oggi|" +
        "domani|dopodomani|stasera|stanotte|fuori|adesso|ora|a|ad|in|per|nel|nella|al|alla|questa|questo|nel)\\b)|" +
        "farà freddo|fa caldo|fa freddo|farà bel tempo|farà brutto tempo|c'è il sole|c'è sole|ci sarà il sole|ci sarà sole|" +
        "c'è vento|tira vento|ci sarà vento|ci sarà un temporale|ci sarà temporale|arriva un temporale|è nuvoloso|sarà nuvoloso|sarà sereno)\\b"
    )
    private val NOT_WEATHER = ci(
        "\\b(?:perché|perche|come mai|come si forma|come si formano|cos'è|cosa sono|che cos'è|spiegami|significa|vuol dire|" +
        "verbo|verbale|verbi|coniugazione|in che tempo|in quanto tempo|ci vuole|ci vogliono|ci metto|ci mette|impiego|cerchio|" +
        "angolo|triangolo|bolle|bollire|forno|corpo|febbre|motore|processore|cpu|batteria|frigo|frigorifero|congelatore|borsa|" +
        "bitcoin|azioni|mercato|mercati|economia|inflazione|dollaro|petrolio|cambio|vendite|elezioni|sondaggi|partita|campionato)\\b"
    )
    private val NOT_WEATHER_START = ci(
        "^(?:scrivi|scrivere|manda|mandare|invia|inviare|rispondi|messaggio|whatsapp|chiama|telefona|cerca|cercami|trova|" +
        "trovami|googla|fai una ricerca|ricordami|ricordati)\\b|^apri\\b(?!.*\\b(?:dimmi|leggi|leggimi|che tempo)\\b)"
    )
    private val NOT_PLACE = setOf(
        "oggi", "domani", "dopodomani", "stasera", "stanotte", "stamattina", "stamani", "adesso", "ora", "questa", "questo",
        "questi", "queste", "prossima", "prossimo", "prossimi", "settimana", "weekend", "week", "fine", "mattina", "pomeriggio",
        "sera", "notte", "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica", "casa", "fuori", "qui",
        "qua", "lì", "là", "me", "te", "noi", "zona", "giorni", "giorno", "ore", "tempo", "meteo", "previsioni", "gradi",
        "temperatura", "ombrello", "sole", "vento", "pioggia", "neve", "caldo", "freddo", "nuvoloso", "sereno", "mare",
        "montagna", "spiaggia", "scuola", "lavoro", "ufficio", "palestra", "strada", "giro", "viaggio", "uscire", "fare",
        "un", "una", "uno", "che", "come", "quanti", "quanto", "il", "lo", "la", "gli", "le", "momento",
        "breve", "serata", "mattinata", "giornata", "nottata", "pranzo", "cena", "dice", "dicono", "prevede", "segna",
        "mostra", "sarà", "farà", "fa", "va", "sta", "ci", "ha", "dà", "c'", "l'", "andare", "uscire", "venire", "tornare",
        "partire", "arrivare", "correre", "camminare", "lavorare", "giocare", "stare", "essere"
    )
    private val PLACE_PREP = setOf("a", "ad", "in", "per", "di", "su", "sul", "sulla", "nel", "nella", "al", "alla", "allo",
        "all'", "nell'", "sull'", "dell'", "del", "della")
    private val PLACE_ARTICLES = setOf("il", "lo", "la", "l'", "i", "gli", "le")
    private val PLACE_JOIN = setOf("di", "del", "della", "de", "dei", "sul", "sulla", "in", "a", "al", "san", "santa", "d'", "sant'")

    /** "che tempo fa a Reggio Emilia domani" -> "reggio emilia". null = dove sei. */
    private fun weatherPlace(t: String): String? {
        val w = t.lowercase().replace("'", "' ").replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        for (i in w.indices) {
            // "meteo napoli", "che tempo fa napoli" (il riconoscitore a volte perde la "a")
            val afterMeteo = i > 0 && w[i - 1] in setOf("meteo", "fa", "farà") && w[i] !in PLACE_PREP
            if (w[i] !in PLACE_PREP && !afterMeteo) continue
            var j = if (afterMeteo) i else i + 1
            if (j < w.size && w[j] in PLACE_ARTICLES) j++
            // "per andare a lavoro", "per uscire": dopo "per" un verbo non è un posto
            if (w[i] == "per" && j < w.size && Regex("(?:are|ere|ire)$").containsMatchIn(w[j])) continue
            val name = ArrayList<String>()
            while (j < w.size && name.size < 5) {
                val x = w[j]
                if (x in NOT_PLACE) break
                if (x in PLACE_JOIN) {
                    if (name.isEmpty() && x != "san" && x != "santa" && x != "sant'") break
                    if (j + 1 < w.size && w[j + 1] !in NOT_PLACE && w[j + 1] !in PLACE_PREP) { name.add(x); j++; continue }
                    break
                }
                if (x.length < 2 || x.all { it.isDigit() }) break
                name.add(x); j++
            }
            if (name.isNotEmpty() && name.any { it !in PLACE_JOIN }) return name.joinToString(" ").replace("' ", "'")
        }
        return null
    }

    /** Il giorno chiesto: "domani", "sabato", "nel weekend"… null = oggi / adesso. */
    private fun weatherDay(t: String): String? = when {
        ci("\\bdopodomani\\b").containsMatchIn(t) -> "dopodomani"
        ci("\\bdomani\\b").containsMatchIn(t) -> "domani"
        ci("\\b(?:weekend|week end|fine settimana|fine-settimana)\\b").containsMatchIn(t) -> "nel weekend"
        ci("\\b(?:questa settimana|settimana prossima|prossima settimana|prossimi giorni|settimana)\\b").containsMatchIn(t) -> "nei prossimi giorni"
        else -> giorni.firstOrNull { ci("\\b$it\\b").containsMatchIn(t) }
            ?: if (ci("\\b(?:stasera|stanotte|questa sera|questa notte)\\b").containsMatchIn(t)) "stasera" else null
    }

    /** "che tempo fa?", "pioverà domani a Roma?", "serve l'ombrello?". null = non è una domanda sul meteo. */
    private fun weatherRequest(raw: String): Outcome? {
        if (!WEATHER.containsMatchIn(raw) || NOT_WEATHER.containsMatchIn(raw) || NOT_WEATHER_START.containsMatchIn(raw)) return null
        var city = weatherPlace(raw)?.let { resolveName(it) }
        if (city == null && ci("\\b(?:a casa|casa mia|da me|dove abito|dalle mie parti)\\b").containsMatchIn(raw))
            city = resolveName("casa").takeIf { it != "casa" } ?: Remember.home(mem.memos())
        return Outcome.Weather(city, weatherDay(raw), raw)
    }

    // ------------------------------------------------------------------ modalità conversazione (con SAM)
    // Prima "modalità conversazione" faceva parlare SAM con un'altra IA: tu diventavi "Altra IA" e i comandi
    // non funzionavano più. Ora è la conversazione con SAM; con un'altra IA serve dirlo ("parla con ChatGPT").
    private val CHAT_ON = ci(
        "^$FILL(?:(?:avvia|attiva|inizia|entra in|metti|accendi|apri|passa alla|vai in|torna in) )?(?:la )?" +
        "modalità (?:conversazione|conversa home|conversa|chiacchierata|dialogo|chat)(?:\\s+(?:di|del|della|sul|sulla|su|riguardo)\\s+(.+))?$|" +
        "^$FILL(?:(?:dai|allora|adesso|ora|ti va di|vuoi|possiamo|io e te) )?(?:parliamo|chiacchieriamo|conversiamo|parlare|chiacchierare|" +
        "fare (?:due|quattro|2|4) chiacchiere|facciamo (?:due|quattro|2|4) chiacchiere|facciamo (?:una )?(?:chiacchierata|conversazione)|" +
        "facciamo conversazione|fai (?:una )?(?:conversazione|chiacchierata) con me|parla con me|parla un po' con me|chiacchiera con me|" +
        "voglio parlare con te|vorrei parlare con te|ho voglia di parlare|vuoi parlare con me|parlare con me)" +
        "(?:\\s+(?:un po'|insieme|un attimo|con me|io e te))?(?:\\s+(?:di|del|della|dei|delle|sul|sulla|sui|sulle|su|riguardo)\\s+(.+))?$"
    )
    private val CHAT_OFF = ci(
        "^$FILL(?:(?:esci|uscire|esci fuori) (?:dalla )?modalità (?:conversazione|chiacchierata|dialogo|chat)|" +
        "(?:disattiva|chiudi|termina|ferma|spegni|togli|finisci) (?:la )?modalità (?:conversazione|chiacchierata|dialogo|chat)|" +
        "fine (?:della )?(?:conversazione|chiacchierata)|basta (?:con la )?(?:conversazione|chiacchierata|parlare|chiacchierare|chiacchiere)|" +
        "smettiamo di parlare|(?:chiudi|termina|finisci|ferma) (?:la|questa) (?:conversazione|chiacchierata))$"
    )

    // ------------------------------------------------------------------ conversazione con un'altra IA
    // durante la conversazione con l'altra IA: "SAM, apri la fotocamera" è il proprietario che dà un comando
    private val STRONG_COMMAND = ci(
        "^(?:apri|accendi|spegni|chiama|telefona|scrivi|manda|metti|imposta|alza|abbassa|cerca|cercami|cercalo|ricordati|ricorda|" +
        "memorizza|dimentica|scatta|leggimi|portami|naviga|blocca|esci|che ore|che tempo|che giorno|quanta batteria|cosa vedi|" +
        "dimmi cosa vedi|guarda|guardami|mi vedi|modalità conversazione|parliamo|torcia|sveglia|timer|volume)\\b"
    )

    /** Durante la conversazione con un'altra IA: il comando che il proprietario ha dato a SAM, oppure null (parla l'altra IA). */
    fun commandDuringDialogue(heard: String): String? {
        val m = ci("^(?:ehi |hey |ok |senti )?(?:sam|sem)\\b[\\s,.!?:]*(.+)$").find(heard.trim()) ?: return null
        val rest = m.groupValues[1].trim()
        return if (STRONG_COMMAND.containsMatchIn(rest) || LOOK.containsMatchIn(rest) || LOOK2.containsMatchIn(rest)) rest else null
    }

    /** "parla con chat gpt", "fai una conversazione con l'altra intelligenza artificiale sul futuro". */
    private fun dialogueRequest(t: String): Pair<String?, String?>? {
        // ("modalità conversazione" adesso è la conversazione con SAM: vedi CHAT_ON)
        val m = ci("^(?:vorrei che |voglio che |puoi )?(?:parla|parlare|parli|conversa|conversare|chiacchiera|chiacchierare|dialoga|dialogare|discuti|discutere|fai (?:una )?conversazione|fai due chiacchiere|fai una chiacchierata)\\s+(?:un po'\\s+)?(?:con|insieme a|assieme a)\\s+(.+)").find(t)
            ?: return null
        var rest = m.groupValues[1]
        val chi = ci("^(?:l'|un'|una |la |il |lo |un |con )?(?:altra |altro )?(chat gpt|chatgpt|ciat gpt|chat|gpt|gemini|claude|cloud|alexa|siri|copilot|deepseek|bixby|intelligenza artificiale|intelligenza|ia|assistente|robot|ai|altro telefono|telefono|computer|pc|tablet)\\b").find(rest)
            ?: return null   // "parla con mario" non è questo
        val w = chi.groupValues[1].lowercase()
        val nome = when {
            w.contains("chat") || w.contains("gpt") -> "ChatGPT"
            w == "gemini" -> "Gemini"
            w == "claude" || w == "cloud" -> "Claude"
            w == "alexa" -> "Alexa"
            w == "siri" -> "Siri"
            w == "copilot" -> "Copilot"
            w == "deepseek" -> "DeepSeek"
            w == "bixby" -> "Bixby"
            else -> null
        }
        rest = rest.substring(chi.range.last + 1)
        rest = ci("^\\s*(?:gpt|artificiale)\\b").replace(rest, "")
        var tema = ci("^\\s*(?:e\\s+)?(?:parlate\\s+|parlando\\s+)?(?:(?:di|del|della|dei|degli|delle|dello|sul|sulla|sui|sugli|sulle|sullo|su|riguardo|a proposito di)\\s+|dell'|sull')(.+)").find(rest)?.groupValues?.get(1)?.trim()
        if (tema != null) tema = ci("^(?:(?:alla|allo|alle|agli|ai|al|a|il|la|lo|i|gli|le)\\s+|l')").replace(tema, "").trim().ifEmpty { null }
        return nome to tema
    }

    // ------------------------------------------------------------------ contatti e messaggi
    /** Toglie articoli e parole di contorno dal nome: "alla mia mamma per favore" -> "mamma". */
    private fun cleanName(n: String): String {
        var x = n.trim()
        repeat(3) { x = ci("^(?:a|ad|al|alla|allo|all'|il|la|lo|l'|i|le|mio|mia|miei|mie)\\s+").replace(x, "") }
        x = ci("\\s+(?:per favore|per piacere|subito|adesso|ora|grazie)$").replace(x, "")
        return x.trim(' ', ',', '.', '?', '!')
    }

    /** La scelta imparata tra due omonimi, usata da sola: se dici "no" si dimentica e si chiede di nuovo. */
    private class AutoPick(val pref: Memo, val options: List<Contact>)
    private var autoPick: AutoPick? = null

    /** Trova il contatto e prosegue; se ce ne sono più di uno con lo stesso nome chiede quale (e se lo ricorda). */
    private fun withContact(name: String, next: (Contact) -> Outcome): Outcome {
        autoPick = null
        val chi = cleanName(name)
        if (chi.isEmpty()) return Outcome.Speak("A chi? Dimmi il nome.")
        // numero dettato: si ripete cifra per cifra
        Num.phoneDigits(chi)?.let { n -> return next(Contact(n.toCharArray().joinToString(" "), n)) }
        val (nome, found) = bestContacts(chi)
        if (found.isEmpty()) return Outcome.Speak("Non trovo $chi in rubrica.")
        val pari = ArrayList<Contact>()
        for (c in ContactMatch.ties(nome, found)) if (pari.none { it.number.filter { d -> d.isDigit() }.takeLast(9) == c.number.filter { d -> d.isDigit() }.takeLast(9) }) pari.add(c)
        if (pari.size == 1) return next(pari[0])
        val scelte = pari.take(3)
        val key = "contatto:" + Remember.norm(nome)
        // migliora con l'uso: se l'altra volta hai scelto Mario Rossi, ora propone subito lui
        val pref = mem.memos().lastOrNull { it.kind == Remember.SCELTA && it.key == key }
        val usual = pref?.let { p -> scelte.firstOrNull { it.name == p.text } }
        if (pref != null && usual != null) {
            val o = next(usual)
            autoPick = AutoPick(pref, scelte)
            return o
        }
        pending = { ans ->
            val c = pick(ans, scelte)
            if (c == null) Outcome.Speak("Non ho capito quale. Riprova dicendo anche il cognome.", endSession = true)
            else { rememberChoice(key, c); next(c) }
        }
        val nomi = scelte.map { it.name }
        return Outcome.Speak("Ho trovato ${nomi.dropLast(1).joinToString(", ")} e ${nomi.last()}. Quale?")
    }

    private fun rememberChoice(key: String, c: Contact) {
        mem.memos().filter { it.kind == Remember.SCELTA && it.key == key }.forEach { mem.removeMemo(it) }
        mem.addMemo(Memo(Remember.SCELTA, c.name, key, now()))
    }

    /**
     * "Chiamo Mario Rossi?" "No": la scelta imparata era sbagliata. La dimentica e chiede quale
     * (o prende subito quello detto: "no, Bianchi", "no, l'altro"). null = la risposta non c'entra.
     */
    private fun retryChoice(ans: String, then: (Contact) -> Outcome): Outcome? {
        val ap = autoPick ?: return null
        autoPick = null
        if (!Lang.isNo(ans)) return null
        mem.removeMemo(ap.pref)
        val others = ap.options.filter { it.name != ap.pref.text }
        val rest = ci("^\\W*no\\b[\\s,.!]*").replace(ans, "").trim()
        val direct = when {
            rest.isEmpty() -> null
            ci("\\b(?:l'altr[oa]|altr[oa])\\b").containsMatchIn(rest) && others.size == 1 -> others[0]
            else -> pick(rest, others)?.takeIf { ContactMatch.score(cleanName(rest), it.name) >= 50 }
        }
        if (direct != null) { rememberChoice(ap.pref.key, direct); return then(direct) }
        pending = { a2 ->
            val c = pick(a2, ap.options)
            if (c == null) Outcome.Speak("Non ho capito quale. Riprova dicendo anche il cognome.", endSession = true)
            else { rememberChoice(ap.pref.key, c); then(c) }
        }
        val nomi = ap.options.map { it.name }
        return Outcome.Speak("Scusa. Quale allora: ${nomi.dropLast(1).joinToString(", ")} o ${nomi.last()}?")
    }

    /** "Chiamo Mario?" e poi la chiamata. */
    private fun askCall(c: Contact, viaWhatsApp: Boolean): Outcome {
        pending = { ans ->
            if (Lang.isYes(ans)) {
                autoPick = null
                when {
                    !a.call(c.number) -> Outcome.Speak("Non riesco a fare la chiamata.")
                    a.canCallDirectly() -> Outcome.Handled(endSession = true)
                    else -> Outcome.Speak("Ti ho aperto il numero: premi il tasto verde. Per chiamare da solo mi serve il permesso Telefono, dalla configurazione.", endSession = true)
                }
            } else retryChoice(ans) { other -> askCall(other, viaWhatsApp) } ?: Outcome.Speak("Ok, niente chiamata.", endSession = true)
        }
        return Outcome.Speak(if (viaWhatsApp) "Le chiamate WhatsApp non posso farle. Chiamo ${c.name} con il telefono?" else "Chiamo ${c.name}?")
    }

    /**
     * I contatti per il nome detto (anche un nome insegnato: "amore" -> "giulia"). Se il nome intero non c'è,
     * prova le prime parole ("mamma e dille che arrivo" -> "mamma"), ma solo con una corrispondenza sicura.
     */
    private fun bestContacts(chi0: String): Pair<String, List<Contact>> {
        val chi = resolveName(chi0)
        val all = a.contacts(chi)
        if (all.isNotEmpty()) return chi to all
        val parole = chi.split(' ')
        for (k in minOf(3, parole.size - 1) downTo 1) {
            val nome = resolveName(parole.take(k).joinToString(" "))
            val c = a.contacts(nome)
            if (c.isNotEmpty() && ContactMatch.score(nome, c[0].name) >= 90) return nome to c
        }
        return chi to emptyList()
    }

    /** Quale tra i contatti proposti: "il secondo", "Rossi", "Mario Bianchi". */
    private fun pick(ans: String, opts: List<Contact>): Contact? {
        val ordinali = listOf("prim", "second", "terz")
        for ((i, o) in ordinali.withIndex())
            if (i < opts.size && ci("\\b(?:il |la |l')?${o}[oa]\\b").containsMatchIn(ans)) return opts[i]
        var best: Contact? = null; var bestScore = 0
        for (o in opts) {
            val sc = ContactMatch.score(cleanName(ans), o.name)
            if (sc > bestScore) { best = o; bestScore = sc }
        }
        return best
    }

    /**
     * "scrivi a Mario su WhatsApp che arrivo", "manda un sms a Luca", "manda un messaggio WhatsApp
     * a mamma: ci vediamo dopo". Canale (wa/sms), destinatario e testo (null = da chiedere).
     */
    private fun parseMessage(t: String): Triple<String, String, String?>? {
        if (!ci("^(?:vorrei |voglio |devo |fammi |mi )?(?:scrivi|scrivere|scrivigli|scrivile|manda|mandare|mandami|invia|inviare|messaggia|rispondi|un messaggio|messaggio|whatsapp|un whatsapp)\\b").containsMatchIn(t))
            return null
        val canale = when {
            ci("\\b(sms|messaggino|messaggio normale)\\b").containsMatchIn(t) -> "sms"
            ci("\\bwhatsapp\\b").containsMatchIn(t) -> "wa"
            ci("\\bmessaggio\\b").containsMatchIn(t) -> "sms"
            else -> "wa"
        }
        var x = ci("\\b(?:un |il |uno )?(?:messaggio |messaggino )?(?:su |con |via |tramite |per |in )?(?:whatsapp|sms)\\b").replace(t, " ")
        x = ci("\\b(?:un |il )?(?:messaggio|messaggino)\\b").replace(x, " ")
        x = x.replace(Regex("\\s+"), " ").trim()
        // "scrivi una poesia a mia madre" non è un messaggio: il destinatario viene subito dopo il verbo
        val m = ci("^(?:vorrei |voglio |devo |fammi |mi )?(?:(?:scrivi|scrivere|scrivigli|scrivile|manda|mandare|mandami|invia|inviare|messaggia|rispondi)\\s+)?(?:subito\\s+|anche\\s+)?(?:(?:a|ad|al|alla|allo)\\s+|all'\\s*)(.+)").find(x)
        if (m == null) {
            // "scrivi su whatsapp che arrivo", "manda un messaggio": manca il destinatario, lo si chiede
            if (!ci("\\b(whatsapp|sms|messaggio|messaggino)\\b").containsMatchIn(t)) return null
            val b = ci("(?:\\s*:\\s*|\\s+(?:che|dicendo|scrivendo|con scritto)\\s+)(.+)").find(x)?.groupValues?.get(1)?.trim()
            return Triple(canale, "", b?.ifEmpty { null })
        }
        var chi = m.groupValues[1]
        var testo: String? = null
        val sep = ci("\\s*:\\s*|\\s+(?:che|dicendo|dicendogli|dicendole|di dire|di dirgli|di dirle|scrivendo|con scritto|con il testo)\\s+").find(chi)
        if (sep != null) {
            testo = chi.substring(sep.range.last + 1).trim().ifEmpty { null }
            chi = chi.substring(0, sep.range.first)
        }
        chi = cleanName(chi)
        // "scrivi a mario arrivo tra poco": il nome è all'inizio, il resto è il testo
        val rc = resolveName(chi)
        val intero = a.contacts(rc).firstOrNull()
        if (testo == null && (intero == null || ContactMatch.score(rc, intero.name) < 90)) {
            val parole = chi.split(' ')
            if (parole.size > 1) for (k in minOf(3, parole.size - 1) downTo 1) {
                val nome = parole.take(k).joinToString(" ")
                val rn = resolveName(nome)
                val c = a.contacts(rn).firstOrNull() ?: continue
                if (ContactMatch.score(rn, c.name) >= 90) { chi = nome; testo = parole.drop(k).joinToString(" "); break }
            }
        }
        if (chi.isEmpty()) return null
        return Triple(canale, chi, testo)
    }

    private fun message(canale: String, chi: String, testo: String?): Outcome {
        if (chi.isBlank()) {
            pending = { nome -> message(canale, nome, testo) }
            return Outcome.Speak("A chi lo mando?")
        }
        return messageTo(canale, chi, testo)
    }

    private fun messageTo(canale: String, chi: String, testo: String?): Outcome = withContact(chi) { c ->
        if (testo.isNullOrBlank()) {
            pending = { body -> confirmMessage(canale, c, body) }
            awaitingText = true
            Outcome.Speak(if (canale == "wa") "Cosa scrivo a ${c.name} su WhatsApp?" else "Cosa scrivo a ${c.name}?")
        } else confirmMessage(canale, c, testo)
    }

    private fun confirmMessage(canale: String, c: Contact, body0: String): Outcome {
        val body = body0.trim().replaceFirstChar { it.uppercase() }
        if (canale == "wa" && !a.canAutoSend()) {
            // senza il servizio di accessibilità SAM non può premere invia: apre la chat con il testo pronto
            return if (a.openWhatsApp(c.number, body))
                Outcome.Speak("Ho aperto la chat di ${c.name} con il messaggio pronto: premi invia. Se attivi il servizio di accessibilità di SAM, lo invio io.", endSession = true)
            else Outcome.Speak("Non riesco ad aprire WhatsApp.")
        }
        pending = { ans ->
            when {
                !Lang.isYes(ans) -> retryChoice(ans) { other -> confirmMessage(canale, other, body) }
                    ?: Outcome.Speak("Va bene, non lo mando.", endSession = true)
                canale == "wa" -> { autoPick = null; if (a.sendWhatsApp(c.number, body)) Outcome.Speak("Lo invio.", endSession = true)
                                  else Outcome.Speak("Non riesco ad aprire WhatsApp.") }
                a.sendSms(c.number, body) -> { autoPick = null; Outcome.Speak("Messaggio inviato.", endSession = true) }
                else -> Outcome.Speak("Non riesco a mandare l'SMS.")
            }
        }
        return Outcome.Speak("Mando a ${c.name}${if (canale == "wa") " su WhatsApp" else ""}: $body. Confermi?")
    }

    private val BROWSER = ci("\\b(?:su google|con google|nel browser|apri google|vai su google|apri internet e|col browser|con il browser|in chrome)\\b")
    private val DEEP = ci(
        "^$FILL(?:fai|fammi|fare|farmi|avvia|avviami|prepara|preparami|esegui)\\s+(?:una |la )?ricerca\\s+(?:approfondita|completa|dettagliata|seria|" +
        "accurata|profonda|a fondo)(?:\\s+(?:su internet|sul web|online))?\\s+(?:su|sul|sulla|sullo|sui|sulle|sugli|sull'|di|del|della|riguardo a|riguardo|per)\\s+(.+)$|" +
        "^$FILL(?:fammi|fai|fare|farmi|prepara|preparami|scrivimi|scrivi)\\s+(?:un |una )?(?:rapporto|dossier|relazione approfondita|ricerca completa)\\s+" +
        "(?:su|sul|sulla|sullo|sui|sulle|sugli|sull'|di|del|della|riguardo a)\\s+(.+)$|" +
        "^$FILL(?:approfondisci|approfondiscimi|ricerca a fondo|studia a fondo|cerca a fondo)\\s+(?:su |sul |sulla |il |la |lo |i |le |gli |l')?(.+)$"
    )

    /** "fai una ricerca approfondita sulle auto elettriche" → "auto elettriche". null = no. */
    private fun deepQuery(t: String): String? {
        val m = DEEP.find(t) ?: return null
        val q = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('.', '!', '?') ?: return null
        return q.takeIf { Remember.words(it).isNotEmpty() }
    }

    /** "cerca su internet chi ha vinto", "cercami una pizzeria", "vai su google e cerca…". null = non è una ricerca. */
    private fun searchQuery(t: String): String? {
        val m = ci("^(?:vai su internet e |apri internet e |vai su google e |apri google e |entra su internet e )?(?:cerca|cercami|cercare|ricerca|ricercami|trova|trovami|googla|fai una ricerca|fammi una ricerca)\\s+(.+)").find(t)
            ?: return null
        var q = m.groupValues[1]
        q = ci("^(?:su internet|su google|online|in rete|sul web|nel web|in internet|con google)\\s+").replace(q, "")
        q = ci("^(?:di |su |per |riguardo a |riguardo |sul |sulla |sui |sugli |sulle )").replace(q, "")
        q = ci("\\s+(?:su internet|su google|online|in rete|sul web|nel web|in internet|con google)$").replace(q, "")
        q = q.trim()
        return if (q.isEmpty()) null else q
    }

    // "cercalo su internet", "sì cercalo", "cercalo tu" (e come le sente il riconoscitore: "cercando", "cerca lo")
    private val SEARCH_IT = ci(
        "^$FILL(?:(?:puoi|potresti|allora|dai|vai e) )?(?:cercalo|cercala|cercali|cercale|cercamelo|cercamela|cercameli|cercatelo|cercando|" +
        "cercano|cerca lo|cerca la|trovalo|trovala|guardalo|guardala|googlalo|cerca|cercare|fai la ricerca|fai una ricerca|" +
        "fammi la ricerca|fammi una ricerca|vai a cercare|guarda su internet|controlla su internet|controlla online|verifica su internet)" +
        "(?:\\s+(?:tu|pure|allora|per me|adesso|ora|subito))?(?:\\s+(?:su internet|su google|online|in rete|sul web|nel web|in internet|" +
        "con google|nel browser|in internet))?(?:\\s+(?:tu|pure|per favore|grazie|allora))?$"
    )

    // l'ultima domanda vera (per "cercalo su internet"): non i comandi, non le risposte di una parola
    private var lastTopic: String? = null
    private var lastTopicAt = 0L

    private fun isTopic(t: String) = Remember.words(t).size >= 2 && !SEARCH_IT.containsMatchIn(t) && !CHAT_ON.containsMatchIn(t)

    private fun noteTopic(t: String, o: Outcome) {
        if ((o is Outcome.NeedLlm || o is Outcome.Weather) && isTopic(t)) { lastTopic = t; lastTopicAt = now() }
    }

    private fun recentTopic(): String? = lastTopic?.takeIf { now() - lastTopicAt <= 30 * 60_000L }

    /** 1.16: la ricerca la fa SAM (con le fonti), non più il browser. */
    private fun searchFor(q: String): Outcome = Outcome.Web(q)

    /**
     * Dopo una risposta del cervello del tipo "non posso saperlo": propone di cercare su internet.
     * Se poi l'utente dice altro, la frase viene trattata come un comando normale.
     * Si cerca la domanda vera: "e sabato?" da sola non basta, allora vale l'argomento di prima.
     */
    fun offerSearch(question0: String): String {
        val question = if (isTopic(question0)) question0 else recentTopic() ?: question0
        if (isTopic(question)) { lastTopic = question; lastTopicAt = now() }
        pending = { ans ->
            when {
                Lang.isYes(ans) -> Outcome.Web(question)
                Lang.isNo(ans) -> Outcome.Speak("Va bene.")
                else -> try { route(ans) } catch (e: Exception) { Outcome.Speak("Scusa, qualcosa è andato storto con questo comando.") }
            }
        }
        return "Vuoi che lo cerchi su internet?"
    }

    /** Pausa, riprendi, prossima, precedente. null se la frase non riguarda la musica. */
    private fun mediaCommand(t: String): String? {
        val media = ci("\\b($MEDIA)\\b").containsMatchIn(t)
        if (ci("\\b(prossima|prossimo|successiva|successivo|seguente|salta|avanti|next|skip)\\b").containsMatchIn(t) &&
            (media || ci("^(salta|avanti|prossima|next|skip|la prossima|passa alla prossima|metti la prossima|salta questa)$").containsMatchIn(t)))
            return "next"
        if (media && ci("\\b(precedente|di prima|indietro|previous)\\b").containsMatchIn(t)) return "previous"
        if (ci("\\bpausa\\b").containsMatchIn(t) || (media && off(t))) return "pause"
        if (ci("\\b(riprendi|riparti|ricomincia|continua|fai ripartire|rimetti|play)\\b").containsMatchIn(t) &&
            (media || ci("^(riprendi|riparti|play|fai ripartire)$").containsMatchIn(t)))
            return "play"
        return null
    }

    /** Chiude ciò che è in primo piano (Android non permette di chiudere le app altrui: si torna alla home). */
    private fun close(what: String): Outcome {
        var target = what.trim()
        target = ci("^(?:da|dal|dalla|dallo|dall'|dai|dalle|dagli)\\s*").replace(target, "")
        target = ci("^(?:la|il|lo|l'|le|i|gli|questa|questo|quest')\\s*").replace(target, "").trim()
        if (ci("\\b(tutte|tutti)\\b").containsMatchIn(target) && ci("\\b(app|applicazioni|finestre|programmi)\\b").containsMatchIn(target))
            return if (a.globalAction("recents")) Outcome.Speak("Ti ho aperto le app recenti: tocca Chiudi tutto.", endSession = true)
            else needAccessibility()
        if (ci("\\b(notifiche|notifica|tendina)\\b").containsMatchIn(target))
            return if (a.globalAction("dismiss_shade")) Outcome.Handled(endSession = true) else needAccessibility()
        if (ci("\\b(chiamata|telefonata)\\b").containsMatchIn(target))
            return Outcome.Speak("La chiamata la chiudi tu con il tasto rosso: Android non lo permette alle app.", endSession = true)
        return if (a.globalAction("home")) Outcome.Speak("Fatto.", endSession = true)
        else Outcome.Speak("Non riesco a chiuderla: attiva il servizio di accessibilità di SAM.")
    }

    /** Confusioni tipiche del riconoscitore piccolo, corrette solo dove il senso è chiaro. */
    private fun fixAsr(t: String): String {
        var x = t
        x = ci("^chi ama\\s+").replace(x, "chiama ")          // "chi ama mamma" -> "chiama mamma"
        x = ci("^chiamami\\s+(?!(?:tra|fra)\\b)").replace(x, "chiama ")   // "chiamami mario" -> "chiama mario" (non "chiamami tra un'ora")
        x = ci("^spendi\\s+(?=(?:la|il|lo|l')\\b)").replace(x, "spegni ")   // "spendi la sveglia" -> "spegni la sveglia"
        // "dove ho parcheggiato" sentito come "dovevo parcheggiato"
        x = ci("^dovevo\\s+(?=[\\p{L}]+(?:ato|uto|ito|esso|otto|atto|etto|isto|eso)\\b)").replace(x, "dove ho ")
        // "spegni la turchia" -> "spegni la torcia" (serve anche nelle scorciatoie insegnate)
        x = ci("\\b(accendi|spegni|accendere|spegnere)\\s+(la\\s+)?(?:turchia|torchia|porcia)\\b").replace(x) { m -> m.groupValues[1] + " " + m.groupValues[2] + "torcia" }
        return x
    }

    private fun isTorch(t: String): Boolean {
        if (ci("^$FILL(?:(?:fammi|fai|dammi|fammi un po' di|un po' di)\\s+)?luce(?:\\s+(?:per favore|qui|sam))*$").containsMatchIn(t)) return true
        if (ci("\\b(torcia|torce|lanterna)\\b").containsMatchIn(t) || t.contains("luce del telefono")) {
            if (t.split(' ').size <= 3) return true
            return ci("\\b(accendi|accendere|accendimi|attiva|attivare|metti|mettimi|$OFF)\\b").containsMatchIn(t)
        }
        // "accendi la turchia", "spegni la luce": solo insieme a un verbo di accensione o spegnimento
        return ci("\\b(accendi|accendere|attiva|$OFF)\\b").containsMatchIn(t) &&
               ci("\\b(turchia|torchia|porcia|luce|lucina)\\b").containsMatchIn(t)
    }

    private fun needAccessibility() =
        Outcome.Speak("Per questo comando devi attivare il servizio di accessibilità di SAM dalle impostazioni.")

    // in fondo: tutte le espressioni qui sopra sono già pronte
    init { cleanLookAliases() }
}
