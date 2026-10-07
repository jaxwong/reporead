package com.reporead.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Which heading sections changed between two versions of a note: a minimal line diff of the Markdown source (Myers),
 * with each changed line assigned to the section it is in. A section runs from its heading's line to the next heading of
 * any level; lines before the first heading are the beginning of the note. Sections are identified by position, so
 * duplicate headings stay distinct. Pure computation; bounded by MAX_LINES and MAX_CHANGED_LINES.
 */
public final class NoteChanges {
    /** Versions longer than this are not compared; work is proportional to lines times changed lines. */
    static final int MAX_LINES = 20_000;
    /** Edits larger than this (inserted plus deleted lines) are too large to summarize by section. */
    static final int MAX_CHANGED_LINES = 1_000;

    private NoteChanges() {}

    public enum Change { ADDED, CHANGED, REMOVED }

    /**
     * A changed section. [headingPath] is empty for the beginning of the note. [blockId] is the heading block in the newer
     * version, null for a removed section and for the beginning of the note (the top).
     */
    public record Section(Change change, List<String> headingPath, String blockId, int addedLines, int removedLines) {}

    /** Changed sections in the newer version's order; a removed section appears where it used to be. */
    public record Comparison(List<Section> sections, int addedLines, int removedLines) {}

    record LineDiff(boolean[] deleted, boolean[] inserted) {}

    /** Empty when either version or the edit exceeds the limits: the comparison is too large to summarize. */
    public static Optional<Comparison> compare(List<String> oldLines, List<MarkdownRenderer.Heading> oldHeadings,
                                               List<String> newLines, List<MarkdownRenderer.Heading> newHeadings) {
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) return Optional.empty();
        return lineDiff(oldLines, newLines, MAX_CHANGED_LINES).map(diff -> sections(diff, oldLines.size(), oldHeadings, newLines.size(), newHeadings));
    }

    private static Comparison sections(LineDiff diff, int oldCount, List<MarkdownRenderer.Heading> oldHeadings,
                                       int newCount, List<MarkdownRenderer.Heading> newHeadings) {
        int[] oldSection = sectionOfLine(oldCount, oldHeadings);
        int[] newSection = sectionOfLine(newCount, newHeadings);
        // Index 0 is the beginning of the note; index j + 1 is heading j.
        int[] added = new int[newHeadings.size() + 1];
        int[] removedHere = new int[newHeadings.size() + 1];
        int[] removedSection = new int[oldHeadings.size() + 1];
        int[] removedAfter = new int[oldHeadings.size() + 1];
        int totalAdded = 0;
        int totalRemoved = 0;
        for (int line = 0; line < newCount; line++) {
            if (diff.inserted()[line]) {
                added[newSection[line]]++;
                totalAdded++;
            }
        }
        // Walks both versions in step: an unchanged line advances both, so the last unchanged new line before each old
        // line is where that old line would have been.
        int newLine = 0;
        int lastKeptNew = -1;
        for (int line = 0; line < oldCount; line++) {
            if (oldSection[line] > 0 && line == oldHeadings.get(oldSection[line] - 1).line()) removedAfter[oldSection[line]] = lastKeptNew;
            if (!diff.deleted()[line]) {
                while (diff.inserted()[newLine]) newLine++;
                lastKeptNew = newLine++;
                continue;
            }
            totalRemoved++;
            int section = oldSection[line];
            boolean headingDeleted = section > 0 && diff.deleted()[oldHeadings.get(section - 1).line()];
            if (headingDeleted) removedSection[section]++;
            else removedHere[lastKeptNew < 0 ? 0 : newSection[lastKeptNew]]++;
        }

        record Placed(double position, Section section) {}
        var placed = new ArrayList<Placed>();
        for (int section = 0; section <= newHeadings.size(); section++) {
            if (added[section] == 0 && removedHere[section] == 0) continue;
            if (section == 0) {
                placed.add(new Placed(-1, new Section(Change.CHANGED, List.of(), null, added[0], removedHere[0])));
                continue;
            }
            var heading = newHeadings.get(section - 1);
            var change = diff.inserted()[heading.line()] ? Change.ADDED : Change.CHANGED;
            placed.add(new Placed(heading.line(), new Section(change, heading.headingPath(), heading.blockId(), added[section], removedHere[section])));
        }
        for (int section = 1; section <= oldHeadings.size(); section++) {
            if (removedSection[section] == 0) continue;
            placed.add(new Placed(removedAfter[section] + 0.5,
                new Section(Change.REMOVED, oldHeadings.get(section - 1).headingPath(), null, 0, removedSection[section])));
        }
        placed.sort(Comparator.comparingDouble(Placed::position));
        return new Comparison(placed.stream().map(Placed::section).toList(), totalAdded, totalRemoved);
    }

    /** For each line, 0 before the first heading, else 1 + the index of the heading whose section contains it. */
    private static int[] sectionOfLine(int lineCount, List<MarkdownRenderer.Heading> headings) {
        int[] section = new int[lineCount];
        int next = 0;
        for (int line = 0; line < lineCount; line++) {
            while (next < headings.size() && headings.get(next).line() <= line) next++;
            section[line] = next;
        }
        return section;
    }

    /**
     * A minimal line diff (Myers' greedy algorithm) after removing the common prefix and suffix. Empty when more than
     * [maxEdits] lines must be inserted or deleted. Time is O((n + m) * edits); the kept trace is O(edits²).
     */
    static Optional<LineDiff> lineDiff(List<String> oldLines, List<String> newLines, int maxEdits) {
        var ids = new HashMap<String, Integer>();
        int[] a = oldLines.stream().mapToInt(line -> ids.computeIfAbsent(line, key -> ids.size())).toArray();
        int[] b = newLines.stream().mapToInt(line -> ids.computeIfAbsent(line, key -> ids.size())).toArray();
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) prefix++;
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++;
        int n = a.length - prefix - suffix;
        int m = b.length - prefix - suffix;
        var diff = new LineDiff(new boolean[a.length], new boolean[b.length]);
        int limit = Math.min(n + m, maxEdits);
        int offset = limit + 1;
        int[] furthest = new int[2 * limit + 3];
        // trace.get(d) holds furthest[-d .. d] as it was before step d.
        var trace = new ArrayList<int[]>();
        for (int d = 0; d <= limit; d++) {
            int[] window = new int[2 * d + 1];
            System.arraycopy(furthest, offset - d, window, 0, 2 * d + 1);
            trace.add(window);
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && furthest[offset + k - 1] < furthest[offset + k + 1]) ? furthest[offset + k + 1] : furthest[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[prefix + x] == b[prefix + y]) {
                    x++;
                    y++;
                }
                furthest[offset + k] = x;
                if (x >= n && y >= m) {
                    backtrack(trace, n, m, d, prefix, diff);
                    return Optional.of(diff);
                }
            }
        }
        return Optional.empty();
    }

    private static void backtrack(List<int[]> trace, int n, int m, int edits, int prefix, LineDiff diff) {
        int x = n;
        int y = m;
        for (int d = edits; d > 0; d--) {
            int[] before = trace.get(d);
            // before[i] is furthest[k] for k = i - d; neighbours of k are k - 1 and k + 1 on step d - 1.
            int k = x - y;
            boolean down = k == -d || (k != d && before[k - 1 + d] < before[k + 1 + d]);
            int previousK = down ? k + 1 : k - 1;
            int previousX = before[previousK + d];
            int previousY = previousX - previousK;
            while (x > previousX && y > previousY) {
                x--;
                y--;
            }
            if (down) diff.inserted()[prefix + previousY] = true;
            else diff.deleted()[prefix + previousX] = true;
            x = previousX;
            y = previousY;
        }
    }
}
