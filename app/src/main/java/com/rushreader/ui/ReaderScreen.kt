package com.rushreader.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushreader.MainActivity
import com.rushreader.MainViewModel
import com.rushreader.core.DocType
import com.rushreader.data.Doc

@Composable
fun ReaderScreen(vm: MainViewModel, doc: Doc) {
    BackHandler { vm.closeReader() }
    var chrome by remember(doc.uri) { mutableStateOf(true) }
    val view = LocalView.current
    val keepOn = vm.settings.keepOn
    DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

    Column(Modifier.fillMaxSize().background(RC.Bg)) {
        if (chrome) TopBar(vm, doc)
        Box(Modifier.weight(1f).then(if (!chrome) Modifier.statusBarsPadding() else Modifier)) {
            key(doc.uri) {
                val onTap = { chrome = !chrome }
                val onProgress = { p: Int, c: Int -> vm.updateProgress(doc.uri, p, c) }
                when (doc.type) {
                    DocType.PDF -> PdfReader(doc, vm.settings, onTap, onProgress)
                    DocType.TXT -> TxtReader(doc, vm.settings, onTap, onProgress)
                    DocType.DOCX -> DocxReader(doc, vm.settings, onTap, onProgress)
                    else -> UnsupportedView(doc)
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: MainViewModel, doc: Doc) {
    Row(Modifier.fillMaxWidth().background(RC.Surface).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.closeReader() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = RC.Text) }
        Column(Modifier.weight(1f)) {
            Text(doc.name, color = RC.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(doc.type.label, color = RC.Sub, fontSize = 11.sp)
        }
        if (vm.queue.size > 1) {
            IconButton(onClick = { vm.switchQueue(-1) }, enabled = vm.queueIndex > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "File trước", tint = RC.Text) }
            Text("${vm.queueIndex + 1}/${vm.queue.size}", color = RC.Sub, fontSize = 12.sp)
            IconButton(onClick = { vm.switchQueue(1) }, enabled = vm.queueIndex < vm.queue.size - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "File sau", tint = RC.Text) }
        }
        IconButton(onClick = { vm.toggleFav(doc.uri) }) {
            Icon(Icons.Default.Star, "Yêu thích", tint = if (doc.fav) RC.Accent else Color(0xFF55555C))
        }
    }
}

@Composable
private fun UnsupportedView(doc: Doc) {
    val ctx = LocalContext.current
    val reason = when (doc.type) {
        DocType.DOC -> "Định dạng .doc (Word 97-2003, nhị phân OLE2) có cấu trúc khác hẳn DOCX. Rush Reader chưa có bộ đọc DOC nên không hiển thị được. Hãy lưu lại thành DOCX hoặc PDF, hoặc mở bằng ứng dụng khác."
        DocType.EPUB, DocType.RTF, DocType.XLSX, DocType.PPTX -> "Định dạng ${doc.type.label} chưa được hỗ trợ ở phiên bản này (dự kiến Phase 5). Bạn có thể mở bằng ứng dụng khác."
        else -> "Không nhận dạng được định dạng của file này."
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        TypeBadge(doc.type, 64)
        Spacer(Modifier.height(18.dp))
        Text("Chưa hỗ trợ ${doc.type.label}", color = RC.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(reason, color = RC.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Mở bằng ứng dụng khác") {
            val uri = Uri.parse(doc.uri)
            val mime = try { ctx.contentResolver.getType(uri) } catch (ignored: Exception) { null } ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(view, "Mở bằng")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, MainActivity::class.java)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { ctx.startActivity(chooser) } catch (ignored: Exception) {}
        }
    }
}
