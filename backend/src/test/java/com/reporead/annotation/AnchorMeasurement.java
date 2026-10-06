package com.reporead.annotation;

import com.reporead.document.MarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Measures {@link Anchoring} on a real Markdown repository; it is how {@link Anchoring#MEASURED} was chosen. Opt-in and
 * read-only: runs only when REPOREAD_MEASURE_CORPUS names a local Git clone, and writes aggregate results to
 * REPOREAD_MEASURE_OUT (default build/anchor-measurement.txt) plus reviewable case samples next to it. The corpus and the
 * samples contain private notes and are never committed.
 *
 * <p>Synthetic edits on real note text have a known answer. Real edits from the repository's history are labelled
 * automatically only where the selected block survives verbatim exactly once; other real cases are written out for
 * manual review.
 */
@EnabledIfEnvironmentVariable(named = "REPOREAD_MEASURE_CORPUS", matches = ".+")
class AnchorMeasurement {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*");
    private static final int SELECTIONS_PER_NOTE = 3;

    private final Path corpus = Path.of(System.getenv("REPOREAD_MEASURE_CORPUS"));
    private final Path out = Path.of(Optional.ofNullable(System.getenv("REPOREAD_MEASURE_OUT")).orElse("build/anchor-measurement.txt"));
    /** REPOREAD_MEASURE_SEED draws a different, held-out set of selections and edits. */
    private final Random random = new Random(Long.parseLong(Optional.ofNullable(System.getenv("REPOREAD_MEASURE_SEED")).orElse("20261006")));

    /** What should happen: attach to [blockIndex, start, end) of the new version, or orphan (blockIndex < 0). */
    record Truth(int blockIndex, int start, int end) {
        static final Truth ORPHAN = new Truth(-1, 0, 0);
        boolean orphan() { return blockIndex < 0; }
    }

    record Case(String category, String path, Annotations.Anchor from, List<MarkdownRenderer.Block> after, Truth truth,
                Anchoring.Evidence evidence) {}

    enum Outcome { CORRECT, NEAR, WRONG, ORPHANED, MISSED }

    @Test void measure() throws Exception {
        var notes = new ArrayList<Note>();
        for (String path : git("ls-files", "*.md").lines().toList()) render(path, git("show", "HEAD:" + path)).ifPresent(notes::add);
        var donors = notes.stream().flatMap(note -> note.blocks().stream()).filter(block -> block.text().length() >= 40).toList();

        var cases = new ArrayList<Case>();
        for (var note : notes) synthetic(note, donors, cases);
        var reviewable = new ArrayList<Case>();
        int pairs = history(cases, reviewable);

        var report = new StringBuilder();
        report.append(timing(cases));
        report.append("Corpus: ").append(notes.size()).append(" notes at HEAD; ").append(pairs).append(" real edited versions from history\n");
        report.append("Cases: ").append(cases.size()).append(" labelled, ").append(reviewable.size()).append(" real cases for review\n\n");
        sweep(cases, report);
        // REPOREAD_MEASURE_THRESHOLDS="quoteContext,uniqueQuoteChars,fuzzyQuote,fuzzyContext;..." compares grid points;
        // the first is inspected in detail.
        var compared = Optional.ofNullable(System.getenv("REPOREAD_MEASURE_THRESHOLDS")).stream()
            .flatMap(value -> java.util.Arrays.stream(value.split(";"))).map(point -> point.split(","))
            .map(v -> new Anchoring.Thresholds(Double.parseDouble(v[0]), Integer.parseInt(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3])))
            .toList();
        var inspected = compared.isEmpty() ? Anchoring.MEASURED : compared.getFirst();
        int shouldAttach = (int) cases.stream().filter(c -> !c.truth().orphan()).count();
        for (var point : compared) {
            int wrong = 0;
            int attached = 0;
            for (var c : cases) {
                var outcome = outcome(c, point);
                if (outcome == Outcome.WRONG) wrong++;
                if (outcome == Outcome.CORRECT || outcome == Outcome.NEAR) attached++;
            }
            report.append(String.format("Compared: wrong=%d attached=%d/%d  %s%n", wrong, attached, shouldAttach, point));
        }
        report.append("\nPer category with ").append(inspected == Anchoring.MEASURED ? "MEASURED " : "").append(inspected).append(":\n");
        table(cases, inspected, report);
        reviewOutcomes(reviewable, inspected, report);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        writeSamples(cases, reviewable, inspected);
        System.out.println(report);
    }

    // ---- Corpus ----

    record Note(String path, String sha, List<MarkdownRenderer.Block> blocks) {}

    private Optional<Note> render(String path, String markdown) {
        String sha = MarkdownRenderer.blobSha(markdown.getBytes(StandardCharsets.UTF_8));
        try {
            return Optional.of(new Note(path, sha, MarkdownRenderer.render(markdown, sha, path).blocks()));
        } catch (MarkdownRenderer.ContentRejected rejected) {
            return Optional.empty();
        }
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "-C", corpus.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(false).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", arguments) + " failed in " + corpus);
        return new String(output, StandardCharsets.UTF_8);
    }

    // ---- Selections like a reader's: a word, a phrase, a sentence, or a short whole block ----

    record Selection(int blockIndex, int start, int end) {}

    private Optional<Selection> select(List<MarkdownRenderer.Block> blocks) {
        var usable = new ArrayList<Integer>();
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).text().strip().length() >= 12) usable.add(i);
        if (usable.isEmpty()) return Optional.empty();
        int index = usable.get(random.nextInt(usable.size()));
        String text = blocks.get(index).text();
        var words = new ArrayList<int[]>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) words.add(new int[] {matcher.start(), matcher.end()});
        if (words.isEmpty()) return Optional.empty();
        switch (random.nextInt(4)) {
            case 0 -> {
                var long4 = words.stream().filter(word -> word[1] - word[0] >= 4).toList();
                if (long4.isEmpty()) return Optional.empty();
                int[] word = long4.get(random.nextInt(long4.size()));
                return Optional.of(new Selection(index, word[0], word[1]));
            }
            case 1 -> {
                int length = 3 + random.nextInt(6);
                if (words.size() < length) return Optional.empty();
                int first = random.nextInt(words.size() - length + 1);
                return Optional.of(new Selection(index, words.get(first)[0], words.get(first + length - 1)[1]));
            }
            case 2 -> {
                var sentences = new ArrayList<int[]>();
                int begin = 0;
                for (int i = 0; i < text.length(); i++) {
                    if ((text.charAt(i) == '.' || text.charAt(i) == '\n') && i + 1 - begin >= 20) {
                        sentences.add(new int[] {begin, i + 1});
                        begin = i + 1;
                        while (begin < text.length() && text.charAt(begin) == ' ') begin++;
                    }
                }
                if (text.length() - begin >= 20) sentences.add(new int[] {begin, text.length()});
                var fitting = sentences.stream().filter(sentence -> sentence[1] - sentence[0] <= 300).toList();
                if (fitting.isEmpty()) return Optional.empty();
                int[] sentence = fitting.get(random.nextInt(fitting.size()));
                return Optional.of(new Selection(index, sentence[0], sentence[1]));
            }
            default -> {
                return text.length() <= 300 ? Optional.of(new Selection(index, 0, text.length())) : Optional.empty();
            }
        }
    }

    private static Annotations.Anchor anchor(Note note, Selection selection) {
        return Anchoring.anchorAt(note.sha(), note.blocks(), note.blocks().get(selection.blockIndex()), selection.start(), selection.end());
    }

    /** Renumbers block ids by position, as the renderer does for a new version. */
    private static List<MarkdownRenderer.Block> renumber(List<MarkdownRenderer.Block> blocks) {
        var result = new ArrayList<MarkdownRenderer.Block>();
        for (var block : blocks) result.add(new MarkdownRenderer.Block("b" + result.size(), block.text(), block.headingPath()));
        return result;
    }

    private static MarkdownRenderer.Block withText(MarkdownRenderer.Block block, String text) {
        return new MarkdownRenderer.Block(block.id(), text, block.headingPath());
    }

    private void add(List<Case> cases, String category, Note note, Annotations.Anchor from, List<MarkdownRenderer.Block> after, Truth truth) {
        var renumbered = renumber(after);
        cases.add(new Case(category, note.path(), from, renumbered, truth, Anchoring.evidence(from, renumbered)));
    }

    /** Whether the selected block's text occurs elsewhere in the note (then deletion has no single right answer). */
    private static boolean duplicated(Note note, int index) {
        String text = note.blocks().get(index).text();
        return note.blocks().stream().filter(block -> block.text().equals(text)).count() > 1;
    }

    // ---- Synthetic edits with a known answer ----

    private void synthetic(Note note, List<MarkdownRenderer.Block> donors, List<Case> cases) {
        for (int n = 0; n < SELECTIONS_PER_NOTE; n++) {
            var picked = select(note.blocks());
            if (picked.isEmpty()) continue;
            var selection = picked.get();
            int t = selection.blockIndex();
            var from = anchor(note, selection);
            var blocks = note.blocks();
            var target = blocks.get(t);
            String text = target.text();
            String quote = from.exactText();
            var donor = donors.get(random.nextInt(donors.size()));
            if (donor.text().equals(text)) continue;

            // Unchanged passage, shifted by an inserted block.
            var inserted = new ArrayList<>(blocks);
            int at = random.nextInt(t + 1);
            inserted.add(at, withText(donor, donor.text()));
            add(cases, "insert block before", note, from, inserted, new Truth(t + 1, selection.start(), selection.end()));

            // Unchanged passage, moved to another section (it takes that section's headings).
            if (blocks.size() > 3) {
                var moved = new ArrayList<>(blocks);
                moved.remove(t);
                int to = random.nextInt(moved.size() + 1);
                var headings = to < moved.size() ? moved.get(to).headingPath() : moved.getLast().headingPath();
                moved.add(to, new MarkdownRenderer.Block(target.id(), text, headings));
                add(cases, "move block", note, from, moved, new Truth(to, selection.start(), selection.end()));
            }

            // A word changed in the same block, outside the selection: near (inside the context) or far.
            editOutside(cases, note, from, selection, donor, true);
            editOutside(cases, note, from, selection, donor, false);

            // Light edits inside the selection: one word replaced, or a one-character typo fix.
            var words = wordsIn(text, selection.start(), selection.end());
            if (words.size() >= 4) {
                int[] word = words.get(random.nextInt(words.size()));
                String replacement = donorWord(donor);
                if (!replacement.equals(text.substring(word[0], word[1]))) {
                    var edited = new ArrayList<>(blocks);
                    edited.set(t, withText(target, text.substring(0, word[0]) + replacement + text.substring(word[1])));
                    int delta = replacement.length() - (word[1] - word[0]);
                    add(cases, "light edit: one word", note, from, edited, new Truth(t, selection.start(), selection.end() + delta));
                }
            }
            if (quote.length() >= 12) {
                int inside = selection.start() + 1 + random.nextInt(quote.length() - 2);
                var edited = new ArrayList<>(blocks);
                edited.set(t, withText(target, text.substring(0, inside) + text.substring(inside + 1)));
                add(cases, "light edit: typo", note, from, edited, new Truth(t, selection.start(), selection.end() - 1));
            }

            // The same block duplicated next to itself: the passage still sits at its position with its context, so it
            // stays there. Under another heading, the heading decides.
            var copied = new ArrayList<>(blocks);
            copied.add(t + random.nextInt(2), target);
            add(cases, "duplicate, same section", note, from, copied, new Truth(t, selection.start(), selection.end()));
            var elsewhere = blocks.stream().filter(block -> !block.headingPath().equals(target.headingPath())).findFirst();
            if (elsewhere.isPresent()) {
                var placed = new ArrayList<>(blocks);
                int where = blocks.indexOf(elsewhere.get());
                placed.add(where, new MarkdownRenderer.Block(target.id(), text, elsewhere.get().headingPath()));
                int expected = where <= t ? t + 1 : t;
                add(cases, "duplicate, other section", note, from, placed, new Truth(expected, selection.start(), selection.end()));
            }

            // The quote alone reappears in a new paragraph elsewhere; the original stays.
            var quoted = new ArrayList<>(blocks);
            int quoteAt = random.nextInt(blocks.size() + 1);
            quoted.add(quoteAt, withText(donor, "As noted, " + quote + " matters here."));
            add(cases, "quote repeated elsewhere", note, from, quoted,
                new Truth(quoteAt <= t ? t + 1 : t, selection.start(), selection.end()));

            if (duplicated(note, t)) continue;
            // The passage is gone: deleted, rewritten as another paragraph, or its words mostly replaced.
            var deleted = new ArrayList<>(blocks);
            deleted.remove(t);
            add(cases, "delete block", note, from, deleted, Truth.ORPHAN);
            var rewritten = new ArrayList<>(blocks);
            rewritten.set(t, withText(target, donor.text()));
            add(cases, "rewrite block", note, from, rewritten, Truth.ORPHAN);
            heavyEdit(cases, note, from, selection, donors);
        }
    }

    private void editOutside(List<Case> cases, Note note, Annotations.Anchor from, Selection selection, MarkdownRenderer.Block donor, boolean near) {
        var target = note.blocks().get(selection.blockIndex());
        String text = target.text();
        var candidates = new ArrayList<int[]>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            boolean outside = matcher.end() <= selection.start() || matcher.start() >= selection.end();
            int distance = matcher.end() <= selection.start() ? selection.start() - matcher.end() : matcher.start() - selection.end();
            if (outside && (near ? distance < Anchoring.CONTEXT_CHARS - 8 : distance > Anchoring.CONTEXT_CHARS + 8)) {
                candidates.add(new int[] {matcher.start(), matcher.end()});
            }
        }
        if (candidates.isEmpty()) return;
        int[] word = candidates.get(random.nextInt(candidates.size()));
        String replacement = donorWord(donor);
        if (replacement.equals(text.substring(word[0], word[1]))) return;
        var edited = new ArrayList<>(note.blocks());
        edited.set(selection.blockIndex(), withText(target, text.substring(0, word[0]) + replacement + text.substring(word[1])));
        int delta = word[1] <= selection.start() ? replacement.length() - (word[1] - word[0]) : 0;
        add(cases, near ? "edit next to the selection" : "edit far from the selection", note, from, edited,
            new Truth(selection.blockIndex(), selection.start() + delta, selection.end() + delta));
    }

    /** Replaces at least 60% of the selection's words (all of a one-word selection): the passage itself changed. */
    private void heavyEdit(List<Case> cases, Note note, Annotations.Anchor from, Selection selection, List<MarkdownRenderer.Block> donors) {
        var target = note.blocks().get(selection.blockIndex());
        String text = target.text();
        var words = wordsIn(text, selection.start(), selection.end());
        if (words.isEmpty()) return;
        int replace = Math.max(1, (int) Math.ceil(words.size() * 0.6));
        var chosen = new ArrayList<>(words);
        java.util.Collections.shuffle(chosen, random);
        var replaced = chosen.subList(0, replace).stream().sorted((a, b) -> Integer.compare(b[0], a[0])).toList();
        var edited = new StringBuilder(text);
        for (int[] word : replaced) {
            String replacement = donorWord(donors.get(random.nextInt(donors.size())));
            if (replacement.equalsIgnoreCase(text.substring(word[0], word[1]))) return;
            edited.replace(word[0], word[1], replacement);
        }
        var blocks = new ArrayList<>(note.blocks());
        blocks.set(selection.blockIndex(), withText(target, edited.toString()));
        add(cases, words.size() == 1 ? "rewrite: the selected word" : "rewrite: most selected words", note, from, blocks, Truth.ORPHAN);
    }

    private static List<int[]> wordsIn(String text, int start, int end) {
        var words = new ArrayList<int[]>();
        var matcher = WORD.matcher(text);
        matcher.region(start, end);
        while (matcher.find()) words.add(new int[] {matcher.start(), matcher.end()});
        return words;
    }

    private String donorWord(MarkdownRenderer.Block donor) {
        var words = new ArrayList<String>();
        var matcher = WORD.matcher(donor.text());
        while (matcher.find()) if (matcher.end() - matcher.start() >= 4) words.add(matcher.group());
        return words.isEmpty() ? "changed" : words.get(random.nextInt(words.size()));
    }

    // ---- Real edits from the repository's history ----

    /** Adds labelled cases for verbatim-surviving blocks; collects the rest for review. Returns the version pairs used. */
    private int history(List<Case> cases, List<Case> reviewable) throws Exception {
        int pairs = 0;
        for (String line : git("log", "--format=%H", "--reverse").lines().toList()) {
            String parents = git("rev-list", "--parents", "-n", "1", line).strip();
            String[] ids = parents.split(" ");
            if (ids.length != 2) continue;
            for (String change : git("diff-tree", "-r", "-M", "--no-commit-id", "--name-status", ids[1], ids[0]).lines().toList()) {
                String[] fields = change.split("\t");
                boolean modified = fields[0].equals("M");
                boolean renamed = fields[0].startsWith("R") && !fields[0].equals("R100");
                if (!(modified || renamed)) continue;
                String oldPath = fields[1];
                String newPath = renamed ? fields[2] : fields[1];
                if (!oldPath.toLowerCase().endsWith(".md") || !newPath.toLowerCase().endsWith(".md")) continue;
                var before = render(oldPath, git("show", ids[1] + ":" + oldPath));
                var after = render(newPath, git("show", ids[0] + ":" + newPath));
                if (before.isEmpty() || after.isEmpty()) continue;
                pairs++;
                for (int n = 0; n < SELECTIONS_PER_NOTE * 4; n++) {
                    var picked = select(before.get().blocks());
                    if (picked.isEmpty()) continue;
                    var selection = picked.get();
                    var from = anchor(before.get(), selection);
                    String blockText = before.get().blocks().get(selection.blockIndex()).text();
                    var newBlocks = after.get().blocks();
                    var survivors = new ArrayList<Integer>();
                    for (int i = 0; i < newBlocks.size(); i++) if (newBlocks.get(i).text().equals(blockText)) survivors.add(i);
                    var evidence = Anchoring.evidence(from, newBlocks);
                    if (survivors.size() == 1 && !duplicated(before.get(), selection.blockIndex())) {
                        cases.add(new Case("real edit: block unchanged", newPath, from, newBlocks,
                            new Truth(survivors.getFirst(), selection.start(), selection.end()), evidence));
                    } else if (survivors.isEmpty()) {
                        reviewable.add(new Case("real edit: block changed", newPath, from, newBlocks, null, evidence));
                    }
                }
            }
        }
        return pairs;
    }

    // ---- Cost ----

    /** Slowest real case, and a constructed worst case at the fuzzy bounds (gathering evidence is the expensive part). */
    private static String timing(List<Case> cases) {
        long slowest = 0;
        for (var c : cases) {
            long started = System.nanoTime();
            Anchoring.evidence(c.from(), c.after());
            slowest = Math.max(slowest, System.nanoTime() - started);
        }
        String quote = "lorem ipsum dolor sit amet ".repeat(Anchoring.MAX_FUZZY_QUOTE_CHARS / 27);
        var blocks = new ArrayList<MarkdownRenderer.Block>();
        for (int i = 0; i < Anchoring.MAX_FUZZY_BLOCKS; i++) {
            String text = ("lorem ipsum dolor sit amat " + i + " ").repeat(Anchoring.MAX_FUZZY_BLOCK_CHARS / 30);
            blocks.add(new MarkdownRenderer.Block("b" + i, text.substring(0, Math.min(text.length(), Anchoring.MAX_FUZZY_BLOCK_CHARS)), List.of()));
        }
        var original = new MarkdownRenderer.Block("b0", "x " + quote + " y", List.of());
        var from = Anchoring.anchorAt("a".repeat(40), List.of(original), original, 2, 2 + quote.length());
        long started = System.nanoTime();
        var evidence = Anchoring.evidence(from, blocks);
        long worst = System.nanoTime() - started;
        return String.format("Evidence time: slowest of %d cases %.1f ms; constructed worst case (%d-char quote, %d related %d-char blocks, %d fuzzy candidates) %.1f ms%n",
            cases.size(), slowest / 1e6, quote.length(), blocks.size(), Anchoring.MAX_FUZZY_BLOCK_CHARS, evidence.fuzzy().size(), worst / 1e6);
    }

    // ---- Scoring ----

    private static Outcome outcome(Case c, Anchoring.Thresholds thresholds) {
        var found = Anchoring.decide(c.evidence(), thresholds);
        if (c.truth().orphan()) return found.isEmpty() ? Outcome.ORPHANED : Outcome.WRONG;
        if (found.isEmpty()) return Outcome.MISSED;
        var candidate = found.get().candidate();
        if (!candidate.block().id().equals("b" + c.truth().blockIndex())) return Outcome.WRONG;
        if (candidate.start() == c.truth().start() && candidate.end() == c.truth().end()) return Outcome.CORRECT;
        int overlap = Math.min(candidate.end(), c.truth().end()) - Math.max(candidate.start(), c.truth().start());
        return overlap > 0 ? Outcome.NEAR : Outcome.WRONG;
    }

    private static final List<Double> CONTEXTS = List.of(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.01);
    private static final List<Integer> UNIQUE_LENGTHS = List.of(1, 4, 8, 12, 16, 20, 30, 40, 60, Integer.MAX_VALUE);
    private static final List<Double> FUZZY_QUOTES = List.of(0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.01);

    /** Every grid point with its wrong attachments and how many should-attach cases it attaches correctly or nearly. */
    private static void sweep(List<Case> cases, StringBuilder report) {
        record Point(Anchoring.Thresholds thresholds, int wrong, int attached, int shouldAttach) {}
        var points = new ArrayList<Point>();
        int shouldAttach = (int) cases.stream().filter(c -> !c.truth().orphan()).count();
        for (double quoteContext : CONTEXTS) {
            for (int unique : UNIQUE_LENGTHS) {
                for (double fuzzyQuote : FUZZY_QUOTES) {
                    for (double fuzzyContext : CONTEXTS) {
                        var thresholds = new Anchoring.Thresholds(quoteContext, unique, fuzzyQuote, fuzzyContext);
                        int wrong = 0;
                        int attached = 0;
                        for (var c : cases) {
                            var outcome = outcome(c, thresholds);
                            if (outcome == Outcome.WRONG) wrong++;
                            if (outcome == Outcome.CORRECT || outcome == Outcome.NEAR) attached++;
                        }
                        points.add(new Point(thresholds, wrong, attached, shouldAttach));
                    }
                }
            }
        }
        // For each number of wrong attachments, the most permissive-in-recall grid point (ties: strictest thresholds first).
        var frontier = new TreeMap<Integer, Point>();
        for (var point : points) {
            var best = frontier.get(point.wrong());
            if (best == null || point.attached() > best.attached()) frontier.put(point.wrong(), point);
        }
        report.append("Sweep frontier (wrong attachments -> best recall of should-attach cases):\n");
        frontier.entrySet().stream().limit(12).forEach(entry -> report.append(String.format("  wrong=%-4d attached=%d/%d  %s%n",
            entry.getKey(), entry.getValue().attached(), entry.getValue().shouldAttach(), entry.getValue().thresholds())));
        report.append("Zero-wrong grid points with recall within 0.5% of the best, strictest first:\n");
        var zero = points.stream().filter(point -> point.wrong() == 0).toList();
        int bestZero = zero.stream().mapToInt(Point::attached).max().orElse(0);
        zero.stream().filter(point -> point.attached() >= bestZero - point.shouldAttach() / 200).limit(20)
            .forEach(point -> report.append(String.format("  attached=%d  %s%n", point.attached(), point.thresholds())));
    }

    private static void table(List<Case> cases, Anchoring.Thresholds thresholds, StringBuilder report) {
        var rows = new LinkedHashMap<String, Map<Outcome, Integer>>();
        for (var c : cases) {
            rows.computeIfAbsent(c.category(), key -> new TreeMap<>()).merge(outcome(c, thresholds), 1, Integer::sum);
        }
        report.append(String.format("  %-32s %8s %6s %6s %9s %7s%n", "category", "correct", "near", "WRONG", "orphaned", "missed"));
        rows.forEach((category, counts) -> report.append(String.format("  %-32s %8d %6d %6d %9d %7d%n", category,
            counts.getOrDefault(Outcome.CORRECT, 0), counts.getOrDefault(Outcome.NEAR, 0), counts.getOrDefault(Outcome.WRONG, 0),
            counts.getOrDefault(Outcome.ORPHANED, 0), counts.getOrDefault(Outcome.MISSED, 0))));
    }

    private static void reviewOutcomes(List<Case> reviewable, Anchoring.Thresholds thresholds, StringBuilder report) {
        var counts = new TreeMap<String, Integer>();
        for (var c : reviewable) {
            counts.merge(Anchoring.decide(c.evidence(), thresholds).map(found -> found.method().name()).orElse("ORPHANED"), 1, Integer::sum);
        }
        report.append("Real edits in changed blocks (no automatic label): ").append(counts).append('\n');
    }

    /** Private samples for manual review: every real changed-block attachment, and synthetic wrong/near cases. */
    private void writeSamples(List<Case> cases, List<Case> reviewable, Anchoring.Thresholds thresholds) throws IOException {
        var samples = new StringBuilder();
        for (var c : reviewable) {
            var found = Anchoring.decide(c.evidence(), thresholds);
            if (found.isEmpty() && random.nextInt(20) != 0) continue;
            samples.append("=== ").append(c.category()).append(" | ").append(c.path()).append(" | ")
                .append(found.map(f -> f.method().name()).orElse("ORPHANED")).append('\n');
            describe(samples, c, found);
        }
        for (var c : cases) {
            var outcome = outcome(c, thresholds);
            if (outcome != Outcome.WRONG && outcome != Outcome.NEAR) continue;
            samples.append("=== ").append(outcome).append(" | ").append(c.category()).append(" | ").append(c.path())
                .append(" | expected b").append(c.truth().blockIndex()).append(" | exact candidates ")
                .append(c.evidence().exact().stream().map(e -> e.block().id() + String.format("@%.2f", e.context())).toList()).append('\n');
            describe(samples, c, Anchoring.decide(c.evidence(), thresholds));
        }
        Files.writeString(out.resolveSibling(out.getFileName() + ".samples.txt"), samples);
    }

    private static void describe(StringBuilder samples, Case c, Optional<Anchoring.Found> found) {
        samples.append("  was:  …").append(c.from().prefixText()).append('[').append(c.from().exactText()).append(']')
            .append(c.from().suffixText()).append("…  ").append(c.from().headingPath()).append('\n');
        found.ifPresent(f -> {
            var candidate = f.candidate();
            String text = candidate.block().text();
            samples.append("  now:  …").append(text, Math.max(0, candidate.start() - 32), candidate.start()).append('[')
                .append(text, candidate.start(), candidate.end()).append(']')
                .append(text, candidate.end(), Math.min(text.length(), candidate.end() + 32)).append(String.format(
                    "…  quote=%.2f context=%.2f sameHeadings=%s%n", candidate.quote(), candidate.context(), candidate.sameHeadings()));
        });
    }
}
