package com.mobox.notes

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    private val initial = Note(id = "edit", categoryId = "c", body = "原文", updated = 1)
    @Test fun typingCanUndoAndRedoWithoutChangingNoteIdentity() {
        val h = EditHistory(initial).record(initial.copy(body = "原文新输入", updated = 2))
        assertTrue(h.canUndo); assertFalse(h.canRedo)
        val undone = h.undo(3)
        assertEquals("原文", undone.current.body); assertEquals(initial.id, undone.current.id)
        assertFalse(undone.canUndo); assertTrue(undone.canRedo)
        assertEquals("原文新输入", undone.redo(4).current.body)
    }
    @Test fun newInputAfterUndoClearsRedo() {
        val h = EditHistory(initial).record(initial.copy(body = "A")).record(initial.copy(body = "B"))
        val branch = h.undo(3).record(initial.copy(body = "C"))
        assertFalse(branch.canRedo); assertEquals("C", branch.redo(4).current.body)
        assertEquals("A", branch.undo(5).current.body)
    }
    @Test fun titleAndFormattingAndImagesAreRestoredTogether() {
        val h = EditHistory(initial).record(initial.copy(title = "标题", bold = true, images = listOf("AQID")))
        assertEquals(initial.copy(updated = 10), h.undo(10).current)
        assertEquals(listOf("AQID"), h.undo(10).redo(11).current.images)
    }
    @Test fun noOpAndTimestampOnlyUpdatesDoNotConsumeHistory() {
        val h = EditHistory(initial)
        assertEquals(h, h.record(initial.copy(updated = 999)))
        assertEquals(h, h.undo(2)); assertEquals(h, h.redo(2))
    }
    @Test fun historyIsBoundedAndNotSerialized() {
        var h = EditHistory(initial)
        repeat(150) { h = h.record(initial.copy(body = "text-$it")) }
        assertEquals(100, h.past.size)
        assertFalse(h.current.json().has("past")); assertFalse(h.current.json().has("future"))
    }
    @Test fun undoUpdatesSaveTimestampAndRejectsCrossNoteHistory() {
        val h = EditHistory(initial).record(initial.copy(body = "changed"))
        assertEquals(100, h.undo(100).current.updated)
        assertThrows(IllegalArgumentException::class.java) { h.record(initial.copy(id = "another")) }
    }
}
