package com.reporead.android.core.network

import com.reporead.android.auth.StoredSession
import javax.crypto.IllegalBlockSizeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionUnreadableTest {
    @Test
    fun aSessionThePhoneCannotReadNowFailsTheRequestBeforeConnecting() {
        val failure = IllegalBlockSizeException()
        // Port 1 refuses connections: reaching the network would fail as BACKEND_UNAVAILABLE instead.
        val api =
            Api("http://127.0.0.1:1", { StoredSession.TemporarilyUnavailable(failure) }) {
                error("Nothing was sent, so nothing was refused")
            }

        val error = assertThrows(ApiException::class.java) { api.bytes("/api/documents/1/images/a.png") }

        assertEquals(0, error.status)
        assertEquals("SESSION_UNREADABLE", error.code)
        assertSame(failure, error.cause)
        assertEquals("This phone couldn't read its sign-in just now. Nothing was sent; try again.", error.describe())
    }
}
