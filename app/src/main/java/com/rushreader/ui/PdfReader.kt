package com.rushreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushreader.core.friendlyError
import com.rushreader.data.Doc
import com.rushreader.data.Settings
import com.rushreader.pdf.BitmapPool
import com.rushreader.pdf.PdfEngine
import com.rushreader.pdf.RenderPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val InvertFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)))

@Composable
fun PdfReader(doc: Doc, settings: Settings, onTap: () -> Unit, onProgress: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.uri) {
        try { engine = PdfEngine.open(ctx, Uri.parse(doc.uri)) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { err = friendlyError(e) }
    }
    DisposableEffect(doc.uri) { onDispose { engine?.close(); BitmapPool.clear() } }

    val e = engine
    when {
        err != null -> ErrorBox(err!!)
        e == null -> Loading()
        else -> PdfPages(e, doc, settings, onTap, onProgress)
    }
}

@Composable
private fun PdfPages(engine: PdfEngine, doc: Doc, settings: Settings, onTap: () -> Unit, onProgress: (Int, Int) -> Unit) {
    val density = LocalDensity.current
    val screenWpx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pinch by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = doc.page.coerceIn(0, engine.pageCount - 1))
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { delay(400); onProgress(it, engine.pageCount) }
    }

    val contentWpx = (screenWpx * zoom).roundToInt()
    val widthDp: Dp = with(density) { contentWpx.toDp() }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0F0F11)).clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom = if (zoom > 1.05f) 1f else 2f })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var multi = false
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size >= 2) {
                                multi = true
                                val z = ev.calculateZoom()
                                if (z != 1f) pinch = (pinch * z).coerceIn(1f / zoom, 4f / zoom)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (multi) { zoom = (zoom * pinch).coerceIn(1f, 4f); pinch = 1f }
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(widthDp).fillMaxHeight().graphicsLayer {
                        scaleX = pinch; scaleY = pinch; transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(engine.pageCount, key = { it }) { i ->
                        PdfPageItem(engine, i, contentWpx, widthDp, settings.quality, settings.invertPdf)
                    }
                }
            }
        }
        // Thanh chuyển trang
        Row(Modifier.fillMaxWidth().background(RC.Surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            var sliding by remember { mutableStateOf<Float?>(null) }
            val cur = (listState.firstVisibleItemIndex + 1).coerceIn(1, engine.pageCount)
            Text("${(sliding?.roundToInt() ?: cur)} / ${engine.pageCount}", color = RC.Text, fontSize = 13.sp, modifier = Modifier.widthIn(min = 72.dp))
            if (engine.pageCount > 1) Slider(
                value = sliding ?: cur.toFloat(),
                onValueChange = { sliding = it },
                onValueChangeFinished = { val t = sliding; sliding = null; if (t != null) scope.launch { listState.scrollToItem(t.roundToInt() - 1) } },
                valueRange = 1f..engine.pageCount.toFloat(),
                colors = SliderDefaults.colors(thumbColor = RC.Accent, activeTrackColor = RC.Accent),
                modifier = Modifier.weight(1f)
            ) else Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PdfPageItem(engine: PdfEngine, index: Int, contentWpx: Int, widthDp: Dp, quality: Int, invert: Boolean) {
    val ctx = LocalContext.current
    var ratio by remember(engine, index) { mutableFloatStateOf(1.414f) }
    var bmp by remember(index, contentWpx, quality) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(index, contentWpx, quality) { mutableStateOf(false) }

    LaunchedEffect(engine, index, contentWpx, quality) {
        val size = engine.pageSize(index) ?: return@LaunchedEffect
        ratio = size.second.toFloat() / size.first.coerceAtLeast(1)
        val (w, h) = RenderPolicy.fit(size.first, size.second, (contentWpx * RenderPolicy.widthScale(quality)).toInt(), RenderPolicy.pageBudgetBytes(ctx, quality))
        var b: Bitmap? = null
        var ok = false
        try {
            b = BitmapPool.acquire(w, h)
            engine.render(index, b)
            bmp = b; ok = true
        } catch (e: CancellationException) { throw e
        } catch (e: OutOfMemoryError) { BitmapPool.clear(); RenderPolicy.pressure = true; failed = true
        } catch (e: Exception) { failed = true
        } finally { if (!ok && b != null) BitmapPool.release(b) }
    }
    DisposableEffect(index, contentWpx, quality) { onDispose { bmp?.let { BitmapPool.release(it) }; bmp = null } }

    Box(Modifier.width(widthDp).aspectRatio(1f / ratio).background(if (invert) Color(0xFF111111) else Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(bitmap = b.asImageBitmap(), contentDescription = "Trang ${index + 1}", contentScale = ContentScale.FillBounds,
            colorFilter = if (invert) InvertFilter else null, modifier = Modifier.fillMaxSize())
        else if (failed) Text("Không render được trang ${index + 1}", color = Color.Gray, fontSize = 12.sp)
    }
}
