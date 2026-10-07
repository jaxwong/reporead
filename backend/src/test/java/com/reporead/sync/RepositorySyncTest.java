package com.reporead.sync;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.GitHubSecurity;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RepositorySyncTest {
    // Test-only repository, SHAs, and tokens; no live GitHub call is made or claimed.
    private static final String TOKEN = "TEST_ONLY_ALICE_USER_TOKEN";
    private static final String BRANCH = "https://api.github.com/repos/test-only/notes/branches/main";
    private static final String COMMIT_A = "a".repeat(40);
    private static final String COMMIT_B = "b".repeat(40);
    private static final String TREE_A = "1".repeat(40);
    private static final String TREE_B = "2".repeat(40);
    private static final String BLOB_1 = "3".repeat(40);
    private static final String BLOB_2 = "4".repeat(40);

    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("githubUserApi") RestTemplate githubUserApi;
    @Autowired @Qualifier("githubTreeApi") RestTemplate githubTreeApi;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired AppSessions sessions;
    @Autowired JdbcClient db;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    MockRestServiceServer user;
    MockRestServiceServer trees;
    String alice;
    long connection;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        user = MockRestServiceServer.bindTo(githubUserApi).ignoreExpectOrder(true).build();
        trees = MockRestServiceServer.bindTo(githubTreeApi).ignoreExpectOrder(true).build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, TOKEN);
        connection = connect(1, "main");
    }

    @AfterEach void verifyNoExtraCalls() {
        user.verify();
        trees.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private long connect(long userId, String branch) {
        return db.sql("""
                insert into repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch)
                values (:userId, 11, 7, 'test-only', 'notes', :branch) returning id""")
            .param("userId", userId).param("branch", branch).query(Long.class).single();
    }

    private static String branch(String commit, String tree) {
        return "{\"name\":\"main\",\"commit\":{\"sha\":\"" + commit + "\",\"commit\":{\"tree\":{\"sha\":\"" + tree + "\"}}}}";
    }

    private static String entry(String path, String mode, String type, String sha) {
        return "{\"path\":\"" + path + "\",\"mode\":\"" + mode + "\",\"type\":\"" + type + "\",\"sha\":\"" + sha + "\"}";
    }

    private static String tree(boolean truncated, String... entries) {
        return "{\"sha\":\"" + TREE_A + "\",\"truncated\":" + truncated + ",\"tree\":[" + String.join(",", entries) + "]}";
    }

    private void expectSync(String commit, String treeSha, ResponseCreator treeResponse) {
        user.expect(requestTo(BRANCH)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andRespond(withSuccess(branch(commit, treeSha), MediaType.APPLICATION_JSON));
        trees.expect(requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + treeSha + "?recursive=1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andRespond(treeResponse);
    }

    /** Ends one request phase: every expected call happened, and later expectations start fresh. */
    private void next() {
        user.verify();
        trees.verify();
        user.reset();
        trees.reset();
    }

    private ResultActions sync(String bearer, long id) throws Exception {
        return mvc.perform(post("/api/repositories/" + id + "/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions documents(String bearer, long id) throws Exception {
        return mvc.perform(get("/api/repositories/" + id + "/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private List<String> activePaths() {
        return db.sql("select path from documents where deleted_at is null order by path").query(String.class).list();
    }

    private String checkpoint() {
        return db.sql("select last_synced_commit_sha from repository_connections where id = :id").param("id", connection)
            .query(String.class).optional().orElse(null);
    }

    @Test void syncPublishesOnlyRegularMarkdownFilesAndTheSecondRunIsIdempotent() throws Exception {
        String body = tree(false,
            entry("intro.md", "100644", "blob", BLOB_1), entry("algorithms", "040000", "tree", TREE_B),
            entry("algorithms/Graphs.MD", "100755", "blob", BLOB_2), entry("image.png", "100644", "blob", BLOB_1),
            entry("link.md", "120000", "blob", BLOB_1), entry("vendor.md", "160000", "commit", COMMIT_B));
        expectSync(COMMIT_A, TREE_A, withSuccess(body, MediaType.APPLICATION_JSON));
        expectSync(COMMIT_A, TREE_A, withSuccess(body, MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk()).andExpect(jsonPath("$.commitSha").value(COMMIT_A))
            .andExpect(jsonPath("$.documentCount").value(2));
        var ids = db.sql("select id from documents order by id").query(Long.class).list();
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(ids, db.sql("select id from documents order by id").query(Long.class).list());
        assertEquals(List.of("algorithms/Graphs.MD", "intro.md"), activePaths());
        documents(alice, connection).andExpect(status().isOk()).andExpect(jsonPath("$.lastSyncedCommitSha").value(COMMIT_A))
            .andExpect(jsonPath("$.documents[0].path").value("algorithms/Graphs.MD"))
            .andExpect(jsonPath("$.documents[0].title").value("Graphs"))
            .andExpect(jsonPath("$.documents[1].blobSha").value(BLOB_1));
    }

    @Test void aSnapshotRecordsItsImageFilesForEmbedsAndReplacesThemNextTime() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("intro.md", "100644", "blob", BLOB_1),
            entry("attachments/Pasted image.png", "100644", "blob", BLOB_2), entry("diagram.SVG", "100755", "blob", BLOB_2),
            entry("link.png", "120000", "blob", BLOB_2), entry("notes.txt", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of("attachments/Pasted image.png", "diagram.SVG"),
            db.sql("select path from repository_images order by path").query(String.class).list());
        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("intro.md", "100644", "blob", BLOB_1),
            entry("img/new.webp", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of("img/new.webp"), db.sql("select path from repository_images").query(String.class).list());
    }

    @Test void truncatedTreeChangesNothingAndIsNotTreatedAsDeletion() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("intro.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        double succeeded = meters.counter(RepositorySync.SYNCS, "outcome", "success", "code", "NONE").count();
        double incomplete = meters.counter(RepositorySync.SYNCS, "outcome", "failure", "code", "GITHUB_TREE_INCOMPLETE").count();
        double documents = meters.counter(RepositorySync.DOCUMENTS_SYNCED).count();
        sync(alice, connection).andExpect(status().isOk());
        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(true), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_TREE_INCOMPLETE"));
        assertEquals(List.of("intro.md"), activePaths());
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(succeeded + 1, meters.counter(RepositorySync.SYNCS, "outcome", "success", "code", "NONE").count());
        assertEquals(incomplete + 1, meters.counter(RepositorySync.SYNCS, "outcome", "failure", "code", "GITHUB_TREE_INCOMPLETE").count());
        assertEquals(documents + 1, meters.counter(RepositorySync.DOCUMENTS_SYNCED).count());
    }

    @Test void pathMissingFromACompleteTreeIsMarkedDeletedNotRemovedAndCanReturn() throws Exception {
        String both = tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2));
        expectSync(COMMIT_A, TREE_A, withSuccess(both, MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long b = db.sql("select id from documents where path = 'b.md'").query(Long.class).single();

        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(1));
        assertEquals(List.of("a.md"), activePaths());
        assertEquals(2, db.sql("select count(*) from documents").query(Integer.class).single());
        assertEquals(BLOB_2, db.sql("select current_blob_sha from documents where path = 'a.md'").query(String.class).single());

        next();
        expectSync(COMMIT_A, TREE_A, withSuccess(both, MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(b, db.sql("select id from documents where path = 'b.md' and deleted_at is null").query(Long.class).single());
    }

    private long documentId(String path) {
        return db.sql("select id from documents where path = :path").param("path", path).query(Long.class).single();
    }

    private java.time.Instant changedAt(String path) {
        var at = db.sql("select content_changed_at from documents where path = :path").param("path", path)
            .query((row, n) -> java.util.Optional.ofNullable(row.getTimestamp(1))).single();
        return at.map(java.sql.Timestamp::toInstant).orElse(null);
    }

    private java.time.Instant lastSyncedAt() {
        return db.sql("select last_synced_at from repository_connections where id = :id").param("id", connection)
            .query((row, n) -> row.getTimestamp(1).toInstant()).single();
    }

    @Test void aRefreshRecordsWhenItFoundANoteNewChangedOrBackButNotTheFirstSnapshotOrAMove() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("same.md", "100644", "blob", BLOB_1), entry("edited.md", "100644", "blob", BLOB_1),
            entry("gone.md", "100644", "blob", "5".repeat(40)), entry("old/moved.md", "100644", "blob", "6".repeat(40))), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        for (var path : List.of("same.md", "edited.md", "gone.md", "old/moved.md")) assertNull(changedAt(path), path);

        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("same.md", "100644", "blob", BLOB_1), entry("edited.md", "100644", "blob", BLOB_2),
            entry("new.md", "100644", "blob", "7".repeat(40)), entry("new/moved.md", "100644", "blob", "6".repeat(40))), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        var second = lastSyncedAt();
        assertNull(changedAt("same.md"));
        assertEquals(second, changedAt("edited.md"));
        assertEquals(second, changedAt("new.md"));
        assertNull(changedAt("new/moved.md"), "a path-only move is not a content change");
        documents(alice, connection).andExpect(status().isOk())
            .andExpect(jsonPath("$.documents[?(@.path == 'edited.md')].contentChangedAt").value(second.toString()))
            .andExpect(jsonPath("$.documents[?(@.path == 'same.md')].contentChangedAt").value(org.hamcrest.Matchers.contains((Object) null)));

        next();
        // The same snapshot again changes nothing; a note that returns after being deleted was found again.
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("same.md", "100644", "blob", BLOB_1), entry("edited.md", "100644", "blob", BLOB_2),
            entry("new.md", "100644", "blob", "7".repeat(40)), entry("new/moved.md", "100644", "blob", "6".repeat(40)),
            entry("gone.md", "100644", "blob", "5".repeat(40))), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        var third = lastSyncedAt();
        assertEquals(second, changedAt("edited.md"));
        assertEquals(third, changedAt("gone.md"));
    }

    @Test void pathOnlyMoveWithTheSameBlobKeepsTheDocumentAndItsReadingState() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("backend/spring.md", "100644", "blob", BLOB_1),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long spring = documentId("backend/spring.md");
        db.sql("""
                insert into reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                values (1, :id, :sha, 40, '{"headingPath":[],"textPrefix":null,"blockIndex":3}', now())""")
            .param("id", spring).param("sha", BLOB_1).update();

        String moved = tree(false, entry("java/spring/transactions.md", "100644", "blob", BLOB_1), entry("other.md", "100644", "blob", BLOB_2));
        for (int run = 0; run < 2; run++) {
            next();
            expectSync(COMMIT_B, TREE_B, withSuccess(moved, MediaType.APPLICATION_JSON));
            sync(alice, connection).andExpect(status().isOk());
            assertEquals(spring, documentId("java/spring/transactions.md"));
            assertEquals(List.of("java/spring/transactions.md", "other.md"), activePaths());
            assertEquals(2, db.sql("select count(*) from documents").query(Integer.class).single());
        }
        assertEquals("transactions", db.sql("select title from documents where id = :id").param("id", spring).query(String.class).single());
        assertEquals(spring, db.sql("select document_id from reading_states").query(Long.class).single());
    }

    @Test void identicalContentDuplicatesAreNeverMergedByAMove() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_1),
            entry("x.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        var before = List.of(documentId("a.md"), documentId("b.md"), documentId("x.md"));

        next();
        // Two vanished documents share one SHA, and one vanished SHA reappears at two paths: neither is a unique move.
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("c.md", "100644", "blob", BLOB_1), entry("d.md", "100644", "blob", BLOB_1),
            entry("y.md", "100644", "blob", BLOB_2), entry("z.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of("c.md", "d.md", "y.md", "z.md"), activePaths());
        assertEquals(before, db.sql("select id from documents where deleted_at is not null order by path").query(Long.class).list());
        assertEquals(7, db.sql("select count(*) from documents").query(Integer.class).single());
    }

    @Test void aPathThatOnceHadADocumentResumesItInsteadOfReceivingAMove() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2)),
            MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        long a = documentId("a.md");
        long b = documentId("b.md");
        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());

        next();
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("b.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(List.of(b), db.sql("select id from documents where deleted_at is null").query(Long.class).list());
        assertEquals(List.of(a), db.sql("select id from documents where deleted_at is not null").query(Long.class).list());
    }

    // ---- Moves with edits (content moves) ----

    /** Test-only note text: ten numbered sentences, enough five-word shingles to be compared by content. */
    private static byte[] note(String topic, String verb) {
        var text = new StringBuilder("# " + topic + "\n\n");
        for (int i = 1; i <= 10; i++) text.append("In step ").append(i).append(" the ").append(topic).append(" worker ").append(verb)
            .append(" a batch and records its checkpoint before continuing.\n\n");
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] edited(byte[] original) {
        return new String(original, java.nio.charset.StandardCharsets.UTF_8).replace("In step 4 the", "In step 4, after a pause, the")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) {
        return com.reporead.document.MarkdownRenderer.blobSha(bytes);
    }

    private void expectBlob(byte[] bytes) {
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(bytes)))
            .andRespond(withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void readSomething(long documentId) {
        db.sql("""
                insert into reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                values (1, :id, :sha, 10, '{"headingPath":[],"textPrefix":null,"blockIndex":0}', now())""")
            .param("id", documentId).param("sha", BLOB_1).update();
    }

    private long syncedNote(String path, byte[] bytes) throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry(path, "100644", "blob", sha(bytes)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        next();
        return documentId(path);
    }

    @Test void aNoteMovedAndEditedKeepsItsIdentityWhenItCarriesUserData() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("pipelines/ingest-worker.md", "100644", "blob", sha(after)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(after);
        sync(alice, connection).andExpect(status().isOk());
        assertEquals(id, documentId("pipelines/ingest-worker.md"));
        assertEquals(sha(after), db.sql("select current_blob_sha from documents where id = :id").param("id", id).query(String.class).single());
        assertEquals(List.of("other.md", "pipelines/ingest-worker.md"), activePaths());
        assertEquals(2, db.sql("select count(*) from documents").query(Integer.class).single());
    }

    @Test void anUnrelatedNewNoteIsNotMergedWithADeletedOne() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] unrelated = note("billing", "invoices");
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("billing.md", "100644", "blob", sha(unrelated)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(unrelated);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("billing.md"));
        assertNotNull(db.sql("select deleted_at from documents where id = :id").param("id", id).query(java.sql.Timestamp.class).single());
    }

    @Test void twoEquallyLikelyNewNotesAreAmbiguousAndNeitherIsAMove() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] copyA = edited(before);
        byte[] copyB = new String(before, java.nio.charset.StandardCharsets.UTF_8).replace("In step 7 the", "In step 7 only the")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("a/ingest.md", "100644", "blob", sha(copyA)),
            entry("b/ingest.md", "100644", "blob", sha(copyB)), entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        expectBlob(before);
        expectBlob(copyA);
        expectBlob(copyB);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("a/ingest.md"));
        assertNotEquals(id, documentId("b/ingest.md"));
    }

    @Test void aNoteWithoutUserDataIsNotComparedAndCostsNoBlobReads() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        byte[] after = edited(before);
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("pipelines/ingest.md", "100644", "blob", sha(after)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("pipelines/ingest.md"));
    }

    @Test void tooManyCandidatesAreNotComparedAndCostNoBlobReads() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        var entries = new java.util.ArrayList<String>();
        for (int i = 0; i < RepositorySync.MAX_CONTENT_MOVE_CANDIDATES; i++) entries.add(entry("new-" + i + ".md", "100644", "blob", BLOB_1));
        entries.add(entry("other.md", "100644", "blob", BLOB_2));
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entries.toArray(String[]::new)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        assertNotNull(db.sql("select deleted_at from documents where id = :id").param("id", id).query(java.sql.Timestamp.class).single());
    }

    @Test void anOldVersionGitHubNoLongerHasOnlyExcludesThatCandidate() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("pipelines/ingest.md", "100644", "blob", sha(after)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before))).andRespond(withResourceNotFound());
        expectBlob(after);
        sync(alice, connection).andExpect(status().isOk());
        assertNotEquals(id, documentId("pipelines/ingest.md"));
        assertEquals(COMMIT_B, checkpoint());
    }

    @Test void anOutageWhileComparingFailsTheSyncWithoutChanges() throws Exception {
        byte[] before = note("ingest", "reads");
        long id = syncedNote("backend/ingest.md", before);
        readSomething(id);
        byte[] after = edited(before);
        expectSync(COMMIT_B, TREE_B, withSuccess(tree(false, entry("pipelines/ingest.md", "100644", "blob", sha(after)),
            entry("other.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha(before))).andRespond(withServiceUnavailable());
        sync(alice, connection).andExpect(status().isServiceUnavailable());
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(List.of("backend/ingest.md", "other.md"), activePaths());
    }

    @Test void repositoryWithoutMarkdownIsAnEmptyCompleteLibrary() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("README.txt", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(0));
        documents(alice, connection).andExpect(jsonPath("$.lastSyncedCommitSha").value(COMMIT_A)).andExpect(jsonPath("$.documents").isEmpty());
    }

    @Test void neverSyncedRepositoryHasNoCheckpoint() throws Exception {
        documents(alice, connection).andExpect(status().isOk())
            .andExpect(jsonPath("$.lastSyncedCommitSha").doesNotExist()).andExpect(jsonPath("$.documents").isEmpty());
    }

    @Test void missingOrEmptyDefaultBranchFailsWithoutChanges() throws Exception {
        user.expect(requestTo(BRANCH)).andRespond(withResourceNotFound());
        sync(alice, connection).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEFAULT_BRANCH_NOT_FOUND"));
        assertNull(checkpoint());
    }

    @Test void treeFailureAfterTheBranchCallLeavesThePreviousSnapshot() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("a.md", "100644", "blob", BLOB_1)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk());
        next();
        expectSync(COMMIT_B, TREE_B, withServiceUnavailable());
        sync(alice, connection).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        assertEquals(COMMIT_A, checkpoint());
        assertEquals(List.of("a.md"), activePaths());
    }

    @Test void malformedTreeEntriesAreRejected() throws Exception {
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entry("a.md", "100644", "blob", "not-a-sha")), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        next();
        expectSync(COMMIT_A, TREE_A, withSuccess("{\"tree\":[]}", MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
        assertNull(checkpoint());
    }

    @Test void treeCeilingIsAboveGitHubsSevenMegabyteMaximumButStillBounded() throws Exception {
        var entries = new StringBuilder();
        for (int i = 0; entries.length() <= GitHubSecurity.MAX_RESPONSE_BYTES; i++) {
            if (i > 0) entries.append(',');
            entries.append(entry("assets/image-" + i + ".png", "100644", "blob", BLOB_1));
        }
        expectSync(COMMIT_A, TREE_A, withSuccess(tree(false, entries.toString(), entry("a.md", "100644", "blob", BLOB_2)), MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(1));
        next();
        expectSync(COMMIT_B, TREE_B, withSuccess(new byte[GitHubSecurity.MAX_TREE_RESPONSE_BYTES + 1], MediaType.APPLICATION_JSON));
        sync(alice, connection).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GITHUB_RESPONSE_LIMIT"));
        assertEquals(COMMIT_A, checkpoint());
    }

    @Test void branchNamesAreEncodedAsOnePathSegment() throws Exception {
        db.sql("update repository_connections set default_branch = 'release/notes v2' where id = :id").param("id", connection).update();
        user.expect(requestTo("https://api.github.com/repos/test-only/notes/branches/release%2Fnotes%20v2")).andRespond(withResourceNotFound());
        sync(alice, connection).andExpect(status().isNotFound());
    }

    @Test void anotherUserCannotSyncOrListAndMakesNoGitHubCalls() throws Exception {
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        sync(bob, connection).andExpect(status().isNotFound());
        documents(bob, connection).andExpect(status().isNotFound());
        documents(alice, 999).andExpect(status().isNotFound());
        mvc.perform(post("/api/repositories/" + connection + "/sync")).andExpect(status().isUnauthorized());
    }

    @Test void concurrentSyncsOfOneConnectionDoNotDuplicateDocuments() throws Exception {
        String body = tree(false, entry("a.md", "100644", "blob", BLOB_1), entry("b.md", "100644", "blob", BLOB_2));
        user.expect(ExpectedCount.times(4), requestTo(BRANCH)).andRespond(withSuccess(branch(COMMIT_A, TREE_A), MediaType.APPLICATION_JSON));
        trees.expect(ExpectedCount.times(4), requestTo("https://api.github.com/repos/test-only/notes/git/trees/" + TREE_A + "?recursive=1"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var runs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) runs.add(executor.submit(() -> sync(alice, connection).andExpect(status().isOk())));
            for (var run : runs) run.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, db.sql("select count(*) from documents").query(Integer.class).single());
        assertEquals(COMMIT_A, checkpoint());
    }
}
