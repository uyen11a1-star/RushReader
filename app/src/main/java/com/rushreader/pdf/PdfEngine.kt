package com.rushreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.rushreader.core.CacheManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class PdfPasswordException : IOException("PDF có mật khẩu — engine PdfRenderer của Android không hỗ trợ mở file này.")

/**
 * Bọc android.graphics.pdf.PdfRenderer. PdfRenderer chỉ cho mở 1 trang tại một thời điểm,
 * nên mọi thao tác chạy tuần tự trên 1 thread riêng (không đụng Main Thread).
 */
class PdfEngine private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val temp: File?
) : Closeable {
    val pageCount: Int = renderer.pageCount
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val dispatcher: CoroutineDispatcher = exec.asCoroutineDispatcher()
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    @Volatile private var closed = false

    suspend fun pageSize(i: Int): Pair<Int, Int>? {
        sizes[i]?.let { return it }
        return withContext(dispatcher) {
            if (closed) return@withContext null
            val p = renderer.openPage(i)
            try { (p.width to p.height).also { sizes[i] = it } } finally { p.close() }
        }
    }

    suspend fun render(i: Int, bmp: Bitmap) = withContext(dispatcher) {
        check(!closed) { "closed" }
        bmp.eraseColor(Color.WHITE)
        val p = renderer.openPage(i)
        try { p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { p.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        exec.execute {
            try { renderer.close() } catch (ignored: Exception) {}
            try { pfd.close() } catch (ignored: Exception) {}
            temp?.delete()
        }
        exec.shutdown()
    }

    companion object {
        suspend fun open(ctx: Context, uri: Uri): PdfEngine = withContext(Dispatchers.IO) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Không mở được file")
                create(pfd, null)
            } catch (e: PdfPasswordException) { throw e
            } catch (e: SecurityException) { throw e
            } catch (e: Exception) {
                // fd không seek được hoặc provider lạ: thử lại bằng bản sao tạm
                val f = CacheManager.copyToCache(ctx, uri)
                try { create(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), f) }
                catch (e2: Exception) { f.delete(); throw e2 }
            }
        }

        private fun create(pfd: ParcelFileDescriptor, temp: File?): PdfEngine {
            try {
                val r = try { PdfRenderer(pfd) } catch (ignored: SecurityException) { throw PdfPasswordException() }
                if (r.pageCount <= 0) { r.close(); throw IOException("PDF không có trang nào") }
                return PdfEngine(pfd, r, temp)
            } catch (e: Exception) { try { pfd.close() } catch (ignored: Exception) {}; throw e }
        }
    }
}
