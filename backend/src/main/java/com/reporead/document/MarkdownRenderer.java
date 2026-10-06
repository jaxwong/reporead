package com.reporead.document;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class MarkdownRenderer {
    public static final int MAX_NOTE_BYTES = 1_048_576;
    public static final int MAX_DIAGRAM_CHARS = 20_000;
    public static final int MAX_DIAGRAMS = 16;
    public static final int MAX_EDGES = 200;
    private static final int MAX_BLOCKS = 4_096;
    private static final String BLOCK_SELECTOR = "p,h1,h2,h3,h4,h5,h6,pre,td,th,li";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(EXTENSIONS)
            .escapeHtml(true).sanitizeUrls(true).softbreak(" ").build();
    private static final Cleaner CLEANER = new Cleaner(new Safelist()
            .addTags("p", "h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "em", "strong", "del",
                    "blockquote", "pre", "code", "br", "hr", "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "input")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "title")
            .addAttributes("code", "class")
            .addAttributes("input", "type", "disabled", "checked")
            .addAttributes("ol", "start")
            .addProtocols("a", "href", "https", "http", "mailto")
            .addProtocols("img", "src", "https")
            .preserveRelativeLinks(true));

    private MarkdownRenderer() {}

    public record Block(String id, String text, List<String> headingPath) {}
    public record RenderedNote(String html, List<Block> blocks, int diagramCount) {}

    public static final class ContentRejected extends IllegalArgumentException {
        public ContentRejected(String message) { super(message); }
    }

    public static String blobSha(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("MarkdownRenderer requires the JDK SHA-1 implementation", error);
        }
    }

    public static RenderedNote render(String markdown, String sourceBlobSha, String sourceLabel) {
        Objects.requireNonNull(markdown, "MarkdownRenderer markdown");
        Objects.requireNonNull(sourceLabel, "MarkdownRenderer source label");
        if (sourceBlobSha == null || !sourceBlobSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("MarkdownRenderer sourceBlobSha must be a lowercase Git SHA-1");
        }
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NOTE_BYTES) throw new ContentRejected("Markdown exceeds the 1 MiB reader limit");
        if (!blobSha(bytes).equals(sourceBlobSha)) throw new ContentRejected("Markdown bytes do not match sourceBlobSha");

        var dirty = Jsoup.parseBodyFragment(HTML.render(PARSER.parse(markdown)), ORIGIN);
        dirty.outputSettings().prettyPrint(false);
        var clean = CLEANER.clean(dirty);
        for (var image : clean.select("img")) {
            if (!image.attr("src").equals("/assets/proof-image.svg")) {
                image.replaceWith(new Element("span").addClass("image-blocked")
                        .text("[Image blocked: " + image.attr("alt") + "]"));
            }
        }
        for (var input : clean.select("input")) {
            if (!input.attr("type").equals("checkbox")) throw new IllegalStateException("Parser emitted a non-checkbox input");
            input.attr("disabled", "");
        }
        // CommonMark already emits a newline after each br; keep that text, not a second visual break.
        for (var lineBreak : clean.select("br")) lineBreak.remove();

        String[] headings = new String[6];
        var blocks = new ArrayList<Block>();
        int diagrams = 0;
        for (var element : clean.body().getAllElements()) {
            String tag = element.tagName();
            if (tag.matches("h[1-6]")) {
                int level = tag.charAt(1) - '1';
                headings[level] = nodeText(element);
                Arrays.fill(headings, level + 1, headings.length, null);
            }
            if (!element.is(BLOCK_SELECTOR) || element.select(BLOCK_SELECTOR).size() != 1) continue;
            String text = nodeText(element);
            if (blocks.size() == MAX_BLOCKS) throw new ContentRejected("Markdown exceeds the 4096-block reader limit");
            String id = "b" + blocks.size();
            element.attr("data-block-id", id).attr("data-anchor-text", text);
            var headingPath = new ArrayList<String>();
            for (int level = 0; level < headings.length; level++) {
                if (headings[level] != null) {
                    element.attr("data-heading-" + (level + 1), headings[level]);
                    headingPath.add(headings[level]);
                }
            }
            if (tag.equals("pre") && element.selectFirst("code.language-mermaid") != null) {
                if (text.length() > MAX_DIAGRAM_CHARS) throw new ContentRejected("Mermaid exceeds the 20000 UTF-16-unit limit");
                if (++diagrams > MAX_DIAGRAMS) throw new ContentRejected("Markdown exceeds the 16-diagram reader limit");
                element.attr("data-mermaid", "");
            }
            blocks.add(new Block(id, text, List.copyOf(headingPath)));
        }

        var document = Document.createShell(ORIGIN);
        document.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
        document.head().appendElement("meta").attr("charset", "utf-8");
        document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1");
        document.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
                "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'none'; font-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'");
        document.head().appendElement("link").attr("rel", "stylesheet").attr("href", "/assets/reader.css");
        document.head().appendElement("script").attr("src", "/assets/reader.js").attr("defer", "");
        document.body().attr("data-source-blob-sha", sourceBlobSha)
                .attr("data-max-diagram-chars", Integer.toString(MAX_DIAGRAM_CHARS))
                .attr("data-max-edges", Integer.toString(MAX_EDGES));
        document.body().appendElement("p").addClass("source-label").text(sourceLabel);
        document.body().appendElement("p").id("render-status").text("Rendering diagrams…");
        var note = document.body().appendElement("main").id("note").html(clean.body().html());
        if (blocks.isEmpty()) note.appendElement("p").text("This note is empty.");
        return new RenderedNote("<!doctype html>\n" + document.outerHtml(), List.copyOf(blocks), diagrams);
    }

    private static String nodeText(org.jsoup.nodes.Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        var result = new StringBuilder();
        for (var child : node.childNodes()) result.append(nodeText(child));
        return result.toString();
    }
}
