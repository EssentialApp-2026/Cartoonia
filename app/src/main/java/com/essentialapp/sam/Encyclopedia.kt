package com.essentialapp.sam

import java.io.Closeable

/**
 * L'enciclopedia sul telefono: un file .zim di Kiwix (Wikipedia in italiano), letto dove sta, senza internet.
 * Dalla domanda ("chi era Garibaldi?") al titolo della voce, e dalla voce alla sua introduzione: SAM la dà al
 * cervello prima della domanda (così risponde dai fatti invece che dalla memoria) o la legge a voce.
 */
class Encyclopedia(private val zim: Zim, private val alsoClose: Closeable? = null) : Closeable {

    class Found(val title: String, val text: String)

    /** "2026-06-01" (dal file), o null. */
    val date: String? by lazy { zim.metadata("Date") }
    val name: String by lazy { zim.metadata("Title") ?: "Enciclopedia" }
    val language: String? by lazy { zim.metadata("Language") }
    /** Quante voci (quelle vere, se il file lo dice). */
    val articles: Int by lazy {
        zim.metadata("Counter")?.let { c -> Regex("text/html[^=]*=(\\d+)").find(c)?.groupValues?.get(1)?.toIntOrNull() } ?: zim.entryCount
    }

    /** Il nome della raccolta per Kiwix ("wikipedia_it_all") e la versione ("nopic", "maxi", "mini"): per gli aggiornamenti. */
    val zimName: String? by lazy { zim.metadata("Name") }
    val flavour: String? by lazy { zim.metadata("Flavour") }

    /** Controlla tutto il file con la sua impronta (dopo uno scaricamento). Lento: legge tutti i GB. */
    fun verify(onPct: ((Int) -> Unit)? = null, stop: (() -> Boolean)? = null): Boolean = zim.verify(onPct, stop)

    override fun close() { zim.close(); try { alsoClose?.close() } catch (e: Exception) {} }

    /** Controlla che si legga davvero (anche la compressione): la pagina principale o una voce qualsiasi. */
    fun check() {
        val e = zim.findByPath('W', "mainPage") ?: zim.findByPath(zim.articleNs, "Pagina_principale")
            ?: throw Zim.ZimException("nel file non trovo la pagina principale")
        zim.content(zim.resolve(e))
    }

    /** La voce per [subject] ("garibaldi", "la torre di pisa"): l'introduzione, seguendo rinvii e disambiguazioni. */
    fun find(subject: String, max: Int = 1400): Found? {
        for (cand in candidates(subject)) {
            val e = try { zim.findArticle(cand) } catch (x: Exception) { null } ?: continue
            val real = zim.resolve(e)
            if (real.isRedirect || !isHtml(real)) continue
            val html = String(zim.content(real), Charsets.UTF_8)
            val intro = WikiText.intro(html, max)
            if (intro.isBlank()) continue
            if (WikiText.isDisambiguation(real.title, intro)) {
                // "Garibaldi può riferirsi a: …": la prima voce dell'elenco è di solito quella giusta
                for (link in WikiText.listLinks(html)) {
                    val t = try { zim.findByPath(zim.articleNs, link)?.let { zim.resolve(it) } } catch (x: Exception) { null } ?: continue
                    if (t.isRedirect || !isHtml(t)) continue
                    val i2 = WikiText.intro(String(zim.content(t), Charsets.UTF_8), max)
                    if (i2.isNotBlank() && !WikiText.isDisambiguation(t.title, i2)) return Found(t.title, i2)
                }
                continue
            }
            return Found(real.title, intro)
        }
        return null
    }

    private fun isHtml(e: Zim.Entry) = zim.mimeTypes.getOrNull(e.mime)?.startsWith("text/html") == true

    companion object {
        private fun rx(p: String) = Lang.rx(p)

        private const val LEAD = "^(?:(?:ma|e|allora|senti|dimmi|sai|mi sai dire|mi dici|sapresti dirmi|ok|okay)\\s+)*"
        private const val OF = "(?:di|della|del|dello|dei|degli|delle|su|sul|sulla|sullo|sui|sugli|sulle|riguardo a|riguardo al|riguardo alla|a proposito di)"
        private val QUESTIONS = listOf(
            rx("$LEAD(?:chi|che cosa|cosa|che)\\s+(?:è stato|è stata|e stato|e stata|sono stati|sono state|è|e|era|fu|sono|erano|furono)\\s+(.+)$"),
            rx("$LEAD(?:che cos'|cos')\\s*(?:è|e|era)\\s+(.+)$"),
            // solo "si trova": "dov'è la macchina?" è una cosa tua, non una voce dell'enciclopedia
            rx("$LEAD(?:dove|dov')\\s*(?:si trova|si trovano|si trovava|nasce|sorge|sorgeva)\\s+(.+)$"),
            rx("^(?:parlami|raccontami|dimmi qualcosa|dimmi tutto|spiegami|cosa sai|che cosa sai|che sai|cosa mi sai dire|che mi dici|" +
                "che cosa mi sai dire|mi parli|puoi parlarmi|vorrei sapere qualcosa|mi racconti qualcosa)\\s+(?:$OF\\s+|dell'|sull')(.+)$"),
            rx("$LEAD(?:quando|in che anno|in quale anno)\\s+(?:è nato|è nata|nacque|è morto|è morta|morì|è stato fondato|è stata fondata|" +
                "fu fondato|fu fondata|è stato costruito|è stata costruita|fu costruito|fu costruita|è stata scoperta|è stato scoperto|" +
                "è stato inventato|è stata inventata|è iniziata|è iniziato|è finita|è finito|è scoppiata|c'è stata|c'è stato|è avvenuta|" +
                "è avvenuto|fu scritta|fu scritto|è stata scritta|è stato scritto|visse|è vissuto|ha vissuto|ha regnato|regnò)\\s+(.+)$"),
            rx("$LEAD(?:chi|che cosa|cosa)\\s+(?:ha scritto|ha inventato|ha scoperto|ha dipinto|ha fondato|ha costruito|ha composto|ha diretto|" +
                "ha progettato|ha scolpito|scrisse|inventò|scoprì|dipinse|fondò|costruì|compose|diresse|progettò|scolpì)\\s+(.+)$"),
            rx("$LEAD(?:quanti abitanti (?:ha|conta|ci sono a|ci sono in)|quanto è (?:alto|alta|lungo|lunga|grande|profondo|profonda|esteso|estesa)|" +
                "quanto (?:misura|pesa)|di che nazionalità (?:è|era)|che lingua si parla (?:in|a|nel|nella|negli))\\s+(.+)$"),
            rx("$LEAD(?:qual è|qual e|quale è|quali sono)\\s+(?:la capitale|il capoluogo|la popolazione|la lingua|la lingua ufficiale|la moneta|" +
                "la valuta|l'altezza|la lunghezza|la superficie|la storia|l'origine)\\s+(?:$OF\\s+|dell'|sull')(.+)$")
        )
        /** Le domande che chiedono di leggere proprio l'enciclopedia ("cosa dice l'enciclopedia su Napoli?"). */
        private val READ = listOf(
            rx("^(?:cosa|che cosa) (?:dice|scrive|c'è scritto (?:su|in|nella|nell'))\\s*(?:l'enciclopedia|la enciclopedia|wikipedia|la wikipedia)\\s+(?:$OF\\s+|dell'|sull')(.+)$"),
            rx("^(?:cerca|leggimi|leggi|apri|trova|cercami)\\s+(?:nell'enciclopedia|sull'enciclopedia|in enciclopedia|su wikipedia|in wikipedia|" +
                "nella wikipedia|la voce|la pagina di wikipedia|la voce di wikipedia)\\s+(?:di\\s+|su\\s+|del\\s+|della\\s+|dell')?(.+)$"),
            rx("^(?:cerca|leggimi|cercami|trova)\\s+(.+?)\\s+(?:nell'enciclopedia|sull'enciclopedia|su wikipedia|in wikipedia)$"),
            rx("^(?:enciclopedia|wikipedia)\\s+(.+)$")
        )
        private val ARTICLES = Regex("^(?:il|lo|la|i|gli|le|un|uno|una)\\s+|^(?:l'|un')")
        private val SMALL = setOf("di", "del", "della", "dello", "dei", "degli", "delle", "dell'", "da", "dal", "dalla", "dai", "e", "ed",
            "il", "lo", "la", "i", "gli", "le", "l'", "in", "nel", "nella", "a", "al", "alla", "ai", "con", "per", "su", "sul", "sulla",
            "tra", "fra", "d'", "o", "de", "von", "van", "der", "du")
        private val NOT_SUBJECT = Regex("^(?:questo|questa|quello|quella|questi|queste|quelli|quelle|lui|lei|loro|tu|te|io|me|noi|voi|esso|essa|" +
            "successo|stato|fatto|detto|tutto|niente|nulla|vero|giusto|meglio|peggio|bello|qui|qua|lì|là|ora|adesso|oggi|domani|ieri)\\b|" +
            "\\b(?:mio|mia|miei|mie|tuo|tua|tuoi|tue|nostro|nostra|vostro|vostra|suo|sua)\\b|" +
            "^(?:il |la |lo |l')?(?:attuale|presidente|papa|pontefice|sindaco|premier|primo ministro|ministro|re|regina|capo|segretario|" +
            "allenatore|ct|campione|vincitore|direttore|amministratore|governatore|cancelliere)\\b|" +
            "^(?:il |la |lo |l'|i |le |mio |mia )?(?:mamma|papà|babbo|nonno|nonna|nonni|zio|zia|fratello|sorella|figlio|figlia|figli|moglie|" +
            "marito|fidanzato|fidanzata|amico|amica|amici|cugino|cugina|vicino|vicina|capo|collega|bambino|bambina|amore|tesoro)$")

        /** I nomi di persona più comuni: "chi è Maria?" di solito è una persona di casa, non la voce dell'enciclopedia. */
        private val FIRST_NAMES = ("maria anna giulia francesca chiara sara laura valentina alessia martina sofia giorgia elena silvia federica " +
            "elisa paola roberta simona monica barbara stefania cristina daniela alessandra angela rosa lucia carmela teresa antonella " +
            "giovanna patrizia rita marta irene beatrice aurora alice ginevra emma greta noemi viola camilla ludovica gaia nicole " +
            "giuseppe giovanni antonio mario luigi francesco vincenzo salvatore pietro paolo andrea marco luca matteo alessandro lorenzo " +
            "davide simone riccardo gabriele stefano roberto massimo fabio claudio enrico carlo sergio domenico gennaro ciro raffaele " +
            "pasquale michele angelo nicola alberto franco bruno gianni giorgio leonardo tommaso edoardo filippo emanuele daniele " +
            "federico mattia samuele diego christian manuel cristian antonino rosario carmine sam samuele").split(" ").toSet()

        private val RELATION = Regex("\\b(?:mio|mia|miei|mie|amico|amica|cugino|cugina|fratello|sorella|figlio|figlia|moglie|marito|" +
            "fidanzat[oa]|compagn[oa]|collega|capo|zio|zia|nonno|nonna|suocer[oa]|cognat[oa]|nipote|cane|gatto|si chiama)\\b")

        /**
         * Il soggetto è una persona (o un animale) della tua vita, secondo i ricordi: "Gennaro è mio cugino" -> "chi è Gennaro?"
         * non va cercato nell'enciclopedia. Un posto nominato nei ricordi ("abito vicino a Napoli") invece sì.
         */
        fun isPersonal(subject: String, memoTexts: List<String>): Boolean {
            val bare = ARTICLES.replace(subject.trim().lowercase(), "")
            if (bare.isEmpty()) return false
            val word = Regex("(?<![\\p{L}])" + Regex.escape(bare) + "(?![\\p{L}])")
            return memoTexts.any { t -> val l = t.lowercase(); word.containsMatchIn(l) && RELATION.containsMatchIn(l) }
        }

        /** Un nome di persona comune, da solo: meglio non cercarlo nell'enciclopedia (è quasi sempre qualcuno che conosci). */
        fun isCommonFirstName(subject: String): Boolean = ARTICLES.replace(subject.trim().lowercase(), "").let { it in FIRST_NAMES }

        /** Il soggetto di una domanda da enciclopedia ("chi era Garibaldi" -> "garibaldi"); null = non lo è. */
        fun subject(question: String): String? {
            val q = clean(question)
            for (r in QUESTIONS) r.find(q)?.let { return tidy(it.groupValues[1]) }
            return null
        }

        /** "Cosa dice l'enciclopedia su X?": il soggetto da leggere; null = non è una richiesta di lettura. */
        fun readRequest(question: String): String? {
            val q = clean(question)
            for (r in READ) r.find(q)?.let { return tidy(it.groupValues[1]) }
            return null
        }

        private fun clean(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[?!¿¡]+"), " ").replace(Regex("[.,;:]+\\s*$"), "")
            .replace(Regex("\\s+"), " ").trim()

        private fun tidy(s0: String): String? {
            var s = s0.trim().trim('.', ',', ' ')
            s = s.replace(Regex("\\s+(?:per favore|per piacere|in breve|esattamente|di preciso|grazie|sam|secondo te|secondo wikipedia|" +
                "nell'enciclopedia|sull'enciclopedia|su wikipedia|in poche parole|in sintesi)$"), "").trim()
            if (s.isEmpty() || s.length > 70) return null
            val words = s.split(' ').filter { it.isNotBlank() }
            if (words.size > 8) return null
            if (NOT_SUBJECT.containsMatchIn(s)) return null
            if (words.none { w -> w.replace(Regex("^(?:l'|d'|dell'|un')"), "").count { it.isLetter() } >= 2 && w !in SMALL }) return null
            return s
        }

        private fun capWord(w: String): String {
            // "dell'asse" -> "dell'Asse", "l'aquila" -> "L'Aquila" (solo la parte dopo l'apostrofo, se è un articolo)
            val apo = w.indexOf('\'')
            if (apo in 1..4 && apo < w.length - 1) {
                val head = w.substring(0, apo + 1)
                return head + w.substring(apo + 1).replaceFirstChar { it.uppercase() }
            }
            return w.replaceFirstChar { it.uppercase() }
        }

        /** I titoli da provare per un soggetto: "torre di pisa" -> "Torre di Pisa", "Torre di pisa", … */
        fun candidates(subject: String): List<String> {
            val s = subject.trim().lowercase()
            val bare = ARTICLES.replace(s, "").trim()
            val out = LinkedHashSet<String>()
            for (base in listOf(bare, s)) {
                if (base.isBlank()) continue
                val w = base.split(' ').filter { it.isNotBlank() }
                out.add(w.mapIndexed { i, x -> if (i == 0 || x !in SMALL) capWord(x) else x }.joinToString(" "))    // Torre di Pisa
                out.add(base.replaceFirstChar { it.uppercase() })                                                     // Seconda guerra mondiale
                out.add(w.joinToString(" ") { capWord(it) })                                                          // Torre Di Pisa
                if (base.startsWith("l'")) out.add("L'" + capWord(base.substring(2)))                                  // L'Aquila
            }
            return out.toList()
        }

        /** "2026-06-01" -> "giugno 2026". */
        fun monthYear(date: String?): String? {
            val m = Regex("^(\\d{4})-(\\d{2})").find(date ?: return null) ?: return null
            val month = m.groupValues[2].toInt()
            if (month !in 1..12) return null
            return "${Tools.MESI[month - 1]} ${m.groupValues[1]}"
        }

        /** Quanti mesi ha l'enciclopedia ([date] "2026-06-01"), rispetto a [today]. null = non si sa. */
        fun ageMonths(date: String?, today: java.time.LocalDate = java.time.LocalDate.now()): Int? {
            val m = Regex("^(\\d{4})-(\\d{2})").find(date ?: return null) ?: return null
            return (today.year - m.groupValues[1].toInt()) * 12 + (today.monthValue - m.groupValues[2].toInt())
        }
    }
}
