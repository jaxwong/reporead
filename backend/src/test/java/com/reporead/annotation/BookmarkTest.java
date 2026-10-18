package com.reporead.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class BookmarkTest extends SharedSpringTest {
    private static final String SHA = "a".repeat(40);

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
                values (1, 'a.md', 'a', :sha) returning id""").param("sha", SHA).query(Long.class).single();
    }

    private ResultActions set(String bearer, long id) throws Exception {
        return mvc.perform(
                put("/api/documents/" + id + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions clear(String bearer, long id) throws Exception {
        return mvc.perform(
                delete("/api/documents/" + id + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    /** The library: its bookmarks are this user's bookmarks. */
    private ResultActions list(String bearer) throws Exception {
        return mvc.perform(get("/api/library").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test
    void settingAndClearingAreIdempotentAndUseTheAnnotationDomain() throws Exception {
        String first = set(alice, note)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(note))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertEquals(
                first,
                set(alice, note)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertEquals(
                "BOOKMARK",
                db.sql("select type from annotations").query(String.class).single());
        list(alice)
                .andExpect(jsonPath("$.bookmarks.length()").value(1))
                .andExpect(jsonPath("$.bookmarks[0].documentId").value(note));
        clear(alice, note).andExpect(status().isNoContent());
        clear(alice, note).andExpect(status().isNoContent());
        list(alice).andExpect(jsonPath("$.bookmarks").isEmpty());
    }

    @Test
    void bookmarksArePrivate() throws Exception {
        set(alice, note).andExpect(status().isOk());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        set(bob, note).andExpect(status().isNotFound());
        clear(bob, note).andExpect(status().isNotFound());
        list(bob).andExpect(jsonPath("$.bookmarks").isEmpty());
        set(alice, 999).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + note + "/bookmark")).andExpect(status().isUnauthorized());
        assertEquals(
                1,
                db.sql("select count(*) from annotations").query(Integer.class).single());
    }
}
