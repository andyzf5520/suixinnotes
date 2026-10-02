package com.mobox.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString()
data class Note(
    val id: String = uid(), val categoryId: String, val kind: String = "note",
    val title: String = "", val body: String = "", val username: String = "",
    val password: String = "", val url: String = "", val images: List<String> = emptyList(),
    val fontSize: Int = 17, val font: String = "system", val bold: Boolean = false,
    val italic: Boolean = false, val paper: Int = 0, val favorite: Boolean = false,
    val updated: Long = System.currentTimeMillis()
) {
    fun displayTitle(): String = title.trim().ifBlank {
        body.lineSequence().firstOrNull()?.trim()?.take(300).orEmpty().ifBlank { "无标题" }
    }
    fun json() = JSONObject().put("id", id).put("categoryId", categoryId).put("kind", kind)
        .put("title", title).put("body", body).put("username", username).put("password", password)
        .put("url", url).put("images", JSONArray(images)).put("fontSize", fontSize)
        .put("font", font).put("bold", bold).put("italic", italic).put("paper", paper)
        .put("favorite", favorite).put("updated", updated)
    companion object {
        fun read(o: JSONObject): Note {
            val images = o.optJSONArray("images") ?: JSONArray()
            require(images.length() <= 6) { "每条记录最多 6 张图片" }
            val note = Note(o.getString("id"), o.getString("categoryId"), o.optString("kind", "note"),
                o.optString("title"), o.optString("body"), o.optString("username"), o.optString("password"),
                o.optString("url"), (0 until images.length()).map { images.getString(it) },
                o.optInt("fontSize", 17).coerceIn(12, 28), o.optString("font", "system"),
                o.optBoolean("bold"), o.optBoolean("italic"), o.optInt("paper").coerceIn(0, 4),
                o.optBoolean("favorite"), o.optLong("updated"))
            require(note.kind in listOf("note", "account", "todo")) { "未知记录类型" }
            require(note.title.length <= 300 && note.body.length <= 100_000) { "文本超过限制" }
            require(note.username.length <= 10_000 && note.password.length <= 10_000 && note.url.length <= 10_000)
            note.images.forEach { require(it.length <= 3_000_000) { "图片超过限制" }; Base64.getDecoder().decode(it) }
            return note
        }
    }
}
data class Category(val id: String = uid(), val name: String, val color: Int = 0, val sealed: String? = null) {
    fun json() = JSONObject().put("id", id).put("name", name).put("color", color).apply { sealed?.let { put("sealed", it) } }
}
data class Preferences(val dark: Boolean = false, val grid: Boolean = true, val accent: Int = 0, val fontSize: Int = 17,
    val sort: String = "updated_desc", val defaultDownloads: Boolean = true) {
    fun json() = JSONObject().put("dark", dark).put("grid", grid).put("accent", accent).put("fontSize", fontSize)
        .put("sort", sort).put("defaultDownloads", defaultDownloads)
}
data class Vault(val categories: List<Category>, val notes: List<Note> = emptyList(), val prefs: Preferences = Preferences()) {
    fun bytes(): ByteArray = JSONObject().put("version", 1).put("categories", JSONArray(categories.map { it.json() }))
        .put("notes", JSONArray(notes.map { it.json() })).put("prefs", prefs.json()).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun fresh() = Vault(listOf(Category(name = "随手记"), Category(name = "账号", color = 1), Category(name = "生活", color = 2)))
        fun read(bytes: ByteArray): Vault {
            val o = JSONObject(bytes.toString(Charsets.UTF_8))
            require(o.getInt("version") == 1) { "不支持的数据版本" }
            val a = o.getJSONArray("categories")
            require(a.length() in 1..100) { "分类数量无效" }
            val cats = (0 until a.length()).map {
                val c = a.getJSONObject(it)
                Category(c.getString("id"), c.getString("name"), c.optInt("color").coerceIn(0, 4), if (c.has("sealed")) c.getString("sealed") else null)
            }
            require(cats.map { it.id }.distinct().size == cats.size && cats.all { it.name.isNotBlank() && it.name.length <= 40 })
            val n = o.getJSONArray("notes")
            require(n.length() <= 10_000)
            val notes = (0 until n.length()).map { Note.read(n.getJSONObject(it)) }
            require(notes.map { it.id }.distinct().size == notes.size)
            require(notes.all { note -> cats.any { it.id == note.categoryId && it.sealed == null } }) { "分类归属无效" }
            val p = o.optJSONObject("prefs") ?: JSONObject()
            val sort = p.optString("sort", "updated_desc").takeIf { it in NoteOrdering.options.keys } ?: "updated_desc"
            return Vault(cats, notes, Preferences(p.optBoolean("dark"), p.optBoolean("grid", true), p.optInt("accent").coerceIn(0, 3), p.optInt("fontSize", 17).coerceIn(12, 28), sort, p.optBoolean("defaultDownloads", true)))
        }
    }
}
object NoteOrdering {
    val options = linkedMapOf("updated_desc" to "修改时间：最新在前", "updated_asc" to "修改时间：最早在前", "title_asc" to "标题：升序", "title_desc" to "标题：降序")
    fun sorted(notes: List<Note>, sort: String): List<Note> {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val order = when(sort) {
            "updated_asc" -> compareBy<Note> { it.updated }
            "title_asc" -> Comparator<Note> { a, b -> collator.compare(a.displayTitle(), b.displayTitle()) }
            "title_desc" -> Comparator<Note> { a, b -> collator.compare(b.displayTitle(), a.displayTitle()) }
            else -> compareByDescending { it.updated }
        }
        return notes.sortedWith(compareByDescending<Note> { it.favorite }.then(order).thenBy { it.id })
    }
}
object Exports {
    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun csv(s: String): String {
        val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n')) "'$s" else s
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun csv(notes: List<Note>, secrets: Boolean) = "\uFEFF" + (listOf(listOf("标题", "用户名", "密码", "网址", "说明")) + notes.filter { it.kind == "account" }.map {
        listOf(it.displayTitle(), it.username, if (secrets) it.password else "[已隐藏]", it.url, it.body)
    }).joinToString("\r\n") { row -> row.joinToString(",", transform = ::csv) }
    fun text(notes: List<Note>, secrets: Boolean) = notes.joinToString("\n\n────────\n\n") {
        buildString { append(it.displayTitle()).append('\n'); if (it.kind == "account") append("用户名：${it.username}\n密码：${if (secrets) it.password else "[已隐藏]"}\n网址：${it.url}\n"); append(it.body); if (it.images.isNotEmpty()) append("\n[${it.images.size} 张图片未包含在 TXT 中]") }
    }
    fun html(notes: List<Note>, secrets: Boolean): String = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>墨匣导出</title><body>" + notes.joinToString("") {
        "<article><h1>${html(it.displayTitle())}</h1>" + (if (it.kind == "account") "<p>用户名：${html(it.username)}<br>密码：${html(if (secrets) it.password else "[已隐藏]")}<br>网址：${html(it.url)}</p>" else "") +
            "<div style=\"white-space:pre-wrap;font-size:${it.fontSize}px;font-weight:${if(it.bold) "bold" else "normal"};font-style:${if(it.italic) "italic" else "normal"}\">${html(it.body)}</div>" +
            it.images.joinToString("") { image -> "<p><img alt=\"笔记图片\" style=\"max-width:100%\" src=\"data:image/jpeg;base64,$image\"></p>" } + "</article><hr>"
    } + "</body></html>"
}
