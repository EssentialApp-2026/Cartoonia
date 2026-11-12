package com.essentialapp.sam

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 1.23 · La mente di SAM, organizzata come quella umana (e con i pilastri di OpenJarvis: memoria, agenti, strumenti,
 * apprendimento), tutta sul dispositivo:
 *  - memoria episodica: il diario. Le conversazioni diventano episodi riassunti che restano per un anno (le battute
 *    intere solo 7 giorni), così «di cosa abbiamo parlato a settembre?» ha una risposta vera.
 *  - memoria semantica con la sua forza: i ricordi che usi restano vivi, quelli mai usati sbiadiscono (non si cancellano:
 *    se ne riparli tornano in mente); quelli importanti pesano di più.
 *  - associazioni: a ogni domanda «vengono in mente» gli episodi, gli impegni e lo stato d'animo che c'entrano.
 *  - memoria prospettica: gli impegni detti di passaggio («domani ho il dentista alle 16») → te li ricorda e poi
 *    ti chiede com'è andata.
 *  - memoria procedurale: le abitudini (il meteo ogni mattina verso le 7) → te le propone al momento giusto.
 *  - emozioni: capisce come ti senti e adatta il tono; se qualcuno sta molto male indica subito chi può aiutare.
 *  - sonno: quando è fermo riordina: riassume la giornata nel diario, aggiorna abitudini e impegni.
 *  - pensiero veloce e lento: alle domande difficili ci pensa prima di rispondere.
 *  - metacognizione: sa da dove viene quello che ha detto («come lo sai?»).
 *  - curiosità: «facciamo conoscenza» → ti fa qualche domanda per conoscerti.
 * Kotlin puro: la usano il telefono e il PC, e si collauda senza telefono.
 */
class Mind(val zone: ZoneId = ZoneId.systemDefault()) {

    /** Un episodio del diario: una conversazione (battute vicine nel tempo), riassunta. */
    data class Episode(val start: Long, val end: Long, val summary: String, val topics: List<String>, val valence: Double,
                       val importance: Int, val lines: Int, val refined: Boolean = false)

    /** Un impegno detto di passaggio. [state]: open | asked (chiesto com'è andata) | done | dropped. */
    data class Plan(val id: Long, val text: String, val day: LocalDate, val time: String?, val state: String, val created: Long)

    /** Una cosa fatta (un comando), per imparare le abitudini. */
    data class Act(val key: String, val label: String, val command: String, val at: Long)

    /** Un'abitudine: [minute] = minuto del giorno tipico; [days] = i giorni (tutti = ogni giorno). */
    data class Habit(val key: String, val label: String, val command: String, val minute: Int, val days: Set<DayOfWeek>, val count: Int)

    /** Da dove viene l'ultima risposta. [kind]: memoria | enciclopedia | web | cervello | conversazione | comando. */
    data class Source(val kind: String, val detail: String, val question: String, val at: Long)

    class Use(var count: Int, var last: Long)

    private val episodes = ArrayList<Episode>()
    private val plans = ArrayList<Plan>()
    private val acts = ArrayList<Act>()
    private val uses = HashMap<String, Use>()
    private val kv = HashMap<String, String>()
    private var moodValence = 0.0
    private var moodLabel = ""
    private var moodAt = 0L
    @Volatile var lastSource: Source? = null

    /** Chiamata a ogni cambiamento: il servizio salva la mente su file. */
    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { onChange?.invoke() }

    // ------------------------------------------------------------------ impostazioni
    /** pensiero lento da solo alle domande difficili */
    var thinkAuto: Boolean
        get() = synchronized(this) { kv["pensa"] != "0" }
        set(v) { synchronized(this) { kv["pensa"] = if (v) "1" else "0" }; changed() }
    /** proposte da sola: saluto del mattino, abitudini, «com'è andata?» */
    var proactive: Boolean
        get() = synchronized(this) { kv["proposte"] != "0" }
        set(v) { synchronized(this) { kv["proposte"] = if (v) "1" else "0" }; changed() }

    fun get(k: String): String? = synchronized(this) { kv[k] }
    fun put(k: String, v: String) { synchronized(this) { kv[k] = v }; changed() }

    private fun day(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    private fun minuteOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(zone).let { it.hour * 60 + it.minute }
    private fun dayKey(d: LocalDate) = d.toString()

    // ------------------------------------------------------------------ percezione
    /** Cosa ha colto da una frase: impegni nuovi, emozione, crisi. */
    class Perception(val plans: List<Plan>, val feeling: Feelings.Feeling?, val crisis: Boolean)

    /** Ogni frase detta a SAM (non i comandi): coglie gli impegni e lo stato d'animo. */
    fun perceive(text: String, now: Long): Perception {
        val crisis = Feelings.crisis(text)
        val f = if (crisis) Feelings.Feeling("molto giù", -1.0, 1.0) else Feelings.read(text)
        val added = ArrayList<Plan>()
        synchronized(this) {
            if (f != null) { moodValence = f.valence * f.intensity; moodLabel = f.label; moodAt = now }
            if (!crisis) for (p in Plans.extract(text, now, zone)) {
                val same = plans.firstOrNull { it.day == p.day && it.state == "open" && similar(it.text, p.text) }
                if (same != null) {
                    if (p.time != null && same.time != p.time) { plans[plans.indexOf(same)] = same.copy(time = p.time); added.add(same.copy(time = p.time)) }
                    continue
                }
                val plan = Plan(now + added.size, p.text, p.day, p.time, "open", now)
                plans.add(plan); added.add(plan)
            }
            while (plans.size > 300) plans.removeAt(0)
        }
        if (f != null || added.isNotEmpty()) changed()
        return Perception(added, f, crisis)
    }

    private fun similar(a: String, b: String): Boolean {
        val q = Remember.words(a).distinct()
        return q.isNotEmpty() && Remember.score(q, Memo(Remember.NOTA, b)) >= 60
    }

    /** Lo stato d'animo di adesso (sbiadisce in qualche ora): (valenza -1..1, parola) o null se neutro o vecchio. */
    fun mood(now: Long): Pair<Double, String>? = synchronized(this) {
        if (moodAt == 0L || moodLabel.isEmpty()) return null
        val hours = (now - moodAt) / 3_600_000.0
        if (hours < 0 || hours > 6) return null
        val v = moodValence * exp(-hours / 3.0)
        if (abs(v) < 0.2) null else v to moodLabel
    }

    // ------------------------------------------------------------------ abitudini (memoria procedurale)
    /** La chiave dell'abitudine per un comando eseguito ([o] = l'esito); null = non è un'abitudine (domande, chiacchiere). */
    fun habitKey(command: String, o: Outcome): Pair<String, String>? = when (o) {
        is Outcome.Weather -> "meteo" to "il meteo"
        is Outcome.Media -> if (o.action == "show") "foto" to "le foto" else null
        is Outcome.Speak, is Outcome.Handled -> {
            val c = if (NOT_HABIT.containsMatchIn(command.trim())) "" else Remember.norm(command).split(' ').filter { it.isNotBlank() && it !in setOf("sam", "per", "favore", "grazie", "ok", "dai") }.take(7).joinToString(" ")
            if (c.isNotEmpty() && Remember.isCommand(command) && !(o is Outcome.Speak && o.text.startsWith("Non "))) c to "«${command.trim()}»" else null
        }
        else -> null
    }

    // le domande (che ore sono, quanto fa…) e le cose una tantum non sono abitudini
    private val NOT_HABIT = Lang.rx("^(?:che ore|quanto fa|calcola|dimmi|copia|esci|parla|guarda|guardami|fammi|fai|ricordami|cerca|cercami|controlla|scrivi|manda|invia|chiama|telefona)\\b")

    /** Un comando eseguito: si segna (per imparare le abitudini). */
    fun act(command: String, o: Outcome, now: Long) {
        val (key, label) = habitKey(command, o) ?: return
        synchronized(this) {
            acts.add(Act(key, label, command.trim(), now))
            val old = now - 60L * 86_400_000L
            acts.removeAll { it.at < old }
            while (acts.size > 800) acts.removeAt(0)
        }
        changed()
    }

    fun habits(now: Long): List<Habit> = synchronized(this) { Habits.detect(acts.toList(), now, zone) }

    /** L'abitudine di questo momento non ancora fatta né proposta oggi; null = nessuna. */
    fun habitDue(now: Long): Habit? {
        val today = day(now); val m = minuteOf(now)
        val list = habits(now)
        synchronized(this) {
            return list.firstOrNull { h ->
                (h.days.size >= 5 || today.dayOfWeek in h.days) && m in (h.minute - 40)..(h.minute + 50) &&
                    acts.none { it.key == h.key && day(it.at) == today } && kv["proposto:${h.key}"] != dayKey(today)
            }
        }
    }

    fun markOffered(h: Habit, now: Long) = put("proposto:${h.key}", dayKey(day(now)))

    // ------------------------------------------------------------------ impegni (memoria prospettica)
    fun plansOn(d: LocalDate): List<Plan> = synchronized(this) { plans.filter { it.day == d && it.state != "dropped" } }

    fun plansBetween(a: LocalDate, b: LocalDate): List<Plan> = synchronized(this) {
        plans.filter { !it.day.isBefore(a) && !it.day.isAfter(b) && it.state != "dropped" }.sortedWith(compareBy({ it.day }, { it.time ?: "99" }))
    }

    /** Un impegno di ieri (o degli ultimi 3 giorni) di cui non ha ancora chiesto com'è andata. */
    fun followUp(now: Long): Plan? = synchronized(this) {
        val today = day(now)
        plans.filter { it.state == "open" && it.day.isBefore(today) && ChronoUnit.DAYS.between(it.day, today) <= 3 }.maxByOrNull { it.day }
    }

    fun setPlanState(p: Plan, state: String) {
        synchronized(this) { val i = plans.indexOfFirst { it.id == p.id }; if (i >= 0) plans[i] = plans[i].copy(state = state) }
        changed()
    }

    /** Gli impegni scritti nelle note ("il dentista è martedì alle 16"): il giorno si conta da quando l'hai detto. */
    fun notePlans(memos: List<Memo>, d: LocalDate): List<String> =
        memos.filter { it.kind == Remember.NOTA && it.at > 0 }.mapNotNull { m ->
            val w = Plans.whenOf(m.text.lowercase(), m.at, zone) ?: return@mapNotNull null
            if (w.first == d) m.text else null
        }

    /** «Oggi hai il dentista alle 16 e vai a pescare.» null = niente. */
    fun dayText(d: LocalDate, memos: List<Memo>, label: String): String? {
        val ps = plansOn(d).filter { it.state == "open" || it.state == "asked" }.sortedBy { it.time ?: "99" }
            .map { Plans.you(it.text) + (it.time?.let { t -> " alle ${Plans.time(t)}" } ?: "") }
        val ns = notePlans(memos, d).filter { n -> ps.none { similar(it, n) } }.map { "«$it»" }
        val all = ps + ns
        if (all.isEmpty()) return null
        return "$label " + join(all) + "."
    }

    // ------------------------------------------------------------------ diario (memoria episodica)
    fun episodes(): List<Episode> = synchronized(this) { episodes.toList() }

    /**
     * Il sonno: le battute nuove diventano episodi del diario (solo quelle finite da almeno [quietMs]: una conversazione
     * ancora in corso aspetta), gli impegni vecchi si chiudono, le cose fatte troppo vecchie si lasciano andare.
     * Ritorna quanti episodi nuovi.
     */
    fun sleep(lines: List<Talk.Line>, owner: String?, now: Long, quietMs: Long = 30 * 60_000L): Int {
        val since = get("diario_fino")?.toLongOrNull() ?: 0L
        val fresh = lines.filter { it.at > since && it.sam.isNotEmpty() && !Feelings.crisis(it.you) }.sortedBy { it.at }
        val groups = ArrayList<List<Talk.Line>>()
        var cur = ArrayList<Talk.Line>()
        for (l in fresh) {
            if (cur.isNotEmpty() && l.at - cur.last().at > 45 * 60_000L) { groups.add(cur); cur = ArrayList() }
            cur.add(l)
        }
        if (cur.isNotEmpty() && now - cur.last().at >= quietMs) groups.add(cur)
        var made = 0
        var upTo = since
        synchronized(this) {
            for (g in groups) {
                episodes.add(Episodes.build(g, owner)); made++
                upTo = maxOf(upTo, g.last().at)
            }
            while (episodes.size > 1500) episodes.removeAt(0)
            val today = day(now)
            for (i in plans.indices) {
                val p = plans[i]
                if (p.state == "open" && ChronoUnit.DAYS.between(p.day, today) > 3) plans[i] = p.copy(state = "dropped")
            }
            val oldPlans = today.minusDays(120)
            plans.removeAll { it.day.isBefore(oldPlans) }
            if (upTo > since) kv["diario_fino"] = upTo.toString()
            kv["sonno"] = dayKey(today)
        }
        changed()
        return made
    }

    /** Un episodio da rifinire col cervello (il riassunto vero, invece delle parole chiave); null = nessuno. */
    fun unrefined(): Episode? = synchronized(this) { episodes.lastOrNull { !it.refined } }

    /** Il riassunto scritto dal cervello per [e] ([summary] null = non usabile: si tiene quello di prima). */
    fun refine(e: Episode, summary: String?) {
        synchronized(this) {
            val i = episodes.indexOfFirst { it.start == e.start && it.end == e.end }
            if (i >= 0) episodes[i] = episodes[i].copy(summary = summary ?: episodes[i].summary, refined = true)
        }
        changed()
    }

    /** Gli episodi che c'entrano con [question] (e col periodo che nomina), dal più vecchio. */
    fun recall(question: String, now: Long, max: Int = 4, olderThan: Long = 0L): List<Episode> {
        val period = MediaCommands.period(question, now, zone)
        val q = Remember.words(question).map { it.lowercase() }.filter { it.length > 2 }.toSet()
        val all = episodes().filter { olderThan == 0L || it.end < olderThan }
        val inPeriod = if (period != null) all.filter { it.end >= period.first && it.start < period.second } else all
        val scored = inPeriod.map { e ->
            val w = (e.topics + Remember.words(e.summary)).map { it.lowercase() }.toSet()
            val hits = q.count { it in w || w.any { x -> x.length >= 5 && it.length >= 5 && x.take(5) == it.take(5) } }
            val ageDays = (now - e.end) / 86_400_000.0
            e to (hits * 10 + e.importance - ageDays / 30.0 + (if (period != null) 5.0 else 0.0))
        }.filter { period != null || it.second >= 10 }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }.sortedBy { it.start }
    }

    /** Una riga di diario: «sabato 24 ottobre: Abbiamo parlato di …». */
    fun episodeLine(e: Episode, now: Long): String {
        val d = day(e.start); val today = day(now)
        val whenTxt = when (ChronoUnit.DAYS.between(d, today)) {
            0L -> "oggi"; 1L -> "ieri"
            else -> Lang.italianDate(d).let { if (d.year == today.year) it.removeSuffix(" ${d.year}") else it }
        }
        val t = Instant.ofEpochMilli(e.start).atZone(zone)
        return "$whenTxt alle ${"%d:%02d".format(t.hour, t.minute)}: ${e.summary}"
    }

    /** Le righe di diario per le domande sul passato («di cosa abbiamo parlato il mese scorso?»). null = niente. */
    fun recallBlock(question: String, now: Long, olderThan: Long): String? {
        val eps = recall(question, now, 6, olderThan)
        if (eps.isEmpty()) return null
        return "Dal diario (riassunti di conversazioni più vecchie):\n" + eps.joinToString("\n") { "- " + episodeLine(it, now) }
    }

    // ------------------------------------------------------------------ forza dei ricordi (memoria semantica)
    private fun memoKey(m: Memo) = Remember.norm(m.text).take(80)

    /** Questi ricordi sono stati usati (rispondere, venire in mente): diventano più forti. */
    fun touch(ms: Collection<Memo>, now: Long) {
        if (ms.isEmpty()) return
        synchronized(this) {
            for (m in ms) { val u = uses.getOrPut(memoKey(m)) { Use(0, 0L) }; u.count++; u.last = now }
            if (uses.size > 3000) uses.entries.sortedBy { it.value.last }.take(uses.size - 3000).forEach { uses.remove(it.key) }
        }
        changed()
    }

    /** Quanto è forte un ricordo adesso: uso, freschezza, importanza (come la memoria umana: si rafforza usandolo). */
    fun strength(m: Memo, now: Long): Double {
        val u = synchronized(this) { uses[memoKey(m)] }
        val days = { t: Long -> ((now - t).coerceAtLeast(0L)) / 86_400_000.0 }
        var s = 0.0
        if (m.at > 0) s += 1.2 * exp(-days(m.at) / 45.0)
        if (u != null) s += ln(1.0 + u.count) + 1.5 * exp(-days(u.last) / 30.0)
        if (m.kind == Remember.NOTA) s += 0.6
        if (Lang.rx("\\b(?:importante|non dimenticare|non scordare|fondamentale)\\b").containsMatchIn(m.text)) s += 1.0
        if (m.key.startsWith("conosci:")) s += 0.5
        return s
    }

    /** Sbiadito: imparato da solo, mai usato da 3 mesi (non si cancella: se ne riparli torna in mente). */
    fun faded(m: Memo, now: Long): Boolean {
        if (m.kind != Remember.FATTO || !m.key.startsWith(SelfLearn.KEY)) return false
        val u = synchronized(this) { uses[memoKey(m)] }
        val last = maxOf(m.at, u?.last ?: 0L)
        return last > 0 && now - last > 90L * 86_400_000L
    }

    /** I ricordi in ordine di forza, dal più debole (chi sceglie "i più recenti" prende così i più vivi); i sbiaditi fuori. */
    fun rank(memos: List<Memo>, now: Long): List<Memo> {
        val keep = memos.filter { it.kind != Remember.NOTA && it.kind != Remember.FATTO }
        val ranked = memos.filter { (it.kind == Remember.NOTA || it.kind == Remember.FATTO) && !faded(it, now) }.sortedBy { strength(it, now) }
        return keep + ranked
    }

    // ------------------------------------------------------------------ associazioni (memoria di lavoro)
    private val TIMEY = Lang.rx("\\b(?:oggi|domani|dopodomani|stasera|settimana|weekend|fine settimana|programma|programmi|impegn\\w*|appuntament\\w*|" +
        "agenda|devo|dobbiamo|organizz\\w*|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica|orari\\w*|tempo libero)\\b")
    private val PASTY = Lang.rx("\\b(?:ricordi|ti ricordi|ti ho detto|ti avevo detto|abbiamo parlato|avevamo parlato|l'altra volta|la volta scorsa|" +
        "quella volta|tempo fa|mesi fa|settimane fa|il mese scorso|l'anno scorso|di nuovo|ancora quella|come l'altra)\\b")

    /**
     * Quello che «viene in mente» davanti a una domanda (la memoria di lavoro): episodi passati che c'entrano, gli impegni
     * se la domanda parla di tempi, lo stato d'animo recente. Breve, per non riempire il cervello. null = niente.
     */
    fun context(question: String, now: Long): String? {
        val parts = ArrayList<String>()
        val older = now - Talk.KEEP_MS
        val eps = recall(question, now, 2, older).filter { e ->
            PASTY.containsMatchIn(question) || run {
                val q = Remember.words(question).map { it.lowercase() }.filter { it.length > 3 }.toSet()
                q.count { it in e.topics } >= 2
            }
        }
        if (eps.isNotEmpty()) parts.add("Ti viene in mente da conversazioni passate: " + eps.joinToString("; ") { episodeLine(it, now) })
        if (TIMEY.containsMatchIn(question)) {
            val today = day(now)
            val next = plansBetween(today, today.plusDays(7)).filter { it.state == "open" }.take(5)
            if (next.isNotEmpty()) parts.add("Impegni che ti ha detto: " + next.joinToString("; ") { p ->
                relDay(p.day, today) + ": " + Plans.you(p.text) + (p.time?.let { " alle ${Plans.time(it)}" } ?: "") })
        }
        mood(now)?.let { (v, label) ->
            parts.add(if (v < 0) "Poco fa ha detto di sentirsi $label: tienine conto nel tono (con calore, senza fare lo psicologo e senza ripeterglielo)."
                else "Poco fa era $label: puoi essere allegro anche tu.")
        }
        return if (parts.isEmpty()) null else "(" + parts.joinToString(" ") + ")"
    }

    fun relDay(d: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "oggi"; 1L -> "domani"; 2L -> "dopodomani"; -1L -> "ieri"
        in 3L..6L -> Plans.DAYS[d.dayOfWeek.value - 1]
        in 7L..13L -> "${Plans.DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${Plans.MONTHS[d.monthValue - 1]}"
        else -> "${d.dayOfMonth} ${Plans.MONTHS[d.monthValue - 1]}"
    }

    // ------------------------------------------------------------------ il saluto del mattino (agente pianificato)
    /** Cosa chiedere alla fine (una cosa sola): com'è andata un impegno, oppure un'abitudine da fare. */
    sealed class Ask { class FollowUp(val plan: Plan) : Ask(); class DoHabit(val habit: Habit) : Ask() }
    class Digest(val text: String, val ask: Ask?)

    /** «Buongiorno»: la giornata (impegni, note con la data), cosa ha imparato stanotte, e una domanda. */
    fun digest(owner: String?, memos: List<Memo>, now: Long, full: Boolean = true): Digest {
        val today = day(now)
        val h = Instant.ofEpochMilli(now).atZone(zone).hour
        val hello = when (h) { in 5..12 -> "Buongiorno"; in 13..17 -> "Buon pomeriggio"; else -> "Buonasera" } +
            (owner?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + "!"
        val sb = StringBuilder(hello)
        if (full) sb.append(" Oggi è ").append(Lang.italianDate(today).removeSuffix(" ${today.year}")).append('.')
        val todayTxt = dayText(today, memos, "Oggi")
        sb.append(' ').append(todayTxt ?: if (full) "Per oggi non mi hai detto impegni." else "")
        if (full) dayText(today.plusDays(1), memos, "Domani")?.let { sb.append(' ').append(it) }
        // quello che ha imparato da solo dall'ultima volta
        val since = get("saluto_at")?.toLongOrNull() ?: (now - 86_400_000L)
        val learned = memos.filter { it.kind == Remember.FATTO && it.key.startsWith(SelfLearn.KEY) && it.at > since }.takeLast(2)
        if (learned.isNotEmpty()) sb.append(" Ripensandoci mi sono segnato che: ").append(join(learned.map { "«${it.text}»" })).append('.')
        val f = followUp(now)
        val habit = if (f == null) habitDue(now) else null
        val ask: Ask? = when {
            f != null -> { sb.append(' ').append(Plans.past(f.text, relDay(f.day, today))).append(": com'è andata?"); Ask.FollowUp(f) }
            habit != null -> { sb.append(' ').append(habitQuestion(habit)); Ask.DoHabit(habit) }
            else -> null
        }
        put("saluto_at", now.toString()); put("salutato", dayKey(today))
        return Digest(sb.toString().replace(Regex("\\s+"), " ").trim(), ask)
    }

    fun habitQuestion(h: Habit): String {
        val part = when (h.minute / 60) { in 4..11 -> "mattina"; in 12..13 -> "giorno"; in 14..17 -> "pomeriggio"; else -> "sera" }
        val whenTxt = if (h.days.size >= 5) "Come ogni $part" else "Come ogni ${h.days.sortedBy { it.value }.joinToString(" e ") { Plans.DAYS[it.value - 1] }}"
        return if (h.key == "meteo" || h.key == "foto") "$whenTxt, vuoi ${h.label}?" else "$whenTxt: ${h.label}. Lo faccio?"
    }

    /** Al risveglio («SAM» e basta): il saluto del primo risveglio del mattino, un «com'è andata?», un'abitudine. null = niente. */
    fun wakeOffer(owner: String?, memos: List<Memo>, now: Long): Digest? {
        if (!proactive) return null
        val today = day(now); val h = Instant.ofEpochMilli(now).atZone(zone).hour
        if (get("salutato") != dayKey(today) && h in 5..11) {
            val d = digest(owner, memos, now, full = false)
            if (d.ask != null || dayText(today, memos, "Oggi") != null) return d
        }
        followUp(now)?.let { f -> return Digest(Plans.past(f.text, relDay(f.day, today)) + ": com'è andata?", Ask.FollowUp(f)) }
        habitDue(now)?.let { hb -> return Digest(habitQuestion(hb), Ask.DoHabit(hb)) }
        return null
    }

    // ------------------------------------------------------------------ metacognizione
    fun source(kind: String, detail: String, question: String, now: Long) { lastSource = Source(kind, detail, question, now) }

    // ------------------------------------------------------------------ la mente vista da fuori
    /** Il pannello «La mente»: cosa sa, impegni, abitudini, diario, stato d'animo. Markdown. */
    fun report(memos: List<Memo>, owner: String?, now: Long): String {
        val sb = StringBuilder()
        val today = day(now)
        sb.append("## Come ti vedo\n")
        sb.append(mood(now)?.let { (v, l) -> (if (v < 0) "Poco fa mi sei sembrato **$l**." else "Poco fa eri **$l**.") } ?: "Niente di particolare: tranquillo.").append("\n\n")
        val facts = memos.filter { it.kind == Remember.NOTA || it.kind == Remember.FATTO }
        val strong = facts.filter { !faded(it, now) }.sortedByDescending { strength(it, now) }
        sb.append("## Cosa so di te (${facts.size} ricordi)\n")
        if (strong.isEmpty()) sb.append("Ancora niente: raccontami di te, o di' «facciamo conoscenza».\n")
        for (m in strong.take(12)) sb.append("- ").append(m.text).append(if (m.kind == Remember.NOTA) " · _me l'hai chiesto tu_" else "").append('\n')
        val faded = facts.count { faded(it, now) }
        if (faded > 0) sb.append("- _e $faded ricordi sbiaditi: non li uso da mesi, ma se ne parli tornano in mente_\n")
        sb.append("\n## I tuoi impegni\n")
        val next = plansBetween(today, today.plusDays(14)).filter { it.state == "open" }
        if (next.isEmpty()) sb.append("Nessuno. Quando mi dici «domani ho il dentista alle 16» me lo segno da solo.\n")
        for (p in next) sb.append("- ").append(relDay(p.day, today)).append(": ").append(Plans.you(p.text)).append(p.time?.let { " alle ${Plans.time(it)}" } ?: "").append('\n')
        sb.append("\n## Le tue abitudini\n")
        val hs = habits(now)
        if (hs.isEmpty()) sb.append("Ancora nessuna: le imparo quando chiedi le stesse cose alla stessa ora per qualche giorno.\n")
        for (h in hs) sb.append("- ").append(Habits.describe(h)).append('\n')
        sb.append("\n## Il diario\n")
        val eps = episodes().takeLast(8).asReversed()
        if (eps.isEmpty()) sb.append("Ancora vuoto: lo scrivo quando sono fermo, riordinando le nostre conversazioni.\n")
        for (e in eps) sb.append("- ").append(episodeLine(e, now)).append('\n')
        sb.append("\n## Come ragiono\n")
        sb.append("- Alle domande difficili ci penso prima di rispondere: **").append(if (thinkAuto) "sì" else "no").append("**\n")
        sb.append("- Ti propongo le cose da solo (saluto del mattino, abitudini, «com'è andata?»): **").append(if (proactive) "sì" else "no").append("**\n")
        get("sonno")?.let { sb.append("- Ultimo riordino dei ricordi: ").append(relDay(LocalDate.parse(it), today)).append('\n') }
        return sb.toString()
    }

    // ------------------------------------------------------------------ su file
    fun toText(): String = synchronized(this) {
        val sb = StringBuilder("#MENTE\t1\n")
        for ((k, v) in kv) sb.append("K\t").append(c(k)).append('\t').append(c(v)).append('\n')
        if (moodAt > 0) sb.append("M\t").append(moodAt).append('\t').append(moodValence).append('\t').append(c(moodLabel)).append('\n')
        for (e in episodes) sb.append("E\t${e.start}\t${e.end}\t${e.importance}\t${e.valence}\t${e.lines}\t${if (e.refined) 1 else 0}\t${c(e.topics.joinToString(","))}\t${c(e.summary)}\n")
        for (p in plans) sb.append("P\t${p.id}\t${p.day}\t${p.time ?: ""}\t${p.state}\t${p.created}\t${c(p.text)}\n")
        for (a in acts) sb.append("A\t${a.at}\t${c(a.key)}\t${c(a.label)}\t${c(a.command)}\n")
        for ((k, u) in uses) sb.append("U\t${c(k)}\t${u.count}\t${u.last}\n")
        sb.toString()
    }

    private fun c(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun load(text: String) {
        synchronized(this) {
            episodes.clear(); plans.clear(); acts.clear(); uses.clear(); kv.clear()
            for (row in text.lines()) {
                val p = row.split('\t')
                try {
                    when (p.getOrNull(0)) {
                        "K" -> if (p.size >= 3) kv[p[1]] = p[2]
                        "M" -> if (p.size >= 4) { moodAt = p[1].toLong(); moodValence = p[2].toDouble(); moodLabel = p[3] }
                        "E" -> if (p.size >= 9) episodes.add(Episode(p[1].toLong(), p[2].toLong(), p[8], p[7].split(',').filter { it.isNotBlank() },
                            p[4].toDouble(), p[3].toInt(), p[5].toInt(), p[6] == "1"))
                        "P" -> if (p.size >= 7) plans.add(Plan(p[1].toLong(), p[6], LocalDate.parse(p[2]), p[3].ifEmpty { null }, p[4], p[5].toLong()))
                        "A" -> if (p.size >= 5) acts.add(Act(p[2], p[3], p[4], p[1].toLong()))
                        "U" -> if (p.size >= 4) uses[p[1]] = Use(p[2].toInt(), p[3].toLong())
                    }
                } catch (e: Exception) {}
            }
        }
    }

    companion object {
        fun join(items: List<String>): String = when (items.size) {
            0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
        }
    }
}

/** Le emozioni nelle frasi (italiano). Kotlin puro. */
object Feelings {
    data class Feeling(val label: String, val valence: Double, val intensity: Double)

    private const val ME = "(?:(?:io )?(?:sono|mi sento|mi sento proprio|sto|oggi sono|sono proprio|sono un po'|sono molto|sono davvero|sono troppo|" +
        "sono così|sono parecchio|sono abbastanza|che|quanto sono|mi sento un po'|mi sento molto|oggi mi sento|sono veramente|sono tanto)\\s+)"
    private val LEX: List<Triple<Regex, String, Double>> = listOf(
        Triple(Lang.rx("\\b${ME}(?:triste|tristissim[oa]|giù|giù di morale|depress[oa]|abbattut[oa]|demoralizzat[oa]|a terra)\\b"), "triste", -0.7),
        Triple(Lang.rx("\\b${ME}(?:stanc[oa]|stanchissim[oa]|distrutt[oa]|esaust[oa]|sfinit[oa]|a pezzi|cotto|cotta|morto di sonno|morta di sonno)\\b"), "stanco", -0.4),
        Triple(Lang.rx("\\b${ME}(?:arrabbiat[oa]|incazzat[oa]|furios[oa]|nervos[oa]|irritat[oa]|incavolat[oa]|arrabbiatissim[oa])\\b"), "arrabbiato", -0.6),
        Triple(Lang.rx("\\b${ME}(?:preoccupat[oa]|in ansia|ansios[oa]|agitat[oa]|stressat[oa]|sotto stress|in pensiero|in agitazione|teso|tesa)\\b|" +
            "\\bho (?:l'ansia|ansia|paura|tanta paura|un po' di paura|un brutto presentimento)\\b"), "preoccupato", -0.5),
        Triple(Lang.rx("\\b(?:mi sento|mi sento così|mi sento tanto|mi sento molto|sono così|sono tanto|sono molto|sono troppo) (?:sol[oa]|isolat[oa])\\b|" +
            "\\bsono sol[oa] al mondo\\b|\\bnessuno mi (?:capisce|vuole bene|cerca|ascolta)\\b"), "solo", -0.6),
        Triple(Lang.rx("\\b(?:sto male|non sto bene|sto poco bene|ho la febbre|sono malat[oa]|mi sento male|ho mal di)\\b"), "poco bene", -0.5),
        Triple(Lang.rx("\\b${ME}(?:delus[oa]|amareggiat[oa]|ferit[oa])\\b"), "deluso", -0.5),
        Triple(Lang.rx("\\b${ME}(?:annoiat[oa]|stuf[oa])\\b|\\bche noia\\b"), "annoiato", -0.25),
        Triple(Lang.rx("\\b(?:che giornata (?:pesante|orribile|terribile|storta|no|da dimenticare|di merda)|giornata (?:pesante|orribile|terribile|storta|da dimenticare))\\b"), "giù", -0.5),
        Triple(Lang.rx("\\b${ME}(?:felice|felicissim[oa]|content[oa]|contentissim[oa]|allegr[oa]|di buon umore|al settimo cielo)\\b|\\bsto (?:benissimo|alla grande|da dio|una favola)\\b"), "felice", 0.7),
        Triple(Lang.rx("\\b${ME}(?:entusiasta|gasat[oa]|carichissim[oa]|emozionat[oa]|eccitat[oa])\\b"), "entusiasta", 0.8),
        Triple(Lang.rx("\\b${ME}(?:rilassat[oa]|tranquill[oa]|seren[oa]|in pace)\\b"), "sereno", 0.4),
        Triple(Lang.rx("\\b${ME}(?:soddisfatt[oa]|fier[oa]|orgoglios[oa])\\b|\\bce l'ho fatta\\b|\\bè andata benissimo\\b"), "soddisfatto", 0.6),
        Triple(Lang.rx("\\b(?:che (?:bella|splendida|fantastica|magnifica) giornata|giornata (?:fantastica|stupenda|bellissima|perfetta))\\b"), "di buon umore", 0.6),
    )
    private val NEG = Lang.rx("\\bnon (?:sono|mi sento|sto|ho)\\b(?:\\s+\\S+){0,2}\\s*$")
    private val STRONG = Lang.rx("\\b(?:molto|troppo|tantissimo|davvero|proprio|veramente|così|parecchio|da morire|da matti)\\b|issim[oa]\\b|!")

    /** Lo stato d'animo detto in prima persona ("sono stanco morto", "che giornata pesante"); null = nessuno. */
    fun read(text: String): Feeling? {
        val t = text.lowercase()
        for ((rx, label, v) in LEX) {
            val m = rx.find(t) ?: continue
            if (NEG.containsMatchIn(t.substring(0, m.range.first + minOf(m.value.length, 12)))) continue
            if (Lang.rx("\\bnon\\s+$").containsMatchIn(t.substring(0, m.range.first))) continue
            val intensity = if (STRONG.containsMatchIn(t)) 1.0 else 0.7
            return Feeling(label, v, intensity)
        }
        return null
    }

    private val CRISIS = Lang.rx(
        "\\b(?:voglio|vorrei|preferirei|penso di|sto pensando di|ho voglia di) (?:morire|farla finita|uccidermi|ammazzarmi|togliermi la vita|" +
        "suicidarmi|sparire per sempre|farmi del male)\\b|" +
        "\\bmi (?:voglio|vorrei) (?:uccidere|ammazzare|togliere la vita|fare del male|suicidare)\\b|\\bfarla finita (?:con la vita|per sempre|una volta per tutte)\\b|" +
        "\\b(?:mi suicido|mi suiciderò|ho (?:dei )?pensieri suicid\\w+|penso (?:spesso )?al suicidio|sto pensando al suicidio)\\b|" +
        "\\bnon (?:voglio|ce la faccio) più (?:a )?vivere\\b|\\bnon ha (?:più )?senso (?:vivere|la mia vita)\\b|" +
        "\\bmeglio (?:se )?(?:fossi|sono) mort[oa]\\b"
    )
    private val JOKE = Lang.rx("\\b(?:dalle risate|dal ridere|di risate|dalla fame|di fame|di sonno|dal sonno|di caldo|dal caldo|di freddo|dal freddo|di lavoro|dalla noia|di noia)\\b")

    /** Parole di chi sta pensando di farsi del male: SAM risponde subito con chi può aiutare. */
    fun crisis(text: String): Boolean { val t = text.lowercase(); return CRISIS.containsMatchIn(t) && !JOKE.containsMatchIn(t) }

    /** La risposta di SAM: vicina, breve, con i numeri giusti (Italia). */
    fun crisisReply(): String =
        "Mi dispiace tanto che tu stia così, e sono contento che tu me l'abbia detto. Non sei solo. Se sei in pericolo adesso chiama il 112. " +
        "Puoi parlare subito con qualcuno al Telefono Amico, 02 2327 2327, o ai Samaritans, 06 7720 8977. Vuoi che chiami il Telefono Amico?"
}

/** Gli impegni detti di passaggio: «domani ho il dentista alle 16», «sabato vado a pescare». Kotlin puro. */
object Plans {
    data class Found(val text: String, val day: LocalDate, val time: String?)

    val DAYS = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val NUMW = mapOf("un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6,
        "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "quindici" to 15)

    private val WHEN = Lang.rx(
        "(?:entro |per )?(?:dopodomani|domani(?: mattina| pomeriggio| sera)?|stasera|stanotte|oggi pomeriggio|questo pomeriggio|oggi|" +
        "(?:il |la )?(?:prossimo |prossima )?(?:lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica)(?: prossim[oa])?|" +
        "(?:il |nel )?(?:prossimo )?(?:weekend|fine settimana)(?: prossimo)?|la settimana prossima|la prossima settimana|" +
        "(?:tra|fra) (?:\\d{1,2}|un|uno|una|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|quindici) giorni|" +
        "(?:il |l'|entro il |entro l')?\\d{1,2} (?:gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre))"
    )
    private val TIME = Lang.rx("\\b(?:alle|per le|verso le|dalle) (\\d{1,2})(?:[:.](\\d{2}))?(?: e (mezza|un quarto|mezzo))?(?: (di sera|del pomeriggio|di pomeriggio|di mattina|del mattino|di notte))?\\b")
    private const val ART = "(?:un |una |uno |un'|il |la |lo |l'|i |le |gli |dal |dalla |dallo |al |alla )?"
    private const val NOUNS = "appuntamento|visita|visita medica|esame|esami|colloquio|riunione|partita|dentista|medico|dottore|controllo|analisi|udienza|turno|" +
        "lezione|corso|cena|pranzo|festa|compleanno|matrimonio|viaggio|volo|aereo|treno|concerto|allenamento|gara|interrogazione|consegna|scadenza|" +
        "incontro|meeting|call|saggio|recita|torneo|trasferta|intervento|operazione|vaccino|patente|revisione|tagliando|meccanico|parrucchiere|barbiere|" +
        "commercialista|avvocato|notaio|banca|posta|ufficio|colloqui|conferenza|esibizione|spettacolo|cerimonia|battesimo|comunione|cresima|funerale|trasloco|ferie"
    private val PLAN = Lang.rx(
        "^(?:(?:e|poi|io|allora|invece|che|perché|perche|ma|anche|sai che|sai|ah|comunque)\\s+)*(?:" +
        "(?:ho|abbiamo) $ART(?:$NOUNS)\\b.*|" +
        "(?:vado|andiamo) (?:a|ad|al|alla|allo|ai|alle|agli|all'|in|nel|nella|dal|dalla|dallo|dai|da|verso|fuori|via|al mare|in montagna)\\b.*|" +
        "(?:devo|dobbiamo) (?:andare|portare|pagare|consegnare|finire|comprare|chiamare|prenotare|ritirare|fare|preparare|studiare|partire|lavorare|" +
        "incontrare|vedere|mandare|spedire|rinnovare|presentare|accompagnare|prendere|sistemare|aggiustare|riparare|pulire|cucinare|organizzare)\\b.*|" +
        "(?:esco|usciamo|parto|partiamo|torno|torniamo|lavoro|gioco|giochiamo|faccio|facciamo|incontro|vedo|vediamo|festeggio|festeggiamo|" +
        "mi sposo|ci sposiamo|traslochiamo|trasloco|ceniamo|pranziamo)\\b.*|" +
        "c'è $ART(?:$NOUNS)\\b.*|" +
        "(?:mio|mia|i miei|le mie) [\\p{L}']+ (?:ha|hanno|fa|fanno|compie|compiono|parte|partono|torna|tornano|va|vanno|si sposa|esce)\\b.*|" +
        "è il compleanno (?:di|del|della|dello) .+|compie (?:gli )?anni .+)$"
    )
    private val SKIP = Lang.rx("\\b(?:ricord\\w*|promemoria|sveglia|timer|segnati|annota|appunta)\\b")
    private val QUESTION = Lang.rx("^(?:cosa|che cosa|che|quando|dove|come|perché|chi|quale|quali|quanto|quanti|hai|sai se|secondo te)\\b")

    /** Gli impegni in una frase (al futuro: domani, sabato, il 5 novembre…). Domande, ricordi espliciti e negazioni no. */
    fun extract(text: String, now: Long, zone: ZoneId): List<Found> {
        if (text.contains('?') || SKIP.containsMatchIn(text)) return emptyList()
        val out = ArrayList<Found>()
        for (clause0 in text.lowercase().split(Regex("[.;!\\n]+|,\\s+(?=(?:e |poi |ma |invece )?(?:domani|dopodomani|stasera|oggi|lunedì|martedì|mercoledì|giovedì|venerdì|sabato|domenica|il \\d))"))) {
            val clause = clause0.trim().trim(',', ' ')
            if (clause.isEmpty() || QUESTION.containsMatchIn(clause)) continue
            val w = WHEN.find(clause) ?: continue
            val day = dayOf(w.value.trim(), now, zone) ?: continue
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            if (day.isBefore(today)) continue
            var rest = (clause.substring(0, w.range.first) + " " + clause.substring(w.range.last + 1)).replace(Regex("\\s+"), " ").trim().trim(',', ' ')
            val tm = TIME.find(rest)
            val time = tm?.let { m ->
                var h = m.groupValues[1].toInt(); var min = m.groupValues[2].toIntOrNull() ?: 0
                when (m.groupValues[3]) { "mezza", "mezzo" -> min = 30; "un quarto" -> min = 15 }
                if (m.groupValues[4].let { it.contains("sera") || it.contains("pomeriggio") } && h < 12) h += 12
                if (h > 23 || min > 59) null else "%d:%02d".format(h, min)
            }
            if (tm != null) rest = (rest.substring(0, tm.range.first) + " " + rest.substring(tm.range.last + 1)).replace(Regex("\\s+"), " ").trim().trim(',', ' ')
            // il pezzo con l'impegno (le altre parti della frase restano fuori: "…, che giornata pesante")
            val seg = rest.split(Regex("\\s*,\\s*|\\s+(?:ma|però|pero|anche se|perché|perche)\\s+"))
                .map { it.trim().replace(Regex("^(?:(?:e|poi|io|allora|invece|che|perché|perche|ma|anche|sai che|sai|ah|comunque)\\s+)+"), "") }
                .firstOrNull { it.isNotEmpty() && PLAN.matches(it) } ?: continue
            var t = seg.trim().trimEnd(',', ' ')
            if (t.split(' ').size > 12) t = t.split(' ').take(12).joinToString(" ")
            if (t.length < 4) continue
            out.add(Found(t, day, time))
        }
        return out
    }

    /** «16:00» → «16», «16:30» → «16:30» (come si dice). */
    fun time(t: String): String = t.removeSuffix(":00")

    /** Il giorno di un'espressione di tempo, contando da [now]. null = non è un giorno. */
    fun dayOf(w0: String, now: Long, zone: ZoneId): LocalDate? {
        val w = w0.lowercase().removePrefix("entro ").removePrefix("per ").trim()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        when {
            w.startsWith("dopodomani") -> return today.plusDays(2)
            w.startsWith("domani") -> return today.plusDays(1)
            w.startsWith("stasera") || w.startsWith("stanotte") || w.startsWith("oggi") || w.startsWith("questo pomeriggio") -> return today
            w.contains("settimana prossima") || w.contains("prossima settimana") -> return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            w.contains("weekend") || w.contains("fine settimana") -> return if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY)
                today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)) else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        }
        Regex("(?:tra|fra) (\\S+) giorni").find(w)?.let { m -> val n = m.groupValues[1].toIntOrNull() ?: NUMW[m.groupValues[1]] ?: return null; return today.plusDays(n.toLong()) }
        for ((i, d) in DAYS.withIndex()) if (w.contains(d)) return today.with(TemporalAdjusters.next(DayOfWeek.of(i + 1)))
        Regex("(\\d{1,2}) (${MONTHS.joinToString("|")})").find(w)?.let { m ->
            val dd = m.groupValues[1].toInt(); val mm = MONTHS.indexOf(m.groupValues[2]) + 1
            var d = try { LocalDate.of(today.year, mm, dd) } catch (e: Exception) { return null }
            if (d.isBefore(today)) d = d.plusYears(1)
            return d
        }
        return null
    }

    /** Il giorno nominato in una nota ("il dentista è martedì alle 16"), contando da quando l'hai detta. */
    fun whenOf(text: String, at: Long, zone: ZoneId): Pair<LocalDate, String>? {
        val w = WHEN.find(text) ?: return null
        val d = dayOf(w.value, at, zone) ?: return null
        return d to w.value
    }

    private val YOU = listOf(
        "ho" to "hai", "abbiamo" to "avete", "vado" to "vai", "andiamo" to "andate", "devo" to "devi", "dobbiamo" to "dovete", "esco" to "esci",
        "usciamo" to "uscite", "parto" to "parti", "partiamo" to "partite", "torno" to "torni", "torniamo" to "tornate", "lavoro" to "lavori",
        "gioco" to "giochi", "giochiamo" to "giocate", "faccio" to "fai", "facciamo" to "fate", "incontro" to "incontri", "vedo" to "vedi",
        "vediamo" to "vedete", "festeggio" to "festeggi", "festeggiamo" to "festeggiate", "trasloco" to "traslochi", "traslochiamo" to "traslocate",
        "mi sposo" to "ti sposi", "ci sposiamo" to "vi sposate", "ceniamo" to "cenate", "pranziamo" to "pranzate"
    )

    /** In seconda persona: "vado a pescare con mio fratello" → "vai a pescare con tuo fratello". */
    fun you(text: String): String {
        var t = text.trim()
        for ((a, b) in YOU) if (t == a || t.startsWith("$a ")) { t = b + t.substring(a.length); break }
        return t.replace(Regex("\\bmio\\b"), "tuo").replace(Regex("\\bmia\\b"), "tua").replace(Regex("\\bmiei\\b"), "tuoi").replace(Regex("\\bmie\\b"), "tue")
            .replace(Regex("\\bcon me\\b"), "con te")
    }

    private val PAST = listOf(
        "ho" to "avevi", "abbiamo" to "avevate", "vado" to "dovevi andare", "andiamo" to "dovevate andare", "devo" to "dovevi",
        "dobbiamo" to "dovevate", "esco" to "dovevi uscire", "usciamo" to "dovevate uscire", "parto" to "dovevi partire", "partiamo" to "dovevate partire",
        "torno" to "dovevi tornare", "torniamo" to "dovevate tornare", "lavoro" to "dovevi lavorare", "gioco" to "dovevi giocare",
        "giochiamo" to "dovevate giocare", "faccio" to "dovevi fare", "facciamo" to "dovevate fare", "incontro" to "dovevi incontrare",
        "vedo" to "dovevi vedere", "vediamo" to "dovevate vedere", "festeggio" to "dovevi festeggiare", "festeggiamo" to "dovevate festeggiare",
        "trasloco" to "dovevi traslocare", "traslochiamo" to "dovevate traslocare", "ceniamo" to "dovevate cenare", "pranziamo" to "dovevate pranzare",
        "c'è" to "c'era", "mi sposo" to "ti sposavi", "ci sposiamo" to "vi sposavate"
    )
    private val PAST3 = mapOf("ha" to "aveva", "hanno" to "avevano", "fa" to "faceva", "fanno" to "facevano", "compie" to "compiva", "compiono" to "compivano",
        "parte" to "partiva", "partono" to "partivano", "torna" to "tornava", "tornano" to "tornavano", "va" to "andava", "vanno" to "andavano",
        "si sposa" to "si sposava", "esce" to "usciva")

    /** «Ieri avevi il dentista», «Sabato dovevi andare a pescare»: per chiedere com'è andata. */
    fun past(text: String, whenTxt: String): String {
        val t = text.trim()
        val w = whenTxt.replaceFirstChar { it.uppercase() }
        for ((a, b) in PAST) if (t == a || t.startsWith("$a ")) return "$w " + you(b + t.substring(a.length)).trim()
        Regex("^(mio|mia|i miei|le mie) ([\\p{L}']+) (si sposa|ha|hanno|fa|fanno|compie|compiono|parte|partono|torna|tornano|va|vanno|esce)\\b(.*)$").find(t)?.let { m ->
            val poss = mapOf("mio" to "tuo", "mia" to "tua", "i miei" to "i tuoi", "le mie" to "le tue")[m.groupValues[1]] ?: m.groupValues[1]
            val tail = m.groupValues[4].trim()
            return "$w $poss ${m.groupValues[2]} ${PAST3[m.groupValues[3]] ?: m.groupValues[3]}${if (tail.isEmpty()) "" else " " + you(tail)}".trim()
        }
        return "$w avevi in programma: «${you(t)}»"
    }
}

/** Le abitudini: le stesse cose alla stessa ora per più giorni. Kotlin puro. */
object Habits {
    /**
     * Per ogni comando ripetuto: la finestra di 90 minuti con più giorni diversi; se sono almeno 3 giorni (nelle ultime
     * 4 settimane) è un'abitudine. Tutti i giorni (5 o più giorni della settimana diversi) oppure certi giorni.
     */
    fun detect(acts: List<Mind.Act>, now: Long, zone: ZoneId): List<Mind.Habit> {
        val from = now - 28L * 86_400_000L
        val out = ArrayList<Mind.Habit>()
        for ((key, list0) in acts.filter { it.at in from..now }.groupBy { it.key }) {
            val list = list0.map { a -> a to Instant.ofEpochMilli(a.at).atZone(zone) }
            if (list.map { it.second.toLocalDate() }.distinct().size < 3) continue
            val mins = list.map { it.second.hour * 60 + it.second.minute }
            var best: List<Pair<Mind.Act, java.time.ZonedDateTime>> = emptyList()
            for (m in mins.distinct()) {
                val win = list.filter { val x = it.second.hour * 60 + it.second.minute; x in m..(m + 90) }
                if (win.map { it.second.toLocalDate() }.distinct().size > best.map { it.second.toLocalDate() }.distinct().size) best = win
            }
            val days = best.map { it.second.toLocalDate() }.distinct()
            if (days.size < 3) continue
            val weekdays = best.map { it.second.dayOfWeek }.toSet()
            val sorted = best.map { it.second.hour * 60 + it.second.minute }.sorted()
            val median = sorted[sorted.size / 2]
            // 3 giorni della settimana diversi o più: tutti i giorni; altrimenti certi giorni (il sabato, il lunedì e il giovedì…)
            val daily = weekdays.size >= 3
            val set = if (daily) DayOfWeek.values().toSet() else weekdays
            val last = best.maxByOrNull { it.first.at }!!.first
            out.add(Mind.Habit(key, last.label, last.command, median, set, best.size))
        }
        return out.sortedBy { it.minute }
    }

    fun describe(h: Mind.Habit): String {
        val time = "%d:%02d".format(h.minute / 60, h.minute % 60)
        val days = if (h.days.size >= 5) "ogni giorno" else "il " + h.days.sortedBy { it.value }.joinToString(" e il ") { Plans.DAYS[it.value - 1] }
        return "$days verso le $time: ${h.label} (${h.count} volte)"
    }
}

/** Gli episodi del diario: dalle battute al riassunto. Kotlin puro. */
object Episodes {
    private val SKIP_TOPIC = setOf("sam", "ciao", "grazie", "bene", "va", "fare", "dire", "cosa", "fatto", "detto", "sono", "anche", "ancora", "molto",
        "tutto", "niente", "oggi", "domani", "ieri", "adesso", "allora", "quindi", "però", "pero", "certo", "okay", "vuoi", "puoi", "posso", "voglio",
        "dimmi", "sapere", "vedere", "parlare", "parliamo", "risposta", "domanda", "momento", "volta", "volte", "modo", "cose", "cosa", "qualcosa")

    /** Le parole chiave di un gruppo di battute (quelle tue contano il doppio). */
    fun topics(lines: List<Talk.Line>, owner: String?, max: Int = 5): List<String> {
        val skip = SKIP_TOPIC + setOfNotNull(owner?.let { Remember.norm(it) }, Remember.norm(Identity.name))
        val count = HashMap<String, Int>()
        for (l in lines) {
            for (w in Remember.words(l.you)) if (w.length >= 4 && w !in skip && w.none { it.isDigit() }) count[w] = (count[w] ?: 0) + 2
            for (w in Remember.words(l.sam).take(40)) if (w.length >= 5 && w !in skip && w.none { it.isDigit() }) count[w] = (count[w] ?: 0) + 1
        }
        return count.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length }).take(max).map { it.key }
    }

    fun build(lines: List<Talk.Line>, owner: String?): Mind.Episode {
        val tops = topics(lines, owner)
        val feelings = lines.mapNotNull { Feelings.read(it.you) }
        val valence = if (feelings.isEmpty()) 0.0 else feelings.sumOf { it.valence * it.intensity } / feelings.size
        val important = lines.count { Lang.rx("\\b(?:ricordati|ricorda|importante|non dimenticare|domani|devo)\\b").containsMatchIn(it.you) }
        val importance = minOf(10, lines.size / 3 + (abs(valence) * 4).toInt() + important * 2)
        val summary = if (tops.isEmpty()) "Ci siamo scambiati qualche battuta." else "Abbiamo parlato di " + Mind.join(tops.take(4)) + "."
        return Mind.Episode(lines.first().at, lines.last().at, summary, tops, valence, importance, lines.size)
    }

    /** Le istruzioni per il riassunto vero (le scrive il cervello quando SAM è fermo). */
    fun prompt(owner: String?): String {
        val who = owner?.takeIf { it.isNotBlank() } ?: "l'utente"
        return "Ti vengono date alcune battute tra $who e il suo assistente. Scrivi il diario dell'assistente: UNA frase, al massimo 25 parole, " +
            "che comincia con «Abbiamo», con le cose concrete dette (nomi, cose da fare, decisioni). Solo quello che c'è nelle battute, " +
            "niente di inventato, niente commenti."
    }

    fun question(lines: List<Talk.Line>, owner: String?): String {
        val who = owner?.takeIf { it.isNotBlank() } ?: "Utente"
        return lines.takeLast(16).joinToString("\n") { "$who: «${it.you.take(200)}» — assistente: «${it.sam.take(200)}»" }
    }

    /** Il riassunto del cervello, se è una frase sola e sta nelle parole dette (niente inventato). null = non usabile. */
    fun accept(raw: String?, lines: List<Talk.Line>): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.replace(Regex("(?s)<think>.*?</think>"), " ").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.trim('«', '»', '"', '*', ' ').removePrefix("Diario:").trim()
        if (!t.startsWith("Abbiamo", ignoreCase = true) || t.length > 220 || t.length < 15) return null
        val said = Remember.words(lines.joinToString(" ") { it.you + " " + it.sam }).toSet()
        val w = Remember.words(t).filter { it.length >= 4 && it !in setOf("abbiamo", "parlato", "deciso", "detto") }.distinct()
        if (w.isEmpty()) return null
        val hit = w.count { x -> said.any { it == x || (it.length >= 5 && x.length >= 5 && it.take(5) == x.take(5)) } }
        return if (hit * 3 >= w.size * 2) t.trimEnd('.') + "." else null
    }
}

/** Pensiero veloce e lento: le domande che meritano un ragionamento prima della risposta. Kotlin puro. */
object Thinking {
    private val HARD = Lang.rx(
        "\\b(?:conviene|mi conviene|è meglio|cosa mi consigli|cosa consigli|quale scelgo|quale dovrei|cosa dovrei fare|come dovrei|" +
        "aiutami a (?:decidere|scegliere|capire|risolvere|ragionare|pianificare|organizzare)|ragiona|ragioniamo|risolvi|risolvere|" +
        "come (?:posso|potrei|faccio a) (?:risolvere|migliorare|organizzare|ottimizzare|aumentare|ridurre|calcolare|capire se|decidere)|" +
        "strategia|analizza|analizzami|confronta|differenza tra|vantaggi e svantaggi|valuta|stima|quanto (?:mi )?(?:costa|costerebbe|viene|verrebbe) in totale|" +
        "se (?:ho|avessi|compro|comprassi|metto|mettessi|investo|investissi|guadagno)\\b.+\\b(?:quanto|quanti|quante|cosa)\\b|" +
        "perché (?:succede|accade|non funziona|il mio|la mia)|qual è il modo migliore|indovinello|problema di)\\b"
    )
    private val EASY = Lang.rx("^(?:chi è|chi era|cos'è|che cos'è|quando è nato|dove si trova|come si dice|traduci|che ore|che giorno)\\b")

    /** true se la domanda è di quelle difficili (scegliere, calcolare, risolvere, ragionare): meglio pensarci prima. */
    fun hard(q: String): Boolean {
        val t = q.trim().lowercase()
        if (EASY.containsMatchIn(t)) return false
        val words = t.split(Regex("\\s+")).size
        val numbers = Regex("\\d+(?:[.,]\\d+)?").findAll(t).count()
        return (HARD.containsMatchIn(t) && words >= 6) || (numbers >= 2 && words >= 10 && Lang.rx("\\b(?:quanto|quanti|quante|quale|se)\\b").containsMatchIn(t))
    }
}
