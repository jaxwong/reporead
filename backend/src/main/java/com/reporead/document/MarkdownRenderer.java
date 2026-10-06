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
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MarkdownRenderer {
    public static final int MAX_NOTE_BYTES = 1_048_576;
    public static final int MAX_DIAGRAM_CHARS = 20_000;
    public static final int MAX_DIAGRAMS = 16;
    public static final int MAX_EDGES = 200;
    public static final int MAX_IMAGES = 64;
    /** Image types the reader displays; also the only paths the image endpoint serves. */
    public static final Map<String, String> IMAGE_TYPES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp", "svg", "image/svg+xml");
    /** Same-origin path that the Android reader serves from its cache or the authenticated image endpoint. */
    public static final String IMAGE_PREFIX = "/repo-image/";
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

    /** {@code documentPath} is the note's repository path: shown as its source label and used to resolve relative images. */
    public static RenderedNote render(String markdown, String sourceBlobSha, String documentPath) {
        Objects.requireNonNull(markdown, "MarkdownRenderer markdown");
        Objects.requireNonNull(documentPath, "MarkdownRenderer document path");
        if (sourceBlobSha == null || !sourceBlobSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("MarkdownRenderer sourceBlobSha must be a lowercase Git SHA-1");
        }
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NOTE_BYTES) throw new ContentRejected("Markdown exceeds the 1 MiB reader limit");
        if (!blobSha(bytes).equals(sourceBlobSha)) throw new ContentRejected("Markdown bytes do not match sourceBlobSha");

        var dirty = Jsoup.parseBodyFragment(HTML.render(PARSER.parse(markdown)), ORIGIN);
        dirty.outputSettings().prettyPrint(false);
        var clean = CLEANER.clean(dirty);
        int images = 0;
        for (var image : clean.select("img")) {
            String alt = image.attr("alt");
            String src = image.attr("src");
            if (src.startsWith("//") || src.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                image.replaceWith(blockedImage("Remote image blocked", alt));
                continue;
            }
            var path = repositoryImagePath(documentPath, src);
            if (path.isEmpty()) {
                image.replaceWith(blockedImage("Unsupported image", alt));
            } else if (++images > MAX_IMAGES) {
                image.replaceWith(blockedImage("Image limit reached", alt));
            } else {
                image.attributes().remove("title");
                image.attr("src", IMAGE_PREFIX + UriUtils.encodePath(path.get(), StandardCharsets.UTF_8)).attr("loading", "eager");
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
        document.body().appendElement("p").addClass("source-label").text(documentPath);
        document.body().appendElement("p").id("render-status").text("Rendering diagrams…");
        var note = document.body().appendElement("main").id("note").html(clean.body().html());
        if (blocks.isEmpty()) note.appendElement("p").text("This note is empty.");
        return new RenderedNote("<!doctype html>\n" + document.outerHtml(), List.copyOf(blocks), diagrams);
    }

    private static Element blockedImage(String reason, String alt) {
        return new Element("span").addClass("image-blocked").text("[" + reason + ": " + alt + "]");
    }

    /**
     * Resolves a relative or root-relative Markdown image against the note's directory, as GitHub does.
     * Empty when the reference escapes the repository, is malformed, or is not a supported image type.
     */
    static Optional<String> repositoryImagePath(String documentPath, String src) {
        String reference = src.split("[?#]", 2)[0];
        if (reference.isEmpty()) return Optional.empty();
        try {
            reference = UriUtils.decode(reference, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        var segments = new ArrayList<String>();
        if (!reference.startsWith("/")) {
            String[] directory = documentPath.split("/");
            segments.addAll(Arrays.asList(directory).subList(0, directory.length - 1));
        }
        for (String segment : reference.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return Optional.empty();
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) return Optional.empty();
        String path = String.join("/", segments);
        return isImagePath(path) ? Optional.of(path) : Optional.empty();
    }

    /** A normalized repository path (no empty, ".", or ".." segments) with a supported image extension. */
    public static boolean isImagePath(String path) {
        if (path.isEmpty() || path.length() > 1024 || path.startsWith("/") || path.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') && IMAGE_TYPES.containsKey(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String nodeText(org.jsoup.nodes.Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        var result = new StringBuilder();
        for (var child : node.childNodes()) result.append(nodeText(child));
        return result.toString();
    }
}
