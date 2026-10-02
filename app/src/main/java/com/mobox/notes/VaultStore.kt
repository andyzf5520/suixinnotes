package com.mobox.notes

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

interface VaultPersistence {
    fun read(): ByteArray
    fun save(vault: Vault, password: CharArray)
}
class VaultStore(context: Context) : VaultPersistence {
    private val file = File(context.filesDir, "vault.mobx")
    private val previous = File(context.filesDir, "vault.previous.mobx")
    private val atomic = AtomicFile(file)
    fun exists(): Boolean = file.exists() || File(file.path + ".bak").exists()
    fun previousExists() = previous.exists()
    @Synchronized override fun read(): ByteArray = atomic.openRead().use { it.limitedStoreRead() }
    @Synchronized private fun write(data: ByteArray) {
        require(data.size <= Crypto.MAX_BYTES)
        val stream = atomic.startWrite()
        try { stream.write(data); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun open(password: CharArray): Session {
        val data = Crypto.decrypt(read(), password)
        return try { Session(this, Vault.read(data), password.copyOf()) } finally { data.fill(0) }
    }
    @Synchronized fun create(password: CharArray): Session {
        require(!exists()) { "已有保险库，请先解锁或恢复" }
        return Session(this, Vault.fresh(), password.copyOf()).also { save(it.vault, password) }
    }
    @Synchronized override fun save(vault: Vault, password: CharArray) {
        val data = vault.bytes()
        try { write(Crypto.encrypt(data, password)) } finally { data.fill(0) }
    }
    @Synchronized fun install(bytes: ByteArray, password: CharArray): Session {
        val plaintext = Crypto.decrypt(bytes, password)
        val vault = try { Vault.read(plaintext) } finally { plaintext.fill(0) }
        // Current vault remains recoverable if the restored data is not what the user expected.
        if (exists()) {
            val old = read()
            val archive = AtomicFile(previous)
            val stream = archive.startWrite()
            try { stream.write(old); archive.finishWrite(stream) }
            catch (e: Exception) { archive.failWrite(stream); throw e }
        }
        write(bytes)
        return Session(this, vault, password.copyOf())
    }
    @Synchronized fun swapPrevious() {
        require(previous.exists()) { "没有上一个本地版本" }
        val old = previous.readBytes()
        val current = if (exists()) read() else null
        write(old)
        if (current != null) {
            val archive = AtomicFile(previous)
            val stream = archive.startWrite()
            try { stream.write(current); archive.finishWrite(stream) }
            catch (e: Exception) { archive.failWrite(stream); throw e }
        }
    }
}

private fun java.io.InputStream.limitedStoreRead(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(true) { val n = read(buffer); if(n < 0) break; require(output.size() + n <= Crypto.MAX_BYTES) { "保险库超过容量限制" }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
class Session(private val store: VaultPersistence, var vault: Vault, private var master: CharArray) {
    private data class OpenDomain(val password: CharArray, var notes: List<Note>)
    private val domains = mutableMapOf<String, OpenDomain>()
    private var closed = false
    @Synchronized fun visibleNotes(): List<Note> = vault.notes + domains.values.flatMap { it.notes }
    @Synchronized fun isOpen(id: String) = !closed && vault.categories.any { it.id == id && (it.sealed == null || domains.containsKey(id)) }
    private fun checkOpen() = check(!closed) { "保险库已锁定，请重新解锁" }
    private fun persist(next: Vault) { checkOpen(); store.save(next, master); vault = next }
    @Synchronized fun validateMaster(password: CharArray) { checkOpen(); val d = Crypto.decrypt(store.read(), password); d.fill(0) }
    @Synchronized fun backup(): ByteArray { checkOpen(); return store.read() }
    @Synchronized fun updatePrefs(prefs: Preferences) = persist(vault.copy(prefs = prefs))
    @Synchronized fun category(name: String, color: Int, password: CharArray?, id: String? = null) {
        checkOpen()
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 40) { "分类名须为 1–40 个字符" }
        require(vault.categories.none { it.name == clean && it.id != id }) { "已有同名分类" }
        if (id != null) {
            persist(vault.copy(categories = vault.categories.map { if (it.id == id) it.copy(name = clean, color = color) else it }))
        } else {
            require(vault.categories.size < 100) { "分类最多 100 个" }
            val category = Category(name = clean, color = color)
            val seal = password?.let { encode(category.id, emptyList(), it) }
            persist(vault.copy(categories = vault.categories + category.copy(sealed = seal)))
        }
    }
    private fun encode(id: String, notes: List<Note>, password: CharArray): String {
        val data = JSONObject().put("categoryId", id).put("notes", JSONArray(notes.map { it.json() })).toString().toByteArray()
        return try { Base64.getEncoder().encodeToString(Crypto.encrypt(data, password)) } finally { data.fill(0) }
    }
    @Synchronized fun unlockCategory(id: String, password: CharArray) {
        checkOpen()
        val c = vault.categories.first { it.id == id }
        val data = Crypto.decrypt(Base64.getDecoder().decode(c.sealed ?: error("此分类未加密")), password)
        val notes = try {
            val o = JSONObject(data.toString(Charsets.UTF_8))
            require(o.getString("categoryId") == id) { "分类密文归属无效" }
            val a = o.getJSONArray("notes")
            require(a.length() <= 10_000)
            (0 until a.length()).map { Note.read(a.getJSONObject(it)) }.also { n ->
                require(n.all { it.categoryId == id } && n.map { it.id }.distinct().size == n.size)
                require(n.none { note -> visibleNotes().any { it.id == note.id && it.categoryId != id } }) { "条目 ID 冲突" }
            }
        } finally { data.fill(0) }
        domains.remove(id)?.password?.fill('\u0000')
        domains[id] = OpenDomain(password.copyOf(), notes)
    }
    @Synchronized fun lockCategory(id: String) { domains.remove(id)?.password?.fill('\u0000') }
    @Synchronized fun saveNote(note: Note) {
        checkOpen()
        require(note.title.length <= 300 && note.body.length <= 100_000 && note.images.size <= 6) { "记录超过首版限制" }
        val target = vault.categories.first { it.id == note.categoryId }
        val old = visibleNotes().firstOrNull { it.id == note.id }
        require(old == null || old.categoryId == note.categoryId) { "首版不支持跨分类移动，请复制内容新建" }
        if (target.sealed == null) {
            persist(vault.copy(notes = vault.notes.filterNot { it.id == note.id } + note))
        } else {
            val domain = domains[note.categoryId] ?: error("请先解锁分类")
            val list = domain.notes.filterNot { it.id == note.id } + note
            val seal = encode(target.id, list, domain.password)
            persist(vault.copy(categories = vault.categories.map { if (it.id == target.id) it.copy(sealed = seal) else it }))
            domain.notes = list
        }
    }
    @Synchronized fun deleteNote(note: Note) {
        checkOpen()
        val c = vault.categories.first { it.id == note.categoryId }
        if (c.sealed == null) persist(vault.copy(notes = vault.notes.filterNot { it.id == note.id }))
        else {
            val d = domains[c.id] ?: error("请先解锁分类")
            val list = d.notes.filterNot { it.id == note.id }
            val seal = encode(c.id, list, d.password)
            persist(vault.copy(categories = vault.categories.map { if (it.id == c.id) it.copy(sealed = seal) else it }))
            d.notes = list
        }
    }
    @Synchronized fun changeMaster(password: CharArray) {
        checkOpen(); require(password.size >= 8) { "主密码至少 8 个字符" }
        store.save(vault, password); master.fill('\u0000'); master = password.copyOf()
    }
    @Synchronized fun close() {
        closed = true; master.fill('\u0000'); domains.values.forEach { it.password.fill('\u0000') }
        domains.clear(); vault = Vault.fresh()
    }
}
