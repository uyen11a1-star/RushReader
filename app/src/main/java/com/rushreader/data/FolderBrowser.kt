package com.rushreader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.rushreader.core.DocType

data class BEntry(val uri: Uri, val docId: String, val name: String, val isDir: Boolean, val size: Long, val type: DocType)

object FolderBrowser {
    fun list(ctx: Context, tree: Uri, docId: String): List<BEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<BEntry>()
        try {
            ctx.contentResolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val dir = mime == Document.MIME_TYPE_DIR
                    var t = DocType.UNKNOWN
                    if (!dir) {
                        t = DocType.fromExtension(name)
                        if (t == DocType.UNKNOWN) t = DocType.fromMime(mime)
                        if (t == DocType.UNKNOWN) continue
                    }
                    out.add(BEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, name, dir, size, t))
                }
            }
        } catch (ignored: Exception) {}
        return out.sortedWith(compareByDescending<BEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }
}
