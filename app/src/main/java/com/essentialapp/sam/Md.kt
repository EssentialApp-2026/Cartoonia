package com.essentialapp.sam

/**
 * Il testo formattato delle risposte (Markdown semplice, come nelle app di chat): titoli, **grassetto**, *corsivo*, `codice`,
 * blocchi di codice, elenchi e i numeri delle fonti [1]. Diventa testo semplice + stili ([Style]) che la schermata colora.
 * Kotlin puro: si collauda senza telefono.
 */
object Md {
    enum class Kind { BOLD, ITALIC, CODE, CODE_BLOCK, H1, H2, H3, CITE, BULLET }
    data class Style(val start: Int, val end: Int, val kind: Kind)
    class Rendered(val text: String, val styles: List<Style>)

    fun render(src: String): Rendered {
        val out = StringBuilder()
        val styles = ArrayList<Style>()
        val lines = src.replace("\r", "").split('\n')
        var inCode = false
        var codeStart = 0
        for ((li, line0) in lines.withIndex()) {
            val line = line0.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (!inCode) { inCode = true; if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n'); codeStart = out.length }
                else {
                    inCode = false
                    if (out.endsWith("\n")) out.setLength(out.length - 1)
                    if (out.length > codeStart) styles.add(Style(codeStart, out.length, Kind.CODE_BLOCK))
                    if (li < lines.size - 1) out.append('\n')
                }
                continue
            }
            if (inCode) { out.append(line0).append('\n'); continue }
            var text = line
            var head: Kind? = null
            Regex("^\\s{0,3}(#{1,6})\\s+(.*)$").find(text)?.let { m ->
                head = when (m.groupValues[1].length) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
                text = m.groupValues[2].trimEnd('#', ' ')
            }
            var bullet = false
            Regex("^(\\s*)[-*•+]\\s+(.*)$").find(text)?.let { m -> bullet = true; text = m.groupValues[1] + "• " + m.groupValues[2] }
            val lineStart = out.length
            inline(text, out, styles)
            if (head != null && out.length > lineStart) styles.add(Style(lineStart, out.length, head!!))
            if (bullet) styles.add(Style(lineStart, out.length, Kind.BULLET))
            if (li < lines.size - 1) out.append('\n')
        }
        if (inCode && out.length > codeStart) {   // blocco non chiuso (risposta ancora in arrivo)
            if (out.endsWith("\n")) out.setLength(out.length - 1)
            styles.add(Style(codeStart, out.length, Kind.CODE_BLOCK))
        }
        return Rendered(out.toString().trimEnd(), styles.filter { it.end <= out.toString().trimEnd().length && it.end > it.start })
    }

    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)([^_]+?)(?<!\\s)_(?![\\w_])|\\[(\\d+(?:\\s*[,–-]\\s*\\d+)*)\\]")

    private fun inline(text: String, out: StringBuilder, styles: MutableList<Style>) {
        var i = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, i, m.range.first)
            val g = m.groupValues
            val (content, kind) = when {
                g[1].isNotEmpty() -> g[1] to Kind.BOLD
                g[2].isNotEmpty() -> g[2] to Kind.BOLD
                g[3].isNotEmpty() -> g[3] to Kind.CODE
                g[4].isNotEmpty() -> g[4] to Kind.ITALIC
                g[5].isNotEmpty() -> g[5] to Kind.ITALIC
                else -> "[${g[6]}]" to Kind.CITE
            }
            val s = out.length
            out.append(content)
            styles.add(Style(s, out.length, kind))
            i = m.range.last + 1
        }
        out.append(text, i, text.length)
    }
}
