package com.rushreader.docx

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

object RtfParser {
    private const val MAX_BYTES = 30L shl 20
    private const val MAX_BLOCKS = 30000
    private val SKIP = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "footnote", "filetbl", "listtable", "listoverridetable", "generator", "themedata",
        "colorschememapping", "latentstyles", "datastore", "rsidtbl", "revtbl", "xmlnstbl", "pgptbl", "private", "object", "fldinst")

    private class St(var skip: Boolean, var b: Boolean, var i: Boolean, var u: Boolean, var uc: Int) {
        fun copy() = St(skip, b, i, u, uc)
    }

    fun parse(file: File): DocxDocument {
        if (file.length() > MAX_BYTES) throw IOException("File RTF quá lớn để mở an toàn")
        val bytes = file.readBytes()
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "{\\rtf") throw IOException("Không phải file RTF hợp lệ")
        var cs: Charset = Charset.forName("windows-1252")
        val blocks = ArrayList<DocxBlock>()
        var pb = AnnotatedString.Builder(); var plain = 0; var lastEmpty = false
        val run = StringBuilder(); var rb = false; var ri = false; var ru = false
        val stack = ArrayDeque<St>(); var cur = St(false, false, false, false, 1)
        var pendingSkip = 0

        fun flushRun() {
            if (run.isEmpty()) return
            val s = run.toString(); run.setLength(0)
            if (rb || ri || ru) pb.withStyle(SpanStyle(fontWeight = if (rb) FontWeight.Bold else null, fontStyle = if (ri) FontStyle.Italic else null,
                textDecoration = if (ru) TextDecoration.Underline else null)) { append(s) } else pb.append(s)
            plain += s.length
        }
        fun emit(t: String) {
            if (cur.skip) return
            if (rb != cur.b || ri != cur.i || ru != cur.u) { flushRun(); rb = cur.b; ri = cur.i; ru = cur.u }
            run.append(t)
        }
        fun flushPara() {
            flushRun()
            val a = pb.toAnnotatedString()
            if (a.text.isNotBlank()) { blocks.add(DocxBlock.Para(a, 0, null, 0, null)); lastEmpty = false }
            else if (!lastEmpty && blocks.isNotEmpty()) { blocks.add(DocxBlock.Para(AnnotatedString(""), 0, null, 0, null)); lastEmpty = true }
            pb = AnnotatedString.Builder(); plain = 0
        }

        val n = bytes.size; var i = 0
        while (i < n && blocks.size < MAX_BLOCKS) {
            val ch = (bytes[i].toInt() and 0xFF).toChar()
            when (ch) {
                '{' -> { stack.addLast(cur.copy()); i++ }
                '}' -> { if (stack.isNotEmpty()) cur = stack.removeLast(); i++ }
                '\r', '\n' -> i++
                '\\' -> {
                    i++
                    if (i >= n) break
                    val c = (bytes[i].toInt() and 0xFF).toChar()
                    when {
                        c == '\\' || c == '{' || c == '}' -> { i++; if (pendingSkip > 0) pendingSkip-- else emit(c.toString()) }
                        c == '\'' -> {
                            val end = minOf(i + 3, n)
                            val hex = String(bytes, i + 1, end - (i + 1), Charsets.ISO_8859_1)
                            i = end
                            val v = hex.toIntOrNull(16)
                            if (pendingSkip > 0) pendingSkip-- else if (v != null) emit(String(byteArrayOf(v.toByte()), cs))
                        }
                        c == '*' -> { cur.skip = true; i++ }
                        c == '~' -> { i++; emit(" ") }
                        c == '_' -> { i++; emit("-") }
                        c.isLetter() -> {
                            val st = i
                            while (i < n && (bytes[i].toInt().toChar().isLetter())) i++
                            val word = String(bytes, st, i - st, Charsets.ISO_8859_1)
                            var param: Int? = null
                            var neg = false
                            if (i < n && bytes[i].toInt().toChar() == '-') { neg = true; i++ }
                            val ds = i
                            while (i < n && bytes[i].toInt().toChar().isDigit()) i++
                            if (i > ds) param = String(bytes, ds, i - ds, Charsets.ISO_8859_1).toIntOrNull()?.let { if (neg) -it else it }
                            if (i < n && bytes[i].toInt().toChar() == ' ') i++
                            when (word) {
                                "par", "sect", "page" -> flushPara()
                                "line" -> emit("\n")
                                "tab" -> emit("    ")
                                "b" -> cur.b = param != 0
                                "i" -> cur.i = param != 0
                                "ul" -> cur.u = param != 0
                                "ulnone" -> cur.u = false
                                "plain" -> { cur.b = false; cur.i = false; cur.u = false }
                                "uc" -> cur.uc = param ?: 1
                                "u" -> if (param != null) { val code = if (param < 0) param + 65536 else param; emit(code.toChar().toString()); pendingSkip = cur.uc }
                                "ansicpg" -> if (param != null) try { cs = Charset.forName("windows-$param") } catch (ignored: Exception) {}
                                "emdash" -> emit("—"); "endash" -> emit("–"); "bullet" -> emit("•")
                                "lquote" -> emit("‘"); "rquote" -> emit("’"); "ldblquote" -> emit("“"); "rdblquote" -> emit("”")
                                else -> if (word in SKIP) cur.skip = true
                            }
                        }
                        else -> i++
                    }
                }
                else -> { i++; if (pendingSkip > 0) pendingSkip-- else emit(ch.toString()) }
            }
        }
        flushPara()
        if (blocks.none { it is DocxBlock.Para && it.text.text.isNotBlank() }) throw IOException("RTF không có nội dung văn bản đọc được")
        return DocxDocument(file, null, blocks, blocks.size >= MAX_BLOCKS)
    }
}
