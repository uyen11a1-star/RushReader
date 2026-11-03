package com.rushreader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rushreader.core.DocType
import com.rushreader.core.FileMeta
import com.rushreader.data.Doc
import com.rushreader.data.Settings
import com.rushreader.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    var docs by mutableStateOf(store.loadDocs()); private set
    var settings by mutableStateOf(store.loadSettings()); private set
    var root by mutableStateOf(store.loadRoot()); private set
    var queue by mutableStateOf<List<String>>(emptyList()); private set
    var queueIndex by mutableIntStateOf(0); private set
    var readingUri by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)

    val reading: Doc? get() = readingUri?.let { u -> docs.firstOrNull { it.uri == u } }
    val recents: List<Doc> get() = docs.filter { it.lastOpened > 0 }.sortedByDescending { it.lastOpened }.take(30)
    val favorites: List<Doc> get() = docs.filter { it.fav }.sortedBy { it.name.lowercase() }

    private fun commit(list: List<Doc>) {
        // giữ tối đa 100 mục: ưu tiên mục yêu thích và mục mở gần nhất
        val kept = if (list.size <= 100) list else {
            val keep = list.sortedWith(compareByDescending<Doc> { it.fav }.thenByDescending { it.lastOpened }).take(100).map { it.uri }.toSet()
            list.filter { it.uri in keep }
        }
        docs = kept; store.saveDocs(kept)
    }

    fun open(uris: List<Uri>, persist: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val now = System.currentTimeMillis()
            val found = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    try {
                        if (persist) try { app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: Exception) {}
                        val m = FileMeta.query(app, u)
                        Doc(u.toString(), m.name, DocType.resolve(app, u, m.name, m.mime), m.size, now, 0, 0, false)
                    } catch (ignored: Exception) { null }
                }
            }
            if (found.isEmpty()) { message = "Không đọc được file nào từ yêu cầu mở này."; return@launch }
            if (found.size < uris.size) message = "Đã bỏ qua ${uris.size - found.size} file không đọc được."
            var list = docs
            found.forEach { n ->
                val old = list.firstOrNull { it.uri == n.uri }
                val merged = if (old == null) n else old.copy(name = n.name, type = n.type, size = n.size, lastOpened = now)
                list = if (old == null) listOf(merged) + list else list.map { if (it.uri == n.uri) merged else it }
            }
            commit(list)
            queue = found.map { it.uri }; queueIndex = 0; readingUri = queue[0]
        }
    }

    fun switchQueue(delta: Int) {
        val i = queueIndex + delta
        if (i in queue.indices) { queueIndex = i; readingUri = queue[i] }
    }

    fun closeReader() { readingUri = null; queue = emptyList(); queueIndex = 0 }

    fun updateProgress(uri: String, page: Int, count: Int) {
        if (docs.none { it.uri == uri }) return
        commit(docs.map { if (it.uri == uri) it.copy(page = page, pageCount = count) else it })
    }

    fun toggleFav(uri: String) = commit(docs.map { if (it.uri == uri) it.copy(fav = !it.fav) else it })

    fun removeFromRecents(uri: String) {
        commit(docs.mapNotNull { if (it.uri != uri) it else if (it.fav) it.copy(lastOpened = 0) else null })
    }

    fun updateSettings(s: Settings) { settings = s; store.saveSettings(s) }

    fun setRoot(uri: Uri?) {
        if (uri != null) try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {}
        root = uri?.toString(); store.saveRoot(root)
    }
}
