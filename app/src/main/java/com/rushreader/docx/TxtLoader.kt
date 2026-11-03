package com.rushreader.docx

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtLoader {
    private const val MAX = 6L * 1024 * 1024
    private const val CHUNK = 4000

    class Result(val lines: List<String>, val truncated: Boolean, val charset: String)

    fun load(ctx: Context, uri: Uri): Result {
        val buf = ByteArrayOutputStream()
        var truncated = false
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được file")
        input.use { s ->
            val b = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val n = s.read(b); if (n < 0) break
                val take = minOf(n.toLong(), MAX - total).toInt()
                buf.write(b, 0, take); total += take
                if (total >= MAX) { truncated = take < n || s.read() >= 0; break }
            }
        }
        val (text, cs) = decode(buf.toByteArray(), truncated)
        return Result(split(text), truncated, cs)
    }

    private fun decode(b: ByteArray, truncated: Boolean): Pair<String, String> {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8) to "UTF-8"
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        for (trim in 0..(if (truncated) 3 else 0)) {
            try {
                val d = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                return d.decode(ByteBuffer.wrap(b, 0, (b.size - trim).coerceAtLeast(0))).toString() to "UTF-8"
            } catch (ignored: CharacterCodingException) {}
        }
        val fb: Charset = try { Charset.forName("windows-1258") } catch (ignored: Exception) { Charsets.ISO_8859_1 }
        return String(b, fb) to fb.name()
    }

    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.length <= CHUNK) out.add(line)
            else { var i = 0; while (i < line.length) { out.add(line.substring(i, minOf(i + CHUNK, line.length))); i += CHUNK } }
        }
        return out
    }
}
