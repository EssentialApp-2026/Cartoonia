package com.essentialapp.sam

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 2.0: SAM si gestisce da solo. Qui, uguale per telefono e PC e senza niente di Android o di Windows:
 *  - le frasi ("controllati", "riparati", "cosa è andato storto?", "fai una copia", "ripristina la copia di ieri"…);
 *  - il registro di quello che va storto ([Journal]);
 *  - le copie di sicurezza di ricordi, mente, conversazione e impostazioni ([Backups]);
 *  - il controllo di salute ([Health]) e la guardia contro gli avvii che finiscono male ([CrashGuard]).
 * Telefono e PC ci mettono i fatti (spazio, permessi, modelli) e le riparazioni.
 */
object CareCommands {
    private const val FILL = "(?:(?:sam|ok|okay|ehi|allora|senti|ascolta|dai|ora|adesso|e|poi|per favore|per piacere)[,\\s]+)*"
    private fun rx(p: String) = Lang.rx("^$FILL(?:$p)[\\s?!.]*$")

    private val CHECK = rx("controllati|controlla te stesso|fatti un controllo|fai un controllo(?: completo| generale| di te)?|fai (?:la |una |l')?(?:auto)?diagnosi|" +
        "(?:auto)?diagnosi|stato del sistema|come stai messo|funziona tutto|funzioni bene|va tutto bene (?:con te|da te)|sei a posto|" +
        "hai (?:dei |qualche )?problem[ai]|controlla (?:se )?(?:funziona tutto|è tutto a posto|che sia tutto a posto)")
    private val REPAIR = rx("riparati|aggiustati|sistemati|ripara(?: tutto| i problemi| gli errori)?|risolvi(?: tutto| i problemi)|aggiusta(?: tutto| i problemi)|" +
        "sistema(?: tutto| i problemi)|rimettiti a posto")
    private val LOG = rx("(?:che )?cos(?:a è|'è) andato storto|cosa (?:non ha|non è) (?:funzionato|andato)|che (?:errori|problemi) (?:hai|ci sono) (?:avuto|avuti|stati)|" +
        "(?:mostrami|fammi vedere|leggi|leggimi|apri) (?:il )?registro(?: degli errori)?|registro(?: degli errori)?|" +
        "perché ti sei (?:chiuso|bloccato|fermato|spento)|ci sono stati (?:errori|problemi)")
    private val BACKUP = rx("fai (?:subito |adesso |ora )?(?:una |la |il )?(?:copia(?: di sicurezza| di tutto)?|backup|salvataggio)(?: adesso| ora| subito)?|" +
        "salva (?:una copia di )?tutto|metti (?:tutto )?al sicuro(?: i ricordi)?")
    private val BACKUPS = rx("(?:che|quali|quante) copie (?:hai|ci sono|hai fatto)|(?:elenca|mostrami|fammi vedere) le copie(?: di sicurezza)?|" +
        "quando hai fatto l'ultima copia|a quando risale l'ultima copia|le copie(?: di sicurezza)?")
    private val RESTORE_VERB = Lang.rx("^$FILL(?:ripristina|recupera|rimetti|riprendi|torna|carica)\\b")
    private val RESTORE_ARG = Lang.rx("\\b(?:copia|backup|salvataggio)(?: di sicurezza)?(?:\\s+(?:di|del|dello|della|dell'))?\\s*(.*)$")
    private val CLEAN = rx("fai (?:un po' di )?pulizia|pulisciti|ripulisciti|libera(?: un po' di)? spazio|cancella i (?:file )?temporanei|svuota la cache")
    private val SPACE = rx("quanto spazio (?:occupi|usi|prendi|ti serve)|quanto (?:pesi|occupi)|quanto spazio (?:libero )?(?:c'è|ho|hai|resta|rimane|è rimasto)" +
        "(?: sul (?:telefono|computer|pc|disco))?")
    private val VERSION = rx("(?:che|quale) versione (?:sei|hai|è)|a che versione sei|dimmi la (?:tua )?versione|versione")
    private val NEWS = rx("(?:che )?cos(?:a c'è|'è) di nuovo|(?:quali sono )?le novità|novità|cosa hai di nuovo|cosa sai fare di nuovo|cosa è cambiato")

    // 2.0.2: riprendere il lavoro interrotto. Le forme corte («continua», «vai avanti») valgono solo se c'è un lavoro in sospeso
    private val RESUME = rx("(?:continua|riprendi|prosegui|finisci|vai avanti con|riparti)\\s+(?:il lavoro|col lavoro|con il lavoro|il lavoro di prima|da dove (?:eri|ti eri|sei) (?:rimasto|arrivato|fermato|interrotto)|" +
        "(?:da )?dove (?:eravamo|eri) rimast[oi]|quello che stavi (?:facendo|scrivendo)|a scrivere|a lavorare|il programma|il codice|il documento|la scrittura)|" +
        "riprendi da dove (?:eri|ti eri) (?:rimasto|fermato|interrotto)|ricomincia da dove (?:eri|ti eri) (?:rimasto|fermato|interrotto)")
    private val RESUME_SOFT = rx("continua|vai avanti|prosegui|riprendi|vai pure avanti|continua pure|riprova")
    private val DOING = rx("(?:che )?cos(?:a |')stavi (?:facendo|scrivendo)|a che punto (?:eri|sei)(?: rimasto| arrivato)?|dove (?:eri|eravamo) rimast[oi]|" +
        "(?:hai|avevi) (?:un |qualche )?lavor[oi] in sospeso|cosa avevi in sospeso|ti ricordi cosa stavi facendo")

    /** null = la frase non riguarda la cura di sé. */
    fun parse(t: String): Outcome.Care? {
        if (RESUME.containsMatchIn(t)) return Outcome.Care("resume")
        if (RESUME_SOFT.containsMatchIn(t)) return Outcome.Care("resume", "soft")
        if (DOING.containsMatchIn(t)) return Outcome.Care("doing")
        if (CHECK.containsMatchIn(t)) return Outcome.Care("check")
        if (REPAIR.containsMatchIn(t)) return Outcome.Care("repair")
        if (LOG.containsMatchIn(t)) return Outcome.Care("log")
        if (BACKUPS.containsMatchIn(t)) return Outcome.Care("backups")
        if (BACKUP.containsMatchIn(t)) return Outcome.Care("backup")
        if (RESTORE_VERB.containsMatchIn(t)) RESTORE_ARG.find(t)?.let { m ->
            val arg = m.groupValues[1].trim().trim('?', '.', '!').trim().removePrefix("l'").trim()
            // «carica la copia del file»: non è una copia di sicurezza (di quelle si dice solo il giorno)
            if (arg.isEmpty() || Backups.knows(arg)) return Outcome.Care("restore", arg)
        }
        if (CLEAN.containsMatchIn(t)) return Outcome.Care("clean")
        if (SPACE.containsMatchIn(t)) return Outcome.Care("space")
        if (VERSION.containsMatchIn(t)) return Outcome.Care("version")
        if (NEWS.containsMatchIn(t)) return Outcome.Care("news")
        return null
    }
}

/** Cosa c'è di nuovo, da dire e da mostrare in «Informazioni». */
object About {
    const val PC_VERSION = "2.0.3"
    val NEWS = listOf(
        "Posso usare anche i cervelli online con i tuoi account, come Groq e Gemini: quando uno finisce i token passo da solo al successivo, e se finiscono tutti rispondo col cervello del telefono. Si impostano in Configurazione, alla voce Cervelli online",
        "I file che scrivo adesso sono completi: se non è finito continuo da solo finché arriva in fondo",
        "Programmi e documenti li salvo in HTML, in un file unico che si apre nel browser",
        "Se vengo fermato a metà mi ricordo cosa stavo facendo: di' «continua» e riprendo da lì",
        "Mentre scrivo un programma o un documento non chiedo più «ci sei ancora?» e non mi interrompo",
        "Mi controllo da solo: di' «controllati» e ti dico cosa funziona e cosa no",
        "Mi riparo da solo: di' «riparati», e ogni notte faccio da me pulizia e controllo",
        "Ogni giorno metto al sicuro ricordi, mente e impostazioni: «ripristina la copia di ieri»",
        "Tengo un registro di quello che va storto: «cosa è andato storto?»",
        "Grafica nuova, impostazioni in ordine e la pagina Informazioni",
        "Sul computer mi installo come un vero programma di Windows",
    )
    fun newsSpoken(version: String) = "Sono alla versione $version. Le novità: " + NEWS.joinToString(". ") + "."
    fun newsMarkdown(version: String) = "## Versione $version\n" + NEWS.joinToString("\n") { "- $it" }
}

/**
 * Il registro: una riga per ogni cosa andata storta (o riparata). Formato: millisecondi, livello, dove, testo (con i tab).
 * Livelli: E errore, C chiusura improvvisa, W avviso, I cosa ho fatto io (riparazioni, copie).
 */
class Journal(private val file: File, private val max: Int = 400, private val zone: ZoneId = ZoneId.systemDefault()) {
    class Entry(val at: Long, val level: String, val where: String, val text: String) {
        val bad get() = level == "E" || level == "C"
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(300)

    @Synchronized fun add(level: String, where: String, text: String, now: Long = System.currentTimeMillis()) {
        try {
            val all = entries().toMutableList()
            val w = clean(where); val x = clean(text)
            // la stessa cosa ripetuta di fila (un microfono che non parte, per esempio) non riempie il registro
            val last = all.lastOrNull()
            if (last != null && last.level == level && last.where == w && last.text == x && now - last.at < 10 * 60_000L) return
            all.add(Entry(now, level, w, x))
            write(all.takeLast(max))
        } catch (e: Throwable) {}
    }

    fun error(where: String, e: Throwable, now: Long = System.currentTimeMillis()) = add("E", where, describe(e), now)
    fun crash(where: String, e: Throwable, now: Long = System.currentTimeMillis()) = add("C", where, describe(e) + trace(e), now)
    fun did(text: String, now: Long = System.currentTimeMillis()) = add("I", "cura", text, now)

    private fun describe(e: Throwable) = (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    private fun trace(e: Throwable): String = try {
        e.stackTrace.firstOrNull { it.className.startsWith("com.essentialapp.sam") }?.let { " (in ${it.fileName ?: it.className}:${it.lineNumber})" } ?: ""
    } catch (t: Throwable) { "" }

    private fun write(all: List<Entry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(all.joinToString("") { "${it.at}\t${it.level}\t${it.where}\t${it.text}\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    }

    @Synchronized fun entries(): List<Entry> = try {
        if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull { l ->
            val p = l.split('\t')
            val at = p.getOrNull(0)?.toLongOrNull()
            if (p.size < 4 || at == null) null else Entry(at, p[1], p[2], p[3])
        }
    } catch (e: Throwable) { emptyList() }

    fun bad(since: Long): List<Entry> = entries().filter { it.bad && it.at >= since }
    fun lastCrash(since: Long): Entry? = entries().lastOrNull { it.level == "C" && it.at >= since }

    private fun whenText(at: Long, now: Long): String {
        val d = Instant.ofEpochMilli(at).atZone(zone); val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val hm = "%d:%02d".format(d.hour, d.minute)
        return when (d.toLocalDate()) {
            today -> "oggi alle $hm"
            today.minusDays(1) -> "ieri alle $hm"
            else -> "il ${d.dayOfMonth} ${Backups.MONTHS[d.monthValue - 1]} alle $hm"
        }
    }

    /** «Cosa è andato storto?»: gli ultimi sette giorni, in parole. */
    fun summary(now: Long = System.currentTimeMillis()): String {
        val week = bad(now - 7 * 86_400_000L)
        if (week.isEmpty()) return "Negli ultimi sette giorni non è andato storto niente."
        val groups = week.groupBy { it.where + "|" + it.text }.values.sortedByDescending { it.last().at }
        val n = groups.size
        val head = if (n == 1) "Negli ultimi sette giorni c'è stato un solo problema." else "Negli ultimi sette giorni ci sono stati $n problemi diversi."
        val body = groups.take(3).joinToString(" ") { g ->
            val e = g.last()
            val times = if (g.size > 1) ", ${g.size} volte, l'ultima ${whenText(e.at, now)}" else ", ${whenText(e.at, now)}"
            (if (e.level == "C") "Mi sono chiuso da solo" else e.where.replaceFirstChar { it.uppercase() }) + times + ": " + e.text.trimEnd('.') + "."
        }
        return head + " " + body + (if (n > 3) " Gli altri li vedi nel registro." else "") + " Se vuoi che provi a sistemare, di': riparati."
    }

    /** Il registro da leggere sullo schermo (dal più recente). */
    fun markdown(n: Int = 40, now: Long = System.currentTimeMillis()): String {
        val all = entries().takeLast(n).reversed()
        if (all.isEmpty()) return "Il registro è vuoto: non è andato storto niente."
        return all.joinToString("\n") { e ->
            val mark = when (e.level) { "C" -> "**Chiusura improvvisa**"; "E" -> "**Errore**"; "W" -> "Avviso"; else -> "Fatto" }
            "- ${whenText(e.at, now)} · $mark · ${e.where}: ${e.text}"
        }
    }

    @Synchronized fun clear() { try { write(emptyList()) } catch (e: Throwable) {} }
}

/**
 * Le copie di sicurezza: uno zip al giorno con i file piccoli di SAM (ricordi, mente, conversazione, impostazioni).
 * Nomi: copia_2026-11-10_0314_auto.zip. Si tengono le copie degli ultimi 7 giorni, poi una a settimana per cinque settimane,
 * e le ultime 5 fatte a mano. Prima di ripristinare si fa sempre una copia di com'è adesso.
 */
class Backups(private val dir: File, private val sources: () -> List<File>, private val zone: ZoneId = ZoneId.systemDefault()) {
    class Info(val file: File, val at: Long, val kind: String) {
        val bytes get() = file.length()
    }

    companion object {
        val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
        private val NAME = Regex("^copia_(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})(\\d{2})_([a-z]+)\\.zip$")
        const val AUTO = "auto"; const val MANUAL = "mano"; const val BEFORE = "prima"
        private val DAY_MONTH = Lang.rx("\\b(\\d{1,2})(?:°)?\\s+(?:di\\s+)?(" + MONTHS.joinToString("|") + ")\\b")
        private val DAYS_AGO = Lang.rx("\\b(\\d{1,2})\\s+giorni fa\\b")
        private val WORDS = Lang.rx("\\b(?:ieri|altro ieri|altroieri|oggi|stamattina|stanotte|settimana scorsa|prima|ultima|più recente)\\b")
        /** La frase dice un giorno che [pick] capisce. */
        fun knows(spec: String) = DAY_MONTH.containsMatchIn(spec) || DAYS_AGO.containsMatchIn(spec) || WORDS.containsMatchIn(spec)
    }

    private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone)

    fun list(): List<Info> = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
        val m = NAME.find(f.name) ?: return@mapNotNull null
        val (y, mo, d, h, mi, kind) = m.destructured
        val at = try { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(h.toInt(), mi.toInt()).atZone(zone).toInstant().toEpochMilli() } catch (e: Exception) { return@mapNotNull null }
        Info(f, at, kind)
    }.sortedByDescending { it.at }

    fun last(): Info? = list().firstOrNull()

    /** Oggi la copia automatica non c'è ancora. */
    fun due(now: Long): Boolean { val today = date(now).toLocalDate(); return list().none { it.kind == AUTO && date(it.at).toLocalDate() == today } }

    /** Fa la copia adesso. null se non c'è niente da copiare o non ci riesce. */
    @Synchronized fun make(now: Long = System.currentTimeMillis(), kind: String = AUTO): Info? {
        val files = sources().filter { it.isFile && it.length() > 0 }
        if (files.isEmpty()) return null
        dir.mkdirs()
        val d = date(now)
        val name = "copia_%04d-%02d-%02d_%02d%02d_%s.zip".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, kind)
        val out = File(dir, name); val tmp = File(dir, "$name.tmp")
        return try {
            ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                for (f in files) { z.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() }); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
            if (!verify(tmp)) { tmp.delete(); return null }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.delete(); return null }
            prune(now)
            Info(out, now, kind)
        } catch (e: Throwable) { tmp.delete(); null }
    }

    /** Lo zip si apre e ogni file dentro si legge fino in fondo (lo zip controlla da sé che non sia rovinato). */
    fun verify(f: File): Boolean = try {
        ZipFile(f).use { z ->
            val es = z.entries().toList()
            val buf = ByteArray(8192)
            for (e in es) z.getInputStream(e).use { s -> while (s.read(buf) >= 0) { } }
            es.isNotEmpty()
        }
    } catch (e: Throwable) { false }

    /** Tiene: gli ultimi 7 giorni, poi la più recente di ogni settimana per 4 settimane; le ultime 5 a mano e le ultime 3 «prima del ripristino». */
    fun prune(now: Long) {
        try {
            val all = list()
            val keep = HashSet<File>()
            all.filter { it.kind == MANUAL }.take(5).forEach { keep.add(it.file) }
            all.filter { it.kind == BEFORE }.take(3).forEach { keep.add(it.file) }
            val auto = all.filter { it.kind == AUTO }
            val today = date(now).toLocalDate()
            // una per giorno negli ultimi 7 giorni
            auto.groupBy { date(it.at).toLocalDate() }.forEach { (day, l) -> if (!day.isBefore(today.minusDays(6))) keep.add(l.first().file) }
            // poi una per settimana (la prima di ogni settimana: è sempre la stessa, non cambia da un giorno all'altro) per 5 settimane
            auto.filter { !date(it.at).toLocalDate().isBefore(today.minusDays(35)) }
                .groupBy { val d = date(it.at).toLocalDate(); d.minusDays((d.dayOfWeek.value - 1).toLong()) }.values.forEach { keep.add(it.last().file) }
            if (auto.isNotEmpty()) keep.add(auto.first().file)
            all.filter { it.file !in keep }.forEach { it.file.delete() }
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        } catch (e: Throwable) {}
    }

    /** «di ieri», «dell'altro ieri», «di oggi», «del 3 novembre», «di 3 giorni fa», «» o «ultima» = la più recente. */
    fun pick(spec: String, now: Long): Info? {
        val all = list().filter { it.kind != BEFORE || spec.contains("prima") }
        val s = spec.lowercase().trim()
        val today = date(now).toLocalDate()
        fun ofDay(d: LocalDate) = all.firstOrNull { date(it.at).toLocalDate() == d }
        DAY_MONTH.find(s)?.let { m ->
            val day = m.groupValues[1].toInt(); val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            var d = try { LocalDate.of(today.year, month, day) } catch (e: Exception) { return null }
            if (d.isAfter(today)) d = d.minusYears(1)
            return ofDay(d)
        }
        DAYS_AGO.find(s)?.let { return ofDay(today.minusDays(it.groupValues[1].toLong())) }
        return when {
            s.contains("altro ieri") || s.contains("altroieri") -> ofDay(today.minusDays(2))
            s.contains("ieri") -> ofDay(today.minusDays(1))
            s.contains("oggi") || s.contains("stamattina") || s.contains("stanotte") -> ofDay(today)
            s.contains("settimana scorsa") -> all.firstOrNull { !date(it.at).toLocalDate().isAfter(today.minusDays(7)) }
            s.contains("prima") -> all.firstOrNull { it.kind == BEFORE }
            s.isEmpty() || s.contains("ultima") || s.contains("recente") -> all.firstOrNull()
            else -> null
        }
    }

    fun whenText(i: Info, now: Long): String {
        val d = date(i.at); val today = date(now).toLocalDate()
        val hm = "%d:%02d".format(d.hour, d.minute)
        return when (d.toLocalDate()) {
            today -> "di oggi alle $hm"
            today.minusDays(1) -> "di ieri alle $hm"
            else -> "del ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} alle $hm"
        }
    }

    /**
     * Rimette i file della copia in [into]. Prima fa una copia di com'è adesso (si può tornare indietro:
     * «ripristina la copia di prima»). Ritorna i nomi dei file rimessi; vuoto = la copia è rovinata e non ho toccato niente.
     */
    @Synchronized fun restore(i: Info, into: File, now: Long = System.currentTimeMillis()): List<String> {
        if (!verify(i.file)) return emptyList()
        make(now, BEFORE)
        val done = ArrayList<String>()
        try {
            ZipFile(i.file).use { z ->
                for (e in z.entries()) {
                    val name = File(e.name).name            // mai fuori dalla cartella
                    if (e.isDirectory || name.isEmpty()) continue
                    val tmp = File(into, "$name.ripristino")
                    z.getInputStream(e).use { s -> tmp.outputStream().use { s.copyTo(it) } }
                    val dst = File(into, name)
                    if (dst.exists()) dst.delete()
                    if (tmp.renameTo(dst)) done.add(name) else tmp.delete()
                }
            }
        } catch (t: Throwable) {}
        return done
    }

    /** Per lo schermo: l'elenco delle copie. */
    fun markdown(now: Long): String {
        val all = list()
        if (all.isEmpty()) return "Nessuna copia ancora: la prima la faccio stanotte, oppure di' «fai una copia»."
        return all.joinToString("\n") { i ->
            val k = when (i.kind) { MANUAL -> "fatta da te"; BEFORE -> "prima di un ripristino"; else -> "automatica" }
            "- Copia ${whenText(i, now)} · $k · ${Health.size(i.bytes)}"
        }
    }

    fun spoken(now: Long): String {
        val all = list()
        val l = all.firstOrNull() ?: return "Non ho ancora nessuna copia. La faccio da solo ogni giorno; se la vuoi adesso di': fai una copia."
        return "Ho ${if (all.size == 1) "una copia" else "${all.size} copie"} di ricordi, mente e impostazioni. L'ultima è ${whenText(l, now)}. " +
            "Per tornare indietro di': ripristina la copia di ieri."
    }
}

/** Il controllo di salute: una riga per ogni cosa guardata. */
object Health {
    enum class Level { OK, WARN, FAIL }

    /** [fix] = cosa posso fare io («riparati») o cosa devi fare tu; [auto] = lo sistemo da solo. */
    class Check(val id: String, val title: String, val level: Level, val detail: String, val fix: String? = null, val auto: Boolean = false)

    fun worst(c: List<Check>): Level = when {
        c.any { it.level == Level.FAIL } -> Level.FAIL
        c.any { it.level == Level.WARN } -> Level.WARN
        else -> Level.OK
    }

    fun size(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9).replace('.', ',')
        b >= 1_000_000L -> "${b / 1_000_000L} MB"
        b >= 1000L -> "${b / 1000L} KB"
        else -> "$b byte"
    }

    /** In una riga, per l'intestazione. */
    fun headline(c: List<Check>): String {
        val bad = c.count { it.level == Level.FAIL }; val warn = c.count { it.level == Level.WARN }
        return when {
            bad > 0 -> if (bad == 1) "1 problema" else "$bad problemi"
            warn > 0 -> if (warn == 1) "1 cosa da guardare" else "$warn cose da guardare"
            else -> "Tutto a posto"
        }
    }

    /** A voce: prima i problemi, poi gli avvisi; se è tutto a posto, una frase sola. */
    fun spoken(c: List<Check>): String {
        val bad = c.filter { it.level == Level.FAIL }; val warn = c.filter { it.level == Level.WARN }
        if (bad.isEmpty() && warn.isEmpty()) return "Ho controllato ${c.size} cose ed è tutto a posto."
        val sb = StringBuilder("Ho controllato ${c.size} cose. ")
        if (bad.isNotEmpty()) sb.append(if (bad.size == 1) "C'è un problema: " else "Ci sono ${bad.size} problemi: ")
            .append(bad.joinToString(" ") { it.detail.trimEnd('.') + "." + (it.fix?.let { f -> " " + f.trimEnd('.') + "." } ?: "") }).append(' ')
        if (warn.isNotEmpty()) sb.append(if (bad.isEmpty()) (if (warn.size == 1) "Una cosa da guardare: " else "${warn.size} cose da guardare: ") else "E poi: ")
            .append(warn.take(3).joinToString(" ") { it.detail.trimEnd('.') + "." + (it.fix?.let { f -> " " + f.trimEnd('.') + "." } ?: "") }).append(' ')
        if ((bad + warn).any { it.auto }) sb.append("Quello che posso lo sistemo io: di' riparati.")
        else sb.append("Il resto funziona.")
        return sb.toString().trim()
    }

    fun markdown(c: List<Check>): String = "## Stato: ${headline(c)}\n" + c.sortedBy { -it.level.ordinal }.joinToString("\n") { k ->
        val mark = when (k.level) { Level.OK -> "✓"; Level.WARN -> "!"; Level.FAIL -> "✗" }
        "- **$mark ${k.title}** · ${k.detail}" + (if (k.level != Level.OK && k.fix != null) " *${k.fix}*" else "")
    }

    // ---- i controlli uguali su telefono e PC -----------------------------------------------------------------

    /** Lo spazio libero: sotto 1 GB è un problema (le copie e i file non si salvano), sotto 4 GB un avviso. */
    fun storage(free: Long, where: String): Check = when {
        free in 0 until 1_000_000_000L -> Check("spazio", "Spazio", Level.FAIL, "Sul $where restano solo ${size(free)} liberi", "Faccio pulizia dei miei file temporanei, ma serve liberare altro spazio", auto = true)
        free in 0 until 4_000_000_000L -> Check("spazio", "Spazio", Level.WARN, "Sul $where restano ${size(free)} liberi: pochi", "Posso fare pulizia dei miei file temporanei", auto = true)
        else -> Check("spazio", "Spazio", Level.OK, "${size(free)} liberi")
    }

    /**
     * Un file di cervello: c'è, non è vuoto, comincia come deve (i GGUF con «GGUF») e, se si sa quanto deve pesare,
     * non è rimasto a metà. [expected] <= 0 = non si sa.
     */
    fun brainFile(f: File?, name: String, expected: Long = -1L): Check {
        if (f == null || !f.isFile) return Check("cervello", "Cervello", Level.WARN, "Non c'è un cervello: eseguo i comandi ma non converso", "Scaricalo dalle impostazioni")
        val problem = modelProblem(f, expected)
        return if (problem != null) Check("cervello", "Cervello", Level.FAIL, "Il file del cervello $name $problem", "Va scaricato di nuovo: lo faccio io se dici riparati", auto = true)
        else Check("cervello", "Cervello", Level.OK, "$name, ${size(f.length())}")
    }

    /** null = il file sembra buono. */
    fun modelProblem(f: File, expected: Long = -1L): String? {
        val len = f.length()
        if (len < 1_000_000L) return "è vuoto o quasi"
        if (f.name.endsWith(".gguf", ignoreCase = true)) {
            val head = ByteArray(4)
            val ok = try { f.inputStream().use { it.read(head) == 4 } && String(head, Charsets.ISO_8859_1) == "GGUF" } catch (e: Throwable) { false }
            if (!ok) return "non è un file GGUF valido"
        }
        if (expected > 0 && len < expected * 97 / 100) return "è rimasto a metà (${size(len)} su ${size(expected)})"
        return null
    }

    /** Il file dei ricordi: si legge, e le righe sono fatte come devono (4 campi separati da tab). */
    fun memoryFile(f: File): Check {
        if (!f.exists()) return Check("ricordi", "Ricordi", Level.OK, "Ancora nessun ricordo")
        return try {
            val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val bad = lines.count { it.split('\t').size < 4 }
            when {
                bad == 0 -> Check("ricordi", "Ricordi", Level.OK, if (lines.size == 1) "1 ricordo, il file è integro" else "${lines.size} ricordi, il file è integro")
                bad * 2 > lines.size -> Check("ricordi", "Ricordi", Level.FAIL, "Il file dei ricordi è rovinato ($bad righe su ${lines.size})", "Di': ripristina l'ultima copia")
                else -> Check("ricordi", "Ricordi", Level.WARN, "$bad righe dei ricordi sono rovinate (le altre ${lines.size - bad} sono a posto)", "Le tolgo io se dici riparati", auto = true)
            }
        } catch (e: Throwable) { Check("ricordi", "Ricordi", Level.FAIL, "Non riesco a leggere il file dei ricordi", "Di': ripristina l'ultima copia") }
    }

    /** Toglie dal file dei ricordi le righe rovinate (le buone restano come sono). Ritorna quante ne ha tolte. */
    fun fixMemoryFile(f: File): Int = try {
        val lines = f.readLines(Charsets.UTF_8)
        val good = lines.filter { it.isBlank() || it.split('\t').size >= 4 }
        val n = lines.size - good.size
        if (n > 0) { val tmp = File(f.path + ".tmp"); tmp.writeText(good.joinToString("") { it + "\n" }, Charsets.UTF_8); if (!tmp.renameTo(f)) { f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() } }
        n
    } catch (e: Throwable) { 0 }

    fun backups(b: Backups, now: Long): Check {
        val l = b.last() ?: return Check("copie", "Copie di sicurezza", Level.WARN, "Non c'è ancora nessuna copia di sicurezza", "La faccio io adesso se dici riparati", auto = true)
        val days = (now - l.at) / 86_400_000L
        return if (days >= 3) Check("copie", "Copie di sicurezza", Level.WARN, "L'ultima copia di sicurezza è di $days giorni fa", "Ne faccio una adesso se dici riparati", auto = true)
        else Check("copie", "Copie di sicurezza", Level.OK, "L'ultima è ${b.whenText(l, now)}")
    }

    fun journal(j: Journal, now: Long): Check {
        val day = j.bad(now - 86_400_000L)
        val crashes = day.count { it.level == "C" }
        return when {
            crashes >= 2 -> Check("registro", "Registro", Level.FAIL, "Nelle ultime 24 ore mi sono chiuso da solo $crashes volte", "Di': cosa è andato storto")
            day.size >= 1 -> Check("registro", "Registro", Level.WARN, if (day.size == 1) "Nelle ultime 24 ore c'è stato un errore" else "Nelle ultime 24 ore ci sono stati ${day.size} errori", "Di': cosa è andato storto")
            else -> Check("registro", "Registro", Level.OK, "Nessun errore nelle ultime 24 ore")
        }
    }

    /**
     * Pulizia: toglie da [dir] (solo i file temporanei di SAM) quelli più vecchi di [olderMs] che [keep] non protegge.
     * Ritorna i byte liberati.
     */
    fun clean(dir: File, now: Long, olderMs: Long, keep: (File) -> Boolean = { false }): Long {
        var freed = 0L
        try {
            dir.walkBottomUp().forEach { f ->
                if (f == dir || keep(f)) return@forEach
                if (f.isFile && now - f.lastModified() > olderMs) { val n = f.length(); if (f.delete()) freed += n }
                else if (f.isDirectory && f.list()?.isEmpty() == true) f.delete()
            }
        } catch (e: Throwable) {}
        return freed
    }

    fun dirSize(dir: File): Long = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (e: Throwable) { 0L }
}

/**
 * La guardia degli avvii: ogni avvio si segna; se resta in piedi un minuto si cancella il segno. Tre avvii di fila
 * finiti male in dieci minuti = modalità sicura (si parte senza le parti pesanti, così almeno ci si può parlare).
 */
class CrashGuard(private val file: File) {
    private fun read(): List<Long> = try { if (file.exists()) file.readText().split('\n').mapNotNull { it.trim().toLongOrNull() } else emptyList() } catch (e: Throwable) { emptyList() }
    private fun write(l: List<Long>) { try { file.parentFile?.mkdirs(); file.writeText(l.joinToString("\n")) } catch (e: Throwable) {} }

    /** Da chiamare all'avvio. true = modalità sicura. */
    fun started(now: Long = System.currentTimeMillis()): Boolean {
        val recent = read().filter { now - it in 0..10 * 60_000L }
        write(recent + now)
        return recent.size >= 3
    }
    /** SAM è rimasto in piedi (o si è chiuso per bene): gli avvii di prima non contano più. */
    fun healthy() = write(emptyList())
    fun pending(): Int = read().size
}

/**
 * 2.0.2: il lavoro in corso. Quando SAM comincia una cosa lunga (scrivere un programma, un documento, una risposta)
 * se la segna in «incorso.txt»; se viene fermato (da te, da un blocco, dal telefono che lo chiude) la cosa passa in
 * «lavoro.txt» con quello che aveva già scritto, e «continua» la riprende da lì. Dopo tre giorni si dimentica.
 */
class WorkLog(private val dir: File) {
    class Job(val at: Long, val kind: String, val text: String, val partial: String)

    private val running = File(dir, "incorso.txt")
    private val stopped = File(dir, "lavoro.txt")
    @Volatile private var current: Job? = null
    private var lastSave = 0L

    private fun write(f: File, j: Job) { try {
        val tmp = File(f.path + ".tmp")
        tmp.writeText("${j.at}\t${j.kind}\t${j.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')}\n${j.partial}", Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8); tmp.delete() }
    } catch (e: Throwable) {} }

    private fun read(f: File): Job? = try {
        if (!f.exists()) null else {
            val all = f.readText(Charsets.UTF_8); val nl = all.indexOf('\n')
            val head = (if (nl >= 0) all.substring(0, nl) else all).split('\t', limit = 3)
            val at = head.getOrNull(0)?.toLongOrNull()
            if (at == null || head.size < 3 || head[2].isBlank()) null else Job(at, head[1], head[2], if (nl >= 0) all.substring(nl + 1) else "")
        }
    } catch (e: Throwable) { null }

    /** Comincia un lavoro: [kind] «write» (un file da scrivere) o «ask» (una risposta). */
    @Synchronized fun begin(kind: String, text: String, now: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        val j = Job(now, kind, text.trim(), ""); current = j; lastSave = now; write(running, j)
    }

    /** Quello che ha scritto finora (si salva al più ogni 5 secondi: serve se il telefono chiude SAM a metà). */
    @Synchronized fun progress(partial: String, now: Long = System.currentTimeMillis()) {
        val c = current ?: return
        if (now - lastSave < 5000) return
        lastSave = now; val j = Job(c.at, c.kind, c.text, partial); current = j; write(running, j)
    }

    /** Finito per bene: non c'è niente da riprendere. */
    @Synchronized fun done() { if (current != null || running.exists()) { current = null; try { running.delete() } catch (e: Throwable) {} } }

    /** Fermato a metà: resta da parte per «continua». */
    @Synchronized fun interrupt(partial: String? = null, now: Long = System.currentTimeMillis()): Job? {
        val c = current ?: return null
        val j = Job(now, c.kind, c.text, partial ?: c.partial)
        current = null; write(stopped, j); try { running.delete() } catch (e: Throwable) {}
        return j
    }

    /** All'avvio: se c'era un lavoro in corso, SAM è stato chiuso a metà. Ritorna quel lavoro (ora in sospeso). */
    @Synchronized fun recover(now: Long = System.currentTimeMillis()): Job? {
        val j = read(running) ?: return null
        try { running.delete() } catch (e: Throwable) {}
        if (now - j.at > KEEP_MS) return null
        write(stopped, j); return j
    }

    /** Il lavoro in sospeso, se c'è ed è recente. */
    fun pending(now: Long = System.currentTimeMillis()): Job? = read(stopped)?.takeIf { now - it.at in 0..KEEP_MS }

    /** Lo prende per riprenderlo (non resta più in sospeso). */
    @Synchronized fun take(now: Long = System.currentTimeMillis()): Job? { val j = pending(now); try { stopped.delete() } catch (e: Throwable) {}; return j }

    companion object {
        const val KEEP_MS = 3 * 86_400_000L
        fun short(text: String) = if (text.length > 90) text.take(88).trimEnd() + "…" else text

        /** «Cosa stavi facendo?» */
        fun describe(j: Job?): String {
            if (j == null) return "Non avevo niente in sospeso: l'ultimo lavoro l'ho finito."
            val lines = j.partial.lines().count { it.isNotBlank() }
            return "Mi sono fermato mentre lavoravo a questo: «${short(j.text)}»." +
                (if (j.kind == "write" && lines > 3) " Avevo già scritto $lines righe." else "") + " Di': continua, e riprendo da lì."
        }

        /**
         * La domanda da fare al cervello per continuare un testo o un programma lasciato a metà. 2.0.4: gli si fa vedere
         * anche l'inizio (così sa com'è fatto il file) e gli si chiede il seguito dentro un blocco, senza ragionamenti:
         * prima certi cervelli rispondevano «The user wants me to continue…» e quel testo finiva nel file.
         */
        fun continuation(question: String, partial: String): String {
            val head = if (partial.length > 3000) "Così comincia il file (solo per ricordarti com'è fatto):\n-----\n" + partial.take(700) + "\n[…]\n-----\n" else ""
            return question + "\n\nNe hai già scritto una parte e ti sei interrotto a metà.\n" + head +
                "Queste sono le ultime righe già scritte:\n-----\n" + partial.takeLast(1500) + "\n-----\n" +
                "Scrivi SOLO il seguito, partendo esattamente dal carattere dopo l'ultimo qui sopra, fino alla fine del file. " +
                "Non ripetere le righe già scritte, non ricominciare da capo, non spiegare e non ragionare ad alta voce: " +
                "rispondi con un unico blocco tra tre apici inversi che contiene soltanto il seguito."
        }

        /**
         * Dal pezzo nuovo scritto dal cervello, solo il seguito vero: via i ragionamenti (<think> o parole prima del blocco),
         * via i tre apici. Se il blocco è stato chiuso (il cervello ha finito da sé) in fondo resta un ``` di chiusura.
         */
        fun onlyMore(more: String): String {
            var t = more.replace("\r", "").replace(Regex("(?s)<think>.*?</think>"), "")
            val open = t.indexOf("<think>"); if (open >= 0) t = t.substring(0, open)
            val i = t.indexOf("```")
            if (i < 0) return t
            val nl = t.indexOf('\n', i)
            val after = if (nl >= 0) t.substring(nl + 1) else ""
            // un ``` con quasi niente dopo è la chiusura: il seguito è quello che c'è prima
            if (after.trim().length < 20 && t.substring(0, i).trim().length > after.trim().length) return t.substring(0, i).trimEnd() + "\n```\n"
            val end = after.indexOf("```")
            return if (end >= 0) after.substring(0, end).trimEnd() + "\n```\n" else after
        }

        /**
         * Unisce la parte vecchia e il seguito. Se il seguito ricomincia qualche riga prima (ripete le ultime righe già
         * scritte, o riscrive per intero la riga rimasta a metà) le righe doppie si tolgono.
         */
        fun join(partial: String, more: String): String {
            val m = onlyMore(more)
            if (m.isBlank()) return partial
            val pl = partial.split('\n'); val ml = m.split('\n')
            val n = pl.size
            val last = pl[n - 1].trim()
            for (idx in maxOf(0, n - 15) until n) {
                val count = n - idx
                if (count > ml.size) continue
                var same = true
                for (k in 0 until count - 1) if (pl[idx + k].trim() != ml[k].trim() || ml[k].isBlank()) { same = false; break }
                if (!same) continue
                // l'ultima riga vecchia può essere a metà: basta che la riga nuova cominci allo stesso modo
                val lastOk = if (last.isEmpty()) count >= 3 else ml[count - 1].trim().startsWith(last) && (count >= 2 || last.length >= 4)
                if (lastOk) return if (last.isEmpty()) partial + ml.subList(count - 1, ml.size).joinToString("\n")
                    else (pl.subList(0, idx) + ml).joinToString("\n")
            }
            // la parte vecchia finisce con una riga completa (e il seguito non va a capo da sé): si va a capo
            val complete = last.isNotEmpty() && (last.last() in ";{}>,)" || last.endsWith("*/") || last.endsWith("-->"))
            return if (complete && !partial.endsWith("\n") && !m.startsWith("\n")) partial + "\n" + m else partial + m
        }
    }
}
