// Test-only page fixture without canonical search blocks. Production saves must provide the backend-issued blocks.
package com.reporead.android.database

internal suspend fun RepoReadDao.saveNoteWithoutSearch(row: SavedPageEntity) = saveNote(row, emptyList())
