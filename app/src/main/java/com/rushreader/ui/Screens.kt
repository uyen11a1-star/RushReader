package com.rushreader.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushreader.MainViewModel
import com.rushreader.core.CacheManager
import com.rushreader.core.DocType
import com.rushreader.data.BEntry
import com.rushreader.data.Doc
import com.rushreader.data.FolderBrowser
import com.rushreader.pdf.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val OPEN_MIMES = arrayOf(
    "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain",
    "application/epub+zip", "application/rtf", "text/rtf",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/octet-stream"
)

@Composable
fun RushApp(vm: MainViewModel) {
    val reading = vm.reading
    if (reading != null) { ReaderScreen(vm, reading); return }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.open(it, persist = true) }
    val openFiles = { picker.launch(OPEN_MIMES) }

    Scaffold(
        containerColor = RC.Bg,
        bottomBar = {
            NavigationBar(containerColor = RC.Surface) {
                val items = listOf("Trang chủ" to Icons.Default.Home, "Duyệt file" to Icons.Default.List,
                    "Yêu thích" to Icons.Default.Favorite, "Cài đặt" to Icons.Default.Settings)
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RC.Accent, selectedTextColor = RC.Accent,
                            indicatorColor = Color(0xFF2B2616), unselectedIconColor = RC.Sub, unselectedTextColor = RC.Sub)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm, openFiles)
                1 -> BrowserScreen(vm, openFiles)
                2 -> FavoritesScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }

    vm.message?.let {
        AlertDialog(
            onDismissRequest = { vm.message = null }, containerColor = RC.Card,
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK", color = RC.Accent) } },
            text = { Text(it, color = RC.Text) }
        )
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, openFiles: () -> Unit) {
    val recents = vm.recents
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                buildAnnotatedString { withStyle(SpanStyle(color = RC.Accent)) { append("Rush") }; append(" Reader") },
                color = RC.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 12.dp)
            )
            Text("Đọc nhanh  •  Nhẹ  •  Hoàn toàn offline", color = RC.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            PrimaryButton("Mở tài liệu", onClick = openFiles)
        }
        val last = recents.firstOrNull { it.type.supported }
        if (last != null) item {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF211E12)).clickable { vm.open(listOf(Uri.parse(last.uri))) }.padding(16.dp)) {
                Text("TIẾP TỤC ĐỌC", color = RC.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Text(last.name, color = RC.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                val pt = progressText(last)
                if (pt != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (last.page.toFloat() / last.pageCount.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        color = RC.Accent, trackColor = Color(0xFF3A3520), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp)); Text(pt, color = RC.Sub, fontSize = 12.sp)
                }
            }
        }
        item { Text("Gần đây", color = RC.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
        if (recents.isEmpty()) item { EmptyState("Chưa có tài liệu nào", "Nhấn \"Mở tài liệu\" hoặc mở file từ trình quản lý file bằng Rush Reader.") }
        items(recents, key = { it.uri }) { d ->
            DocRow(d, onOpen = { vm.open(listOf(Uri.parse(d.uri))) }, onFav = { vm.toggleFav(d.uri) }, onRemove = { vm.removeFromRecents(d.uri) })
        }
    }
}

@Composable
private fun BrowserScreen(vm: MainViewModel, openFiles: () -> Unit) {
    val ctx = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setRoot(it) }
    val rootStr = vm.root
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Duyệt file")
        if (rootStr == null) {
            EmptyState("Chọn thư mục để duyệt", "Rush Reader chỉ đọc thư mục bạn cấp quyền (ví dụ Download hoặc Documents), không quét toàn bộ máy.")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Chọn thư mục") { treePicker.launch(null) }
                OutlinedButton(onClick = openFiles, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Hoặc chọn file riêng lẻ", color = RC.Text) }
            }
            return@Column
        }
        val tree = remember(rootStr) { Uri.parse(rootStr) }
        val stack = remember(rootStr) { mutableStateListOf(DocumentsContract.getTreeDocumentId(tree) to "Thư mục gốc") }
        BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
        val top = stack.last()
        val entries by produceState<List<BEntry>?>(null, rootStr, top.first) {
            value = null
            value = withContext(Dispatchers.IO) { FolderBrowser.list(ctx, tree, top.first) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stack.joinToString(" / ") { it.second }, color = RC.Sub, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = { treePicker.launch(null) }) { Text("Đổi thư mục", color = RC.Accent, fontSize = 12.sp) }
        }
        val list = entries
        if (list == null) Loading("Đang đọc thư mục…")
        else if (list.isEmpty()) EmptyState("Thư mục trống", "Không có tài liệu hoặc thư mục con nào được hỗ trợ ở đây.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.docId }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RC.Card).clickable {
                        if (e.isDir) stack.add(e.docId to e.name) else vm.open(listOf(e.uri))
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (e.isDir) Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2B2616)), contentAlignment = Alignment.Center) { Text("DIR", color = RC.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    else TypeBadge(e.type)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = RC.Text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (!e.isDir) Text("${e.type.label}  •  ${formatSize(e.size)}", color = RC.Sub, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: MainViewModel) {
    val favs = vm.favorites
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Yêu thích")
        if (favs.isEmpty()) EmptyState("Chưa có tài liệu yêu thích", "Nhấn biểu tượng ngôi sao ở một tài liệu để lưu vào đây.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(favs, key = { it.uri }) { d -> DocRow(d, onOpen = { vm.open(listOf(Uri.parse(d.uri))) }, onFav = { vm.toggleFav(d.uri) }) }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Cài đặt")
        Section("Văn bản (TXT, DOCX)") {
            Text("Cỡ chữ: ${s.textSp} sp", color = RC.Text, fontSize = 14.sp)
            Slider(value = s.textSp.toFloat(), onValueChange = { vm.updateSettings(s.copy(textSp = it.toInt())) }, valueRange = 14f..30f,
                colors = SliderDefaults.colors(thumbColor = RC.Accent, activeTrackColor = RC.Accent))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sans", "Serif", "Mono").forEachIndexed { i, n ->
                    FilterChip(selected = s.font == i, onClick = { vm.updateSettings(s.copy(font = i)) }, label = { Text(n) })
                }
            }
        }
        Section("PDF") {
            SwitchRow("Trang tối (đảo màu)", "Dễ đọc ban đêm; ảnh trong PDF cũng bị đảo màu", s.invertPdf) { vm.updateSettings(s.copy(invertPdf = it)) }
            Text("Chất lượng render", color = RC.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tự động", "Tiết kiệm RAM", "Cao").forEachIndexed { i, n ->
                    FilterChip(selected = s.quality == i, onClick = { vm.updateSettings(s.copy(quality = i)) }, label = { Text(n, fontSize = 12.sp) })
                }
            }
        }
        Section("Chung") {
            SwitchRow("Giữ màn hình sáng khi đọc", null, s.keepOn) { vm.updateSettings(s.copy(keepOn = it)) }
            OutlinedButton(onClick = {
                val freed = CacheManager.clear(ctx); BitmapPool.clear()
                Toast.makeText(ctx, "Đã xoá ${formatSize(freed)} cache", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Xoá cache tạm", color = RC.Text) }
        }
        Section("Định dạng hỗ trợ (v0.1)") {
            Text(
                "• PDF: đọc được (engine PdfRenderer của Android). Chưa có tìm kiếm văn bản; PDF có mật khẩu chưa mở được.\n" +
                "• DOCX: văn bản, tiêu đề, danh sách (chỉ dấu •), bảng dạng chữ, ảnh PNG/JPG/GIF/WebP. Chưa có ảnh EMF/WMF, số thứ tự danh sách, header/footer.\n" +
                "• TXT: UTF-8, UTF-16 (có BOM), Windows-1258; chỉ đọc 6 MB đầu của file quá lớn.\n" +
                "• DOC (Word 97-2003): chưa đọc được — cấu trúc khác DOCX.\n" +
                "• EPUB, RTF, XLSX, PPTX: chưa hỗ trợ (dự kiến Phase 5).",
                color = RC.Sub, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Text("Rush Reader 0.1.0", color = RC.Sub, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RC.Card).padding(16.dp)) {
        Text(title, color = RC.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RC.Text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = RC.Sub, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = RC.OnAccent, checkedTrackColor = RC.Accent))
    }
}
