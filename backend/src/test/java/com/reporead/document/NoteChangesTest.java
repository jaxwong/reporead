package com.reporead.document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.reporead.document.NoteChanges.Change.ADDED;
import static com.reporead.document.NoteChanges.Change.CHANGED;
import static com.reporead.document.NoteChanges.Change.REMOVED;
import static org.junit.jupiter.api.Assertions.*;

class NoteChangesTest {
    private static final String NOTE = """
        Intro line.

        # Databases

        Shared opening paragraph.

        ## Isolation

        Read committed is the default.

        ## Locks

        Row locks block writers.

        # Spring

        ## Notes

        Spring note one.

        # Postgres

        ## Notes

        Postgres note one.
        """;

    private static Optional<NoteChanges.Comparison> compare(String before, String after) {
        var old = MarkdownRenderer.render(before, MarkdownRendererTest.sha(before), "notes/test.md");
        var current = MarkdownRenderer.render(after, MarkdownRendererTest.sha(after), "notes/test.md");
        return NoteChanges.compare(MarkdownRenderer.sourceLines(before), old.headings(), MarkdownRenderer.sourceLines(after), current.headings());
    }

    private static NoteChanges.Comparison changed(String before, String after) {
        return compare(before, after).orElseThrow(() -> new AssertionError("comparison was too large"));
    }

    private static String blockOf(String markdown, String headingText, int occurrence) {
        var headings = MarkdownRenderer.render(markdown, MarkdownRendererTest.sha(markdown), "notes/test.md").headings();
        return headings.stream().filter(heading -> heading.headingPath().getLast().equals(headingText)).toList().get(occurrence).blockId();
    }

    @Test void anUnchangedNoteHasNoSections() {
        var result = changed(NOTE, NOTE);
        assertEquals(List.of(), result.sections());
        assertEquals(0, result.addedLines());
        assertEquals(0, result.removedLines());
    }

    @Test void aParagraphInsertedIntoASectionChangesOnlyThatSection() {
        String after = NOTE.replace("Read committed is the default.\n", "Read committed is the default.\n\nSerializable prevents write skew.\n");
        var result = changed(NOTE, after);
        assertEquals(List.of(new NoteChanges.Section(CHANGED, List.of("Databases", "Isolation"), blockOf(after, "Isolation", 0), 2, 0)),
            result.sections());
        assertEquals(2, result.addedLines());
    }

    @Test void aNewSectionIsAddedAtItsHeadingAndADeletedSectionIsRemovedInPlace() {
        String after = NOTE.replace("## Locks\n\nRow locks block writers.\n\n", "")
            .replace("# Spring\n", "## Deadlocks\n\nThey are detected and one transaction aborts.\n\n# Spring\n");
        var result = changed(NOTE, after);
        assertEquals(List.of(
            new NoteChanges.Section(REMOVED, List.of("Databases", "Locks"), null, 0, 2),
            new NoteChanges.Section(ADDED, List.of("Databases", "Deadlocks"), blockOf(after, "Deadlocks", 0), 2, 0)), result.sections());
        // The blank lines between paragraphs are unchanged lines, so a swapped section counts only its text lines.
    }

    @Test void duplicateHeadingsAreDistinctSectionsWithTheirOwnBlocks() {
        String after = NOTE.replace("Postgres note one.\n", "Postgres note one, revised.\n");
        var result = changed(NOTE, after);
        assertEquals(List.of(new NoteChanges.Section(CHANGED, List.of("Postgres", "Notes"), blockOf(after, "Notes", 1), 1, 1)), result.sections());
        assertNotEquals(blockOf(after, "Notes", 0), result.sections().getFirst().blockId());
    }

    @Test void textBeforeTheFirstHeadingIsTheBeginningOfTheNote() {
        var result = changed(NOTE, NOTE.replace("Intro line.\n", "A new intro.\n"));
        assertEquals(List.of(new NoteChanges.Section(CHANGED, List.of(), null, 1, 1)), result.sections());
    }

    @Test void linesDeletedAtTheEndOfASectionBelongToThatSectionNotTheNextHeading() {
        var result = changed(NOTE, NOTE.replace("Row locks block writers.\n\n", ""));
        assertEquals(List.of(new NoteChanges.Section(CHANGED, List.of("Databases", "Locks"), blockOf(NOTE, "Locks", 0), 0, 2)), result.sections());
    }

    @Test void aRenamedHeadingIsReportedAsRemovedAndAdded() {
        String after = NOTE.replace("## Locks\n", "## Row locks\n");
        var result = changed(NOTE, after);
        assertEquals(List.of(
            new NoteChanges.Section(REMOVED, List.of("Databases", "Locks"), null, 0, 1),
            new NoteChanges.Section(ADDED, List.of("Databases", "Row locks"), blockOf(after, "Row locks", 0), 1, 0)), result.sections());
    }

    @Test void aWholeNewNoteIsOneAddedSectionPerHeading() {
        var result = changed("", "# One\n\ntext\n\n# Two\n");
        assertEquals(List.of(ADDED, ADDED), result.sections().stream().map(NoteChanges.Section::change).toList());
        assertEquals(5, result.addedLines());
    }

    @Test void theLineDiffIsMinimal() {
        // Myers' example: abcabba -> cbabac takes 5 edits.
        var diff = NoteChanges.lineDiff(List.of("a", "b", "c", "a", "b", "b", "a"), List.of("c", "b", "a", "b", "a", "c"), 10).orElseThrow();
        int deleted = 0;
        int inserted = 0;
        for (boolean line : diff.deleted()) if (line) deleted++;
        for (boolean line : diff.inserted()) if (line) inserted++;
        assertEquals(5, deleted + inserted);
        assertEquals(List.of("a", "b", "c", "a", "b", "b", "a").size() - deleted, List.of("c", "b", "a", "b", "a", "c").size() - inserted);
    }

    @Test void moreChangedLinesThanTheLimitAreTooLargeToSummarize() {
        var before = new StringBuilder("# Big\n\n");
        var after = new StringBuilder("# Big\n\n");
        for (int i = 0; i < NoteChanges.MAX_CHANGED_LINES / 2 + 1; i++) {
            before.append("old line ").append(i).append('\n');
            after.append("new line ").append(i).append('\n');
        }
        assertEquals(Optional.empty(), compare(before.toString(), after.toString()));
        assertTrue(compare(before.toString(), before.toString()).isPresent());
    }

    @Test void aVersionWithMoreLinesThanTheLimitIsTooLargeToSummarize() {
        var lines = new ArrayList<String>();
        for (int i = 0; i <= NoteChanges.MAX_LINES; i++) lines.add("x");
        assertEquals(Optional.empty(), NoteChanges.compare(List.of("x"), List.of(), lines, List.of()));
        assertEquals(Optional.empty(), NoteChanges.compare(lines, List.of(), List.of("x"), List.of()));
    }
}
