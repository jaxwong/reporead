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
                    .andExpect(jsonPath("$.snapshotCommitSha").value("c".repeat(40)))
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
        int fixedBytes =
                MarkdownRenderer.pageHtml(MarkdownRenderer.render("x", Hashes.gitBlobSha(new byte[] {'x'}), path)
                                        .page())
                                .getBytes(StandardCharsets.UTF_8)
                                .length
                        - 2
                        + path.getBytes(StandardCharsets.UTF_8).length
                        + 80; // source blob and saved image snapshot
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
            for (String field : List.of("path", "title", "sourceBlobSha", "snapshotCommitSha", "html", "text")) {
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
        int metadataBytes = (path + title + sha + sha + text).getBytes(StandardCharsets.UTF_8).length;
        String html = "h".repeat(MarkdownRenderer.MAX_PAGE_BYTES - metadataBytes);
        var accepted = new DocumentController.Content(1, path, title, sha, sha, html, text, MarkdownRenderer.FORMAT);
        assertTrue(json.writeValueAsBytes(accepted).length < 8 * 1_048_576);
        var failure = assertThrows(
                ApiFailure.class,
                () -> new DocumentController.Content(
                        1, path, title + "x", sha, sha, html, text, MarkdownRenderer.FORMAT));
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

    private ResultActions image(String bearer, long id, String path, String commitSha) throws Exception {
        return mvc.perform(get("/api/documents/" + id + "/image")
                .param("path", path)
                .param("commitSha", commitSha)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    @Test
    void repositoryImageIsServedAtTheRequestedSnapshotWithItsType() throws Exception {
        long id = document("notes/a.md", Hashes.gitBlobSha(NOTE), false);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        images.expect(requestTo(
                        "https://api.github.com/repos/test-only/notes/contents/notes/images/flow%20chart.png?ref="
                                + "b".repeat(40)))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                .andRespond(withSuccess(png, MediaType.APPLICATION_OCTET_STREAM));
        var response = image(alice, id, "notes/images/flow chart.png", "b".repeat(40))
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

    @Test
    void contentResolvesEmbedsInItsSnapshotAndKeepsMissingOrAmbiguousImagesVisible() throws Exception {
        String markdown = "![[Pasted image.png]] ![[chart.png]] ![[sub/deep.gif]] ![[missing.png]] ![[deep.gif]]";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.gitBlobSha(bytes);
        long id = document("notes/a.md", sha, false);
        images(
                "attachments/Pasted image.png",
                "notes/chart.png",
                "other/chart.png",
                "notes/sub/deep.gif",
                "x/deep.gif",
                "y/deep.gif");
        expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
        var response = read(alice, id)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var content = json.readTree(response);
        assertEquals("c".repeat(40), content.get("snapshotCommitSha").stringValue());
        var page = Jsoup.parse(content.get("html").stringValue());
        assertEquals(
                List.of(
                        "/repo-image/attachments/Pasted%20image.png",
                        "/repo-image/notes/chart.png", "/repo-image/notes/sub/deep.gif"),
                page.select("img").eachAttr("src"));
        assertEquals(2, page.select(".image-blocked").size());
        assertTrue(page.select(".image-blocked").get(0).attr("data-label").contains("No image named missing.png"));
        assertTrue(page.select(".image-blocked").get(1).attr("data-label").contains("3 images are named deep.gif"));
        // Image availability must never change the quote/offset contract.
        assertEquals(markdown, content.get("text").stringValue());
        assertEquals(markdown, page.selectFirst("[data-block-id=b0]").attr("data-anchor-text"));
        assertTrue(
                page.select("[data-embed]").isEmpty(),
                "all transient embed references are bound or visibly unavailable");
    }

    @Test
    void refreshDuringBlobFetchCannotMixNewEmbedResolutionWithAnOldSnapshot() throws Exception {
        byte[] bytes = "![[chart.png]]".getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.gitBlobSha(bytes);
        long id = document("notes/a.md", sha, false);
        images("old/chart.png");
        expectBlob(sha, request -> {
            // Simulate another request publishing an image-only edit after content captured its source.
            db.sql("delete from repository_images where repository_connection_id = 1")
                    .update();
            images("new/chart.png");
            db.sql("update repository_connections set last_synced_commit_sha = :sha where id = 1")
                    .param("sha", "d".repeat(40))
                    .update();
            return withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM).createResponse(request);
        });
        var response = read(alice, id)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var content = json.readTree(response);
        assertEquals("c".repeat(40), content.get("snapshotCommitSha").stringValue());
        assertEquals(
                "/repo-image/old/chart.png",
                Jsoup.parse(content.get("html").stringValue())
                        .selectFirst("img")
                        .attr("src"));
        next();
        expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
        response = read(alice, id)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        content = json.readTree(response);
        assertEquals("d".repeat(40), content.get("snapshotCommitSha").stringValue());
        assertEquals(
                "/repo-image/new/chart.png",
                Jsoup.parse(content.get("html").stringValue())
                        .selectFirst("img")
                        .attr("src"));
    }

    @Test
    void imagePathBindingThatExceedsThePageLimitIsTypedUnsupportedContent() throws Exception {
        String tail = "\n\n![[x.png]]";
        int fixed = MarkdownRenderer.pageHtml(MarkdownRenderer.render(
                                        "x" + tail,
                                        Hashes.gitBlobSha(("x" + tail).getBytes(StandardCharsets.UTF_8)),
                                        "a.md")
                                .page())
                        .getBytes(StandardCharsets.UTF_8)
                        .length
                - 2;
        byte[] bytes = ("x".repeat((MarkdownRenderer.MAX_PAGE_BYTES - fixed - 50) / 2) + tail)
                .getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.gitBlobSha(bytes);
        long id = document("a.md", sha, false);
        images("long/" + "x".repeat(900) + "/x.png");
        expectBlob(sha, withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
        read(alice, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
    }

    @Test
    void imagesRequireAnExplicitValidSnapshotAndNeverAcceptTheLegacyEmbedContract() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        for (String commit : List.of("", "ABC", "C".repeat(40))) {
            image(alice, id, "a.png", commit)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VERSIONS"));
        }
        mvc.perform(get("/api/documents/" + id + "/image")
                        .param("path", "a.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/" + id + "/image")
                        .param("embed", "a.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imagePathOwnershipAndDeletionAreCheckedBeforeGitHub() throws Exception {
        long id = document("a.md", Hashes.gitBlobSha(NOTE), false);
        for (String path : new String[] {"../a.png", "/a.png", "a.md", "a//b.png", ""}) {
            image(alice, id, path, "c".repeat(40))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE_PATH"));
        }
        String bob = TestSessions.signIn(sessions, clients, registrations, 84, "TEST_ONLY_BOB_USER_TOKEN");
        image(bob, id, "a.png", "c".repeat(40)).andExpect(status().isNotFound());
        image(alice, document("gone.md", Hashes.gitBlobSha(NOTE), true), "a.png", "c".repeat(40))
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
        image(alice, id, "missing.png", "c".repeat(40))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        image(alice, id, "huge.png", "c".repeat(40))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT"));
    }
}
