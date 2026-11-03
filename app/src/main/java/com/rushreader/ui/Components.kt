package com.rushreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rushreader.core.DocType
import com.rushreader.data.Doc

fun formatSize(b: Long): String = when {
    b < 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format("%.1f MB", b / 1048576.0)
}

fun progressText(d: Doc): String? = when {
    d.pageCount <= 0 -> null
    d.type == DocType.PDF -> "Trang ${(d.page + 1).coerceAtMost(d.pageCount)}/${d.pageCount}"
    else -> "Đã đọc ${(d.page * 100L / d.pageCount).toInt().coerceIn(0, 100)}%"
}

@Composable
fun TypeBadge(type: DocType, size: Int = 46) {
    val (bg, fg) = when (type) {
        DocType.PDF -> Color(0xFF3A1E1B) to Color(0xFFFF8A7A)
        DocType.DOCX, DocType.DOC -> Color(0xFF1B2638) to Color(0xFF7FB0FF)
        DocType.TXT -> Color(0xFF26262A) to Color(0xFFC9C9D0)
        else -> Color(0xFF26262A) to RC.Sub
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(type.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DocRow(doc: Doc, onOpen: () -> Unit, onFav: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RC.Card).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeBadge(doc.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, color = RC.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(formatSize(doc.size), progressText(doc)).joinToString("  •  ")
            Text(meta, color = RC.Sub, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onFav) {
            Icon(Icons.Default.Star, contentDescription = "Yêu thích", tint = if (doc.fav) RC.Accent else Color(0xFF45454C))
        }
        if (onRemove != null) IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Xoá khỏi danh sách", tint = RC.Sub)
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RC.Accent, contentColor = RC.OnAccent)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = RC.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = RC.Sub, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, color = RC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
fun Loading(label: String = "Đang mở…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = RC.Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp)); Text(label, color = RC.Sub, fontSize = 13.sp)
        }
    }
}

@Composable
fun ErrorBox(msg: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Không mở được tài liệu", color = RC.Danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp)); Text(msg, color = RC.Sub, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}
