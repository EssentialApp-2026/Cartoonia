package com.essentialapp.sam

/**
 * Mini JSON in Kotlin puro (nessuna dipendenza): serve per parlare con il
 * motore IA, che scambia messaggi in JSON. Supporta oggetti, array, stringhe,
 * numeri, true/false/null.
 */
object Json {

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** {"role":"user","content":"..."}; con un'immagine: content = [immagine (percorso del file), testo]. */
    fun userMessage(text: String, image: String? = null): String =
        if (image == null) "{\"role\":\"user\",\"content\":${quote(text)}}"
        else "{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"path\":${quote(image)}},{\"type\":\"text\",\"text\":${quote(text)}}]}"

    fun parse(s: String): Any? {
        val p = P(s)
        val v = p.value()
        return v
    }

    /** Estrae il testo da un messaggio del modello: content stringa o lista di {type:text,text:...}. */
    fun messageText(json: String): String {
        val root = try { parse(json) } catch (e: Exception) { return json }
        val m = root as? Map<*, *> ?: return ""
        return when (val c = m["content"]) {
            is String -> c
            is List<*> -> c.joinToString("") { part ->
                val pm = part as? Map<*, *>
                if (pm != null && (pm["type"] == null || pm["type"] == "text")) (pm["text"] as? String) ?: "" else ""
            }
            else -> ""
        }
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("fine inattesa")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }
        fun expect(w: String) {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("atteso $w")
            i += w.length
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i] != ':') throw IllegalArgumentException("atteso :")
                i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m } else -> throw IllegalArgumentException("atteso , o }") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l } else -> throw IllegalArgumentException("atteso , o ]") }
            }
        }
        fun str(): String {
            if (s[i] != '"') throw IllegalArgumentException("attesa stringa")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
