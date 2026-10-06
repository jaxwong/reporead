package com.reporead.annotation;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookmarkTest {
    private static final String SHA = "a".repeat(40);

    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        TestEnvironment.register(properties);
    }

    @Autowired MockMvc mvc;
    @Autowired AppSessions sessions;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired JdbcClient db;
    String alice;
    long note;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE");
        db.sql("insert into repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch) values (1, 11, 7, 'test-only', 'notes', 'main')").update();
        note = db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at)
                values (1, 'a.md', 'a', :sha, :sha, now()) returning id""").param("sha", SHA).query(Long.class).single();
    }

    private ResultActions set(String bearer, long id, String sha) throws Exception {
        return mvc.perform(put("/api/documents/" + id + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"sourceBlobSha\":\"" + sha + "\"}"));
    }

    private ResultActions clear(String bearer, long id) throws Exception {
        return mvc.perform(delete("/api/documents/" + id + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private ResultActions list(String bearer) throws Exception {
        return mvc.perform(get("/api/bookmarks").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test void settingAndClearingAreIdempotentAndUseTheAnnotationDomain() throws Exception {
        String first = set(alice, note, SHA).andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value(note))
            .andReturn().getResponse().getContentAsString();
        assertEquals(first, set(alice, note, "b".repeat(40)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("BOOKMARK", db.sql("select type from annotations").query(String.class).single());
        list(alice).andExpect(jsonPath("$.bookmarks.length()").value(1)).andExpect(jsonPath("$.bookmarks[0].sourceBlobSha").value(SHA));
        clear(alice, note).andExpect(status().isNoContent());
        clear(alice, note).andExpect(status().isNoContent());
        list(alice).andExpect(jsonPath("$.bookmarks").isEmpty());
    }

    @Test void bookmarksArePrivateAndValidated() throws Exception {
        set(alice, note, SHA).andExpect(status().isOk());
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        set(bob, note, SHA).andExpect(status().isNotFound());
        clear(bob, note).andExpect(status().isNotFound());
        list(bob).andExpect(jsonPath("$.bookmarks").isEmpty());
        set(alice, note, "nope").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BOOKMARK"));
        set(alice, 999, SHA).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookmarks")).andExpect(status().isUnauthorized());
        assertEquals(1, db.sql("select count(*) from annotations").query(Integer.class).single());
    }
}
