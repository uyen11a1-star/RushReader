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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
    var zoom by remember { mutableFloatStateOf(1f) }   // 1..4 khi cols == 1
    var cols by remember { mutableIntStateOf(1) }      // 1..4 cột (chế độ xem bao quát)
    var pinch by remember { mutableFloatStateOf(1f) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = doc.page.coerceIn(0, engine.pageCount - 1))
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collectLatest { delay(400); onProgress(it, engine.pageCount) }
    }

    val widthDp: Dp = with(density) { (screenWpx * zoom).roundToInt().toDp() }
    val gap = 8.dp
    val sidePad = if (cols > 1) gap else 0.dp
    val pageWdp: Dp = (widthDp - sidePad * 2 - gap * (cols - 1)) / cols
    val pageWpx = with(density) { pageWdp.toPx() }.roundToInt().coerceAtLeast(32)

    fun zoomIn() { if (cols > 1) cols -= 1 else zoom = (zoom + 0.5f).coerceAtMost(4f) }
    fun zoomOut() { if (cols == 1 && zoom > 1.01f) zoom = (zoom - 0.5f).coerceAtLeast(1f) else { zoom = 1f; cols = (cols + 1).coerceAtMost(4) } }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0F0F11)).clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                        if (cols > 1) cols = 1 else zoom = if (zoom > 1.05f) 1f else 2f
                    })
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
                                if (z != 1f) pinch = (pinch * z).coerceIn(0.4f, if (cols == 1) 4f / zoom else 2.5f)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (multi) {
                            if (cols == 1) {
                                val e = zoom * pinch
                                if (e < 0.75f) { zoom = 1f; cols = 2 } else zoom = e.coerceIn(1f, 4f)
                            } else {
                                if (pinch < 0.8f) cols = (cols + 1).coerceAtMost(4) else if (pinch > 1.25f) cols -= 1
                            }
                            pinch = 1f
                        }
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    modifier = Modifier.width(widthDp).fillMaxHeight().graphicsLayer {
                        scaleX = pinch; scaleY = pinch; transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                    contentPadding = PaddingValues(horizontal = sidePad, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    items(engine.pageCount, key = { it }) { i ->
                        PdfPageItem(engine, i, pageWpx, pageWdp, settings.quality, settings.invertPdf)
                    }
                }
            }
        }
        // Thanh chuyển trang + zoom
        Row(Modifier.fillMaxWidth().background(RC.Surface).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            var sliding by remember { mutableStateOf<Float?>(null) }
            val cur = (gridState.firstVisibleItemIndex + 1).coerceIn(1, engine.pageCount)
            Text("${(sliding?.roundToInt() ?: cur)} / ${engine.pageCount}", color = RC.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).widthIn(min = 64.dp))
            if (engine.pageCount > 1) Slider(
                value = sliding ?: cur.toFloat(),
                onValueChange = { sliding = it },
                onValueChangeFinished = { val t = sliding; sliding = null; if (t != null) scope.launch { gridState.scrollToItem(t.roundToInt() - 1) } },
                valueRange = 1f..engine.pageCount.toFloat(),
                colors = SliderDefaults.colors(thumbColor = RC.Accent, activeTrackColor = RC.Accent),
                modifier = Modifier.weight(1f)
            ) else Spacer(Modifier.weight(1f))
            TextButton(onClick = { zoomOut() }, enabled = !(cols == 4), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", color = RC.Accent, fontSize = 24.sp) }
            Text(if (cols > 1) "$cols cột" else "${(zoom * 100).roundToInt()}%", color = RC.Sub, fontSize = 12.sp, modifier = Modifier.widthIn(min = 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { zoomIn() }, enabled = !(cols == 1 && zoom >= 4f), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("+", color = RC.Accent, fontSize = 24.sp) }
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
