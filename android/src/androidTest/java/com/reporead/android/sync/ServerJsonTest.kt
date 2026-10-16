package com.reporead.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reporead.android.core.network.ApiException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Android's own org.json, which JVM unit tests only have as stubs. */
@RunWith(AndroidJUnit4::class)
class ServerJsonTest {
    /** Android's getString reads a JSON null as the text "null" and a number as its digits; neither is a string. */
    @Test
    fun aNullOrANumberWhereTheContractSaysTextBreaksIt() {
        val review =
            JSONObject()
                .put("cardMutationId", "test-only-card")
                .put("grade", 4)
                .put("reviewedAt", "2026-10-17T00:00:00Z")
                .put("blobSha", "a".repeat(40))
        for (notText in listOf(JSONObject.NULL, 7)) {
            review.put("mutationId", notText)
            assertEquals("INVALID_RESPONSE", assertThrows(ApiException::class.java) { reviewRow(review) }.code)
        }
    }
}
