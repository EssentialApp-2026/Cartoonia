package com.essentialapp.sam

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servizio di accessibilità (facoltativo, ma consigliato). Permette a SAM di:
 *  - fare le azioni di sistema vietate alle app: indietro, home, recenti, notifiche,
 *    blocco schermo, menu di spegnimento;
 *  - aprire le app anche quando SAM è in sottofondo (Android lo consente ai servizi di accessibilità);
 *  - accorgersi se un'app non si è aperta, per dirtelo;
 *  - premere "invia" su WhatsApp quando gli chiedi di mandare un messaggio;
 *  - leggerti il meteo dall'app del meteo (o dalla pagina delle previsioni) quando glielo chiedi;
 *  - fare uno screenshot.
 * Non registra e non manda nulla da nessuna parte: SAM non ha accesso a internet.
 */
class SamAccessibilityService : AccessibilityService() {

    // (nella 1.6.5 da qui si riaccendeva l'ascolto quando Android chiudeva SAM: ma acceso in sottofondo Android
    // può non dargli il microfono, e SAM restava sordo. Ora, se si ferma, una notifica ti chiede di riaprirlo.)
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foreground = pkg
            expect?.let { if (it.pkg == pkg) { expect = null; main.removeCallbacks(it.timeout) } }
        }
        val s = send ?: return
        if (pkg in s.pkgs) trySend(s)
    }

    /** Cerca il tasto invia di WhatsApp nella chat aperta e lo preme (una volta sola). */
    private fun trySend(s: PendingSend) {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in s.pkgs) return
        // il testo deve essere già nella casella, così non si invia per sbaglio una vecchia bozza
        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()
        if (entry != null) {
            val txt = entry.text?.toString() ?: ""
            if (!txt.contains(s.body.take(12), ignoreCase = true)) return
        }
        val button = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser && it.isEnabled }
            ?: findByDescription(root, setOf("invia", "send"))
            ?: return
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) finishSend(true)
    }

    private fun findByDescription(n: AccessibilityNodeInfo, labels: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        if (d != null && d in labels && n.isClickable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findByDescription(c, labels, depth + 1)?.let { return it }
        }
        return null
    }

    /** Il testo della finestra in primo piano, una riga per scritta: (pacchetto, testo). */
    private fun dumpText(max: Int): Pair<String, String>? {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val sb = StringBuilder()
        collectText(root, sb, max, 0)
        return pkg to sb.toString()
    }

    private fun collectText(n: AccessibilityNodeInfo, sb: StringBuilder, max: Int, depth: Int) {
        if (sb.length >= max || depth > 40) return
        val t = n.text?.toString()?.trim().orEmpty()
        val piece = (if (t.isNotEmpty()) t else n.contentDescription?.toString()?.trim().orEmpty()).replace(Regex("\\s+"), " ")
        if (piece.isNotEmpty() && !sb.endsWith(piece + "\n")) sb.append(piece).append('\n')
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
            collectText(c, sb, max, depth + 1)
        }
    }

    private class PendingSend(val pkgs: List<String>, val body: String, val onResult: (Boolean) -> Unit, val timeout: Runnable)
    private class Expect(val pkg: String, val timeout: Runnable)

    companion object {
        @Volatile private var instance: SamAccessibilityService? = null
        @Volatile private var send: PendingSend? = null
        @Volatile private var expect: Expect? = null
        /** l'app in primo piano, secondo l'ultimo cambio di finestra */
        @Volatile var foreground: String? = null; private set
        private val main = Handler(Looper.getMainLooper())

        fun isOn(): Boolean = instance != null

        fun perform(which: String): Boolean {
            val s = instance ?: return false
            val action = when (which) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "lock" -> GLOBAL_ACTION_LOCK_SCREEN
                "power" -> GLOBAL_ACTION_POWER_DIALOG
                "dismiss_shade" -> GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
                "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
                else -> return false
            }
            return try { s.performGlobalAction(action) } catch (e: Exception) { false }
        }

        /** Apre un'attività dal servizio di accessibilità: Android lo permette anche con SAM in sottofondo. */
        fun launch(intent: Intent): Boolean {
            val s = instance ?: return false
            return try { s.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
        }

        /** Se entro [ms] l'app [pkg] non arriva in primo piano, chiama [onFail] (per dire che è stata bloccata). */
        fun expectForeground(pkg: String, ms: Long, onFail: () -> Unit) {
            if (instance == null || foreground == pkg) return
            expect?.let { main.removeCallbacks(it.timeout) }
            val t = Runnable { if (expect?.pkg == pkg) { expect = null; onFail() } }
            expect = Expect(pkg, t)
            main.postDelayed(t, ms)
        }

        /** Preme "invia" nella prossima chat WhatsApp che si apre con [body] già scritto. */
        fun requestSend(pkgs: List<String>, body: String, ms: Long, onResult: (Boolean) -> Unit) {
            send?.let { main.removeCallbacks(it.timeout) }
            val t = Runnable { if (send != null) finishSend(false) }
            send = PendingSend(pkgs, body, onResult, t)
            main.postDelayed(t, ms)
        }

        // finestre che non sono l'app da leggere: tendina, schermata di blocco, launcher
        private val NOT_CONTENT = setOf(
            "com.android.systemui", "com.miui.home", "com.mi.android.globallauncher", "com.google.android.apps.nexuslauncher",
            "com.android.launcher3", "com.miui.securitycenter"
        )
        @Volatile private var readTurn = 0

        /**
         * Legge il testo dell'app in primo piano (non SAM, non la tendina o il launcher) appena [ready] lo accetta,
         * riprovando ogni mezzo secondo per al massimo [timeoutMs]. [pkg] = l'app attesa (null = qualunque).
         * Il risultato arriva sul thread principale: il testo migliore trovato, oppure null.
         */
        fun readScreen(pkg: String?, timeoutMs: Long, ready: (String) -> Boolean, onResult: (String?) -> Unit) {
            if (instance == null) { main.post { onResult(null) }; return }
            val turn = ++readTurn
            val start = SystemClock.uptimeMillis()
            var best: String? = null
            val tick = object : Runnable {
                override fun run() {
                    if (turn != readTurn) return
                    val s = instance ?: run { onResult(best); return }
                    val r = try { s.dumpText(6000) } catch (e: Exception) { null }
                    if (r != null && r.first != s.packageName && r.first !in NOT_CONTENT && (pkg == null || r.first == pkg)) {
                        if (r.second.length > (best?.length ?: 0)) best = r.second
                        if (ready(r.second)) { onResult(r.second); return }
                    }
                    if (SystemClock.uptimeMillis() - start >= timeoutMs) { onResult(best); return }
                    main.postDelayed(this, 500)
                }
            }
            main.postDelayed(tick, 900)
        }

        /** Smette di aspettare una lettura in corso (SAM interrotto). */
        fun cancelRead() { readTurn++ }

        private fun finishSend(ok: Boolean) {
            val s = send ?: return
            send = null
            main.removeCallbacks(s.timeout)
            main.post { s.onResult(ok) }
        }
    }
}
