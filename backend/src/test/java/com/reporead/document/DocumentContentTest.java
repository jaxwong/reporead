package com.reporead.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.reporead.ApiFailure;
import com.reporead.Hashes;
import com.reporead.SharedSpringTest;
import com.reporead.TestEnvironment;
import com.reporead.auth.AppSessions;
import com.reporead.auth.TestSessions;
import com.reporead.github.GitHubHttp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class DocumentContentTest extends SharedSpringTest {
    // Test-only note content, tokens, and repository; no live GitHub call is made or claimed.
    private static final String TOKEN = "TEST_ONLY_ALICE_USER_TOKEN";
    private static final byte[] NOTE = "# Spring\n\nA **transaction** groups work.\n\n<script>alert(1)</script>\n"
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("githubUserApi")
    RestTemplate githubUserApi;

    @Autowired
    @Qualifier("githubImageApi")
    RestTemplate githubImageApi;

    @Autowired
    OAuth2AuthorizedClientService clients;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    AppSessions sessions;

    @Autowired
    JdbcClient db;

    @Autowired
    JsonMapper json;

    MockRestServiceServer server;
    MockRestServiceServer images;
    String alice;

    @BeforeEach
    void setup() {
        TestEnvironment.reset(db);
        server = MockRestServiceServer.bindTo(githubUserApi)
                .ignoreExpectOrder(true)
                .build();
        images = MockRestServiceServer.bindTo(githubImageApi)
                .ignoreExpectOrder(true)
                .build();
        alice = TestSessions.signIn(sessions, clients, registrations, 42, TOKEN);
        db.sql("""
                insert into repository_connections (user_id, github_repository_id, owner, name, default_branch, last_synced_commit_sha)
                values (1, 11, 'test-only', 'notes', 'main', :commit)""").param("commit", "c".repeat(40)).update();
    }

    @AfterEach
    void verifyNoExtraCalls() {
        server.verify();
        images.verify();
        clients.removeAuthorizedClient("github", "42");
        clients.removeAuthorizedClient("github", "84");
    }

    private long document(String path, String blobSha, boolean deleted) {
        return db.sql("""
                insert into documents (repository_connection_id, path, title, current_blob_sha, deleted_at)
                values (1, :path, 'title', :blob, case when :deleted then now() end) returning id""")
                .param("path", path)
                .param("blob", blobSha)
                .param("deleted", deleted)
                .query(Long.class)
                .single();
    }

    private void expectBlob(String sha, ResponseCreator response) {
        server.expect(requestTo("https://api.github.com/repos/test-only/notes/git/blobs/" + sha))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                .andRespond(response);
    }

    private void next() {
        server.verify();
        server.reset();
    }

    private ResultActions read(String bearer, long id) throws Exception {
        return mvc.perform(
                get("/api/documents/" + id + "/content").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test
    void ownedNoteIsFetchedAtItsBlobShaAndRenderedSafelyOnEveryRead() throws Exception {
        String sha = Hashes.gitBlobSha(NOTE);
        long id = document("backend/spring.md", sha, false);
        expectBlob(sha, withSuccess(NOTE, MediaType.APPLICATION_OCTET_STREAM));
        expectBlob(sha, withSuccess(NOTE, MediaType.APPLICATION_OCTET_STREAM));
        for (int run = 0; run < 2; run++) {
            String body = read(alice, id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.path").value("backend/spring.md"))
                    .andExpect(jsonPath("$.sourceBlobSha").value(sha))
                    .andExpect(jsonPath("$.commitSha").doesNotExist())
                    .andExpect(
                            jsonPath("$.text").value("Spring\nA transaction groups work.\n<script>alert(1)</script>"))
                    .andExpect(jsonPath("$.renderFormat").value(MarkdownRenderer.FORMAT))
                    .andExpect(
                            content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TOKEN"))))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var html = Jsoup.parse(json.readTree(body).get("html").stringValue());
            assertEquals(
                    "A transaction groups work.",
                    html.selectFirst("[data-block-id=b1]").attr("data-anchor-text"));
            assertEquals(1, html.select("script").size(), "only the app-owned reader script");
            assertEquals(sha, html.body().attr("data-source-blob-sha"));
        }
    }

    @Test
    void anotherUsersNoteIsNotFoundWithoutAGitHubCall() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        read(bob, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        read(alice, 999).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + id + "/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletedNoteIsGoneWithoutAGitHubCall() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), true);
        read(alice, id)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DELETED"));
    }

    @Test
    void bytesThatDoNotMatchTheBlobShaAreRejected() throws Exception {
        String sha = Hashes.gitBlobSha(NOTE);
        long id = document("a.md", sha, false);
        expectBlob(
                sha, withSuccess("# Different".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM));
        read(alice, id)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GITHUB_INVALID_RESPONSE"));
    }

    @Test
    void anEmptyNoteIsShownAsEmpty() throws Exception {
        // GitHub answers an empty file with a 200 and no body; its blob SHA is Git's empty-blob hash.
        byte[] empty = new byte[0];
        String sha = Hashes.gitBlobSha(empty);
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", sha);
        long id = document("k8s/helm.md", sha, false);
        expectBlob(sha, withSuccess(empty, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("This note is empty.")));
    }

    @Test
    void oversizedAndNonUtf8NotesAreVisiblyUnsupported() throws Exception {
        byte[] large = new byte[GitHubHttp.MAX_RESPONSE_BYTES + 1];
        java.util.Arrays.fill(large, (byte) 'x');
        String largeSha = Hashes.gitBlobSha(large);
        expectBlob(largeSha, withSuccess(large, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, document("large.md", largeSha, false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));

        byte[] latin1 = {(byte) 0xE9, 't', 'e'};
        String latin1Sha = Hashes.gitBlobSha(latin1);
        next();
        expectBlob(latin1Sha, withSuccess(latin1, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, document("latin1.md", latin1Sha, false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
    }

    @Test
    void contentAtTheCompletePageLimitIsDeliveredButOneMoreMetadataByteIsUnsupported() throws Exception {
        String path = "test-only/測定.md";
        int fixedBytes = MarkdownRenderer.render("x", Hashes.gitBlobSha(new byte[] {'x'}), path)
                        .html()
                        .getBytes(StandardCharsets.UTF_8)
                        .length
                - 2
                + path.getBytes(StandardCharsets.UTF_8).length
                + 40;
        // Each source character occurs in HTML twice and in search text once; use the title for the remainder.
        int available = MarkdownRenderer.MAX_PAGE_BYTES - fixedBytes - 1;
        byte[] bytes = "x".repeat(available / 3).getBytes(StandardCharsets.UTF_8);
        String title = "T".repeat(1 + available % 3);
        String sha = Hashes.gitBlobSha(bytes);
        long id = document(path, sha, false);
        db.sql("update documents set title = :title where id = :id")
                .param("title", title)
                .param("id", id)
                .update();
        for (int run = 0; run < 2; run++) {
            next();
            expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
            String body = read(alice, id)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var content = json.readTree(body);
            long storedBytes = 0;
            for (String field : List.of("path", "title", "sourceBlobSha", "html", "text")) {
                storedBytes += content.get(field).stringValue().getBytes(StandardCharsets.UTF_8).length;
            }
            assertEquals(MarkdownRenderer.MAX_PAGE_BYTES, storedBytes);
        }
        db.sql("update documents set title = :title where id = :id")
                .param("title", title + "x")
                .param("id", id)
                .update();
        next();
        expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
    }

    @Test
    void completePageLimitIncludesSearchTextAndEveryMetadataStringInUtf8() {
        String path = "test-only/測定.md";
        String title = "測😀";
        String sha = "a".repeat(40);
        // Worst-case JSON escaping still fits the existing 8 MiB transport bound.
        String text = "\u0001".repeat(500_000);
        int metadataBytes = (path + title + sha + text).getBytes(StandardCharsets.UTF_8).length;
        String html = "h".repeat(MarkdownRenderer.MAX_PAGE_BYTES - metadataBytes);
        var accepted = new DocumentController.Content(1, path, title, sha, html, text, MarkdownRenderer.FORMAT);
        assertTrue(json.writeValueAsBytes(accepted).length < 8 * 1_048_576);
        var failure = assertThrows(
                ApiFailure.class,
                () -> new DocumentController.Content(1, path, title + "x", sha, html, text, MarkdownRenderer.FORMAT));
        assertEquals(ApiFailure.Code.UNSUPPORTED_CONTENT, failure.code);
    }

    @Test
    void smallSourcesWhoseRenderedPagesExpandPastStorageLimitsAreUnsupported() throws Exception {
        // Synthetic notes: heading paths are repeated on every block, and HTML escaping expands them again.
        for (String heading : List.of("H".repeat(500), "\"&".repeat(250))) {
            for (int paragraphs : List.of(1000, 3000)) {
                var source = new StringBuilder();
                for (int level = 1; level <= 6; level++) {
                    source.append("#".repeat(level)).append(' ').append(heading).append("\n\n");
                }
                source.append("paragraph\n\n".repeat(paragraphs));
                byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
                assertTrue(bytes.length < GitHubHttp.MAX_RESPONSE_BYTES);
                String sha = Hashes.gitBlobSha(bytes);
                next();
                expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
                read(alice, document("expanding-" + paragraphs + "-" + heading.charAt(0) + ".md", sha, false))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
            }
        }
    }

    @Test
    void renderLimitRejectionIsVisible(CapturedOutput log) throws Exception {
        byte[] diagrams = "```mermaid\nA-->B\n```\n\n"
                .repeat(MarkdownRenderer.MAX_DIAGRAMS + 1)
                .getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.gitBlobSha(diagrams);
        expectBlob(sha, withSuccess(diagrams, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, document("diagrams.md", sha, false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Markdown exceeds the 16-diagram reader limit"));
        // The log says which limit, not only the code: messages hold no credentials or note content.
        assertTrue(
                log.getOut().contains("code=UNSUPPORTED_CONTENT message=Markdown exceeds the 16-diagram reader limit"));
    }

    @Test
    void vanishedBlobAndGitHubOutageAreDistinct() throws Exception {
        String sha = Hashes.gitBlobSha(NOTE);
        long id = document("a.md", sha, false);
        expectBlob(sha, withResourceNotFound());
        read(alice, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        server.verify();
        server.reset();
        expectBlob(sha, withServiceUnavailable());
        read(alice, id)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test
    void missingGitHubTokenRequiresSignInWithoutACall() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        clients.removeAuthorizedClient("github", "42");
        read(alice, id)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    }

    // ---- Changes since the version the user last read ----

    private static final byte[] READ =
            "# Spring\n\nIntro.\n\n## Transactions\n\nPropagation REQUIRED.\n\n## Caching\n\nUse a TTL.\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CURRENT =
            "# Spring\n\nIntro.\n\n## Transactions\n\nPropagation REQUIRED.\n\nIsolation READ COMMITTED.\n\n## Retries\n\nBack off.\n"
                    .getBytes(StandardCharsets.UTF_8);

    private ResultActions changes(String bearer, long id, String since, String to) throws Exception {
        var request = get("/api/documents/" + id + "/changes").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (since != null) request.param("since", since);
        if (to != null) request.param("to", to);
        return mvc.perform(request);
    }

    @Test
    void changesSinceTheReadVersionAreSummarizedBySectionsOfTheDisplayedVersion() throws Exception {
        String read = Hashes.gitBlobSha(READ);
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("backend/spring.md", current, false);
        expectBlob(read, withSuccess(READ, MediaType.APPLICATION_OCTET_STREAM));
        expectBlob(current, withSuccess(CURRENT, MediaType.APPLICATION_OCTET_STREAM));
        changes(alice, id, read, current)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.sinceBlobSha").value(read))
                .andExpect(jsonPath("$.toBlobSha").value(current))
                .andExpect(jsonPath("$.status").value("CHANGED"))
                .andExpect(jsonPath("$.addedLines").value(4))
                .andExpect(jsonPath("$.removedLines").value(2))
                .andExpect(jsonPath("$.sections.length()").value(3))
                .andExpect(jsonPath("$.sections[0].change").value("CHANGED"))
                .andExpect(jsonPath("$.sections[0].headingPath[1]").value("Transactions"))
                .andExpect(jsonPath("$.sections[0].blockId").value("b2"))
                .andExpect(jsonPath("$.sections[1].change").value("REMOVED"))
                .andExpect(jsonPath("$.sections[1].headingPath[1]").value("Caching"))
                .andExpect(jsonPath("$.sections[1].blockId").doesNotExist())
                .andExpect(jsonPath("$.sections[2].change").value("ADDED"))
                .andExpect(jsonPath("$.sections[2].headingPath[1]").value("Retries"))
                .andExpect(jsonPath("$.sections[2].blockId").value("b5"));
        // The block ids are the displayed version's: b2 and b5 are those headings in the reader's page.
        next();
        expectBlob(current, withSuccess(CURRENT, MediaType.APPLICATION_OCTET_STREAM));
        var html = Jsoup.parse(
                json.readTree(read(alice, id).andReturn().getResponse().getContentAsString())
                        .get("html")
                        .stringValue());
        assertEquals("Transactions", html.selectFirst("[data-block-id=b2]").text());
        assertEquals("Retries", html.selectFirst("[data-block-id=b5]").text());
    }

    @Test
    void theSameVersionIsUnchangedWithoutAGitHubCall() throws Exception {
        String sha = Hashes.gitBlobSha(READ);
        long id = document("a.md", sha, false);
        changes(alice, id, sha, sha)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNCHANGED"))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void anOldVersionGitHubNoLongerHasIsUnavailableNotAnEmptySummary() throws Exception {
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("a.md", current, false);
        expectBlob("e".repeat(40), withResourceNotFound());
        changes(alice, id, "e".repeat(40), current)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SINCE_UNAVAILABLE"))
                .andExpect(
                        jsonPath("$.reason").value("GitHub no longer has this note version; refresh the repository."))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void anOldVersionThatIsNoLongerAReadableNoteIsUnavailable() throws Exception {
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("a.md", current, false);
        byte[] latin1 = "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
        String old = Hashes.gitBlobSha(latin1);
        expectBlob(old, withSuccess(latin1, MediaType.APPLICATION_OCTET_STREAM));
        changes(alice, id, old, current)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SINCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("This note is not valid UTF-8 text."));
    }

    @Test
    void anOutageReadingTheOldVersionFailsTheComparisonInsteadOfCallingItUnavailable() throws Exception {
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("a.md", current, false);
        expectBlob("e".repeat(40), withServiceUnavailable());
        changes(alice, id, "e".repeat(40), current)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test
    void anEditTooLargeToSummarizeSaysSoInsteadOfAPartialList() throws Exception {
        var before = new StringBuilder("# Big\n");
        var after = new StringBuilder("# Big\n");
        for (int i = 0; i <= NoteChanges.MAX_CHANGED_LINES / 2; i++) {
            before.append("old ").append(i).append('\n');
            after.append("new ").append(i).append('\n');
        }
        byte[] old = before.toString().getBytes(StandardCharsets.UTF_8);
        byte[] current = after.toString().getBytes(StandardCharsets.UTF_8);
        long id = document("a.md", Hashes.gitBlobSha(current), false);
        expectBlob(Hashes.gitBlobSha(old), withSuccess(old, MediaType.APPLICATION_OCTET_STREAM));
        expectBlob(Hashes.gitBlobSha(current), withSuccess(current, MediaType.APPLICATION_OCTET_STREAM));
        changes(alice, id, Hashes.gitBlobSha(old), Hashes.gitBlobSha(current))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TOO_LARGE"))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void aGitHubOutageFailsTheComparisonInsteadOfReportingAState() throws Exception {
        String read = Hashes.gitBlobSha(READ);
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("a.md", current, false);
        expectBlob(read, withServiceUnavailable());
        changes(alice, id, read, current)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
        next();
        expectBlob(read, withSuccess(READ, MediaType.APPLICATION_OCTET_STREAM));
        expectBlob(current, withServiceUnavailable());
        changes(alice, id, read, current)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_UNAVAILABLE"));
    }

    @Test
    void changesAreCheckedForOwnershipDeletionAndVersionsBeforeGitHub() throws Exception {
        String read = Hashes.gitBlobSha(READ);
        String current = Hashes.gitBlobSha(CURRENT);
        long id = document("a.md", current, false);
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        changes(bob, id, read, current).andExpect(status().isNotFound());
        changes(alice, document("gone.md", current, true), read, current)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DELETED"));
        for (String[] versions :
                new String[][] {{null, current}, {read, null}, {"ABC", current}, {read, current.toUpperCase()}}) {
            changes(alice, id, versions[0], versions[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VERSIONS"));
        }
    }

    private ResultActions image(String bearer, long id, String path) throws Exception {
        return mvc.perform(get("/api/documents/" + id + "/image")
                .param("path", path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test
    void repositoryImageIsServedAtTheNotesCommitWithItsType() throws Exception {
        long id = document("notes/a.md", Hashes.gitBlobSha(NOTE), false);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        images.expect(requestTo(
                        "https://api.github.com/repos/test-only/notes/contents/notes/images/flow%20chart.png?ref="
                                + "c".repeat(40)))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                .andRespond(withSuccess(png, MediaType.APPLICATION_OCTET_STREAM));
        var response = image(alice, id, "notes/images/flow chart.png")
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andReturn()
                .getResponse();
        assertArrayEquals(png, response.getContentAsByteArray());
    }

    private void images(String... paths) {
        for (String path : paths)
            db.sql("insert into repository_images (repository_connection_id, path) values (1, :path)")
                    .param("path", path)
                    .update();
    }

    private ResultActions embed(long id, String name) throws Exception {
        return mvc.perform(get("/api/documents/" + id + "/image")
                .param("embed", name)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice));
    }

    @Test
    void anObsidianEmbedIsFoundByNamePreferringTheNotesFolder() throws Exception {
        long id = document("notes/a.md", Hashes.gitBlobSha(NOTE), false);
        images(
                "attachments/Pasted image.png",
                "notes/chart.png",
                "other/chart.png",
                "notes/sub/deep.gif",
                "x/deep.gif",
                "y/deep.gif");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        for (String path : List.of("attachments/Pasted%20image.png", "notes/chart.png", "notes/sub/deep.gif")) {
            images.expect(requestTo(
                            "https://api.github.com/repos/test-only/notes/contents/" + path + "?ref=" + "c".repeat(40)))
                    .andRespond(withSuccess(png, MediaType.APPLICATION_OCTET_STREAM));
        }
        embed(id, "Pasted image.png").andExpect(status().isOk());
        embed(id, "chart.png").andExpect(status().isOk());
        embed(id, "sub/deep.gif").andExpect(status().isOk());
        // Not found and ambiguous are answered from the stored list, without GitHub.
        embed(id, "missing.png")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        embed(id, "deep.gif")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMAGE_AMBIGUOUS"));
        embed(id, "notes.md")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_PATH"));
        mvc.perform(get("/api/documents/" + id + "/image")
                        .param("embed", "chart.png")
                        .param("path", "notes/chart.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imagePathOwnershipAndDeletionAreCheckedBeforeGitHub() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        for (String path : new String[] {"../a.png", "/a.png", "a.md", "a//b.png", ""}) {
            image(alice, id, path)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE_PATH"));
        }
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        image(bob, id, "a.png").andExpect(status().isNotFound());
        image(alice, document("gone.md", Hashes.gitBlobSha(NOTE), true), "a.png")
                .andExpect(status().isGone());
    }

    @Test
    void missingAndOversizedImagesAreDistinctFailures() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        images.expect(requestTo(
                        "https://api.github.com/repos/test-only/notes/contents/missing.png?ref=" + "c".repeat(40)))
                .andRespond(withResourceNotFound());
        images.expect(requestTo("https://api.github.com/repos/test-only/notes/contents/huge.png?ref=" + "c".repeat(40)))
                .andRespond(withSuccess(
                        new byte[GitHubHttp.MAX_IMAGE_RESPONSE_BYTES + 1], MediaType.APPLICATION_OCTET_STREAM));
        image(alice, id, "missing.png")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        image(alice, id, "huge.png")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
    }
}
