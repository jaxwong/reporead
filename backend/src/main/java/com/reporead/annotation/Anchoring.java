package com.reporead.annotation;

import com.reporead.document.MarkdownRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds an anchored passage in another version of its note, or decides that it cannot be found reliably. Pure: the
 * caller supplies the version's canonical blocks. Stages, in order: the passage's block unchanged (anywhere); unchanged
 * position with unchanged context; the exact quote, where context and heading must single out one occurrence; a bounded
 * fuzzy match that must be equally unique.
 * Anything else is an orphan: a passage is never attached merely because it is the closest text.
 */
final class Anchoring {
    /** Characters of block text kept on each side of a selection as its context. */
    static final int CONTEXT_CHARS = 32;
    /** Fuzzy matching is skipped for longer quotes and blocks, and when more blocks than this share the words. */
    static final int MAX_FUZZY_QUOTE_CHARS = 1_000;
    static final int MAX_FUZZY_BLOCK_CHARS = 20_000;
    static final int MAX_FUZZY_BLOCKS = 16;

    /**
     * How much evidence each stage needs. [quoteContext]: context similarity that supports an exact-quote occurrence.
     * [uniqueQuoteChars]: an exact quote at least this long that occurs once needs no context. [fuzzyQuote] and
     * [fuzzyContext]: the quote and context similarity a fuzzy candidate needs.
     */
    record Thresholds(double quoteContext, int uniqueQuoteChars, double fuzzyQuote, double fuzzyContext) {}

    /** Set from the measurement recorded in requirements/reporead-stage4-record.md, not chosen by hand. */
    static final Thresholds MEASURED = new Thresholds(0.5, 16, 0.85, 0.8);

    enum Method { POSITION, BLOCK, QUOTE, FUZZY }

    record Resolved(Method method, Annotations.Anchor anchor) {}

    record Candidate(MarkdownRenderer.Block block, int start, int end, double quote, double context, boolean sameHeadings) {}

    /**
     * What one version offers for an anchor, independent of thresholds. [position]: the passage at its old block id and
     * offsets with unchanged context. [block]: the same offsets in the one block whose text is unchanged. [fuzzy] is only
     * searched when none of those nor a full-context exact quote can decide.
     */
    record Evidence(Annotations.Anchor from, Candidate position, Candidate block, List<Candidate> exact, List<Candidate> fuzzy) {}

    private Anchoring() {}

    /**
     * The anchor for [start, end) of a block in one source version, with its context and headings from that version and
     * how distinguishable its quote is among all of that version's blocks.
     */
    static Annotations.Anchor anchorAt(String sourceBlobSha, List<MarkdownRenderer.Block> blocks, MarkdownRenderer.Block block, int start, int end) {
        String text = block.text();
        String quote = text.substring(start, end);
        String prefix = prefix(text, start);
        String suffix = suffix(text, end);
        int occurrences = 0;
        double rival = 0;
        long sameBlocks = blocks.stream().filter(other -> other.text().equals(text)).count();
        for (var other : blocks) {
            for (int at = other.text().indexOf(quote); at >= 0; at = other.text().indexOf(quote, at + 1)) {
                occurrences++;
                if (other != block || at != start) {
                    rival = Math.max(rival, context(prefix, suffix, other.text(), at, at + quote.length()));
                }
            }
        }
        var located = new Annotations.Anchor(sourceBlobSha, block.id(), quote, prefix, suffix, start, end, block.headingPath(),
            null, null, null, null);
        // Look-alikes too many (or too long) to compare are unknown, so the passage counts as indistinguishable from them.
        double lookAlike = fuzzyCandidates(located, blocks).map(candidates -> candidates.stream()
            .filter(candidate -> candidate.block() != block).mapToDouble(Candidate::quote).max().orElse(0)).orElse(1.0);
        return new Annotations.Anchor(sourceBlobSha, block.id(), quote, prefix, suffix, start, end, block.headingPath(),
            sameBlocks == 1 ? sha256(text) : null, occurrences, rival, lookAlike);
    }

    static Optional<Resolved> resolve(Annotations.Anchor from, String sourceBlobSha, List<MarkdownRenderer.Block> blocks) {
        return decide(evidence(from, blocks), MEASURED)
            .map(found -> new Resolved(found.method(),
                anchorAt(sourceBlobSha, blocks, found.candidate().block(), found.candidate().start(), found.candidate().end())));
    }

    static Evidence evidence(Annotations.Anchor from, List<MarkdownRenderer.Block> blocks) {
        String quote = from.exactText();
        Candidate position = null;
        var exact = new ArrayList<Candidate>();
        for (var block : blocks) {
            for (int at = block.text().indexOf(quote); at >= 0; at = block.text().indexOf(quote, at + 1)) {
                var candidate = candidate(from, block, at, at + quote.length());
                exact.add(candidate);
                if (block.id().equals(from.blockId()) && at == from.startOffset() && candidate.context() == 1.0) position = candidate;
            }
        }
        Candidate unchanged = null;
        if (from.blockSha() != null) {
            var same = blocks.stream().filter(block -> sha256(block.text()).equals(from.blockSha())).toList();
            if (same.size() == 1) unchanged = candidate(from, same.getFirst(), from.startOffset(), from.endOffset());
        }
        boolean decided = position != null || unchanged != null || exact.stream().anyMatch(candidate -> candidate.context() == 1.0);
        // Unsearched (bounded) look-alikes are ambiguous, so they offer no fuzzy candidate.
        return new Evidence(from, position, unchanged, List.copyOf(exact), decided ? List.of() : fuzzyCandidates(from, blocks).orElse(List.of()));
    }

    record Found(Method method, Candidate candidate) {}

    /**
     * Block identity is the strongest evidence, then position. Elsewhere, a candidate must have more similar context than
     * the passage's best rival had in its own version, and a fuzzy candidate must also resemble the quote more than its
     * closest look-alike in another block did. A passage with an identical-context rival is found only by its block.
     */
    static Optional<Found> decide(Evidence evidence, Thresholds thresholds) {
        if (evidence.block() != null) return Optional.of(new Found(Method.BLOCK, evidence.block()));
        var from = evidence.from();
        double rival = from.rivalContext() == null ? 1.0 : from.rivalContext();
        // An identical-context rival can shift into the old position, so position only identifies a distinguishable passage.
        if (evidence.position() != null && rival < 1.0) return Optional.of(new Found(Method.POSITION, evidence.position()));
        double lookAlike = from.rivalQuote() == null ? 1.0 : from.rivalQuote();
        boolean uniqueQuote = from.quoteOccurrences() != null && from.quoteOccurrences() == 1;
        var exact = evidence.exact();
        var full = exact.stream().filter(candidate -> candidate.context() == 1.0).toList();
        if (!full.isEmpty()) return rival < 1.0 ? single(full).map(found -> new Found(Method.QUOTE, found)) : Optional.empty();
        var supported = exact.stream().filter(candidate -> (candidate.context() >= thresholds.quoteContext() && candidate.context() > rival)
            || (exact.size() == 1 && uniqueQuote && from.exactText().length() >= thresholds.uniqueQuoteChars())).toList();
        if (!supported.isEmpty()) return single(supported).map(found -> new Found(Method.QUOTE, found));
        var fuzzy = evidence.fuzzy().stream().filter(candidate -> candidate.quote() >= thresholds.fuzzyQuote() && candidate.quote() > lookAlike
            && candidate.context() >= thresholds.fuzzyContext() && candidate.context() > rival).toList();
        return single(fuzzy).map(found -> new Found(Method.FUZZY, found));
    }

    /** One candidate, or the one under the same headings; several equally good candidates are ambiguous. */
    private static Optional<Candidate> single(List<Candidate> candidates) {
        if (candidates.size() == 1) return Optional.of(candidates.getFirst());
        var sameHeadings = candidates.stream().filter(Candidate::sameHeadings).toList();
        return sameHeadings.size() == 1 ? Optional.of(sameHeadings.getFirst()) : Optional.empty();
    }

    private static Candidate candidate(Annotations.Anchor from, MarkdownRenderer.Block block, int start, int end) {
        String text = block.text();
        return new Candidate(block, start, end, similarity(from.exactText(), text.substring(start, end)),
            context(from.prefixText(), from.suffixText(), text, start, end), block.headingPath().equals(from.headingPath()));
    }

    /**
     * How similar the text around [start, end) is to a stored prefix and suffix: the weaker side, because deleting a
     * passage can pull a neighbour's text into place on one side only.
     */
    private static double context(String prefix, String suffix, String text, int start, int end) {
        return Math.min(similarity(prefix, prefix(text, start)), similarity(suffix, suffix(text, end)));
    }

    /**
     * The best approximate occurrence of context + quote in each block that shares most of its words. Bounded: empty
     * (not searched) when the quote is long or too many blocks qualify (which is ambiguous anyway); oversized blocks are
     * not searched.
     */
    private static Optional<List<Candidate>> fuzzyCandidates(Annotations.Anchor from, List<MarkdownRenderer.Block> blocks) {
        if (from.exactText().length() > MAX_FUZZY_QUOTE_CHARS) return Optional.empty();
        String pattern = from.prefixText() + from.exactText() + from.suffixText();
        Set<String> words = words(pattern);
        if (words.isEmpty()) return Optional.of(List.of());
        var related = blocks.stream()
            .filter(block -> block.text().length() <= MAX_FUZZY_BLOCK_CHARS)
            .filter(block -> {
                var shared = new HashSet<>(words(block.text()));
                shared.retainAll(words);
                return shared.size() * 2 >= words.size();
            }).toList();
        if (related.size() > MAX_FUZZY_BLOCKS) return Optional.empty();
        var candidates = new ArrayList<Candidate>();
        for (var block : related) {
            int[] span = align(pattern, from.prefixText().length(), from.prefixText().length() + from.exactText().length(), block.text());
            if (span != null) candidates.add(candidate(from, block, span[0], span[1]));
        }
        return Optional.of(List.copyOf(candidates));
    }

    /**
     * Aligns pattern to its best-matching substring of text and maps the pattern range [from, to) onto text. Only
     * insertions and deletions count (a mismatch costs both), so the alignment keeps the most characters matched and
     * pairs identical words instead of substituting across them. Null when that range maps to nothing.
     */
    static int[] align(String pattern, int from, int to, String text) {
        int m = pattern.length();
        int n = text.length();
        // Free start in text: the cheapest end position of any substring alignment.
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            current[0] = i;
            for (int j = 1; j <= n; j++) {
                int substitute = previous[j - 1] + (pattern.charAt(i - 1) == text.charAt(j - 1) ? 0 : 2);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int end = 0;
        for (int j = 1; j <= n; j++) if (previous[j] < previous[end]) end = j;
        // Global alignment of the pattern with text that ends at [end], recording the path to map the range boundaries.
        int start = Math.max(0, end - m * 2);
        String segment = text.substring(start, end);
        int s = segment.length();
        int[][] cost = new int[m + 1][s + 1];
        for (int i = 0; i <= m; i++) cost[i][0] = i;
        for (int j = 1; j <= s; j++) cost[0][j] = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= s; j++) {
                int substitute = cost[i - 1][j - 1] + (pattern.charAt(i - 1) == segment.charAt(j - 1) ? 0 : 2);
                cost[i][j] = Math.min(substitute, Math.min(cost[i - 1][j], cost[i][j - 1]) + 1);
            }
        }
        // Walk back from (m, s). Text inserted at a boundary stays outside the range: walking backwards, the start keeps
        // the first (largest) column seen on its row and the end the last (smallest).
        int[] mapped = {-1, -1};
        int i = m;
        int j = s;
        while (true) {
            if (i == from && mapped[0] < 0) mapped[0] = j;
            if (i == to) mapped[1] = j;
            if (i == 0) break;
            if (j > 0 && cost[i][j] == cost[i - 1][j - 1] + (pattern.charAt(i - 1) == segment.charAt(j - 1) ? 0 : 2)) {
                i--;
                j--;
            } else if (cost[i][j] == cost[i - 1][j] + 1) {
                i--;
            } else {
                j--;
            }
        }
        if (mapped[0] < 0 || mapped[1] <= mapped[0]) return null;
        return new int[] {start + mapped[0], start + mapped[1]};
    }

    /** 1 - edit distance / longer length; two empty strings are identical. */
    static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Anchoring requires the JDK SHA-256 implementation", error);
        }
    }

    private static Set<String> words(String text) {
        var words = new HashSet<String>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty()) words.add(word);
        return words;
    }

    /** Context windows never end inside a surrogate pair: half a character is stored as '?' and never matches again. */
    private static String prefix(String text, int start) {
        int from = Math.max(0, start - CONTEXT_CHARS);
        return text.substring(MarkdownRenderer.splitsCharacter(text, from) ? from + 1 : from, start);
    }

    private static String suffix(String text, int end) {
        int to = Math.min(text.length(), end + CONTEXT_CHARS);
        return text.substring(end, MarkdownRenderer.splitsCharacter(text, to) ? to - 1 : to);
    }
}
