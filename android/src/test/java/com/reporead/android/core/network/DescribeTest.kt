package com.reporead.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app words a code for its own screens; the server's message names none of them. */
class DescribeTest {
    private fun describe(status: Int, code: String) = ApiException(status, code, "Test-only server message").describe()

    @Test
    fun codesWhoseFixIsInThisAppAreWordedWithItsControls() {
        assertTrue(describe(404, "DEFAULT_BRANCH_NOT_FOUND").contains("tap Update on it under Add repository"))
        assertTrue(describe(502, "REPOSITORY_RENAMED").contains("tap Update on it under Add repository"))
        assertTrue(describe(409, "ACCOUNT_MISMATCH").contains("sign out first to remove them from this phone"))
        assertTrue(describe(409, "SYNC_SUPERSEDED").contains("refresh again"))
        assertTrue(describe(409, "ANNOTATION_CONFLICT").contains("reopen the note"))
    }

    @Test
    fun anyOtherCodeShowsTheServersMessage() {
        assertEquals("Test-only server message", describe(502, "GITHUB_INVALID_RESPONSE"))
    }
}
