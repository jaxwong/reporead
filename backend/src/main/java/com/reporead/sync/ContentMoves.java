package com.reporead.sync;

import com.reporead.document.MarkdownRenderer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recognizes a note that was moved and edited between two syncs. Pure: the caller supplies rendered versions. Constants
 * come from MoveMeasurement on a real notes repository (see requirements/reporead-stage4-record.md): real moves with
 * edits scored at least 0.54 and had at least 229 shingles; unrelated notes with at least 10 shingles scored at most 0.198.
 */
final class ContentMoves {
    static final double MIN_SIMILARITY = 0.5;
    /** Notes with fewer five-word shingles (empty notes, bare templates) are never matched by content. */
    static final int MIN_SHINGLES = 50;

    private ContentMoves() {}

    /**
     * Pairs vanished documents with new paths: each pair must be the only one above MIN_SIMILARITY for both its
     * document and its path, and both sides must have at least MIN_SHINGLES shingles. Anything ambiguous stays unpaired.
     */
    static Map<Long, String> pairs(Map<Long, Set<String>> vanished, Map<String, Set<String>> fresh) {
        var candidates = new HashMap<Long, List<String>>();
        var reverse = new HashMap<String, List<Long>>();
        for (var document : vanished.entrySet()) {
            if (document.getValue().size() < MIN_SHINGLES) continue;
            for (var path : fresh.entrySet()) {
                if (path.getValue().size() < MIN_SHINGLES || similarity(document.getValue(), path.getValue()) < MIN_SIMILARITY) continue;
                candidates.computeIfAbsent(document.getKey(), id -> new java.util.ArrayList<>()).add(path.getKey());
                reverse.computeIfAbsent(path.getKey(), key -> new java.util.ArrayList<>()).add(document.getKey());
            }
        }
        var pairs = new HashMap<Long, String>();
        candidates.forEach((id, paths) -> {
            if (paths.size() == 1 && reverse.get(paths.getFirst()).size() == 1) pairs.put(id, paths.getFirst());
        });
        return pairs;
    }

    /** Jaccard similarity of two shingle sets. */
    static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) return 1.0;
        int common = 0;
        for (String shingle : left) if (right.contains(shingle)) common++;
        return (double) common / (left.size() + right.size() - common);
    }

    /** The set of five consecutive lower-cased words across all canonical block text. */
    static Set<String> shingles(List<MarkdownRenderer.Block> blocks) {
        var words = new java.util.ArrayList<String>();
        for (var block : blocks) {
            for (String word : block.text().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty()) words.add(word);
        }
        var shingles = new HashSet<String>();
        for (int i = 0; i + 5 <= words.size(); i++) shingles.add(String.join(" ", words.subList(i, i + 5)));
        return shingles;
    }
}
