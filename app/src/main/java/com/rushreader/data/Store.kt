package com.rushreader.data

import android.content.Context
import com.rushreader.core.DocType
import org.json.JSONArray
import org.json.JSONObject

/** Một tài liệu đã biết. lastOpened = 0 nghĩa là không nằm trong "Gần đây" (chỉ còn vì được yêu thích). */
data class Doc(
    val uri: String, val name: String, val type: DocType, val size: Long,
    val lastOpened: Long, val page: Int, val pageCount: Int, val fav: Boolean
)

data class Settings(
    val textSp: Int = 18, val font: Int = 0, val invertPdf: Boolean = false,
    val keepOn: Boolean = true, val quality: Int = 0
)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("rush", Context.MODE_PRIVATE)

    fun loadDocs(): List<Doc> = try {
        val a = JSONArray(p.getString("docs", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Doc(o.getString("u"), o.getString("n"), DocType.byName(o.optString("t")), o.optLong("s", -1),
                o.optLong("l"), o.optInt("p"), o.optInt("c"), o.optBoolean("f")) }
    } catch (ignored: Exception) { emptyList() }

    fun saveDocs(docs: List<Doc>) {
        val a = JSONArray()
        docs.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("t", it.type.name).put("s", it.size)
            .put("l", it.lastOpened).put("p", it.page).put("c", it.pageCount).put("f", it.fav)) }
        p.edit().putString("docs", a.toString()).apply()
    }

    fun loadSettings() = Settings(
        p.getInt("textSp", 18), p.getInt("font", 0), p.getBoolean("invertPdf", false),
        p.getBoolean("keepOn", true), p.getInt("quality", 0)
    )

    fun saveSettings(s: Settings) {
        p.edit().putInt("textSp", s.textSp).putInt("font", s.font).putBoolean("invertPdf", s.invertPdf)
            .putBoolean("keepOn", s.keepOn).putInt("quality", s.quality).apply()
    }

    fun loadRoot(): String? = p.getString("root", null)
    fun saveRoot(u: String?) { p.edit().putString("root", u).apply() }
}
