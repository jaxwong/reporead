// Owns real PostgreSQL/HTTP reading-state validation, last-write-wins and optional-input/complete-output coverage.

package com.reporead.reading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.Hashes;
import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.document.Documents;
import com.reporead.document.MarkdownRenderer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns behavioral coverage of reading state at the authenticated HTTP boundary and its database owner. */
class ReadingStateTest extends SharedSpringTest {
    // Test-only users, documents, and SHAs; no GitHub call is involved in reading state.
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @Autowired
    MockMvc mvc;

    @Autowired
    AppSessions sessions;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    JdbcClient db;

    @Autowired
    Documents documents;

    @Autowired
    TransactionTemplate transaction;

    String alice;
    long note;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE");
        db.sql(
                        "insert into repository_connections (user_id, github_repository_id, owner, name, default_branch) values (1, 11, 'test-only', 'notes', 'main')")
                .update();
        note = db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha)
                values (1, 'backend/spring.md', 'spring', :sha) returning id""").param("sha", SHA_A).query(Long.class).single();
    }

    private static String body(String sha, int progress, Instant at, String anchor) {
        return "{\"lastReadBlobSha\":\"" + sha + "\",\"progressPercent\":" + progress + ",\"lastReadAt\":\"" + at
                + "\",\"anchor\":" + anchor + "}";
    }

    private static final String ANCHOR =
            "{\"headingPath\":[\"Spring\",\"Proxies\"],\"textPrefix\":\"Spring implements\",\"blockIndex\":43}";

    private ResultActions save(String bearer, long documentId, String json) throws Exception {
        return mvc.perform(put("/api/documents/" + documentId + "/reading-state")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /** The library: its readingStates are this user's reading states. */
    private ResultActions list(String bearer) throws Exception {
        return mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"textPrefix\":null"})
    void anOptionalInputPrefixIsAlwaysPresentInTheResponse(String prefix) throws Exception {
        save(alice, note, body(SHA_A, 40, Instant.now(), "{\"headingPath\":[],\"blockIndex\":0" + prefix + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchor", org.hamcrest.Matchers.hasKey("textPrefix")))
                .andExpect(jsonPath("$.anchor.textPrefix").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void savedStateRecordsTheVersionReadAndIsListedMostRecentFirst() throws Exception {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        save(alice, note, body(SHA_B, 40, at, ANCHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadBlobSha").value(SHA_B))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.anchor.headingPath[1]").value("Proxies"))
                .andExpect(jsonPath("$.anchor.blockIndex").value(43));
        save(alice, note, body(SHA_B, 40, at, ANCHOR)).andExpect(status().isOk());
        list(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingStates.length()").value(1))
                .andExpect(jsonPath("$.readingStates[0].progressPercent").value(40))
                .andExpect(jsonPath("$.readingStates[0].lastReadAt").value(at.toString()))
                .andExpect(jsonPath("$.readingStates[0].documentId").value(note));
    }

    @Test
    void olderOrEqualWritesDoNotReplaceNewerProgress() throws Exception {
        Instant newer = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        save(alice, note, body(SHA_A, 80, newer, ANCHOR)).andExpect(status().isOk());
        save(alice, note, body(SHA_A, 10, newer.minusSeconds(60), ANCHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(80));
        save(alice, note, body(SHA_A, 20, newer, ANCHOR))
                .andExpect(jsonPath("$.progressPercent").value(80));
        save(alice, note, body(SHA_A, 90, newer.plusSeconds(1), ANCHOR))
                .andExpect(jsonPath("$.progressPercent").value(90));
    }

    @Test
    void concurrentWritesKeepTheNewest() throws Exception {
        Instant base = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MILLIS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var runs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 10; i++) {
                int progress = i * 10;
                runs.add(executor.submit(
                        () -> save(alice, note, body(SHA_A, progress, base.plusSeconds(progress), ANCHOR))
                                .andExpect(status().isOk())));
            }
            for (var run : runs) run.get();
        }
        list(alice).andExpect(jsonPath("$.readingStates[0].progressPercent").value(90));
    }

    @Test
    void repositoryRefreshDoesNotChangeTheLastReadVersion() throws Exception {
        save(alice, note, body(SHA_A, 50, Instant.now(), ANCHOR)).andExpect(status().isOk());
        transaction.executeWithoutResult(status -> documents.publishSnapshot(
                1, List.of(new Documents.SourceFile("backend/spring.md", SHA_B)), Instant.now(), List.of(), true));
        list(alice).andExpect(jsonPath("$.readingStates[0].lastReadBlobSha").value(SHA_A));
    }

    @Test
    void deletedDocumentKeepsItsReadingHistory() throws Exception {
        save(alice, note, body(SHA_A, 50, Instant.now(), ANCHOR)).andExpect(status().isOk());
        transaction.executeWithoutResult(
                status -> documents.publishSnapshot(1, List.of(), Instant.now(), List.of(), true));
        list(alice)
                .andExpect(jsonPath("$.readingStates[0].progressPercent").value(50))
                .andExpect(jsonPath("$.repositories[0].documents[0].deleted").value(true));
    }

    @Test
    void invalidStatesAreRejected() throws Exception {
        Instant now = Instant.now();
        for (String json : List.of(
                body("not-a-sha", 1, now, ANCHOR),
                body(SHA_A, 101, now, ANCHOR),
                body(SHA_A, -1, now, ANCHOR),
                body(SHA_A, 1, now, "null"),
                body(SHA_A, 1, now, "{\"headingPath\":[],\"blockIndex\":null}"),
                body(SHA_A, 1, now, "{\"headingPath\":null,\"blockIndex\":0}"),
                body(SHA_A, 1, now, "{\"headingPath\":[null],\"blockIndex\":0}"),
                "{\"lastReadBlobSha\":\"" + SHA_A + "\",\"progressPercent\":1,\"anchor\":" + ANCHOR + "}")) {
            save(alice, note, json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertEquals(
                0,
                db.sql("select count(*) from reading_states")
                        .query(Integer.class)
                        .single());
    }

    @Test
    void aClockTenMinutesAheadIsStoredAtServerNow() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        save(alice, note, body(SHA_A, 50, before.plusSeconds(600), ANCHOR)).andExpect(status().isOk());
        Instant stored = db.sql("select last_read_at from reading_states where document_id = :id")
                .param("id", note)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        assertTrue(!stored.isBefore(before) && !stored.isAfter(Instant.now().plusMillis(1)), stored.toString());
        list(alice).andExpect(jsonPath("$.readingStates[0].progressPercent").value(50));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b", "a\\ud83cb", "a\\udf4eb"})
    void unstorableAnchorTextIsAMalformedRequest(String text) throws Exception {
        for (String anchor : List.of(
                "{\"headingPath\":[\"" + text + "\"],\"textPrefix\":null,\"blockIndex\":0}",
                "{\"headingPath\":[],\"textPrefix\":\"" + text + "\",\"blockIndex\":0}")) {
            save(alice, note, body(SHA_A, 50, Instant.now(), anchor))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertEquals(
                0,
                db.sql("select count(*) from reading_states")
                        .query(Integer.class)
                        .single());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1969-12-31T23:59:59Z", "-300000-01-01T00:00:00Z"})
    void aClockBefore1970IsStoredAtTheEpoch(String at) throws Exception {
        save(alice, note, body(SHA_A, 50, Instant.parse(at), ANCHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadAt").value(Instant.EPOCH.toString()));
        list(alice).andExpect(jsonPath("$.readingStates[0].lastReadAt").value(Instant.EPOCH.toString()));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4096, Integer.MAX_VALUE})
    void anAnchorOverTheOldLimitsIsStoredAsSent(int blockIndex) throws Exception {
        String heading = "😀" + "h".repeat(501);
        String prefix = "😀" + "p".repeat(201);
        String anchor = "{\"headingPath\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"" + heading + "\"],\"textPrefix\":\""
                + prefix + "\",\"blockIndex\":" + blockIndex + "}";
        save(alice, note, body(SHA_A, 50, Instant.now().minusSeconds(1), anchor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchor.headingPath.length()").value(7))
                .andExpect(jsonPath("$.anchor.headingPath[6]").value(heading))
                .andExpect(jsonPath("$.anchor.textPrefix").value(prefix))
                .andExpect(jsonPath("$.anchor.blockIndex").value(blockIndex));
        list(alice)
                .andExpect(jsonPath("$.readingStates[0].anchor.headingPath[6]").value(heading))
                .andExpect(jsonPath("$.readingStates[0].anchor.textPrefix").value(prefix))
                .andExpect(jsonPath("$.readingStates[0].anchor.blockIndex").value(blockIndex));
    }

    @Test
    void everyHeadingPathTheRenderedPageCarriesIsAValidReadingState() throws Exception {
        // A paragraph directly followed by "---" is a setext heading of any length; the reader sends the page's path
        // back.
        String markdown = "word ".repeat(150).strip() + "\n---\n\nThe section's first paragraph.\n";
        var rendered = MarkdownRenderer.render(
                markdown, Hashes.gitBlobSha(markdown.getBytes(StandardCharsets.UTF_8)), "notes/long.md");
        String heading = rendered.page().selectFirst("[data-block-id=b1]").attr("data-heading-2");
        String anchor = "{\"headingPath\":[\"" + heading + "\"],\"textPrefix\":\"The section\",\"blockIndex\":1}";
        save(alice, note, body(SHA_A, 10, Instant.now(), anchor)).andExpect(status().isOk());
    }

    @Test
    void readingStateIsPrivateToItsUser() throws Exception {
        save(alice, note, body(SHA_A, 50, Instant.now(), ANCHOR)).andExpect(status().isOk());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        save(bob, note, body(SHA_A, 10, Instant.now(), ANCHOR)).andExpect(status().isNotFound());
        list(bob).andExpect(jsonPath("$.readingStates").isEmpty());
        save(alice, 999, body(SHA_A, 10, Instant.now(), ANCHOR)).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + note + "/reading-state")).andExpect(status().isUnauthorized());
    }
}
