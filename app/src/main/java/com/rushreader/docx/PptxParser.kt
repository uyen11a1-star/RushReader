package com.rushreader.docx

import androidx.compose.ui.text.AnnotatedString
import com.rushreader.docx.XmlUtil.attr
import com.rushreader.docx.XmlUtil.local
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object PptxParser {
    private const val MAX_SLIDES = 500

    fun parse(file: File): DocxDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw IOException("Không phải file PPTX hợp lệ (không đọc được ZIP)") }
        try {
            val pres = zip.getEntry("ppt/presentation.xml") ?: throw IOException("Không phải PPTX hợp lệ (thiếu presentation.xml)")
            val rels = XmlUtil.readRels(zip, "ppt/_rels/presentation.xml.rels")
            val slides = ArrayList<String>()
            val p = XmlUtil.parser(zip.getInputStream(pres))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "sldId") {
                    val t = attr(p, "id")?.let { rels[it] }
                    if (t != null) slides.add(if (t.startsWith("/")) t.removePrefix("/") else "ppt/$t")
                }
                ev = p.next()
            }
            if (slides.isEmpty()) throw IOException("PPTX không có slide nào")
            val blocks = ArrayList<DocxBlock>()
            slides.take(MAX_SLIDES).forEachIndexed { idx, path ->
                blocks.add(DocxBlock.Para(AnnotatedString("Slide ${idx + 1}"), 2, null, 0, null))
                val e = zip.getEntry(path)
                if (e == null) { blocks.add(DocxBlock.Para(AnnotatedString("[Không đọc được slide này]"), 0, null, 0, null)); return@forEachIndexed }
                val before = blocks.size
                try { slide(zip, path, blocks) } catch (ex: Exception) { blocks.add(DocxBlock.Para(AnnotatedString("[Không đọc được slide này]"), 0, null, 0, null)) }
                if (blocks.size == before) blocks.add(DocxBlock.Para(AnnotatedString("(Slide không có văn bản)"), 0, null, 0, null))
            }
            return DocxDocument(file, zip, blocks, slides.size > MAX_SLIDES)
        } catch (e: Exception) { try { zip.close() } catch (ignored: Exception) {}; throw e }
    }

    private fun slide(zip: ZipFile, path: String, blocks: MutableList<DocxBlock>) {
        val p = XmlUtil.parser(zip.getInputStream(zip.getEntry(path)))
        var title = false; var sb = StringBuilder(); var marker: String? = null; var lvl = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                "ph" -> { val t = attr(p, "type"); if (t == "title" || t == "ctrTitle") title = true }
                "p" -> { sb = StringBuilder(); marker = null; lvl = 0 }
                "pPr" -> lvl = attr(p, "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                "buChar", "buAutoNum" -> marker = "•"
                "t" -> sb.append(p.nextText())
                "br" -> sb.append('\n')
            } else if (ev == XmlPullParser.END_TAG) when (local(p.name)) {
                "p" -> { val s = sb.toString().trim(); if (s.isNotEmpty()) blocks.add(DocxBlock.Para(AnnotatedString(s), if (title) 3 else 0, marker, lvl, null)) }
                "sp" -> title = false
            }
            ev = p.next()
        }
    }
}
