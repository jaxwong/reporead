package com.reporead.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** GET /api/library: one user's lists, from one database snapshot. Test-only users, repositories and SHAs. */
class LibraryTest extends SharedSpringTest {
    private static final String SHA = "a".repeat(40);
    private static final String ANCHOR = "{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0}";

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
    TransactionTemplate transaction;

    String alice;
    long active;
    long removed;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE");
        long notes = connection(1, 11, "notes");
        active = document(notes, "a.md", false);
        removed = document(notes, "b.md", true);
    }

    private long connection(long userId, long githubRepositoryId, String name) {
        return db.sql("""
                INSERT INTO repository_connections (user_id, github_repository_id, owner, name, default_branch, last_synced_commit_sha)
                VALUES (:userId, :githubRepositoryId, 'test-only', :name, 'main', :sha) RETURNING id""")
                .param("userId", userId)
                .param("githubRepositoryId", githubRepositoryId)
                .param("name", name)
                .param("sha", SHA)
                .query(Long.class)
                .single();
    }

    private long document(long connection, String path, boolean deleted) {
        return db.sql("""
                INSERT INTO documents (repository_connection_id, path, title, current_blob_sha, deleted_at)
                VALUES (:connection, :path, :path, :sha, CASE WHEN :deleted THEN now() END) RETURNING id""")
                .param("connection", connection)
                .param("path", path)
                .param("sha", SHA)
                .param("deleted", deleted)
                .query(Long.class)
                .single();
    }

    private ResultActions library(String bearer) throws Exception {
        return mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test
    void theLibraryIsTheUsersRepositoriesWithEveryNoteAndTheirReadingBookmarksAndNotebook() throws Exception {
        mvc.perform(put("/api/documents/" + active + "/reading-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastReadBlobSha\":\"" + SHA + "\",\"progressPercent\":40,\"anchor\":" + ANCHOR
                                + ",\"lastReadAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/documents/" + removed + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
        long other = document(connection(1, 12, "other"), "z.md", false);
        library(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories.length()").value(2))
                .andExpect(jsonPath("$.repositories[0].id").value(1))
                .andExpect(jsonPath("$.repositories[0].fullName").value("test-only/notes"))
                .andExpect(jsonPath("$.repositories[0].documentCount").value(1))
                .andExpect(jsonPath("$.repositories[0].lastSyncedCommitSha").value(SHA))
                // Removed notes are listed too: the one place the phone learns the title of a note it still has data
                // on.
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(2))
                .andExpect(jsonPath("$.repositories[0].documents[0].id").value(active))
                .andExpect(jsonPath("$.repositories[0].documents[0].blobSha").value(SHA))
                .andExpect(jsonPath("$.repositories[0].documents[0].deleted").value(false))
                .andExpect(jsonPath("$.repositories[0].documents[1].id").value(removed))
                .andExpect(jsonPath("$.repositories[0].documents[1].deleted").value(true))
                .andExpect(jsonPath("$.repositories[1].fullName").value("test-only/other"))
                .andExpect(jsonPath("$.repositories[1].documents.length()").value(1))
                .andExpect(jsonPath("$.repositories[1].documents[0].id").value(other))
                .andExpect(jsonPath("$.readingStates.length()").value(1))
                .andExpect(jsonPath("$.readingStates[0].documentId").value(active))
                .andExpect(jsonPath("$.readingStates[0].progressPercent").value(40))
                .andExpect(jsonPath("$.bookmarks.length()").value(1))
                .andExpect(jsonPath("$.bookmarks[0].documentId").value(removed))
                .andExpect(jsonPath("$.notebook.annotations").isEmpty())
                .andExpect(jsonPath("$.notebook.reviews").isEmpty())
                .andExpect(jsonPath("$.notebook.sessionLimit").doesNotExist());
    }

    @Test
    void theLibraryIsPrivateToItsUser() throws Exception {
        mvc.perform(put("/api/documents/" + active + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        library(bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isEmpty())
                .andExpect(jsonPath("$.readingStates").isEmpty())
                .andExpect(jsonPath("$.bookmarks").isEmpty())
                .andExpect(jsonPath("$.notebook.annotations").isEmpty())
                .andExpect(jsonPath("$.notebook.reviews").isEmpty());
        // Bob's own repository: his library lists only its note, and Alice's lists none of it.
        long bobs = document(connection(2, 11, "notes"), "bob.md", false);
        library(bob)
                .andExpect(jsonPath("$.repositories.length()").value(1))
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(1))
                .andExpect(jsonPath("$.repositories[0].documents[0].id").value(bobs))
                .andExpect(jsonPath("$.bookmarks").isEmpty());
        library(alice)
                .andExpect(jsonPath("$.repositories.length()").value(1))
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(2));
        mvc.perform(get("/api/library")).andExpect(status().isUnauthorized());
    }

    /**
     * The server refuses a library answer over its cap whole, before sending any of it, and sends one at the cap. A
     * note's title is ASCII in the answer, so each character added to it adds one byte.
     */
    @Test
    void aLibraryOverTheServersCapIsRefusedWholeAndOneAtTheCapIsServed() throws Exception {
        long padded = document(1, "padded.md", false);
        int base = library(alice).andReturn().getResponse().getContentAsByteArray().length;
        int atCap = LibrarySnapshotReader.MAX_RESPONSE_BYTES - base + "padded.md".length();
        retitle(padded, "t".repeat(atCap));
        var served = library(alice).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(LibrarySnapshotReader.MAX_RESPONSE_BYTES, served.getContentAsByteArray().length);
        retitle(padded, "t".repeat(atCap + 1));
        var refused = library(alice)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LIBRARY_TOO_LARGE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertFalse(refused.contains("repositories"), refused);
        assertTrue(refused.length() < 1_000, "only the error, nothing of the library: " + refused.length());
    }

    private void retitle(long document, String title) {
        db.sql("UPDATE documents SET title = :title WHERE id = :id")
                .param("title", title)
                .param("id", document)
                .update();
    }

    /**
     * A write that commits while the library is being read is wholly in the next read and wholly out of this one. The
     * write holds the reading-state table until it commits, so the read has already listed the repositories and notes
     * when it waits there; without one snapshot it would then list a reading state of a note it did not list.
     */
    @Test
    void aWriteCommittedDuringTheReadIsWhollyOutOfItAndWhollyInTheNext() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        long added;
        ResultActions during;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writing = executor.submit(() -> transaction.execute(status -> {
                long id = document(1, "c.md", false);
                db.sql("""
                        INSERT INTO reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                        VALUES (1, :id, :sha, 10, CAST(:anchor AS jsonb), now())""")
                        .param("id", id)
                        .param("sha", SHA)
                        .param("anchor", ANCHOR)
                        .update();
                db.sql("LOCK TABLE reading_states IN access exclusive mode").update();
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException("Interrupted while holding the test write", interrupted);
                }
                return id;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var reading = executor.submit(() -> library(alice));
            TestEnvironment.awaitLockWait(db);
            release.countDown();
            added = writing.get(10, TimeUnit.SECONDS);
            during = reading.get(10, TimeUnit.SECONDS);
        }
        during.andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0].documentCount").value(1))
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(2))
                .andExpect(jsonPath("$.readingStates").isEmpty());
        library(alice)
                .andExpect(jsonPath("$.repositories[0].documentCount").value(2))
                .andExpect(jsonPath("$.repositories[0].documents.length()").value(3))
                .andExpect(jsonPath("$.readingStates[0].documentId").value(added));
    }
}
