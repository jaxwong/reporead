package com.reporead.document;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.parser.IncludeSourceSpans;
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
    /** Same-origin path for an Obsidian image embed by attachment name, resolved by the server's image endpoint. */
    public static final String EMBED_PREFIX = "/repo-embed/";
    /**
     * Same-origin path the reader app intercepts to open another note: {@code target} (an Obsidian link name) or
     * {@code path} (a repository path from a relative Markdown link), and an optional {@code heading}.
     */
    public static final String NOTE_LINK = "/note-link";
    /**
     * The page format the app relies on; bumped when rendered HTML gains something a saved copy would lack. 2: note
     * links and Obsidian embeds.
     */
    public static final int FORMAT = 2;
    private static final java.util.regex.Pattern WIKILINK = java.util.regex.Pattern.compile("(!?)\\[\\[([^\\[\\]\\n]+?)\\]\\]");
    private static final int MAX_BLOCKS = 4_096;
    private static final String BLOCK_SELECTOR = "p,h1,h2,h3,h4,h5,h6,pre,td,th,li";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
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
    /** A heading block and the 0-based line of {@link #sourceLines} where it starts: its section runs to the next heading. */
    public record Heading(String blockId, int line, List<String> headingPath) {}
    public record RenderedNote(String html, List<Block> blocks, int diagramCount, List<Heading> headings) {}

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

        var parsed = PARSER.parse(markdown);
        var headingLines = new ArrayList<Integer>();
        parsed.accept(new AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Heading heading) {
                headingLines.add(heading.getSourceSpans().getFirst().getLineIndex());
                visitChildren(heading);
            }
        });
        var dirty = Jsoup.parseBodyFragment(HTML.render(parsed), ORIGIN);
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
        images = linkNotes(clean, documentPath, images);
        for (var input : clean.select("input")) {
            if (!input.attr("type").equals("checkbox")) throw new IllegalStateException("Parser emitted a non-checkbox input");
            input.attr("disabled", "");
        }
        // CommonMark already emits a newline after each br; keep that text, not a second visual break.
        for (var lineBreak : clean.select("br")) lineBreak.remove();

        String[] headings = new String[6];
        var blocks = new ArrayList<Block>();
        var headingBlocks = new ArrayList<Heading>();
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
            if (tag.matches("h[1-6]")) {
                if (headingBlocks.size() == headingLines.size()) throw new IllegalStateException("Rendered more headings than the parser found");
                headingBlocks.add(new Heading(id, headingLines.get(headingBlocks.size()), List.copyOf(headingPath)));
            }
        }
        if (headingBlocks.size() != headingLines.size()) {
            throw new IllegalStateException("Rendered " + headingBlocks.size() + " heading blocks for " + headingLines.size() + " parsed headings");
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
        return new RenderedNote("<!doctype html>\n" + document.outerHtml(), List.copyOf(blocks), diagrams, List.copyOf(headingBlocks));
    }

    /** The source split into lines as the parser numbers them: a line ends at \n, \r\n, or \r; a final line break adds no line. */
    public static List<String> sourceLines(String markdown) {
        var lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (c != '\n' && c != '\r') continue;
            lines.add(markdown.substring(start, i));
            if (c == '\r' && i + 1 < markdown.length() && markdown.charAt(i + 1) == '\n') i++;
            start = i + 1;
        }
        if (start < markdown.length()) lines.add(markdown.substring(start));
        return List.copyOf(lines);
    }

    /**
     * Obsidian {@code [[links]]} and {@code ![[embeds]]} outside code, and relative links to {@code .md} files, become
     * note links and attachment images. The original text stays in the page — what is not shown is in hidden spans — so
     * canonical block text, and every anchor into it, is unchanged. Returns the image count including embeds.
     */
    private static int linkNotes(Document clean, String documentPath, int images) {
        for (var link : clean.select("a[href]")) {
            String href = link.attr("href");
            if (href.startsWith("//") || href.startsWith("#") || href.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) continue;
            String[] parts = href.split("#", 2);
            if (!parts[0].toLowerCase(Locale.ROOT).endsWith(".md")) continue;
            var path = repositoryPath(documentPath, parts[0]);
            if (path.isEmpty()) continue;
            String heading = null;
            if (parts.length == 2 && !parts[1].isEmpty()) {
                try {
                    heading = UriUtils.decode(parts[1], StandardCharsets.UTF_8);
                } catch (IllegalArgumentException malformed) {
                    // A malformed fragment still opens the note, just not at a heading.
                    heading = null;
                }
            }
            link.attr("href", noteLink("path", path.get(), heading));
        }
        var texts = new ArrayList<TextNode>();
        for (var element : clean.body().getAllElements()) {
            if (element.closest("a, code, pre") != null) continue;
            for (var node : element.textNodes()) if (node.getWholeText().contains("[[")) texts.add(node);
        }
        for (var text : texts) {
            String whole = text.getWholeText();
            var matcher = WIKILINK.matcher(whole);
            var replacement = new ArrayList<org.jsoup.nodes.Node>();
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) replacement.add(new TextNode(whole.substring(last, matcher.start())));
                boolean embed = !matcher.group(1).isEmpty();
                String inner = matcher.group(2);
                int bar = inner.indexOf('|');
                String target = bar < 0 ? inner : inner.substring(0, bar);
                String alias = bar < 0 ? null : inner.substring(bar + 1);
                int hash = target.indexOf('#');
                String name = (hash < 0 ? target : target.substring(0, hash)).trim();
                String heading = hash < 0 ? null : target.substring(target.lastIndexOf('#') + 1).trim();
                if (heading != null && (heading.isEmpty() || heading.startsWith("^"))) heading = null;
                String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (embed && IMAGE_TYPES.containsKey(extension)) {
                    var span = new Element("span").addClass("embed").appendChild(hidden(matcher.group()));
                    if (++images > MAX_IMAGES) {
                        span.appendChild(new Element("span").addClass("image-blocked").attr("data-label", "[Image limit reached: " + name + "]"));
                    } else {
                        var image = new Element("img").attr("src", EMBED_PREFIX + UriUtils.encodePath(name, StandardCharsets.UTF_8))
                            .attr("alt", name).attr("loading", "eager");
                        if (alias != null && alias.trim().matches("\\d{1,4}(x\\d{1,4})?")) {
                            String[] size = alias.trim().split("x");
                            image.attr("width", size[0]);
                            if (size.length == 2) image.attr("height", size[1]);
                        }
                        span.appendChild(image);
                    }
                    replacement.add(span);
                } else if (name.isEmpty() && heading == null) {
                    replacement.add(new TextNode(matcher.group()));
                } else {
                    var link = new Element("a").addClass("wikilink")
                        .attr("href", name.isEmpty() ? noteLink(null, null, heading) : noteLink("target", name, heading));
                    link.appendChild(hidden(matcher.group(1) + "[["));
                    if (alias != null) link.appendChild(hidden(target + "|")).appendChild(new TextNode(alias));
                    else link.appendChild(new TextNode(target));
                    link.appendChild(hidden("]]"));
                    replacement.add(link);
                }
                last = matcher.end();
            }
            if (replacement.isEmpty()) continue;
            if (last < whole.length()) replacement.add(new TextNode(whole.substring(last)));
            for (var node : replacement) text.before(node);
            text.remove();
        }
        return images;
    }

    private static Element hidden(String text) {
        return new Element("span").addClass("wl-hidden").appendChild(new TextNode(text));
    }

    private static String noteLink(String key, String value, String heading) {
        var query = new ArrayList<String>();
        if (key != null) query.add(key + "=" + UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8));
        if (heading != null) query.add("heading=" + UriUtils.encodeQueryParam(heading, StandardCharsets.UTF_8));
        return NOTE_LINK + "?" + String.join("&", query);
    }

    private static Element blockedImage(String reason, String alt) {
        return new Element("span").addClass("image-blocked").text("[" + reason + ": " + alt + "]");
    }

    /**
     * Resolves a relative or root-relative Markdown image against the note's directory, as GitHub does.
     * Empty when the reference escapes the repository, is malformed, or is not a supported image type.
     */
    static Optional<String> repositoryImagePath(String documentPath, String src) {
        return repositoryPath(documentPath, src).filter(MarkdownRenderer::isImagePath);
    }

    /**
     * Resolves a relative or root-relative reference against the note's directory, as GitHub does. Empty when it
     * escapes the repository or is malformed.
     */
    static Optional<String> repositoryPath(String documentPath, String src) {
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
        return Optional.of(String.join("/", segments));
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
