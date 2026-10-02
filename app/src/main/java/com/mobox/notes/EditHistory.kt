package com.mobox.notes

/** Session-only history; never serialized into the vault or exported. */
data class EditHistory(val current: Note, val past: List<Note> = emptyList(), val future: List<Note> = emptyList()) {
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun record(next: Note): EditHistory {
        require(next.id == current.id)
        if(next.copy(updated = current.updated) == current) return this
        return EditHistory(next, (past + current).takeLast(100), emptyList())
    }
    fun undo(now: Long): EditHistory = if(!canUndo) this else
        EditHistory(past.last().copy(updated = now), past.dropLast(1), listOf(current) + future)
    fun redo(now: Long): EditHistory = if(!canRedo) this else
        EditHistory(future.first().copy(updated = now), (past + current).takeLast(100), future.drop(1))
}
