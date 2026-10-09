package com.reporead.android.sync

import com.reporead.android.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which failures refuse the one item a sync step sent, so the step goes on, and which leave it pending. */
class RefusalTest {
    private fun refuses(status: Int, code: String) = ApiException(status, code, "Test-only failure").refusesItem()

    @Test fun theServerRefusingThisItemIsFinalForIt() {
        for ((status, code) in listOf(400 to "INVALID_READING_STATE", 400 to "INVALID_REQUEST", 404 to "NOT_FOUND",
            404 to "SOURCE_NOT_FOUND", 409 to "MUTATION_ID_REUSED", 409 to "CARD_CHANGED", 410 to "ANNOTATION_DELETED",
            422 to "INVALID_ANCHOR", 422 to "UNSUPPORTED_CONTENT")) {
            assertEquals("$status $code", true, refuses(status, code))
        }
    }

    @Test fun failuresThatSayNothingAboutTheItemLeaveItPending() {
        for ((status, code) in listOf(0 to "BACKEND_UNAVAILABLE", 401 to "SIGN_IN_REQUIRED", 403 to "GITHUB_ACCESS_DENIED",
            429 to "HTTP_429", 500 to "HTTP_500", 502 to "GITHUB_INVALID_RESPONSE", 503 to "GITHUB_UNAVAILABLE",
            // Something other than RepoRead answered on the backend's address: not RepoRead's refusal.
            404 to "HTTP_404", 400 to "HTTP_400", 200 to "INVALID_RESPONSE")) {
            assertEquals("$status $code", false, refuses(status, code))
        }
    }
}
