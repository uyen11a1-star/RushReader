package com.rushreader.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Bản sao tạm trong cacheDir cho file không đọc trực tiếp được (DOCX cần ZipFile, PDF fd không seek được). */
object CacheManager {
    private const val MAX_BYTES = 150L shl 20

    private fun dir(ctx: Context) = File(ctx.cacheDir, "docs").apply { mkdirs() }

    fun copyToCache(ctx: Context, uri: Uri): File {
        trim(ctx)
        val f = File(dir(ctx), "d_${System.nanoTime()}.bin")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } catch (e: Exception) { f.delete(); throw e }
        return f
    }

    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= MAX_BYTES) break; total -= f.length(); f.delete() }
    }

    fun clear(ctx: Context): Long {
        var n = 0L
        dir(ctx).listFiles()?.forEach { n += it.length(); it.delete() }
        return n
    }
}
