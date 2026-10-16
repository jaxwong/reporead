package com.reporead.android.core.network

import java.time.Instant
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    @Test
    fun aMalformedTimeInAResponseBreaksTheContractInsteadOfCrashing() {
        val error = assertThrows(ApiException::class.java) { contract { Instant.parse("2026-10-17 not a time") } }
        assertEquals("INVALID_RESPONSE", error.code)
        assertTrue(error.cause is DateTimeParseException)
    }
}
