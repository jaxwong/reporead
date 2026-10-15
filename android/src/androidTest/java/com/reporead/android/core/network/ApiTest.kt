package com.reporead.android.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reporead.android.data.LoopbackServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Api owns what a 401 means: the session it sent is no longer accepted. Test-only loopback server, never the backend.
 */
@RunWith(AndroidJUnit4::class)
class ApiTest {
    private fun failure(status: Int, code: String) =
        status to JSONObject().put("code", code).put("message", "Test-only $code").toString()

    @Test
    fun aRefusedSessionIsReportedOnceWithTheTokenThatWasSent() = runBlocking {
        val refused = mutableListOf<String?>()
        LoopbackServer(
                mapOf(
                    "GET /api/auth/me" to failure(401, "HTTP_401"),
                    "GET /api/repositories" to failure(401, "SIGN_IN_REQUIRED"),
                    "GET /api/bookmarks" to failure(503, "GITHUB_UNAVAILABLE"),
                    "GET /api/notebook" to failure(404, "NOT_FOUND"),
                )
            )
            .use { server ->
                val api = Api(server.url, { "TEST_ONLY_SESSION" }) { refused += it }
                for (path in listOf("/api/auth/me", "/api/repositories", "/api/bookmarks", "/api/notebook")) {
                    try {
                        api.get(path)
                        fail("$path must fail")
                    } catch (expected: ApiException) {
                        assertTrue(expected.status != 0)
                    }
                }
            }
        // Only the two 401s, each naming the session it was sent with; other failures say nothing about the session.
        assertEquals(listOf("TEST_ONLY_SESSION", "TEST_ONLY_SESSION"), refused)
    }
}
