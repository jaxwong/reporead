// Owns: Coverage of Library wording derived from structured synchronization outcomes.

package com.reporead.android.library

import com.reporead.android.network.ApiException
import com.reporead.android.sync.RefusedChange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owns bookmark refusal wording at the consuming UI boundary. */
class SyncMessageTest {
    @Test
    fun theLibraryWordsStructuredRefusalsWithoutLosingTheirDocumentOrReason() {
        val error = ApiException(400, "INVALID_REQUEST", "Test-only refusal")
        assertEquals(
            "The bookmark on “Long heading note” (Test-only refusal)",
            RefusedChange(1, "Long heading note", error).describe(),
        )
        assertEquals(
            "The bookmark on a note no longer in your lists (Test-only refusal)",
            RefusedChange(1, null, error).describe(),
        )
    }
}
