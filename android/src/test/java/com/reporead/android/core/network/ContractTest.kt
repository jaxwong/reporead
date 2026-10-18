// Owns: JVM coverage of wire strictness and producer/consumer agreement using shared test-only examples.

package com.reporead.android.core.network

import com.reporead.android.data.AnnotationType
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owns regression checks for the decoder and encoded request agreement, independent of Android JSON stubs. */
class ContractTest {
    private val sha = "a".repeat(40)
    private val at = Instant.parse("2000-01-01T00:00:00Z")
    private val mutation = "00000000-0000-0000-0000-000000000001"

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/api-contract/$name.json")) {
                "Missing shared producer fixture $name"
            }
            .bufferedReader()
            .use { it.readText() }

    private inline fun <reified T> answer(name: String): T = decodeResponse(fixture(name), 200)

    private inline fun <reified T> broken(body: String): ApiException {
        val error = assertThrows(ApiException::class.java) { decodeResponse<T>(body, 201) }
        assertEquals(201, error.status)
        assertEquals("INVALID_RESPONSE", error.code)
        assertTrue(error.cause is IllegalArgumentException)
        return error
    }

    private inline fun <reified T> request(name: String, value: T) {
        assertEquals(
            name,
            wireJson.parseToJsonElement(fixture("requests/$name")),
            wireJson.parseToJsonElement(wireJson.encodeToString(value)),
        )
    }

    @Test
    fun everyConsumedResponseShapeDecodesTheBackendVerifiedExample() {
        assertEquals(2, answer<Library>("library").notebook.annotations.size)
        assertTrue(answer<Library>("library-empty").repositories.isEmpty())
        assertEquals(null, answer<AvailableList>("available").repositories.single().connectionId)
        assertEquals(11L, answer<Connection>("connection").githubRepositoryId)
        assertEquals(7L, answer<RepositorySynced>("synced").repositoryId)
        assertEquals(1L, answer<NoteContent>("content").documentId)
        assertEquals(null, answer<Changes>("changes").sections.single().blockId)
        assertEquals(ChangeStatus.SINCE_UNAVAILABLE, answer<Changes>("changes-unavailable").status)
        assertEquals(50, answer<ReadingState>("reading").progressPercent)
        assertEquals(1L, answer<Bookmark>("bookmark").documentId)
        assertEquals(1, answer<AnnotationList>("annotations").annotations.size)
        assertEquals(null, answer<Annotation>("annotation").question)
        assertEquals(AnnotationType.CARD, answer<Annotation>("annotation-card").type)
        assertEquals(4, answer<Review>("review").grade)
        assertEquals(1, answer<StoredData>("stored-data").documents)
        assertEquals(listOf(1L), answer<Disconnected>("disconnected").documentIds)
        assertEquals(1, answer<AccountDeleted>("account-deleted").repositories)
        assertEquals("TEST_ONLY_SESSION", answer<IssuedSession>("session").accessToken)
        assertEquals("NOT_FOUND", answer<Failure>("failure").code)
    }

    @Test
    fun requestsMatchTheBodiesVerifiedByBackendControllers() {
        val anchor = SelectionRequest(sha, "b1", 0, 4, "text")
        request("token", TokenRequest("TEST_ONLY_CODE", "TEST_ONLY_VERIFIER", null))
        request("connect", ConnectRequest(7))
        request("reading", ReadingRequest(sha, 50, ReadingAnchor(emptyList(), null, 0), at))
        request("create", CreateAnnotationRequest(mutation, anchor, null, AnnotationType.HIGHLIGHT, null))
        request("edit", EditAnnotationRequest(null, 1))
        request("reattach", ReattachRequest(1, anchor))
        request("check", CheckRequest(1, sha))
        request("review", ReviewRequest("00000000-0000-0000-0000-000000000002", 4, at, sha))
    }

    @Test
    fun requiredNullableKeysAreDifferentFromExplicitNull() {
        broken<Available>(
            """{"githubRepositoryId":11,"installationId":7,"fullName":"test-only/notes","privateRepository":true}"""
        )
        broken<ReadingAnchor>("""{"headingPath":[],"blockIndex":0}""")
        broken<ReadingAnchor>("""{"textPrefix":null,"blockIndex":0}""")
        broken<ReadingAnchor>("""{"headingPath":[],"textPrefix":null}""")
        broken<ReadingAnchor>("""{"headingPath":null,"textPrefix":null,"blockIndex":0}""")
        broken<Library>(fixture("library").replace("\"lastSyncedCommitSha\": null,", ""))
        broken<Library>(fixture("library").replace("\"contentChangedAt\": null,", ""))
        broken<Changes>(fixture("changes").replace("\"reason\": null,", ""))
        broken<Changes>(fixture("changes").replace("\"blockId\": null,", ""))
        val annotation = wireJson.parseToJsonElement(fixture("annotation")).jsonObject
        for (field in listOf("note", "question", "checkedBlobSha")) {
            broken<Annotation>(JsonObject(annotation - field).toString())
        }
        assertEquals(null, answer<ReadingState>("reading").anchor.textPrefix)
    }

    @Test
    fun scalarsAreNotCoercedEvenInsideNullableFieldsOrLists() {
        for (bad in listOf("null", "7", "false", "[]", "{}")) {
            broken<Review>(
                fixture("review")
                    .replace("\"mutationId\": \"00000000-0000-0000-0000-000000000002\"", "\"mutationId\": $bad")
            )
        }
        for (bad in listOf("\"50\"", "12.7", "2147483648", "null", "true")) {
            broken<ReadingState>(fixture("reading").replace("\"progressPercent\": 50", "\"progressPercent\": $bad"))
        }
        for (bad in listOf("\"1\"", "1.7", "9223372036854775808", "null", "true")) {
            broken<Disconnected>("""{"documentIds":[$bad]}""")
        }
        broken<Document>(
            """{"id":1,"path":"n.md","title":"Test-only","blobSha":"$sha","contentChangedAt":null,"deleted":"false"}"""
        )
        broken<Available>(
            """{"githubRepositoryId":11,"installationId":7,"fullName":"test-only/notes","privateRepository":true,"connectionId":"7"}"""
        )
    }

    @Test
    fun consumerValueRulesFailBeforeMapping() {
        broken<ReadingState>(fixture("reading").replace("\"progressPercent\": 50", "\"progressPercent\": 101"))
        broken<ReadingAnchor>("""{"headingPath":[],"textPrefix":null,"blockIndex":-1}""")
        broken<Notebook>("""{"annotations":[],"reviews":[],"sessionLimit":0}""")
        broken<Review>(fixture("review").replace("\"grade\": 4", "\"grade\": 6"))
        for (question in listOf("null", "\"\"", "\"  \"")) {
            broken<Annotation>(
                fixture("annotation-card").replace("\"question\": \"Test-only question?\"", "\"question\": $question")
            )
        }
        broken<Annotation>(fixture("annotation").replace("\"type\": \"HIGHLIGHT\"", "\"type\": \"FUTURE_TYPE\""))
        broken<Annotation>(fixture("annotation").replace("\"endOffset\": 4", "\"endOffset\": 3"))
        broken<Annotation>(fixture("annotation").replace("\"id\": 9", "\"id\": 0"))
        broken<Annotation>(fixture("annotation").replace("\"version\": 1", "\"version\": 0"))
        broken<SessionUser>("""{"id":0}""")
        broken<NoteContent>(fixture("content").replace("b".repeat(40), "INVALID"))
        broken<IssuedSession>(fixture("session").replace("TEST_ONLY_SESSION", ""))
        for (code in listOf("7", "null", "\"\"", "\"  \"")) {
            broken<Failure>("""{"code":$code,"message":"Test-only"}""")
        }
    }

    @Test
    fun aMalformedTimeInAResponseBreaksTheContractInsteadOfCrashing() {
        val error = broken<Review>(fixture("review").replace(at.toString(), "not-a-time"))
        assertTrue(error.cause?.cause is DateTimeParseException)
        val overflow = broken<Review>(fixture("review").replace(at.toString(), Instant.MAX.toString()))
        assertTrue(overflow.cause?.cause is ArithmeticException)
        assertEquals(
            at,
            decodeResponse<Review>(fixture("review").replace(at.toString(), "2000-01-01T01:00:00+01:00"), 200)
                .reviewedAt,
        )
        assertEquals(
            at.plusNanos(123_456_789),
            decodeResponse<Review>(fixture("review").replace(at.toString(), "2000-01-01T00:00:00.123456789Z"), 200)
                .reviewedAt,
        )
    }

    @Test
    fun unknownFieldsAreIgnoredWithoutRelaxingKnownFields() {
        val body = fixture("reading").replace("\"anchor\": {", "\"extra\": 7, \"anchor\": {\"future\": true,")
        assertEquals(answer<ReadingState>("reading"), decodeResponse<ReadingState>(body, 200))
    }
}
