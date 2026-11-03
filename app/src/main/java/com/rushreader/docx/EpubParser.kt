package com.rushreader.docx

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.rushreader.docx.XmlUtil.attr
import com.rushreader.docx.XmlUtil.local
import com.rushreader.docx.XmlUtil.resolve
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object EpubParser {
    private const val MAX_BLOCKS = 30000
    private const val MAX_CHAPTER = 20L shl 20
    private val BLOCK = setOf("p", "div", "section", "article", "blockquote", "li", "tr", "ul", "ol", "table", "pre", "figure", "aside", "header", "footer", "dd", "dt")
    private val SKIP = setOf("head", "script", "style")

    fun parse(file: File): DocxDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw IOException("Không phải file EPUB hợp lệ (không đọc được ZIP)") }
        try {
            val container = zip.getEntry("META-INF/container.xml") ?: throw IOException("Không phải EPUB hợp lệ (thiếu container.xml)")
            var opf: String? = null
            run {
                val p = XmlUtil.parser(zip.getInputStream(container))
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && local(p.name) == "rootfile") { opf = attr(p, "full-path"); break }
                    ev = p.next()
                }
            }
            val opfPath = opf ?: throw IOException("EPUB không chỉ ra file nội dung (OPF)")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfEntry = zip.getEntry(opfPath) ?: throw IOException("EPUB thiếu file OPF")
            val manifest = HashMap<String, String>(); val spine = ArrayList<String>()
            val p = XmlUtil.parser(zip.getInputStream(opfEntry))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                    "item" -> { val id = attr(p, "id"); val h = attr(p, "href"); if (id != null && h != null) manifest[id] = resolve(opfDir, h) }
                    "itemref" -> attr(p, "idref")?.let { spine.add(it) }
                }
                ev = p.next()
            }
            val blocks = ArrayList<DocxBlock>(); var truncated = false
            for (id in spine) {
                val path = manifest[id] ?: continue
                val lp = path.lowercase()
                if (!(lp.endsWith(".xhtml") || lp.endsWith(".html") || lp.endsWith(".htm") || lp.endsWith(".xml"))) continue
                val e = zip.getEntry(path) ?: continue
                if (e.size > MAX_CHAPTER) continue
                try { chapter(zip, path, blocks) }
                catch (ex: Exception) { blocks.add(DocxBlock.Para(AnnotatedString("[Không đọc được một chương]"), 0, null, 0, null)) }
                if (blocks.size >= MAX_BLOCKS) { truncated = true; break }
            }
            if (blocks.isEmpty()) throw IOException("EPUB không có nội dung đọc được (có thể bị DRM hoặc là EPUB dạng ảnh)")
            return DocxDocument(file, zip, blocks, truncated)
        } catch (e: Exception) { try { zip.close() } catch (ignored: Exception) {}; throw e }
    }

    private fun chapter(zip: ZipFile, path: String, blocks: MutableList<DocxBlock>) {
        val dir = path.substringBeforeLast('/', "")
        val p = XmlUtil.parser(zip.getInputStream(zip.getEntry(path)))
        var skip = 0; var bold = 0; var italic = 0; var under = 0
        var pb = AnnotatedString.Builder(); var len = 0; var endsSpace = false
        var level = 0; var marker: String? = null

        fun flush() {
            if (len > 0) blocks.add(DocxBlock.Para(pb.toAnnotatedString(), level, marker, 0, null))
            pb = AnnotatedString.Builder(); len = 0; endsSpace = false
        }
        fun add(raw: String) {
            var t = raw.replace(Regex("\\s+"), " ")
            if (len == 0) t = t.trimStart()
            if (endsSpace && t.startsWith(" ")) t = t.substring(1)
            if (t.isEmpty()) return
            if (bold > 0 || italic > 0 || under > 0) {
                pb.withStyle(SpanStyle(fontWeight = if (bold > 0) FontWeight.Bold else null, fontStyle = if (italic > 0) FontStyle.Italic else null,
                    textDecoration = if (under > 0) TextDecoration.Underline else null)) { append(t) }
            } else pb.append(t)
            len += t.length; endsSpace = t.endsWith(" ")
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && blocks.size < MAX_BLOCKS) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = local(p.name).lowercase()
                    if (n in SKIP) skip++
                    else if (skip == 0) when {
                        n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> { flush(); level = n[1] - '0' }
                        n in BLOCK -> { flush(); if (n == "li") marker = "•" }
                        n == "br" -> { pb.append("\n"); len++ }
                        n == "img" || n == "image" -> { flush(); (attr(p, "src") ?: attr(p, "href"))?.let { blocks.add(DocxBlock.Image(resolve(dir, it))) } }
                        n == "b" || n == "strong" -> bold++
                        n == "i" || n == "em" || n == "cite" -> italic++
                        n == "u" -> under++
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = local(p.name).lowercase()
                    if (n in SKIP) skip = (skip - 1).coerceAtLeast(0)
                    else if (skip == 0) when {
                        n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> { flush(); level = 0 }
                        n in BLOCK -> { flush(); if (n == "li") marker = null }
                        n == "b" || n == "strong" -> bold = (bold - 1).coerceAtLeast(0)
                        n == "i" || n == "em" || n == "cite" -> italic = (italic - 1).coerceAtLeast(0)
                        n == "u" -> under = (under - 1).coerceAtLeast(0)
                    }
                }
                XmlPullParser.TEXT -> if (skip == 0) add(p.text ?: "")
            }
            ev = p.next()
        }
        flush()
    }
}
