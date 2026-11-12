package com.essentialapp.sam

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import java.net.URLEncoder

/** Implementazione reale delle azioni sul telefono. */
class AndroidActions(private val ctx: Context) : Actions {

    companion object {
        const val BLOCKED = "Android non mi ha lasciato aprire l'app. Nella configurazione di SAM, " +
            "alla voce permessi speciali, attiva le finestre pop-up in background di Xiaomi."
    }

    private val pm get() = ctx.packageManager

    /** Frasi da dire più tardi (esito dell'invio su WhatsApp, app bloccata…): le imposta il servizio vocale. */
    var speakLater: ((String) -> Unit)? = null

    /**
     * Apre un'altra app. Con SAM in sottofondo Android (e ancora di più Xiaomi) blocca in silenzio
     * le aperture: si passa prima dal servizio di accessibilità, che ha il permesso di farlo, e se
     * l'app non compare entro qualche secondo SAM lo dice invece di restare zitto.
     */
    private fun launch(intent: Intent, verify: Boolean = true): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = try { pm.resolveActivity(intent, 0)?.activityInfo?.packageName } catch (e: Exception) { null }
        val ok = SamAccessibilityService.launch(intent) ||
            try { ctx.startActivity(intent); true } catch (e: Exception) { false }
        if (ok && verify && target != null && target != "android" && screenUnlocked())
            SamAccessibilityService.expectForeground(target, 4000) { speakLater?.invoke(BLOCKED) }
        return ok
    }

    private fun screenUnlocked(): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (e: Exception) { false }

    private fun view(uri: String): Boolean = launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))

    private fun installed(pkg: String): Boolean = try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- APP ---------------------------------------------------------------
    private val apps = linkedMapOf(
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "instagram" to listOf("com.instagram.android"),
        "facebook" to listOf("com.facebook.katana"),
        "messenger" to listOf("com.facebook.orca"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "capcut" to listOf("com.lemon.lvoverseas"),
        "youtube music" to listOf("com.google.android.apps.youtube.music"),
        "youtube" to listOf("com.google.android.youtube"),
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome", "com.mi.globalbrowser"),
        "gmail" to listOf("com.google.android.gm"),
        "posta" to listOf("com.google.android.gm"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "maps" to listOf("com.google.android.apps.maps"),
        "mappe" to listOf("com.google.android.apps.maps"),
        "spotify" to listOf("com.spotify.music"),
        "metatrader" to listOf("net.metaquotes.metatrader5", "net.metaquotes.metatrader4"),
        "tradingview" to listOf("com.tradingview.tradingviewapp"),
        "calcolatrice" to listOf("com.miui.calculator", "com.google.android.calculator"),
        "galleria" to listOf("com.miui.gallery", "com.google.android.apps.photos"),
        "google foto" to listOf("com.google.android.apps.photos"),
        "orologio" to listOf("com.android.deskclock", "com.google.android.deskclock"),
        "calendario" to listOf("com.xiaomi.calendar", "com.google.android.calendar", "com.android.calendar"),
        "note" to listOf("com.miui.notes", "com.google.android.keep"),
        "play store" to listOf("com.android.vending"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "amazon" to listOf("com.amazon.mShop.android.shopping"),
        "file" to listOf("com.mi.android.globalFileexplorer", "com.google.android.apps.nbu.files"),
        "messaggi" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "contatti" to listOf("com.google.android.contacts", "com.android.contacts"),
        "telefono" to listOf("com.google.android.dialer", "com.android.contacts"),
        "meteo" to listOf("com.miui.weather2"),
        "claude" to listOf("com.anthropic.claude"),
        "gemini" to listOf("com.google.android.apps.bard"),
        "chatgpt" to listOf("com.openai.chatgpt"),
        "termux" to listOf("com.termux"),
    )

    private fun openPackage(pkg: String): Boolean =
        pm.getLaunchIntentForPackage(pkg)?.let { launch(it) } ?: false

    override fun openApp(name: String): Boolean {
        val n = name.trim().lowercase()
        if (Regex("\\bimpostazioni\\b").containsMatchIn(n)) return openSettings()
        if (Regex("\\b(fotocamera|camera)\\b").containsMatchIn(n)) return openCamera(false)
        for (key in apps.keys.sortedWith(Comparator { x, y -> y.length - x.length }))
            if (n.contains(key)) return apps.getValue(key).any { openPackage(it) }
        // cerca tra le app installate per etichetta
        val words = Regex("[a-z0-9]+").findAll(n).map { it.value }.filter { it.length >= 4 }.toList()
        if (words.isNotEmpty()) {
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (words.any { label.contains(it) } && openPackage(app.packageName)) return true
            }
        }
        return false
    }

    override fun openSettings() = launch(Intent(Settings.ACTION_SETTINGS))

    override fun openCamera(selfie: Boolean): Boolean {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) i.putExtra("android.intent.extras.CAMERA_FACING", 1)      // richieste non ufficiali:
            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)           // molte fotocamere le rispettano
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        return launch(i)
    }

    // ---- BATTERIA ----------------------------------------------------------
    @SuppressLint("ObsoleteSdkInt")
    override fun batteryText(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "La batteria è al $pct per cento, " + (if (charging) "in carica." else "non in carica.")
    }

    // ---- TORCIA / VIBRAZIONE ----------------------------------------------
    override fun torch(on: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    override fun vibrate(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---- VOLUME ------------------------------------------------------------
    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun stream(ring: Boolean) = if (ring) AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC

    override fun currentVolumePercent(ring: Boolean): Int {
        val s = stream(ring)
        val max = am.getStreamMaxVolume(s).coerceAtLeast(1)
        return Math.round(am.getStreamVolume(s) * 100f / max)
    }

    override fun setVolumePercent(percent: Int, ring: Boolean) {
        val s = stream(ring)
        val max = am.getStreamMaxVolume(s)
        val lvl = Math.round(max * percent / 100f).coerceIn(0, max)
        try { am.setStreamVolume(s, lvl, AudioManager.FLAG_SHOW_UI) } catch (e: Exception) {}
    }

    // ---- LUMINOSITÀ (serve il permesso Modifica impostazioni di sistema) ---
    override fun setBrightnessPercent(percent: Int) {
        if (!Settings.System.canWrite(ctx)) { requestWriteSettings(); return }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
            Math.round(255 * percent.coerceIn(1, 100) / 100f))
    }

    override fun setBrightnessAuto() {
        if (!Settings.System.canWrite(ctx)) { requestWriteSettings(); return }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    }

    private fun requestWriteSettings() {
        launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    // ---- SVEGLIE / TIMER ---------------------------------------------------
    override fun setAlarm(hour: Int, minute: Int): Boolean = launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "SAM")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        verify = false   // senza schermata: l'orologio non compare in primo piano
    )

    override fun setTimer(seconds: Int): Boolean = launch(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "SAM")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        verify = false
    )

    /** Spegne/posticipa la sveglia che suona (o la prossima), oppure apre l'elenco di sveglie o timer. */
    override fun alarmScreen(which: String): Boolean = when (which) {
        "dismiss" -> launch(Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT), verify = false)
        "snooze" -> launch(Intent(AlarmClock.ACTION_SNOOZE_ALARM), verify = false)
        "alarms" -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        "timers" -> launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        else -> false
    }

    // ---- MUSICA IN RIPRODUZIONE (tasti multimediali, nessun permesso) -----
    override fun media(cmd: String): Boolean {
        val code = when (cmd) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        } catch (e: Exception) { false }
    }

    // ---- CONTATTI / COMUNICAZIONI -----------------------------------------
    private var cache: List<Contact> = emptyList()
    private var cacheAt = 0L

    /** Tutta la rubrica (letta al massimo ogni 10 secondi): una voce per persona, numero principale o cellulare. */
    private fun allContacts(): List<Contact> {
        val now = System.currentTimeMillis()
        if (now - cacheAt < 10_000) return cache
        cache = readContacts(); cacheAt = now
        return cache
    }

    private fun readContacts(): List<Contact> {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )
        val best = LinkedHashMap<Long, Pair<Contact, Int>>()   // id -> (contatto, priorità)
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val nm = c.getString(1) ?: continue
                    val ph = c.getString(2) ?: continue
                    val prio = when {
                        c.getInt(4) != 0 -> 3
                        c.getInt(3) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> 2
                        else -> 1
                    }
                    val old = best[id]
                    if (old == null || prio > old.second) best[id] = Contact(nm, ph) to prio
                }
            }
        } catch (e: Exception) { return emptyList() }
        return best.values.map { it.first }
    }

    override fun contacts(name: String): List<Contact> {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return ContactMatch.rank(q, allContacts())
    }

    override fun canCallDirectly(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun call(number: String): Boolean {
        val n = number.replace(Regex("[^\\d+]"), "")
        if (canCallDirectly()) {
            // la chiamata la fa partire il sistema telefonico: funziona anche con SAM in sottofondo
            try {
                val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.placeCall(Uri.fromParts("tel", n, null), Bundle())
                return true
            } catch (e: Exception) {}
            if (launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")), verify = false)) return true
        }
        return launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")), verify = false)
    }

    @SuppressLint("MissingPermission")
    override fun sendSms(number: String, body: String): Boolean = try {
        val n = number.replace(Regex("[^\\d+]"), "")
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ctx.getSystemService(android.telephony.SmsManager::class.java)
            else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            sm.sendTextMessage(n, null, body, null, null)
            true
        } else {
            // ripiego: apri l'app Messaggi con il testo pronto
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).putExtra("sms_body", body))
        }
    } catch (e: Exception) { false }

    private fun intl(n: String): String {
        var x = n.replace(Regex("[^\\d+]"), "")
        return when {
            x.startsWith("+") -> x.drop(1)
            x.startsWith("00") -> x.drop(2)
            x.startsWith("3") || x.startsWith("0") -> "39$x"
            else -> x
        }
    }

    private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Chat WhatsApp con il testo già scritto: si chiede direttamente a WhatsApp, non al browser. */
    override fun openWhatsApp(number: String, body: String): Boolean {
        val q = "phone=${intl(number)}&text=${Uri.encode(body)}"
        for (p in WHATSAPP) {
            if (!installed(p)) continue
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?$q")).setPackage(p))) return true
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?$q")).setPackage(p))) return true
        }
        return view("https://wa.me/${intl(number)}?text=${Uri.encode(body)}")
    }

    override fun canAutoSend(): Boolean = SamAccessibilityService.isOn()

    override fun sendWhatsApp(number: String, body: String): Boolean {
        val pkgs = WHATSAPP.filter { installed(it) }
        if (pkgs.isEmpty()) return false
        SamAccessibilityService.requestSend(pkgs, body, 12_000) { ok ->
            speakLater?.invoke(if (ok) "Messaggio inviato su WhatsApp." else "Non sono riuscito a premere invia: controlla WhatsApp e premilo tu.")
        }
        return openWhatsApp(number, body)
    }

    // ---- POSIZIONE / NAVIGAZIONE ------------------------------------------
    override fun navigateTo(dest: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(dest)}"))
            .setPackage("com.google.android.apps.maps"))
            || view("https://www.google.com/maps/dir/?api=1&destination=${enc(dest)}")

    override fun whereAmI(): Boolean =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=la+mia+posizione")))
            || view("https://www.google.com/maps/search/?api=1&query=my+location")

    // ---- METEO -------------------------------------------------------------
    // app del meteo conosciute (prima quella di Xiaomi); le altre si trovano per nome ("Meteo", "Weather")
    private val WEATHER_APPS = listOf(
        "com.miui.weather2", "com.google.android.apps.weather", "com.accuweather.android", "com.weather.Weather",
        "it.ilmeteo.android.ilmeteo", "com.ilmeteo.android.ilmeteo", "it.meteo3b.android", "com.sec.android.daemonapp"
    )

    override fun openWeather(city: String?, day: String?): String? {
        if (city.isNullOrBlank()) {
            // dove sei: l'app del meteo usa già la tua posizione
            for (p in WEATHER_APPS) pm.getLaunchIntentForPackage(p)?.let { if (launch(it)) return p }
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if ((label.contains("meteo") || label.contains("weather")) && openPackage(app.packageName)) return app.packageName
            }
        }
        // un'altra città (o nessuna app del meteo): la pagina delle previsioni nel browser
        val q = listOfNotNull("meteo", city?.trim()?.ifEmpty { null }, day).joinToString(" ")
        val url = "https://www.google.com/search?q=${enc(q)}"
        // il browser che la aprirà: SAM legge solo quella finestra ("" = non si sa, se Android chiede quale usare)
        val browser = try { pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)), 0)?.activityInfo?.packageName } catch (e: Exception) { null }
        return if (view(url)) (browser?.takeIf { it != "android" } ?: "") else null
    }

    // ---- WEB / MUSICA ------------------------------------------------------
    override fun webSearch(query: String) = view("https://www.google.com/search?q=${enc(query)}")
    override fun openUrl(url: String) = view(url)
    override fun openBrowser(): Boolean {
        for (p in listOf("com.android.chrome", "com.mi.globalbrowser", "org.mozilla.firefox", "com.opera.browser"))
            pm.getLaunchIntentForPackage(p)?.let { if (launch(it)) return true }
        return view("https://www.google.com")
    }
    override fun youtubeSearch(query: String) = view("https://www.youtube.com/results?search_query=${enc(query)}")
    override fun playMusic(): Boolean {
        if (openPackage("com.spotify.music")) return true
        return launch(Intent("android.intent.action.MUSIC_PLAYER").addCategory(Intent.CATEGORY_APP_MUSIC))
    }

    override fun playSearch(query: String): Boolean {
        // richiesta standard "suona questo": Spotify, YouTube Music e le app di musica la capiscono
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        // prima Spotify se c'è (se manca, launch fallisce e si passa alle altre app)
        return launch(Intent(i).setPackage("com.spotify.music")) || launch(i)
    }

    // ---- NOTIFICHE / ACCESSIBILITÀ ----------------------------------------
    override fun readNotifications(): String {
        if (!NotificationReader.isEnabled(ctx))
            return "Per leggere le notifiche devi attivare SAM nell'accesso alle notifiche."
        val list = NotificationReader.latest()
        if (list.isEmpty()) return "Non hai notifiche recenti."
        val head = "Hai ${list.size} notifiche. Le ultime: "
        return head + list.take(5).joinToString(". ") { "${it.first}: ${it.second.take(120)}" }
    }

    override fun globalAction(which: String): Boolean {
        if (SamAccessibilityService.perform(which)) return true
        // senza servizio di accessibilità si può comunque tornare alla schermata home
        return which == "home" && launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    @SuppressLint("InlinedApi")
    override fun openSystemPanel(which: String): Boolean = when (which) {
        "wifi" -> launch(Intent(Settings.Panel.ACTION_WIFI))
        "dati" -> launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        "bluetooth" -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "aereo" -> launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        "nfc" -> launch(Intent(Settings.ACTION_NFC_SETTINGS))
        "gps" -> launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        "silenzioso" -> launch(Intent(Settings.ACTION_SOUND_SETTINGS))
        else -> openSettings()
    }

    // ---- APPUNTI -----------------------------------------------------------
    private val clip get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    override fun clipboardGet(): String =
        clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    override fun clipboardSet(text: String) { clip.setPrimaryClip(ClipData.newPlainText("SAM", text)) }

    // ---- VISTA -------------------------------------------------------------
    /** La schermata con cui SAM guarda: si apre anche con SAM in sottofondo (servizio di accessibilità o permesso pop-up). */
    // senza il controllo di launch(): se la schermata non compare, lo dice il servizio vocale (un messaggio solo)
    override fun look(front: Boolean): Boolean =
        launch(Intent(ctx, LookActivity::class.java).putExtra(LookActivity.EXTRA_FRONT, front), verify = false)

    /** 1.21: la fotocamera di SAM per fare una foto da tenere ([mode] "photo") o filmare ("video"). */
    fun capture(mode: String, front: Boolean, delay: Int, seconds: Int): Boolean =
        launch(Intent(ctx, LookActivity::class.java).putExtra(LookActivity.EXTRA_FRONT, front).putExtra(LookActivity.EXTRA_MODE, mode)
            .putExtra(LookActivity.EXTRA_DELAY, delay).putExtra(LookActivity.EXTRA_SECONDS, seconds), verify = false)

    /** Apre una foto o un video con l'app Galleria. */
    fun viewMedia(uri: String, mime: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), verify = false)
}
