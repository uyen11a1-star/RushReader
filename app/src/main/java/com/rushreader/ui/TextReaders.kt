package com.rushreader.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushreader.core.CacheManager
import com.rushreader.core.friendlyError
import com.rushreader.data.Doc
import com.rushreader.data.Settings
import com.rushreader.docx.DocxBlock
import com.rushreader.docx.DocxDocument
import com.rushreader.docx.DocxParser
import com.rushreader.docx.TxtLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private fun fontOf(i: Int) = when (i) { 1 -> FontFamily.Serif; 2 -> FontFamily.Monospace; else -> FontFamily.Default }

@Composable
fun TxtReader(doc: Doc, s: Settings, onTap: () -> Unit, onProgress: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    var res by remember { mutableStateOf<TxtLoader.Result?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.uri) {
        try { res = withContext(Dispatchers.IO) { TxtLoader.load(ctx, Uri.parse(doc.uri)) } }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { err = friendlyError(e) }
    }
    val r = res
    when {
        err != null -> ErrorBox(err!!)
        r == null -> Loading()
        else -> {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = doc.page.coerceIn(0, (r.lines.size - 1).coerceAtLeast(0)))
            LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.collectLatest { delay(400); onProgress(it, r.lines.size) } }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
            ) {
                items(r.lines.size) { i ->
                    Text(r.lines[i].ifEmpty { " " }, color = RC.Text, fontSize = s.textSp.sp, lineHeight = (s.textSp * 1.55f).sp, fontFamily = fontOf(s.font))
                }
                if (r.truncated) item { Text("— File quá lớn: chỉ hiển thị 6 MB đầu tiên —", color = RC.Sub, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp)) }
            }
        }
    }
}

@Composable
fun DocxReader(doc: Doc, s: Settings, onTap: () -> Unit, onProgress: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<DocxDocument?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.uri) {
        try {
            data = withContext(Dispatchers.IO) {
                val f = CacheManager.copyToCache(ctx, Uri.parse(doc.uri))
                try { DocxParser.parse(f) } catch (e: Throwable) { f.delete(); throw e }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { err = friendlyError(e) }
    }
    DisposableEffect(doc.uri) { onDispose { data?.close() } }

    val d = data
    when {
        err != null -> ErrorBox(err!!)
        d == null -> Loading("Đang đọc DOCX…")
        else -> {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = doc.page.coerceIn(0, (d.blocks.size - 1).coerceAtLeast(0)))
            LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.collectLatest { delay(400); onProgress(it, d.blocks.size) } }
            val ff = fontOf(s.font)
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(d.blocks.size) { i ->
                    when (val b = d.blocks[i]) {
                        is DocxBlock.Para -> {
                            val scale = when (b.level) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.25f; 4, 5, 6 -> 1.1f; else -> 1f }
                            val sz = s.textSp * scale
                            Row(Modifier.padding(start = (b.listLevel * 18).dp, top = if (b.level > 0) 8.dp else 0.dp)) {
                                if (b.list) Text("•  ", color = RC.Accent, fontSize = sz.sp, fontFamily = ff)
                                Text(b.text, color = if (b.level == 1) RC.Accent else RC.Text, fontSize = sz.sp, lineHeight = (sz * 1.5f).sp,
                                    fontWeight = if (b.level > 0) FontWeight.Bold else null, textAlign = b.align, fontFamily = ff, modifier = Modifier.weight(1f))
                            }
                        }
                        is DocxBlock.Table -> DocxTable(b, s, ff)
                        is DocxBlock.Image -> DocxImage(d, b)
                    }
                }
                if (d.truncated) item { Text("— Tài liệu rất dài: chỉ hiển thị phần đầu —", color = RC.Sub, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun DocxTable(t: DocxBlock.Table, s: Settings, ff: FontFamily) {
    val cols = t.rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val wide = cols > 3
    Column(Modifier.then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()).border(1.dp, RC.Outline)) {
        t.rows.forEach { row ->
            Row {
                for (c in 0 until cols) {
                    Text(row.getOrNull(c) ?: "", color = RC.Text, fontSize = (s.textSp * 0.85f).sp, fontFamily = ff,
                        modifier = (if (wide) Modifier.width(130.dp) else Modifier.weight(1f)).border(0.5.dp, RC.Outline).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DocxImage(d: DocxDocument, b: DocxBlock.Image) {
    val density = LocalDensity.current
    val maxW = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, b.entry) { value = withContext(Dispatchers.IO) { d.loadImage(b.entry, maxW) } }
    val bm = bmp
    if (bm != null) Image(bm.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
    else Text("[Hình ảnh không hiển thị được]", color = RC.Sub, fontSize = 12.sp)
}
