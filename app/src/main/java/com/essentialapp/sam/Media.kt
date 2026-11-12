package com.essentialapp.sam

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 1.21 · Foto e video: vederli e farli.
 *  - "mostrami le foto di ieri", "fammi vedere l'ultimo video", "i tuoi disegni" → si vedono nella chat (tocchi e si aprono)
 *  - "cosa c'è nell'ultima foto?" → il cervello che vede la guarda
 *  - "scatta una foto", "fammi un selfie tra 3 secondi" → SAM scatta da solo e la mette nella Galleria
 *  - "registra un video di 20 secondi" → SAM filma da solo
 *  - "fammi un video con le foto di sabato", "anima l'ultima foto" → un video con zoom, panoramiche e dissolvenze ([Reel])
 * Qui la parte che capisce le frasi e che fa i fotogrammi, in Kotlin puro: la usano il telefono e il PC.
 */
object MediaCommands {
    private fun ci(p: String) = Lang.rx(p)

    private const val FILL = "(?:(?:sam|ehi|ok|okay|allora|senti|per favore|perfavore|dai|adesso|ora|ascolta|e|poi)[,]?\\s+)*" +
        "(?:(?:mi |ti )?(?:puoi |potresti |riesci a |vorrei che (?:tu )?|voglio che (?:tu )?)?)?"
    private const val ART = "(?:(?:un'|una |uno |un |1 |la |il |lo |l'|le |i |gli |delle |dei |degli |qualche |tutte le |tutti i )\\s*)?"
    private const val PHOTO = "foto|fotografia|fotografie|fotine|immagine|immagini|selfie|scatti|scatto|disegno|disegni|disegnino|disegnini|" +
        "screenshot|schermata|schermate|catture dello schermo"
    private const val VIDEO = "video|videoclip|filmato|filmati|filmino|filmini|clip|registrazioni video"
    private const val NUM = "(?:\\d{1,3})"
    private const val SECS = "(?:(?:di|per|da|lungo|lunga|della durata di)\\s+)?(?:$NUM|un|1|mezzo)\\s*(?:secondi|secondo|sec|s|minuti|minuto|min)(?:\\s+e\\s+mezzo)?"
    private const val CAMSIDE = "(?:(?:con|dalla|usando)\\s+(?:la\\s+)?(?:fotocamera|camera|telecamera)\\s+)?(?:davanti|frontale|anteriore|dietro|posteriore)|" +
        "(?:con|dalla)\\s+(?:la\\s+)?(?:fotocamera|camera|telecamera|webcam)"
    private const val WHEN = "(?:(?:di|del|dello|della|dei|delle|dell'|nel|nella|in|da|fatt[ei] |scattat[ei] |girat[ei] )\\s*)?" +
        "(?:oggi|stamattina|stamani|stasera|stanotte|ieri(?: sera| mattina)?|l'altro ieri|l'altroieri|altroieri|" +
        "(?:questa|quest'ultima|la|ultima) settimana(?: scorsa)?|settimana scorsa|(?:questo|il) mese(?: scorso)?|mese scorso|quest'anno|" +
        "(?:lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica)(?: scorso| scorsa)?|ultim[ie] $NUM giorni|" +
        "$NUM(?: (?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre))?)"
    private const val MINE = "(?:che (?:hai|avevi) (?:fatto|disegnato|creato|generato|scattato|girato|registrato)(?: tu)?|tuoi|tue|di sam|fatt[ie] da te|che mi hai fatto)"
    private const val OWN = "(?:che (?:ho|abbiamo) (?:fatto|scattato|girato|registrato|ripreso|preso)|mie|miei|del telefono|della galleria|nella galleria|in galleria|sul telefono|sul pc|sul computer)"
    private const val TAIL = "(?:\\s+(?:$WHEN|$MINE|$OWN|per favore|adesso|ora|qui|qua|in chat|sullo schermo))*"

    // "mostrami le ultime 5 foto di ieri", "fammi vedere l'ultimo video", "apri l'ultima foto", "i tuoi disegni"
    private val SHOW = ci(
        "^$FILL(?:mostrami|mostra|mostrare|mostrarmi|fammi vedere|fai vedere|fa vedere|fammi rivedere|famme vede|vediamo|apri|aprimi|aprire|" +
        "visualizza|visualizzami|voglio vedere|vorrei vedere|fammi guardare|trovami|cercami|cerca|tirami fuori|dammi)\\s+" +
        "$ART(?:(?:mie|miei|tuoi|tue)\\s+)?(?:(?:ultim[aoie]|recenti|nuov[ie])\\s+)?(?:$NUM\\s+)?(?:(?:mie|miei|tuoi|tue)\\s+)?" +
        "(?:$PHOTO|$VIDEO)(?:\\s+e\\s+(?:i |le |gli )?(?:$PHOTO|$VIDEO))?(?:\\s+(?:più )?recenti)?$TAIL$"
    )
    // "che foto ho fatto ieri?", "quante foto ho fatto oggi?", "hai fatto dei disegni?"
    private val SHOW_Q = ci(
        "^$FILL(?:che|quali|quante|quanti)\\s+(?:$PHOTO|$VIDEO)\\s+(?:ho|abbiamo|hai)\\s+(?:fatto|scattato|girato|registrato|disegnato)$TAIL$"
    )
    // "cosa c'è nell'ultima foto?", "descrivimi l'ultima foto", "guarda l'ultimo screenshot", "com'è venuta la foto?"
    private val DESCRIBE = ci(
        "^$FILL(?:(?:dimmi|descrivimi|spiegami)\\s+)?(?:(?:cosa|che cosa|chi) c'è|cosa si vede|cosa vedi|che si vede)\\s+" +
        "(?:nell'|nella |nel |sull'|sulla |in |nelle |negli )\\s*(?:ultim[ao] |mi[ao] |tu[ao] )?(?:$PHOTO)$TAIL$|" +
        "^$FILL(?:descrivi|descrivimi|guarda|guardami|analizza|analizzami|commenta|leggi|leggimi|riconosci|dimmi cosa c'è in|spiegami)\\s+" +
        "(?:l'|la |lo |il |quest'|questa )\\s*(?:ultim[ao]\\s+)?(?:$PHOTO)\\s*(?:che (?:ho|hai|abbiamo) (?:appena )?(?:fatto|scattato|disegnato))?$TAIL$|" +
        "^$FILL(?:com'è|come è|come ti sembra|ti piace|è bella) (?:venut[ao] )?(?:l'|la |il )\\s*(?:ultim[ao] )?(?:foto|fotografia|selfie|disegno)(?: che (?:ho|hai) (?:appena )?(?:fatto|scattato))?$"
    )
    // "scatta una foto", "fammi un selfie tra 3 secondi", "fai una foto alla pianta", "scattami una foto con la fotocamera davanti"
    private val PHOTO_NOW = ci(
        "^$FILL(?:scatta|scattami|scattaci|scattare|scattarmi|fai|fammi|fatti|facci|fare|farmi|farci|facciamo|scattiamo)\\s+" +
        "(?:un'|una |un |uno |1 |la |il |lo |l')?\\s*(?:foto|fotografia|selfie|foto ricordo|autoscatto)" +
        "(?:\\s+(?:a me|di me|a noi|di noi|a tutti|di gruppo|col timer|con il timer|con l'autoscatto|adesso|ora|subito|per favore|" +
        "$CAMSIDE|(?:tra|fra) $NUM\\s*(?:secondi|secondo)|(?:a|al|alla|allo|ai|alle|agli|all')\\s+[^,]{1,40}))*$"
    )
    // "registra un video", "registra un video di 20 secondi con la fotocamera davanti", "filmami per 10 secondi"
    private val RECORD = ci(
        "^$FILL(?:registra|registrami|registrare|registrarmi|riprendi|riprendimi|riprendere|filma|filmami|filmare|filmarmi|gira|girami|girare|" +
        "fai|fammi|fare|farmi|facciamo|giriamo|registriamo|inizia a registrare|comincia a registrare|avvia)" +
        "(?:\\s+(?:un'|una |un |uno |1 |la |il |lo |l')?\\s*(?:video|videoclip|filmato|filmino|ripresa|registrazione video|videoselfie|video selfie))?" +
        "(?:\\s+(?:a me|di me|a noi|di noi|adesso|ora|subito|per favore|breve|corto|$SECS|$CAMSIDE|(?:tra|fra) $NUM\\s*secondi))*$"
    )
    private val RECORD_VERB_ONLY = ci("^$FILL(?:registra|registrami|riprendimi|filma|filmami|girami|registrarmi|filmarmi)(?:\\s|$)")
    private val RECORD_WORD = ci("\\b(?:video|videoclip|filmato|filmino|ripresa|registrazione video|videoselfie)\\b")
    // "registra lo schermo per 30 secondi"
    private val SCREEN_REC = ci(
        "^$FILL(?:registra|registrami|registrare|filma|riprendi|fai un video del(?:lo)?|fammi un video del(?:lo)?|fai una registrazione del(?:lo)?)\\s+" +
        "(?:lo |il )?(?:schermo|desktop|monitor|display)(?:\\s+(?:$SECS|adesso|ora|per favore))*$"
    )
    // "fammi un video con le foto di sabato", "monta le foto di ieri in un video", "anima l'ultima foto"
    private val REEL = ci(
        "^$FILL(?:fammi|fai|fare|farmi|crea|creami|creare|genera|generami|prepara|preparami|realizza|realizzami|monta|montami)\\s+" +
        "(?:un'|una |un |uno |il |lo |l')?\\s*(?:video|videoclip|filmato|filmino|montaggio|slideshow|video ricordo|presentazione video|clip|reel)" +
        "(?:\\s+(?:ricordo|breve|corto|bello|carino|musicale))?\\s+" +
        "(?:con|coi|delle|dalle|usando|sulle|degli|dei|dagli|dai|di)\\s+(?:le |gli |i |tutte le |queste |quelle )?(?:(?:mie|miei)\\s+)?" +
        "(?:(?:ultim[ei]|recenti)\\s+)?(?:$NUM\\s+)?(?:$PHOTO)$TAIL$|" +
        "^$FILL(?:monta|montami|unisci|mettimi|metti)\\s+(?:le |tutte le |queste )?(?:(?:mie|ultim[ei])\\s+)?(?:$NUM\\s+)?(?:$PHOTO)$TAIL\\s+in\\s+(?:un |uno )?(?:video|filmato|montaggio|slideshow)$"
    )
    private val ANIMATE = ci(
        "^$FILL(?:anima|animami|animare|muovi|dai vita a(?:ll')?|fai muovere)\\s+(?:l'|la |il |quest'|questa )?\\s*(?:ultim[ao]\\s+)?(?:$PHOTO)(?:\\s+che (?:ho|hai) (?:fatto|scattato|disegnato))?$TAIL$|" +
        "^$FILL(?:trasforma|fai diventare|converti)\\s+(?:l'|la |il |quest'|questa )?\\s*(?:ultim[ao]\\s+)?(?:$PHOTO)(?:\\s+che (?:ho|hai) (?:fatto|scattato|disegnato))?$TAIL\\s+in\\s+(?:un )?(?:video|filmato|animazione)$|" +
        "^$FILL(?:fammi|fai|crea|creami)\\s+(?:un |il )?(?:video|filmato|animazione)\\s+(?:dall'|dalla |dal |con l'|con la |della |dell')\\s*(?:ultim[ao]\\s+)?(?:foto|fotografia|selfie|disegno|immagine)(?:\\s+che (?:ho|hai) (?:fatto|scattato|disegnato))?$"
    )

    /** La frase chiede di vedere o fare foto e video? null = no. [now] e [zone] servono per "ieri", "sabato", "questa settimana". */
    fun parse(t: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Outcome.Media? {
        val s = t.trim().trimEnd('.', '!', '?', ' ').replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        val front = ci("\\b(?:davanti|frontale|anteriore|selfie|videoselfie|video selfie|a me|di me|a noi|di noi|filmami|riprendimi|registrami|girami|filmarmi|registrarmi)\\b").containsMatchIn(s) &&
            !ci("\\b(?:dietro|posteriore)\\b").containsMatchIn(s)
        if (SCREEN_REC.matches(s)) return Outcome.Media("screenrec", "video", seconds = seconds(s, 30))
        if (ANIMATE.matches(s)) return Outcome.Media("reel", kindOf(s, "photo"), count = 1, mine = mine(s)).withWhen(s, now, zone)
        if (REEL.matches(s)) return Outcome.Media("reel", "photo", count = count(s), mine = mine(s)).withWhen(s, now, zone)
        if (PHOTO_NOW.matches(s)) {
            val delay = Regex("(?:tra|fra) (\\d{1,3})").find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (ci("\\b(?:timer|autoscatto|di gruppo|a tutti|a noi|di noi)\\b").containsMatchIn(s)) 5 else if (front) 3 else 0
            return Outcome.Media("photo", "photo", front = front, seconds = delay.coerceIn(0, 30))
        }
        if (RECORD.matches(s) && (RECORD_WORD.containsMatchIn(s) || RECORD_VERB_ONLY.containsMatchIn(s)) &&
            !ci("\\b(?:audio|vocale|voce|nota|messaggio|chiamata|canzone|schermo)\\b").containsMatchIn(s))
            return Outcome.Media("record", "video", front = front, seconds = seconds(s, 15))
        // "descrivi la foto", "cosa c'è nella foto" senza dire quale: è la fotocamera ("guarda e dimmi"), non la Galleria
        if (DESCRIBE.matches(s) && (period(s, now, zone) != null ||
                ci("\\b(?:ultim[ao]|che (?:ho|hai|abbiamo)|mi[ao]|tu[ao]|com'è|come è|ti piace|è bella|come ti sembra|disegno|screenshot|schermata)\\b").containsMatchIn(s)))
            return Outcome.Media("describe", kindOf(s, "photo"), count = 1, mine = mine(s)).withWhen(s, now, zone)
        if (SHOW.matches(s) || SHOW_Q.matches(s)) {
            val single = ci("\\b(?:l'ultim[ao]|ultim[ao] (?:$PHOTO|$VIDEO)|l'ultimo|l'ultima)\\b").containsMatchIn(s) && count(s) == 0
            val open = single && ci("^$FILL(?:apri|aprimi|aprire)\\b").containsMatchIn(s)
            return Outcome.Media("show", kindOf(s, "photo"), count = if (single) 1 else count(s), mine = mine(s), open = open,
                ask = SHOW_Q.matches(s)).withWhen(s, now, zone)
        }
        return null
    }

    /** foto, video, entrambi ("any") o le schermate ("screenshot"). */
    fun kindOf(s: String, default: String): String {
        val v = ci("\\b(?:$VIDEO)\\b").containsMatchIn(s.replace(Regex("(?i)\\b(?:fammi|fai|crea|creami|un|il) (?:video|filmato)\\b.*?\\b(?:con|dall'|dalla|delle|dai)\\b"), ""))
        val p = ci("\\b(?:foto|fotografi[ae]|fotine|immagin[ei]|selfie|scatti|scatto|disegn[oi]|disegnin[oi])\\b").containsMatchIn(s)
        val sc = ci("\\b(?:screenshot|schermat[ae]|catture dello schermo)\\b").containsMatchIn(s)
        return when { sc && !p && !v -> "screenshot"; v && p -> "any"; v -> "video"; p -> "photo"; else -> default }
    }

    /** i disegni, o quello che ha fatto SAM: la cartella SAM */
    fun mine(s: String): Boolean = ci("\\b(?:disegn[oi]|disegnin[oi]|$MINE)\\b").containsMatchIn(s)

    private fun count(s: String): Int = Regex("(?<![:\\d])(\\d{1,3})(?=\\s+(?:ultim[ei]\\s+)?(?:mie |miei |tuoi |tue )?(?:$PHOTO|$VIDEO)\\b)", RegexOption.IGNORE_CASE)
        .find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 200) ?: 0

    /** La durata chiesta, in secondi (default [def], massimo 3 minuti). */
    fun seconds(s: String, def: Int): Int {
        val m = Regex("(?i)(\\d{1,3}|un|1|mezzo)\\s*(secondi|secondo|sec|s|minuti|minuto|min)\\b(\\s+e\\s+mezzo)?").find(s) ?: return def
        val n = when (m.groupValues[1].lowercase()) { "un", "1" -> 1.0; "mezzo" -> 0.5; else -> m.groupValues[1].toDouble() } +
            (if (m.groupValues[3].isNotEmpty()) 0.5 else 0.0)
        val secs = if (m.groupValues[2].lowercase().startsWith("m")) n * 60 else n
        return secs.toInt().coerceIn(2, 180)
    }

    private fun Outcome.Media.withWhen(s: String, now: Long, zone: ZoneId): Outcome.Media {
        val w = period(s, now, zone) ?: return this
        return copy(from = w.first, to = w.second, period = w.third)
    }

    private val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val DAYS = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")

    /** "ieri", "sabato", "questa settimana", "del 24 ottobre" → (da, a, come dirlo). null = nessun periodo. */
    fun period(s0: String, now: Long, zone: ZoneId): Triple<Long, Long, String>? {
        val s = s0.lowercase()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        fun day(d: LocalDate, label: String) = Triple(start(d), start(d.plusDays(1)), label)
        fun span(a: LocalDate, b: LocalDate, label: String) = Triple(start(a), start(b), label)
        Regex("\\bultim[ie] (\\d{1,3}) giorni\\b").find(s)?.let { val n = it.groupValues[1].toLong().coerceIn(1, 3650)
            return span(today.minusDays(n - 1), today.plusDays(1), "degli ultimi $n giorni") }
        when {
            Regex("\\bl'altro ?ieri\\b|\\baltroieri\\b").containsMatchIn(s) -> return day(today.minusDays(2), "dell'altro ieri")
            Regex("\\bieri\\b").containsMatchIn(s) -> return day(today.minusDays(1), "di ieri")
            Regex("\\b(?:oggi|stamattina|stamani|stasera|stanotte)\\b").containsMatchIn(s) -> return day(today, "di oggi")
            Regex("\\bsettimana scorsa\\b").containsMatchIn(s) -> { val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                return span(mon, mon.plusDays(7), "della settimana scorsa") }
            Regex("\\b(?:questa|quest'ultima|ultima|la) settimana\\b").containsMatchIn(s) ->
                return span(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today.plusDays(1), "di questa settimana")
            Regex("\\bmese scorso\\b").containsMatchIn(s) -> { val first = today.withDayOfMonth(1).minusMonths(1)
                return span(first, first.plusMonths(1), "del mese scorso") }
            Regex("\\b(?:questo|il) mese\\b").containsMatchIn(s) -> return span(today.withDayOfMonth(1), today.plusDays(1), "di questo mese")
            Regex("\\bquest'anno\\b").containsMatchIn(s) -> return span(today.withDayOfYear(1), today.plusDays(1), "di quest'anno")
        }
        for ((i, d) in DAYS.withIndex()) if (Regex("\\b$d\\b").containsMatchIn(s)) {
            var date = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(i + 1)))
            if (Regex("\\b$d scors[oa]\\b").containsMatchIn(s) && date == today) date = date.minusWeeks(1)
            return day(date, "di $d")
        }
        Regex("\\b(?:del|dell'|il|nel|al)\\s*(\\d{1,2})(?:\\s+(${MONTHS.joinToString("|")}))?\\b").find(s)?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = if (m.groupValues[2].isNotEmpty()) MONTHS.indexOf(m.groupValues[2]) + 1 else today.monthValue
            var date = try { LocalDate.of(today.year, mm, dd) } catch (e: Exception) { return null }
            if (date.isAfter(today)) date = if (m.groupValues[2].isNotEmpty()) date.minusYears(1) else try { date.minusMonths(1) } catch (e: Exception) { return null }
            return day(date, "del ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}")
        }
        return null
    }

    /** Come chiamare quello che si mostra: "foto", "video", "foto e video", "disegni", "schermate". */
    fun noun(kind: String, mine: Boolean, n: Int): String = when {
        kind == "video" -> "video"
        kind == "any" -> "foto e video"
        kind == "screenshot" -> if (n == 1) "schermata" else "schermate"
        mine -> if (n == 1) "disegno" else "disegni"
        else -> "foto"
    }

    // separatori che la chat salvata non tocca (a capo e tabulazioni lì diventano spazi)
    private const val REC = '\u001E'
    private const val UNIT = '\u001F'
    private fun clean(x: String) = x.replace(REC, ' ').replace(UNIT, ' ').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** Una riga della chat con le foto e i video da mostrare: per ogni elemento indirizzo, tipo, nome e durata in ms. */
    fun line(items: List<Item>): String = items.joinToString(REC.toString()) { listOf(clean(it.ref), clean(it.mime), clean(it.name), it.durationMs.toString()).joinToString(UNIT.toString()) }

    fun items(line: String): List<Item> = line.split(REC).mapNotNull { l ->
        val p = l.split(UNIT)
        if (p.size < 2 || p[0].isBlank()) null else Item(p[0], p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "0" }.toLongOrNull() ?: 0L)
    }

    /** Un elemento da mostrare: [ref] è l'indirizzo (content:// sul telefono, il percorso del file sul PC). */
    data class Item(val ref: String, val mime: String, val name: String, val durationMs: Long = 0, val taken: Long = 0,
                    val width: Int = 0, val height: Int = 0) {
        val isVideo get() = mime.startsWith("video")
    }

    /** Cosa dire mostrando [shown] elementi su [total] trovati ("Ecco l'ultima foto.", "Ecco 12 foto di ieri: ne hai 40…"). */
    fun shownText(o: Outcome.Media, shown: List<Item>, total: Int): String {
        val per = if (o.period.isNotEmpty()) " ${o.period}" else ""
        if (shown.size == 1) {
            val it = shown[0]
            val drawing = o.mine && !it.isVideo
            val masc = it.isVideo || drawing
            val n1 = when { it.isVideo -> "video"; drawing -> "disegno"; o.kind == "screenshot" -> "schermata"; else -> "foto" }
            val art = if (total == 1 && o.count != 1) (if (masc) "l'unico" else "l'unica") else (if (masc) "l'ultimo" else "l'ultima")
            return "Ecco $art $n1${if (o.mine) " che ho fatto" else ""}$per." +
                (if (it.isVideo && it.durationMs > 0) " Dura ${duration(it.durationMs)}." else "") +
                (if (masc) " Toccalo per aprirlo." else " Toccala per aprirla.")
        }
        val n = noun(o.kind, o.mine, shown.size)
        val head = if (o.ask) "${if (total == 1) "Una" else "$total"} $n$per." else "Ecco ${shown.size} $n$per."
        val more = if (total > shown.size) " Ne hai $total in tutto: ti mostro le più recenti." else ""
        return head + more + " Tocca per aprire, tieni premuto per condividere."
    }

    /** "1:05" */
    fun duration(ms: Long): String { val s = (ms + 500) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
}

/**
 * Il video con le foto (come i "ricordi" della Galleria): ogni foto si muove (zoom e panoramiche, come nei documentari),
 * si passa dall'una all'altra con una dissolvenza incrociata, all'inizio e alla fine si sfuma dal nero. Verticale (720×1280)
 * se la maggior parte delle foto è verticale, altrimenti orizzontale (1280×720). Fotogrammi in YUV 4:2:0 (I420).
 */
object Reel {
    const val FPS = 30
    const val MAX_PHOTOS = 40

    class Frame(val w: Int, val h: Int) {
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2))
        val v = ByteArray((w / 2) * (h / 2))
    }

    /** Una foto già ritagliata nelle proporzioni del video: [rgb] = 3 byte per pixel. */
    class Pic(val rgb: ByteArray, val w: Int, val h: Int)

    /** [clip] = fotogrammi di ogni foto, [overlap] = quanti si sovrappongono nella dissolvenza. */
    data class Plan(val w: Int, val h: Int, val count: Int, val clip: Int, val overlap: Int) {
        val step get() = clip - overlap
        val total get() = if (count <= 0) 0 else count * step + overlap
        val seconds get() = (total + FPS / 2) / FPS
        /** la grandezza a cui preparare ogni foto (un po' più grande del video: così lo zoom resta nitido) */
        val srcW get() = (w * 5 / 4) and 1.inv()
        val srcH get() = (h * 5 / 4) and 1.inv()
    }

    /** Il piano del video per [count] foto: 8 secondi per una foto sola, poi circa 2,5 secondi a foto (meno se sono tante). */
    fun plan(count: Int, portrait: Boolean, maxSeconds: Int = 60): Plan {
        val w = if (portrait) 720 else 1280
        val h = if (portrait) 1280 else 720
        if (count <= 1) return Plan(w, h, count.coerceAtLeast(0), 8 * FPS, 0)
        val overlap = FPS / 2
        val step = ((maxSeconds * FPS - overlap) / count).coerceIn(36, 75)
        return Plan(w, h, count, step + overlap, overlap)
    }

    /** Verticale se lo è la maggior parte delle foto ([sizes] = larghezza, altezza). */
    fun portrait(sizes: List<Pair<Int, Int>>): Boolean = sizes.count { it.second > it.first } * 2 > sizes.size

    /** Il ritaglio (x, y, larghezza, altezza) di una foto [sw]×[sh] con le proporzioni [w]:[h], centrato (un po' in alto: i visi). */
    fun cover(sw: Int, sh: Int, w: Int, h: Int): IntArray {
        return if (sw.toLong() * h > sh.toLong() * w) {      // più larga: si tagliano i lati
            val cw = (sh.toLong() * w / h).toInt().coerceIn(1, sw)
            intArrayOf((sw - cw) / 2, 0, cw, sh)
        } else {                                              // più alta: si taglia sopra e sotto
            val ch = (sw.toLong() * h / w).toInt().coerceIn(1, sh)
            intArrayOf(0, ((sh - ch) * 0.4f).toInt(), sw, ch)
        }
    }

    private val STYLES = arrayOf(Motion.Style.ZOOM_IN, Motion.Style.PAN_RIGHT, Motion.Style.ZOOM_OUT, Motion.Style.PAN_UP)
    fun style(k: Int): Motion.Style = STYLES[k % STYLES.size]

    /** Quale foto (o quali due, durante la dissolvenza) va nel fotogramma [i]: (foto, fotogramma suo). */
    fun at(p: Plan, i: Int): Pair<Int, Int> {
        var k = (i / p.step).coerceAtMost(p.count - 1)
        if (k < 0) k = 0
        return k to (i - k * p.step)
    }

    /**
     * Riempie [out] col fotogramma [i]. [pic] dà la foto numero k (il chiamante ne tiene in memoria solo un paio);
     * [tmp] serve per la dissolvenza (stessa grandezza di [out]).
     */
    fun frame(p: Plan, i: Int, pic: (Int) -> Pic, out: Frame, tmp: Frame) {
        val (k, local) = at(p, i)
        val last = (p.clip - 1).coerceAtLeast(1).toFloat()
        render(pic(k), Motion.cam(style(k), local / last, 1), out)
        if (local < p.overlap && k > 0) {
            render(pic(k - 1), Motion.cam(style(k - 1), (local + p.step) / last, 1), tmp)
            blend(out, tmp, (local + 1f) / (p.overlap + 1f))
        }
        val light = Motion.fade(i, p.total, FPS)
        if (light < 1f) dim(out, light)
    }

    /** [out] = a·[out] + (1−a)·[other] */
    fun blend(out: Frame, other: Frame, a: Float) {
        val wa = (a * 256).toInt().coerceIn(0, 256); val wb = 256 - wa
        fun mix(x: ByteArray, y: ByteArray) { for (j in x.indices) x[j] = (((x[j].toInt() and 0xff) * wa + (y[j].toInt() and 0xff) * wb + 128) shr 8).toByte() }
        mix(out.y, other.y); mix(out.u, other.u); mix(out.v, other.v)
    }

    /** Verso il nero: luminosità [light] (0..1). */
    fun dim(f: Frame, light: Float) {
        for (j in f.y.indices) f.y[j] = (16 + ((f.y[j].toInt() and 0xff) - 16) * light).toInt().coerceIn(0, 255).toByte()
        for (j in f.u.indices) { f.u[j] = (128 + ((f.u[j].toInt() and 0xff) - 128) * light).toInt().toByte(); f.v[j] = (128 + ((f.v[j].toInt() and 0xff) - 128) * light).toInt().toByte() }
    }

    /**
     * L'inquadratura [cam] (in proporzione: 0..1, come dà [Motion.cam] con lato 1) della foto [pic], ingrandita a [out]
     * con interpolazione bilineare. Colori BT.601 a gamma limitata, come i video.
     */
    fun render(pic: Pic, cam: Motion.Cam, out: Frame) {
        val w = out.w; val h = out.h; val sw = pic.w; val sh = pic.h; val rgb = pic.rgb
        val rowR = IntArray(w); val rowG = IntArray(w); val rowB = IntArray(w)
        val prevR = IntArray(w); val prevG = IntArray(w); val prevB = IntArray(w)
        val limX = (sw - 1).toFloat(); val limY = (sh - 1).toFloat()
        val kx = cam.size * sw / w; val ky = cam.size * sh / h
        val ox = cam.x * sw; val oy = cam.y * sh
        val x0s = IntArray(w); val x1s = IntArray(w); val fxs = FloatArray(w)
        for (xx in 0 until w) {
            val sx = (ox + (xx + 0.5f) * kx - 0.5f).coerceIn(0f, limX)
            x0s[xx] = sx.toInt(); x1s[xx] = minOf(x0s[xx] + 1, sw - 1); fxs[xx] = sx - x0s[xx]
        }
        for (yy in 0 until h) {
            val sy = (oy + (yy + 0.5f) * ky - 0.5f).coerceIn(0f, limY)
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val fy = sy - y0
            val r0 = y0 * sw; val r1 = y1 * sw
            for (xx in 0 until w) {
                val fx = fxs[xx]
                val a = 3 * (r0 + x0s[xx]); val b = 3 * (r0 + x1s[xx]); val c = 3 * (r1 + x0s[xx]); val d = 3 * (r1 + x1s[xx])
                fun ch(o: Int): Int {
                    val top = (rgb[a + o].toInt() and 0xff) * (1 - fx) + (rgb[b + o].toInt() and 0xff) * fx
                    val bot = (rgb[c + o].toInt() and 0xff) * (1 - fx) + (rgb[d + o].toInt() and 0xff) * fx
                    return (top * (1 - fy) + bot * fy + 0.5f).toInt().coerceIn(0, 255)
                }
                val r = ch(0); val g = ch(1); val bl = ch(2)
                rowR[xx] = r; rowG[xx] = g; rowB[xx] = bl
                out.y[yy * w + xx] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
            }
            if (yy and 1 == 1) {
                val cy = yy / 2; val cw = w / 2
                for (cx in 0 until cw) {
                    val x = cx * 2
                    val r = (rowR[x] + rowR[x + 1] + prevR[x] + prevR[x + 1]) / 4
                    val g = (rowG[x] + rowG[x + 1] + prevG[x] + prevG[x + 1]) / 4
                    val bl = (rowB[x] + rowB[x + 1] + prevB[x] + prevB[x + 1]) / 4
                    out.u[cy * cw + cx] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    out.v[cy * cw + cx] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            } else { rowR.copyInto(prevR); rowG.copyInto(prevG); rowB.copyInto(prevB) }
        }
    }

    /** Ridimensiona [src] (RGB, [sw]×[sh]) a [w]×[h] con la media dei pixel (per rimpicciolire bene le foto grandi). */
    fun scale(src: ByteArray, sw: Int, sh: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) {
            val ya = y * sh / h; val yb = maxOf(ya + 1, (y + 1) * sh / h)
            for (x in 0 until w) {
                val xa = x * sw / w; val xb = maxOf(xa + 1, (x + 1) * sw / w)
                var r = 0; var g = 0; var b = 0; var n = 0
                var yy = ya
                while (yy < yb) {
                    var xx = xa
                    while (xx < xb) { val o = 3 * (yy * sw + xx); r += src[o].toInt() and 0xff; g += src[o + 1].toInt() and 0xff; b += src[o + 2].toInt() and 0xff; n++; xx++ }
                    yy++
                }
                val o = 3 * (y * w + x)
                out[o] = (r / n).toByte(); out[o + 1] = (g / n).toByte(); out[o + 2] = (b / n).toByte()
            }
        }
        return out
    }
}
