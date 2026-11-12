package com.essentialapp.sam

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Modalità agente (1.16, come OK Computer di Kimi): "fammi una presentazione su…". Il cervello scrive la scaletta, SAM la
 * trasforma in slide PowerPoint modificabili (.pptx) e in PDF. Il .pptx parte da un modello vero (quello di python-pptx,
 * licenza MIT, fatto con PowerPoint) messo negli assets, in formato 16:9. Kotlin puro: si collauda senza telefono.
 */
object Slides {
    data class Slide(val title: String, val bullets: List<String>)
    data class Deck(val title: String, val subtitle: String, val slides: List<Slide>)

    const val DEFAULT_COUNT = 7
    const val MAX_COUNT = 15

    fun prompt(count: Int): String =
        "Prepara una presentazione in italiano di $count slide sull'argomento richiesto. Usa ESATTAMENTE questo formato e nient'altro:\n" +
        "# Titolo della presentazione\n> Sottotitolo breve\n## Titolo della slide\n- punto breve\n- punto breve\n- punto breve\n" +
        "Ogni slide ha da 3 a 5 punti, ognuno al massimo 15 parole. La prima slide è un'introduzione, l'ultima le conclusioni. " +
        "Niente testo fuori da questo formato."

    fun question(topic: String, sources: String? = null): String =
        (if (sources != null) "Informazioni trovate su internet (usale; metti il numero della fonte tra parentesi quadre):\n$sources\n\n" else "") +
        "Argomento: ${topic.trim()}"

    /** La scaletta dalla risposta del cervello (accetta anche varianti: "Slide 1: …", "**Titolo**", "1. punto"). */
    fun parse(raw: String, fallbackTitle: String): Deck {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace("\r", "")
        var title: String? = null
        var subtitle = ""
        val slides = ArrayList<Slide>()
        var cur: String? = null
        var bullets = ArrayList<String>()
        fun flush() { cur?.let { if (bullets.isNotEmpty() || slides.isEmpty()) slides.add(Slide(it, bullets.take(6))) }; cur = null; bullets = ArrayList() }
        fun clean(s: String) = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("[*_`]"), "").trim().trimEnd(':').trim()
        for (line0 in text.lines()) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            val slideHead = Regex("^(?:#{2,}\\s*|(?:slide|diapositiva)\\s*\\d+\\s*[:.\\-–]\\s*)(.+)$", RegexOption.IGNORE_CASE).find(line)
            when {
                line.startsWith("# ") && title == null -> title = clean(line.removePrefix("# "))
                line.startsWith(">") && subtitle.isEmpty() && slides.isEmpty() && cur == null -> subtitle = clean(line.removePrefix(">"))
                slideHead != null -> {
                    flush()
                    // "## Slide 1: Il sole", "## 2. Il vento" → "Il sole", "Il vento"
                    cur = clean(slideHead.groupValues[1].replace(Regex("^(?:(?:slide|diapositiva)\\s*\\d+\\s*[:.\\-–]\\s*|\\d+[.)]\\s+)", RegexOption.IGNORE_CASE), "")).take(90)
                }
                Regex("^(?:[-•*]|\\d+[.)])\\s+(.+)$").find(line) != null -> {
                    val b = clean(Regex("^(?:[-•*]|\\d+[.)])\\s+(.+)$").find(line)!!.groupValues[1])
                    if (b.isNotEmpty()) { if (cur == null) cur = title ?: fallbackTitle; bullets.add(b.take(200)) }
                }
                cur != null && line.length > 3 -> bullets.add(clean(line).take(200))
            }
        }
        flush()
        val t = (title ?: fallbackTitle.replaceFirstChar { it.uppercase() }).take(120)
        return Deck(t, subtitle.take(160), slides.filter { it.title.isNotBlank() }.take(MAX_COUNT))
    }

    /** Il testo della presentazione (per lo schermo e per il PDF in caso di problemi). */
    fun outline(d: Deck): String = buildString {
        append("# ").append(d.title).append('\n')
        if (d.subtitle.isNotEmpty()) append(d.subtitle).append('\n')
        for ((i, s) in d.slides.withIndex()) {
            append("\n## ").append(i + 1).append(". ").append(s.title).append('\n')
            for (b in s.bullets) append("• ").append(b).append('\n')
        }
    }

    // ------------------------------------------------------------------ PowerPoint
    private const val CX = 12192000L
    private const val CY = 6858000L
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val SLIDE_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val REL_LAYOUT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"

    fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun xfrm(x: Long, y: Long, w: Long, h: Long) = "<a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>"

    private fun run(text: String, size: Int, bold: Boolean = false, color: String? = null) =
        "<a:r><a:rPr lang=\"it-IT\" sz=\"${size * 100}\"" + (if (bold) " b=\"1\"" else "") + " dirty=\"0\">" +
        (if (color != null) "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" else "") + "</a:rPr><a:t>${xml(text)}</a:t></a:r>"

    private fun rect(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, color: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, w, h)}" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
        "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"it-IT\"/></a:p></p:txBody></p:sp>"

    private fun slideXml(shapes: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<p:sld $NS><p:cSld><p:spTree>" +
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
        shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    private const val DARK = "1F2A44"
    private const val ACCENT = "3B82F6"

    /** La prima slide: il titolo grande e il sottotitolo, su fondo scuro. */
    private fun titleSlide(d: Deck): String = slideXml(
        rect(4, "Sfondo", 0, 0, CX, CY, DARK) + rect(5, "Linea", 838200, 3886200, 2400000, 60000, ACCENT) +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Titolo\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"ctrTitle\"/></p:nvPr></p:nvSpPr>" +
        "<p:spPr>${xfrm(838200, 1700000, 10515600, 2100000)}</p:spPr><p:txBody><a:bodyPr anchor=\"b\"><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
        "<a:p><a:pPr algn=\"l\"/>${run(d.title, 44, bold = true, color = "FFFFFF")}</a:p></p:txBody></p:sp>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Sottotitolo\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"subTitle\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
        "<p:spPr>${xfrm(838200, 4100000, 10515600, 1200000)}</p:spPr><p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
        "<a:p><a:pPr marL=\"0\" indent=\"0\" algn=\"l\"><a:buNone/></a:pPr>${run(d.subtitle.ifEmpty { "Presentazione preparata da SAM" }, 22, color = "C7D2FE")}</a:p></p:txBody></p:sp>"
    )

    /** Una slide: la barra colorata, il titolo e i punti. */
    private fun contentSlide(s: Slide, n: Int, total: Int): String {
        val size = when { s.bullets.size >= 6 -> 18; s.bullets.size == 5 -> 20; else -> 22 }
        val paras = s.bullets.joinToString("") {
            "<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:spcBef><a:spcPts val=\"900\"/></a:spcBef>" +
            "<a:buClr><a:srgbClr val=\"$ACCENT\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/></a:pPr>${run(it, size, color = "1F2937")}</a:p>"
        }.ifEmpty { "<a:p><a:endParaRPr lang=\"it-IT\"/></a:p>" }
        return slideXml(
            rect(4, "Barra", 0, 0, 228600, CY, ACCENT) +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Titolo\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
            "<p:spPr>${xfrm(685800, 381000, 10820400, 1143000)}</p:spPr><p:txBody><a:bodyPr anchor=\"b\"><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
            "<a:p><a:pPr algn=\"l\"/>${run(s.title, 34, bold = true, color = DARK)}</a:p></p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Testo\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr>" +
            "<p:spPr>${xfrm(685800, 1700000, 10820400, 4500000)}</p:spPr><p:txBody><a:bodyPr><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"5\" name=\"Numero\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(10668000, 6324600, 1219200, 381000)}" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>" +
            "<a:p><a:pPr algn=\"r\"/>${run("$n / $total", 12, color = "9CA3AF")}</a:p></p:txBody></p:sp>"
        )
    }

    /** Il .pptx: il modello di base ([base], i byte di assets/pptx/base.pptx) con le slide della presentazione. */
    fun pptx(base: ByteArray, d: Deck): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(base.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) entries[e.name] = z.readBytes() } }
        val slides = ArrayList<String>()
        slides.add(titleSlide(d))
        for ((i, s) in d.slides.withIndex()) slides.add(contentSlide(s, i + 1, d.slides.size))
        // presentazione: elenco delle slide dopo i master
        var pres = String(entries["ppt/presentation.xml"]!!, Charsets.UTF_8)
        val ids = slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rIdS${it + 1}\"/>" }
        pres = pres.replace("</p:sldMasterIdLst>", "</p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst>")
        entries["ppt/presentation.xml"] = pres.toByteArray(Charsets.UTF_8)
        var rels = String(entries["ppt/_rels/presentation.xml.rels"]!!, Charsets.UTF_8)
        rels = rels.replace("</Relationships>", slides.indices.joinToString("") {
            "<Relationship Id=\"rIdS${it + 1}\" Type=\"$REL_SLIDE\" Target=\"slides/slide${it + 1}.xml\"/>" } + "</Relationships>")
        entries["ppt/_rels/presentation.xml.rels"] = rels.toByteArray(Charsets.UTF_8)
        var types = String(entries["[Content_Types].xml"]!!, Charsets.UTF_8)
        types = types.replace("</Types>", slides.indices.joinToString("") {
            "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"$SLIDE_TYPE\"/>" } + "</Types>")
        entries["[Content_Types].xml"] = types.toByteArray(Charsets.UTF_8)
        for ((i, x) in slides.withIndex()) {
            entries["ppt/slides/slide${i + 1}.xml"] = x.toByteArray(Charsets.UTF_8)
            val layout = if (i == 0) 1 else 2
            entries["ppt/slides/_rels/slide${i + 1}.xml.rels"] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_LAYOUT\" Target=\"../slideLayouts/slideLayout$layout.xml\"/></Relationships>").toByteArray(Charsets.UTF_8)
        }
        // il titolo del documento
        entries["docProps/core.xml"]?.let { c ->
            var core = String(c, Charsets.UTF_8)
            core = if (core.contains("<dc:title>")) core.replace(Regex("<dc:title>.*?</dc:title>"), "<dc:title>${xml(d.title)}</dc:title>")
                   else core.replace("</cp:coreProperties>", "<dc:title>${xml(d.title)}</dc:title></cp:coreProperties>")
            entries["docProps/core.xml"] = core.toByteArray(Charsets.UTF_8)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            // [Content_Types].xml per primo, come fa PowerPoint
            val order = listOf("[Content_Types].xml") + entries.keys.filter { it != "[Content_Types].xml" }
            for (name in order) { z.putNextEntry(ZipEntry(name)); z.write(entries[name]!!); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
