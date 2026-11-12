package com.essentialapp.sam

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

/**
 * Gli strumenti esatti di SAM: le domande che un cervello IA piccolo sbaglia spesso e che invece si calcolano.
 * Date e ricorrenze (quanto manca a Natale, quando cade Pasqua, che giorno era il…), che ore sono nel mondo,
 * conversioni (miglia, gradi, libbre…), le lettere di una parola, IVA, sconti e percentuali, testa o croce, dadi.
 * Kotlin puro: si collauda sul PC con una data e un'ora fisse.
 */
object Tools {
    val GIORNI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    val MESI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
        "settembre", "ottobre", "novembre", "dicembre")
    /** IVA ordinaria in Italia */
    const val IVA = 22.0
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")

    private fun rx(p: String) = Lang.rx(p)

    /**
     * La risposta esatta a [t0] (la frase già ripulita dal cervello dei comandi, con i numeri in cifre), oppure null
     * se non è una domanda per gli strumenti. [now] e [here] = adesso e il fuso del telefono; [rnd] = per monete e dadi.
     */
    fun answer(t0: String, now: LocalDateTime = LocalDateTime.now(), here: ZoneId = ZoneId.systemDefault(),
               rnd: Random = Random.Default): String? {
        val t = clean(t0)
        if (t.isEmpty()) return null
        return try {
            chance(t, rnd) ?: letters(t) ?: worldClock(t, now, here) ?: calendar(t, now.toLocalDate()) ?: money(t) ?: convert(t)
        } catch (e: Exception) { null }
    }

    private fun clean(s: String) = s.lowercase(Locale.ITALIAN).replace('’', '\'').replace('`', '\'')
        .replace(Regex("[?!¿¡]+"), " ").replace(Regex("[.,;:]+\\s*$"), "").replace(Regex("\\s+"), " ").trim()

    // ================================================================== numeri detti a voce
    /** Un numero all'italiana: virgola e al massimo [dec] decimali, senza zeri inutili. */
    fun say(x: Double, dec: Int = 2): String {
        val f = 10.0.pow(dec)
        val r = Math.round(x * f) / f
        if (abs(r - Math.rint(r)) < 1e-9) return Math.round(r).toString()
        return String.format(Locale.ROOT, "%.${dec}f", r).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    /** Con le cifre che servono (tre significative, gli interi grandi interi): 1609 · 16,1 · 2,54 · 0,454. */
    fun sayNice(x: Double): String {
        val a = abs(x)
        if (a < 1e-12) return "0"
        if (a >= 100) return say(x, 0)
        return say(x, (2 - floor(log10(a)).toInt()).coerceIn(0, 8))
    }

    private fun valueOf(s: String): Double? = parseNum(s.replace(',', '.'))

    /** "3", "3.5", "3 virgola 5", "un", "una", "mezzo", "sei", "2 e mezzo" -> numero. */
    private fun parseNum(s0: String): Double? {
        val s = s0.trim()
        val half = Regex("^(.+?)\\s+e\\s+mezz[oa]$").find(s)
        if (half != null) return parseNum(half.groupValues[1])?.plus(0.5)
        return when (s) {
            "un", "uno", "una", "un'" -> 1.0
            "mezzo", "mezza" -> 0.5
            "sei" -> 6.0
            else -> s.replace(Regex("\\s+virgola\\s+"), ".").replace(',', '.').toDoubleOrNull()
        }
    }

    // ================================================================== testa o croce, dadi, numeri a caso, scelte
    private val COIN = rx("^(?:(?:dai|allora|ok|okay|sam)\\s+)?(?:(?:lancia|tira|lanciami|tirami|fai|facciamo|giochiamo a|giochiamo)\\s+)?" +
        "(?:una\\s+|la\\s+)?(?:moneta|monetina|testa o croce)(?:\\s+(?:per me|in aria))?$")
    private val DICE = rx("^(?:lancia|tira|lanciami|tirami)\\s+(?:(un|il|uno|i|sei|\\d)\\s+)?(dado|dadi)(?:\\s+(?:per me|a sei facce|da sei))?$")
    private val RANDOM_N = rx("^(?:(?:dammi|dimmi|scegli|pensa|estrai|genera|fammi|tirami fuori)\\s+)?(?:a\\s+)?(?:un|uno)\\s+numero" +
        "(?:\\s+(a caso|casuale|random|qualsiasi|qualunque))?(?:\\s+(?:tra|fra|da)\\s+(-?\\d+)\\s+(?:e|a)\\s+(-?\\d+))?(?:\\s+(a caso))?$")
    private val CHOOSE = rx("^(?:scegli|decidi|sceglimi)(?:\\s+tu)?(?:\\s+per me)?\\s+(?:tra|fra)\\s+(.+)$")
    private val CHOOSE2 = rx("^(.+?)\\s+(?:o|oppure)\\s+(.+?)\\s+(?:scegli|decidi) tu$")

    private fun chance(t: String, rnd: Random): String? {
        if (COIN.containsMatchIn(t)) return if (rnd.nextBoolean()) "Ho lanciato la moneta: testa!" else "Ho lanciato la moneta: croce!"
        DICE.find(t)?.let { m ->
            val w = m.groupValues[1]
            val n = when {
                w.isEmpty() -> if (m.groupValues[2] == "dadi") 2 else 1
                w == "un" || w == "il" || w == "uno" -> 1
                w == "i" -> 2
                w == "sei" -> 6
                else -> w.toInt()
            }.coerceIn(1, 6)
            val r = List(n) { rnd.nextInt(1, 7) }
            return if (n == 1) "Ho tirato il dado: ${r[0]}."
            else "Ho tirato $n dadi: ${r.dropLast(1).joinToString(", ")} e ${r.last()}, in tutto ${r.sum()}."
        }
        RANDOM_N.find(t)?.let { m ->
            val (casual, a, b, casual2) = m.destructured
            if (casual.isEmpty() && casual2.isEmpty() && (a.isEmpty() || b.isEmpty())) return null
            var lo = a.toIntOrNull() ?: 1; var hi = b.toIntOrNull() ?: 100
            if (lo > hi) { val x = lo; lo = hi; hi = x }
            return "Il numero è ${rnd.nextInt(lo, hi + 1)}."
        }
        val items = CHOOSE.find(t)?.groupValues?.get(1)?.split(Regex("\\s*,\\s*|\\s+(?:e|o|oppure)\\s+"))
            ?: CHOOSE2.find(t)?.let { listOf(it.groupValues[1], it.groupValues[2]) }
        val opts = items?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
        if (opts != null && opts.size >= 2) return "Scelgo ${opts[rnd.nextInt(opts.size)]}."
        return null
    }

    // ================================================================== lettere di una parola
    private val LETTERS = rx("^(?:quante|da quante|di quante)\\s+lettere\\s+(?:ha|ci sono in|ci sono nella|ci sono nel|contiene|è formata|e formata|" +
        "è composta|e composta|è fatta|e fatta|è|e|compongono)\\s+(?:da\\s+)?(?:la parola\\s+|il nome\\s+|il cognome\\s+|nome\\s+|parola\\s+)?(.+)$")
    private val LETTERS2 = rx("^(?:la parola\\s+|il nome\\s+)?(.+?)\\s+(?:quante lettere ha|da quante lettere è formata|di quante lettere è)$")
    private val VOWELS = rx("^quante\\s+(vocali|consonanti)\\s+(?:ha|ci sono in|ci sono nella|ci sono nel|contiene)\\s+(?:la parola\\s+|il nome\\s+)?(.+)$")
    private val SPELL = rx("^(?:come si scrive|come si scrivono|come scrivo|fai lo spelling di|fammi lo spelling di|lo spelling di|spelling di|" +
        "dimmi le lettere di|scandisci|scandiscimi|compitami|compita)\\s+(?:la parola\\s+|il nome\\s+|il cognome\\s+)?(.+?)(?:\\s+lettera per lettera)?$")
    private val NOT_A_WORD = setOf("un", "uno", "una", "il", "lo", "la", "i", "gli", "le", "di", "a", "da", "in", "con", "su", "per", "tra", "fra",
        "mio", "mia", "tuo", "tua", "suo", "sua", "questo", "questa", "quel", "quella", "che", "cosa", "come")

    /** "ciao", "buon compleanno", "l'albero": parole da contare o compitare (non "una lettera di dimissioni"). */
    private fun words(x: String, max: Int): List<String>? {
        val w = x.trim().split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > max) return null
        if (w[0] in NOT_A_WORD || w.any { it in setOf("mio", "mia", "tuo", "tua", "codice", "targa", "iban") }) return null
        if (w.any { tok -> tok.any { !(it.isLetter() || it == '\'') } }) return null
        return w
    }

    private fun letters(t: String): String? {
        val target = LETTERS.find(t)?.groupValues?.get(1) ?: LETTERS2.find(t)?.groupValues?.get(1)
        if (target != null) {
            if (rx("\\balfabeto\\b").containsMatchIn(target))
                return if (rx("\\b(?:inglese|internazionale)\\b").containsMatchIn(target)) "L'alfabeto inglese ha 26 lettere."
                else "L'alfabeto italiano ha 21 lettere; con J, K, W, X e Y, che usiamo per le parole straniere, arriva a 26."
            val w = words(target, 3) ?: return null
            val n = w.sumOf { tok -> tok.count { it.isLetter() } }
            val shown = w.joinToString(" ")
            return "«$shown» ha $n letter${if (n == 1) "a" else "e"}" + (if (w.size > 1) ", senza contare gli spazi." else ".")
        }
        VOWELS.find(t)?.let { m ->
            val w = words(m.groupValues[2], 3) ?: return null
            val all = w.joinToString("")
            val vowels = all.count { it in "aeiouàèéìíòóùú" }
            val letters = all.count { it.isLetter() }
            val shown = w.joinToString(" ")
            return if (m.groupValues[1] == "vocali") "«$shown» ha $vowels vocal${if (vowels == 1) "e" else "i"}."
            else (letters - vowels).let { c -> "«$shown» ha $c consonant${if (c == 1) "e" else "i"}." }
        }
        SPELL.find(t)?.let { m ->
            val w = words(m.groupValues[1], 2) ?: return null
            return "${w.joinToString(" ").replaceFirstChar { it.uppercase() }}: ${spell(w)}."
        }
        return null
    }

    /** "gennaro" -> "G, E, doppia N, A, R, O" (le accentate e l'apostrofo detti a parole). */
    fun spell(words: List<String>): String {
        val out = ArrayList<String>()
        for ((wi, w) in words.withIndex()) {
            if (wi > 0) out.add("spazio")
            var i = 0
            while (i < w.length) {
                val c = w[i]
                val name = when (c) {
                    '\'' -> "apostrofo"
                    'à', 'á' -> "A accentata"; 'è', 'é' -> "E accentata"; 'ì', 'í' -> "I accentata"
                    'ò', 'ó' -> "O accentata"; 'ù', 'ú' -> "U accentata"
                    else -> c.uppercaseChar().toString()
                }
                if (c.isLetter() && i + 1 < w.length && w[i + 1] == c) { out.add("doppia $name"); i += 2 }
                else { out.add(name); i++ }
            }
        }
        return out.joinToString(", ")
    }

    // ================================================================== che ore sono nel mondo
    private const val CLOCK_HEAD = "(?:sai\\s+)?(?:che ore sono|che ora è|che ora e|che ore saranno|che ore fa|mi dici l'ora|dimmi l'ora|l'ora esatta|l'ora|l'orario)"
    private const val IN_PLACE = "(?:(?:ad|a|in|negli|nella|nelle|nello|nel|alle|alla|al|sulla|sulle)\\s+|nell')"
    private val CLOCK_Q = rx("^$CLOCK_HEAD\\s+(?:adesso\\s+|ora\\s+|in questo momento\\s+)?$IN_PLACE(.+)$")
    private val CLOCK_Q2 = rx("^$IN_PLACE(.+?)\\s+(?:che ore sono|che ora è|che ora e)(?:\\s+adesso|\\s+ora|\\s+in questo momento)?$")
    private val TZ_Q = rx("^(?:che\\s+|qual è il\\s+|qual e il\\s+|com'è il\\s+|quale\\s+|quante ore di\\s+)?(?:fuso orario|fuso|differenza di orario|differenza di fuso|" +
        "differenza di ore|differenza oraria|quante ore di differenza)\\s+(?:c'è\\s+|ci sono\\s+|ha\\s+|hanno\\s+)?" +
        "(?:(?:con|di|del|della|dello|degli|dei|delle|tra l'italia e|tra qui e|tra noi e|in|a|per)\\s+|dell')?(.+)$")
    private val NOT_PLACES = setOf("questo momento", "punto", "realtà", "realta", "tutto", "pratica", "media", "anticipo", "ritardo",
        "qui", "casa", "casa mia", "italia qui")

    private class Place(val where: String, val zone: String)

    /** I posti più chiesti (chiave senza accenti e senza articolo): come si dice ("a New York") e il fuso. */
    private val PLACES: Map<String, Place> by lazy {
        val m = HashMap<String, Place>()
        fun p(where: String, zone: String, vararg keys: String) { for (k in keys) m[k] = Place(where, zone) }
        /** città con il loro nome: "a Parigi" */
        fun c(zone: String, vararg names: String) {
            for (n in names) m[deaccent(n.lowercase())] = Place((if (n.startsWith("A")) "ad " else "a ") + n, zone)
        }
        // Italia
        p("in Italia", "Europe/Rome", "italia", "sicilia", "sardegna", "vaticano", "citta del vaticano")
        c("Europe/Rome", "Roma", "Milano", "Napoli", "Torino", "Firenze", "Venezia", "Bologna", "Palermo", "Bari", "Genova", "Catania",
            "Verona", "Casoria", "Caserta", "Salerno", "Pozzuoli", "Sorrento", "Capri", "Ischia", "Trieste", "Cagliari", "San Marino")
        // Americhe
        c("America/New_York", "New York", "Washington", "Boston", "Miami", "Filadelfia", "Atlanta", "Orlando"); m["nuova york"] = Place("a New York", "America/New_York")
        p("negli Stati Uniti, a New York", "America/New_York", "stati uniti", "usa", "america", "stati uniti d'america")
        p("in Florida", "America/New_York", "florida")
        c("America/Toronto", "Toronto", "Montreal"); p("in Canada, a Toronto", "America/Toronto", "canada")
        c("America/Chicago", "Chicago", "Dallas", "Houston", "New Orleans"); p("in Texas", "America/Chicago", "texas")
        c("America/Denver", "Denver"); c("America/Phoenix", "Phoenix")
        c("America/Los_Angeles", "Los Angeles", "San Francisco", "Las Vegas", "Seattle", "San Diego"); p("in California", "America/Los_Angeles", "california")
        c("America/Vancouver", "Vancouver"); p("in Alaska", "America/Anchorage", "alaska"); p("alle Hawaii", "Pacific/Honolulu", "hawaii", "honolulu")
        p("in Messico, a Città del Messico", "America/Mexico_City", "messico"); c("America/Mexico_City", "Città del Messico"); c("America/Cancun", "Cancún")
        p("a Cuba", "America/Havana", "cuba"); c("America/Havana", "L'Avana"); m["avana"] = Place("all'Avana", "America/Havana")
        m["l'avana"] = Place("all'Avana", "America/Havana")
        p("in Repubblica Dominicana", "America/Santo_Domingo", "repubblica dominicana"); c("America/Santo_Domingo", "Santo Domingo")
        p("in Giamaica", "America/Jamaica", "giamaica"); p("a Porto Rico", "America/Puerto_Rico", "porto rico")
        p("in Brasile, a San Paolo", "America/Sao_Paulo", "brasile"); c("America/Sao_Paulo", "San Paolo", "Rio de Janeiro"); m["sao paulo"] = Place("a San Paolo", "America/Sao_Paulo")
        p("in Argentina", "America/Argentina/Buenos_Aires", "argentina"); c("America/Argentina/Buenos_Aires", "Buenos Aires")
        p("in Cile", "America/Santiago", "cile"); c("America/Santiago", "Santiago del Cile")
        p("in Perù", "America/Lima", "peru"); c("America/Lima", "Lima"); p("in Colombia", "America/Bogota", "colombia"); c("America/Bogota", "Bogotà")
        p("in Venezuela", "America/Caracas", "venezuela"); c("America/Caracas", "Caracas"); p("in Ecuador", "America/Guayaquil", "ecuador"); c("America/Guayaquil", "Quito")
        // Europa
        c("Europe/London", "Londra", "Manchester", "Liverpool", "Edimburgo"); p("in Inghilterra", "Europe/London", "inghilterra")
        p("nel Regno Unito", "Europe/London", "regno unito", "gran bretagna"); p("in Scozia", "Europe/London", "scozia")
        p("in Irlanda", "Europe/Dublin", "irlanda"); c("Europe/Dublin", "Dublino"); p("in Portogallo", "Europe/Lisbon", "portogallo"); c("Europe/Lisbon", "Lisbona")
        p("alle Canarie", "Atlantic/Canary", "canarie", "isole canarie"); c("Atlantic/Canary", "Tenerife", "Gran Canaria", "Lanzarote", "Fuerteventura")
        p("in Spagna", "Europe/Madrid", "spagna"); c("Europe/Madrid", "Madrid", "Barcellona", "Ibiza", "Maiorca", "Valencia", "Siviglia")
        p("in Francia", "Europe/Paris", "francia"); c("Europe/Paris", "Parigi", "Nizza", "Marsiglia")
        p("in Germania", "Europe/Berlin", "germania"); c("Europe/Berlin", "Berlino", "Monaco di Baviera", "Francoforte", "Amburgo")
        p("in Olanda", "Europe/Amsterdam", "olanda", "paesi bassi"); c("Europe/Amsterdam", "Amsterdam")
        p("in Belgio", "Europe/Brussels", "belgio"); c("Europe/Brussels", "Bruxelles")
        p("in Svizzera", "Europe/Zurich", "svizzera"); c("Europe/Zurich", "Zurigo", "Ginevra", "Lugano")
        p("in Austria", "Europe/Vienna", "austria"); c("Europe/Vienna", "Vienna")
        p("in Repubblica Ceca", "Europe/Prague", "repubblica ceca"); c("Europe/Prague", "Praga")
        p("in Polonia", "Europe/Warsaw", "polonia"); c("Europe/Warsaw", "Varsavia", "Cracovia")
        p("in Ungheria", "Europe/Budapest", "ungheria"); c("Europe/Budapest", "Budapest")
        p("in Danimarca", "Europe/Copenhagen", "danimarca"); c("Europe/Copenhagen", "Copenaghen")
        p("in Svezia", "Europe/Stockholm", "svezia"); c("Europe/Stockholm", "Stoccolma")
        p("in Norvegia", "Europe/Oslo", "norvegia"); c("Europe/Oslo", "Oslo")
        p("in Finlandia", "Europe/Helsinki", "finlandia"); c("Europe/Helsinki", "Helsinki")
        p("in Islanda", "Atlantic/Reykjavik", "islanda"); c("Atlantic/Reykjavik", "Reykjavík")
        p("in Grecia", "Europe/Athens", "grecia"); c("Europe/Athens", "Atene", "Creta", "Rodi", "Mykonos", "Santorini")
        p("in Turchia", "Europe/Istanbul", "turchia"); c("Europe/Istanbul", "Istanbul", "Ankara")
        p("in Romania", "Europe/Bucharest", "romania"); c("Europe/Bucharest", "Bucarest")
        p("in Bulgaria", "Europe/Sofia", "bulgaria"); c("Europe/Sofia", "Sofia")
        p("in Ucraina", "Europe/Kiev", "ucraina"); c("Europe/Kiev", "Kiev", "Kyiv")
        p("in Russia, a Mosca", "Europe/Moscow", "russia"); c("Europe/Moscow", "Mosca", "San Pietroburgo")
        p("in Albania", "Europe/Tirane", "albania"); c("Europe/Tirane", "Tirana")
        p("in Serbia", "Europe/Belgrade", "serbia"); c("Europe/Belgrade", "Belgrado")
        p("in Croazia", "Europe/Zagreb", "croazia"); c("Europe/Zagreb", "Zagabria", "Spalato")
        p("a Malta", "Europe/Malta", "malta"); p("in Lussemburgo", "Europe/Luxembourg", "lussemburgo"); c("Europe/Monaco", "Monaco")
        // Africa e oceano Indiano
        p("in Egitto", "Africa/Cairo", "egitto"); c("Africa/Cairo", "Sharm el Sheikh", "Hurghada", "Marsa Alam"); p("al Cairo", "Africa/Cairo", "cairo", "il cairo")
        m["sharm"] = Place("a Sharm el Sheikh", "Africa/Cairo")
        p("in Marocco", "Africa/Casablanca", "marocco"); c("Africa/Casablanca", "Marrakech", "Casablanca")
        p("in Tunisia", "Africa/Tunis", "tunisia"); c("Africa/Tunis", "Tunisi", "Djerba")
        p("in Algeria", "Africa/Algiers", "algeria"); c("Africa/Algiers", "Algeri"); p("in Libia", "Africa/Tripoli", "libia"); c("Africa/Tripoli", "Tripoli")
        p("in Nigeria", "Africa/Lagos", "nigeria"); c("Africa/Lagos", "Lagos"); p("in Senegal", "Africa/Dakar", "senegal"); c("Africa/Dakar", "Dakar")
        p("in Ghana", "Africa/Accra", "ghana"); p("in Kenya", "Africa/Nairobi", "kenya"); c("Africa/Nairobi", "Nairobi")
        p("in Tanzania", "Africa/Dar_es_Salaam", "tanzania"); c("Africa/Dar_es_Salaam", "Zanzibar")
        p("in Etiopia", "Africa/Addis_Ababa", "etiopia"); c("Africa/Addis_Ababa", "Addis Abeba")
        p("in Sudafrica", "Africa/Johannesburg", "sudafrica"); c("Africa/Johannesburg", "Johannesburg", "Città del Capo")
        p("a Capo Verde", "Atlantic/Cape_Verde", "capo verde"); p("a Mauritius", "Indian/Mauritius", "mauritius")
        p("in Madagascar", "Indian/Antananarivo", "madagascar"); p("alle Seychelles", "Indian/Mahe", "seychelles"); p("alle Maldive", "Indian/Maldives", "maldive")
        // Asia e Oceania
        p("in Israele", "Asia/Jerusalem", "israele"); c("Asia/Jerusalem", "Tel Aviv", "Gerusalemme")
        p("in Libano", "Asia/Beirut", "libano"); c("Asia/Beirut", "Beirut"); p("in Giordania", "Asia/Amman", "giordania"); c("Asia/Amman", "Amman")
        p("in Arabia Saudita", "Asia/Riyadh", "arabia saudita"); c("Asia/Riyadh", "Riad")
        p("negli Emirati Arabi", "Asia/Dubai", "emirati", "emirati arabi", "emirati arabi uniti"); c("Asia/Dubai", "Dubai", "Abu Dhabi")
        p("in Qatar", "Asia/Qatar", "qatar"); c("Asia/Qatar", "Doha"); p("in Iran", "Asia/Tehran", "iran"); c("Asia/Tehran", "Teheran")
        p("in Iraq", "Asia/Baghdad", "iraq"); c("Asia/Baghdad", "Baghdad"); p("in Pakistan", "Asia/Karachi", "pakistan"); c("Asia/Karachi", "Karachi")
        p("in India", "Asia/Kolkata", "india"); c("Asia/Kolkata", "Nuova Delhi", "Delhi", "Mumbai", "Bombay", "Goa")
        p("in Nepal", "Asia/Kathmandu", "nepal"); c("Asia/Kathmandu", "Kathmandu")
        p("in Sri Lanka", "Asia/Colombo", "sri lanka"); p("in Bangladesh", "Asia/Dhaka", "bangladesh"); c("Asia/Dhaka", "Dacca")
        p("in Thailandia", "Asia/Bangkok", "thailandia"); c("Asia/Bangkok", "Bangkok", "Phuket")
        p("in Vietnam", "Asia/Ho_Chi_Minh", "vietnam"); c("Asia/Ho_Chi_Minh", "Hanoi", "Ho Chi Minh")
        p("in Cambogia", "Asia/Phnom_Penh", "cambogia"); c("Asia/Singapore", "Singapore")
        p("in Malesia", "Asia/Kuala_Lumpur", "malesia"); c("Asia/Kuala_Lumpur", "Kuala Lumpur")
        p("in Indonesia, a Giacarta", "Asia/Jakarta", "indonesia"); c("Asia/Jakarta", "Giacarta"); c("Asia/Makassar", "Bali")
        p("nelle Filippine", "Asia/Manila", "filippine"); c("Asia/Manila", "Manila")
        p("in Cina", "Asia/Shanghai", "cina"); c("Asia/Shanghai", "Pechino", "Shanghai"); c("Asia/Hong_Kong", "Hong Kong")
        p("a Taiwan", "Asia/Taipei", "taiwan"); c("Asia/Taipei", "Taipei")
        p("in Giappone", "Asia/Tokyo", "giappone"); c("Asia/Tokyo", "Tokyo", "Osaka", "Kyoto")
        p("in Corea del Sud", "Asia/Seoul", "corea", "corea del sud"); c("Asia/Seoul", "Seul")
        p("in Australia, a Sydney", "Australia/Sydney", "australia"); c("Australia/Sydney", "Sydney"); c("Australia/Melbourne", "Melbourne")
        c("Australia/Brisbane", "Brisbane"); c("Australia/Perth", "Perth")
        p("in Nuova Zelanda", "Pacific/Auckland", "nuova zelanda"); c("Pacific/Auckland", "Auckland")
        p("alle Figi", "Pacific/Fiji", "figi", "fiji"); p("a Tahiti", "Pacific/Tahiti", "tahiti", "polinesia")
        m
    }

    private fun deaccent(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun place(s0: String): Place? {
        var s = deaccent(s0.trim().lowercase())
        s = s.replace(Regex("\\s+(?:adesso|ora|in questo momento|di preciso|esattamente)$"), "").trim()
        PLACES[s]?.let { return it }
        s = s.replace(Regex("^(?:il|lo|la|i|gli|le)\\s+|^l'"), "").trim()
        return PLACES[s]
    }

    private fun worldClock(t: String, now: LocalDateTime, here: ZoneId): String? {
        val asked = CLOCK_Q.find(t)?.groupValues?.get(1) ?: CLOCK_Q2.find(t)?.groupValues?.get(1)
        val tz = if (asked == null) TZ_Q.find(t)?.groupValues?.get(1) else null
        val name = (asked ?: tz ?: return null).trim()
        if (deaccent(name) in NOT_PLACES || name.isEmpty()) return null
        val p = place(name) ?: return "Non so in che fuso orario è $name: chiedimelo con la città o la nazione, per esempio: che ore sono a New York?"
        val zone = ZoneId.of(p.zone)
        val hereZ = ZonedDateTime.of(now, here)
        val there = hereZ.withZoneSameInstant(zone)
        val diff = (there.offset.totalSeconds - hereZ.offset.totalSeconds) / 60
        val where = p.where.replaceFirstChar { it.uppercase() }
        val time = clock12(there.hour, there.minute)
        if (diff == 0) return if (tz != null) "$where è la stessa ora che c'è qui." else "$where è la stessa ora di qui: ${clock24(there.hour, there.minute)}."
        val rel = minutesText(abs(diff)) + if (diff > 0) " avanti" else " indietro"
        if (tz != null) return "$where l'orologio è $rel rispetto a qui: adesso lì ${time}."
        val shift = ChronoUnit.DAYS.between(hereZ.toLocalDate(), there.toLocalDate())
        val day = when {
            shift > 0 -> "è già domani, ${GIORNI[there.dayOfWeek.value - 1]}: "
            shift < 0 -> "è ancora ieri, ${GIORNI[there.dayOfWeek.value - 1]}: "
            else -> ""
        }
        return "$where $day$time, $rel rispetto a qui."
    }

    /** 60 -> "un'ora", 330 -> "5 ore e mezza", 345 -> "5 ore e 45 minuti", 30 -> "mezz'ora". */
    private fun minutesText(m: Int): String {
        val h = m / 60; val r = m % 60
        val hs = when (h) { 0 -> ""; 1 -> "un'ora"; else -> "$h ore" }
        return when {
            h == 0 && r == 30 -> "mezz'ora"
            h == 0 -> "$r minuti"
            r == 0 -> hs
            r == 30 -> "$hs e mezza"
            else -> "$hs e $r minuti"
        }
    }

    private fun minutesPart(m: Int) = when (m) { 0 -> ""; 15 -> " e un quarto"; 30 -> " e mezza"; else -> " e $m" }

    /** L'ora detta come si dice: "sono le 3 e un quarto del pomeriggio", "è l'una di notte", "è mezzogiorno e 10". */
    fun clock12(h: Int, m: Int): String = when (h) {
        0 -> "è mezzanotte${minutesPart(m)}"
        12 -> "è mezzogiorno${minutesPart(m)}"
        1, 13 -> "è l'una${minutesPart(m)} ${partOfDay(h)}"
        else -> "sono le ${if (h > 12) h - 12 else h}${minutesPart(m)} ${partOfDay(h)}"
    }

    /** L'ora di qui, con le 24 ore: "sono le 14 e 5", "è l'una e un quarto", "è mezzanotte in punto". */
    fun clock24(h: Int, m: Int): String = when (h) {
        0 -> "è mezzanotte" + (if (m == 0) " in punto" else minutesPart(m))
        1 -> "è l'una" + (if (m == 0) " in punto" else minutesPart(m))
        else -> "sono le $h" + (if (m == 0) " in punto" else minutesPart(m))
    }

    /** "Che ore sono?": la risposta intera. */
    fun timeNow(h: Int, m: Int): String = clock24(h, m).replaceFirstChar { it.uppercase() } + "."

    private fun partOfDay(h: Int) = when (h) {
        in 0..5 -> "di notte"
        in 6..11 -> "di mattina"
        in 12..17 -> "del pomeriggio"
        else -> "di sera"
    }

    // ================================================================== date e ricorrenze
    private val MONTHS_RX = MESI.joinToString("|")
    private const val WEEKDAYS_RX = "luned[iì]|marted[iì]|mercoled[iì]|gioved[iì]|venerd[iì]|sabato|domenica"

    /** Pasqua (algoritmo gregoriano anonimo, Meeus/Jones/Butcher). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }

    // termini periodici di Meeus (Astronomical Algorithms, tabella 27.C)
    private val SA = doubleArrayOf(485.0, 203.0, 199.0, 182.0, 156.0, 136.0, 77.0, 74.0, 70.0, 58.0, 52.0, 50.0,
        45.0, 44.0, 29.0, 18.0, 17.0, 16.0, 14.0, 12.0, 12.0, 12.0, 9.0, 8.0)
    private val SB = doubleArrayOf(324.96, 337.23, 342.08, 27.85, 73.14, 171.52, 222.54, 296.72, 243.58, 119.81, 297.17, 21.02,
        247.54, 325.15, 60.93, 155.12, 288.79, 198.04, 199.76, 95.39, 287.11, 320.81, 227.73, 15.45)
    private val SC = doubleArrayOf(1934.136, 32964.467, 20.186, 445267.112, 45036.886, 22518.443, 65928.934, 3034.906, 9037.513,
        33718.147, 150.678, 2281.226, 29929.562, 31555.956, 4443.417, 67555.328, 4562.452, 62894.029, 31436.921, 14577.848,
        31931.756, 34777.259, 1222.114, 16859.074)

    /** Inizio della stagione astronomica: 0 = primavera, 1 = estate, 2 = autunno, 3 = inverno (Meeus, cap. 27, al minuto). */
    fun seasonStart(y: Int, which: Int, zone: ZoneId = ROME): ZonedDateTime {
        val yy = (y - 2000) / 1000.0
        val jde0 = when (which) {
            0 -> 2451623.80984 + 365242.37404 * yy + 0.05169 * yy * yy - 0.00411 * yy.pow(3) - 0.00057 * yy.pow(4)
            1 -> 2451716.56767 + 365241.62603 * yy + 0.00325 * yy * yy + 0.00888 * yy.pow(3) - 0.00030 * yy.pow(4)
            2 -> 2451810.21715 + 365242.01767 * yy - 0.11575 * yy * yy + 0.00337 * yy.pow(3) + 0.00078 * yy.pow(4)
            else -> 2451900.05952 + 365242.74049 * yy - 0.06223 * yy * yy - 0.00823 * yy.pow(3) + 0.00032 * yy.pow(4)
        }
        val tt = (jde0 - 2451545.0) / 36525
        val w = Math.toRadians(35999.373 * tt - 2.47)
        val dl = 1 + 0.0334 * cos(w) + 0.0007 * cos(2 * w)
        var s = 0.0
        for (i in SA.indices) s += SA[i] * cos(Math.toRadians(SB[i] + SC[i] * tt))
        val jde = jde0 + 0.00001 * s / dl
        // tempo dinamico -> tempo universale: in questi anni circa 69 secondi
        val ms = Math.round((jde - 2440587.5) * 86_400_000.0) - 69_000L
        return Instant.ofEpochMilli(ms).atZone(zone)
    }

    private class Feast(val re: Regex, val label: String, val on: (Int) -> LocalDate)

    private fun f(p: String, label: String, on: (Int) -> LocalDate) = Feast(rx("^(?:$p)$"), label, on)

    private val FEASTS: List<Feast> by lazy { listOf(
        f("(?:la )?vigilia(?: di natale)?", "la Vigilia di Natale") { LocalDate.of(it, 12, 24) },
        f("(?:il )?(?:santo )?natale|(?:il )?giorno di natale", "Natale") { LocalDate.of(it, 12, 25) },
        f("santo stefano", "Santo Stefano") { LocalDate.of(it, 12, 26) },
        f("san silvestro|(?:l')?ultimo (?:giorno )?dell'anno|(?:la )?fine dell'anno|fine anno|(?:la )?notte di capodanno|" +
            "(?:la )?vigilia di capodanno", "San Silvestro") { LocalDate.of(it, 12, 31) },
        f("(?:il )?capodanno|(?:l')?anno nuovo|(?:il )?primo (?:giorno )?dell'anno|(?:il )?nuovo anno", "Capodanno") { LocalDate.of(it, 1, 1) },
        f("(?:la )?befana|(?:l')?epifania", "la Befana") { LocalDate.of(it, 1, 6) },
        f("san valentino|(?:la )?festa (?:di san valentino|degli innamorati)", "San Valentino") { LocalDate.of(it, 2, 14) },
        f("(?:la )?festa della donna", "la festa della donna") { LocalDate.of(it, 3, 8) },
        f("(?:la )?festa del pap[aà]|san giuseppe", "la festa del papà") { LocalDate.of(it, 3, 19) },
        f("(?:la )?(?:festa della )?liberazione", "la festa della Liberazione") { LocalDate.of(it, 4, 25) },
        f("(?:la )?festa (?:del lavoro|dei lavoratori)", "la festa dei lavoratori") { LocalDate.of(it, 5, 1) },
        f("(?:la )?festa della mamma", "la festa della mamma") {
            LocalDate.of(it, 5, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY)) },
        f("(?:la )?festa della repubblica", "la festa della Repubblica") { LocalDate.of(it, 6, 2) },
        f("ferragosto", "Ferragosto") { LocalDate.of(it, 8, 15) },
        f("san gennaro|(?:la )?festa di san gennaro", "San Gennaro") { LocalDate.of(it, 9, 19) },
        f("(?:la )?festa dei nonni", "la festa dei nonni") { LocalDate.of(it, 10, 2) },
        f("halloween", "Halloween") { LocalDate.of(it, 10, 31) },
        f("ognissanti|tutti i santi|(?:la )?festa di tutti i santi", "Ognissanti") { LocalDate.of(it, 11, 1) },
        f("(?:il )?giorno dei morti|(?:la )?commemorazione dei defunti|(?:la )?festa dei morti", "il giorno dei morti") { LocalDate.of(it, 11, 2) },
        f("(?:il )?black friday", "il Black Friday") {
            LocalDate.of(it, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)).plusDays(1) },
        f("(?:l')?immacolata(?: concezione)?", "l'Immacolata") { LocalDate.of(it, 12, 8) },
        // le feste che dipendono dalla Pasqua
        f("(?:la )?pasqua", "Pasqua") { easter(it) },
        f("(?:la )?pasquetta|(?:il )?luned[iì] dell'angelo|(?:il )?luned[iì] di pasqua", "Pasquetta") { easter(it).plusDays(1) },
        f("(?:il )?venerd[iì] santo", "il Venerdì santo") { easter(it).minusDays(2) },
        f("(?:la )?domenica delle palme|(?:le )?palme", "la domenica delle Palme") { easter(it).minusDays(7) },
        f("(?:il )?carnevale|(?:il )?marted[iì] grasso|(?:l')?ultimo giorno di carnevale", "il martedì grasso") { easter(it).minusDays(47) },
        f("(?:il )?gioved[iì] grasso", "il giovedì grasso") { easter(it).minusDays(52) },
        f("(?:il )?mercoled[iì] delle ceneri|(?:le )?ceneri", "il mercoledì delle Ceneri") { easter(it).minusDays(46) },
        f("(?:l')?ascensione", "l'Ascensione") { easter(it).plusDays(42) },
        f("(?:la )?pentecoste", "la Pentecoste") { easter(it).plusDays(49) },
        // le stagioni astronomiche: equinozi e solstizi, calcolati
        f("(?:la )?primavera|(?:l')?inizio della primavera|(?:l')?equinozio di primavera", "la primavera") { seasonStart(it, 0).toLocalDate() },
        f("(?:l')?estate|(?:l')?inizio dell'estate|(?:il )?solstizio(?: d'estate)?", "l'estate") { seasonStart(it, 1).toLocalDate() },
        f("(?:l')?autunno|(?:l')?inizio dell'autunno|(?:l')?equinozio d'autunno|(?:l')?equinozio di autunno", "l'autunno") {
            seasonStart(it, 2).toLocalDate() },
        f("(?:l')?inverno|(?:l')?inizio dell'inverno|(?:il )?solstizio d'inverno", "l'inverno") { seasonStart(it, 3).toLocalDate() }
    ) }

    private enum class Kind { FEAST, DATE, WEEKDAY, MONTH, OTHER }

    /** Il giorno chiesto: [name] come si dice ("Natale", "la Befana", "il 15 agosto"), [onYear] = la data in un dato anno. */
    private class Target(val name: String, val onYear: ((Int) -> LocalDate?)?, val fixed: LocalDate?, val kind: Kind, val month: Int = 0)

    private val DATE_RX = rx("^(?:(?:$WEEKDAYS_RX)\\s+)?(?:il\\s+|l')?(\\d{1,2}|primo)\\s+($MONTHS_RX)$")
    private val WEEKDAY_RX = rx("^($WEEKDAYS_RX)(?:\\s+(?:prossimo|prossima|che viene))?$")
    private val MONTH_RX = rx("^(?:il mese di\\s+|inizio\\s+|l'inizio di\\s+)?($MONTHS_RX)$")

    /** Il giorno detto con le parole del calendario (senza l'anno). null = non è un giorno. */
    private fun target(s0: String, today: LocalDate): Target? {
        val s = s0.trim().replace(Regex("^(?:il giorno di|la data di|il giorno del|il giorno della|la festa di)\\s+(?=\\S)"), "").trim()
        for (fe in FEASTS) if (fe.re.containsMatchIn(s)) return Target(fe.label, fe.on, null, Kind.FEAST)
        DATE_RX.find(s)?.let { m ->
            val day = if (m.groupValues[1] == "primo") 1 else m.groupValues[1].toInt()
            val month = MESI.indexOf(m.groupValues[2].lowercase()) + 1
            if (day < 1 || day > 31) return null
            return Target(dayName(day, month), { y -> try { LocalDate.of(y, month, day) } catch (e: Exception) { null } }, null, Kind.DATE)
        }
        WEEKDAY_RX.find(s)?.let { m ->
            val dow = GIORNI.indexOfFirst { deaccent(it) == deaccent(m.groupValues[1].lowercase()) } + 1
            var d = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)))
            if (rx("\\b(?:prossimo|prossima)\\b").containsMatchIn(s) && d == today) d = d.plusWeeks(1)
            return Target(GIORNI[dow - 1], null, d, Kind.WEEKDAY)
        }
        MONTH_RX.find(s)?.let { m ->
            val month = MESI.indexOf(m.groupValues[1].lowercase()) + 1
            return Target("il primo ${MESI[month - 1]}", { y -> LocalDate.of(y, month, 1) }, null, Kind.MONTH, month)
        }
        if (rx("^(?:la\\s+)?fine (?:del|di questo) mese$|^fine mese$").containsMatchIn(s))
            return Target("la fine del mese", null, today.with(TemporalAdjusters.lastDayOfMonth()), Kind.OTHER)
        if (rx("^(?:il\\s+)?(?:fine settimana|weekend|week end)$").containsMatchIn(s)) {
            val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            return Target("il fine settimana", null, if (today.dayOfWeek == DayOfWeek.SUNDAY) today else sat, Kind.OTHER)
        }
        return null
    }

    /** "il 25 dicembre", "l'8 settembre", "il primo maggio". */
    private fun dayName(day: Int, month: Int) =
        (if (day == 8 || day == 11) "l'$day" else if (day == 1) "il primo" else "il $day") + " " + MESI[month - 1]

    /** "venerdì 25 dicembre" (con l'anno se non è [year]). */
    fun dateText(d: LocalDate, year: Int? = null): String =
        "${GIORNI[d.dayOfWeek.value - 1]} ${if (d.dayOfMonth == 1) "primo" else d.dayOfMonth} ${MESI[d.monthValue - 1]}" +
            if (year != null && d.year == year) "" else " ${d.year}"

    /** La parte finale con l'anno: "pasqua del 2027", "natale dell'anno prossimo". (testo senza anno, anno o null) */
    private fun splitYear(s: String, today: LocalDate): Pair<String, Int?> {
        Regex("^(.+?)\\s+(?:(?:del|nel|dell'anno|nell'anno|per il|di)\\s+)?(\\d{4})$").find(s)?.let { return it.groupValues[1] to it.groupValues[2].toInt() }
        Regex("^(.+?)\\s+(?:di\\s+)?quest'anno$").find(s)?.let { return it.groupValues[1] to today.year }
        Regex("^(.+?)\\s+(?:dell'anno prossimo|l'anno prossimo|del prossimo anno|il prossimo anno|dell'anno che viene)$").find(s)
            ?.let { return it.groupValues[1] to today.year + 1 }
        Regex("^(.+?)\\s+(?:dell'anno scorso|l'anno scorso|dello scorso anno|lo scorso anno)$").find(s)?.let { return it.groupValues[1] to today.year - 1 }
        return s to null
    }

    /** La data del giorno chiesto: nell'anno detto, oppure la prossima volta (o l'ultima, se [past]). */
    private fun resolve(tg: Target, year: Int?, today: LocalDate, past: Boolean): LocalDate? {
        tg.fixed?.let { return it }
        val on = tg.onYear ?: return null
        if (year != null) return on(year)
        val thisYear = on(today.year) ?: on(today.year + 1) ?: return null
        return if (past) { if (!thisYear.isAfter(today)) thisYear else on(today.year - 1) }
        else { if (!thisYear.isBefore(today)) thisYear else on(today.year + 1) }
    }

    private val PREP_A = mapOf("il " to "al ", "lo " to "allo ", "la " to "alla ", "l'" to "all'", "i " to "ai ", "gli " to "agli ", "le " to "alle ")
    private val PREP_DA = mapOf("il " to "dal ", "lo " to "dallo ", "la " to "dalla ", "l'" to "dall'", "i " to "dai ", "gli " to "dagli ", "le " to "dalle ")
    private fun withPrep(name: String, map: Map<String, String>, bare: String): String {
        for ((art, p) in map) if (name.startsWith(art)) return p + name.removePrefix(art)
        return "$bare $name"
    }
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private const val PREPS_A = "(?:(?:ad|a|alla|allo|alle|al|ai|agli|per il|per la|per|prima della|prima del|prima di)\\s+|all'|per l'|prima dell')"
    private val UNTIL = rx("^(?:(?:ma|e|allora|senti|dimmi)\\s+)?(?:quanti giorni|quante settimane|quanti mesi|quanto tempo|quanto)\\s+" +
        "(?:mancano|manca|ci vogliono|ci vuole|restano|rimangono|ci separano da|ci separa da)\\s+(?:ancora\\s+)?$PREPS_A(.+)$")
    private val UNTIL2 = rx("^(?:tra|fra|entro)\\s+quant[io](?:\\s+giorni)?\\s+(?:è|e|sarà|sara|arriva|c'è|cade|viene)\\s+(.+)$")
    private val UNTIL3 = rx("^(.+?)\\s+(?:tra|fra)\\s+quant[io](?:\\s+giorni)?\\s+(?:è|e|sarà|sara|arriva|c'è|cade|viene)$|" +
        "^(?:$PREPS_A)?(.+?)\\s+quanto (?:tempo )?manca$")
    private const val WHEN_VERB = "(?:è|e|cade|sarà|sara|viene|c'è|ci sarà|capita|cadrà|inizia|comincia|arriva|era|fu|è stato|e stato|cadeva|cadde)"
    private val WHEN = rx("^(?:(?:ma|e|allora|senti|dimmi)\\s+)?(?:quando|in che giorno|che giorno|in che data|che data|di che giorno|in quale giorno|quale giorno)" +
        "(?:\\s+della settimana)?\\s+$WHEN_VERB\\s+(.+)$")
    private val WHEN2 = rx("^(.+?)\\s+(?:quando|in che giorno|che giorno(?: della settimana)?|in che data|che data)\\s+$WHEN_VERB$")
    private val PAST_VERB = rx("\\b(?:era|fu|è stato|e stato|cadeva|cadde)\\b")
    private const val UNIT_T = "(giorni|giorno|settimane|settimana|mesi|mese|anni|anno)"
    private const val QTY = "(\\d+|un|una|uno|sei)"
    private val REL = rx("^(?:che giorno|che data|quale giorno|in che giorno)\\s+(?:sarà|sara|è|e|cade)\\s+(?:tra|fra)\\s+$QTY\\s+$UNIT_T$|" +
        "^(?:tra|fra)\\s+$QTY\\s+$UNIT_T\\s+(?:che giorno|che data|quale giorno)\\s+(?:sarà|sara|è|e)$")
    private val AGO = rx("^(?:che giorno|che data|quale giorno)\\s+(?:era|è|e|fu)\\s+$QTY\\s+$UNIT_T\\s+fa$|^$QTY\\s+$UNIT_T\\s+fa\\s+(?:che giorno|che data)\\s+(?:era|è|e)$")
    private val BETWEEN = rx("^(?:quanti giorni|quante settimane)\\s+(?:ci sono|passano|ci corrono|corrono|ci sono di differenza|di differenza ci sono|ci separano)?\\s*" +
        "(?:tra\\s+|fra\\s+|dal\\s+|dall'|dalla\\s+|da\\s+)(.+?)(?:\\s+e\\s+|\\s+al\\s+|\\s+all'|\\s+alla\\s+|\\s+a\\s+)(.+)$")
    private val SINCE = rx("^(?:quanti giorni|quante settimane|quanto tempo)\\s+(?:sono passati|è passato|e passato|sono trascorsi|è trascorso|ci sono stati)\\s+" +
        "(?:(?:dal|dalla|dallo|da)\\s+|dall')(.+)$")
    private val AGE_Q = rx("\\b(?:quanti anni|che età|che eta)\\b")
    private val BORN = rx("\\bnat[oaie]\\s+(?:nel\\s+|il\\s+|l')?(.+?)(?:\\s+(?:quanti anni|che età|che eta)\\b.*)?$")
    private val LEAP = rx("^(?:ma\\s+)?(?:il\\s+)?(\\d{4}|quest'anno|l'anno prossimo|l'anno scorso)\\s+(?:è|e|sarà|era|fu)\\s+(?:un anno\\s+)?bisestile$|" +
        "^(?:è|e)\\s+bisestile\\s+(?:il\\s+)?(\\d{4}|quest'anno|l'anno prossimo)$")
    private val NEXT_LEAP = rx("^(?:quando|qual)\\s+(?:è|e|sarà|c'è|cade)\\s+il prossimo (?:anno )?bisestile$|^il prossimo (?:anno )?bisestile (?:quando è|qual è)$")
    private val MONTH_DAYS = rx("^quanti giorni (?:ha|ci sono a|ci sono in|ha il mese di|dura)\\s+(?:il mese di\\s+)?($MONTHS_RX)(?:\\s+(?:del\\s+|nel\\s+|di\\s+)?(\\d{4}|quest'anno))?$")
    private val YEAR_Q = rx("^(?:in\\s+)?(?:che|quale) anno (?:è|e|siamo)(?: oggi| adesso)?$")
    private val MONTH_Q = rx("^(?:in\\s+)?(?:che|quale) mese (?:è|e|siamo)(?: oggi| adesso)?$")
    private val WEEK_Q = rx("^(?:in\\s+)?(?:che|quale) settimana (?:è|e|siamo)(?: dell'anno)?$|^(?:che|quale) numero di settimana (?:è|e|siamo)$")
    private val DOY_Q = rx("^(?:che|quale) giorno dell'anno (?:è|e)(?: oggi)?$|^oggi (?:che|quale) giorno dell'anno è$")

    private fun calendar(t: String, today: LocalDate): String? {
        if (YEAR_Q.containsMatchIn(t)) return "Siamo nel ${today.year}."
        if (MONTH_Q.containsMatchIn(t)) return "Siamo a ${MESI[today.monthValue - 1]}."
        if (WEEK_Q.containsMatchIn(t)) return "È la settimana numero ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} dell'anno."
        if (DOY_Q.containsMatchIn(t)) return "Oggi è il giorno numero ${today.dayOfYear} dell'anno: ne mancano ${today.lengthOfYear() - today.dayOfYear} alla fine."

        // bisestile
        LEAP.find(t)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val y = when (w) { "quest'anno" -> today.year; "l'anno prossimo" -> today.year + 1; "l'anno scorso" -> today.year - 1; else -> w.toInt() }
            val leap = java.time.Year.isLeap(y.toLong())
            val verb = if (y < today.year) "era" else if (y == today.year) "è" else "sarà"
            return if (leap) "Sì, il $y $verb bisestile: febbraio ha 29 giorni."
            else "No, il $y non $verb bisestile. Il prossimo anno bisestile è il ${nextLeap(y)}."
        }
        if (NEXT_LEAP.containsMatchIn(t)) {
            val y = if (java.time.Year.isLeap(today.year.toLong()) && today.monthValue <= 2) today.year else nextLeap(today.year)
            return "Il prossimo anno bisestile è il $y."
        }
        MONTH_DAYS.find(t)?.let { m ->
            val month = MESI.indexOf(m.groupValues[1].lowercase()) + 1
            val y = m.groupValues[2].let { if (it.isEmpty() || it == "quest'anno") today.year else it.toInt() }
            val n = java.time.YearMonth.of(y, month).lengthOfMonth()
            return if (month == 2) "Febbraio ${if (y == today.year) "quest'anno" else "nel $y"} ha $n giorni." else "${cap(MESI[month - 1])} ha $n giorni."
        }

        // tra N giorni / N giorni fa
        REL.find(t)?.let { m ->
            val g = m.groupValues
            val (q, u) = if (g[1].isNotEmpty()) g[1] to g[2] else g[3] to g[4]
            val n = qty(q) ?: return null
            val d = shift(today, n, u)
            return "Tra ${qtyText(n, u)} sarà ${dateText(d, today.year)}."
        }
        AGO.find(t)?.let { m ->
            val g = m.groupValues
            val (q, u) = if (g[1].isNotEmpty()) g[1] to g[2] else g[3] to g[4]
            val n = qty(q) ?: return null
            val d = shift(today, -n, u)
            return "${cap(qtyText(n, u))} fa era ${dateText(d, today.year)}."
        }

        // quanti giorni tra due date
        BETWEEN.find(t)?.let { m ->
            val (a0, ya) = splitYear(m.groupValues[1].trim(), today)
            val (b0, yb) = splitYear(m.groupValues[2].trim(), today)
            val ta = target(a0, today) ?: return null
            val tb = target(b0, today) ?: return null
            if (ta.fixed != null || tb.fixed != null) return null
            val onA = ta.onYear ?: return null
            val onB = tb.onYear ?: return null
            val y1 = ya ?: today.year
            val da = onA(y1) ?: return null
            var db = onB(yb ?: y1) ?: return null
            if (yb == null && db.isBefore(da)) db = onB(y1 + 1) ?: return null
            val n = abs(ChronoUnit.DAYS.between(da, db))
            val weeks = rx("^quante settimane").containsMatchIn(t)
            val tail = if (weeks) ", cioè ${weeksText(n)}" else ""
            return "${cap(withPrep(ta.name, PREP_DA, "da"))} ${withPrep(tb.name, PREP_A, "a")} ci sono $n giorn${if (n == 1L) "o" else "i"}$tail."
        }
        SINCE.find(t)?.let { m ->
            val (s0, y) = splitYear(m.groupValues[1].trim(), today)
            val tg = target(s0, today) ?: return null
            val d = resolve(tg, y, today, past = true) ?: return null
            val n = ChronoUnit.DAYS.between(d, today)
            if (n < 0) return "${cap(tg.name)} ${dateText(d, today.year)} deve ancora arrivare: mancano ${-n} giorni."
            val nm = tg.name + (if (tg.kind == Kind.DATE && d.year != today.year) " ${d.year}" else "")
            val whenTxt = if (tg.kind == Kind.DATE) "" else ", ${dateText(d, today.year)},"
            return "${cap(withPrep(nm, PREP_DA, "da"))}$whenTxt sono passati $n giorn${if (n == 1L) "o" else "i"}."
        }

        // quanti anni ha chi è nato…
        if (AGE_Q.containsMatchIn(t)) BORN.find(t)?.let { m ->
            val w = m.groupValues[1].trim()
            if (Regex("^\\d{4}$").matches(w)) {
                val y = w.toInt()
                if (y > today.year) return "Il $y deve ancora arrivare."
                val n = today.year - y
                return if (n == 0) "Chi è nato nel $y quest'anno non ha ancora compiuto un anno."
                else "Chi è nato nel $y quest'anno compie $n anni: ne ha $n se il compleanno è già passato, altrimenti ${n - 1}."
            }
            val (s0, y) = splitYear(w, today)
            if (y == null) return null
            val tg = target(s0, today) ?: return null
            val birth = tg.onYear?.invoke(y) ?: return null
            if (birth.isAfter(today)) return "Quella data deve ancora arrivare."
            val age = Period.between(birth, today).years
            val bd = birth.withYear(today.year)
            return if (bd == today) "Chi è nato ${tg.name} $y compie $age anni proprio oggi!"
            else "Chi è nato ${tg.name} $y ha $age ann${if (age == 1) "o" else "i"}."
        }

        // quanto manca a… / quando è…
        val until = UNTIL.find(t)?.groupValues?.get(1)
            ?: UNTIL2.find(t)?.groupValues?.get(1)
            ?: UNTIL3.find(t)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (until != null) {
            val (s0, y) = splitYear(until.trim(), today)
            val tg = target(s0, today) ?: return null
            if (tg.month > 0 && tg.month == today.monthValue && y == null) return "Siamo già a ${MESI[tg.month - 1]}!"
            val d = resolve(tg, y, today, past = false) ?: return null
            return countdown(tg, d, today, weeks = rx("^(?:(?:ma|e|allora|senti|dimmi)\\s+)?quante settimane").containsMatchIn(t),
                months = rx("^(?:(?:ma|e|allora|senti|dimmi)\\s+)?quanti mesi").containsMatchIn(t))
        }
        val whenM = WHEN.find(t)?.groupValues?.get(1) ?: WHEN2.find(t)?.groupValues?.get(1)
        if (whenM != null) {
            val past = PAST_VERB.containsMatchIn(t)
            val (s0, y) = splitYear(whenM.trim(), today)
            if (rx("^(?:oggi|domani|dopodomani|ieri|l'altro ieri|stamattina|stasera)$").containsMatchIn(s0)) return null
            val tg = target(s0, today) ?: return null
            val d = resolve(tg, y, today, past) ?: return null
            return whenAnswer(tg, d, today)
        }
        return null
    }

    private fun nextLeap(y: Int): Int { var n = y + 1; while (!java.time.Year.isLeap(n.toLong())) n++; return n }

    private fun qty(q: String): Long? = when (q) { "un", "una", "uno" -> 1L; "sei" -> 6L; else -> q.toLongOrNull() }

    private fun shift(d: LocalDate, n: Long, u: String): LocalDate = when {
        u.startsWith("giorn") -> d.plusDays(n)
        u.startsWith("settiman") -> d.plusWeeks(n)
        u.startsWith("mes") -> d.plusMonths(n)
        else -> d.plusYears(n)
    }

    private fun qtyText(n: Long, u: String): String {
        val (one, many) = when {
            u.startsWith("giorn") -> "un giorno" to "giorni"
            u.startsWith("settiman") -> "una settimana" to "settimane"
            u.startsWith("mes") -> "un mese" to "mesi"
            else -> "un anno" to "anni"
        }
        return if (n == 1L) one else "$n $many"
    }

    private fun weeksText(n: Long): String {
        val w = n / 7; val d = n % 7
        val ws = if (w == 1L) "una settimana" else "$w settimane"
        return when {
            w == 0L -> "meno di una settimana"
            d == 0L -> ws
            else -> "$ws e ${if (d == 1L) "un giorno" else "$d giorni"}"
        }
    }

    private fun countdown(tg: Target, d: LocalDate, today: LocalDate, weeks: Boolean, months: Boolean): String {
        val n = ChronoUnit.DAYS.between(today, d)
        val wd = GIORNI[d.dayOfWeek.value - 1]
        // "il 15 agosto 2027" se non è quest'anno
        val name = if (tg.kind == Kind.DATE && d.year != today.year) "${tg.name} ${d.year}" else tg.name
        val toName = if (tg.kind == Kind.WEEKDAY) "a $name ${d.dayOfMonth} ${MESI[d.monthValue - 1]}" else withPrep(name, PREP_A, "a")
        val tail = when (tg.kind) {
            Kind.WEEKDAY -> "."
            Kind.DATE -> ", che cade di $wd."
            else -> ": ${dateText(d, today.year)}."
        }
        return when {
            n == 0L -> "${cap(name)} è oggi!"
            n == 1L -> "Manca un giorno: ${tg.name} è domani" + (if (tg.kind == Kind.WEEKDAY) "." else ", ${dateText(d, today.year)}.")
            n < 0 -> "${cap(name)} è già passat${if (name.startsWith("la ") || name == "Pasqua" || name == "Pasquetta") "a" else "o"}: " +
                "era ${dateText(d, today.year)}."
            weeks -> "Mancano ${weeksText(n)} $toName, cioè $n giorni$tail"
            months -> {
                val p = Period.between(today, d)
                val mm = p.years * 12 + p.months
                val ms = when (mm) { 0 -> "meno di un mese"; 1 -> "un mese"; else -> "$mm mesi" } +
                    (if (mm > 0 && p.days > 0) " e ${if (p.days == 1) "un giorno" else "${p.days} giorni"}" else "")
                "Mancano $ms $toName, cioè $n giorni$tail"
            }
            else -> "Mancano $n giorni $toName$tail"
        }
    }

    private fun whenAnswer(tg: Target, d: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, d)
        val wd = GIORNI[d.dayOfWeek.value - 1]
        if (tg.kind == Kind.DATE) {
            val nm = cap(tg.name) + (if (d.year != today.year) " ${d.year}" else "")
            return when {
                n == 0L -> "$nm è oggi, $wd."
                n == 1L -> "$nm è domani, $wd."
                n < 0 -> "$nm era $wd."
                else -> "$nm ${if (d.year == today.year) "quest'anno " else ""}sarà $wd."
            }
        }
        if (tg.kind == Kind.WEEKDAY) return "${cap(tg.name)} ${if (n == 0L) "è oggi, " else "è "}il ${d.dayOfMonth} ${MESI[d.monthValue - 1]}."
        val name = cap(tg.name)
        return when {
            n == 0L -> "$name è oggi, ${dateText(d, today.year)}!"
            n == 1L -> "$name è domani, ${dateText(d, today.year)}."
            n < 0 -> "$name ${if (d.year == today.year) "quest'anno " else ""}era ${dateText(d, today.year)}."
            else -> "$name ${if (d.year == today.year) "quest'anno " else ""}sarà ${dateText(d, today.year)}."
        }
    }

    // ================================================================== IVA, sconti, percentuali, conti divisi
    private const val AMT = "(\\d+(?:[.,]\\d+)?(?:\\s+virgola\\s+\\d+)?(?:\\s+(?:euro|€))?(?:\\s+e\\s+\\d{1,2}(?:\\s+centesimi)?)?|un euro)"
    private const val PCT = "(\\d+(?:[.,]\\d+)?(?:\\s+virgola\\s+\\d+)?)\\s*(?:%|per ?cento)"
    private const val RATE = "(?:\\s+(?:al|del)\\s+(\\d+(?:[.,]\\d+)?)(?:\\s*(?:%|per ?cento))?)?"
    private val VAT_ADD = rx("^(?:quanto (?:fa|è|e|viene|costa|sono)\\s+)?$AMT\\s+(?:più|piu|\\+|con)\\s+(?:l'|la\\s+)?iva$RATE(?:\\s+quanto (?:fa|è|viene))?$|" +
        "^aggiungi\\s+(?:l')?iva$RATE\\s+(?:a|ad)\\s+$AMT$")
    private val VAT_OFF = rx("^(?:quanto (?:fa|è|e|viene|sono)\\s+)?$AMT(?:\\s+iva compresa|\\s+iva inclusa|\\s+ivato)?\\s+(?:senza|meno)\\s+(?:l'|la\\s+)?iva$RATE$|" +
        "^(?:scorpora|scorporami|togli|toglimi|leva)\\s+(?:l')?iva$RATE\\s+(?:da|a|dal|al)\\s+$AMT$|^scorporo(?:\\s+(?:dell')?iva)?$RATE\\s+(?:di|da|su)\\s+$AMT$")
    private val VAT_OF = rx("^(?:quanto (?:è|e|fa|viene)\\s+)?(?:l')?iva$RATE\\s+(?:su|di|del|dello|della|per)\\s+$AMT$")
    private val DISCOUNT = rx("^(?:quanto (?:fa|è|e|viene|pago|costa|sono)\\s+)?(?:(?:lo|uno)\\s+)?sconto\\s+(?:del\\s+)?$PCT\\s+(?:su|di|a|sul|sullo|sulla)\\s+$AMT$|" +
        "^(?:quanto (?:fa|è|e|viene|pago|costa)\\s+)?$AMT\\s+(?:scontato|scontati|con lo sconto|con uno sconto|meno lo sconto|meno)\\s+(?:del\\s+|il\\s+)?$PCT$")
    private val PLUS_PCT = rx("^(?:quanto (?:fa|è|e|viene)\\s+)?$AMT\\s+(?:più|piu|\\+)\\s+(?:il\\s+)?$PCT$|^(?:aumenta|aggiungi(?:\\s+il)?)\\s+$PCT\\s+(?:a|ad)\\s+$AMT$")
    private val RATIO = rx("^(?:che percentuale è|che percentuale e|quanto è in percentuale|quanto fa in percentuale|in percentuale quanto è)\\s+(\\d+(?:[.,]\\d+)?)\\s+(?:su|di|rispetto a)\\s+(\\d+(?:[.,]\\d+)?)$|" +
        "^(\\d+(?:[.,]\\d+)?)\\s+(?:su|di)\\s+(\\d+(?:[.,]\\d+)?)\\s+(?:che percentuale è|che percentuale e|quanto è in percentuale|in percentuale)$")
    private val SPLIT = rx("^(?:dividi|dividimi|dividere|fai la divisione di)\\s+$AMT\\s+(?:per|tra|fra|in)\\s+(\\d+|sei)(?:\\s+(?:persone|amici|parti|teste))?$|" +
        "^$AMT\\s+diviso\\s+(\\d+|sei)\\s+(?:persone|amici|teste)(?:\\s+quanto (?:fa|è|viene))?$")

    /** "100", "49,90", "49 virgola 90", "49 euro e 90" -> euro. */
    private fun euros(s0: String): Double? {
        val s = s0.trim()
        if (s == "un euro") return 1.0
        val m = Regex("^(\\d+(?:[.,]\\d+)?(?:\\s+virgola\\s+\\d+)?)(?:\\s+(?:euro|€))?(?:\\s+e\\s+(\\d{1,2})(?:\\s+centesimi)?)?$").find(s) ?: return null
        val base = parseNum(m.groupValues[1]) ?: return null
        val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { if (it.length == 1) it.toDouble() * 10 else it.toDouble() } ?: 0.0
        return base + cents / 100.0
    }

    private fun eur(x: Double): String {
        val r = Math.round(x * 100) / 100.0
        return (if (abs(r - Math.rint(r)) < 1e-9) Math.round(r).toString() else String.format(Locale.ROOT, "%.2f", r).replace('.', ',')) + " euro"
    }

    private fun money(t: String): String? {
        val tt = t.replace(Regex("(\\d)\\s*€"), "$1 euro")
        VAT_ADD.find(tt)?.let { m ->
            val g = m.groupValues
            val a = euros(g[1].ifEmpty { g[4] }) ?: return null
            val r = (g[2].ifEmpty { g[3] }).let { if (it.isEmpty()) IVA else valueOf(it) ?: IVA }
            val vat = a * r / 100
            return "${eur(a)} più IVA al ${say(r)}% fanno ${eur(a + vat)}: l'IVA è ${eur(vat)}."
        }
        VAT_OFF.find(tt)?.let { m ->
            val g = m.groupValues
            val a = euros(listOf(g[1], g[4], g[6]).first { it.isNotEmpty() }) ?: return null
            val r = listOf(g[2], g[3], g[5]).firstOrNull { it.isNotEmpty() }?.let { valueOf(it) } ?: IVA
            val net = a / (1 + r / 100)
            return "${eur(a)} IVA compresa sono ${eur(net)} senza IVA: l'IVA al ${say(r)}% è ${eur(a - net)}."
        }
        VAT_OF.find(tt)?.let { m ->
            val a = euros(m.groupValues[2]) ?: return null
            val r = m.groupValues[1].let { if (it.isEmpty()) IVA else valueOf(it) ?: IVA }
            return "L'IVA al ${say(r)}% su ${eur(a)} è ${eur(a * r / 100)}: in tutto ${eur(a * (1 + r / 100))}."
        }
        DISCOUNT.find(tt)?.let { m ->
            val g = m.groupValues
            val (p, a) = if (g[1].isNotEmpty()) valueOf(g[1]) to euros(g[2]) else valueOf(g[4]) to euros(g[3])
            if (p == null || a == null || p > 100) return null
            val off = a * p / 100
            return "Con lo sconto del ${say(p)}% su ${eur(a)} paghi ${eur(a - off)}: risparmi ${eur(off)}."
        }
        PLUS_PCT.find(tt)?.let { m ->
            val g = m.groupValues
            val (a, p) = if (g[1].isNotEmpty()) euros(g[1]) to valueOf(g[2]) else euros(g[4]) to valueOf(g[3])
            if (a == null || p == null) return null
            return "${say(a)} più il ${say(p)}% fa ${say(a * (1 + p / 100))}."
        }
        RATIO.find(tt)?.let { m ->
            val g = m.groupValues
            val (x, y) = if (g[1].isNotEmpty()) valueOf(g[1]) to valueOf(g[2]) else valueOf(g[3]) to valueOf(g[4])
            if (x == null || y == null || y == 0.0) return null
            return "${say(x)} su ${say(y)} è il ${say(x / y * 100, 1)}%."
        }
        SPLIT.find(tt)?.let { m ->
            val g = m.groupValues
            val a = euros(g[1].ifEmpty { g[3] }) ?: return null
            val n = (g[2].ifEmpty { g[4] }).let { if (it == "sei") 6 else it.toInt() }
            if (n <= 0) return null
            val each = a / n
            val exact = abs(Math.round(each * 100) / 100.0 - each) < 1e-9
            return "${eur(a)} divisi per $n fanno ${if (exact) "" else "circa "}${eur(each)} a testa."
        }
        return null
    }

    // ================================================================== conversioni
    private class U(val dim: Char, val f: Double, val one: String, val many: String, val fem: Boolean = false)

    private val UNIT_LIST: List<Pair<List<String>, U>> = listOf(
        listOf("millimetro", "millimetri", "mm") to U('L', 0.001, "millimetro", "millimetri"),
        listOf("centimetro", "centimetri", "cm") to U('L', 0.01, "centimetro", "centimetri"),
        listOf("decimetro", "decimetri") to U('L', 0.1, "decimetro", "decimetri"),
        listOf("metro", "metri") to U('L', 1.0, "metro", "metri"),
        listOf("chilometro", "chilometri", "kilometro", "kilometri", "km") to U('L', 1000.0, "chilometro", "chilometri"),
        listOf("pollice", "pollici") to U('L', 0.0254, "pollice", "pollici"),
        listOf("piede", "piedi") to U('L', 0.3048, "piede", "piedi"),
        listOf("iarda", "iarde", "yard") to U('L', 0.9144, "iarda", "iarde", fem = true),
        listOf("miglio nautico", "miglia nautiche", "miglio marino", "miglia marine") to U('L', 1852.0, "miglio nautico", "miglia nautiche"),
        listOf("miglio", "miglia") to U('L', 1609.344, "miglio", "miglia"),
        listOf("milligrammo", "milligrammi") to U('M', 1e-6, "milligrammo", "milligrammi"),
        listOf("grammo", "grammi") to U('M', 0.001, "grammo", "grammi"),
        listOf("etto", "etti", "ettogrammo", "ettogrammi") to U('M', 0.1, "etto", "etti"),
        listOf("chilo", "chili", "chilogrammo", "chilogrammi", "kilo", "kili", "kg") to U('M', 1.0, "chilo", "chili"),
        listOf("quintale", "quintali") to U('M', 100.0, "quintale", "quintali"),
        listOf("tonnellata", "tonnellate") to U('M', 1000.0, "tonnellata", "tonnellate", fem = true),
        listOf("libbra", "libbre") to U('M', 0.45359237, "libbra", "libbre", fem = true),
        listOf("oncia", "once") to U('M', 0.028349523125, "oncia", "once", fem = true),
        listOf("carato", "carati") to U('M', 0.0002, "carato", "carati"),
        listOf("millilitro", "millilitri", "ml") to U('V', 0.001, "millilitro", "millilitri"),
        listOf("centilitro", "centilitri", "cl") to U('V', 0.01, "centilitro", "centilitri"),
        listOf("decilitro", "decilitri") to U('V', 0.1, "decilitro", "decilitri"),
        listOf("litro", "litri") to U('V', 1.0, "litro", "litri"),
        listOf("ettolitro", "ettolitri") to U('V', 100.0, "ettolitro", "ettolitri"),
        listOf("metro cubo", "metri cubi") to U('V', 1000.0, "metro cubo", "metri cubi"),
        listOf("gallone", "galloni") to U('V', 3.785411784, "gallone", "galloni"),
        listOf("cucchiaio", "cucchiai") to U('V', 0.015, "cucchiaio", "cucchiai"),
        listOf("cucchiaino", "cucchiaini") to U('V', 0.005, "cucchiaino", "cucchiaini"),
        listOf("chilometro orario", "chilometri orari", "chilometri all'ora", "chilometri l'ora", "chilometri al ora", "km orari", "km all'ora",
            "km/h", "kmh") to U('S', 1 / 3.6, "chilometro orario", "chilometri orari"),
        listOf("miglio orario", "miglia orarie", "miglia all'ora", "miglia l'ora", "mph") to U('S', 0.44704, "miglio orario", "miglia orarie"),
        listOf("nodo", "nodi") to U('S', 1852.0 / 3600, "nodo", "nodi"),
        listOf("metro al secondo", "metri al secondo") to U('S', 1.0, "metro al secondo", "metri al secondo"),
        listOf("metro quadro", "metri quadri", "metro quadrato", "metri quadrati", "mq") to U('A', 1.0, "metro quadro", "metri quadri"),
        listOf("chilometro quadrato", "chilometri quadrati", "chilometro quadro", "chilometri quadri") to U('A', 1e6, "chilometro quadrato", "chilometri quadrati"),
        listOf("ettaro", "ettari") to U('A', 1e4, "ettaro", "ettari"),
        listOf("acro", "acri") to U('A', 4046.8564224, "acro", "acri"),
        listOf("piede quadrato", "piedi quadrati", "piede quadro", "piedi quadri") to U('A', 0.09290304, "piede quadrato", "piedi quadrati"),
        listOf("secondo", "secondi") to U('T', 1.0, "secondo", "secondi"),
        listOf("minuto", "minuti") to U('T', 60.0, "minuto", "minuti"),
        listOf("ora", "ore") to U('T', 3600.0, "ora", "ore", fem = true),
        listOf("giorno", "giorni") to U('T', 86400.0, "giorno", "giorni"),
        listOf("settimana", "settimane") to U('T', 604800.0, "settimana", "settimane", fem = true),
        listOf("anno", "anni") to U('T', 365 * 86400.0, "anno", "anni"),
        // temperature (conversione a parte)
        listOf("grado centigrado", "gradi centigradi", "grado celsius", "gradi celsius", "celsius", "centigradi", "gradi") to
            U('C', 0.0, "grado centigrado", "gradi centigradi"),
        listOf("grado fahrenheit", "gradi fahrenheit", "fahrenheit") to U('F', 0.0, "grado Fahrenheit", "gradi Fahrenheit"),
        listOf("kelvin", "gradi kelvin") to U('K', 0.0, "kelvin", "kelvin")
    )
    private val UNIT_KEYS = UNIT_LIST.flatMap { (ks, u) -> ks.map { it to u } }.sortedByDescending { it.first.length }
    private val UNIT_MAP = UNIT_KEYS.toMap()
    private val UNIT_RX = UNIT_KEYS.joinToString("|") { it.first }
    private const val NUMQ = "(\\d+(?:[.,]\\d+)?(?:\\s+virgola\\s+\\d+)?(?:\\s+e\\s+mezz[oa])?|un|uno|una|mezzo|mezza|sei)"

    private val CONV1 = rx("^(?:(?:converti|convertimi|trasforma|trasformami|calcola|passa)\\s+)?$NUMQ\\s+($UNIT_RX)\\s+" +
        "(?:in|a|quanti|quante|in quanti|in quante|sono quanti|sono quante|fanno quanti|fanno quante|corrispondono a quanti|corrispondono a quante|" +
        "equivalgono a quanti|equivalgono a quante)\\s+($UNIT_RX)(?:\\s+(?:sono|fanno|è|e|corrispondono|equivalgono|vale|valgono))?$")
    private val CONV2 = rx("^(?:a\\s+|in\\s+)?(?:quanti|quante)\\s+($UNIT_RX)\\s+(?:sono|fanno|ci sono in|ci stanno in|corrispondono a|equivalgono a|è|e|" +
        "vale|valgono|pesa|pesano|misura|misurano|ha|hanno|c'è in|ci sono dentro)\\s+$NUMQ\\s+($UNIT_RX)$")
    private val CONV3 = rx("^(?:quanto|quanti|quante)\\s+(?:è|e|sono|fa|fanno|vale|valgono|misura|misurano|pesa|pesano)\\s+$NUMQ\\s+($UNIT_RX)\\s+(?:in|a)\\s+($UNIT_RX)$")

    private fun temp(x: Double, from: Char, to: Char): Double {
        val c = when (from) { 'F' -> (x - 32) * 5 / 9; 'K' -> x - 273.15; else -> x }
        return when (to) { 'F' -> c * 9 / 5 + 32; 'K' -> c + 273.15; else -> c }
    }

    private fun isTemp(u: U) = u.dim == 'C' || u.dim == 'F' || u.dim == 'K'

    /** "un'oncia", "un litro", "una libbra", "un acro". */
    private fun oneOf(u: U): String = when {
        u.fem && u.one.first() in "aeiou" && u.one != "iarda" -> "un'${u.one}"
        u.fem -> "una ${u.one}"
        else -> "un ${u.one}"
    }

    private fun convert(t: String): String? {
        var s = t.replace(Regex("\\bun'"), "un ").replace(Regex("\\buna\\s+'"), "una ")
        // "un chilo e mezzo in libbre" -> "1.5 chilo in libbre"
        s = Regex("\\b(\\d+(?:[.,]\\d+)?|un|uno|una)\\s+($UNIT_RX)\\s+e\\s+mezz[oa]\\b").replace(s) {
            "${(parseNum(it.groupValues[1].replace(',', '.')) ?: 0.0) + 0.5} ${it.groupValues[2]}"
        }
        var n: String; var a: String; var b: String
        val m1 = CONV1.find(s)
        val m2 = if (m1 == null) CONV2.find(s) else null
        val m3 = if (m1 == null && m2 == null) CONV3.find(s) else null
        when {
            m1 != null -> { n = m1.groupValues[1]; a = m1.groupValues[2]; b = m1.groupValues[3] }
            m2 != null -> { b = m2.groupValues[1]; n = m2.groupValues[2]; a = m2.groupValues[3] }
            m3 != null -> { n = m3.groupValues[1]; a = m3.groupValues[2]; b = m3.groupValues[3] }
            else -> return null
        }
        val x = parseNum(n.replace(',', '.')) ?: return null
        var ua = UNIT_MAP[a.lowercase()] ?: return null
        var ub = UNIT_MAP[b.lowercase()] ?: return null
        // "gradi" da soli sono centigradi: "30 gradi in fahrenheit", "quanti gradi sono 100 fahrenheit"
        if (isTemp(ua) && isTemp(ub)) {
            if (ua.dim == ub.dim) return null
            val r = temp(x, ua.dim, ub.dim)
            val rs = say(r, 1)
            val approx = abs((rs.replace(',', '.').toDoubleOrNull() ?: r) - r) > 1e-9
            val src = "${say(x, 2)} ${if (x == 1.0) ua.one else ua.many}"
            return "${cap(src)} ${if (x == 1.0) "è" else "sono"} ${if (approx) "circa " else ""}$rs ${if (rs == "1") ub.one else ub.many}."
        }
        if (ua.dim != ub.dim || ua === ub) return null
        val r = x * ua.f / ub.f
        val rs = sayNice(r)
        val approx = abs((rs.replace(',', '.').toDoubleOrNull() ?: r) - r) > 1e-9 * maxOf(1.0, abs(r))
        val src = if (x == 1.0) oneOf(ua) else "${say(x, 4)} ${ua.many}"
        val dst = "${if (approx) "circa " else ""}$rs ${if (rs == "1") ub.one else ub.many}"
        return "${cap(src)} ${if (x == 1.0) "è" else "sono"} $dst."
    }

    // ================================================================== valute: il cambio cambia ogni giorno
    private val CURRENCY = rx("\\b(?:euro|dollar[oi]|sterlin[ae]|franc[oi] svizzer[oi]|franchi|yen|yuan|renminbi|rubl[oi]|bitcoin|ethereum|" +
        "lir[ae] turc[ah]e?|rupi[ae]|real|pesos?|zloty|corone?)\\b")
    private val FX_HINT = rx("\\b(?:cambio|quanto vale|quanto valgono|quanti|quante|in euro|in dollari|convert|valuta|valute|quotazione|prezzo)\\b")

    /** "Quanti dollari sono 100 euro?", "il cambio euro dollaro": il cambio cambia ogni giorno, senza internet non si sa. */
    fun isCurrency(t0: String): Boolean {
        val t = clean(t0)
        val n = CURRENCY.findAll(t).map { it.value.lowercase().take(5) }.distinct().count()
        return (n >= 2 && FX_HINT.containsMatchIn(t)) ||
            (n >= 1 && rx("\\b(?:cambio|quotazione|quanto vale (?:un |il |l')?(?:dollaro|bitcoin|ethereum|euro|sterlina|yen))\\b").containsMatchIn(t))
    }
}
