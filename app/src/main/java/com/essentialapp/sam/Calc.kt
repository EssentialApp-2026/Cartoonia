package com.essentialapp.sam

/**
 * Piccolo valutatore di espressioni per i comandi tipo "quanto fa 15 per 23"
 * o "quanto fa il 20 per cento di 150". Kotlin puro, nessun eval pericoloso:
 * un parser a discesa ricorsiva che accetta solo numeri e + - * / ( ).
 */
object Calc {

    fun eval(raw: String): Double? {
        var e = " " + raw.lowercase() + " "
        for (k in listOf("quanto fa", "quanto è", "quanto e", "quant'è", "calcola", "quanto vale"))
            e = e.replace(k, " ")
        // il riconoscitore vocale scrive i numeri a parole: "dodici per sette", "il venti per cento di centocinquanta"
        e = e.replace("per cento", " % ").replace("percento", " % ")
        e = " " + Num.wordsToDigits(e.replace(Regex("\\s+"), " ").trim(), all = true) + " "
        e = Regex("(\\d)\\s+virgola\\s+(\\d)").replace(e, "$1.$2")
        // "20 per cento di 150" -> (20/100)*150
        e = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:per cento|percento|%)\\s*di\\s*").replace(e) { "(${it.groupValues[1].replace(',', '.')}/100)*" }
        e = e.replace("al quadrato", "^2").replace("al cubo", "^3")
        val sost = listOf(
            "diviso per" to "/", "diviso" to "/", "fratto" to "/", "÷" to "/",
            "moltiplicato per" to "*", "moltiplicato" to "*", "×" to "*",
            "per" to "*", "più" to "+", "piu" to "+", "meno" to "-",
            "elevato alla" to "^", "elevato a" to "^", "alla" to "^", "virgola" to "."
        )
        for ((x, y) in sost) e = e.replace(x, y)
        e = Regex("(?<=\\d)\\s*x\\s*(?=\\d)").replace(e, "*")
        e = Regex("(?<=\\d),(?=\\d)").replace(e, ".")
        e = Regex("[^\\d.+\\-*/^()\\s]").replace(e, "").trim()
        if (!Regex("\\d").containsMatchIn(e)) return null
        return try {
            val p = Parser(e)
            val v = p.parseExpr()
            if (!p.atEnd()) null else if (v.isFinite()) v else null
        } catch (ex: Exception) { null }
    }

    /** Numero detto all'italiana: intero se tondo, altrimenti con la virgola. */
    fun say(x: Double): String {
        if (kotlin.math.abs(x - Math.round(x)) < 1e-9) return Math.round(x).toString()
        return "%.4f".format(x).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd(): Boolean { skip(); return i >= s.length }
        private fun skip() { while (i < s.length && s[i] == ' ') i++ }
        private fun peek(): Char? { skip(); return if (i < s.length) s[i] else null }

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) when (peek()) {
                '+' -> { i++; v += parseTerm() }
                '-' -> { i++; v -= parseTerm() }
                else -> return v
            }
        }
        private fun parseTerm(): Double {
            var v = parsePow()
            while (true) when (peek()) {
                '*' -> { i++; v *= parsePow() }
                '/' -> { i++; v /= parsePow() }
                else -> return v
            }
        }
        private fun parsePow(): Double {
            val b = parseUnary()
            return if (peek() == '^') { i++; Math.pow(b, parsePow()) } else b
        }
        private fun parseUnary(): Double = when (peek()) {
            '-' -> { i++; -parseUnary() }
            '+' -> { i++; parseUnary() }
            else -> parseAtom()
        }
        private fun parseAtom(): Double {
            if (peek() == '(') {
                i++
                val v = parseExpr()
                if (peek() == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) throw IllegalStateException("numero atteso")
            return s.substring(start, i).toDouble()
        }
    }
}
