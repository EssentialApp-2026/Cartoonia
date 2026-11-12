package com.essentialapp.sam

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/**
 * La voce di SAM: quale voce italiana del telefono (solo quelle che funzionano senza internet), con che motore di
 * sintesi, a che velocità e con che tono. Le scelte restano salvate; si cambiano a voce ("cambia voce", "parla più
 * piano", "voce più grave") o nella configurazione.
 */
object VoiceSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE)

    /** Il motore di sintesi scelto (il pacchetto, per esempio quello di Google); null = quello del telefono. */
    fun engine(ctx: Context): String? = prefs(ctx).getString("voce_motore", null)
    fun setEngine(ctx: Context, pkg: String?) {
        prefs(ctx).edit().apply { if (pkg == null) remove("voce_motore") else putString("voce_motore", pkg) }
            .remove("voce_nome").remove("voce_prima").apply()   // le voci di un altro motore hanno altri nomi
    }

    /** La voce scelta (il suo nome nel motore); null = la migliore che c'è. */
    fun voiceName(ctx: Context): String? = prefs(ctx).getString("voce_nome", null)
    /** La voce di prima, per "torna alla voce di prima". */
    fun previousVoice(ctx: Context): String? = prefs(ctx).getString("voce_prima", null)
    fun setVoiceName(ctx: Context, name: String?, previous: String?) {
        prefs(ctx).edit().apply {
            if (name == null) remove("voce_nome") else putString("voce_nome", name)
            if (previous == null) remove("voce_prima") else putString("voce_prima", previous)
        }.apply()
    }

    fun rate(ctx: Context): Float = prefs(ctx).getFloat("voce_velocita", Tuning.NORMAL)
    fun pitch(ctx: Context): Float = prefs(ctx).getFloat("voce_tono", Tuning.NORMAL)
    fun setRate(ctx: Context, r: Float) { prefs(ctx).edit().putFloat("voce_velocita", r).apply() }
    fun setPitch(ctx: Context, p: Float) { prefs(ctx).edit().putFloat("voce_tono", p).apply() }

    /** Le voci italiane del motore installate sul telefono e che non usano internet, dalla migliore. */
    fun italianVoices(tts: TextToSpeech): List<Voice> = try {
        (tts.voices ?: emptySet()).filter { v ->
            v.locale.language == "it" && !v.isNetworkConnectionRequired &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }.sortedWith(Comparator { a, b -> if (a.quality != b.quality) b.quality - a.quality else a.name.compareTo(b.name) })
    } catch (e: Exception) { emptyList() }

    /** Applica voce, velocità e tono salvati. Ritorna la voce usata (null = nessuna voce italiana senza internet). */
    fun apply(ctx: Context, tts: TextToSpeech): Voice? {
        val list = italianVoices(tts)
        val wanted = voiceName(ctx)
        val v = list.firstOrNull { it.name == wanted } ?: list.firstOrNull()
        if (v != null) try { tts.voice = v } catch (e: Exception) {}
        try { tts.setSpeechRate(rate(ctx)) } catch (e: Exception) {}
        try { tts.setPitch(pitch(ctx)) } catch (e: Exception) {}
        return v
    }

    private fun quality(q: Int): String = when {
        q >= Voice.QUALITY_VERY_HIGH -> "qualità molto alta"
        q >= Voice.QUALITY_HIGH -> "qualità alta"
        q >= Voice.QUALITY_NORMAL -> "qualità normale"
        else -> "qualità bassa"
    }

    /** "la voce 2 di 4, qualità alta" (i nomi veri delle voci, tipo it-it-x-itd-local, non dicono niente). */
    fun describe(v: Voice?, list: List<Voice>): String {
        if (v == null) return "nessuna voce italiana"
        val i = list.indexOfFirst { it.name == v.name }
        return (if (i >= 0) "la voce ${i + 1} di ${list.size}" else "una voce") + ", " + quality(v.quality)
    }

    /** Il nome del motore di sintesi ("Servizi di sintesi vocale di Google"), o null. */
    fun engineLabel(tts: TextToSpeech, pkg: String?): String? = try {
        val p = pkg ?: tts.defaultEngine
        tts.engines.firstOrNull { it.name == p }?.label
    } catch (e: Exception) { null }

    /** Il testo di prova quando scegli una voce. */
    const val SAMPLE = "Ciao! Questa è la mia voce: ti piace? Se no, ne proviamo un'altra."
}
