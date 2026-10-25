// Owns: Coverage that temporarily unreadable sessions fail requests unsent without losing their cause.

package com.reporead.android.network

import com.reporead.android.auth.StoredSession
import javax.crypto.IllegalBlockSizeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Owns the unsent-request and preserved-session assertions for transient decryption failures. */
class SessionUnreadableTest {
    @Test
    fun aSessionThePhoneCannotReadNowFailsTheRequestBeforeConnecting() {
        val failure = IllegalBlockSizeException()
        // Port 1 refuses connections: reaching the network would fail as BACKEND_UNAVAILABLE instead.
        val api =
            Api("http://127.0.0.1:1", { StoredSession.TemporarilyUnavailable(failure) }) {
                error("Nothing was sent, so nothing was refused")
            }

        val error = assertThrows(ApiException::class.java) { api.documentImage(1, "a".repeat(40), "a.png") }

        assertEquals(0, error.status)
        assertEquals("SESSION_UNREADABLE", error.code)
        assertSame(failure, error.cause)
        assertEquals("This phone couldn't read its sign-in just now. Nothing was sent; try again.", error.describe())
    }
}
