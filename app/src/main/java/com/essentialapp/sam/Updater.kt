package com.essentialapp.sam

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gli aggiornamenti di SAM (dalla 1.10): l'unico motivo per cui SAM usa internet, e solo col Wi-Fi.
 *  - L'enciclopedia: una volta alla settimana guarda sul sito di Kiwix se c'è una Wikipedia più nuova della stessa
 *    versione (per esempio "tutte le voci, senza immagini"); se c'è la scarica, la controlla (impronta MD5) e la usa
 *    al posto della vecchia, da solo.
 *  - Il cervello: guarda su Hugging Face se il file del cervello che usi è cambiato; se sì lo scarica, lo controlla
 *    (impronta sha256 e versione del motore) e poi ti chiede se installarlo. Il vecchio resta da parte finché il
 *    nuovo non è partito.
 * Gli scaricamenti li fa Android (DownloadManager): riprendono da soli se il Wi-Fi va e viene, e non usano mai i dati.
 */
object Updater {
    const val JOB_PERIODIC = 4101
    const val JOB_PROCESS = 4102
    private const val WEEK = 7L * 86_400_000L
    private const val CH = "sam_aggiornamenti"
    private const val NID = 46
    private const val UA = "SAM-aggiornamenti/1.10 (Android)"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)
    private val lock = Any()

    // ------------------------------------------------------------------ impostazioni
    /** Aggiornamenti automatici (di serie sì): una volta alla settimana, solo col Wi-Fi. */
    fun autoOn(ctx: Context): Boolean = prefs(ctx).getBoolean("agg_auto", true)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("agg_auto", on).apply()
        schedule(ctx)
    }

    /** Programma il controllo periodico: Android lo fa partire solo col Wi-Fi e la batteria non scarica. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        try {
            if (!autoOn(ctx)) { js.cancel(JOB_PERIODIC); return }
            if (js.getPendingJob(JOB_PERIODIC) != null) return
            js.schedule(JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .setPeriodic(24 * 3_600_000L, 6 * 3_600_000L)
                .build())
        } catch (e: Exception) {}
    }

    /** Uno scaricamento è finito: il controllo del file e la sistemazione, appena possibile (anche con SAM spento). */
    fun scheduleProcess(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        try {
            // solo un piccolo ritardo (Android 15 non accetta lavori con una scadenza troppo stretta)
            js.schedule(JobInfo.Builder(JOB_PROCESS, ComponentName(ctx, UpdateJob::class.java))
                .setMinimumLatency(1_000)
                .build())
        } catch (e: Exception) {}
    }

    /** true = collegato a una rete che non si paga a consumo (il Wi-Fi di casa): solo così SAM scarica. */
    fun onWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ il controllo
    /**
     * Il controllo degli aggiornamenti. [force] = adesso (chiesto a voce o dalla configurazione), altrimenti solo se è
     * passata una settimana dall'ultimo. [brainOnly] = solo il cervello. Ritorna cosa è successo, a parole ("" = niente
     * da dire). Thread di background, col Wi-Fi.
     */
    fun check(ctx: Context, force: Boolean, brainOnly: Boolean = false, stop: () -> Boolean = { false }): String = synchronized(lock) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("agg_ultimo", 0L) < WEEK) return@synchronized ""
        processLocked(ctx, stop)
        val http = Http(UA)
        val said = ArrayList<String>()
        if (!brainOnly) said.add(checkZim(ctx, http))
        said.add(checkBrain(ctx, http, stop))
        val text = said.filter { it.isNotBlank() }.joinToString(" ")
        p.edit().putLong("agg_ultimo", now).putString("agg_esito", text).apply()
        text
    }

    /** L'enciclopedia che c'è adesso: indirizzo, famiglia di Kiwix, mese della copia. */
    private class CurrentZim(val uri: String, val family: String?, val month: String?)

    private fun currentZim(ctx: Context): CurrentZim? {
        val u = EncyclopediaFile.uri(ctx) ?: return null
        return try {
            EncyclopediaFile.open(ctx, u).use { e ->
                val name = EncyclopediaFile.fileName(ctx, u)
                CurrentZim(u, Kiwix.family(name, e.zimName, e.flavour), Kiwix.month(e.date, name))
            }
        } catch (x: Throwable) { null }
    }

    private fun checkZim(ctx: Context, http: Http): String {
        if (prefs(ctx).getLong("agg_zim_id", -1L) >= 0) return zimProgress(ctx) ?: ""
        val cur = currentZim(ctx) ?: return ""          // senza enciclopedia non si scarica niente da solo
        val fam = cur.family ?: return "Della tua enciclopedia non conosco il nome che usa Kiwix: per aggiornarla scegline una nuova dalla configurazione."
        val news = try { UpdateCheck.encyclopedia(http, fam, cur.month) }
                   catch (e: Exception) { return "Il sito di Kiwix non risponde: riprovo la prossima volta." }
            ?: return "L'enciclopedia è la più recente" + (Kiwix.monthWords(cur.month)?.let { ", di $it." } ?: ".")
        return startZimDownload(ctx, news)
    }

    /** Scarica un'enciclopedia nuova (anche la prima, dalla configurazione). Thread di background. */
    fun downloadEncyclopedia(ctx: Context, family: String): String = synchronized(lock) {
        if (prefs(ctx).getLong("agg_zim_id", -1L) >= 0) return@synchronized zimProgress(ctx) ?: "Sto già scaricando l'enciclopedia."
        val cur = currentZim(ctx)
        val month = if (cur?.family == family) cur.month else null
        val news = try { UpdateCheck.encyclopedia(Http(UA), family, month) }
                   catch (e: Exception) { return@synchronized "Il sito di Kiwix non risponde: riprova più tardi, col Wi-Fi." }
            ?: return@synchronized "Hai già la Wikipedia più recente."
        startZimDownload(ctx, news)
    }

    private fun startZimDownload(ctx: Context, n: UpdateCheck.ZimNews): String {
        val month = Kiwix.monthWords(n.month) ?: n.month
        val dir = SamApp.downloadsDir(ctx)
        val need = UpdateCheck.spaceNeeded(n.size)
        val free = try { StatFs(dir.path).availableBytes } catch (e: Exception) { -1L }
        if (free in 0 until need)
            return "C'è la Wikipedia di $month, ma sul telefono non c'è abbastanza spazio per scaricarla: servono ${Brains.gb(need)} liberi."
        File(dir, n.file).delete()
        // se l'ultima volta non è riuscito (un mirror che non va), si scarica dal server principale di Kiwix
        val url = if (prefs(ctx).getInt("agg_zim_falliti", 0) % 2 == 1) Kiwix.url(n.family, n.file, Kiwix.MASTER) else n.url
        val id = enqueue(ctx, url, n.file, "Wikipedia di $month", "SAM scarica l'enciclopedia nuova (solo col Wi-Fi)")
            ?: return "Non riesco a far partire lo scaricamento dell'enciclopedia."
        prefs(ctx).edit().putLong("agg_zim_id", id).putString("agg_zim_file", n.file).putString("agg_zim_url", n.url).apply()
        return "C'è la Wikipedia di $month: la sto scaricando col Wi-Fi" + (if (n.size > 0) ", ${Brains.gb(n.size)}" else "") +
            ". Quando è pronta la controllo e la uso da solo."
    }

    private fun checkBrain(ctx: Context, http: Http, stop: () -> Boolean): String {
        if (prefs(ctx).getLong("agg_cerv_id", -1L) >= 0) return brainProgress(ctx) ?: ""
        readyBrain(ctx)?.let { return "Il cervello nuovo è pronto: per installarlo di' installa il cervello nuovo." }
        val f = SamApp.llmFile(ctx) ?: return ""
        val model = Brains.byFile(f.name) ?: return ""   // un cervello fuori dal catalogo: non so dove guardare
        val sha = installedSha(ctx, f, stop) ?: return ""
        val news = try { UpdateCheck.brain(http, model.url, model.file, sha) }
                   catch (e: Exception) { return "Hugging Face non risponde: riprovo la prossima volta." }
            ?: return "Il cervello ${model.name} è il più recente."
        if (news.sha256.equals(prefs(ctx).getString("agg_cerv_scartato", null), ignoreCase = true))
            return "C'è una versione nuova di ${model.name}, ma per usarla serve un SAM.apk più nuovo."
        return startBrainDownload(ctx, model, news.sha256, news.size, switchWhenDone = false)
    }

    /**
     * Scarica un cervello del catalogo: una versione nuova di quello che usi ([switchWhenDone] = false: poi ti chiede se
     * installarla), oppure un altro cervello che hai chiesto ([switchWhenDone] = true: appena pronto lo usa). [sha256] null =
     * lo chiede a Hugging Face (rete: thread di background).
     */
    fun startBrainDownload(ctx: Context, model: BrainModel, sha256: String? = null, size0: Long = -1L, switchWhenDone: Boolean,
                           /** 1.15: un cervello per un lavoro (programmare, guardare): si aggiunge agli altri, non diventa il principale */
                           addOnly: Boolean = false,
                           /** false = senza chiedere l'impronta a Hugging Face adesso (niente rete: si controlla col motore all'arrivo) */
                           askHf: Boolean = true): String {
        if (prefs(ctx).getLong("agg_cerv_id", -1L) >= 0) return brainProgress(ctx) ?: "Sto già scaricando un cervello."
        var sha = sha256
        var size = size0
        if (sha == null && askHf) {
            val m = try { HfFile.meta(Http(UA).head(model.url, followOtherSites = false).headers) } catch (e: Exception) { null }
                ?: return "Hugging Face non risponde: riprova più tardi, col Wi-Fi."
            sha = m.sha256
            if (m.size > 0) size = m.size
        }
        if (size <= 0) size = model.bytes
        val dir = SamApp.downloadsDir(ctx)
        // il file, e poi la copia di lavoro che il motore prepara la prima volta (circa metà del file)
        val need = size + size / 2 + 800_000_000L
        val free = try { StatFs(dir.path).availableBytes } catch (e: Exception) { -1L }
        if (free in 0 until need)
            return "Per scaricare ${model.name} servono ${Brains.gb(need)} liberi sul telefono, e adesso non ci sono."
        File(dir, model.file).delete()
        val id = enqueue(ctx, model.url, model.file, model.name, "SAM scarica il cervello (solo col Wi-Fi)")
            ?: return "Non riesco a far partire lo scaricamento del cervello."
        prefs(ctx).edit().putLong("agg_cerv_id", id).putString("agg_cerv_file", model.file).putString("agg_cerv_sha", sha)
            .putBoolean("agg_cerv_passa", switchWhenDone).putBoolean("agg_cerv_aggiungi", addOnly).apply()
        if (addOnly) return "Scarico ${model.name} (${Brains.gb(size)}), solo col Wi-Fi: quando è pronto lo uso da solo quando serve."
        return if (switchWhenDone) "Scarico ${model.name} col Wi-Fi (${Brains.gb(size)}): appena è pronto lo controllo e passo a quello."
               else "C'è una versione nuova di ${model.name}: la scarico col Wi-Fi e, quando è pronta, ti chiedo se installarla."
    }

    private fun enqueue(ctx: Context, url: String, file: String, title: String, desc: String): Long? = try {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val r = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(desc)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(ctx, null, "scaricamenti/$file")
            .addRequestHeader("User-Agent", UA)
        dm.enqueue(r)
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ scaricamenti finiti
    private class Dl(val status: Int, val reason: Int, val done: Long, val total: Long)

    /** non si è potuto chiedere ad Android (un errore passeggero): non si tocca niente, si riprova dopo */
    private const val UNKNOWN = -1

    /** Lo stato dello scaricamento; null = Android non lo conosce più (tolto dall'elenco degli scaricamenti). */
    private fun query(ctx: Context, id: Long): Dl? {
        val cursor = try { ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)) }
                     catch (e: Exception) { null } ?: return Dl(UNKNOWN, 0, 0, 0)
        return try {
            cursor.use { c ->
                if (!c.moveToFirst()) null else Dl(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
            }
        } catch (e: Exception) { Dl(UNKNOWN, 0, 0, 0) }
    }

    /** Toglie lo scaricamento dal registro di Android (il file a quel punto è già stato spostato, o non serve). */
    private fun forget(ctx: Context, id: Long) { try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (e: Exception) {} }

    /** Uno scaricamento di Android è finito: se è uno di SAM, si sistema appena possibile. */
    fun onDownloadComplete(ctx: Context, id: Long) {
        val p = prefs(ctx)
        // anche i modelli per disegnare (1.14)
        val img = p.getString("img_ids", "")!!.split(',').mapNotNull { it.trim().toLongOrNull() }
        if (id < 0 || (id != p.getLong("agg_zim_id", -1L) && id != p.getLong("agg_cerv_id", -1L) && id !in img)) return
        // con SAM acceso ci pensa lui subito; in ogni caso anche Android, appena può (il secondo non trova più niente da fare)
        if (VoiceService.running) VoiceService.downloadsDone(ctx)
        scheduleProcess(ctx)
    }

    /**
     * Gli scaricamenti finiti: si controllano i file e si mettono al loro posto. Ritorna le novità (anche salvate per
     * dopo). [stop] = Android chiede di fermarsi (il controllo di un file lungo si riprende la volta dopo).
     */
    fun processDownloads(ctx: Context, stop: () -> Boolean = { false }): List<String> = synchronized(lock) { processLocked(ctx, stop) }

    private fun processLocked(ctx: Context, stop: () -> Boolean): List<String> {
        val out = listOfNotNull(processZim(ctx, stop), processBrain(ctx, stop))
        for (n in out) addNews(ctx, n)
        return out
    }

    private fun clearZim(ctx: Context) {
        prefs(ctx).edit().remove("agg_zim_id").remove("agg_zim_file").remove("agg_zim_url").apply()
    }

    private fun processZim(ctx: Context, stop: () -> Boolean): String? {
        val p = prefs(ctx)
        val id = p.getLong("agg_zim_id", -1L).takeIf { it >= 0 } ?: return null
        val file = p.getString("agg_zim_file", null) ?: run { clearZim(ctx); return null }
        val tmp = File(SamApp.downloadsDir(ctx), file)
        when (query(ctx, id)?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {}
            DownloadManager.STATUS_FAILED, null -> {
                // non riuscito (o tolto dall'elenco degli scaricamenti): si riprova al prossimo controllo
                forget(ctx, id); tmp.delete(); clearZim(ctx)
                p.edit().putInt("agg_zim_falliti", p.getInt("agg_zim_falliti", 0) + 1).apply()
                return null
            }
            else -> return null   // in corso, in pausa, in attesa del Wi-Fi
        }
        // il file arrivato si controlla tutto: deve aprirsi e la sua impronta MD5 deve tornare
        val ok = try {
            Encyclopedia(Zim.open(tmp.path)).use { e -> e.check(); e.verify(stop = stop) }
        } catch (x: InterruptedException) { return null              // Android ha fermato il lavoro: si riprende dopo
        } catch (x: Throwable) { false }
        if (!ok) {   // rovinato: si riscarica al prossimo controllo
            forget(ctx, id); tmp.delete(); clearZim(ctx)
            p.edit().putInt("agg_zim_falliti", p.getInt("agg_zim_falliti", 0) + 1).apply()
            return null
        }
        p.edit().remove("agg_zim_falliti").apply()
        val dest = File(SamApp.encyclopediaDir(ctx), file)
        dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        forget(ctx, id)
        val old = EncyclopediaFile.uri(ctx)
        val oldName = old?.let { EncyclopediaFile.fileName(ctx, it) }
        EncyclopediaFile.setPath(ctx, dest.absolutePath)
        clearZim(ctx)
        VoiceService.encyclopediaChanged(ctx)
        // la vecchia: se l'aveva scaricata SAM la toglie lui; se l'avevi scelta tu resta dov'è
        var extra = ""
        if (old != null && old != dest.absolutePath) {
            if (EncyclopediaFile.isOwn(ctx, old)) File(old).delete()
            else if (oldName != null) extra = " La vecchia, $oldName, è ancora sul telefono: se vuoi, cancellala dalla cartella Download per liberare spazio."
        }
        val month = Kiwix.monthWords(Kiwix.split(file)?.second) ?: "adesso"
        notify(ctx, "Enciclopedia aggiornata", "SAM usa la Wikipedia di $month.")
        return "Ho aggiornato l'enciclopedia: ora è la Wikipedia di $month.$extra"
    }

    private fun clearBrain(ctx: Context) {
        prefs(ctx).edit().remove("agg_cerv_id").remove("agg_cerv_file").remove("agg_cerv_sha").remove("agg_cerv_passa").remove("agg_cerv_aggiungi").apply()
    }

    private fun processBrain(ctx: Context, stop: () -> Boolean): String? {
        val p = prefs(ctx)
        val id = p.getLong("agg_cerv_id", -1L).takeIf { it >= 0 } ?: return null
        val file = p.getString("agg_cerv_file", null) ?: run { clearBrain(ctx); return null }
        val want = p.getString("agg_cerv_sha", null)
        val switch = p.getBoolean("agg_cerv_passa", false)
        val addOnly = p.getBoolean("agg_cerv_aggiungi", false)
        val tmp = File(SamApp.downloadsDir(ctx), file)
        when (query(ctx, id)?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {}
            DownloadManager.STATUS_FAILED, null -> {
                forget(ctx, id); tmp.delete(); clearBrain(ctx)
                return if (switch || addOnly) "Lo scaricamento del cervello non è riuscito: riprova quando vuoi." else null
            }
            else -> return null
        }
        val got = try { Digest.sha256(tmp, stop = stop) } catch (x: InterruptedException) { return null } catch (x: Throwable) { null }
        val bad = when {
            got == null -> "non riesco a leggerlo"
            want != null && !got.equals(want, ignoreCase = true) -> "è arrivato rovinato"
            else -> ModelStore.checkBrainFile(ctx, tmp)
        }
        val name = Brains.byFile(file)?.name ?: file.removeSuffix(".litertlm")
        if (bad != null) {
            forget(ctx, id); tmp.delete(); clearBrain(ctx)
            // un file intero ma che il motore di SAM non sa usare: non si riscarica ogni settimana
            if (got != null && (want == null || got.equals(want, ignoreCase = true))) p.edit().putString("agg_cerv_scartato", got).apply()
            return "Ho scaricato $name, ma non va bene: $bad. Non l'ho installato."
        }
        if (addOnly) {
            // un cervello per un lavoro: si mette con gli altri, il principale resta quello di prima
            val dest = File(SamApp.modelsDir(ctx), file)
            dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            forget(ctx, id); clearBrain(ctx)
            rememberSha(ctx, dest, got!!)
            val what = if (ModelRouter.codes(file)) "quando mi chiedi di scrivere codice" else if (ModelRouter.sees(file)) "quando devo guardare" else "quando serve"
            notify(ctx, "Cervello pronto", "SAM usa $name $what.")
            return "Ho scaricato $name: lo uso da solo $what, poi torno al cervello di sempre."
        }
        if (switch) {
            // un altro cervello che hai chiesto: si mette con gli altri e si usa subito
            val dest = File(SamApp.modelsDir(ctx), file)
            dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            forget(ctx, id); clearBrain(ctx)
            rememberSha(ctx, dest, got!!)
            SamApp.clearEngineCache(ctx, file)
            prefs(ctx).edit().putString("cervello_prima", SamApp.llmFile(ctx)?.name).apply()
            SamApp.setActiveBrain(ctx, file)
            if (VoiceService.running) VoiceService.reloadBrain(ctx)
            notify(ctx, "Cervello pronto", "SAM usa $name.")
            return "Ho scaricato $name e lo sto caricando: la prima volta ci mette qualche minuto."
        }
        // una versione nuova del cervello che usi: resta da parte finché non dici di installarla
        val ready = File(SamApp.modelsDir(ctx), "nuovo").apply { mkdirs() }
        val dest = File(ready, file)
        dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        forget(ctx, id); clearBrain(ctx)
        p.edit().putString("agg_pronto", dest.absolutePath).putString("agg_pronto_sha", got).remove("agg_pronto_chiesto").apply()
        notify(ctx, "Cervello nuovo pronto", "Di' a SAM: installa il cervello nuovo.")
        return null   // lo chiede SAM a voce ("lo installo?")
    }

    // ------------------------------------------------------------------ il cervello nuovo da installare
    class ReadyBrain(val file: File, val sha: String?) {
        val name: String get() = Brains.byFile(file.name)?.name ?: file.nameWithoutExtension
    }

    /** Il cervello nuovo già scaricato e controllato, che aspetta il tuo sì. null = nessuno. */
    fun readyBrain(ctx: Context): ReadyBrain? {
        val path = prefs(ctx).getString("agg_pronto", null) ?: return null
        val f = File(path)
        if (!f.isFile) { prefs(ctx).edit().remove("agg_pronto").remove("agg_pronto_sha").apply(); return null }
        return ReadyBrain(f, prefs(ctx).getString("agg_pronto_sha", null))
    }

    /** Si chiede di installarlo al massimo una volta ogni 12 ore (se dici di no, te lo richiede più avanti). */
    fun shouldAskInstall(ctx: Context): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong("agg_pronto_chiesto", 0L) > 12 * 3_600_000L

    fun markAsked(ctx: Context) { prefs(ctx).edit().putLong("agg_pronto_chiesto", System.currentTimeMillis()).apply() }

    /**
     * Mette il cervello nuovo al posto di quello vecchio, che resta da parte (".vecchio") finché il nuovo non è partito.
     * Il motore deve essere già fermo. Ritorna il file nuovo, o null se non c'è.
     */
    fun swapInBrain(ctx: Context): File? {
        val r = readyBrain(ctx) ?: return null
        val models = SamApp.modelsDir(ctx)
        val dest = File(models, r.file.name)
        val backup = File(models, r.file.name + ".vecchio")
        backup.delete()
        if (dest.exists() && !dest.renameTo(backup)) return null
        if (!r.file.renameTo(dest)) {
            try { r.file.copyTo(dest, overwrite = true); r.file.delete() }
            catch (e: Exception) { if (backup.exists()) backup.renameTo(dest); return null }
        }
        SamApp.clearEngineCache(ctx, dest.name)
        SamApp.setActiveBrain(ctx, dest.name)
        r.sha?.let { rememberSha(ctx, dest, it) }
        prefs(ctx).edit().remove("agg_pronto").remove("agg_pronto_sha").apply()
        return dest
    }

    /** Il cervello nuovo è partito: il vecchio non serve più. */
    fun keepNewBrain(ctx: Context, fileName: String) { File(SamApp.modelsDir(ctx), "$fileName.vecchio").delete() }

    /** Il cervello nuovo non parte: si rimette il vecchio. */
    fun rollbackBrain(ctx: Context, fileName: String) {
        val models = SamApp.modelsDir(ctx)
        val backup = File(models, "$fileName.vecchio")
        if (!backup.exists()) return
        File(models, fileName).delete()
        backup.renameTo(File(models, fileName))
        SamApp.clearEngineCache(ctx, fileName)
    }

    /** L'impronta del cervello sul telefono (calcolata una volta, poi ricordata finché il file non cambia). */
    private fun installedSha(ctx: Context, f: File, stop: () -> Boolean): String? {
        val key = "cerv_sha_" + f.name
        val stamp = "${f.length()}|${f.lastModified()}"
        prefs(ctx).getString(key, null)?.let { v -> if (v.substringBeforeLast('|') == stamp) return v.substringAfterLast('|') }
        val sha = try { Digest.sha256(f, stop = stop) } catch (e: Throwable) { return null }
        rememberSha(ctx, f, sha)
        return sha
    }

    private fun rememberSha(ctx: Context, f: File, sha: String) {
        prefs(ctx).edit().putString("cerv_sha_" + f.name, "${f.length()}|${f.lastModified()}|$sha").apply()
    }

    // ------------------------------------------------------------------ a che punto siamo
    private fun progress(ctx: Context, id: Long, what: String): String? {
        val d = query(ctx, id) ?: return null
        val pct = if (d.total > 0) (d.done * 100 / d.total).toInt() else 0
        val tot = if (d.total > 0) " di ${Brains.gb(d.total)}" else ""
        return when (d.status) {
            DownloadManager.STATUS_RUNNING -> "Sto scaricando $what: sono al $pct per cento$tot."
            DownloadManager.STATUS_PENDING -> "Lo scaricamento di $what sta per partire."
            DownloadManager.STATUS_PAUSED -> when (d.reason) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI, DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
                    "Lo scaricamento di $what aspetta il Wi-Fi: sono al $pct per cento."
                else -> "Lo scaricamento di $what è in pausa al $pct per cento: riprende da solo."
            }
            DownloadManager.STATUS_SUCCESSFUL -> "Ho finito di scaricare $what: lo sto controllando."
            else -> null
        }
    }

    private fun zimProgress(ctx: Context): String? {
        val p = prefs(ctx)
        val id = p.getLong("agg_zim_id", -1L).takeIf { it >= 0 } ?: return null
        val month = Kiwix.monthWords(Kiwix.split(p.getString("agg_zim_file", null))?.second)
        return progress(ctx, id, "la Wikipedia" + (month?.let { " di $it" } ?: ""))
    }

    private fun brainProgress(ctx: Context): String? {
        val p = prefs(ctx)
        val id = p.getLong("agg_cerv_id", -1L).takeIf { it >= 0 } ?: return null
        val name = p.getString("agg_cerv_file", null)?.let { Brains.byFile(it)?.name ?: it } ?: "il cervello"
        return progress(ctx, id, name)
    }

    /** Gli scaricamenti in corso e il cervello pronto da installare, a parole. null = niente. */
    fun statusText(ctx: Context): String? {
        val parts = listOfNotNull(zimProgress(ctx), brainProgress(ctx),
            readyBrain(ctx)?.let { "${it.name} nuovo è pronto da installare: di' installa il cervello nuovo." })
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** Per la configurazione: aggiornamenti sì o no, l'ultimo controllo e com'è andato, gli scaricamenti in corso. */
    fun setupText(ctx: Context): String {
        val p = prefs(ctx)
        val sb = StringBuilder(if (autoOn(ctx)) "Aggiornamenti automatici: sì (una volta alla settimana, solo col Wi-Fi) ✓"
                               else "Aggiornamenti automatici: no. Puoi sempre controllare a mano.")
        val last = p.getLong("agg_ultimo", 0L)
        if (last > 0) {
            sb.append("\nUltimo controllo: ").append(SimpleDateFormat("d MMMM 'alle' HH:mm", Locale.ITALIAN).format(Date(last)))
            p.getString("agg_esito", null)?.takeIf { it.isNotBlank() }?.let { sb.append(". ").append(it) }
        }
        statusText(ctx)?.let { sb.append("\n").append(it) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ novità da dire
    /** Una novità da dire a voce la prossima volta che chiami SAM (oltre alla notifica). */
    fun addNews(ctx: Context, text: String) {
        val old = prefs(ctx).getString("notizie", null)
        prefs(ctx).edit().putString("notizie", if (old.isNullOrBlank()) text else "$old $text").apply()
    }

    /** Le novità da dire (una volta sola). null = nessuna. */
    fun takeNews(ctx: Context): String? {
        val n = prefs(ctx).getString("notizie", null)?.takeIf { it.isNotBlank() } ?: return null
        prefs(ctx).edit().remove("notizie").apply()
        return n
    }

    private fun notify(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Aggiornamenti di SAM", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, NID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NID, Notification.Builder(ctx, CH)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_sam)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {}
    }
}

/** Il lavoro degli aggiornamenti, fatto partire da Android (col Wi-Fi) anche quando SAM è spento. */
class UpdateJob : JobService() {
    /** Android chiede di fermarsi: il controllo di un file lungo si interrompe e si riprende la volta dopo */
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val periodic = params.jobId == Updater.JOB_PERIODIC
        stopped = false
        Thread {
            try {
                Updater.processDownloads(this) { stopped }
                if (periodic && Updater.autoOn(this) && !stopped) Updater.check(this, force = false) { stopped }
            } catch (e: Throwable) {}
            try { jobFinished(params, false) } catch (e: Throwable) {}
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true   // si riprova più avanti
    }
}

/** Android avvisa che uno scaricamento è finito (solo il gestore degli scaricamenti può mandare questo avviso). */
class DownloadDone : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            Updater.onDownloadComplete(ctx, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
    }
}
