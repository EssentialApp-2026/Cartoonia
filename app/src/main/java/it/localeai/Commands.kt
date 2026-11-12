package it.localeai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CommandResult(val reply: String, val speak: Boolean = true)

/**
 * Comandi del telefono riconosciuti in italiano, eseguiti subito senza IA.
 * Restituisce null se la frase non è un comando: allora risponde l'IA.
 */
object CommandRouter {
    private val numberWords = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11,
        "dodici" to 12, "quindici" to 15, "venti" to 20, "trenta" to 30, "quaranta" to 40,
        "quarantacinque" to 45, "cinquanta" to 50, "sessanta" to 60, "novanta" to 90
    )

    fun normalize(input: String): String {
        var s = input.lowercase(Locale.ITALIAN).trim()
        s = s.replace(Regex("^(ehi|hey|ok|ciao)\\s+"), "")
        s = s.replace(Regex("^(sam|sem|samm|saam)\\b[,!.]?\\s*"), "")
        s = s.replace(Regex("\\b(per favore|per piacere|grazie)\\b"), "")
        s = s.replace(Regex("[?!.,]+$"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun parseNumber(token: String): Int? =
        token.toIntOrNull() ?: numberWords[token]

    fun handle(context: Context, raw: String): CommandResult? {
        val s = normalize(raw)
        if (s.isEmpty()) return CommandResult("Dimmi pure.")

        // Interruzione della voce.
        if (Regex("^(stop|basta|zitto|zitta|silenzio|fermati|smetti)( di parlare)?$").matches(s)) {
            SamBrain.stopAll()
            return CommandResult("", speak = false)
        }
        if (Regex("^(nuova chat|nuova conversazione|dimentica tutto|ricomincia)$").matches(s)) {
            SamBrain.newChat()
            return CommandResult("Va bene, ricominciamo da capo.")
        }
        if (Regex("^(modalità|modalita|passa a|usa)( la modalità| il modello)? (offline|locale)$").matches(s)) {
            AccountStore.mode = EngineMode.LOCAL_ONLY
            return CommandResult("Ok, da ora uso solo il modello locale.")
        }
        if (Regex("^(modalità|modalita|passa a|usa)( la modalità| i modelli)? online$").matches(s)) {
            AccountStore.mode = EngineMode.ONLINE_FIRST
            return CommandResult("Ok, uso i modelli online e passo al locale se finiscono i token.")
        }

        // Ora, data, batteria.
        if (Regex("(che ore sono|che ora è|che ora e|dimmi l'ora)").containsMatchIn(s)) {
            val f = SimpleDateFormat("H:mm", Locale.ITALIAN)
            return CommandResult("Sono le ${f.format(Date())}.")
        }
        if (Regex("(che giorno è|che giorno e|che data è|che data e|quanti ne abbiamo)").containsMatchIn(s)) {
            val f = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALIAN)
            return CommandResult("Oggi è ${f.format(Date())}.")
        }
        if (s.contains("batteria")) {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            return CommandResult(
                if (level < 0) "Non riesco a leggere la batteria."
                else "La batteria è al $level per cento" + (if (charging) ", in carica." else ".")
            )
        }

        // Torcia.
        if (s.contains("torcia") || s.contains("flash")) {
            val on = !(s.contains("spegni") || s.contains("spenta") || s.contains("off"))
            return CommandResult(setTorch(context, on))
        }

        // Volume.
        if (s.contains("volume")) return CommandResult(volume(context, s))

        // Sveglia.
        Regex("sveglia (?:alle |per le |a |domani alle )?(\\d{1,2}|\\w+)(?:[:. ]+(?:e )?(\\d{1,2}))?(.*)")
            .find(s)?.let { m ->
                var hour = parseNumber(m.groupValues[1]) ?: return@let
                var minute = m.groupValues[2].toIntOrNull() ?: 0
                val rest = m.groupValues[3]
                if (rest.contains("mezza")) minute = 30
                if (rest.contains("un quarto")) minute = if (rest.contains("meno")) 45 else 15
                if (rest.contains("meno un quarto")) hour = (hour + 23) % 24
                if (rest.contains("di sera") || rest.contains("del pomeriggio")) { if (hour < 12) hour += 12 }
                if (hour !in 0..23 || minute !in 0..59) return@let
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "SAM")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                Launcher.start(context, intent, "Imposta sveglia")
                return CommandResult("Sveglia impostata alle $hour e ${"%02d".format(minute)}.")
            }

        // Timer.
        Regex("timer (?:di |da |per )?(\\d+|\\w+) (secondi|secondo|minuti|minuto|ore|ora)").find(s)?.let { m ->
            val n = parseNumber(m.groupValues[1]) ?: return@let
            val secs = when {
                m.groupValues[2].startsWith("second") -> n
                m.groupValues[2].startsWith("minut") -> n * 60
                else -> n * 3600
            }
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "SAM")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            Launcher.start(context, intent, "Avvia timer")
            return CommandResult("Timer di $n ${m.groupValues[2]} avviato.")
        }

        // Messaggi (SMS o WhatsApp).
        Regex("^(?:manda|invia|scrivi)(?: un)? (?:messaggio|sms|whatsapp)(?: su whatsapp)? a (.+?)(?: (?:che dice|dicendo|con scritto|scrivendo|dicendogli|dicendole) (.+))?$")
            .find(s)?.let { m ->
                val target = m.groupValues[1].removeSuffix(" su whatsapp").trim()
                val text = m.groupValues[2]
                val contact = findContact(context, target)
                    ?: return CommandResult(contactProblem(context, target))
                val whatsapp = s.contains("whatsapp")
                val intent = if (whatsapp) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://wa.me/${internationalNumber(contact.second)}?text=${Uri.encode(text)}"
                    ))
                } else {
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.second}"))
                        .putExtra("sms_body", text)
                }
                Launcher.start(context, intent, "Messaggio a ${contact.first}")
                return CommandResult("Ho preparato il messaggio per ${contact.first}: controlla e premi invia.")
            }

        // Chiamate.
        Regex("^(?:chiama|telefona a|telefona|fai una chiamata a)\\s+(.+)$").find(s)?.let { m ->
            val target = m.groupValues[1].trim()
            val digits = target.replace(Regex("[^0-9+]"), "")
            val (name, number) = if (digits.length >= 3 && digits.length >= target.count { !it.isWhitespace() } - 2) {
                target to digits
            } else {
                findContact(context, target) ?: return CommandResult(contactProblem(context, target))
            }
            val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            Launcher.start(context, intent, "Chiama $name")
            return CommandResult("Chiamo $name.")
        }

        // Navigazione.
        Regex("^(?:naviga|portami|guidami|indicazioni|navigatore)(?: (?:verso|per|fino a|a|al|alla|allo|all'|in|da))?\\s+(.+)$")
            .find(s)?.let { m ->
                val dest = m.groupValues[1]
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}"))
                if (!Launcher.start(context, intent, "Naviga verso $dest")) {
                    Launcher.start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")), "Mappa")
                }
                return CommandResult("Avvio la navigazione verso $dest.")
            }

        // Foto e fotocamera.
        if (Regex("^(scatta|fai|fammi) (una )?(foto|fotografia)|^apri (la )?(fotocamera|camera)$").containsMatchIn(s)) {
            Launcher.start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Fotocamera")
            return CommandResult("Apro la fotocamera.")
        }

        // Musica / YouTube.
        Regex("^(?:riproduci|metti|ascolta|fammi sentire)\\s+(.+?)(?: su youtube)?$").find(s)?.let { m ->
            val q = m.groupValues[1]
            val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, q)
            if (s.contains("youtube") || !Launcher.start(context, play, "Riproduci $q")) {
                Launcher.start(context, yt, "YouTube: $q")
            }
            return CommandResult("Cerco $q.")
        }

        // Ricerca web.
        Regex("^(?:cerca|cercami|googla)(?: su google)?\\s+(.+)$").find(s)?.let { m ->
            val q = m.groupValues[1]
            val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            if (!Launcher.start(context, search, "Cerca $q")) {
                Launcher.start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")), "Cerca $q")
            }
            return CommandResult("Ecco i risultati per $q.")
        }

        // Promemoria in calendario.
        Regex("^(?:ricordami di|ricordami|promemoria(?: per)?|segna)\\s+(.+?)(?: (domani|oggi))?(?: alle (\\d{1,2})(?:[:. ](\\d{2}))?)?$")
            .find(s)?.let { m ->
                val title = m.groupValues[1]
                val cal = Calendar.getInstance()
                if (m.groupValues[2] == "domani") cal.add(Calendar.DAY_OF_YEAR, 1)
                val h = m.groupValues[3].toIntOrNull()
                if (h != null) {
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m.groupValues[4].toIntOrNull() ?: 0)
                } else cal.add(Calendar.HOUR_OF_DAY, 1)
                val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title.replaceFirstChar { it.uppercase() })
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 30 * 60_000)
                Launcher.start(context, intent, "Promemoria")
                return CommandResult("Ho preparato il promemoria: confermalo nel calendario.")
            }

        // Impostazioni rapide.
        if (Regex("(wi-?fi|wifi)").containsMatchIn(s) && Regex("^(apri|attiva|accendi|disattiva|spegni|impostazioni)").containsMatchIn(s)) {
            val intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            Launcher.start(context, intent, "Wi-Fi")
            return CommandResult("Ecco le impostazioni del Wi-Fi.")
        }
        if (s.contains("bluetooth") && Regex("^(apri|attiva|accendi|disattiva|spegni|impostazioni)").containsMatchIn(s)) {
            Launcher.start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth")
            return CommandResult("Ecco le impostazioni del Bluetooth.")
        }
        if (Regex("^apri (le )?impostazioni$").matches(s)) {
            Launcher.start(context, Intent(Settings.ACTION_SETTINGS), "Impostazioni")
            return CommandResult("Apro le impostazioni.")
        }

        // Apertura app.
        Regex("^(?:apri|avvia|lancia|vai su)\\s+(?:l'app |l'applicazione |app )?(.+)$").find(s)?.let { m ->
            val wanted = m.groupValues[1]
            val app = findApp(context, wanted) ?: return CommandResult("Non trovo nessuna app chiamata $wanted.")
            val intent = context.packageManager.getLaunchIntentForPackage(app.second)
                ?: return CommandResult("Non riesco ad aprire ${app.first}.")
            Launcher.start(context, intent, "Apri ${app.first}")
            return CommandResult("Apro ${app.first}.")
        }

        return null
    }

    private fun setTorch(context: Context, on: Boolean): String = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) "Questo telefono non ha la torcia."
        else {
            cm.setTorchMode(id, on)
            if (on) "Torcia accesa." else "Torcia spenta."
        }
    } catch (e: Exception) {
        "Non riesco a usare la torcia: la fotocamera è occupata."
    }

    private fun volume(context: Context, s: String): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "Non riesco a cambiare il volume."
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        when {
            Regex("(massimo|al massimo|tutto)").containsMatchIn(s) -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            Regex("(muto|zero|togli)").containsMatchIn(s) -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            Regex("(giù|giu|abbassa|diminuisci|meno|basso)").containsMatchIn(s) ->
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            Regex("(su|alza|aumenta|più|piu|alto)").containsMatchIn(s) ->
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            else -> {
                Regex("(\\d{1,3})").find(s)?.let { m ->
                    val pct = m.value.toInt().coerceIn(0, 100)
                    am.setStreamVolume(stream, max * pct / 100, AudioManager.FLAG_SHOW_UI)
                } ?: return "Vuoi alzare o abbassare il volume?"
            }
        }
        val pct = am.getStreamVolume(stream) * 100 / maxOf(1, max)
        return "Volume al $pct per cento."
    }

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun simplify(s: String): String =
        stripAccents(s.lowercase(Locale.ITALIAN)).replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /** Cerca il contatto: restituisce nome e numero. */
    private fun findContact(context: Context, query: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val wanted = simplify(query.removePrefix("a ").removePrefix("al ").removePrefix("alla "))
        if (wanted.isEmpty()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        var best: Pair<String, String>? = null
        var bestScore = 0
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = simplify(name)
                val score = when {
                    n == wanted -> 100
                    n.startsWith("$wanted ") -> 80
                    n.split(" ").contains(wanted) -> 70
                    n.contains(wanted) -> 50
                    wanted.contains(n) && n.length >= 3 -> 40
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; best = name to number }
            }
        }
        return best
    }

    private fun contactProblem(context: Context, target: String): String =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) "Mi serve il permesso per leggere i contatti: concedilo dalle impostazioni di SAM."
        else "Non trovo $target tra i contatti."

    private fun internationalNumber(number: String): String {
        var d = number.replace(Regex("[^0-9+]"), "")
        if (d.startsWith("+")) return d.substring(1)
        if (d.startsWith("00")) return d.substring(2)
        if (d.length == 10 && (d.startsWith("3") || d.startsWith("0"))) d = "39$d"
        return d
    }

    /** Cerca un'app installata per nome: restituisce etichetta e package. */
    private fun findApp(context: Context, query: String): Pair<String, String>? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val wanted = simplify(query)
        var best: Pair<String, String>? = null
        var bestScore = 0
        pm.queryIntentActivities(main, 0).forEach { ri ->
            val label = ri.loadLabel(pm).toString()
            val l = simplify(label)
            val score = when {
                l == wanted -> 100
                l.startsWith(wanted) -> 80
                l.split(" ").contains(wanted) -> 70
                l.contains(wanted) -> 50
                wanted.contains(l) && l.length >= 3 -> 40
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = label to ri.activityInfo.packageName }
        }
        return best
    }
}

/**
 * Avvia un'attività. Se SAM è in background senza il permesso "mostra sopra
 * altre app", Android blocca l'avvio: in quel caso mostra una notifica da toccare.
 */
object Launcher {
    private const val CHANNEL = "sam_actions"

    fun start(context: Context, intent: Intent, label: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canLaunch = SamBrain.uiVisible || Settings.canDrawOverlays(context)
        if (canLaunch) {
            return try {
                context.startActivity(intent); true
            } catch (e: Exception) {
                false
            }
        }
        notify(context, intent, label)
        return true
    }

    private fun notify(context: Context, intent: Intent, label: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Azioni di SAM", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            context, label.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SAM")
            .setContentText("Tocca per: $label")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(label.hashCode(), n) }
    }
}
