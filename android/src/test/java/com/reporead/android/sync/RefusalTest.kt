// Owns: Coverage of the client-owned permanent-refusal decision against server-verified status/code pairs.

package com.reporead.android.sync

import com.reporead.android.network.ApiException
import com.reporead.android.network.wireJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which failures refuse the one item a sync step sent, so the step goes on, and which leave it pending. */
class RefusalTest {
    private fun refuses(status: Int, code: String) = ApiException(status, code, "Test-only failure").refusesItem()

    @Test
    fun theServerRefusingThisItemIsFinalForIt() {
        val cases =
            checkNotNull(javaClass.getResourceAsStream("/api-contract/item-refusals.json")).bufferedReader().use {
                wireJson.parseToJsonElement(it.readText()).jsonArray
            }
        for (item in cases) {
            val status = item.jsonObject.getValue("status").jsonPrimitive.int
            val code = item.jsonObject.getValue("code").jsonPrimitive.content
            assertEquals("$status $code", true, refuses(status, code))
        }
    }

    @Test
    fun failuresThatSayNothingAboutTheItemLeaveItPending() {
        for ((status, code) in
            listOf(
                0 to "BACKEND_UNAVAILABLE",
                401 to "SIGN_IN_REQUIRED",
                403 to "GITHUB_ACCESS_DENIED",
                // Only connecting the repository again fixes it; what waits is still valid.
                409 to "REPOSITORY_RENAMED",
                // Capacity can be freed by deleting other annotations; this creation must stay pending.
                422 to "ANNOTATION_LIMIT",
                429 to "HTTP_429",
                500 to "HTTP_500",
                502 to "GITHUB_INVALID_RESPONSE",
                503 to "GITHUB_UNAVAILABLE",
                // Something other than RepoRead answered on the backend's address: not RepoRead's refusal.
                404 to "HTTP_404",
                400 to "HTTP_400",
                200 to "INVALID_RESPONSE",
                409 to "INVALID_RESPONSE",
                409 to "UNKNOWN_TEST_ONLY_CODE",
                409 to "NOT_FOUND",
                500 to "ANNOTATION_DELETED",
            )) {
            assertEquals("$status $code", false, refuses(status, code))
        }
    }
}
