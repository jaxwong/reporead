// Owns: Coverage of Library wording derived from structured synchronization outcomes.

package com.reporead.android.library

import com.reporead.android.core.network.ApiException
import com.reporead.android.sync.RefusedChange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owns the preserved reading/bookmark refusal wording at the consuming UI boundary. */
class SyncMessageTest {
    @Test
    fun theLibraryWordsStructuredRefusalsWithoutLosingTheirDocumentOrReason() {
        val error = ApiException(400, "INVALID_READING_STATE", "Test-only refusal")
        assertEquals(
            "Your place in “Long heading note” (Test-only refusal)",
            RefusedChange(RefusedChange.Kind.READING, 1, "Long heading note", error).describe(),
        )
        assertEquals(
            "The bookmark on “Long heading note” (Test-only refusal)",
            RefusedChange(RefusedChange.Kind.BOOKMARK, 1, "Long heading note", error).describe(),
        )
        assertEquals(
            "Your place in a note no longer in your lists (Test-only refusal)",
            RefusedChange(RefusedChange.Kind.READING, 1, null, error).describe(),
        )
    }
}
