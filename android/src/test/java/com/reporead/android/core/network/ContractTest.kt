// Owns: JVM coverage of decoding strictness and value rules for the generated wire models.

package com.reporead.android.core.network

import com.reporead.android.core.network.wire.AnnotationsAnnotation
import com.reporead.android.core.network.wire.AppSessionsIssuedSession
import com.reporead.android.core.network.wire.AppUser
import com.reporead.android.core.network.wire.ConnectionDataDisconnected
import com.reporead.android.core.network.wire.DocumentControllerChanges
import com.reporead.android.core.network.wire.DocumentControllerContent
import com.reporead.android.core.network.wire.DocumentsSummary
import com.reporead.android.core.network.wire.LibraryRepository
import com.reporead.android.core.network.wire.LibrarySnapshot
import com.reporead.android.core.network.wire.NoteChangesSection
import com.reporead.android.core.network.wire.ReadingStatesAnchor
import com.reporead.android.core.network.wire.ReadingStatesState
import com.reporead.android.core.network.wire.RepositoryConnectionsAvailable
import com.reporead.android.core.network.wire.ReviewsNotebook
import com.reporead.android.core.network.wire.ReviewsReview
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What generation cannot promise: strict tokens, required-nullable presence and the value rules ([checkRules]). */
class ContractTest {
    private inline fun <reified T> json(value: T) = wireJson.parseToJsonElement(wire(value)).jsonObject

    /** [key] replaced by a raw JSON value, or removed when [raw] is null. */
    private fun JsonObject.with(key: String, raw: String?) =
        JsonObject(if (raw == null) this - key else this + (key to wireJson.parseToJsonElement(raw)))

    private inline fun <reified T : Any> broken(body: Any): ApiException {
        val error = assertThrows(ApiException::class.java) { decodeResponse<T>(body.toString(), 201) }
        assertEquals(201, error.status)
        assertEquals("INVALID_RESPONSE", error.code)
        assertTrue(error.cause is IllegalArgumentException)
        return error
    }

    /** [key] holding null decodes, and the same answer without the key is broken. */
    private inline fun <reified T : Any> requiredNullable(answer: JsonObject, key: String) {
        decodeResponse<T>(answer.with(key, "null").toString(), 200)
        broken<T>(answer.with(key, null))
    }

    @Test
    fun aNullableKeyMustStillBePresent() {
        for (key in listOf("note", "question", "checkedBlobSha")) {
            requiredNullable<AnnotationsAnnotation>(json(testHighlight()), key)
        }
        requiredNullable<LibraryRepository>(json(testRepository()), "lastSyncedCommitSha")
        requiredNullable<DocumentsSummary>(json(testDocument()), "contentChangedAt")
        requiredNullable<DocumentControllerChanges>(json(testChanges()), "reason")
        requiredNullable<NoteChangesSection>(json(testChanges().sections.single()), "blockId")
        requiredNullable<RepositoryConnectionsAvailable>(json(testAvailable()), "connectionId")
        requiredNullable<ReadingStatesAnchor>(json(testReading().anchor), "textPrefix")
        broken<ReviewsReview>(json(testReview()).with("grade", null))
    }

    @Test
    fun scalarsAreNotCoercedEvenInsideNullableFieldsOrLists() {
        for (bad in listOf("null", "7", "false", "[]", "{}")) {
            broken<ReviewsReview>(json(testReview()).with("mutationId", bad))
        }
        for (bad in listOf("\"50\"", "12.7", "2147483648", "null", "true")) {
            broken<ReadingStatesState>(json(testReading()).with("progressPercent", bad))
        }
        for (bad in listOf("\"1\"", "1.7", "9223372036854775808", "null", "true")) {
            broken<ConnectionDataDisconnected>(json(testDisconnected()).with("documentIds", "[$bad]"))
        }
        broken<DocumentsSummary>(json(testDocument()).with("deleted", "\"false\""))
        broken<RepositoryConnectionsAvailable>(json(testAvailable()).with("connectionId", "\"7\""))
    }

    @Test
    fun valueRulesFailBeforeAnythingIsPublished() {
        broken<ReadingStatesState>(wire(testReading().copy(progressPercent = 101)))
        broken<ReadingStatesAnchor>(wire(testReading().anchor.copy(blockIndex = -1)))
        broken<ReviewsNotebook>(wire(testLibrary().notebook.copy(sessionLimit = 0)))
        broken<ReviewsReview>(wire(testReview().copy(grade = 6)))
        for (question in listOf(null, "", "  ")) broken<AnnotationsAnnotation>(
            wire(testCard().copy(question = question))
        )
        broken<AnnotationsAnnotation>(wire(testHighlight().copy(type = AnnotationsAnnotation.Type.BOOKMARK)))
        broken<AnnotationsAnnotation>(json(testHighlight()).with("type", "\"FUTURE_TYPE\""))
        broken<AnnotationsAnnotation>(wire(testHighlight().copy(location = testPassage().copy(endOffset = 3))))
        broken<AnnotationsAnnotation>(wire(testHighlight().copy(id = 0)))
        broken<AnnotationsAnnotation>(wire(testHighlight().copy(version = 0)))
        broken<AppUser>(wire(testSession().user.copy(id = 0)))
        broken<DocumentControllerContent>(wire(testContent().copy(snapshotCommitSha = "INVALID")))
        broken<AppSessionsIssuedSession>(wire(testSession(accessToken = "")))
        // Rules reach into what an answer holds: one bad reading state breaks the whole library.
        broken<LibrarySnapshot>(wire(testLibrary(readingStates = listOf(testReading().copy(progressPercent = -1)))))
    }

    @Test
    fun aMalformedTimeBreaksTheContractInsteadOfCrashing() {
        val review = json(testReview())
        val error = broken<ReviewsReview>(review.with("reviewedAt", "\"not-a-time\""))
        assertTrue(error.cause?.cause is DateTimeParseException)
        val overflow = broken<ReviewsReview>(review.with("reviewedAt", "\"${Instant.MAX}\""))
        assertTrue(overflow.cause?.cause is ArithmeticException)
        val offset = review.with("reviewedAt", "\"2000-01-01T01:00:00+01:00\"")
        assertEquals(testAt, decodeResponse<ReviewsReview>(offset.toString(), 200).reviewedAt)
        val nanos = review.with("reviewedAt", "\"2000-01-01T00:00:00.123456789Z\"")
        assertEquals(testAt.plusNanos(123_456_789), decodeResponse<ReviewsReview>(nanos.toString(), 200).reviewedAt)
    }

    @Test
    fun unknownFieldsAreIgnoredWithoutRelaxingKnownFields() {
        val anchor = json(testReading().anchor).with("future", "true")
        val body = json(testReading()).with("extra", "7").with("anchor", anchor.toString())
        assertEquals(testReading(), decodeResponse<ReadingStatesState>(body.toString(), 200))
    }
}
