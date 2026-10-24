package com.reporead.document;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class MarkdownRendererTest {
    private MarkdownRenderer.RenderedDocument render(String source) {
        return MarkdownRenderer.render(source, sha(source), "notes/backend/test.md");
    }

    private org.jsoup.nodes.Document page(MarkdownRenderer.RenderedDocument rendered) {
        return Jsoup.parse(MarkdownRenderer.pageHtml(rendered.page()));
    }

    static String sha(String source) {
        try {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError("JDK must provide SHA-1", failure);
        }
    }

    @Test
    void paragraphQuoteUsesCanonicalUtf16Offsets() {
        var result = render("# Heading\n\nA **transaction** groups work.");
        assertEquals("A transaction groups work.", result.blocks().get(1).text());
        assertEquals("transaction", result.blocks().get(1).text().substring(2, 13));
        assertEquals(java.util.List.of("Heading"), result.blocks().get(1).headingPath());
        var html = page(result);
        assertEquals(
                result.blocks().get(1).text(),
                html.selectFirst("[data-block-id=b1]").attr("data-anchor-text"));
    }

    @Test
    void headingsKeepTheirCanonicalBlocksAndPaths() {
        String source =
                "intro\r\n\r\n# Top\r\n\r\n```sh\r\n# not a heading\r\n```\r\n\r\nSetext\r\n---\r\n\r\n> ## Quoted\r\n\r\n- ## In list\r\n\r\n## Top\r\n";
        var result = render(source);
        var html = page(result);
        assertEquals(
                java.util.List.of(
                        new MarkdownRenderer.Block("b1", "Top", java.util.List.of("Top")),
                        new MarkdownRenderer.Block("b3", "Setext", java.util.List.of("Top", "Setext")),
                        new MarkdownRenderer.Block("b4", "Quoted", java.util.List.of("Top", "Quoted")),
                        new MarkdownRenderer.Block("b5", "In list", java.util.List.of("Top", "In list")),
                        new MarkdownRenderer.Block("b6", "Top", java.util.List.of("Top", "Top"))),
                result.blocks().stream()
                        .filter(block -> html.selectFirst("[data-block-id=" + block.id() + "]")
                                .tagName()
                                .matches("h[1-6]"))
                        .toList());
    }

    @Test
    void aLongHeadingKeepsItsTextButItsPathIsCappedWithoutSplittingACharacter() {
        // A paragraph directly followed by "---" is a setext heading of any length.
        String heading = "😀" + "x".repeat(MarkdownRenderer.MAX_HEADING_CHARS);
        var result = render(heading + "\n---\n\nBody.\n");
        assertEquals(heading, result.blocks().get(0).text(), "canonical text is unchanged");
        String path = result.blocks().get(1).headingPath().getFirst();
        assertTrue(path.length() <= MarkdownRenderer.MAX_HEADING_CHARS, "length " + path.length());
        assertTrue(path.endsWith("…"));
        assertTrue(heading.startsWith(path.substring(0, path.length() - 1)));
        assertEquals(path, new String(path.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(path, page(result).selectFirst("[data-block-id=b1]").attr("data-heading-2"));
    }

    /**
     * The phone decodes this exact href (BacklinksTest.theRenderersEncodingOfAPlusDecodesToAPlus): "+" stays a literal
     * plus.
     */
    @Test
    void aPlusInALinkNameStaysLiteral() {
        var html = page(render("See [[C++ templates#A+B]]."));
        assertEquals(
                "/note-link?target=C++%20templates&heading=A+B",
                html.selectFirst("a.wikilink").attr("href"));
    }

    @Test
    void obsidianLinksBecomeNoteLinksWithoutChangingCanonicalText() {
        String source =
                "See [[stack]], [[queues|the queue note]], [[stack#Push#Pop]], [[#Local]] and [[fifo#^block1]]. Code `[[not]]`.\n\n"
                        + "```\n[[not a link]]\n```\n";
        var result = render(source);
        assertEquals(
                "See [[stack]], [[queues|the queue note]], [[stack#Push#Pop]], [[#Local]] and [[fifo#^block1]]. Code [[not]].",
                result.blocks().getFirst().text(),
                "brackets and targets stay in the canonical text, only hidden");
        assertEquals("[[not a link]]\n", result.blocks().get(1).text());
        var html = page(result);
        assertEquals(
                java.util.List.of(
                        "/note-link?target=stack",
                        "/note-link?target=queues",
                        "/note-link?target=stack&heading=Pop",
                        "/note-link?heading=Local",
                        "/note-link?target=fifo"),
                html.select("a.wikilink").eachAttr("href"));
        var aliased = html.select("a.wikilink").get(1);
        assertEquals(
                java.util.List.of("[[", "queues|", "]]"),
                aliased.select(".wl-hidden").eachText());
        assertEquals("the queue note", aliased.ownText());
        assertTrue(html.select("code a, pre a").isEmpty(), "code is never linked");
        assertEquals(
                result.blocks().getFirst().text(),
                html.selectFirst("[data-block-id=b0]").attr("data-anchor-text"));
    }

    @Test
    void footnotesAreMarkedWithoutChangingCanonicalText() {
        // Consecutive definitions are one paragraph to CommonMark without a footnote extension, as in the real notes.
        String source =
                "Claim[^src] and another[^1], again[^src]. Undefined [^none]. Code `[^1]`, [link [^1]](https://example.com).\n\n"
                        + "| A |\n| --- |\n| cell[^1] |\n\n"
                        + "[^1]: First **bold** note.\n[^src]: Second note, https://example.com/x.\n";
        var result = render(source);
        assertEquals(
                java.util.List.of(
                        "Claim[^src] and another[^1], again[^src]. Undefined [^none]. Code [^1], link [^1].",
                        "A",
                        "cell[^1]",
                        "[^1]: First bold note. [^src]: Second note, https://example.com/x."),
                result.blocks().stream().map(MarkdownRenderer.Block::text).toList(),
                "source text stays, only hidden");
        var html = page(result);
        // Numbered in definition order, which is the order the definitions are shown; an undefined label stays literal.
        assertEquals(
                java.util.List.of("src", "1", "src", "1"),
                html.select(".fn-ref").eachAttr("data-footnote"));
        assertEquals(
                java.util.List.of("2", "1", "2", "1"), html.select(".fn-ref").eachAttr("data-label"));
        assertEquals(
                java.util.List.of("[^src]", "[^1]", "[^src]", "[^1]"),
                html.select(".fn-ref > .wl-hidden").eachText());
        assertTrue(html.select("code .fn-ref, a .fn-ref").isEmpty(), "code and link text are never marked");
        assertEquals(java.util.List.of("1", "src"), html.select(".fn-def").eachAttr("data-footnote"));
        assertEquals(java.util.List.of("1", "2"), html.select(".fn-def").eachAttr("data-label"));
        assertEquals(
                java.util.List.of("[^1]:", "[^src]:"),
                html.select(".fn-def > .wl-hidden").eachText());
        for (var block : result.blocks()) {
            assertEquals(
                    block.text(),
                    html.selectFirst("[data-block-id=" + block.id() + "]").attr("data-anchor-text"));
        }
    }

    @Test
    void onlyAParagraphThatStartsWithADefinitionDefinesFootnotes() {
        var html = page(render("Prose that mentions [^1]: not a definition.\n\nSee [^1]."));
        assertTrue(html.select(".fn-def, .fn-ref").isEmpty());
    }

    @Test
    void obsidianImageEmbedsBecomeImagesAndNoteEmbedsBecomeLinks() {
        String source = "![[Pasted image 1.png]] and ![[diagram.svg|300]] and ![[other note]]";
        var result = render(source);
        assertEquals(source, result.blocks().getFirst().text());
        var html = page(result);
        assertEquals(
                java.util.List.of("Pasted image 1.png", "diagram.svg"),
                html.select("#note img").eachAttr("data-embed"));
        assertEquals("300", html.select("#note img").get(1).attr("width"));
        assertEquals("Pasted image 1.png", html.selectFirst("#note img").attr("alt"));
        assertEquals(
                java.util.List.of("/note-link?target=other%20note"),
                html.select("a.wikilink").eachAttr("href"));
    }

    @Test
    void relativeMarkdownLinksBecomeNoteLinksResolvedAgainstTheNote() {
        var html = page(
                render(
                        "[next](../queues/fifo%20list.md#Enqueue) [root](/top.md) [out](../../../x.md) [web](https://example.com/a.md)"));
        assertEquals(
                java.util.List.of(
                        "/note-link?path=notes/queues/fifo%20list.md&heading=Enqueue",
                        "/note-link?path=top.md", "https://example.com/a.md"),
                html.select("#note a[href]").eachAttr("href"));
        assertEquals(
                "out", html.selectFirst("#note a:not([href])").text(), "a link out of the repository opens nothing");
    }

    @Test
    void anAuthoredLinkToTheAppsOwnPagesIsANoteOrNothing() {
        // The app decodes every /note-link it is tapped or shown, and "%" alone is not a valid escape.
        var html = page(
                render("[a](/note-link?target=50%) [b](https://appassets.androidplatform.net/note-link?target=%zz) "
                        + "[c](scripts/run.py) [d](next.md) [e](https://example.com/x) [f](#Heading)"));
        assertEquals(
                java.util.List.of("/note-link?path=notes/backend/next.md", "https://example.com/x", "#Heading"),
                html.select("#note a[href]").eachAttr("href"));
        assertEquals(
                java.util.List.of("a", "b", "c", "d", "e", "f"),
                html.select("#note a").eachText());
    }

    @Test
    void aFootnoteWhoseTextIsOnlyAUrlIsStillAFootnote() {
        // CommonMark reads "[^1]: <url>" as a link reference definition: it renders nothing and makes [^1] a link.
        var result = render("See the paper[^1].\n\n[^1]: https://arxiv.org/abs/1706.03762\n");
        assertEquals(
                java.util.List.of("See the paper[^1].", "[^1]: https://arxiv.org/abs/1706.03762"),
                result.blocks().stream().map(MarkdownRenderer.Block::text).toList());
        var html = page(result);
        assertEquals(java.util.List.of("1"), html.select(".fn-ref").eachAttr("data-label"));
        assertEquals(java.util.List.of("1"), html.select(".fn-def").eachAttr("data-label"));
        assertTrue(html.select("#note a").isEmpty());
    }

    @Test
    void aByteOrderMarkDoesNotHideTheFirstHeading() {
        var result = render("\uFEFF# Title\n\nBody\n");
        assertEquals(java.util.List.of("Title"), result.blocks().getFirst().headingPath());
        assertEquals("h1", page(result).selectFirst("[data-block-id=b0]").tagName());
        assertEquals(
                java.util.List.of("Title", "Body"),
                result.blocks().stream().map(MarkdownRenderer.Block::text).toList());
    }

    @Test
    void unicodeAndHardBreaksArePreserved() {
        var result = render("A 😀 **café**  \nsecond line");
        assertEquals("A 😀 café\nsecond line", result.blocks().getFirst().text());
        assertEquals("café", result.blocks().getFirst().text().substring(5, 9));
    }

    @Test
    void tablesCodeAndTaskListsAreSupported() {
        var result = render(
                "| Key | Value |\n| --- | --- |\n| a | **b** |\n\n```java\nint x = 1;\n```\n\n- [x] done\n- [ ] pending");
        var html = page(result);
        assertEquals(1, html.select("table").size());
        assertEquals(1, html.select("code.language-java").size());
        assertEquals(2, html.select("input[type=checkbox][disabled]").size());
        assertTrue(result.blocks().stream().anyMatch(block -> block.text().equals("int x = 1;\n")));
        assertTrue(result.blocks().stream().anyMatch(block -> block.text().equals("b")));
    }

    @Test
    void rawHtmlAndUnsafeLinksAreNotExecuted() {
        var html = page(render(
                "<script>alert(1)</script>\n\n[unsafe](javascript:alert)\n\n![bad](https://example.com/tracker.png)"));
        assertEquals(1, html.select("script").size(), "only the app-owned reader script is allowed");
        assertEquals("/assets/reader.js", html.selectFirst("script").attr("src"));
        assertTrue(html.select("a[href^=javascript:]").isEmpty());
        assertTrue(html.select("img").isEmpty());
        assertTrue(html.text().contains("Remote image blocked: bad"));
    }

    @Test
    void relativeImagesResolveLikeGitHubAndOthersAreVisiblyBlocked() {
        var html = page(render("""
            ![diagram](images/flow%20chart.png)

            ![shared](../../assets/logo.SVG "title")

            ![root](/docs/a.webp?raw=true)

            ![remote](https://example.com/tracker.png)

            ![escape](../../../secret.png)

            ![not an image](notes.md)
            """));
        assertEquals(
                java.util.List.of(
                        "/repo-image/notes/backend/images/flow%20chart.png",
                        "/repo-image/assets/logo.SVG", "/repo-image/docs/a.webp"),
                html.select("img").eachAttr("src"));
        assertEquals("", html.select("img").get(1).attr("title"));
        assertTrue(html.text().contains("Remote image blocked: remote"));
        assertTrue(html.text().contains("Unsupported image: escape"));
        assertTrue(html.text().contains("Unsupported image: not an image"));
    }

    @Test
    void imageCountIsBounded() {
        var html = page(render("![i](a.png)\n\n".repeat(MarkdownRenderer.MAX_IMAGES + 1)));
        assertEquals(MarkdownRenderer.MAX_IMAGES, html.select("img").size());
        assertTrue(html.text().contains("Image limit reached: i"));
    }

    @Test
    void imagePathsMustBeNormalizedRepositoryImages() {
        assertTrue(MarkdownRenderer.isImagePath("a/b.png"));
        for (String path : new String[] {
            "", "/a.png", "a//b.png", "a/./b.png", "a/../b.png", "a.md", "png", "a/.png/b", "x".repeat(1025) + ".png"
        }) {
            assertFalse(MarkdownRenderer.isImagePath(path), path);
        }
    }

    @Test
    void mermaidSourceIsBoundedAndNotAnExecutableScript() {
        var result = render("```mermaid\nflowchart TD\n  A --> B\n```");
        assertEquals(1, result.diagramCount());
        assertEquals(1, page(result).select("pre[data-mermaid]").size());
        assertThrows(
                MarkdownRenderer.ContentRejected.class,
                () -> render("```mermaid\n" + "x".repeat(MarkdownRenderer.MAX_DIAGRAM_CHARS + 1) + "\n```"));
        assertThrows(
                MarkdownRenderer.ContentRejected.class,
                () -> render("```mermaid\nA-->B\n```\n\n".repeat(MarkdownRenderer.MAX_DIAGRAMS + 1)));
    }

    @Test
    void emptyNoteHasAVisibleEmptyState() {
        var result = render("");
        assertTrue(result.blocks().isEmpty());
        assertTrue(page(result).text().contains("This note is empty"));
    }

    @Test
    void mismatchedVersionsAreRejected() {
        assertThrows(
                MarkdownRenderer.ContentRejected.class, () -> MarkdownRenderer.render("note", "a".repeat(40), "test"));
        assertThrows(IllegalArgumentException.class, () -> MarkdownRenderer.render("note", "not-a-sha", "test"));
    }

    @Test
    void secondRenderIsIdenticalAndBlocksDoNotOverlap() {
        String source = "# Title\n\n- parent\n  - child\n\n> quoted **text**";
        var first = render(source);
        var second = render(source);
        assertEquals(MarkdownRenderer.pageHtml(first.page()), MarkdownRenderer.pageHtml(second.page()));
        assertEquals(first.blocks(), second.blocks());
        assertEquals(first.diagramCount(), second.diagramCount());
        var html = page(first);
        for (var block : html.select("[data-block-id]")) {
            assertEquals(1, block.select("[data-block-id]").size(), "anchor blocks must not overlap");
        }
    }
}
