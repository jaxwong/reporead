// Test-only page fixture without canonical search blocks. Production saves must provide the backend-issued blocks.
package com.reporead.android.data

internal suspend fun LibraryDao.saveNoteWithoutSearch(row: NoteRow) = saveNote(row, emptyList())
