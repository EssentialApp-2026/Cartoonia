package com.essentialapp.sam

/**
 * Decompressore zstd (RFC 8878) in Kotlin puro, senza librerie: serve a leggere l'enciclopedia, perché i file .zim
 * di Wikipedia hanno i testi compressi così. Solo decompressione e senza dizionari (i .zim non li usano).
 * Collaudato sul PC contro la libreria zstd ufficiale, su testi, dati casuali e tutti i livelli di compressione.
 */
object Zstd {
    class ZstdException(msg: String) : Exception(msg)

    private const val MAGIC = 0xFD2FB528.toInt()

    /** Decomprime uno o più frame zstd in [src] da [off] per [len] byte. [sizeHint] = dimensione attesa, se si sa. */
    fun decompress(src: ByteArray, off: Int = 0, len: Int = src.size - off, sizeHint: Int = 0): ByteArray {
        val out = Out(if (sizeHint > 0) sizeHint else maxOf(len * 4, 1024))
        var p = off
        val end = off + len
        var frames = 0
        while (end - p >= 4) {
            val magic = le32(src, p)
            if (magic == MAGIC) { p = Frame(src, p + 4, end, out).decode(); frames++ }
            else if ((magic and 0xFFFFFFF0.toInt()) == 0x184D2A50) p += 8 + le32(src, p + 4)   // frame da saltare
            else if (frames > 0) break                                                          // dopo i dati: niente altro
            else throw ZstdException("non è un dato zstd")
        }
        if (frames == 0) throw ZstdException("nessun frame zstd")
        return out.toArray()
    }

    // ------------------------------------------------------------------ utilità
    private fun le32(s: ByteArray, p: Int): Int =
        (s[p].toInt() and 0xFF) or ((s[p + 1].toInt() and 0xFF) shl 8) or ((s[p + 2].toInt() and 0xFF) shl 16) or ((s[p + 3].toInt() and 0xFF) shl 24)

    private fun le16(s: ByteArray, p: Int): Int = (s[p].toInt() and 0xFF) or ((s[p + 1].toInt() and 0xFF) shl 8)

    private fun highBit(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x)

    /** Uscita che cresce da sola. */
    private class Out(cap: Int) {
        var buf = ByteArray(cap)
        var size = 0
        fun ensure(extra: Int) {
            if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
        }
        fun write(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            ensure(len); System.arraycopy(src, off, buf, size, len); size += len
        }
        fun fill(b: Byte, n: Int) {
            ensure(n); java.util.Arrays.fill(buf, size, size + n, b); size += n
        }
        fun copyMatch(offset: Int, len: Int) {
            ensure(len)
            var from = size - offset
            if (offset >= len) { System.arraycopy(buf, from, buf, size, len); size += len }
            else for (i in 0 until len) buf[size++] = buf[from++]   // si sovrappone: byte per byte
        }
        fun toArray(): ByteArray = buf.copyOf(size)
    }

    /** Lettura all'indietro dei bit (come li scrive zstd): il primo campo letto è l'ultimo scritto. */
    private class BackBits(private val s: ByteArray, private val start: Int, end: Int) {
        var pos: Long
        init {
            if (end <= start) throw ZstdException("flusso di bit vuoto")
            val last = s[end - 1].toInt() and 0xFF
            if (last == 0) throw ZstdException("flusso di bit senza segno di fine")
            pos = (end - 1 - start).toLong() * 8 + highBit(last)
        }
        fun read(n: Int): Int { if (n == 0) return 0; pos -= n; return extract(pos, n) }
        fun peek(n: Int): Int = extract(pos - n, n)
        fun skip(n: Int) { pos -= n }
        fun overflow() = pos < 0
        private fun extract(p: Long, n: Int): Int {
            if (n == 0) return 0
            val first = Math.floorDiv(p, 8L)
            var b = Math.floorDiv(p + n - 1, 8L)
            var v = 0L
            while (b >= first) { v = (v shl 8) or (if (b >= 0) (s[start + b.toInt()].toLong() and 0xFF) else 0L); b-- }
            return ((v ushr (p - first * 8).toInt()) and ((1L shl n) - 1)).toInt()
        }
    }

    /** Tabella FSE per decodificare: simbolo, bit da leggere e base del prossimo stato, per ogni stato. */
    private class Fse(val acc: Int, val symbol: IntArray, val nbBits: IntArray, val newState: IntArray) {
        fun init(br: BackBits) = br.read(acc)
        fun update(state: Int, br: BackBits) = newState[state] + br.read(nbBits[state])
    }

    private fun buildFse(norm: IntArray, maxSym: Int, acc: Int): Fse {
        val size = 1 shl acc
        val symbol = IntArray(size); val nbBits = IntArray(size); val newState = IntArray(size)
        val next = IntArray(maxSym + 1)
        var high = size - 1
        for (s in 0..maxSym) {
            if (norm[s] == -1) { symbol[high--] = s; next[s] = 1 } else next[s] = norm[s]
        }
        val step = (size shr 1) + (size shr 3) + 3
        val mask = size - 1
        var pos = 0
        for (s in 0..maxSym) {
            for (i in 0 until norm[s]) {
                symbol[pos] = s
                do { pos = (pos + step) and mask } while (pos > high)
            }
        }
        if (pos != 0) throw ZstdException("tabella FSE non valida")
        for (u in 0 until size) {
            val s = symbol[u]
            val ns = next[s]++
            val nb = acc - highBit(ns)
            nbBits[u] = nb
            newState[u] = (ns shl nb) - size
        }
        return Fse(acc, symbol, nbBits, newState)
    }

    /** Legge la descrizione di una tabella FSE (probabilità normalizzate). Ritorna la tabella e i byte usati. */
    private fun readFse(s: ByteArray, q: Int, limit: Int, maxLog: Int, maxSym: Int): Pair<Fse, Int> {
        var bitPos = 0L
        fun peek(n: Int): Int {
            var v = 0L
            val first = bitPos / 8
            val last = (bitPos + n - 1) / 8
            var b = last
            while (b >= first) { val i = q + b.toInt(); v = (v shl 8) or (if (i < limit) (s[i].toLong() and 0xFF) else 0L); b-- }
            return ((v ushr (bitPos - first * 8).toInt()) and ((1L shl n) - 1)).toInt()
        }
        val acc = peek(4) + 5; bitPos += 4
        if (acc > maxLog) throw ZstdException("precisione FSE troppo alta")
        var remaining = (1 shl acc) + 1
        var threshold = 1 shl acc
        var nbBits = acc + 1
        val norm = IntArray(maxSym + 1)
        var sym = 0
        var prev0 = false
        while (remaining > 1 && sym <= maxSym) {
            if (prev0) {
                var n0 = sym
                while (true) { val r = peek(2); bitPos += 2; n0 += r; if (r != 3) break }
                if (n0 > maxSym) throw ZstdException("troppi simboli nulli")
                while (sym < n0) norm[sym++] = 0
            }
            val max = (2 * threshold - 1) - remaining
            val v = peek(nbBits)
            var count: Int
            if ((v and (threshold - 1)) < max) { count = v and (threshold - 1); bitPos += nbBits - 1 }
            else { count = v and (2 * threshold - 1); if (count >= threshold) count -= max; bitPos += nbBits }
            count--
            remaining -= if (count < 0) -count else count
            norm[sym++] = count
            prev0 = count == 0
            while (remaining < threshold) { nbBits--; threshold = threshold shr 1 }
        }
        if (remaining != 1) throw ZstdException("probabilità FSE non valide")
        return buildFse(norm, sym - 1, acc) to ((bitPos + 7) / 8).toInt()
    }

    private fun rle(symbol: Int) = Fse(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

    // le tabelle predefinite (RFC 8878, 3.1.1.3.2.2)
    private val LL_DEFAULT = intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
        -1, -1, -1, -1)
    private val ML_DEFAULT = intArrayOf(1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1)
    private val OF_DEFAULT = intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1)
    private val LL_PRE by lazy { buildFse(LL_DEFAULT, 35, 6) }
    private val ML_PRE by lazy { buildFse(ML_DEFAULT, 52, 6) }
    private val OF_PRE by lazy { buildFse(OF_DEFAULT, 28, 5) }

    private val LL_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64,
        128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    private val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11,
        12, 13, 14, 15, 16)
    private val ML_BASE = IntArray(53).also { b ->
        for (i in 0..31) b[i] = i + 3
        val rest = intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
        for (i in rest.indices) b[32 + i] = rest[i]
    }
    private val ML_BITS = IntArray(53).also { b ->
        val rest = intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        for (i in rest.indices) b[32 + i] = rest[i]
    }

    /** Tabella di Huffman per i caratteri: indice = i prossimi [maxBits] bit. */
    private class Huffman(val symbol: ByteArray, val nbBits: ByteArray, val maxBits: Int)

    private fun readHuffman(s: ByteArray, q: Int, limit: Int): Pair<Huffman, Int> {
        val header = s[q].toInt() and 0xFF
        val weights = IntArray(257)
        var n = 0
        val used: Int
        if (header >= 128) {
            n = header - 127
            used = 1 + (n + 1) / 2
            for (i in 0 until n) {
                val b = s[q + 1 + i / 2].toInt() and 0xFF
                weights[i] = if (i % 2 == 0) b ushr 4 else b and 0xF
            }
        } else {
            used = 1 + header
            if (q + used > limit) throw ZstdException("pesi di Huffman oltre il blocco")
            val (t, tUsed) = readFse(s, q + 1, q + used, 6, 255)
            val br = BackBits(s, q + 1 + tUsed, q + used)
            var s1 = t.init(br)
            var s2 = t.init(br)
            while (true) {
                if (n > 254) throw ZstdException("troppi pesi di Huffman")
                weights[n++] = t.symbol[s1]; s1 = t.update(s1, br)
                if (br.overflow()) { weights[n++] = t.symbol[s2]; break }
                weights[n++] = t.symbol[s2]; s2 = t.update(s2, br)
                if (br.overflow()) { weights[n++] = t.symbol[s1]; break }
            }
        }
        var sum = 0
        for (i in 0 until n) { val w = weights[i]; if (w > 11) throw ZstdException("peso di Huffman troppo grande"); if (w > 0) sum += 1 shl (w - 1) }
        if (sum == 0) throw ZstdException("pesi di Huffman vuoti")
        val maxBits = highBit(sum) + 1
        val left = (1 shl maxBits) - sum
        if (left == 0 || (left and (left - 1)) != 0) throw ZstdException("pesi di Huffman non validi")
        weights[n++] = highBit(left) + 1
        val size = 1 shl maxBits
        val sym = ByteArray(size); val nb = ByteArray(size)
        var pos = 0
        for (w in 1..maxBits) for (i in 0 until n) if (weights[i] == w) {
            val cnt = 1 shl (w - 1); val bits = (maxBits + 1 - w).toByte()
            for (k in 0 until cnt) { sym[pos] = i.toByte(); nb[pos] = bits; pos++ }
        }
        if (pos != size) throw ZstdException("tabella di Huffman incompleta")
        return Huffman(sym, nb, maxBits) to used
    }

    private fun decodeHuffman(h: Huffman, s: ByteArray, start: Int, end: Int, out: ByteArray, from: Int, count: Int) {
        if (count <= 0) return
        val br = BackBits(s, start, end)
        val mb = h.maxBits
        for (i in 0 until count) {
            val v = br.peek(mb)
            out[from + i] = h.symbol[v]
            br.skip(h.nbBits[v].toInt())
        }
    }

    // ------------------------------------------------------------------ un frame
    private class Frame(private val s: ByteArray, private var p: Int, private val end: Int, private val out: Out) {
        private var huf: Huffman? = null
        private var llT: Fse? = null
        private var ofT: Fse? = null
        private var mlT: Fse? = null
        private val rep = intArrayOf(1, 4, 8)
        private val frameStart = out.size

        fun decode(): Int {
            val fhd = s[p++].toInt() and 0xFF
            val fcsFlag = fhd ushr 6
            val single = (fhd shr 5) and 1 == 1
            val checksum = (fhd shr 2) and 1 == 1
            val dictFlag = fhd and 3
            if ((fhd shr 3) and 1 == 1) throw ZstdException("bit riservato acceso")
            if (!single) p++                                   // finestra: si tiene tutto, non serve
            p += intArrayOf(0, 1, 2, 4)[dictFlag]
            val fcsSize = when (fcsFlag) { 0 -> if (single) 1 else 0; 1 -> 2; 2 -> 4; else -> 8 }
            var content = -1L
            if (fcsSize > 0) {
                content = 0
                for (i in 0 until fcsSize) content = content or ((s[p + i].toLong() and 0xFF) shl (8 * i))
                if (fcsSize == 2) content += 256
                p += fcsSize
            }
            if (content in 1..(256L shl 20)) out.ensure(content.toInt())
            while (true) {
                if (p + 3 > end) throw ZstdException("blocco troncato")
                val bh = (s[p].toInt() and 0xFF) or ((s[p + 1].toInt() and 0xFF) shl 8) or ((s[p + 2].toInt() and 0xFF) shl 16)
                p += 3
                val last = bh and 1 == 1
                val type = (bh shr 1) and 3
                val size = bh ushr 3
                when (type) {
                    0 -> { if (p + size > end) throw ZstdException("blocco troncato"); out.write(s, p, size); p += size }
                    1 -> { out.fill(s[p], size); p += 1 }
                    2 -> { if (p + size > end) throw ZstdException("blocco troncato"); block(p, p + size); p += size }
                    else -> throw ZstdException("tipo di blocco riservato")
                }
                if (last) break
            }
            if (checksum) p += 4
            return p
        }

        private fun block(start: Int, end: Int) {
            var q = start
            // --- i caratteri (literals)
            val b0 = s[q].toInt() and 0xFF
            val ltype = b0 and 3
            val sf = (b0 shr 2) and 3
            val regen: Int
            var csize = 0
            val hdr: Int
            var streams = 1
            if (ltype < 2) {
                when (sf) {
                    0, 2 -> { regen = b0 ushr 3; hdr = 1 }
                    1 -> { regen = (b0 ushr 4) + ((s[q + 1].toInt() and 0xFF) shl 4); hdr = 2 }
                    else -> { regen = (b0 ushr 4) + ((s[q + 1].toInt() and 0xFF) shl 4) + ((s[q + 2].toInt() and 0xFF) shl 12); hdr = 3 }
                }
            } else {
                when (sf) {
                    0, 1 -> {
                        val h = b0 or ((s[q + 1].toInt() and 0xFF) shl 8) or ((s[q + 2].toInt() and 0xFF) shl 16)
                        regen = (h ushr 4) and 0x3FF; csize = (h ushr 14) and 0x3FF; hdr = 3; streams = if (sf == 0) 1 else 4
                    }
                    2 -> {
                        val h = le32(s, q)
                        regen = (h ushr 4) and 0x3FFF; csize = (h ushr 18) and 0x3FFF; hdr = 4; streams = 4
                    }
                    else -> {
                        val h = (le32(s, q).toLong() and 0xFFFFFFFFL) or ((s[q + 4].toLong() and 0xFF) shl 32)
                        regen = ((h ushr 4) and 0x3FFFF).toInt(); csize = ((h ushr 22) and 0x3FFFF).toInt(); hdr = 5; streams = 4
                    }
                }
            }
            q += hdr
            val lit: ByteArray
            val litOff: Int
            when (ltype) {
                0 -> { lit = s; litOff = q; q += regen }
                1 -> { lit = ByteArray(regen); java.util.Arrays.fill(lit, s[q]); litOff = 0; q += 1 }
                else -> {
                    val lend = q + csize
                    if (lend > end) throw ZstdException("caratteri oltre il blocco")
                    var tq = q
                    if (ltype == 2) { val (h, used) = readHuffman(s, q, lend); huf = h; tq += used }
                    val h = huf ?: throw ZstdException("tabella di Huffman mancante")
                    lit = ByteArray(regen); litOff = 0
                    if (streams == 1) decodeHuffman(h, s, tq, lend, lit, 0, regen)
                    else {
                        val s1 = le16(s, tq); val s2 = le16(s, tq + 2); val s3 = le16(s, tq + 4)
                        val a = tq + 6; val b = a + s1; val c = b + s2; val d = c + s3
                        val seg = (regen + 3) / 4
                        val lastSeg = regen - 3 * seg
                        if (lastSeg < 0 || d > lend) throw ZstdException("flussi dei caratteri non validi")
                        decodeHuffman(h, s, a, b, lit, 0, seg)
                        decodeHuffman(h, s, b, c, lit, seg, seg)
                        decodeHuffman(h, s, c, d, lit, 2 * seg, seg)
                        decodeHuffman(h, s, d, lend, lit, 3 * seg, lastSeg)
                    }
                    q = lend
                }
            }
            // --- le sequenze (copie dal già scritto)
            if (q >= end) { out.write(lit, litOff, regen); return }
            var nbSeq = s[q++].toInt() and 0xFF
            if (nbSeq == 0) { out.write(lit, litOff, regen); return }
            if (nbSeq >= 128) {
                if (nbSeq < 255) { nbSeq = ((nbSeq - 128) shl 8) + (s[q].toInt() and 0xFF); q++ }
                else { nbSeq = (s[q].toInt() and 0xFF) + ((s[q + 1].toInt() and 0xFF) shl 8) + 0x7F00; q += 2 }
            }
            val modes = s[q++].toInt() and 0xFF
            // l'ordine nel blocco: lunghezze dei caratteri, distanze, lunghezze delle copie
            val ll: Fse; val of: Fse; val ml: Fse
            run {
                val r = table(modes ushr 6, q, end, LL_PRE, llT, 9, 35); ll = r.first; q = r.second
            }
            run {
                val r = table((modes shr 4) and 3, q, end, OF_PRE, ofT, 8, 31); of = r.first; q = r.second
            }
            run {
                val r = table((modes shr 2) and 3, q, end, ML_PRE, mlT, 9, 52); ml = r.first; q = r.second
            }
            llT = ll; ofT = of; mlT = ml

            val br = BackBits(s, q, end)
            var llS = ll.init(br)
            var ofS = of.init(br)
            var mlS = ml.init(br)
            var litPos = litOff
            val litEnd = litOff + regen
            for (i in 0 until nbSeq) {
                val ofCode = of.symbol[ofS]
                val mlCode = ml.symbol[mlS]
                val llCode = ll.symbol[llS]
                if (ofCode > 31 || mlCode > 52 || llCode > 35) throw ZstdException("codice non valido")
                val ofValue = (1L shl ofCode) + (if (ofCode > 0) br.read(ofCode).toLong() and 0xFFFFFFFFL else 0L)
                val mlen = ML_BASE[mlCode] + br.read(ML_BITS[mlCode])
                val llen = LL_BASE[llCode] + br.read(LL_BITS[llCode])
                val offset: Int
                if (ofValue > 3) {
                    offset = (ofValue - 3).toInt()
                    rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset
                } else {
                    val idx = ofValue.toInt() - 1 + (if (llen == 0) 1 else 0)
                    if (idx == 0) offset = rep[0]
                    else {
                        val o = if (idx == 3) rep[0] - 1 else rep[idx]
                        if (idx != 1) rep[2] = rep[1]
                        rep[1] = rep[0]; rep[0] = o
                        offset = o
                    }
                }
                if (llen > 0) {
                    if (litPos + llen > litEnd) throw ZstdException("caratteri esauriti")
                    out.write(lit, litPos, llen); litPos += llen
                }
                if (offset <= 0 || offset > out.size - frameStart) throw ZstdException("distanza non valida")
                out.copyMatch(offset, mlen)
                if (i < nbSeq - 1) {
                    llS = ll.update(llS, br)
                    mlS = ml.update(mlS, br)
                    ofS = of.update(ofS, br)
                }
            }
            if (litPos < litEnd) out.write(lit, litPos, litEnd - litPos)
        }

        /** Una delle tre tabelle delle sequenze, secondo il modo: predefinita, un simbolo solo, descritta, ripetuta. */
        private fun table(mode: Int, q: Int, end: Int, pre: Fse, previous: Fse?, maxLog: Int, maxSym: Int): Pair<Fse, Int> = when (mode) {
            0 -> pre to q
            1 -> rle(s[q].toInt() and 0xFF) to q + 1
            2 -> { val (t, used) = readFse(s, q, end, maxLog, maxSym); t to q + used }
            else -> (previous ?: throw ZstdException("tabella da ripetere mancante")) to q
        }
    }
}
