package com.reporead.repository;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Disconnecting a repository and deleting an account. Test-only users, repositories, and notes; no GitHub calls. */
@SpringBootTest
@AutoConfigureMockMvc
class DataControlTest {
    private static final String SHA = "a".repeat(40);

    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("githubUserApi") RestTemplate githubUserApi;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired AppSessions sessions;
    @Autowired JdbcClient db;
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

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserApi).build();
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

    @AfterEach void verifyNoGitHubCalls() {
        github.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private long connection(long userId, long githubRepositoryId, String name) {
        return db.sql("""
                insert into repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch, last_synced_commit_sha)
                values (:userId, :repo, 7, 'test-only', :name, 'main', :sha) returning id""")
            .param("userId", userId).param("repo", githubRepositoryId).param("name", name).param("sha", SHA).query(Long.class).single();
    }

    private long document(long connectionId, String path, boolean deleted) {
        return db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at, deleted_at)
                values (:connectionId, :path, 'title', :sha, :sha, now(), case when :deleted then now() end) returning id""")
            .param("connectionId", connectionId).param("path", path).param("sha", SHA).param("deleted", deleted).query(Long.class).single();
    }

    private void reading(long userId, long documentId) {
        db.sql("""
                insert into reading_states (user_id, document_id, last_read_blob_sha, progress_percent, anchor_json, last_read_at)
                values (:userId, :documentId, :sha, 10, '{"headingPath":[],"textPrefix":null,"blockIndex":0}', now())""")
            .param("userId", userId).param("documentId", documentId).param("sha", SHA).update();
    }

    private void bookmark(long userId, long documentId) {
        db.sql("insert into annotations (user_id, document_id, source_blob_sha, type, created_at, updated_at) values (:userId, :documentId, :sha, 'BOOKMARK', now(), now())")
            .param("userId", userId).param("documentId", documentId).param("sha", SHA).update();
    }

    private void highlight(long userId, long documentId, String mutationId) {
        long id = db.sql("""
                insert into annotations (user_id, document_id, source_blob_sha, type, created_at, updated_at, note, resolved_blob_sha)
                values (:userId, :documentId, :sha, 'HIGHLIGHT', now(), now(), 'private note text', :sha) returning id""")
            .param("userId", userId).param("documentId", documentId).param("sha", SHA).query(Long.class).single();
        for (String table : List.of("annotation_anchors", "annotation_locations")) {
            db.sql("insert into " + table + " (annotation_id, source_blob_sha, block_id, exact_text, prefix_text, suffix_text, start_offset, end_offset, heading_path) "
                    + "values (:id, :sha, 'b1', 'quoted passage', '', '', 0, 14, '[]')")
                .param("id", id).param("sha", SHA).update();
        }
        db.sql("insert into annotation_mutations (user_id, mutation_id, request_hash, annotation_id, created_at) values (:userId, cast(:mutationId as uuid), '\\x00', :id, now())")
            .param("userId", userId).param("mutationId", mutationId).param("id", id).update();
    }

    private int count(String sql) {
        return db.sql(sql).query(Integer.class).single();
    }

    private void cardWithReview(long userId, long documentId, String mutationId) {
        highlight(userId, documentId, mutationId);
        long id = db.sql("select annotation_id from annotation_mutations where user_id = :user and mutation_id = cast(:mutation as uuid)")
            .param("user", userId).param("mutation", mutationId).query(Long.class).single();
        db.sql("update annotations set type = 'CARD', question = 'Test-only question?', checked_blob_sha = :sha where id = :id")
            .param("sha", SHA).param("id", id).update();
        db.sql("insert into review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha) values (:user, cast(:mutation as uuid), :id, 4, now(), :sha)")
            .param("user", userId).param("mutation", mutationId).param("id", id).param("sha", SHA).update();
    }

    @Test void cardsAndReviewLogsAreDeletedOnDisconnectAndAccountDeletionWithoutTouchingOtherUsers() throws Exception {
        cardWithReview(1, active, "44444444-4444-4444-4444-444444444444");
        cardWithReview(1, otherNote, "55555555-5555-5555-5555-555555555555");
        cardWithReview(2, bobNote, "66666666-6666-6666-6666-666666666666");
        mvc.perform(get("/api/repositories/" + notes + "/stored-data").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.cards").value(1));
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.cards").value(1));
        assertEquals(2, count("select count(*) from review_log"));
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cards").value(1));
        assertEquals(0, count("select count(*) from review_log where user_id = 1"));
        assertEquals(1, count("select count(*) from review_log where user_id = 2"));
        assertEquals(1, count("select count(*) from annotations where user_id = 2 and type = 'CARD'"));
    }

    @Test void disconnectingDeletesOnlyThatRepositorysDataAfterShowingWhatWillGo() throws Exception {
        mvc.perform(get("/api/repositories/" + notes + "/stored-data").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(2)).andExpect(jsonPath("$.readingStates").value(2))
            .andExpect(jsonPath("$.bookmarks").value(1)).andExpect(jsonPath("$.highlights").value(1));

        db.sql("insert into repository_images (repository_connection_id, path) values (:notes, 'a.png'), (:other, 'b.png')")
            .param("notes", notes).param("other", other).update();
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.documentIds.length()").value(2))
            .andExpect(jsonPath("$.documentIds[0]").value(active)).andExpect(jsonPath("$.documentIds[1]").value(removedUpstream))
            .andExpect(jsonPath("$.readingStates").value(2)).andExpect(jsonPath("$.bookmarks").value(1)).andExpect(jsonPath("$.highlights").value(1));

        assertEquals(List.of(other, bobs), db.sql("select id from repository_connections order by id").query(Long.class).list());
        assertEquals(List.of(otherNote, bobNote), db.sql("select id from documents order by id").query(Long.class).list());
        assertEquals(List.of(otherNote, bobNote), db.sql("select document_id from reading_states order by document_id").query(Long.class).list());
        assertEquals(List.of(otherNote, bobNote), db.sql("select document_id from annotations order by document_id").query(Long.class).list());
        assertEquals(List.of("b.png"), db.sql("select path from repository_images").query(String.class).list());
        assertEquals(2, count("select count(*) from annotation_anchors"));
        assertEquals(2, count("select count(*) from annotation_locations"));
        // The deleted highlight's mutation keeps only its hash, so a late replay is answered as deleted, not recreated.
        assertEquals(1, count("select count(*) from annotation_mutations where annotation_id is null and mutation_id = '11111111-1111-1111-1111-111111111111'"));

        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repositories").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.repositories.length()").value(1)).andExpect(jsonPath("$.repositories[0].id").value(other));
    }

    @Test void anotherUsersRepositoryCannotBeInspectedOrDisconnected() throws Exception {
        mvc.perform(get("/api/repositories/" + notes + "/stored-data").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/repositories/" + notes).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/repositories/" + notes)).andExpect(status().isUnauthorized());
        assertEquals(3, count("select count(*) from repository_connections"));
        assertEquals(4, count("select count(*) from documents"));
    }

    @Test void deletingTheAccountRemovesAllItsDataSessionsAndGitHubTokenButNoOneElses() throws Exception {
        String second = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE_USER_TOKEN");
        mvc.perform(delete("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.repositories").value(2)).andExpect(jsonPath("$.readingStates").value(3))
            .andExpect(jsonPath("$.bookmarks").value(1)).andExpect(jsonPath("$.highlights").value(2));

        for (String table : List.of("repository_connections", "reading_states", "annotations", "annotation_mutations", "app_sessions", "app_sign_in_codes")) {
            assertEquals(0, count("select count(*) from " + table + " where user_id = 1"), table);
        }
        assertEquals(0, count("select count(*) from users where id = 1"));
        assertEquals(List.of(bobNote), db.sql("select id from documents").query(Long.class).list());
        assertNull(clients.loadAuthorizedClient("github", "42"), "the server keeps no GitHub token for a deleted account");
        assertNotNull(clients.loadAuthorizedClient("github", "84"));
        for (String token : List.of(alice, second)) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from annotations where user_id = 2"));
        assertEquals(1, count("select count(*) from annotation_mutations where user_id = 2"));
    }
}
