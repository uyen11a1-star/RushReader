package com.rushreader.core

import java.io.FileNotFoundException

fun friendlyError(e: Throwable): String = when (e) {
    is SecurityException -> "Không có quyền đọc file này (quyền có thể đã hết hạn). Hãy mở lại từ trình quản lý file."
    is FileNotFoundException -> "File không còn tồn tại hoặc đã bị di chuyển."
    is OutOfMemoryError -> "Hết bộ nhớ khi mở tài liệu này."
    else -> e.message ?: "Lỗi không xác định khi mở tài liệu."
}
