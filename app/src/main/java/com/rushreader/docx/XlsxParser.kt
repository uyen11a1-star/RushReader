package com.rushreader.docx

import androidx.compose.ui.text.AnnotatedString
import com.rushreader.docx.XmlUtil.attr
import com.rushreader.docx.XmlUtil.local
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.util.zip.ZipFile

object XlsxParser {
    private const val MAX_ROWS = 3000
    private const val MAX_COLS = 40
    private const val CHUNK = 40
    private const val MAX_XML = 60L shl 20

    fun parse(file: File): DocxDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) { throw IOException("Không phải file XLSX hợp lệ (không đọc được ZIP)") }
        try {
            val wb = zip.getEntry("xl/workbook.xml") ?: throw IOException("Không phải XLSX hợp lệ (thiếu workbook.xml)")
            val rels = XmlUtil.readRels(zip, "xl/_rels/workbook.xml.rels")
            val sheets = ArrayList<Pair<String, String>>()
            val p = XmlUtil.parser(zip.getInputStream(wb))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "sheet") {
                    val name = attr(p, "name") ?: "Sheet"; val rid = attr(p, "id"); val t = rid?.let { rels[it] }
                    if (t != null) sheets.add(name to (if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"))
                }
                ev = p.next()
            }
            if (sheets.isEmpty()) throw IOException("XLSX không có bảng tính nào")
            val shared = readShared(zip)
            val blocks = ArrayList<DocxBlock>(); var truncated = false
            for ((name, path) in sheets) {
                blocks.add(DocxBlock.Para(AnnotatedString(name), 2, null, 0, null))
                val e = zip.getEntry(path)
                if (e == null || e.size > MAX_XML) { blocks.add(DocxBlock.Para(AnnotatedString("[Không đọc được bảng tính này]"), 0, null, 0, null)); continue }
                val rows = readSheet(zip, path, shared)
                if (rows.size >= MAX_ROWS) truncated = true
                if (rows.isEmpty()) blocks.add(DocxBlock.Para(AnnotatedString("(Bảng trống)"), 0, null, 0, null))
                rows.chunked(CHUNK).forEach { blocks.add(DocxBlock.Table(it)) }
            }
            return DocxDocument(file, zip, blocks, truncated)
        } catch (e: Exception) { try { zip.close() } catch (ignored: Exception) {}; throw e }
    }

    private fun readShared(zip: ZipFile): List<String> {
        val e = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        if (e.size > 30L shl 20) throw IOException("Dữ liệu XLSX quá lớn để mở an toàn")
        val out = ArrayList<String>()
        val p = XmlUtil.parser(zip.getInputStream(e))
        var cur = StringBuilder(); var ph = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                "si" -> cur = StringBuilder()
                "rPh" -> ph++
                "t" -> { val t = p.nextText(); if (ph == 0) cur.append(t) }
            } else if (ev == XmlPullParser.END_TAG) when (local(p.name)) {
                "rPh" -> ph = (ph - 1).coerceAtLeast(0)
                "si" -> out.add(cur.toString())
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1) else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1) else break }
        return n - 1
    }

    private fun num(s: String): String {
        val d = s.toDoubleOrNull() ?: return s
        if (d.isNaN() || d.isInfinite()) return s
        return try { BigDecimal(s).round(MathContext(10)).stripTrailingZeros().toPlainString() } catch (ignored: Exception) { s }
    }

    private fun readSheet(zip: ZipFile, path: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = XmlUtil.parser(zip.getInputStream(zip.getEntry(path)))
        var row = ArrayList<String>(); var col = 0; var type: String? = null; var v: String? = null; val inl = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && rows.size < MAX_ROWS) {
            if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                "row" -> row = ArrayList()
                "c" -> { col = attr(p, "r")?.let { colIndex(it) } ?: row.size; type = attr(p, "t"); v = null; inl.setLength(0) }
                "v" -> v = p.nextText()
                "t" -> inl.append(p.nextText())
            } else if (ev == XmlPullParser.END_TAG) when (local(p.name)) {
                "c" -> if (col in 0 until MAX_COLS) {
                    val raw = v
                    val text = when (type) {
                        "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> inl.toString()
                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                        "str", "e" -> raw ?: ""
                        else -> raw?.let { num(it) } ?: ""
                    }
                    while (row.size <= col) row.add("")
                    row[col] = text
                }
                "row" -> if (row.any { it.isNotBlank() }) rows.add(row)
            }
            ev = p.next()
        }
        return rows
    }
}
