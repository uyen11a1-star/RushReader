package com.rushreader.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

sealed interface DocxBlock {
    class Para(val text: AnnotatedString, val level: Int, val marker: String?, val listLevel: Int, val align: TextAlign?) : DocxBlock
    class Table(val rows: List<List<String>>) : DocxBlock
    class Image(val entry: String) : DocxBlock
}

class DocxDocument(private val file: File?, private val zip: ZipFile?, val blocks: List<DocxBlock>, val truncated: Boolean) : Closeable {
    /** Giải mã ảnh nhúng với inSampleSize để giới hạn RAM; trả null nếu định dạng không hỗ trợ (vd EMF/WMF). */
    fun loadImage(entry: String, maxWidth: Int): Bitmap? = try {
        val z = zip ?: return null
        val e = z.getEntry(entry) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        z.getInputStream(e).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            z.getInputStream(e).use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (ignored: Exception) { null } catch (ignored: OutOfMemoryError) { null }

    override fun close() { try { zip?.close() } catch (ignored: Exception) {}; file?.delete() }
}

object DocxParser {
    private const val MAX_XML = 60L shl 20
    private const val MAX_BLOCKS = 30000

    private fun local(n: String?) = n?.substringAfter(':') ?: ""
    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
        return null
    }
    private fun on(v: String?) = v == null || (v != "0" && v != "false" && v != "none")

    private fun headingLevel(id: String): Int {
        val l = id.lowercase().replace(" ", "")
        return when {
            l == "title" -> 1
            l.startsWith("heading") -> l.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
            else -> 0
        }
    }

    fun parse(file: File): DocxDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw IOException("Không phải file DOCX hợp lệ (không đọc được cấu trúc ZIP)") }
        try {
            val entry = zip.getEntry("word/document.xml") ?: throw IOException("Không phải file DOCX hợp lệ (thiếu word/document.xml)")
            if (entry.size > MAX_XML) throw IOException("Nội dung DOCX quá lớn để mở an toàn trên máy này")
            val rels = readRels(zip)
            val numbering = readNumbering(zip)
            val counters = HashMap<String, IntArray>()
            var numId: String? = null
            val p = Xml.newPullParser()
            p.setInput(zip.getInputStream(entry), null)

            val blocks = ArrayList<DocxBlock>()
            var truncated = false
            var tblDepth = 0
            var rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
            var pb = AnnotatedString.Builder(); val plain = StringBuilder()
            var inPara = false; var level = 0; var isList = false; var ilvl = 0; var align: TextAlign? = null
            var inRun = false; var inRPr = false; var inPPr = false
            var bold = false; var italic = false; var under = false; var strike = false
            val images = ArrayList<String>()
            var lastEmpty = false

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && !truncated) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "tbl" -> { tblDepth++; if (tblDepth == 1) rows = ArrayList() }
                        "tr" -> if (tblDepth == 1) row = ArrayList()
                        "tc" -> if (tblDepth == 1) cell.setLength(0)
                        "p" -> { pb = AnnotatedString.Builder(); plain.setLength(0); inPara = true; level = 0; isList = false; ilvl = 0; align = null; images.clear(); numId = null }
                        "pPr" -> inPPr = true
                        "pStyle" -> if (inPara && inPPr) level = headingLevel(attr(p, "val") ?: "")
                        "numPr" -> if (inPara) isList = true
                        "numId" -> if (inPara) { numId = attr(p, "val"); if (numId == "0") isList = false }
                        "ilvl" -> if (inPara) ilvl = attr(p, "val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                        "jc" -> if (inPara && inPPr) align = when (attr(p, "val")) {
                            "center" -> TextAlign.Center; "right", "end" -> TextAlign.End
                            "both", "distribute" -> TextAlign.Justify; else -> null }
                        "r" -> { inRun = true; bold = false; italic = false; under = false; strike = false }
                        "rPr" -> if (inRun) inRPr = true
                        "b" -> if (inRPr) bold = on(attr(p, "val"))
                        "i" -> if (inRPr) italic = on(attr(p, "val"))
                        "u" -> if (inRPr) under = on(attr(p, "val"))
                        "strike" -> if (inRPr) strike = on(attr(p, "val"))
                        "t" -> if (inPara) {
                            val txt = p.nextText()
                            plain.append(txt)
                            if (bold || italic || under || strike) {
                                pb.withStyle(SpanStyle(
                                    fontWeight = if (bold) FontWeight.Bold else null,
                                    fontStyle = if (italic) FontStyle.Italic else null,
                                    textDecoration = when { under && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                        under -> TextDecoration.Underline; strike -> TextDecoration.LineThrough; else -> null }
                                )) { append(txt) }
                            } else pb.append(txt)
                        }
                        "tab" -> if (inPara) { plain.append("    "); pb.append("    ") }
                        "br", "cr" -> if (inPara) { plain.append('\n'); pb.append("\n") }
                        "blip" -> if (inPara && tblDepth == 0) attr(p, "embed")?.let { id -> rels[id]?.let { images.add(it) } }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (local(p.name)) {
                        "pPr" -> inPPr = false
                        "rPr" -> inRPr = false
                        "r" -> { inRun = false; inRPr = false }
                        "p" -> {
                            inPara = false
                            if (tblDepth > 0) {
                                if (cell.isNotEmpty()) cell.append('\n')
                                cell.append(plain.toString().trim())
                            } else {
                                val blank = plain.isBlank()
                                if (!blank || (images.isEmpty() && !lastEmpty)) {
                                    val marker = if (!isList) null else {
                                        val nid = numId
                                        if (nid == null) "•" else {
                                            val c = counters.getOrPut(nid) { IntArray(9) }
                                            c[ilvl]++; for (k in ilvl + 1..8) c[k] = 0
                                            markerFor(numbering.fmts[numbering.numToAbs[nid]]?.get(ilvl), c[ilvl])
                                        }
                                    }
                                    blocks.add(DocxBlock.Para(pb.toAnnotatedString(), level, marker, ilvl, align))
                                }
                                lastEmpty = blank && images.isEmpty()
                                images.forEach { blocks.add(DocxBlock.Image(it)) }
                                images.clear()
                            }
                        }
                        "tc" -> if (tblDepth == 1) row.add(cell.toString().trim())
                        "tr" -> if (tblDepth == 1) rows.add(row)
                        "tbl" -> { if (tblDepth == 1) blocks.add(DocxBlock.Table(rows)); tblDepth = (tblDepth - 1).coerceAtLeast(0); lastEmpty = false }
                    }
                    if (blocks.size >= MAX_BLOCKS) truncated = true
                }
                ev = p.next()
            }
            return DocxDocument(file, zip, blocks, truncated)
        } catch (e: Exception) { try { zip.close() } catch (ignored: Exception) {}; throw e }
    }

    private class Numbering(val numToAbs: Map<String, String>, val fmts: Map<String, Map<Int, String>>)

    private fun readNumbering(zip: ZipFile): Numbering {
        val e = zip.getEntry("word/numbering.xml") ?: return Numbering(emptyMap(), emptyMap())
        val numToAbs = HashMap<String, String>(); val fmts = HashMap<String, HashMap<Int, String>>()
        try {
            val p = Xml.newPullParser(); p.setInput(zip.getInputStream(e), null)
            var abs: String? = null; var lvl = -1; var num: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                    "abstractNum" -> abs = attr(p, "abstractNumId")
                    "lvl" -> lvl = attr(p, "ilvl")?.toIntOrNull() ?: -1
                    "numFmt" -> { val a = abs; val v = attr(p, "val"); if (a != null && lvl >= 0 && v != null) fmts.getOrPut(a) { HashMap() }.putIfAbsent(lvl, v) }
                    "num" -> num = attr(p, "numId")
                    "abstractNumId" -> { val n = num; val v = attr(p, "val"); if (n != null && v != null) numToAbs[n] = v }
                } else if (ev == XmlPullParser.END_TAG) when (local(p.name)) {
                    "abstractNum" -> abs = null; "lvl" -> lvl = -1; "num" -> num = null
                }
                ev = p.next()
            }
        } catch (ignored: Exception) {}
        return Numbering(numToAbs, fmts)
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n.coerceIn(1, 3999); val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    private fun letters(n: Int): String {
        var x = n.coerceAtLeast(1); val sb = StringBuilder()
        while (x > 0) { x--; sb.insert(0, ('a' + x % 26)); x /= 26 }
        return sb.toString()
    }

    private fun markerFor(fmt: String?, n: Int): String = when (fmt) {
        null, "bullet", "none" -> "•"
        "lowerLetter" -> letters(n) + "."
        "upperLetter" -> letters(n).uppercase() + "."
        "lowerRoman" -> roman(n).lowercase() + "."
        "upperRoman" -> roman(n) + "."
        else -> "$n."
    }

    private fun readRels(zip: ZipFile): Map<String, String> {
        val e = zip.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
        val out = HashMap<String, String>()
        try {
            val p = Xml.newPullParser(); p.setInput(zip.getInputStream(e), null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id"); val target = attr(p, "Target"); val type = attr(p, "Type") ?: ""
                    if (id != null && target != null && type.endsWith("/image"))
                        out[id] = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
                }
                ev = p.next()
            }
        } catch (ignored: Exception) {}
        return out
    }
}
