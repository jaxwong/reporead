package com.reporead.document;

import com.reporead.Hashes;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.web.util.UriUtils;

public final class MarkdownRenderer {
    /**
     * UTF-8 bytes across a saved page's HTML, search blocks, path, title, blob SHA and image snapshot SHA. A complete 1
     * MiB payload round-trips through Room on Android 14; 2 MiB fails its CursorWindow. The remaining space covers row
     * overhead. Even sixfold JSON escaping stays below the phone's 8 MiB content-response ceiling.
     */
    public static final int MAX_PAGE_BYTES = 1_048_576;

    public static final int MAX_DIAGRAM_CHARS = 20_000;
    public static final int MAX_DIAGRAMS = 16;
    public static final int MAX_EDGES = 200;
    public static final int MAX_IMAGES = 64;
    /** Image types the reader displays; also the only paths the image endpoint serves. */
    public static final Map<String, String> IMAGE_TYPES = Map.of(
            "png",
            "image/png",
            "jpg",
            "image/jpeg",
            "jpeg",
            "image/jpeg",
            "gif",
            "image/gif",
            "webp",
            "image/webp",
            "svg",
            "image/svg+xml");
    /** Same-origin path that the Android reader serves from its cache or the authenticated image endpoint. */
    public static final String IMAGE_PREFIX = "/repo-image/";
    /**
     * Same-origin path the reader app intercepts to open another note: {@code target} (an Obsidian link name) or
     * {@code path} (a repository path from a relative Markdown link), and an optional {@code heading}.
     */
    public static final String NOTE_LINK = "/note-link";
    /**
     * The page format the app relies on; bumped when rendered HTML gains something a saved copy would lack. 2: note
     * links and Obsidian embeds. 3: footnotes. 4: capped heading paths, BOM handling and URL-only footnotes.
     */
    public static final int FORMAT = 4;

    private static final Pattern WIKILINK = Pattern.compile("(!?)\\[\\[([^\\[\\]\\n]+?)\\]\\]");
    /** A reference with a URL scheme ({@code https:}, {@code mailto:}, ...): not a path in the repository. */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:.*");
    /** A footnote reference {@code [^label]}, or with {@code :} a definition marker. */
    private static final Pattern FOOTNOTE = Pattern.compile("\\[\\^([^\\[\\]\\s]+)\\](:?)");

    public static final int MAX_BLOCKS = 4_096;
    /** Heading path entries emitted by rendered pages are cut to this many UTF-16 units to bound saved reader state. */
    public static final int MAX_HEADING_CHARS = 500;

    private static final String BLOCK_SELECTOR = "p,h1,h2,h3,h4,h5,h6,pre,td,th,li";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String APP_HOST = URI.create(ORIGIN).getHost();
    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create());
    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak(" ")
            .build();
    private static final Cleaner CLEANER = new Cleaner(new Safelist()
            .addTags(
                    "p",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "a",
                    "img",
                    "em",
                    "strong",
                    "del",
                    "blockquote",
                    "pre",
                    "code",
                    "br",
                    "hr",
                    "ul",
                    "ol",
                    "li",
                    "table",
                    "thead",
                    "tbody",
                    "tr",
                    "th",
                    "td",
                    "input")
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
    /** One operation's page DOM and canonical facts; image paths bind before the page is serialized. */
    public record RenderedDocument(Document page, List<Block> blocks, int diagramCount) {}

    public static final class ContentRejected extends IllegalArgumentException {
        public ContentRejected(String message) {
            super(message);
        }
    }

    /**
     * {@code documentPath} is the note's repository path: shown as its source label and used to resolve relative
     * images. The phases run in this order; each leaves canonical block text as the next one expects (ADR-05).
     */
    public static RenderedDocument render(String markdown, String sourceBlobSha, String documentPath) {
        checkInput(markdown, sourceBlobSha, documentPath);
        var parsed = parse(markdown);
        var clean = sanitize(parsed);
        int images = rewriteImages(clean, documentPath);
        linkNotes(clean, documentPath, images);
        markFootnotes(clean);
        tidyBlocks(clean);
        var numbered = numberBlocks(clean);
        return new RenderedDocument(
                page(clean, numbered.blocks().isEmpty(), sourceBlobSha, documentPath),
                numbered.blocks(),
                numbered.diagrams());
    }

    private static void checkInput(String markdown, String sourceBlobSha, String documentPath) {
        Objects.requireNonNull(markdown, "MarkdownRenderer markdown");
        Objects.requireNonNull(documentPath, "MarkdownRenderer document path");
        if (!Hashes.isGitSha(sourceBlobSha)) {
            throw new IllegalArgumentException("MarkdownRenderer sourceBlobSha must be a lowercase Git SHA-1");
        }
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (!Hashes.gitBlobSha(bytes).equals(sourceBlobSha))
            throw new ContentRejected("Markdown bytes do not match sourceBlobSha");
    }

    /**
     * The note as CommonMark should read it, on the same lines. A leading byte order mark is dropped: it would make the
     * first line text, losing a first heading. A footnote definition CommonMark took for a link reference definition
     * (its text is just a URL) is escaped and the note read again: as one, it would render nothing and make each
     * {@code [^label]} a link, while every other definition is paragraph text that markFootnotes marks.
     */
    private static org.commonmark.node.Node parse(String markdown) {
        String source = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        var parsed = PARSER.parse(source);
        var definitions = new ArrayList<Integer>();
        parsed.accept(new AbstractVisitor() {
            @Override
            public void visit(LinkReferenceDefinition definition) {
                if (definition.getLabel().startsWith("^")) {
                    definitions.add(source.indexOf(
                            '[', definition.getSourceSpans().getFirst().getInputIndex()));
                }
            }
        });
        if (definitions.isEmpty()) return parsed;
        var escaped = new StringBuilder(source);
        for (int at : definitions.reversed()) escaped.insert(at, '\\');
        return PARSER.parse(escaped.toString());
    }

    /** CommonMark's HTML (raw HTML escaped, URLs sanitized) through the jsoup safelist. */
    private static Document sanitize(org.commonmark.node.Node parsed) {
        var dirty = Jsoup.parseBodyFragment(HTML.render(parsed), ORIGIN);
        dirty.outputSettings().prettyPrint(false);
        return CLEANER.clean(dirty);
    }

    /**
     * Remote images are blocked; a relative or root-relative one becomes a same-origin repository image, up to
     * MAX_IMAGES. Returns the image count.
     */
    private static int rewriteImages(Document clean, String documentPath) {
        int images = 0;
        for (var image : clean.select("img")) {
            String alt = image.attr("alt");
            String src = image.attr("src");
            if (src.startsWith("//") || SCHEME.matcher(src).matches()) {
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
                image.attr("src", IMAGE_PREFIX + UriUtils.encodePath(path.get(), StandardCharsets.UTF_8))
                        .attr("loading", "eager");
            }
        }
        return images;
    }

    /**
     * Task-list checkboxes are display only. CommonMark already emits a newline after each br, so a hard break keeps
     * that newline as its text instead of a second visual break.
     */
    private static void tidyBlocks(Document clean) {
        for (var input : clean.select("input")) {
            if (!input.attr("type").equals("checkbox"))
                throw new IllegalStateException("Parser emitted a non-checkbox input");
            input.attr("disabled", "");
        }
        for (var lineBreak : clean.select("br")) lineBreak.remove();
    }

    private record Numbered(List<Block> blocks, int diagrams) {}

    /**
     * Gives each anchorable block (one with no nested candidate block) its id, canonical text and heading path, and
     * marks Mermaid diagrams within their bounds.
     */
    private static Numbered numberBlocks(Document clean) {
        String[] headings = new String[6];
        var blocks = new ArrayList<Block>();
        int diagrams = 0;
        for (var element : clean.body().getAllElements()) {
            String tag = element.tagName();
            if (tag.matches("h[1-6]")) {
                int level = tag.charAt(1) - '1';
                headings[level] = headingPathEntry(nodeText(element));
                Arrays.fill(headings, level + 1, headings.length, null);
            }
            if (!element.is(BLOCK_SELECTOR) || element.select(BLOCK_SELECTOR).size() != 1) continue;
            String text = nodeText(element);
            if (blocks.size() == MAX_BLOCKS)
                throw new ContentRejected("Markdown exceeds the " + MAX_BLOCKS + "-block reader limit");
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
                if (text.length() > MAX_DIAGRAM_CHARS)
                    throw new ContentRejected("Mermaid exceeds the " + MAX_DIAGRAM_CHARS + " UTF-16-unit limit");
                if (++diagrams > MAX_DIAGRAMS)
                    throw new ContentRejected("Markdown exceeds the " + MAX_DIAGRAMS + "-diagram reader limit");
                element.attr("data-mermaid", "");
            }
            blocks.add(new Block(id, text, List.copyOf(headingPath)));
        }
        return new Numbered(List.copyOf(blocks), diagrams);
    }

    /** The reader page around the note: CSP, the bundled script and style, and the limits the page enforces. */
    private static Document page(Document clean, boolean isEmpty, String sourceBlobSha, String documentPath) {
        var document = Document.createShell(ORIGIN);
        document.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
        document.head().appendElement("meta").attr("charset", "utf-8");
        document.head()
                .appendElement("meta")
                .attr("name", "viewport")
                .attr("content", "width=device-width, initial-scale=1");
        document.head()
                .appendElement("meta")
                .attr("http-equiv", "Content-Security-Policy")
                .attr(
                        "content",
                        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'none'; font-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'");
        document.head().appendElement("link").attr("rel", "stylesheet").attr("href", "/assets/reader.css");
        document.head().appendElement("script").attr("src", "/assets/reader.js").attr("defer", "");
        document.body()
                .attr("data-source-blob-sha", sourceBlobSha)
                .attr("data-max-diagram-chars", Integer.toString(MAX_DIAGRAM_CHARS))
                .attr("data-max-edges", Integer.toString(MAX_EDGES));
        document.body().appendElement("p").addClass("source-label").text(documentPath);
        document.body().appendElement("p").id("render-status").text("Rendering diagrams…");
        var note = document.body()
                .appendElement("main")
                .id("note")
                .appendChildren(new ArrayList<>(clean.body().childNodes()));
        if (isEmpty) note.appendElement("p").text("This note is empty.");
        return document;
    }

    /** Serializes the bound page once; DocumentController.Content owns the complete saved-page byte limit. */
    static String pageHtml(Document document) {
        return "<!doctype html>\n" + document.outerHtml();
    }

    /**
     * Obsidian {@code [[links]]} and {@code ![[embeds]]} outside code, and relative links to {@code .md} files, become
     * note links and attachment images. The original text stays in the page — what is not shown is in hidden spans — so
     * canonical block text, and every anchor into it, is unchanged. Only links made here reach the app's own origin,
     * whose paths it acts on (NOTE_LINK, the reader's tools): any other authored link there, such as a path that is not
     * a note, opens nothing. Markdown images and embeds share one image count.
     */
    private static void linkNotes(Document clean, String documentPath, int markdownImageCount) {
        rewriteMarkdownLinks(clean, documentPath);
        replaceWikilinks(wikilinkTextNodes(clean), markdownImageCount);
    }

    /** Relative Markdown links to notes become app-owned links; every other same-origin authored path opens nothing. */
    private static void rewriteMarkdownLinks(Document clean, String documentPath) {
        for (var link : clean.select("a[href]")) {
            String href = link.attr("href");
            boolean isAbsolute = href.startsWith("//") || SCHEME.matcher(href).matches();
            if (href.startsWith("#") || (isAbsolute && leavesTheApp(href))) continue;
            String[] pathAndFragment = href.split("#", 2);
            var repositoryTarget =
                    !isAbsolute && pathAndFragment[0].toLowerCase(Locale.ROOT).endsWith(".md")
                            ? repositoryPath(documentPath, pathAndFragment[0])
                            : Optional.<String>empty();
            if (repositoryTarget.isEmpty()) {
                link.removeAttr("href");
                continue;
            }
            String heading = null;
            if (pathAndFragment.length == 2 && !pathAndFragment[1].isEmpty()) {
                try {
                    heading = UriUtils.decode(pathAndFragment[1], StandardCharsets.UTF_8);
                } catch (IllegalArgumentException malformed) {
                    // A malformed fragment still opens the note, just not at a heading.
                    heading = null;
                }
            }
            link.attr("href", noteLink("path", repositoryTarget.get(), heading));
        }
    }

    /** Text nodes where wikilinks can be transformed without entering a link or code block. */
    private static List<TextNode> wikilinkTextNodes(Document clean) {
        var candidates = new ArrayList<TextNode>();
        for (var element : clean.body().getAllElements()) {
            if (element.closest("a, code, pre") != null) continue;
            for (var text : element.textNodes()) {
                if (text.getWholeText().contains("[[")) candidates.add(text);
            }
        }
        return candidates;
    }

    private record Wikilink(
            String source,
            boolean isEmbed,
            String target,
            String alias,
            String name,
            String heading,
            String extension) {}

    /** The source roles of one matched wikilink, before any DOM node is constructed. */
    private static Wikilink parseWikilink(MatchResult match) {
        String wikilinkText = match.group(2);
        int aliasSeparator = wikilinkText.indexOf('|');
        String target = aliasSeparator < 0 ? wikilinkText : wikilinkText.substring(0, aliasSeparator);
        String alias = aliasSeparator < 0 ? null : wikilinkText.substring(aliasSeparator + 1);
        int headingSeparator = target.indexOf('#');
        String name = (headingSeparator < 0 ? target : target.substring(0, headingSeparator)).trim();
        String heading = headingSeparator < 0
                ? null
                : target.substring(target.lastIndexOf('#') + 1).trim();
        if (heading != null && (heading.isEmpty() || heading.startsWith("^"))) heading = null;
        String extension =
                name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return new Wikilink(match.group(), !match.group(1).isEmpty(), target, alias, name, heading, extension);
    }

    /** Replaces wikilinks in source order, carrying the Markdown-image count through the one shared image ceiling. */
    private static void replaceWikilinks(List<TextNode> candidates, int markdownImageCount) {
        int imageCount = markdownImageCount;
        for (var text : candidates) {
            String textWithLinks = text.getWholeText();
            var matcher = WIKILINK.matcher(textWithLinks);
            var replacement = new ArrayList<Node>();
            int untouchedStart = 0;
            while (matcher.find()) {
                if (matcher.start() > untouchedStart)
                    replacement.add(new TextNode(textWithLinks.substring(untouchedStart, matcher.start())));
                var wikilink = parseWikilink(matcher.toMatchResult());
                if (wikilink.isEmbed() && IMAGE_TYPES.containsKey(wikilink.extension())) {
                    replacement.add(buildEmbed(wikilink, ++imageCount <= MAX_IMAGES));
                } else if (wikilink.name().isEmpty() && wikilink.heading() == null) {
                    replacement.add(new TextNode(wikilink.source()));
                } else {
                    var link = new Element("a")
                            .addClass("wikilink")
                            .attr(
                                    "href",
                                    wikilink.name().isEmpty()
                                            ? noteLink(null, null, wikilink.heading())
                                            : noteLink("target", wikilink.name(), wikilink.heading()));
                    link.appendChild(hidden((wikilink.isEmbed() ? "!" : "") + "[["));
                    if (wikilink.alias() != null)
                        link.appendChild(hidden(wikilink.target() + "|")).appendChild(new TextNode(wikilink.alias()));
                    else link.appendChild(new TextNode(wikilink.target()));
                    link.appendChild(hidden("]]"));
                    replacement.add(link);
                }
                untouchedStart = matcher.end();
            }
            if (replacement.isEmpty()) continue;
            if (untouchedStart < textWithLinks.length())
                replacement.add(new TextNode(textWithLinks.substring(untouchedStart)));
            replace(text, replacement);
        }
    }

    /** An image embed plus its hidden source, or the visible over-limit placeholder in the same wrapper. */
    private static Element buildEmbed(Wikilink wikilink, boolean isWithinImageLimit) {
        var embed = new Element("span").addClass("embed").appendChild(hidden(wikilink.source()));
        if (!isWithinImageLimit) {
            return embed.appendChild(new Element("span")
                    .addClass("image-blocked")
                    .attr("data-label", "[Image limit reached: " + wikilink.name() + "]"));
        }
        var image = new Element("img")
                .attr("data-embed", wikilink.name())
                .attr("alt", wikilink.name())
                .attr("loading", "eager");
        if (wikilink.alias() != null && wikilink.alias().trim().matches("\\d{1,4}(x\\d{1,4})?")) {
            String[] dimensions = wikilink.alias().trim().split("x");
            image.attr("width", dimensions[0]);
            if (dimensions.length == 2) image.attr("height", dimensions[1]);
        }
        return embed.appendChild(image);
    }

    /**
     * Footnotes as Obsidian writes them, without a parser extension: an extension would split and move definitions,
     * changing blocks of unchanged blobs (ADR-05). To CommonMark a definition {@code [^label]: text} is a paragraph,
     * and consecutive definitions are one paragraph. A paragraph that starts with a definition marker defines
     * footnotes; within it, each further marker after whitespace (a soft-broken line) starts the next. References to
     * defined labels outside code and links are marked. Footnotes are numbered in definition order: definitions cannot
     * be moved (unlike GitHub, which lists them in reference order), so their list reads 1, 2, 3. Source text stays,
     * hidden, so canonical block text is unchanged; CSS draws the numbers from {@code data-label}.
     */
    private static void markFootnotes(Document clean) {
        var definitions = new ArrayList<Element>();
        for (var paragraph : clean.select("p")) {
            if (!nodeText(paragraph).matches("(?s)\\[\\^[^\\[\\]\\s]+\\]:.*")) continue;
            for (var text : List.copyOf(paragraph.textNodes())) {
                var previous = text.previousSibling();
                boolean isAfterSpace = previous == null || nodeText(previous).matches("(?s).*\\s");
                definitions.addAll(splitFootnotes(
                        text,
                        true,
                        (whole, match) -> !match.group(2).isEmpty()
                                && (match.start() == 0
                                        ? isAfterSpace
                                        : Character.isWhitespace(whole.charAt(match.start() - 1)))));
            }
        }
        var numbers = new HashMap<String, String>();
        for (var marked : definitions)
            numbers.putIfAbsent(marked.attr("data-footnote"), Integer.toString(numbers.size() + 1));
        var references = new ArrayList<Element>();
        for (var element : clean.body().getAllElements()) {
            if (element.closest("a, code, pre, .wl-hidden") != null) continue;
            for (var text : List.copyOf(element.textNodes())) {
                references.addAll(splitFootnotes(text, false, (whole, match) -> numbers.containsKey(match.group(1))));
            }
        }
        for (var marked : references) marked.attr("data-label", numbers.get(marked.attr("data-footnote")));
        for (var marked : definitions) marked.attr("data-label", numbers.get(marked.attr("data-footnote")));
    }

    /**
     * Wraps each footnote match in {@code text} that {@code accept} takes — a definition marker {@code [^label]:} when
     * {@code isDefinition}, otherwise a reference {@code [^label]} (a following colon stays text) — and returns the new
     * elements.
     */
    private static List<Element> splitFootnotes(
            TextNode text, boolean isDefinition, BiPredicate<String, MatchResult> accept) {
        String whole = text.getWholeText();
        var matcher = FOOTNOTE.matcher(whole);
        var replacement = new ArrayList<Node>();
        var marked = new ArrayList<Element>();
        int last = 0;
        while (matcher.find()) {
            if (!accept.test(whole, matcher)) continue;
            int end = isDefinition ? matcher.end() : matcher.end(1) + 1;
            if (matcher.start() > last) replacement.add(new TextNode(whole.substring(last, matcher.start())));
            var element = new Element(isDefinition ? "span" : "sup")
                    .addClass(isDefinition ? "fn-def" : "fn-ref")
                    .attr("data-footnote", matcher.group(1))
                    .appendChild(hidden(whole.substring(matcher.start(), end)));
            replacement.add(element);
            marked.add(element);
            last = end;
        }
        if (marked.isEmpty()) return marked;
        if (last < whole.length()) replacement.add(new TextNode(whole.substring(last)));
        replace(text, replacement);
        return marked;
    }

    /**
     * Puts [nodes] where [text] was, in one insertion: jsoup renumbers every following sibling on each insert, so one
     * node at a time made a note of many links or footnotes quadratic.
     */
    private static void replace(TextNode text, List<Node> nodes) {
        ((Element) text.parentNode()).insertChildren(text.siblingIndex(), nodes);
        text.remove();
    }

    /**
     * A heading as heading paths (and the page's data-heading attributes) carry it: whole when short enough, otherwise
     * cut so that, with a closing ellipsis, it is {@code MAX_HEADING_CHARS} long. The heading block's own text is not
     * cut.
     */
    private static String headingPathEntry(String heading) {
        if (heading.length() <= MAX_HEADING_CHARS) return heading;
        int cut = MAX_HEADING_CHARS - 1;
        return heading.substring(0, splitsCharacter(heading, cut) ? cut - 1 : cut) + "…";
    }

    /** Whether a UTF-16 index falls between the two halves of a surrogate pair: half a character is stored as '?'. */
    public static boolean splitsCharacter(String text, int index) {
        return index > 0
                && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
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

    /** Whether an absolute link opens outside the app: a web or email address whose host is not the reader's. */
    private static boolean leavesTheApp(String href) {
        try {
            String host = new URI(href).getHost();
            return host == null || !host.equalsIgnoreCase(APP_HOST);
        } catch (URISyntaxException malformed) {
            return false;
        }
    }

    private static Element blockedImage(String reason, String alt) {
        return new Element("span").addClass("image-blocked").text("[" + reason + ": " + alt + "]");
    }

    /**
     * Resolves a relative or root-relative Markdown image against the note's directory, as GitHub does. Empty when the
     * reference escapes the repository, is malformed, or is not a supported image type.
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
        return dot > path.lastIndexOf('/')
                && IMAGE_TYPES.containsKey(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String nodeText(Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        var result = new StringBuilder();
        for (var child : node.childNodes()) result.append(nodeText(child));
        return result.toString();
    }
}
