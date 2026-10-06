package com.reporead.document;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {
    private MarkdownRenderer.RenderedNote render(String source) {
        return MarkdownRenderer.render(source, sha(source), "notes/backend/test.md");
    }

    static String sha(String source) {
        try {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError("JDK must provide SHA-1", error);
        }
    }

    @Test void paragraphQuoteUsesCanonicalUtf16Offsets() {
        var result = render("# Heading\n\nA **transaction** groups work.");
        assertEquals("A transaction groups work.", result.blocks().get(1).text());
        assertEquals("transaction", result.blocks().get(1).text().substring(2, 13));
        assertEquals(java.util.List.of("Heading"), result.blocks().get(1).headingPath());
        var html = Jsoup.parse(result.html());
        assertEquals(result.blocks().get(1).text(), html.selectFirst("[data-block-id=b1]").attr("data-anchor-text"));
    }

    @Test void unicodeAndHardBreaksArePreserved() {
        var result = render("A 😀 **café**  \nsecond line");
        assertEquals("A 😀 café\nsecond line", result.blocks().getFirst().text());
        assertEquals("café", result.blocks().getFirst().text().substring(5, 9));
    }

    @Test void tablesCodeAndTaskListsAreSupported() {
        var result = render("| Key | Value |\n| --- | --- |\n| a | **b** |\n\n```java\nint x = 1;\n```\n\n- [x] done\n- [ ] pending");
        var html = Jsoup.parse(result.html());
        assertEquals(1, html.select("table").size());
        assertEquals(1, html.select("code.language-java").size());
        assertEquals(2, html.select("input[type=checkbox][disabled]").size());
        assertTrue(result.blocks().stream().anyMatch(block -> block.text().equals("int x = 1;\n")));
        assertTrue(result.blocks().stream().anyMatch(block -> block.text().equals("b")));
    }

    @Test void rawHtmlAndUnsafeLinksAreNotExecuted() {
        var html = Jsoup.parse(render("<script>alert(1)</script>\n\n[unsafe](javascript:alert)\n\n![bad](https://example.com/tracker.png)").html());
        assertEquals(1, html.select("script").size(), "only the app-owned reader script is allowed");
        assertEquals("/assets/reader.js", html.selectFirst("script").attr("src"));
        assertTrue(html.select("a[href^=javascript:]").isEmpty());
        assertTrue(html.select("img").isEmpty());
        assertTrue(html.text().contains("Remote image blocked: bad"));
    }

    @Test void relativeImagesResolveLikeGitHubAndOthersAreVisiblyBlocked() {
        var html = Jsoup.parse(render("""
            ![diagram](images/flow%20chart.png)

            ![shared](../../assets/logo.SVG "title")

            ![root](/docs/a.webp?raw=true)

            ![remote](https://example.com/tracker.png)

            ![escape](../../../secret.png)

            ![not an image](notes.md)
            """).html());
        assertEquals(java.util.List.of("/repo-image/notes/backend/images/flow%20chart.png", "/repo-image/assets/logo.SVG", "/repo-image/docs/a.webp"),
            html.select("img").eachAttr("src"));
        assertEquals("", html.select("img").get(1).attr("title"));
        assertTrue(html.text().contains("Remote image blocked: remote"));
        assertTrue(html.text().contains("Unsupported image: escape"));
        assertTrue(html.text().contains("Unsupported image: not an image"));
    }

    @Test void imageCountIsBounded() {
        var html = Jsoup.parse(render("![i](a.png)\n\n".repeat(MarkdownRenderer.MAX_IMAGES + 1)).html());
        assertEquals(MarkdownRenderer.MAX_IMAGES, html.select("img").size());
        assertTrue(html.text().contains("Image limit reached: i"));
    }

    @Test void imagePathsMustBeNormalizedRepositoryImages() {
        assertTrue(MarkdownRenderer.isImagePath("a/b.png"));
        for (String path : new String[] {"", "/a.png", "a//b.png", "a/./b.png", "a/../b.png", "a.md", "png", "a/.png/b", "x".repeat(1025) + ".png"}) {
            assertFalse(MarkdownRenderer.isImagePath(path), path);
        }
    }

    @Test void mermaidSourceIsBoundedAndNotAnExecutableScript() {
        var result = render("```mermaid\nflowchart TD\n  A --> B\n```");
        assertEquals(1, result.diagramCount());
        assertEquals(1, Jsoup.parse(result.html()).select("pre[data-mermaid]").size());
        assertThrows(MarkdownRenderer.ContentRejected.class, () -> render("```mermaid\n" + "x".repeat(MarkdownRenderer.MAX_DIAGRAM_CHARS + 1) + "\n```"));
        assertThrows(MarkdownRenderer.ContentRejected.class, () -> render("```mermaid\nA-->B\n```\n\n".repeat(MarkdownRenderer.MAX_DIAGRAMS + 1)));
    }

    @Test void emptyNoteHasAVisibleEmptyState() {
        var result = render("");
        assertTrue(result.blocks().isEmpty());
        assertTrue(Jsoup.parse(result.html()).text().contains("This note is empty"));
    }

    @Test void oversizeAndMismatchedVersionsAreRejected() {
        assertThrows(MarkdownRenderer.ContentRejected.class, () -> render("x".repeat(MarkdownRenderer.MAX_NOTE_BYTES + 1)));
        assertThrows(MarkdownRenderer.ContentRejected.class, () -> MarkdownRenderer.render("note", "a".repeat(40), "test"));
        assertThrows(IllegalArgumentException.class, () -> MarkdownRenderer.render("note", "not-a-sha", "test"));
    }

    @Test void secondRenderIsIdenticalAndBlocksDoNotOverlap() {
        String source = "# Title\n\n- parent\n  - child\n\n> quoted **text**";
        var first = render(source);
        assertEquals(first, render(source));
        var html = Jsoup.parse(first.html());
        for (var block : html.select("[data-block-id]")) {
            assertEquals(1, block.select("[data-block-id]").size(), "anchor blocks must not overlap");
        }
    }
}
