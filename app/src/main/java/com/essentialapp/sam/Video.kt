package com.essentialapp.sam

/**
 * I video (1.16): i veri modelli che creano video sono troppo grandi per un telefono (decine di giga e ore per pochi secondi),
 * quindi SAM fa così: disegna l'immagine con Stable Diffusion e poi la anima come nei documentari (zoom e panoramica lenti,
 * con dissolvenza all'inizio e alla fine) e la salva come video MP4.
 * Qui la parte di calcolo, in Kotlin puro (si collauda senza telefono): il movimento della "telecamera" e i fotogrammi in YUV.
 */
object Motion {
    /** Lato del video (quadrato, multiplo di 16 come vogliono i codificatori). */
    const val SIZE = 720
    const val FPS = 30
    const val SECONDS = 8

    enum class Style(val label: String) { ZOOM_IN("zoom avanti"), ZOOM_OUT("zoom indietro"), PAN_RIGHT("panoramica"), PAN_UP("salita") }

    /** La finestra dell'immagine originale inquadrata: angolo in alto a sinistra ([x], [y]) e lato [size], in pixel. */
    data class Cam(val x: Float, val y: Float, val size: Float)

    /** Lo stile del movimento: sempre lo stesso per la stessa descrizione (così un "rifallo" non cambia a caso). */
    fun styleFor(prompt: String): Style {
        val p = prompt.lowercase()
        return when {
            Regex("\\b(?:panorama|paesaggio|montagn|mare|spiaggia|città|orizzonte|tramonto|alba|cielo|campagna|deserto|foresta|bosco)").containsMatchIn(p) -> Style.PAN_RIGHT
            Regex("\\b(?:grattacielo|torre|albero|razzo|cascata|castello|cattedrale|statua)").containsMatchIn(p) -> Style.PAN_UP
            else -> if ((p.hashCode() and 1) == 0) Style.ZOOM_IN else Style.ZOOM_OUT
        }
    }

    /** Morbido all'inizio e alla fine (niente scatti). */
    fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    /** Dove guarda la telecamera al tempo [t] (0..1) su un'immagine di lato [src]. */
    fun cam(style: Style, t: Float, src: Int): Cam {
        val e = ease(t)
        val s = src.toFloat()
        return when (style) {
            Style.ZOOM_IN -> { val z = s * (1f - 0.30f * e); Cam((s - z) * 0.5f, (s - z) * 0.45f, z) }
            Style.ZOOM_OUT -> { val z = s * (0.70f + 0.30f * e); Cam((s - z) * 0.5f, (s - z) * 0.5f, z) }
            Style.PAN_RIGHT -> { val z = s * 0.72f; Cam((s - z) * e, (s - z) * 0.5f, z) }
            Style.PAN_UP -> { val z = s * 0.72f; Cam((s - z) * 0.5f, (s - z) * (1f - e), z) }
        }
    }

    /** Luminosità del fotogramma [i] su [n]: dissolvenza dal nero e verso il nero (0,6 secondi). */
    fun fade(i: Int, n: Int, fps: Int = FPS): Float {
        val f = (fps * 0.6f).coerceAtLeast(1f)
        return minOf(1f, (i + 1) / f, (n - i) / f).coerceIn(0f, 1f)
    }

    /** Un fotogramma in YUV 4:2:0 a piani separati (I420): Y = lato², poi U e V = (lato/2)² ciascuno. */
    class Frame(val size: Int) {
        val y = ByteArray(size * size)
        val u = ByteArray((size / 2) * (size / 2))
        val v = ByteArray((size / 2) * (size / 2))
    }

    /**
     * Riempie [out] con l'inquadratura [cam] dell'immagine RGB [rgb] (lato [src], 3 byte per pixel), ingrandita a [out].size
     * con interpolazione bilineare, luminosità [light] (0..1). Colori BT.601 a gamma limitata (quella dei video).
     */
    fun render(rgb: ByteArray, src: Int, cam: Cam, light: Float, out: Frame) {
        val n = out.size
        val scale = cam.size / n
        val rowR = IntArray(n); val rowG = IntArray(n); val rowB = IntArray(n)
        val prevR = IntArray(n); val prevG = IntArray(n); val prevB = IntArray(n)
        val lim = src - 1
        for (yy in 0 until n) {
            val sy = (cam.y + (yy + 0.5f) * scale - 0.5f).coerceIn(0f, lim.toFloat())
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, lim); val fy = sy - y0
            for (xx in 0 until n) {
                val sx = (cam.x + (xx + 0.5f) * scale - 0.5f).coerceIn(0f, lim.toFloat())
                val x0 = sx.toInt(); val x1 = minOf(x0 + 1, lim); val fx = sx - x0
                val a = 3 * (y0 * src + x0); val b = 3 * (y0 * src + x1); val c = 3 * (y1 * src + x0); val d = 3 * (y1 * src + x1)
                fun ch(o: Int): Int {
                    val top = (rgb[a + o].toInt() and 0xff) * (1 - fx) + (rgb[b + o].toInt() and 0xff) * fx
                    val bot = (rgb[c + o].toInt() and 0xff) * (1 - fx) + (rgb[d + o].toInt() and 0xff) * fx
                    return ((top * (1 - fy) + bot * fy) * light + 0.5f).toInt().coerceIn(0, 255)
                }
                val r = ch(0); val g = ch(1); val bl = ch(2)
                rowR[xx] = r; rowG[xx] = g; rowB[xx] = bl
                out.y[yy * n + xx] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
            }
            if (yy and 1 == 1) {
                // il colore (U, V) uno ogni 2×2 pixel: la media dei quattro
                val cy = yy / 2
                for (cx in 0 until n / 2) {
                    val x = cx * 2
                    val r = (rowR[x] + rowR[x + 1] + prevR[x] + prevR[x + 1]) / 4
                    val g = (rowG[x] + rowG[x + 1] + prevG[x] + prevG[x + 1]) / 4
                    val bl = (rowB[x] + rowB[x + 1] + prevB[x] + prevB[x + 1]) / 4
                    out.u[cy * (n / 2) + cx] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    out.v[cy * (n / 2) + cx] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            } else { rowR.copyInto(prevR); rowG.copyInto(prevG); rowB.copyInto(prevB) }
        }
    }

    /** Il fotogramma [i] di [n] del video di [rgb] (lato [src]) con lo stile [style]. */
    fun frame(rgb: ByteArray, src: Int, style: Style, i: Int, n: Int, out: Frame) =
        render(rgb, src, cam(style, if (n <= 1) 0f else i / (n - 1f), src), fade(i, n), out)

    /** Da YUV (gamma limitata) a RGB: serve ai collaudi per controllare i colori. */
    fun toRgb(y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
        val c = y - 16; val d = u - 128; val e = v - 128
        fun cl(x: Int) = ((x + 128) shr 8).coerceIn(0, 255)
        return Triple(cl(298 * c + 409 * e), cl(298 * c - 100 * d - 208 * e), cl(298 * c + 516 * d))
    }
}
