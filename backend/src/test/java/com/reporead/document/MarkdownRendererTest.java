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

    @Test void headingsReportTheirSourceLineAndBlockSoChangedLinesMapToSections() {
        String source = "intro\r\n\r\n# Top\r\n\r\n```sh\r\n# not a heading\r\n```\r\n\r\nSetext\r\n---\r\n\r\n> ## Quoted\r\n\r\n- ## In list\r\n\r\n## Top\r\n";
        var result = render(source);
        assertEquals(java.util.List.of(
            new MarkdownRenderer.Heading("b1", 2, java.util.List.of("Top")),
            new MarkdownRenderer.Heading("b3", 8, java.util.List.of("Top", "Setext")),
            new MarkdownRenderer.Heading("b4", 11, java.util.List.of("Top", "Quoted")),
            new MarkdownRenderer.Heading("b5", 13, java.util.List.of("Top", "In list")),
            new MarkdownRenderer.Heading("b6", 15, java.util.List.of("Top", "Top"))), result.headings());
        var html = Jsoup.parse(result.html());
        for (var heading : result.headings()) assertTrue(html.selectFirst("[data-block-id=" + heading.blockId() + "]").tagName().matches("h[1-6]"));
        var lines = MarkdownRenderer.sourceLines(source);
        assertEquals("# Top", lines.get(2));
        assertEquals("## Top", lines.get(15));
        assertEquals(java.util.List.of(), MarkdownRenderer.sourceLines(""));
        assertEquals(java.util.List.of("a", "", "b"), MarkdownRenderer.sourceLines("a\n\rb\n"));
    }

    @Test void obsidianLinksBecomeNoteLinksWithoutChangingCanonicalText() {
        String source = "See [[stack]], [[queues|the queue note]], [[stack#Push#Pop]], [[#Local]] and [[fifo#^block1]]. Code `[[not]]`.\n\n"
            + "```\n[[not a link]]\n```\n";
        var result = render(source);
        assertEquals("See [[stack]], [[queues|the queue note]], [[stack#Push#Pop]], [[#Local]] and [[fifo#^block1]]. Code [[not]].",
            result.blocks().getFirst().text(), "brackets and targets stay in the canonical text, only hidden");
        assertEquals("[[not a link]]\n", result.blocks().get(1).text());
        var html = Jsoup.parse(result.html());
        assertEquals(java.util.List.of("/note-link?target=stack", "/note-link?target=queues", "/note-link?target=stack&heading=Pop",
                "/note-link?heading=Local", "/note-link?target=fifo"),
            html.select("a.wikilink").eachAttr("href"));
        var aliased = html.select("a.wikilink").get(1);
        assertEquals(java.util.List.of("[[", "queues|", "]]"), aliased.select(".wl-hidden").eachText());
        assertEquals("the queue note", aliased.ownText());
        assertTrue(html.select("code a, pre a").isEmpty(), "code is never linked");
        assertEquals(result.blocks().getFirst().text(), html.selectFirst("[data-block-id=b0]").attr("data-anchor-text"));
    }

    @Test void footnotesAreMarkedWithoutChangingCanonicalText() {
        // Consecutive definitions are one paragraph to CommonMark without a footnote extension, as in the real notes.
        String source = "Claim[^src] and another[^1], again[^src]. Undefined [^none]. Code `[^1]`, [link [^1]](https://example.com).\n\n"
            + "| A |\n| --- |\n| cell[^1] |\n\n"
            + "[^1]: First **bold** note.\n[^src]: Second note, https://example.com/x.\n";
        var result = render(source);
        assertEquals(java.util.List.of(
                "Claim[^src] and another[^1], again[^src]. Undefined [^none]. Code [^1], link [^1].",
                "A", "cell[^1]", "[^1]: First bold note. [^src]: Second note, https://example.com/x."),
            result.blocks().stream().map(MarkdownRenderer.Block::text).toList(), "source text stays, only hidden");
        var html = Jsoup.parse(result.html());
        // Numbered in definition order, which is the order the definitions are shown; an undefined label stays literal.
        assertEquals(java.util.List.of("src", "1", "src", "1"), html.select(".fn-ref").eachAttr("data-footnote"));
        assertEquals(java.util.List.of("2", "1", "2", "1"), html.select(".fn-ref").eachAttr("data-label"));
        assertEquals(java.util.List.of("[^src]", "[^1]", "[^src]", "[^1]"), html.select(".fn-ref > .wl-hidden").eachText());
        assertTrue(html.select("code .fn-ref, a .fn-ref").isEmpty(), "code and link text are never marked");
        assertEquals(java.util.List.of("1", "src"), html.select(".fn-def").eachAttr("data-footnote"));
        assertEquals(java.util.List.of("1", "2"), html.select(".fn-def").eachAttr("data-label"));
        assertEquals(java.util.List.of("[^1]:", "[^src]:"), html.select(".fn-def > .wl-hidden").eachText());
        for (var block : result.blocks()) {
            assertEquals(block.text(), html.selectFirst("[data-block-id=" + block.id() + "]").attr("data-anchor-text"));
        }
    }

    @Test void onlyAParagraphThatStartsWithADefinitionDefinesFootnotes() {
        var html = Jsoup.parse(render("Prose that mentions [^1]: not a definition.\n\nSee [^1].").html());
        assertTrue(html.select(".fn-def, .fn-ref").isEmpty());
    }

    @Test void obsidianImageEmbedsBecomeImagesAndNoteEmbedsBecomeLinks() {
        String source = "![[Pasted image 1.png]] and ![[diagram.svg|300]] and ![[other note]]";
        var result = render(source);
        assertEquals(source, result.blocks().getFirst().text());
        var html = Jsoup.parse(result.html());
        assertEquals(java.util.List.of(MarkdownRenderer.EMBED_PREFIX + "Pasted%20image%201.png", MarkdownRenderer.EMBED_PREFIX + "diagram.svg"),
            html.select("#note img").eachAttr("src"));
        assertEquals("300", html.select("#note img").get(1).attr("width"));
        assertEquals("Pasted image 1.png", html.selectFirst("#note img").attr("alt"));
        assertEquals(java.util.List.of("/note-link?target=other%20note"), html.select("a.wikilink").eachAttr("href"));
    }

    @Test void relativeMarkdownLinksBecomeNoteLinksResolvedAgainstTheNote() {
        var html = Jsoup.parse(render("[next](../queues/fifo%20list.md#Enqueue) [root](/top.md) [out](../../../x.md) [web](https://example.com/a.md)").html());
        assertEquals(java.util.List.of("/note-link?path=notes/queues/fifo%20list.md&heading=Enqueue", "/note-link?path=top.md",
            "../../../x.md", "https://example.com/a.md"), html.select("#note a").eachAttr("href"));
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
