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

    @Test fun batchMoveCombinesOrdinaryAndEncryptedSourcesWithoutLeakingContent() {
        val (store,s) = setup(); val sourcePass = "Source-Private2026".toCharArray(); val boxPass = "Box-Private2026".toCharArray()
        val first = Note(categoryId = s.vault.categories.first().id, title = "ordinary-hidden", body = "body", images = listOf("AQID"), bold = true)
        val second = Note(categoryId = s.vault.categories[1].id, kind = "account", title = "account-hidden", username = "user", password = "MoveSecret")
        s.saveNote(first); s.saveNote(second)
        s.category("源私密", 2, sourcePass); val source = s.vault.categories.last(); s.unlockCategory(source.id, sourcePass)
        val third = Note(categoryId = source.id, title = "source-hidden"); s.saveNote(third)
        s.setBoxPassword(null, boxPass); s.unlockCategory(Session.BOX_ID, boxPass)
        s.moveToBox(setOf(first.id,second.id,third.id)); s.lockCategory(Session.BOX_ID)
        assertTrue(s.visibleNotes().isEmpty())
        val outer = Vault.read(Crypto.decrypt(store.read(), master)); assertTrue(outer.notes.isEmpty())
        val text = outer.bytes().toString(Charsets.UTF_8); assertFalse(text.contains("MoveSecret")); assertFalse(text.contains("ordinary-hidden"))
        s.unlockCategory(Session.BOX_ID, boxPass); val moved = s.visibleNotes()
        assertEquals(3,moved.size); assertTrue(moved.all { it.categoryId == Session.BOX_ID })
        assertEquals(first.images,moved.first { it.id == first.id }.images); assertTrue(moved.first { it.id == first.id }.bold)
        s.lockCategory(source.id); s.unlockCategory(source.id, sourcePass); assertEquals(3,s.visibleNotes().size)
    }
    @Test fun failedMovePreservesBothDiskAndVisibleOriginalNotes() {
        val (store,s) = setup(); val p = "Box-Private2026".toCharArray()
        val n = Note(categoryId = s.vault.categories.first().id, body = "keep me"); s.saveNote(n)
        s.setBoxPassword(null,p); s.unlockCategory(Session.BOX_ID,p)
        val original = store.read(); val vault = s.vault; val visible = s.visibleNotes(); store.fail = true
        assertThrows(IllegalStateException::class.java) { s.moveToBox(setOf(n.id)) }
        assertArrayEquals(original,store.read()); assertEquals(vault,s.vault); assertEquals(visible,s.visibleNotes())
    }
    @Test fun moveBackToOrdinaryAndBetweenFoldersPreservesRecordIdentity() {
        val (_,s) = setup(); val p = "Box-Private2026".toCharArray(); val first = s.vault.categories.first().id; val second = s.vault.categories[1].id
        val n = Note(categoryId = first, title = "move back", password = "secret", images = listOf("AQID")); s.saveNote(n)
        s.moveNotes(setOf(n.id),second); assertEquals(second,s.visibleNotes().single().categoryId)
        s.setBoxPassword(null,p)
        assertThrows(IllegalArgumentException::class.java) { s.moveToBox(setOf(n.id)) }
        s.unlockCategory(Session.BOX_ID,p); s.moveToBox(setOf(n.id)); s.moveNotes(setOf(n.id),first)
        assertEquals(n.id,s.visibleNotes().single().id); assertEquals(first,s.vault.notes.single().categoryId); assertEquals(n.images,s.vault.notes.single().images)
        s.lockCategory(Session.BOX_ID); s.unlockCategory(Session.BOX_ID,p); assertEquals(1,s.visibleNotes().size)
    }
    @Test fun boxPasswordChangeRejectsWrongOldAndKeepsOldBackupUsable() {
        val (_,s) = setup(); val old = "Old-Box2026".toCharArray(); val next = "New-Box2026".toCharArray(); val backupPass = "Backup-Only2026".toCharArray()
        s.setBoxPassword(null,old); s.unlockCategory(Session.BOX_ID,old)
        val n = Note(categoryId = Session.BOX_ID, body = "private saved"); s.saveNote(n)
        val backup = s.portableBackup(backupPass); val vault = s.vault
        assertThrows(javax.crypto.AEADBadTagException::class.java) { s.setBoxPassword("wrong".toCharArray(),next) }; assertEquals(vault,s.vault)
        s.setBoxPassword(old,next); assertFalse(s.isOpen(Session.BOX_ID))
        assertThrows(javax.crypto.AEADBadTagException::class.java) { s.unlockCategory(Session.BOX_ID,old) }; s.unlockCategory(Session.BOX_ID,next); assertEquals(n,s.visibleNotes().single())
        val restored = Session(MemoryStore(),Vault.read(Crypto.decrypt(backup,backupPass)),master.copyOf()); restored.unlockCategory(Session.BOX_ID,old); assertEquals(n,restored.visibleNotes().single())
    }
    @Test fun portableBackupUsesDedicatedPasswordAndExcludesOpenedPlaintextDomains() {
        val (_,s) = setup(); val p = "Box-Private2026".toCharArray(); val backupPass = "Backup-Only2026".toCharArray()
        s.setBoxPassword(null,p); s.unlockCategory(Session.BOX_ID,p); s.saveNote(Note(categoryId = Session.BOX_ID, title = "InvisibleTitle"))
        val bytes = s.portableBackup(backupPass)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(bytes,master) }
        val restored = Vault.read(Crypto.decrypt(bytes,backupPass)); assertTrue(restored.notes.isEmpty()); assertFalse(restored.bytes().toString(Charsets.UTF_8).contains("InvisibleTitle"))
        assertThrows(IllegalArgumentException::class.java) { s.portableBackup("short".toCharArray()) }
    }
    @Test fun categoryPinAndDefaultRowLayoutSurviveSerialization() {
        val (store,s) = setup(); assertFalse(s.vault.prefs.grid); val id = s.vault.categories[1].id
        s.pinCategory(id,true); val restored = Vault.read(Crypto.decrypt(store.read(),master)); assertTrue(restored.categories.first { it.id == id }.pinned)
        s.pinCategory(id,false); assertFalse(s.vault.categories.first { it.id == id }.pinned)
    }
}
