package com.reporead.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.Hashes;
import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.datacontrol.ConnectionData;
import com.reporead.repository.RepositoryConnections;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

class AnnotationTest extends SharedSpringTest {
    // Test-only note, users, and tokens; GitHub is mocked and only ever read.
    private static final byte[] OLD =
            "# Spring\n\n## Proxies\n\nBy default, Spring implements declarative transactions using a proxy around the target bean.\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String OLD_SHA = Hashes.gitBlobSha(OLD);
    /** The note has since moved on: a paragraph was inserted above the highlighted one, which is unchanged. */
    private static final byte[] CURRENT = ("# Spring\n\nTransactions are a core Spring feature.\n\n## Proxies\n\n"
                    + "By default, Spring implements declarative transactions using a proxy around the target bean.\n")
            .getBytes(StandardCharsets.UTF_8);

    private static final String CURRENT_SHA = Hashes.gitBlobSha(CURRENT);
    /** A later rewrite that no longer contains the highlighted passage. */
    private static final byte[] REWRITTEN =
            "# Spring\n\n## Weaving\n\nAspectJ weaves advice into the bytecode at build time.\n"
                    .getBytes(StandardCharsets.UTF_8);

    private static final String REWRITTEN_SHA = Hashes.gitBlobSha(REWRITTEN);
    private static final String EXACT = "Spring implements declarative transactions";
    private static final int START = "By default, ".length();

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("githubUserApi")
    RestTemplate githubUserApi;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    AppSessions sessions;

    @Autowired
    JdbcClient db;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    RepositoryConnections connections;

    @Autowired
    ConnectionData data;

    @Autowired
    Annotations annotations;

    /** Sum of a counter across its tags; meters are shared by every test in the context, so tests compare deltas. */
    private double total(String name) {
        return meters.find(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    MockRestServiceServer github;
    String alice;
    long note;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserApi)
                .ignoreExpectOrder(true)
                .build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE");
        db.sql(
                        "insert into repository_connections (user_id, github_repository_id, owner, name, default_branch) values (1, 11, 'test-only', 'notes', 'main')")
                .update();
        // The note has moved on to CURRENT; annotations are created against the older version the user was reading.
        note = db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha)
                values (1, 'backend/spring.md', 'spring', :sha) returning id""").param("sha", CURRENT_SHA).query(Long.class).single();
    }

    @AfterEach
    void verifyOnlyExpectedReads() {
        github.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private void expectSource(ExpectedCount count) {
        github.expect(count, requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + OLD_SHA))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OLD, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void expectVersion(ExpectedCount count, String sha, byte[] bytes) {
        github.expect(count, requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void next() {
        github.verify();
        github.reset();
    }

    private static String body(String mutationId, String block, int start, String exact, String note) {
        return "{\"mutationId\":\"" + mutationId + "\",\"anchor\":{\"sourceBlobSha\":\"" + OLD_SHA + "\",\"blockId\":\""
                + block
                + "\",\"startOffset\":" + start + ",\"endOffset\":" + (start + exact.length()) + ",\"exactText\":\""
                + exact + "\"},\"note\":"
                + (note == null ? "null" : "\"" + note + "\"") + "}";
    }

    private ResultActions create(String bearer, long documentId, String json) throws Exception {
        return mvc.perform(post("/api/documents/" + documentId + "/annotations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions edit(String bearer, long id, String note, int expectedVersion) throws Exception {
        return mvc.perform(patch("/api/annotations/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + note + "\",\"expectedVersion\":" + expectedVersion + "}"));
    }

    private ResultActions remove(String bearer, long id, int expectedVersion) throws Exception {
        return mvc.perform(delete("/api/annotations/" + id)
                .param("expectedVersion", Integer.toString(expectedVersion))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private int count(String table) {
        return db.sql("select count(*) from " + table).query(Integer.class).single();
    }

    @Test
    void createdAnchorIsVerifiedAgainstTheSelectedVersionAndContextComesFromTheServer() throws Exception {
        expectSource(ExpectedCount.once());
        expectVersion(ExpectedCount.once(), CURRENT_SHA, CURRENT);
        String id = UUID.randomUUID().toString();
        create(alice, note, body(id, "b2", START, EXACT, "Proxies only intercept external calls"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mutationId").value(id))
                .andExpect(jsonPath("$.type").value("HIGHLIGHT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ANCHORED"))
                .andExpect(jsonPath("$.anchor.sourceBlobSha").value(OLD_SHA))
                .andExpect(jsonPath("$.anchor.prefixText").value("By default, "))
                .andExpect(jsonPath("$.anchor.suffixText").value(" using a proxy around the target"))
                .andExpect(jsonPath("$.anchor.headingPath[1]").value("Proxies"));
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.annotations.length()").value(1))
                .andExpect(jsonPath("$.annotations[0].note").value("Proxies only intercept external calls"));
    }

    @Test
    void replayAfterALostAcknowledgementReturnsTheOriginalWithoutGitHub() throws Exception {
        expectSource(ExpectedCount.once());
        double created = total(AnnotationService.CREATED);
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        String first = create(alice, note, json)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = create(alice, note, json)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertEquals(first, second);
        assertEquals(1, count("annotations"));
        assertEquals(created + 1, total(AnnotationService.CREATED), "a replay is not counted as a new highlight");
    }

    @Test
    void reusingAMutationIdForDifferentContentIsRejected() throws Exception {
        expectSource(ExpectedCount.once());
        String id = UUID.randomUUID().toString();
        create(alice, note, body(id, "b2", START, EXACT, "first")).andExpect(status().isCreated());
        create(alice, note, body(id, "b2", START, EXACT, "second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MUTATION_ID_REUSED"));
        assertEquals(1, count("annotations"));
    }

    @Test
    void concurrentDuplicateSubmissionsCreateExactlyOneAnnotation() throws Exception {
        expectSource(ExpectedCount.between(1, 4));
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, "once");
        Callable<Integer> submit =
                () -> create(alice, note, json).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var statuses = executor.invokeAll(List.of(submit, submit, submit, submit)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    })
                    .toList();
            assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
            assertEquals(3, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        }
        assertEquals(1, count("annotations"));
        assertEquals(1, count("annotation_anchors"));
        assertEquals(1, count("annotation_mutations"));
    }

    @Test
    void selectionsThatDoNotMatchTheVersionAreRejectedWithoutState() throws Exception {
        expectSource(ExpectedCount.times(3));
        create(alice, note, body(UUID.randomUUID().toString(), "b2", START + 1, EXACT, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ANCHOR"));
        create(alice, note, body(UUID.randomUUID().toString(), "b9", START, EXACT, null))
                .andExpect(status().isUnprocessableContent());
        create(alice, note, body(UUID.randomUUID().toString(), "b2", 200, "beyond", null))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, count("annotations"));
        assertEquals(0, count("annotation_mutations"));
    }

    @Test
    void malformedRequestsAreRejectedBeforeGitHub() throws Exception {
        String valid = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        for (String json : List.of(
                valid.replaceFirst("\"mutationId\":\"[^\"]+\"", "\"mutationId\":\"not-a-uuid\""),
                valid.replace(OLD_SHA, "nope"),
                valid.replace("\"b2\"", "\"x\""),
                body(UUID.randomUUID().toString(), "b2", START, "", null),
                valid.replace("\"endOffset\":" + (START + EXACT.length()), "\"endOffset\":" + (START + 1)),
                body(UUID.randomUUID().toString(), "b2", START, EXACT, "x".repeat(10_001)),
                "{\"mutationId\":\"" + UUID.randomUUID() + "\"}")) {
            create(alice, note, json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        }
    }

    @Test
    void aSelectionThatSplitsACharacterIsRefusedBeforeGitHub() throws Exception {
        // In "An 🍎 a day." the apple is two UTF-16 units at 3 and 4; half of it matches the block as Java compares
        // text.
        String sha = Hashes.gitBlobSha("# Fruit\n\nAn \uD83C\uDF4E a day.\n".getBytes(StandardCharsets.UTF_8));
        for (String range : List.of(
                "\"startOffset\":0,\"endOffset\":4,\"exactText\":\"An \\ud83c\"",
                "\"startOffset\":4,\"endOffset\":7,\"exactText\":\"\\udf4e a\"")) {
            String anchor = "{\"sourceBlobSha\":\"" + sha + "\",\"blockId\":\"b1\"," + range + "}";
            create(alice, note, "{\"mutationId\":\"" + UUID.randomUUID() + "\",\"anchor\":" + anchor + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
            mvc.perform(post("/api/annotations/1/reattach")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"anchor\":" + anchor + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        }
        assertEquals(0, count("annotation_mutations"));
    }

    @Test
    void textTheDatabaseCannotStoreExactlyIsRefusedAsTheItemsFault() throws Exception {
        // PostgreSQL refuses U+0000, and the driver stores an unpaired surrogate as '?'.
        var unstorable = List.of("a\\u0000b", "a\\ud83cb", "a\\udf4eb");
        for (String text : unstorable) {
            create(alice, note, body(UUID.randomUUID().toString(), "b2", START, EXACT, text))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
            create(alice, note, cardBody().replace("How are transactions implemented?", text))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        }
        assertEquals(0, count("annotation_mutations"));
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "kept"));
        for (String text : unstorable) {
            edit(alice, id, text, 1)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        }
        assertEquals(
                List.of("kept"),
                db.sql("select note from annotations where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .list());
    }

    @Test
    void gitHubFailureStoresNothingAndTheSameMutationSucceedsLater() throws Exception {
        github.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + OLD_SHA))
                .andRespond(withServiceUnavailable());
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        create(alice, note, json)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        assertEquals(0, count("annotation_mutations"));
        next();
        expectSource(ExpectedCount.once());
        create(alice, note, json).andExpect(status().isCreated());
    }

    @Test
    void editsUseOptimisticVersionsAndConflictsAreVisible() throws Exception {
        expectSource(ExpectedCount.once());
        expectVersion(ExpectedCount.once(), CURRENT_SHA, CURRENT);
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "v1"));
        edit(alice, id, "from phone", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        edit(alice, id, "stale laptop edit", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOTATION_CONFLICT"));
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.annotations[0].note").value("from phone"));
        mvc.perform(patch("/api/annotations/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletionIsVersionedAndALateReplayCannotResurrectIt() throws Exception {
        expectSource(ExpectedCount.once());
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        long id = createdId(json);
        // No annotation is ever at version 0, so it is a malformed request, not a conflict.
        remove(alice, id, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        mvc.perform(delete("/api/annotations/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        remove(alice, id, 2).andExpect(status().isConflict());
        remove(alice, id, 1).andExpect(status().isNoContent());
        remove(alice, id, 1).andExpect(status().isNotFound());
        create(alice, note, json)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ANNOTATION_DELETED"));
        assertEquals(0, count("annotations"));
        assertEquals(0, count("annotation_anchors"));
    }

    @Test
    void annotationsArePrivateAndBookmarksAreNotEditableAsHighlights() throws Exception {
        expectSource(ExpectedCount.once());
        expectVersion(ExpectedCount.once(), CURRENT_SHA, CURRENT);
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
        create(bob, note, body(UUID.randomUUID().toString(), "b2", START, EXACT, null))
                .andExpect(status().isNotFound());
        edit(bob, id, "x", 1).andExpect(status().isNotFound());
        remove(bob, id, 1).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + note + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceBlobSha\":\"" + CURRENT_SHA + "\"}"))
                .andExpect(status().isOk());
        long bookmark = db.sql("select id from annotations where type = 'BOOKMARK'")
                .query(Long.class)
                .single();
        edit(alice, bookmark, "x", 1).andExpect(status().isNotFound());
        remove(alice, bookmark, 1).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.annotations.length()").value(1));
    }

    // ---- Re-anchoring, orphans, reattachment ----

    private ResultActions list(String bearer) throws Exception {
        return mvc.perform(
                get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions reattach(
            String bearer, long id, int expectedVersion, String sha, String block, int start, String exact)
            throws Exception {
        return mvc.perform(post("/api/annotations/" + id + "/reattach")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + ",\"anchor\":{\"sourceBlobSha\":\"" + sha
                        + "\",\"blockId\":\"" + block + "\",\"startOffset\":" + start + ",\"endOffset\":"
                        + (start + exact.length())
                        + ",\"exactText\":\"" + exact + "\"}}"));
    }

    private void moveNoteTo(String sha) {
        db.sql("update documents set current_blob_sha = :sha where id = :id")
                .param("sha", sha)
                .param("id", note)
                .update();
    }

    @Test
    void listingResolvesHighlightsAgainstTheCurrentVersionOnceAndKeepsTheOriginalSelection() throws Exception {
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "kept"));
        next();
        expectVersion(ExpectedCount.once(), CURRENT_SHA, CURRENT);
        list(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annotations[0].status").value("REANCHORED"))
                .andExpect(jsonPath("$.annotations[0].resolvedBlobSha").value(CURRENT_SHA))
                .andExpect(jsonPath("$.annotations[0].location.sourceBlobSha").value(CURRENT_SHA))
                .andExpect(jsonPath("$.annotations[0].location.blockId").value("b3"))
                .andExpect(jsonPath("$.annotations[0].location.startOffset").value(START))
                .andExpect(jsonPath("$.annotations[0].location.exactText").value(EXACT))
                .andExpect(jsonPath("$.annotations[0].location.rivalContext").doesNotExist())
                .andExpect(jsonPath("$.annotations[0].anchor.sourceBlobSha").value(OLD_SHA))
                .andExpect(jsonPath("$.annotations[0].anchor.blockId").value("b2"))
                .andExpect(jsonPath("$.annotations[0].version").value(1));
        next();
        // Resolved against the current version: listing again needs no GitHub request.
        list(alice).andExpect(jsonPath("$.annotations[0].location.blockId").value("b3"));
        // Re-anchoring is not an edit: the client's version is still current.
        edit(alice, id, "still mine", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("REANCHORED"));
    }

    @Test
    void aPassageThatIsGoneIsOrphanedWithItsOriginalAndLastLocationAndCanBeReattached() throws Exception {
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "about proxies"));
        next();
        moveNoteTo(REWRITTEN_SHA);
        expectVersion(ExpectedCount.once(), REWRITTEN_SHA, REWRITTEN);
        double orphaned = meters.counter(AnnotationService.REANCHOR, "outcome", "ORPHANED")
                .count();
        list(alice)
                .andExpect(jsonPath("$.annotations[0].status").value("ORPHANED"))
                .andExpect(jsonPath("$.annotations[0].resolvedBlobSha").value(REWRITTEN_SHA))
                .andExpect(jsonPath("$.annotations[0].location.sourceBlobSha").value(OLD_SHA))
                .andExpect(jsonPath("$.annotations[0].anchor.exactText").value(EXACT))
                .andExpect(jsonPath("$.annotations[0].anchor.prefixText").value("By default, "))
                .andExpect(jsonPath("$.annotations[0].note").value("about proxies"));
        assertEquals(
                orphaned + 1,
                meters.counter(AnnotationService.REANCHOR, "outcome", "ORPHANED")
                        .count());
        next();

        // Rejected before GitHub: no version, or a malformed selection.
        mvc.perform(post("/api/annotations/" + id + "/reattach")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anchor\":null}"))
                .andExpect(status().isBadRequest());
        reattach(alice, id, 1, "nope", "b2", 0, "AspectJ").andExpect(status().isBadRequest());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        reattach(bob, id, 1, REWRITTEN_SHA, "b2", 0, "AspectJ").andExpect(status().isNotFound());

        expectVersion(ExpectedCount.times(3), REWRITTEN_SHA, REWRITTEN);
        reattach(alice, id, 1, REWRITTEN_SHA, "b2", 9, "weaves")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ANCHOR"));
        reattach(alice, id, 1, REWRITTEN_SHA, "b2", 8, "weaves advice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REANCHORED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.resolvedBlobSha").value(REWRITTEN_SHA))
                .andExpect(jsonPath("$.location.exactText").value("weaves advice"))
                .andExpect(jsonPath("$.location.headingPath[1]").value("Weaving"))
                .andExpect(jsonPath("$.anchor.exactText").value(EXACT));
        reattach(alice, id, 1, REWRITTEN_SHA, "b2", 0, "AspectJ")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOTATION_CONFLICT"));
        next();
        list(alice)
                .andExpect(jsonPath("$.annotations[0].status").value("REANCHORED"))
                .andExpect(jsonPath("$.annotations[0].location.blockId").value("b2"));
    }

    @Test
    void anOrphanIsFoundAgainWhenItsPassageReturnsAndTheOriginalPlaceIsAnchoredAgain() throws Exception {
        expectSource(ExpectedCount.once());
        createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        next();
        moveNoteTo(REWRITTEN_SHA);
        expectVersion(ExpectedCount.once(), REWRITTEN_SHA, REWRITTEN);
        list(alice).andExpect(jsonPath("$.annotations[0].status").value("ORPHANED"));
        next();
        // A branch rewind back to the version the highlight was made on.
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        list(alice)
                .andExpect(jsonPath("$.annotations[0].status").value("ANCHORED"))
                .andExpect(jsonPath("$.annotations[0].resolvedBlobSha").value(OLD_SHA))
                .andExpect(jsonPath("$.annotations[0].location.blockId").value("b2"));
    }

    @Test
    void aDeletedNoteKeepsItsHighlightsWithoutGitHubRequests() throws Exception {
        expectSource(ExpectedCount.once());
        createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "still here"));
        next();
        db.sql("update documents set deleted_at = now() where id = :id")
                .param("id", note)
                .update();
        list(alice)
                .andExpect(jsonPath("$.annotations[0].note").value("still here"))
                .andExpect(jsonPath("$.annotations[0].status").value("ANCHORED"))
                .andExpect(jsonPath("$.annotations[0].resolvedBlobSha").value(OLD_SHA));
    }

    @Test
    void concurrentListingsResolveAHighlightToOneLocation() throws Exception {
        expectSource(ExpectedCount.once());
        createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        next();
        expectVersion(ExpectedCount.between(1, 4), CURRENT_SHA, CURRENT);
        double resolutions = total(AnnotationService.REANCHOR);
        Callable<Integer> listing = () -> list(alice).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : executor.invokeAll(List.of(listing, listing, listing, listing)))
                assertEquals(200, result.get());
        }
        assertEquals(1, count("annotation_locations"));
        assertEquals(resolutions + 1, total(AnnotationService.REANCHOR), "only the applied resolution is counted");
        assertEquals(
                List.of("REANCHORED", CURRENT_SHA),
                db.sql("select status, resolved_blob_sha from annotations")
                        .query((row, n) -> List.of(row.getString(1), row.getString(2)))
                        .single());
    }

    private long createdId(String json) throws Exception {
        String response = create(alice, note, json)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("^\\{\"id\":(\\d+),.*$", "$1"));
    }

    // Card fixtures are test-only; these exercise the real HTTP, transaction, and anchor paths.
    private String cardBody() {
        return body(UUID.randomUUID().toString(), "b2", START, EXACT, null)
                .replace(
                        "\"note\":null",
                        "\"type\":\"CARD\",\"question\":\"How are transactions implemented?\",\"note\":null");
    }

    private ResultActions grade(String bearer, long id, String mutation, int grade, String sha) throws Exception {
        return mvc.perform(post("/api/annotations/" + id + "/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mutationId\":\"" + mutation + "\",\"grade\":" + grade
                        + ",\"reviewedAt\":\"2026-01-01T00:00:00Z\",\"blobSha\":\"" + sha + "\"}"));
    }

    @Test
    void cardsReuseAnchorsAndCreationIdempotencyAndGradesAreStoredOnce() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        String body = cardBody();
        long id = createdId(body);
        create(alice, note, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CARD"))
                .andExpect(jsonPath("$.question").value("How are transactions implemented?"));
        String mutation = UUID.randomUUID().toString();
        for (int i = 0; i < 10; i++) {
            grade(alice, id, i == 0 ? mutation : UUID.randomUUID().toString(), 4, OLD_SHA)
                    .andExpect(status().isOk());
        }
        grade(alice, id, mutation, 4, OLD_SHA).andExpect(status().isOk());
        grade(alice, id, mutation, 5, OLD_SHA).andExpect(status().isConflict());
        assertEquals(10, count("review_log"));
        mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notebook.sessionLimit").value(40))
                .andExpect(jsonPath("$.notebook.annotations[0].type").value("CARD"))
                .andExpect(jsonPath("$.notebook.reviews.length()").value(10));
        remove(alice, id, 1).andExpect(status().isNoContent());
        assertEquals(0, count("review_log"));
        grade(alice, id, mutation, 4, OLD_SHA).andExpect(status().isNotFound());
    }

    @Test
    void changedCardsCannotBeGradedUntilExplicitlyCheckedAndOrphansCanBeReattached() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        next();
        moveNoteTo(CURRENT_SHA);
        grade(alice, id, UUID.randomUUID().toString(), 4, OLD_SHA).andExpect(cardChanged());
        // Not placed in the current version until its note's highlights are listed: nothing to confirm there yet.
        check(alice, id, 1, CURRENT_SHA).andExpect(cardChanged());
        expectVersion(ExpectedCount.once(), CURRENT_SHA, CURRENT);
        list(alice)
                .andExpect(jsonPath("$.annotations[0].status").value("REANCHORED"))
                .andExpect(jsonPath("$.annotations[0].checkedBlobSha").isEmpty());
        grade(alice, id, UUID.randomUUID().toString(), 4, CURRENT_SHA).andExpect(cardChanged());
        check(alice, id, 1, CURRENT_SHA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedBlobSha").value(CURRENT_SHA));
        grade(alice, id, UUID.randomUUID().toString(), 4, CURRENT_SHA).andExpect(status().isOk());
        next();
        moveNoteTo(REWRITTEN_SHA);
        expectVersion(ExpectedCount.once(), REWRITTEN_SHA, REWRITTEN);
        list(alice).andExpect(jsonPath("$.annotations[0].status").value("ORPHANED"));
        check(alice, id, 2, REWRITTEN_SHA).andExpect(cardChanged());
        next();
        expectVersion(ExpectedCount.once(), REWRITTEN_SHA, REWRITTEN);
        reattach(alice, id, 2, REWRITTEN_SHA, "b2", 8, "weaves advice").andExpect(status().isOk());
        grade(alice, id, UUID.randomUUID().toString(), 4, REWRITTEN_SHA).andExpect(cardChanged());
        db.sql("update documents set deleted_at = now() where id = :id")
                .param("id", note)
                .update();
        check(alice, id, 3, REWRITTEN_SHA).andExpect(cardChanged());
    }

    private ResultActions check(String bearer, long id, int expectedVersion, String sha) throws Exception {
        return mvc.perform(post("/api/annotations/" + id + "/check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + ",\"blobSha\":\"" + sha + "\"}"));
    }

    private static ResultMatcher cardChanged() {
        return result -> {
            status().isConflict().match(result);
            jsonPath("$.code").value("CARD_CHANGED").match(result);
        };
    }

    @Test
    void cardAndReviewBoundariesRejectInvalidAndForeignRequestsWithoutGitHub() throws Exception {
        create(alice, note, cardBody().replace("How are transactions implemented?", ""))
                .andExpect(status().isBadRequest());
        create(alice, note, cardBody().replace("CARD", "UNKNOWN")).andExpect(status().isBadRequest());
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        grade(bob, id, UUID.randomUUID().toString(), 4, OLD_SHA).andExpect(status().isNotFound());
        grade(alice, id, UUID.randomUUID().toString(), 6, OLD_SHA).andExpect(status().isBadRequest());
        grade(alice, id, "bad", 4, OLD_SHA).andExpect(status().isBadRequest());
        mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(jsonPath("$.notebook.annotations.length()").value(0))
                .andExpect(jsonPath("$.notebook.reviews.length()").value(0));
        assertEquals(0, count("review_log"));
    }

    @Test
    void concurrentCardCreationsAndGradeReplaysAreIdempotentEvenAfterANoteEdit() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.between(1, 4));
        String body = cardBody();
        Callable<Integer> creating =
                () -> create(alice, note, body).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(creating, creating, creating, creating));
            int created = 0;
            for (var result : results) {
                int status = result.get();
                if (status == 201) created++;
                else assertEquals(200, status);
            }
            assertEquals(1, created);
        }
        long id = db.sql("select id from annotations").query(Long.class).single();
        String mutation = UUID.randomUUID().toString();
        Callable<Integer> grading = () ->
                grade(alice, id, mutation, 4, OLD_SHA).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : executor.invokeAll(List.of(grading, grading, grading, grading)))
                assertEquals(200, result.get());
        }
        assertEquals(1, count("review_log"));
        moveNoteTo(CURRENT_SHA);
        grade(alice, id, mutation, 4, OLD_SHA).andExpect(status().isOk());
        grade(alice, id, UUID.randomUUID().toString(), 4, OLD_SHA).andExpect(status().isConflict());
        assertEquals(1, count("review_log"));
    }

    /**
     * Sends {@code write} while a disconnect of the note's repository holds its lock, as
     * DataControlController.disconnect does, and returns the write's status after the disconnect commits. Fails unless
     * the write waited for it.
     */
    private int statusDuringDisconnect(Callable<ResultActions> write) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var disconnect = executor.submit(() -> transaction.execute(status -> {
                connections.lockForSync(1, 1);
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException("Interrupted while holding the test disconnect", interrupted);
                }
                return data.delete(1);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var writing = executor.submit(write);
            TestEnvironment.awaitLockWait(db);
            assertFalse(writing.isDone(), "the write must wait for the disconnect");
            release.countDown();
            disconnect.get(10, TimeUnit.SECONDS);
            return writing.get(10, TimeUnit.SECONDS).andReturn().getResponse().getStatus();
        }
    }

    @Test
    void aCreationRacingADisconnectWaitsAndIsNotFoundInsteadOfBreakingIt() throws Exception {
        expectSource(ExpectedCount.once());
        assertEquals(
                404,
                statusDuringDisconnect(
                        () -> create(alice, note, body(UUID.randomUUID().toString(), "b2", START, EXACT, null))));
        assertEquals(0, count("annotations"));
    }

    @Test
    void aGradeRacingADisconnectWaitsAndIsNotFoundInsteadOfBreakingIt() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        assertEquals(
                404,
                statusDuringDisconnect(() -> grade(alice, id, UUID.randomUUID().toString(), 4, OLD_SHA)));
        assertEquals(0, count("review_log"));
    }

    @Test
    void anEditRacingADisconnectWaitsAndIsNotFoundInsteadOfBreakingIt() throws Exception {
        expectSource(ExpectedCount.once());
        long highlight = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        assertEquals(404, statusDuringDisconnect(() -> edit(alice, highlight, "late", 1)));
        assertEquals(0, count("annotations"));
    }

    @Test
    void aCardConfirmationRacingADisconnectWaitsAndIsNotFoundInsteadOfBreakingIt() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long card = createdId(cardBody());
        assertEquals(404, statusDuringDisconnect(() -> check(alice, card, 1, OLD_SHA)));
    }

    @Test
    void aReattachmentRacingADisconnectWaitsAndIsNotFoundInsteadOfBreakingIt() throws Exception {
        expectSource(ExpectedCount.times(2));
        long highlight = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        assertEquals(404, statusDuringDisconnect(() -> reattach(alice, highlight, 1, OLD_SHA, "b2", 0, "By default")));
    }

    @Test
    void aGradeDoesNotWaitForAnotherWriteToTheSameRepository() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Holds the shared document lock, as a reading position or bookmark being saved does.
            var writing = executor.submit(() -> transaction.execute(status -> {
                connections.lockForDocumentWrite(1, note);
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException("Interrupted while holding the test write", interrupted);
                }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var grading =
                    executor.submit(() -> grade(alice, id, UUID.randomUUID().toString(), 4, OLD_SHA)
                            .andReturn()
                            .getResponse()
                            .getStatus());
            try {
                assertEquals(200, grading.get(5, TimeUnit.SECONDS), "the grade must not wait for another write");
            } finally {
                release.countDown();
            }
            writing.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, count("review_log"));
    }

    @Test
    void aResolutionReadBeforeTheUsersReattachmentDoesNotOverwriteIt() throws Exception {
        expectSource(ExpectedCount.times(2));
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        var readByListing = annotations.find(1, id).orElseThrow();
        // The user reattaches in the version they are reading, so the resolved version is unchanged.
        reattach(alice, id, 1, OLD_SHA, "b2", 0, "By default").andExpect(status().isOk());
        assertFalse(
                annotations.resolved(readByListing, CURRENT_SHA, Optional.empty()),
                "the listing's stale resolution must not apply");
        var kept = annotations.find(1, id).orElseThrow();
        assertEquals(Annotations.Status.REANCHORED, kept.status());
        assertEquals("By default", kept.location().passage().exactText());
    }

    @Test
    void confirmingACardWithAStaleVersionIsAnEditConflictNotAChangedCard() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        edit(alice, id, "an edit from another phone", 1).andExpect(status().isOk());
        check(alice, id, 1, OLD_SHA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOTATION_CONFLICT"));
    }

    @Test
    void skewedReviewTimesAreClampedAndTheirOriginalMutationsStillReplayExactly() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        for (Instant requested : List.of(
                Instant.EPOCH.minusMillis(1),
                Instant.parse("2999-01-01T00:00:00Z"),
                Instant.parse("+500000-01-01T00:00:00Z"))) {
            String mutation = UUID.randomUUID().toString();
            String body = "{\"mutationId\":\"" + mutation + "\",\"grade\":4,\"reviewedAt\":\"" + requested
                    + "\",\"blobSha\":\"" + OLD_SHA + "\"}";
            Instant before = Instant.now().minusMillis(1);
            String first = mvc.perform(post("/api/annotations/" + id + "/reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            Instant stored = db.sql(
                            "select reviewed_at from review_log where user_id=1 and mutation_id=cast(:id as uuid)")
                    .param("id", mutation)
                    .query((row, n) -> row.getTimestamp(1).toInstant())
                    .single();
            if (requested.isBefore(Instant.EPOCH)) assertEquals(Instant.EPOCH, stored);
            else assertTrue(!stored.isBefore(before) && !stored.isAfter(Instant.now()));
            mvc.perform(post("/api/annotations/" + id + "/reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(content().string(first));
            mvc.perform(post("/api/annotations/" + id + "/reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.replace(
                                    requested.toString(),
                                    requested.plusMillis(1).toString())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MUTATION_ID_REUSED"));
        }
        assertEquals(3, count("review_log"));
    }

    @Test
    void reviewsWrittenBeforeClientTimesWereSeparatedStillReplay() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(cardBody());
        String mutation = UUID.randomUUID().toString();
        String first = grade(alice, id, mutation, 4, OLD_SHA)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        db.sql("update review_log set client_reviewed_at = null").update();
        grade(alice, id, mutation, 4, OLD_SHA)
                .andExpect(status().isOk())
                .andExpect(content().string(first));
        assertEquals(1, count("review_log"));
    }

    @Test
    void reviewTimesAndMissingFieldsAreValidatedAndHighlightsCannotBeGraded() throws Exception {
        moveNoteTo(OLD_SHA);
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        grade(alice, id, UUID.randomUUID().toString(), 4, OLD_SHA).andExpect(status().isNotFound());
        for (String body : List.of(
                "{}",
                "{\"mutationId\":\"" + UUID.randomUUID() + "\",\"grade\":4,\"blobSha\":\"" + OLD_SHA
                        + "\",\"reviewedAt\":\"2026-01-01T00:00:00.000001Z\"}")) {
            mvc.perform(post("/api/annotations/" + id + "/reviews")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, count("review_log"));
    }
}
