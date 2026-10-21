package com.reporead.deletion;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.annotation.Annotations;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/** Disconnecting a repository and deleting an account. Test-only users, repositories, and notes; no GitHub calls. */
class DeletionTest extends SharedSpringTest {
    private static final String SHA = "a".repeat(40);

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("githubUserHttpClient")
    RestTemplate githubUserHttpClient;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    AppSessions sessions;

    @Autowired
    JdbcClient db;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    RepositoryConnections connections;

    @Autowired
    ConnectionDeletion data;

    @Autowired
    Annotations annotations;

    MockRestServiceServer github;
    String alice;
    String bob;
    long notes;
    long other;
    long bobs;
    long active;
    long removedUpstream;
    long otherNote;
    long bobNote;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserHttpClient).build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_USER_TOKEN");
        bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        notes = connection(1, 11, "notes");
        other = connection(1, 12, "other");
        bobs = connection(2, 11, "notes");
        active = document(notes, "a.md", false);
        removedUpstream = document(notes, "gone.md", true);
        otherNote = document(other, "o.md", false);
        bobNote = document(bobs, "a.md", false);
        reading(1, active);
        reading(1, removedUpstream);
        reading(1, otherNote);
        reading(2, bobNote);
        bookmark(1, active);
        highlight(1, removedUpstream, "11111111-1111-1111-1111-111111111111");
        highlight(1, otherNote, "22222222-2222-2222-2222-222222222222");
        highlight(2, bobNote, "33333333-3333-3333-3333-333333333333");
    }

    @AfterEach
    void verifyGitHubCalls() {
        github.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private long connection(long userId, long githubRepositoryId, String name) {
        return db.sql("""
                INSERT INTO repository_connections (user_id, github_repository_id, owner, name, default_branch, last_synced_commit_sha)
                VALUES (:userId, :repo, 'test-only', :name, 'main', :sha) RETURNING id""")
                .param("userId", userId)
                .param("repo", githubRepositoryId)
                .param("name", name)
                .param("sha", SHA)
                .query(Long.class)
                .single();
    }

    private long document(long connectionId, String path, boolean deleted) {
        return db.sql("""
                INSERT INTO documents (repository_connection_id, path, title, current_blob_sha, deleted_at)
                VALUES (:connectionId, :path, 'title', :sha, CASE WHEN :deleted THEN now() END) RETURNING id""")
                .param("connectionId", connectionId)
                .param("path", path)
                .param("sha", SHA)
                .param("deleted", deleted)
                .query(Long.class)
                .single();
    }

    private void reading(long userId, long documentId) {
        db.sql("""
                INSERT INTO reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                VALUES (:userId, :documentId, :sha, 10, '{"headingPath":[],"textPrefix":null,"blockIndex":0}', now())""")
                .param("userId", userId)
                .param("documentId", documentId)
                .param("sha", SHA)
                .update();
    }

    private void bookmark(long userId, long documentId) {
        db.sql(
                        "INSERT INTO annotations (user_id, document_id, type, created_at, updated_at) VALUES (:userId, :documentId, 'BOOKMARK', now(), now())")
                .param("userId", userId)
                .param("documentId", documentId)
                .update();
    }

    private void highlight(long userId, long documentId, String mutationId) {
        long id = db.sql("""
                INSERT INTO annotations (user_id, document_id, type, created_at, updated_at, note, resolved_blob_sha, mutation_id)
                VALUES (:userId, :documentId, 'HIGHLIGHT', now(), now(), 'private note text', :sha, CAST(:mutationId AS uuid)) RETURNING id""")
                .param("userId", userId)
                .param("documentId", documentId)
                .param("sha", SHA)
                .param("mutationId", mutationId)
                .query(Long.class)
                .single();
        db.sql("""
                INSERT INTO annotation_anchors (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset, end_offset, heading_path)
                VALUES (:id, :sha, 'b1', 'quoted passage', '', '', 0, 14, '[]')""").param("id", id).param("sha", SHA).update();
        // A location records how distinguishable its passage is: here, the only occurrence, with no rival or
        // look-alike.
        db.sql("""
                INSERT INTO annotation_locations (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset, end_offset,
                                                  heading_path, quote_occurrences, rival_context, rival_quote)
                VALUES (:id, :sha, 'b1', 'quoted passage', '', '', 0, 14, '[]', 1, 0, 0)""").param("id", id).param("sha", SHA).update();
        db.sql(
                        "INSERT INTO annotation_mutations (user_id, mutation_id, request_hash, created_at) VALUES (:userId, CAST(:mutationId AS uuid), '\\x00', now())")
                .param("userId", userId)
                .param("mutationId", mutationId)
                .update();
    }

    private int count(String sql) {
        return db.sql(sql).query(Integer.class).single();
    }

    private void cardWithReview(long userId, long documentId, String mutationId) {
        highlight(userId, documentId, mutationId);
        long id = db.sql("SELECT id FROM annotations WHERE user_id = :user AND mutation_id = CAST(:mutation AS uuid)")
                .param("user", userId)
                .param("mutation", mutationId)
                .query(Long.class)
                .single();
        db.sql("UPDATE annotations SET type = 'CARD', question = 'Test-only question?' WHERE id = :id")
                .param("id", id)
                .update();
        db.sql(
                        "INSERT INTO review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha) VALUES (:user, CAST(:mutation AS uuid), :id, 4, now(), :sha)")
                .param("user", userId)
                .param("mutation", mutationId)
                .param("id", id)
                .param("sha", SHA)
                .update();
    }

    @Test
    void cardsAndReviewLogsAreDeletedOnDisconnectAndAccountDeletionWithoutTouchingOtherUsers() throws Exception {
        cardWithReview(1, active, "44444444-4444-4444-4444-444444444444");
        cardWithReview(1, otherNote, "55555555-5555-5555-5555-555555555555");
        cardWithReview(2, bobNote, "66666666-6666-6666-6666-666666666666");
        mvc.perform(get("/api/repositories/" + notes + "/stored-data")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.cards").value(1));
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.cards").value(1));
        assertEquals(2, count("SELECT count(*) FROM review_log"));
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_USER_TOKEN", withNoContent());
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").value(1));
        assertEquals(0, count("SELECT count(*) FROM review_log WHERE user_id = 1"));
        assertEquals(1, count("SELECT count(*) FROM review_log WHERE user_id = 2"));
        assertEquals(1, count("SELECT count(*) FROM annotations WHERE user_id = 2 AND type = 'CARD'"));
    }

    @Test
    void disconnectingDeletesOnlyThatRepositorysDataAfterShowingWhatWillGo() throws Exception {
        mvc.perform(get("/api/repositories/" + notes + "/stored-data")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.readingStates").value(2))
                .andExpect(jsonPath("$.bookmarks").value(1))
                .andExpect(jsonPath("$.highlights").value(1));

        db.sql(
                        "INSERT INTO repository_images (repository_connection_id, path) VALUES (:notes, 'a.png'), (:other, 'b.png')")
                .param("notes", notes)
                .param("other", other)
                .update();
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentIds.length()").value(2))
                .andExpect(jsonPath("$.documentIds[0]").value(active))
                .andExpect(jsonPath("$.documentIds[1]").value(removedUpstream))
                .andExpect(jsonPath("$.readingStates").value(2))
                .andExpect(jsonPath("$.bookmarks").value(1))
                .andExpect(jsonPath("$.highlights").value(1));

        assertEquals(
                List.of(other, bobs),
                db.sql("SELECT id FROM repository_connections ORDER BY id")
                        .query(Long.class)
                        .list());
        assertEquals(
                List.of(otherNote, bobNote),
                db.sql("SELECT id FROM documents ORDER BY id").query(Long.class).list());
        assertEquals(
                List.of(otherNote, bobNote),
                db.sql("SELECT document_id FROM reading_states ORDER BY document_id")
                        .query(Long.class)
                        .list());
        assertEquals(
                List.of(otherNote, bobNote),
                db.sql("SELECT document_id FROM annotations ORDER BY document_id")
                        .query(Long.class)
                        .list());
        assertEquals(
                List.of("b.png"),
                db.sql("SELECT path FROM repository_images").query(String.class).list());
        assertEquals(2, count("SELECT count(*) FROM annotation_anchors"));
        assertEquals(2, count("SELECT count(*) FROM annotation_locations"));
        // The deleted highlight's mutation record stays, named by no annotation, so a late replay is answered as
        // deleted, not recreated.
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM annotation_mutations WHERE mutation_id = '11111111-1111-1111-1111-111111111111'"));
        assertEquals(
                0,
                count("SELECT count(*) FROM annotations WHERE mutation_id = '11111111-1111-1111-1111-111111111111'"));

        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.repositories.length()").value(1))
                .andExpect(jsonPath("$.repositories[0].id").value(other));
    }

    /**
     * Runs {@code write} while a disconnect of {@code notes} holds its lock, as DeletionController.disconnect does, and
     * returns the write's HTTP status. A write that did not wait would commit rows the disconnect's deletes no longer
     * see.
     */
    private int writeDuringDisconnect(Callable<ResultActions> write) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var disconnect = executor.submit(() -> transaction.execute(status -> {
                connections.lockForSync(1, notes);
                locked.countDown();
                await(release);
                return data.delete(notes);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var written = executor.submit(write);
            TestEnvironment.awaitLockWait(db);
            assertFalse(written.isDone(), "the write must wait for the disconnect");
            release.countDown();
            disconnect.get(10, TimeUnit.SECONDS);
            return written.get(10, TimeUnit.SECONDS).andReturn().getResponse().getStatus();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException("Interrupted while holding the test disconnect", interrupted);
        }
    }

    @Test
    void readingAndBookmarkWritesRacingADisconnectWaitAndFindNothingInsteadOfBreakingIt() throws Exception {
        assertEquals(
                404,
                writeDuringDisconnect(() -> mvc.perform(put("/api/documents/" + active + "/reading-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastReadBlobSha\":\"" + SHA
                                + "\",\"progressPercent\":50,\"anchor\":{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0},"
                                + "\"lastReadAt\":\"2026-01-01T00:00:00Z\"}"))));
        notes = connection(1, 11, "notes");
        active = document(notes, "a.md", false);
        assertEquals(
                404,
                writeDuringDisconnect(() -> mvc.perform(put("/api/documents/" + active + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))));
        assertEquals(0, count("SELECT count(*) FROM documents WHERE repository_connection_id = " + notes));
    }

    @Test
    void deletingTheAccountWaitsForAConnectionBeingAddedAndDeletesItToo() throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var connecting = executor.submit(() -> transaction.execute(status -> {
                long id = connection(1, 13, "new");
                inserted.countDown();
                await(release);
                return id;
            }));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_USER_TOKEN", withNoContent());
            var deleting = executor.submit(
                    () -> mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            // The deletion waits for the user row the new connection's foreign key holds.
            TestEnvironment.awaitLockWait(db);
            assertFalse(deleting.isDone(), "the account deletion must wait for the connection being added");
            release.countDown();
            connecting.get(10, TimeUnit.SECONDS);
            assertEquals(200, deleting.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, count("SELECT count(*) FROM repository_connections WHERE user_id = 1"));
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = 1"));
    }

    /**
     * The other order: a connection that reaches the user's lock while an account deletion holds it waits, and once the
     * deletion commits it is refused as signed out, instead of failing on the deleted user's foreign key.
     */
    @Test
    void aConnectionWaitingForAnAccountDeletionIsRefusedOnceTheAccountIsGone() throws Exception {
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        github.expect(requestTo("https://api.github.com/user/installations/7/repositories?per_page=100"))
                .andRespond(withSuccess(
                        "{\"total_count\":1,\"repositories\":[{\"id\":13,\"full_name\":\"test-only/new\",\"private\":true,\"default_branch\":\"main\"}]}",
                        MediaType.APPLICATION_JSON));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deleting = executor.submit(() -> transaction.execute(status -> {
                // What account deletion does, held open before it commits.
                sessions.lockUserForDeletion(1);
                for (long id : List.of(notes, other)) {
                    connections.lockForSync(1, id);
                    data.delete(id);
                }
                annotations.deleteMutations(1);
                sessions.deleteUser(1);
                deleted.countDown();
                await(release);
                return null;
            }));
            assertTrue(deleted.await(10, TimeUnit.SECONDS));
            var connecting = executor.submit(() -> mvc.perform(post("/api/repositories/13/connect")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"installationId\":7}"))
                    .andReturn()
                    .getResponse());
            TestEnvironment.awaitLockWait(db);
            assertFalse(connecting.isDone(), "the connection must wait for the account deletion");
            release.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            var refused = connecting.get(10, TimeUnit.SECONDS);
            assertEquals(401, refused.getStatus());
            assertTrue(refused.getContentAsString().contains("\"SIGN_IN_REQUIRED\""), refused.getContentAsString());
        }
        assertEquals(0, count("SELECT count(*) FROM repository_connections WHERE user_id = 1"));
    }

    /**
     * Outside a transaction a row lock is released by the statement that takes it, so a caller that forgot the
     * transaction would be protected by nothing. Each lock refuses to run there instead.
     */
    @Test
    void locksRefuseToRunOutsideATransaction() {
        var outside = List.<Runnable>of(
                () -> connections.lockForSync(1, notes),
                () -> connections.lockForDocumentWrite(1, active),
                () -> sessions.lockUserForDeletion(1),
                () -> sessions.lockUserForWrite(1));
        for (var lock : outside) {
            var refused = assertThrows(IllegalStateException.class, lock::run);
            assertTrue(refused.getMessage().contains("outside a transaction"), refused.getMessage());
        }
        transaction.executeWithoutResult(status -> outside.forEach(Runnable::run));
    }

    @Test
    void anotherUsersRepositoryCannotBeInspectedOrDisconnected() throws Exception {
        mvc.perform(get("/api/repositories/" + notes + "/stored-data")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/repositories/" + notes)).andExpect(status().isUnauthorized());
        assertEquals(3, count("SELECT count(*) FROM repository_connections"));
        assertEquals(4, count("SELECT count(*) FROM documents"));
    }

    @Test
    void deletingTheAccountRemovesAllItsDataSessionsAndGitHubTokenButNoOneElses() throws Exception {
        String second = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_USER_TOKEN");
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_USER_TOKEN", withNoContent());
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").value(2))
                .andExpect(jsonPath("$.readingStates").value(3))
                .andExpect(jsonPath("$.bookmarks").value(1))
                .andExpect(jsonPath("$.highlights").value(2));

        for (String table : List.of(
                "repository_connections",
                "reading_states",
                "annotations",
                "annotation_mutations",
                "app_sessions",
                "app_sign_in_codes")) {
            assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE user_id = 1"), table);
        }
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = 1"));
        assertEquals(
                List.of(bobNote),
                db.sql("SELECT id FROM documents").query(Long.class).list());
        assertNull(
                clients.loadAuthorizedClient("github", "42"), "the server keeps no GitHub token for a deleted account");
        assertNotNull(clients.loadAuthorizedClient("github", "84"));
        for (String token : List.of(alice, second)) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isOk());
        assertEquals(1, count("SELECT count(*) FROM annotations WHERE user_id = 2"));
        assertEquals(1, count("SELECT count(*) FROM annotation_mutations WHERE user_id = 2"));
    }

    @Test
    void gitHubIsAskedToRevokeOnlyAfterTheDeletionCommitted() throws Exception {
        // Revoking cannot be undone, so a deletion that failed must not have revoked the account's GitHub
        // authorization.
        github.expect(requestTo("https://api.github.com/applications/test-only-not-a-github-app/grant"))
                .andExpect(request -> assertEquals(
                        0,
                        count("SELECT count(*) FROM users WHERE id = 1"),
                        "the account must already be deleted when GitHub is asked to revoke its authorization"))
                .andRespond(withNoContent());
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }

    @Test
    void deletingTheAccountDoesNotDependOnGitHubRevokingTheAuthorization() throws Exception {
        TestSessions.expectAuthorizationRevoked(github, "TEST_ONLY_ALICE_USER_TOKEN", withServiceUnavailable());
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
        assertEquals(0, count("SELECT count(*) FROM users WHERE id = 1"));
        assertNull(clients.loadAuthorizedClient("github", "42"));
    }
}
