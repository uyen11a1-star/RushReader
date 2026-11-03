package com.rushreader.docx

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object XmlUtil {
    fun local(n: String?) = n?.substringAfter(':') ?: ""

    fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
        return null
    }

    /** Parser cho XHTML/XML; khai báo sẵn vài entity HTML hay gặp trong EPUB. */
    fun parser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val e = mapOf("nbsp" to " ", "copy" to "©", "mdash" to "—", "ndash" to "–", "hellip" to "…",
            "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "laquo" to "«", "raquo" to "»", "bull" to "•")
        try { e.forEach { (k, v) -> p.defineEntityReplacementText(k, v) } } catch (ignored: Exception) {}
        return p
    }

    /** Ghép đường dẫn tương đối trong file ZIP (xử lý ./ ../ %20 và #fragment). */
    fun resolve(baseDir: String, ref: String): String {
        val clean = Uri.decode(ref.substringBefore('#').substringBefore('?'))
        val parts = ArrayList<String>()
        val full = if (clean.startsWith("/")) clean.removePrefix("/") else if (baseDir.isEmpty()) clean else "$baseDir/$clean"
        for (s in full.split('/')) when (s) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(s) }
        return parts.joinToString("/")
    }

    fun readRels(zip: java.util.zip.ZipFile, path: String): Map<String, String> {
        val e = zip.getEntry(path) ?: return emptyMap()
        val out = HashMap<String, String>()
        try {
            val p = parser(zip.getInputStream(e))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id"); val t = attr(p, "Target")
                    if (id != null && t != null) out[id] = t
                }
                ev = p.next()
            }
        } catch (ignored: Exception) {}
        return out
    }
}
