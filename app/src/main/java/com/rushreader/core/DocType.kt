package com.rushreader.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

enum class DocType(val label: String, val supported: Boolean) {
    PDF("PDF", true), DOCX("DOCX", true), DOC("DOC", false), TXT("TXT", true),
    EPUB("EPUB", true), RTF("RTF", true), XLSX("XLSX", true), PPTX("PPTX", true),
    UNKNOWN("?", false);

    companion object {
        fun fromExtension(name: String): DocType = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PDF; "docx" -> DOCX; "doc" -> DOC; "txt", "text", "log", "md" -> TXT
            "epub" -> EPUB; "rtf" -> RTF; "xlsx" -> XLSX; "pptx" -> PPTX
            else -> UNKNOWN
        }

        fun fromMime(m: String?): DocType = when (m) {
            "application/pdf" -> PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
            "application/msword" -> DOC
            "text/plain" -> TXT
            "application/epub+zip" -> EPUB
            "application/rtf", "text/rtf" -> RTF
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX
            else -> UNKNOWN
        }

        fun byName(n: String?): DocType = entries.firstOrNull { it.name == n } ?: UNKNOWN

        /** Nhận dạng theo đuôi/MIME, rồi đối chiếu magic bytes để phân biệt DOC (OLE2) và DOCX (ZIP). */
        fun resolve(ctx: Context, uri: Uri, name: String, mime: String?): DocType {
            var t = fromExtension(name)
            if (t == UNKNOWN) t = fromMime(mime)
            if (t == UNKNOWN && mime?.startsWith("text/") == true) t = TXT
            val h = readHeader(ctx, uri) ?: return t
            val pdf = h.size >= 4 && h[0] == 0x25.toByte() && h[1] == 0x50.toByte() && h[2] == 0x44.toByte() && h[3] == 0x46.toByte()
            val pk = h.size >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte()
            val ole = h.size >= 4 && h[0] == 0xD0.toByte() && h[1] == 0xCF.toByte() && h[2] == 0x11.toByte() && h[3] == 0xE0.toByte()
            return when {
                pdf -> PDF
                ole && t == DOCX -> DOC
                pk && t == DOC -> DOCX
                else -> t
            }
        }

        private fun readHeader(ctx: Context, uri: Uri): ByteArray? = try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val b = ByteArray(8); val n = s.read(b); if (n <= 0) null else b.copyOf(n)
            }
        } catch (ignored: Exception) { null }
    }
}

data class FileMeta(val name: String, val size: Long, val mime: String?) {
    companion object {
        fun query(ctx: Context, uri: Uri): FileMeta {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            var size = -1L
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (ignored: Exception) {}
            if (size < 0 && uri.scheme == "file") size = uri.path?.let { File(it).length() } ?: -1L
            val mime = try { ctx.contentResolver.getType(uri) } catch (ignored: Exception) { null }
            return FileMeta(name, size, mime)
        }
    }
}
