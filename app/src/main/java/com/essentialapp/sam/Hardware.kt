package com.essentialapp.sam

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** Legge le caratteristiche del telefono (RAM, processore, spazio) per scegliere il cervello giusto. */
object Hardware {

    fun specs(ctx: Context): PhoneSpecs {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val cores = Runtime.getRuntime().availableProcessors()

        val freqs = ArrayList<Long>()
        val caps = ArrayList<Long>()
        for (i in 0 until cores) {
            readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.let { freqs.add(it) }
            readLong("/sys/devices/system/cpu/cpu$i/cpu_capacity")?.let { caps.add(it) }
        }
        // core grandi: per "capacità" se il kernel la dichiara, altrimenti per frequenza
        val maxCap = caps.maxOrNull() ?: 0L
        val maxFreq = freqs.maxOrNull() ?: 0L
        val big = when {
            caps.size == cores && maxCap > 0 -> caps.count { it >= maxCap * 0.6 }
            freqs.isNotEmpty() && maxFreq > 0 -> freqs.count { it >= maxFreq * 0.85 }
            else -> 0
        }

        val free = try { StatFs(SamApp.modelsDir(ctx).path).availableBytes } catch (e: Exception) { -1L }
        val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifEmpty { Build.HARDWARE ?: "" }
        return PhoneSpecs(mi.totalMem, cores, big, maxFreq / 1_000_000.0, free, soc)
    }

    /** Telefono Xiaomi, Redmi o POCO (MIUI / HyperOS hanno permessi in più). */
    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") ||
        Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /**
     * Permesso Xiaomi "Mostra finestre pop-up durante l'esecuzione in background": senza, il telefono
     * blocca in silenzio le app aperte da SAM in sottofondo. null = non si riesce a saperlo.
     */
    fun xiaomiBackgroundPopups(ctx: Context): Boolean? {
        if (!isXiaomi()) return null
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            val r = m.invoke(ops, 10021, android.os.Process.myUid(), ctx.packageName) as Int
            r == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) { null }
    }

    /** Il servizio di accessibilità di SAM è attivo nelle impostazioni? */
    fun accessibilityOn(ctx: Context): Boolean {
        val list = android.provider.Settings.Secure.getString(ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) { null }
}
