package com.rushreader.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import kotlin.math.sqrt

/** Ngân sách bitmap thích ứng theo RAM máy, tuỳ chọn chất lượng và áp lực bộ nhớ. */
object RenderPolicy {
    @Volatile var pressure = false

    fun pageBudgetBytes(ctx: Context, quality: Int): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val low = am.isLowRamDevice || am.memoryClass <= 128
        var b = if (low) 10L shl 20 else 28L shl 20
        b = when (quality) { 1 -> b / 2; 2 -> b * 2; else -> b }
        return if (pressure) b / 2 else b
    }

    fun widthScale(quality: Int) = when (quality) { 1 -> 0.75f; 2 -> 1.25f; else -> 1f }

    /** Kích thước bitmap giữ đúng tỉ lệ trang, không vượt ngân sách byte. */
    fun fit(pw: Int, ph: Int, targetW: Int, budget: Long): Pair<Int, Int> {
        val sw = pw.coerceAtLeast(1); val sh = ph.coerceAtLeast(1)
        var w = targetW.coerceAtLeast(64)
        var h = (w.toLong() * sh / sw).toInt().coerceAtLeast(1)
        val bytes = w.toLong() * h * 4
        if (bytes > budget) {
            val s = sqrt(budget.toDouble() / bytes)
            w = (w * s).toInt().coerceAtLeast(64)
            h = (w.toLong() * sh / sw).toInt().coerceAtLeast(1)
        }
        return w to h
    }
}

/** Pool bitmap ARGB_8888 tái sử dụng; bitmap vượt ngưỡng sẽ bị recycle ngay. */
object BitmapPool {
    private val free = ArrayList<Bitmap>()
    private var bytes = 0L
    private const val MAX_BYTES = 40L shl 20

    @Synchronized fun acquire(w: Int, h: Int): Bitmap {
        val need = w.toLong() * h * 4
        val it = free.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.isRecycled) { it.remove(); continue }
            if (b.allocationByteCount >= need) {
                it.remove(); bytes -= b.allocationByteCount
                b.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                return b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    @Synchronized fun release(b: Bitmap) {
        if (b.isRecycled) return
        val s = b.allocationByteCount.toLong()
        if (bytes + s > MAX_BYTES) { b.recycle(); return }
        free.add(b); bytes += s
    }

    @Synchronized fun clear() { free.forEach { it.recycle() }; free.clear(); bytes = 0 }
}
