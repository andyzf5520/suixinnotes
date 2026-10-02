package com.mobox.notes

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private class MemoryStore : VaultPersistence {
        var data = ByteArray(0)
        var fail = false
        override fun read() = data.copyOf()
        override fun save(vault: Vault, password: CharArray) {
            if(fail) error("模拟磁盘满")
            data = Crypto.encrypt(vault.bytes(), password)
        }
    }
    private val master = "Master-Secret2026".toCharArray()
    private fun setup(): Pair<MemoryStore,Session> {
        val store = MemoryStore(); val v = Vault.fresh(); store.save(v, master)
        return store to Session(store, v, master.copyOf())
    }
    @Test fun privateCategoryIsAbsentFromOuterVaultAndLockClearsVisibleData() {
        val (store,session) = setup(); val privatePass = "Private-Secret2026".toCharArray()
        session.category("秘密", 3, privatePass)
        val c = session.vault.categories.last()
        assertFalse(session.isOpen(c.id))
        assertThrows(javax.crypto.AEADBadTagException::class.java) { session.unlockCategory(c.id, master) }
        session.unlockCategory(c.id, privatePass)
        val n = Note(categoryId = c.id, kind = "account", title = "隐藏标题", password = "DomainOnlySecret")
        session.saveNote(n)
        assertEquals(n, session.visibleNotes().single())
        val outer = Vault.read(Crypto.decrypt(store.read(), master))
        assertTrue(outer.notes.isEmpty()); assertFalse(outer.bytes().toString(Charsets.UTF_8).contains("DomainOnlySecret"))
        session.lockCategory(c.id); assertTrue(session.visibleNotes().isEmpty()); assertFalse(session.isOpen(c.id))
        session.unlockCategory(c.id, privatePass); assertEquals(n, session.visibleNotes().single())
    }
    @Test fun failureDoesNotCommitInMemoryOrReplaceExistingBackup() {
        val (store,session) = setup(); val original = store.read(); val originalVault = session.vault
        store.fail = true
        assertThrows(IllegalStateException::class.java) { session.saveNote(Note(categoryId = session.vault.categories.first().id, title = "unsaved")) }
        assertEquals(originalVault, session.vault); assertArrayEquals(original, store.read())
    }
    @Test fun closeRejectsSavingAndRemovesVisibleSecrets() {
        val (_,s) = setup(); val n = Note(categoryId = s.vault.categories.first().id, password = "Secret")
        s.saveNote(n); s.close(); assertTrue(s.visibleNotes().isEmpty()); assertFalse(s.isOpen(n.categoryId))
        assertThrows(IllegalStateException::class.java) { s.saveNote(n) }
    }
    @Test fun changingMasterPreservesCategoryPasswordAndOldBackupPassword() {
        val (store,s) = setup(); val catPass = "Category-Secret2026".toCharArray(); val newPass = "New-Master2026".toCharArray()
        s.category("私密", 0, catPass); val c = s.vault.categories.last(); val oldBackup = s.backup()
        s.changeMaster(newPass)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(store.read(), master) }
        assertEquals(s.vault, Vault.read(Crypto.decrypt(store.read(), newPass)))
        assertEquals(s.vault.categories, Vault.read(Crypto.decrypt(oldBackup, master)).categories)
        s.unlockCategory(c.id, catPass); assertTrue(s.isOpen(c.id))
    }
    @Test fun duplicateCategoryAndCrossCategoryMoveRejected() {
        val (_,s) = setup(); assertThrows(IllegalArgumentException::class.java) { s.category("随手记", 0, null) }
        val n = Note(categoryId = s.vault.categories.first().id, title = "record"); s.saveNote(n)
        assertThrows(IllegalArgumentException::class.java) { s.saveNote(n.copy(categoryId = s.vault.categories[1].id)) }
        assertEquals(n, s.visibleNotes().single())
    }
    @Test fun deletePrivateNotePersistsAndDoesNotAffectPublicNotes() {
        val (_,s) = setup(); val p = "Private-Pass2026".toCharArray()
        val public = Note(categoryId = s.vault.categories.first().id, title = "公开"); s.saveNote(public)
        s.category("加密", 1, p); val c = s.vault.categories.last(); s.unlockCategory(c.id, p)
        val private = Note(categoryId = c.id, title = "私密"); s.saveNote(private); s.deleteNote(private); s.lockCategory(c.id); s.unlockCategory(c.id, p)
        assertEquals(listOf(public), s.visibleNotes())
    }
}
