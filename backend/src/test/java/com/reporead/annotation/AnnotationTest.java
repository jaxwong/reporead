package com.reporead.annotation;

import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.document.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnnotationTest {
    // Test-only note, users, and tokens; GitHub is mocked and only ever read.
    private static final byte[] OLD = "# Spring\n\n## Proxies\n\nBy default, Spring implements declarative transactions using a proxy around the target bean.\n"
        .getBytes(StandardCharsets.UTF_8);
    private static final String OLD_SHA = MarkdownRenderer.blobSha(OLD);
    private static final String CURRENT_SHA = "c".repeat(40);
    private static final String EXACT = "Spring implements declarative transactions";
    private static final int START = "By default, ".length();

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
    long note;

    @BeforeEach void setup() {
        TestEnvironment.reset(db);
        github = MockRestServiceServer.bindTo(githubUserApi).ignoreExpectOrder(true).build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, "TEST_ONLY_ALICE");
        db.sql("insert into repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch) values (1, 11, 7, 'test-only', 'notes', 'main')").update();
        // The note has moved on to CURRENT_SHA; annotations are created against the older version the user was reading.
        note = db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at)
                values (1, 'backend/spring.md', 'spring', :sha, :sha, now()) returning id""").param("sha", CURRENT_SHA).query(Long.class).single();
    }

    @AfterEach void verifyOnlyExpectedReads() {
        github.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private void expectSource(ExpectedCount count) {
        github.expect(count, requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + OLD_SHA))
            .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(OLD, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void next() {
        github.verify();
        github.reset();
    }

    private static String body(String mutationId, String block, int start, String exact, String note) {
        return "{\"mutationId\":\"" + mutationId + "\",\"anchor\":{\"sourceBlobSha\":\"" + OLD_SHA + "\",\"blockId\":\"" + block
            + "\",\"startOffset\":" + start + ",\"endOffset\":" + (start + exact.length()) + ",\"exactText\":\"" + exact + "\"},\"note\":"
            + (note == null ? "null" : "\"" + note + "\"") + "}";
    }

    private ResultActions create(String bearer, long documentId, String json) throws Exception {
        return mvc.perform(post("/api/documents/" + documentId + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions edit(String bearer, long id, String note, int expectedVersion) throws Exception {
        return mvc.perform(patch("/api/annotations/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + note + "\",\"expectedVersion\":" + expectedVersion + "}"));
    }

    private ResultActions remove(String bearer, long id, int expectedVersion) throws Exception {
        return mvc.perform(delete("/api/annotations/" + id).param("expectedVersion", Integer.toString(expectedVersion))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private int count(String table) {
        return db.sql("select count(*) from " + table).query(Integer.class).single();
    }

    @Test void createdAnchorIsVerifiedAgainstTheSelectedVersionAndContextComesFromTheServer() throws Exception {
        expectSource(ExpectedCount.once());
        String id = UUID.randomUUID().toString();
        create(alice, note, body(id, "b2", START, EXACT, "Proxies only intercept external calls")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("HIGHLIGHT")).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("ANCHORED"))
            .andExpect(jsonPath("$.anchor.sourceBlobSha").value(OLD_SHA))
            .andExpect(jsonPath("$.anchor.prefixText").value("By default, "))
            .andExpect(jsonPath("$.anchor.suffixText").value(" using a proxy around the target"))
            .andExpect(jsonPath("$.anchor.headingPath[1]").value("Proxies"));
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.annotations.length()").value(1)).andExpect(jsonPath("$.annotations[0].note").value("Proxies only intercept external calls"));
    }

    @Test void replayAfterALostAcknowledgementReturnsTheOriginalWithoutGitHub() throws Exception {
        expectSource(ExpectedCount.once());
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        String first = create(alice, note, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(alice, note, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, count("annotations"));
    }

    @Test void reusingAMutationIdForDifferentContentIsRejected() throws Exception {
        expectSource(ExpectedCount.once());
        String id = UUID.randomUUID().toString();
        create(alice, note, body(id, "b2", START, EXACT, "first")).andExpect(status().isCreated());
        create(alice, note, body(id, "b2", START, EXACT, "second")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUTATION_ID_REUSED"));
        assertEquals(1, count("annotations"));
    }

    @Test void concurrentDuplicateSubmissionsCreateExactlyOneAnnotation() throws Exception {
        expectSource(ExpectedCount.between(1, 4));
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, "once");
        Callable<Integer> submit = () -> create(alice, note, json).andReturn().getResponse().getStatus();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var statuses = executor.invokeAll(List.of(submit, submit, submit, submit)).stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).toList();
            assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
            assertEquals(3, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        }
        assertEquals(1, count("annotations"));
        assertEquals(1, count("annotation_anchors"));
        assertEquals(1, count("annotation_mutations"));
    }

    @Test void selectionsThatDoNotMatchTheVersionAreRejectedWithoutState() throws Exception {
        expectSource(ExpectedCount.times(3));
        create(alice, note, body(UUID.randomUUID().toString(), "b2", START + 1, EXACT, null)).andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("INVALID_ANCHOR"));
        create(alice, note, body(UUID.randomUUID().toString(), "b9", START, EXACT, null)).andExpect(status().isUnprocessableContent());
        create(alice, note, body(UUID.randomUUID().toString(), "b2", 200, "beyond", null)).andExpect(status().isUnprocessableContent());
        assertEquals(0, count("annotations"));
        assertEquals(0, count("annotation_mutations"));
    }

    @Test void malformedRequestsAreRejectedBeforeGitHub() throws Exception {
        String valid = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        for (String json : List.of(
            valid.replaceFirst("\"mutationId\":\"[^\"]+\"", "\"mutationId\":\"not-a-uuid\""),
            valid.replace(OLD_SHA, "nope"), valid.replace("\"b2\"", "\"x\""),
            body(UUID.randomUUID().toString(), "b2", START, "", null),
            valid.replace("\"endOffset\":" + (START + EXACT.length()), "\"endOffset\":" + (START + 1)),
            body(UUID.randomUUID().toString(), "b2", START, EXACT, "x".repeat(10_001)),
            "{\"mutationId\":\"" + UUID.randomUUID() + "\"}")) {
            create(alice, note, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ANNOTATION"));
        }
    }

    @Test void gitHubFailureStoresNothingAndTheSameMutationSucceedsLater() throws Exception {
        github.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + OLD_SHA)).andRespond(withServiceUnavailable());
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        create(alice, note, json).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        assertEquals(0, count("annotation_mutations"));
        next();
        expectSource(ExpectedCount.once());
        create(alice, note, json).andExpect(status().isCreated());
    }

    @Test void editsUseOptimisticVersionsAndConflictsAreVisible() throws Exception {
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, "v1"));
        edit(alice, id, "from phone", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        edit(alice, id, "stale laptop edit", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANNOTATION_CONFLICT"));
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.annotations[0].note").value("from phone"));
        mvc.perform(patch("/api/annotations/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test void deletionIsVersionedAndALateReplayCannotResurrectIt() throws Exception {
        expectSource(ExpectedCount.once());
        String json = body(UUID.randomUUID().toString(), "b2", START, EXACT, null);
        long id = createdId(json);
        remove(alice, id, 2).andExpect(status().isConflict());
        remove(alice, id, 1).andExpect(status().isNoContent());
        remove(alice, id, 1).andExpect(status().isNotFound());
        create(alice, note, json).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ANNOTATION_DELETED"));
        assertEquals(0, count("annotations"));
        assertEquals(0, count("annotation_anchors"));
    }

    @Test void annotationsArePrivateAndBookmarksAreNotEditableAsHighlights() throws Exception {
        expectSource(ExpectedCount.once());
        long id = createdId(body(UUID.randomUUID().toString(), "b2", START, EXACT, null));
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB");
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)).andExpect(status().isNotFound());
        create(bob, note, body(UUID.randomUUID().toString(), "b2", START, EXACT, null)).andExpect(status().isNotFound());
        edit(bob, id, "x", 1).andExpect(status().isNotFound());
        remove(bob, id, 1).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + note + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON).content("{\"sourceBlobSha\":\"" + CURRENT_SHA + "\"}")).andExpect(status().isOk());
        long bookmark = db.sql("select id from annotations where type = 'BOOKMARK'").query(Long.class).single();
        edit(alice, bookmark, "x", 1).andExpect(status().isNotFound());
        remove(alice, bookmark, 1).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + note + "/annotations").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
            .andExpect(jsonPath("$.annotations.length()").value(1));
    }

    private long createdId(String json) throws Exception {
        String response = create(alice, note, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll("^\\{\"id\":(\\d+),.*$", "$1"));
    }
}
