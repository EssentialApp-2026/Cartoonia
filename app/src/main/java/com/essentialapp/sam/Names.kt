package com.essentialapp.sam

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/** 1.22 · Il nome dell'assistente salvato sul telefono, e l'icona con il nome nuovo sulla schermata Home. */
object IdentityStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        Identity.set(p.getString("nome_assistente", null), p.getString("nome_sentito", "")!!.split('|').filter { it.isNotBlank() })
    }

    /** Salva il nome nuovo ([forms] = i modi in cui il riconoscitore lo scrive, imparati). */
    fun save(ctx: Context, name: String?, forms: List<String> = Identity.heard) {
        Identity.set(name, forms)
        prefs(ctx).edit().putString("nome_assistente", Identity.name).putString("nome_sentito", Identity.heard.joinToString("|")).apply()
    }

    fun addHeard(ctx: Context, form: String) { Identity.addHeard(form); save(ctx, Identity.name, Identity.heard) }

    /**
     * Android non lascia cambiare il nome sotto l'icona di un'app già installata: si aggiunge alla schermata Home
     * un'icona di SAM con il nome nuovo (chiede conferma il telefono). false = il telefono non lo permette.
     */
    fun pinShortcut(ctx: Context): Boolean = try {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) false else {
            val info = ShortcutInfo.Builder(ctx, "assistente_" + Identity.name.lowercase().filter { it.isLetterOrDigit() })
                .setShortLabel(Identity.name.take(24))
                .setLongLabel(Identity.name.take(24))
                .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
                .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                .build()
            sm.requestPinShortcut(info, null)
        }
    } catch (e: Exception) { false }
}
