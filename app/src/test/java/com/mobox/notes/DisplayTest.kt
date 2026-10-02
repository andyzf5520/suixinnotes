package com.mobox.notes

import org.junit.Assert.*
import org.junit.Test

class DisplayTest {
    @Test fun blankTitleUsesOnlyFirstBodyLineWithoutChangingStoredTitle() {
        val n = Note(categoryId = "c", title = "  ", body = " 第一行 \r\n第二行")
        assertEquals("第一行", n.displayTitle()); assertEquals("  ", n.title)
        assertEquals("手写标题", n.copy(title = " 手写标题 ").displayTitle())
        assertEquals("无标题", n.copy(body = "\n第二行").displayTitle())
    }
    @Test fun timestampSortingAndPinnedPriorityAreDeterministic() {
        val notes = listOf(Note(id = "b", categoryId = "c", updated = 2), Note(id = "a", categoryId = "c", updated = 1), Note(id = "pin", categoryId = "c", updated = 0, favorite = true))
        assertEquals(listOf("pin", "b", "a"), NoteOrdering.sorted(notes, "updated_desc").map { it.id })
        assertEquals(listOf("pin", "a", "b"), NoteOrdering.sorted(notes, "updated_asc").map { it.id })
    }
    @Test fun titleSortUsesFallbackAndKeepsPinsFirst() {
        val notes = listOf(Note(id = "b", categoryId = "c", title = "Banana"), Note(id = "a", categoryId = "c", body = "Apple\nHidden"), Note(id = "z", categoryId = "c", title = "Zebra", favorite = true))
        assertEquals(listOf("z", "a", "b"), NoteOrdering.sorted(notes, "title_asc").map { it.id })
        assertEquals(listOf("z", "b", "a"), NoteOrdering.sorted(notes, "title_desc").map { it.id })
    }
    @Test fun previousBackupsRemainReadableAndNewPreferencesPersist() {
        val v = Vault.fresh(); val o = org.json.JSONObject(v.bytes().toString(Charsets.UTF_8))
        o.getJSONObject("prefs").remove("sort"); o.getJSONObject("prefs").remove("defaultDownloads")
        assertEquals("updated_desc", Vault.read(o.toString().toByteArray()).prefs.sort)
        assertTrue(Vault.read(o.toString().toByteArray()).prefs.defaultDownloads)
        val next = v.copy(prefs = Preferences(grid = false, sort = "title_desc", defaultDownloads = false))
        assertEquals(next, Vault.read(next.bytes()))
    }
    @Test fun fallbackTitlesAreUsedAndEscapedInAllExportFormats() {
        val n = Note(categoryId = "c", kind = "account", body = "<script>First</script>\nSecond")
        assertTrue(Exports.html(listOf(n), false).contains("<h1>&lt;script&gt;First&lt;/script&gt;</h1>"))
        assertTrue(Exports.text(listOf(n), false).startsWith("<script>First</script>\n"))
        assertTrue(Exports.csv(listOf(n), false).contains("\"<script>First</script>\""))
    }
    @Test fun fallbackTitleLengthIsBoundedAndNeverUsesPasswordFields() {
        assertEquals(300, Note(categoryId = "c", body = "a".repeat(1000)).displayTitle().length)
        assertEquals("无标题", Note(categoryId = "c", kind = "account", username = "alice", password = "DoNotShow").displayTitle())
    }
}
