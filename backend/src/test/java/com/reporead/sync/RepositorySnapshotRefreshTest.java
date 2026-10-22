package com.reporead.sync;

import static com.reporead.sync.RepositorySnapshotRefresh.CONCURRENT_BLOB_READS;
import static com.reporead.sync.RepositorySnapshotRefresh.DOCUMENTS_SYNCED;
import static com.reporead.sync.RepositorySnapshotRefresh.MAX_CONTENT_MOVE_CANDIDATES;
import static com.reporead.sync.RepositorySnapshotRefresh.SNAPSHOT_REFRESHES;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.github.GitHubHttpConfiguration;
import jakarta.servlet.ServletException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

@ExtendWith(OutputCaptureExtension.class)
class RepositorySnapshotRefreshTest extends SharedSpringTest {
    // Test-only repository, SHAs, and tokens; no live GitHub call is made or claimed.
    private static final String TOKEN = "TEST_ONLY_ALICE_USER_TOKEN";
    private static final String BRANCH = "https://api.github.com/repos/test-only/notes/branches/main";
    private static final String COMMIT_A = "a".repeat(40);
    private static final String COMMIT_B = "b".repeat(40);
    private static final String TREE_A = "1".repeat(40);
    private static final String TREE_B = "2".repeat(40);
    private static final String BLOB_1 = "3".repeat(40);
    private static final String BLOB_2 = "4".repeat(40);

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("githubUserHttpClient")
    RestTemplate githubUserHttpClient;

    @Autowired
    @Qualifier("githubTreeHttpClient")
    RestTemplate githubTreeHttpClient;

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
    com.reporead.document.Documents documentRows;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;

    MockRestServiceServer user;
    MockRestServiceServer trees;
    String alice;
    long connection;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        user = MockRestServiceServer.bindTo(githubUserHttpClient)
                .ignoreExpectOrder(true)
                .build();
        trees = MockRestServiceServer.bindTo(githubTreeHttpClient)
                .ignoreExpectOrder(true)
                .build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, TOKEN);
        connection = connect(1, "main");
    }

    @AfterEach
    void verifyNoExtraCalls() {
        user.verify();
        trees.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private long connect(long userId, String branch) {
        return db.sql("""
                INSERT INTO repository_connections (user_id, github_repository_id, owner, name, default_branch)
                VALUES (:userId, 11, 'test-only', 'notes', :branch) RETURNING id""")
                .param("userId", userId)
                .param("branch", branch)
                .query(Long.class)
                .single();
    }

    private static String branch(String commit, String tree) {
        return "{\"name\":\"main\",\"commit\":{\"sha\":\"" + commit + "\",\"commit\":{\"tree\":{\"sha\":\"" + tree
                + "\"}}}}";
    }

    private static String entry(String path, String mode, String type, String sha) {
        return "{\"path\":\"" + path + "\",\"mode\":\"" + mode + "\",\"type\":\"" + type + "\",\"sha\":\"" + sha
                + "\"}";
    }

    private static String tree(boolean truncated, String... entries) {
        return "{\"sha\":\"" + TREE_A + "\",\"truncated\":" + truncated + ",\"tree\":[" + String.join(",", entries)
                + "]}";
    }

    private void expectSync(String commit, String treeSha, ResponseCreator treeResponse) {
        user.expect(requestTo(BRANCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(branch(commit, treeSha), MediaType.APPLICATION_JSON));
        trees.expect(requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + treeSha + "?recursive=1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(treeResponse);
    }

    /** Ends one request phase: every expected call happened, and later expectations start fresh. */
    private void next() {
        user.verify();
        trees.verify();
        user.reset();
        trees.reset();
    }

    private ResultActions sync(String bearer, long id) throws Exception {
        return mvc.perform(
                post("/api/repositories/" + id + "/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    /** The library, whose one repository must be {@link #connection}: its checkpoint and documents. */
    private ResultActions listed(String bearer) throws Exception {
        return mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories.length()").value(1))
                .andExpect(jsonPath("$.repositories[0].id").value(connection));
    }

    private List<String> activePaths() {
        return db.sql("SELECT path FROM documents WHERE deleted_at IS NULL ORDER BY path")
                .query(String.class)
                .list();
    }

    private String checkpoint() {
        return db.sql("SELECT last_synced_commit_sha FROM repository_connections WHERE id = :id")
                .param("id", connection)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void syncPublishesOnlyRegularMarkdownFilesAndTheSecondRunIsIdempotent() throws Exception {
        String body = tree(
                false,
                entry("intro.md", "100644", "blob", BLOB_1),
                entry("algorithms", "040000", "tree", TREE_B),
                entry("algorithms/Graphs.MD", "100755", "blob", BLOB_2),
                entry("image.png", "100644", "blob", BLOB_1),
                entry("link.md", "120000", "blob", BLOB_1),
                entry("vendor.md", "160000", "commit", COMMIT_B));
        expectSync(COMMIT_A, TREE_A, withSuccess(body, MediaType.APPLICATION_JSON));
        expectSync(COMMIT_A, TREE_A, withSuccess(body, MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value(COMMIT_A))
                .andExpect(jsonPath("$.documentCount").value(2));
        var ids =
                db.sql("SELECT id FROM documents ORDER BY id").query(Long.class).list();
        var rowVersions = rowVersions();
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(
                ids,
                db.sql("SELECT id FROM documents ORDER BY id").query(Long.class).list());
        assertEquals(rowVersions, rowVersions(), "a refresh that finds nothing changed rewrites no document row");
        assertEquals(List.of("algorithms/Graphs.MD", "intro.md"), activePaths());
        listed(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].lastSyncedCommitSha").value(COMMIT_A))
                .andExpect(jsonPath("$.repositories[0].documents[0].path").value("algorithms/Graphs.MD"))
                .andExpect(jsonPath("$.repositories[0].documents[0].title").value("Graphs"))
                .andExpect(jsonPath("$.repositories[0].documents[1].blobSha").value(BLOB_1));
    }

    @Test
    void aSnapshotRecordsItsImageFilesForEmbedsAndReplacesThemNextTime() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry("intro.md", "100644", "blob", BLOB_1),
                                entry("attachments/Pasted image.png", "100644", "blob", BLOB_2),
                                entry("diagram.SVG", "100755", "blob", BLOB_2),
                                entry("link.png", "120000", "blob", BLOB_2),
                                entry("notes.txt", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(
                List.of("attachments/Pasted image.png", "diagram.SVG"),
                db.sql("SELECT path FROM repository_images ORDER BY path")
                        .query(String.class)
                        .list());
        next();
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("intro.md", "100644", "blob", BLOB_1),
                                entry("img/new.webp", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(
                List.of("img/new.webp"),
                db.sql("SELECT path FROM repository_images").query(String.class).list());
    }

    @Test
    void truncatedTreeChangesNothingAndIsNotTreatedAsDeletion() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(tree(false, entry("intro.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        double succeeded = meters.counter(SNAPSHOT_REFRESHES, "outcome", "success", "code", "NONE")
                .count();
        double incomplete = meters.counter(SNAPSHOT_REFRESHES, "outcome", "failure", "code", "GITHUB_TREE_INCOMPLETE")
                .count();
        double documents = meters.counter(DOCUMENTS_SYNCED).count();
        sync(alice, connection).andExpect(status().isOk());
        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(true), MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_TREE_INCOMPLETE"));
        assertEquals(List.of("intro.md"), activePaths());
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(
                succeeded + 1,
                meters.counter(SNAPSHOT_REFRESHES, "outcome", "success", "code", "NONE")
                        .count());
        assertEquals(
                incomplete + 1,
                meters.counter(SNAPSHOT_REFRESHES, "outcome", "failure", "code", "GITHUB_TREE_INCOMPLETE")
                        .count());
        assertEquals(documents + 1, meters.counter(DOCUMENTS_SYNCED).count());
    }

    @Test
    void pathMissingFromACompleteTreeIsMarkedDeletedNotRemovedAndCanReturn() throws Exception {
        String both = tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2));
        expectSync(COMMIT_A, TREE_A, withSuccess(both, MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long b = db.sql("SELECT id FROM documents WHERE path = 'b.md'")
                .query(Long.class)
                .single();

        next();
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(1));
        assertEquals(List.of("a.md"), activePaths());
        assertEquals(
                2, db.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        // Still listed, so the app can name a note whose reading state, bookmark or highlights are kept.
        listed(alice)
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(2))
                .andExpect(jsonPath("$.repositories[0].documents[?(@.path == 'a.md')].deleted")
                        .value(contains(false)))
                .andExpect(jsonPath("$.repositories[0].documents[?(@.path == 'b.md')].deleted")
                        .value(contains(true)));
        assertEquals(
                BLOB_2,
                db.sql("SELECT current_blob_sha FROM documents WHERE path = 'a.md'")
                        .query(String.class)
                        .single());

        next();
        expectSync(COMMIT_A, TREE_A, withSuccess(both, MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(
                b,
                db.sql("SELECT id FROM documents WHERE path = 'b.md' AND deleted_at IS NULL")
                        .query(Long.class)
                        .single());
        listed(alice)
                .andExpect(jsonPath("$.repositories[0].documents[?(@.path == 'b.md')].deleted")
                        .value(contains(false)));
    }

    private long documentId(String path) {
        return db.sql("SELECT id FROM documents WHERE path = :path")
                .param("path", path)
                .query(Long.class)
                .single();
    }

    private Instant changedAt(String path) {
        var at = db.sql("SELECT content_changed_at FROM documents WHERE path = :path")
                .param("path", path)
                .query((row, n) -> java.util.Optional.ofNullable(row.getTimestamp(1)))
                .single();
        return at.map(java.sql.Timestamp::toInstant).orElse(null);
    }

    /** PostgreSQL's row version (xmin): it changes whenever a row is rewritten, even with identical values. */
    private String rowVersion(String path) {
        return db.sql("SELECT xmin::text FROM documents WHERE path = :path")
                .param("path", path)
                .query(String.class)
                .single();
    }

    private List<String> rowVersions() {
        return db.sql("SELECT xmin::text FROM documents ORDER BY id")
                .query(String.class)
                .list();
    }

    /** The time a refresh published its snapshot at, from its own answer. */
    private static Instant syncedAt(ResultActions refresh) throws Exception {
        String body =
                refresh.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Instant.parse(JsonPath.read(body, "$.syncedAt"));
    }

    /** A row records its refresh's time as PostgreSQL keeps it: to the microsecond, which the driver rounds to. */
    private static void assertRecordedAt(Instant syncedAt, Instant recorded) {
        assertTrue(
                Duration.between(syncedAt, recorded).abs().compareTo(Duration.ofNanos(1_000)) < 0,
                "recorded " + recorded + " for a refresh at " + syncedAt);
    }

    @Test
    void aRefreshRecordsWhenItFoundANoteNewChangedOrBackButNotTheFirstSnapshotOrAMove() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry("same.md", "100644", "blob", BLOB_1),
                                entry("edited.md", "100644", "blob", BLOB_1),
                                entry("gone.md", "100644", "blob", "5".repeat(40)),
                                entry("old/moved.md", "100644", "blob", "6".repeat(40))),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        for (var path : List.of("same.md", "edited.md", "gone.md", "old/moved.md")) assertNull(changedAt(path), path);
        String sameVersion = rowVersion("same.md");

        next();
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("same.md", "100644", "blob", BLOB_1),
                                entry("edited.md", "100644", "blob", BLOB_2),
                                entry("new.md", "100644", "blob", "7".repeat(40)),
                                entry("new/moved.md", "100644", "blob", "6".repeat(40))),
                        MediaType.APPLICATION_JSON));
        var second = syncedAt(sync(alice, connection));
        var recorded = changedAt("edited.md");
        assertRecordedAt(second, recorded);
        assertNull(changedAt("same.md"));
        assertEquals(
                sameVersion,
                rowVersion("same.md"),
                "a new commit that leaves a note unchanged does not rewrite its row");
        assertEquals(recorded, changedAt("new.md"));
        assertNull(changedAt("new/moved.md"), "a path-only move is not a content change");
        listed(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].documents[?(@.path == 'edited.md')].contentChangedAt")
                        .value(recorded.toString()))
                .andExpect(jsonPath("$.repositories[0].documents[?(@.path == 'same.md')].contentChangedAt")
                        .value(contains((Object) null)));

        next();
        // The same snapshot again changes nothing; a note that returns after being deleted was found again.
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("same.md", "100644", "blob", BLOB_1),
                                entry("edited.md", "100644", "blob", BLOB_2),
                                entry("new.md", "100644", "blob", "7".repeat(40)),
                                entry("new/moved.md", "100644", "blob", "6".repeat(40)),
                                entry("gone.md", "100644", "blob", "5".repeat(40))),
                        MediaType.APPLICATION_JSON));
        var third = syncedAt(sync(alice, connection));
        assertEquals(recorded, changedAt("edited.md"));
        assertRecordedAt(third, changedAt("gone.md"));
    }

    @Test
    void pathOnlyMoveWithTheSameBlobKeepsTheDocumentAndItsReadingState(CapturedOutput log) throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry("backend/spring.md", "100644", "blob", BLOB_1),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long spring = documentId("backend/spring.md");
        db.sql("""
                INSERT INTO reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                VALUES (1, :id, :sha, 40, '{"headingPath":[],"textPrefix":null,"blockIndex":3}', now())""").param("id", spring).param("sha", BLOB_1).update();

        String moved = tree(
                false,
                entry("java/spring/transactions.md", "100644", "blob", BLOB_1),
                entry("other.md", "100644", "blob", BLOB_2));
        for (int run = 0; run < 2; run++) {
            next();
            expectSync(COMMIT_B, TREE_B, withSuccess(moved, MediaType.APPLICATION_JSON));
            sync(alice, connection).andExpect(status().isOk());
            assertEquals(spring, documentId("java/spring/transactions.md"));
            assertEquals(List.of("java/spring/transactions.md", "other.md"), activePaths());
            assertEquals(
                    2,
                    db.sql("SELECT count(*) FROM documents")
                            .query(Integer.class)
                            .single());
        }
        assertEquals(
                "transactions",
                db.sql("SELECT title FROM documents WHERE id = :id")
                        .param("id", spring)
                        .query(String.class)
                        .single());
        assertEquals(
                spring,
                db.sql("SELECT document_id FROM reading_states")
                        .query(Long.class)
                        .single());
        var moveLogs = log.getOut()
                .lines()
                .filter(line -> line.contains("Document moved;"))
                .toList();
        assertEquals(1, moveLogs.size(), "only the first sync moves the document");
        assertTrue(moveLogs.getFirst().contains("connectionId=" + connection + " documentId=" + spring));
        assertFalse(moveLogs.getFirst().contains("backend/spring.md"));
        assertFalse(moveLogs.getFirst().contains("java/spring/transactions.md"));
    }

    @Test
    void identicalContentDuplicatesAreNeverMergedByAMove() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry("a.md", "100644", "blob", BLOB_1),
                                entry("b.md", "100644", "blob", BLOB_1),
                                entry("x.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        var before = List.of(documentId("a.md"), documentId("b.md"), documentId("x.md"));

        next();
        // Two vanished documents share one SHA, and one vanished SHA reappears at two paths: neither is a unique move.
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("c.md", "100644", "blob", BLOB_1),
                                entry("d.md", "100644", "blob", BLOB_1),
                                entry("y.md", "100644", "blob", BLOB_2),
                                entry("z.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of("c.md", "d.md", "y.md", "z.md"), activePaths());
        assertEquals(
                before,
                db.sql("SELECT id FROM documents WHERE deleted_at IS NOT NULL ORDER BY path")
                        .query(Long.class)
                        .list());
        assertEquals(
                7, db.sql("SELECT count(*) FROM documents").query(Integer.class).single());
    }

    @Test
    void contentAnotherNoteStillHasIsNoEvidenceOfAMove() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry("kept.md", "100644", "blob", BLOB_1),
                                entry("old.md", "100644", "blob", BLOB_1)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long old = documentId("old.md");

        next();
        // Untouched copies of one template: deleting one and creating another is not a move of the deleted one.
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("kept.md", "100644", "blob", BLOB_1),
                                entry("new.md", "100644", "blob", BLOB_1)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of("kept.md", "new.md"), activePaths());
        assertNotEquals(old, documentId("new.md"));
        assertEquals(
                old,
                db.sql("SELECT id FROM documents WHERE deleted_at IS NOT NULL")
                        .query(Long.class)
                        .single());
    }

    @Test
    void aPathThatOnceHadADocumentResumesItInsteadOfReceivingAMove() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long a = documentId("a.md");
        long b = documentId("b.md");
        next();
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());

        next();
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(tree(false, entry("b.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(
                List.of(b),
                db.sql("SELECT id FROM documents WHERE deleted_at IS NULL")
                        .query(Long.class)
                        .list());
        assertEquals(
                List.of(a),
                db.sql("SELECT id FROM documents WHERE deleted_at IS NOT NULL")
                        .query(Long.class)
                        .list());
    }

    /**
     * Content moves are chosen before the lock, so the snapshot published under it re-checks them: one applies only if
     * its document is still active at the path it left and its target is still a path that never had a document, with
     * neither side taken by an exact move.
     */
    @Test
    void aContentMoveChosenBeforeTheLockAppliesOnlyIfBothSidesAreStillUnmatched() {
        var first = Instant.parse("2026-10-01T00:00:00Z");
        transaction.executeWithoutResult(status -> documentRows.publishSnapshot(
                connection,
                List.of(
                        source("a.md", '5'),
                        source("b.md", '6'),
                        source("c.md", '7'),
                        source("d.md", '8'),
                        source("g.md", '9'),
                        source("h.md", 'c')),
                first,
                List.of(),
                false));
        long a = documentId("a.md"),
                c = documentId("c.md"),
                d = documentId("d.md"),
                g = documentId("g.md"),
                h = documentId("h.md");
        var contentMoves = List.of(
                new com.reporead.document.Documents.Move(a, "a.md", "x.md"),
                new com.reporead.document.Documents.Move(c, "c.md", "b.md"), // b.md already has a document
                new com.reporead.document.Documents.Move(d, "d.md", "f.md"), // d.md moved exactly to e.md
                new com.reporead.document.Documents.Move(g, "elsewhere.md", "y.md"), // g is not at the path it left
                new com.reporead.document.Documents.Move(h, "h.md", "e.md")); // e.md is d's exact move
        var applied = transaction.execute(status -> documentRows.publishSnapshot(
                connection,
                List.of(
                        source("b.md", '6'),
                        source("x.md", 'd'),
                        source("e.md", '8'),
                        source("f.md", 'e'),
                        source("y.md", 'f')),
                first.plusSeconds(60),
                contentMoves,
                true));
        assertEquals(
                List.of(new com.reporead.document.Documents.Move(d, "d.md", "e.md"), contentMoves.getFirst()), applied);
        assertEquals(a, documentId("x.md"));
        assertEquals(d, documentId("e.md"));
        assertEquals(List.of("b.md", "e.md", "f.md", "x.md", "y.md"), activePaths());
        assertEquals(
                List.of(c, g, h),
                db.sql("SELECT id FROM documents WHERE deleted_at IS NOT NULL ORDER BY id")
                        .query(Long.class)
                        .list());
    }

    private static com.reporead.document.Documents.SourceFile source(String path, char blob) {
        return new com.reporead.document.Documents.SourceFile(
                path, String.valueOf(blob).repeat(40));
    }

    // ---- Moves with edits (content moves) ----

    /** Test-only note text: ten numbered sentences, enough five-word shingles to be compared by content. */
    private static byte[] note(String topic, String verb) {
        var text = new StringBuilder("# " + topic + "\n\n");
        for (int i = 1; i <= 10; i++)
            text.append("In step ")
                    .append(i)
                    .append(" the ")
                    .append(topic)
                    .append(" worker ")
                    .append(verb)
                    .append(" a batch and records its checkpoint before continuing.\n\n");
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] edited(byte[] original) {
        return new String(original, java.nio.charset.StandardCharsets.UTF_8)
                .replace("In step 4 the", "In step 4, after a pause, the")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) {
        return com.reporead.Hashes.gitBlobSha(bytes);
    }

    private void expectBlob(byte[] bytes) {
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(bytes)))
                .andRespond(withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void readSomething(long documentId) {
        db.sql("""
                INSERT INTO reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                VALUES (1, :id, :sha, 10, '{"headingPath":[],"textPrefix":null,"blockIndex":0}', now())""").param("id", documentId).param("sha", BLOB_1).update();
    }

    private long syncedNote(String path, byte[] bytes) throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(
                                false,
                                entry(path, "100644", "blob", sha(bytes)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        next();
        return documentId(path);
    }

    @Test
    void aNoteMovedAndEditedKeepsItsIdentityWhenItCarriesUserData() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest-worker.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(after);
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(id, documentId("pipelines/ingest-worker.md"));
        assertEquals(
                sha(after),
                db.sql("SELECT current_blob_sha FROM documents WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single());
        assertEquals(List.of("other.md", "pipelines/ingest-worker.md"), activePaths());
        assertEquals(
                2, db.sql("SELECT count(*) FROM documents").query(Integer.class).single());
    }

    @Test
    void aBookmarkAloneIsUserDataThatKeepsAMovedNotesIdentity() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        mvc.perform(put("/api/documents/" + id + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest-worker.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(after);
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(id, documentId("pipelines/ingest-worker.md"));
    }

    @Test
    void anUnrelatedNewNoteIsNotMergedWithADeletedOne() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] unrelated = note("billing", "invoices");
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("billing.md", "100644", "blob", sha(unrelated)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(unrelated);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("billing.md"));
        assertNotNull(db.sql("SELECT deleted_at FROM documents WHERE id = :id")
                .param("id", id)
                .query(java.sql.Timestamp.class)
                .single());
    }

    @Test
    void twoEquallyLikelyNewNotesAreAmbiguousAndNeitherIsAMove() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] copyA = edited(before);
        byte[] copyB = new String(before, java.nio.charset.StandardCharsets.UTF_8)
                .replace("In step 7 the", "In step 7 only the")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("a/ingest.md", "100644", "blob", sha(copyA)),
                                entry("b/ingest.md", "100644", "blob", sha(copyB)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(copyA);
        expectBlob(copyB);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("a/ingest.md"));
        assertNotEquals(id, documentId("b/ingest.md"));
    }

    @Test
    void aNoteWithoutUserDataIsNotComparedAndCostsNoBlobReads() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("pipelines/ingest.md"));
    }

    @Test
    void tooManyCandidatesAreNotComparedAndCostNoBlobReads() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < MAX_CONTENT_MOVE_CANDIDATES; i++)
            entries.add(entry("new-" + i + ".md", "100644", "blob", BLOB_1));
        entries.add(entry("other.md", "100644", "blob", BLOB_2));
        expectSync(
                COMMIT_B, TREE_B, withSuccess(tree(false, entries.toArray(String[]::new)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertNotNull(db.sql("SELECT deleted_at FROM documents WHERE id = :id")
                .param("id", id)
                .query(java.sql.Timestamp.class)
                .single());
    }

    @Test
    void candidatesAreReadConcurrently() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        var newNotes = new java.util.ArrayList<byte[]>(List.of(after));
        for (int i = 1; i < CONCURRENT_BLOB_READS - 1; i++) newNotes.add(note("topic" + i, "writes"));
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < newNotes.size(); i++)
            entries.add(entry("new-" + i + ".md", "100644", "blob", sha(newNotes.get(i))));
        entries.add(entry("other.md", "100644", "blob", BLOB_2));
        expectSync(
                COMMIT_B, TREE_B, withSuccess(tree(false, entries.toArray(String[]::new)), MediaType.APPLICATION_JSON));
        // Every read waits until all of them have started: read one at a time, the first would wait in vain.
        var started = new CountDownLatch(CONCURRENT_BLOB_READS);
        var blobs = new java.util.ArrayList<>(newNotes);
        blobs.add(before);
        for (byte[] blob : blobs) {
            user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(blob)))
                    .andRespond(request -> {
                        started.countDown();
                        try {
                            if (!started.await(10, TimeUnit.SECONDS))
                                throw new AssertionError(
                                        "Blob reads did not overlap; still waiting for " + started.getCount());
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError("Interrupted waiting for overlapping reads", interrupted);
                        }
                        return withSuccess(blob, MediaType.APPLICATION_OCTET_STREAM)
                                .createResponse(request);
                    });
        }
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(id, documentId("new-0.md"));
    }

    @Test
    void concurrentSyncsShareOneContentReadCeiling() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        var newNotes = new java.util.ArrayList<byte[]>(List.of(after));
        for (int i = 1; i < CONCURRENT_BLOB_READS - 1; i++) newNotes.add(note("topic" + i, "writes"));
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < newNotes.size(); i++)
            entries.add(entry("new-" + i + ".md", "100644", "blob", sha(newNotes.get(i))));
        entries.add(entry("other.md", "100644", "blob", BLOB_2));
        String body = tree(false, entries.toArray(String[]::new));
        user.expect(ExpectedCount.times(2), requestTo(BRANCH))
                .andRespond(withSuccess(branch(COMMIT_B, TREE_B), MediaType.APPLICATION_JSON));
        var treesRead = new CountDownLatch(2);
        trees.expect(
                        ExpectedCount.times(2),
                        requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_B + "?recursive=1"))
                .andRespond(request -> {
                    treesRead.countDown();
                    return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
                });
        var firstReadsStarted = new CountDownLatch(CONCURRENT_BLOB_READS);
        var contentReadCeilingExceeded = new CountDownLatch(1);
        var releaseFirstReads = new CountDownLatch(1);
        var readsStarted = new AtomicInteger();
        var blobs = new java.util.ArrayList<>(newNotes);
        blobs.add(before);
        for (byte[] blob : blobs) {
            user.expect(
                            ExpectedCount.times(2),
                            requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(blob)))
                    .andRespond(request -> {
                        int read = readsStarted.incrementAndGet();
                        if (read > CONCURRENT_BLOB_READS) contentReadCeilingExceeded.countDown();
                        if (read <= CONCURRENT_BLOB_READS) {
                            firstReadsStarted.countDown();
                            try {
                                if (!releaseFirstReads.await(10, TimeUnit.SECONDS))
                                    throw new AssertionError("Timed out holding the first sync's blob reads");
                            } catch (InterruptedException interrupted) {
                                throw new AssertionError(
                                        "Interrupted while holding the first sync's blob reads", interrupted);
                            }
                        }
                        return withSuccess(blob, MediaType.APPLICATION_OCTET_STREAM)
                                .createResponse(request);
                    });
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> sync(alice, connection).andExpect(status().isOk()));
            var second = executor.submit(() -> sync(alice, connection).andExpect(status().isOk()));
            try {
                assertTrue(treesRead.await(10, TimeUnit.SECONDS), "both syncs did not read their trees");
                assertTrue(firstReadsStarted.await(10, TimeUnit.SECONDS), "the first read set did not start");
                assertFalse(
                        contentReadCeilingExceeded.await(1, TimeUnit.SECONDS),
                        "a second sync exceeded the process content-read ceiling; reads started=" + readsStarted.get());
            } finally {
                releaseFirstReads.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(id, documentId("new-0.md"));
    }

    @Test
    void aFailedReadDoesNotStartTheNextReadSet() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        var newNotes = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < CONCURRENT_BLOB_READS; i++) newNotes.add(note("topic" + i, "writes"));
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < newNotes.size(); i++)
            entries.add(entry("new-" + i + ".md", "100644", "blob", sha(newNotes.get(i))));
        entries.add(entry("other.md", "100644", "blob", BLOB_2));
        expectSync(
                COMMIT_B, TREE_B, withSuccess(tree(false, entries.toArray(String[]::new)), MediaType.APPLICATION_JSON));
        var releaseFailure = new CountDownLatch(1);
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before)))
                .andRespond(request -> {
                    try {
                        if (!releaseFailure.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Timed out holding the failed blob read");
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError("Interrupted while holding the failed blob read", interrupted);
                    }
                    return withServiceUnavailable().createResponse(request);
                });
        var firstSetSucceeded = new CountDownLatch(CONCURRENT_BLOB_READS - 1);
        for (int i = 0; i < CONCURRENT_BLOB_READS - 1; i++) {
            byte[] blob = newNotes.get(i);
            user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(blob)))
                    .andRespond(request -> {
                        firstSetSucceeded.countDown();
                        return withSuccess(blob, MediaType.APPLICATION_OCTET_STREAM)
                                .createResponse(request);
                    });
        }
        var nextSetStarted = new CountDownLatch(1);
        byte[] next = newNotes.getLast();
        user.expect(
                        ExpectedCount.between(0, 1),
                        requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(next)))
                .andRespond(request -> {
                    nextSetStarted.countDown();
                    return withSuccess(next, MediaType.APPLICATION_OCTET_STREAM).createResponse(request);
                });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = executor.submit(() -> sync(alice, connection).andExpect(status().isServiceUnavailable()));
            assertTrue(firstSetSucceeded.await(10, TimeUnit.SECONDS), "the first read set did not run");
            boolean startedBeforeFailure = nextSetStarted.await(500, TimeUnit.MILLISECONDS);
            releaseFailure.countDown();
            run.get(10, TimeUnit.SECONDS);
            assertFalse(startedBeforeFailure, "a later read started before the first failure ended the operation");
        }
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(List.of("backend/ingest.md", "other.md"), activePaths());
    }

    @Test
    void aFatalReaderErrorIsNotConvertedToARequestFailure() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before)))
                .andRespond(request -> {
                    throw new AssertionError("test-only fatal reader failure");
                });
        user.expect(
                        ExpectedCount.between(0, 1),
                        requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(after)))
                .andRespond(withSuccess(after, MediaType.APPLICATION_OCTET_STREAM));
        var requestFailure = assertThrows(ServletException.class, () -> sync(alice, connection));
        var fatal = assertInstanceOf(AssertionError.class, requestFailure.getCause());
        assertEquals("test-only fatal reader failure", fatal.getMessage());
        assertEquals(COMMIT_A, checkpoint());
    }

    @Test
    void anOldVersionGitHubNoLongerHasOnlyExcludesThatCandidate(CapturedOutput log) throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before)))
                .andRespond(withResourceNotFound());
        expectBlob(after);
        String requestId = sync(alice, connection)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("X-Request-Id");
        assertNotEquals(id, documentId("pipelines/ingest.md"));
        assertEquals(COMMIT_B, checkpoint());
        var skipped = log.getOut()
                .lines()
                .filter(line -> line.contains("Content move candidate skipped;"))
                .findFirst()
                .orElseThrow();
        assertTrue(skipped.contains("connectionId=" + connection));
        assertTrue(skipped.contains("blobSha=" + sha(before) + " code=SOURCE_NOT_FOUND"));
        // Logged from a reader thread, which carries the sync request's id.
        assertTrue(skipped.contains("[" + requestId + "]"), skipped);
        assertFalse(skipped.contains("backend/ingest.md"));
    }

    @Test
    void anOldVersionThatIsNoLongerAReadableNoteOnlyExcludesThatCandidate() throws Exception {
        // GitHub still has the old version, but it is not UTF-8, so it cannot be compared as a note.
        byte[] before = "# ingest\n\ncaf\u00e9 notes".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = note("ingest", "reads");
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(after);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("pipelines/ingest.md"));
        assertEquals(COMMIT_B, checkpoint());
    }

    @Test
    void anOutageWhileComparingFailsTheSyncWithoutChanges() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(
                        tree(
                                false,
                                entry("pipelines/ingest.md", "100644", "blob", sha(after)),
                                entry("other.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before)))
                .andRespond(withServiceUnavailable());
        // Read concurrently with the failing one, so it may or may not have started when the outage stops the sync.
        user.expect(
                        ExpectedCount.between(0, 1),
                        requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(after)))
                .andRespond(withSuccess(after, MediaType.APPLICATION_OCTET_STREAM));
        sync(alice, connection).andExpect(status().isServiceUnavailable());
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(List.of("backend/ingest.md", "other.md"), activePaths());
    }

    @Test
    void repositoryWithoutMarkdownIsAnEmptyCompleteLibrary() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(tree(false, entry("README.txt", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(0));
        listed(alice)
                .andExpect(jsonPath("$.repositories[0].lastSyncedCommitSha").value(COMMIT_A))
                .andExpect(jsonPath("$.repositories[0].documents").isEmpty());
    }

    @Test
    void neverSyncedRepositoryHasNoCheckpoint() throws Exception {
        listed(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].lastSyncedCommitSha").doesNotExist())
                .andExpect(jsonPath("$.repositories[0].documents").isEmpty());
    }

    @Test
    void missingOrEmptyDefaultBranchFailsWithoutChanges() throws Exception {
        user.expect(requestTo(BRANCH)).andRespond(withResourceNotFound());
        sync(alice, connection)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFAULT_BRANCH_NOT_FOUND"));
        assertNull(checkpoint());
    }

    /** GitHub redirects a renamed repository's or branch's old name; only connecting it again can fix that. */
    @Test
    void aRedirectedRepositoryIsRenamedAndPublishesNothing() throws Exception {
        user.expect(requestTo(BRANCH))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create("https://example.invalid")));
        sync(alice, connection)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPOSITORY_RENAMED"));
        assertNull(checkpoint());
    }

    @Test
    void treeFailureAfterTheBranchCallLeavesThePreviousSnapshot() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        next();
        expectSync(COMMIT_B, TREE_B, withServiceUnavailable());
        sync(alice, connection)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(List.of("a.md"), activePaths());
    }

    @Test
    void malformedTreeEntriesAreRejected() throws Exception {
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(tree(false, entry("a.md", "100644", "blob", "not-a-sha")), MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        next();
        expectSync(COMMIT_A, TREE_A, withSuccess("{\"tree\":[]}", MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        assertNull(checkpoint());
    }

    @Test
    void treeCeilingIsAboveGitHubsSevenMegabyteMaximumButStillBounded() throws Exception {
        var entries = new StringBuilder();
        for (int i = 0; entries.length() <= GitHubHttpConfiguration.MAX_RESPONSE_BYTES; i++) {
            if (i > 0) entries.append(',');
            entries.append(entry("assets/image-" + i + ".png", "100644", "blob", BLOB_1));
        }
        expectSync(
                COMMIT_A,
                TREE_A,
                withSuccess(
                        tree(false, entries.toString(), entry("a.md", "100644", "blob", BLOB_2)),
                        MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(1));
        next();
        expectSync(
                COMMIT_B,
                TREE_B,
                withSuccess(new byte[GitHubHttpConfiguration.MAX_TREE_RESPONSE_BYTES + 1], MediaType.APPLICATION_JSON));
        sync(alice, connection)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_RESPONSE_LIMIT"));
        assertEquals(COMMIT_A, checkpoint());
    }

    @Test
    void branchNamesAreEncodedAsOnePathSegment() throws Exception {
        db.sql("UPDATE repository_connections SET default_branch = 'release/notes v2' WHERE id = :id")
                .param("id", connection)
                .update();
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/branches/release%2Fnotes%20v2"))
                .andRespond(withResourceNotFound());
        sync(alice, connection).andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotSyncOrListAndMakesNoGitHubCalls() throws Exception {
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        sync(bob, connection).andExpect(status().isNotFound());
        mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(jsonPath("$.repositories").isEmpty());
        mvc.perform(post("/api/repositories/" + connection + "/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSyncThatFetchedBeforeANewerSyncPublishedDoesNotOverwriteIt() throws Exception {
        String older = tree(false, entry("a.md", "100644", "blob", BLOB_1));
        String newer = tree(false, entry("a.md", "100644", "blob", BLOB_2), entry("b.md", "100644", "blob", BLOB_1));
        user.expect(ExpectedCount.once(), requestTo(BRANCH))
                .andRespond(withSuccess(branch(COMMIT_A, TREE_A), MediaType.APPLICATION_JSON));
        user.expect(ExpectedCount.once(), requestTo(BRANCH))
                .andRespond(withSuccess(branch(COMMIT_B, TREE_B), MediaType.APPLICATION_JSON));
        trees.expect(requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_B + "?recursive=1"))
                .andRespond(withSuccess(newer, MediaType.APPLICATION_JSON));
        // While the first sync still fetches COMMIT_A's tree, a second sync reads COMMIT_B and publishes it.
        trees.expect(requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_A + "?recursive=1"))
                .andRespond(request -> {
                    try {
                        sync(alice, connection)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.commitSha").value(COMMIT_B));
                    } catch (Exception failure) {
                        throw new AssertionError("The interleaved sync failed", failure);
                    }
                    return withSuccess(older, MediaType.APPLICATION_JSON).createResponse(request);
                });
        sync(alice, connection)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYNC_SUPERSEDED"));
        assertEquals(COMMIT_B, checkpoint());
        assertEquals(List.of("a.md", "b.md"), activePaths());
    }

    @Test
    void aSyncWhoseDefaultBranchChangedWhileItFetchedPublishesNothing() throws Exception {
        user.expect(requestTo(BRANCH)).andRespond(withSuccess(branch(COMMIT_A, TREE_A), MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/user/installations/7/repositories?per_page=100"))
                .andRespond(withSuccess(
                        "{\"total_count\":1,\"repositories\":[{\"id\":11,\"full_name\":\"test-only/notes\",\"private\":true,\"default_branch\":\"next\"}]}",
                        MediaType.APPLICATION_JSON));
        // While the sync fetches main's tree, reconnecting records GitHub's new default branch.
        trees.expect(requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_A + "?recursive=1"))
                .andRespond(request -> {
                    try {
                        mvc.perform(post("/api/repositories/11/connect")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"installationId\":7}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.defaultBranch").value("next"));
                    } catch (Exception failure) {
                        throw new AssertionError("The interleaved reconnect failed", failure);
                    }
                    return withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        sync(alice, connection)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYNC_SUPERSEDED"));
        assertNull(checkpoint());
        assertEquals(List.of(), activePaths());
    }

    @Test
    void concurrentSyncsOfOneConnectionDoNotDuplicateDocuments() throws Exception {
        String body = tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2));
        user.expect(ExpectedCount.times(4), requestTo(BRANCH))
                .andRespond(withSuccess(branch(COMMIT_A, TREE_A), MediaType.APPLICATION_JSON));
        trees.expect(
                        ExpectedCount.times(4),
                        requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_A + "?recursive=1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var runs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++)
                runs.add(executor.submit(() -> sync(alice, connection).andExpect(status().isOk())));
            for (var run : runs) run.get(10, TimeUnit.SECONDS);
        }
        assertEquals(
                2, db.sql("SELECT count(*) FROM documents").query(Integer.class).single());
        assertEquals(COMMIT_A, checkpoint());
    }
}
