package com.reporead.annotation;

import com.reporead.document.MarkdownRenderer.Block;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour of re-anchoring on test-only text. The thresholds themselves come from AnchorMeasurement on real notes. */
class AnchoringTest {
    private static final String OLD = "a".repeat(40);
    private static final String NEW = "b".repeat(40);
    private static final List<String> PROXIES = List.of("Spring", "Proxies");
    private static final List<String> WEAVING = List.of("Spring", "Weaving");
    private static final String PROXY = "By default, Spring implements declarative transactions using a proxy around the target bean.";
    private static final String OTHER = "Self-invocation bypasses the proxy, so the transactional advice never runs for internal calls.";
    private static final String LAST = "AspectJ weaving changes the bytecode instead and has none of these limits at all.";

    private static List<Block> blocks(Object... textsAndHeadings) {
        var blocks = new ArrayList<Block>();
        for (int i = 0; i < textsAndHeadings.length; i += 2) {
            @SuppressWarnings("unchecked") var headings = (List<String>) textsAndHeadings[i + 1];
            blocks.add(new Block("b" + blocks.size(), (String) textsAndHeadings[i], headings));
        }
        return blocks;
    }

    /** Anchors [quote] in block [index] of the old version, exactly as creation does. */
    private static Annotations.Anchor anchor(List<Block> version, int index, String quote) {
        var block = version.get(index);
        int start = block.text().indexOf(quote);
        return Anchoring.anchorAt(OLD, version, block, start, start + quote.length());
    }

    private static Optional<Anchoring.Resolved> resolve(Annotations.Anchor from, List<Block> version) {
        return Anchoring.resolve(from, NEW, version);
    }

    private static void assertAt(Optional<Anchoring.Resolved> resolved, Anchoring.Method method, String blockId, String exact) {
        assertTrue(resolved.isPresent(), "expected an anchor, got an orphan");
        assertEquals(method, resolved.get().method());
        assertEquals(blockId, resolved.get().anchor().blockId());
        assertEquals(exact, resolved.get().anchor().exactText());
        assertEquals(NEW, resolved.get().anchor().sourceBlobSha());
        var text = resolved.get().anchor();
        assertEquals(exact.length(), text.endOffset() - text.startOffset());
    }

    @Test void creationRecordsHowDistinguishableThePassageIs() {
        var version = blocks(PROXY, PROXIES, OTHER, PROXIES, PROXY, WEAVING);
        var repeated = anchor(version, 2, "proxy");
        assertEquals(3, repeated.quoteOccurrences());
        assertNull(anchor(version, 0, "declarative transactions").blockSha(), "the block's text is not unique");
        var unique = anchor(version, 1, "transactional advice");
        assertEquals(1, unique.quoteOccurrences());
        assertEquals(0.0, unique.rivalContext());
        assertNotNull(unique.blockSha());
        assertEquals("the ", unique.prefixText().substring(unique.prefixText().length() - 4));
    }

    @Test void anUnchangedBlockKeepsTheAnchorWhereverItMoved() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES, LAST, WEAVING);
        var from = anchor(before, 1, "transactional advice");
        assertAt(resolve(from, blocks("A new first paragraph about something else entirely.", PROXIES, LAST, WEAVING, PROXY, PROXIES, OTHER, WEAVING)),
            Anchoring.Method.BLOCK, "b3", "transactional advice");
    }

    @Test void anEditElsewhereInTheBlockKeepsTheAnchorByItsUnchangedContextAtThePosition() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var from = anchor(before, 0, "declarative transactions");
        // The change is beyond the 32 characters of context, so position and context still agree.
        var edited = blocks(PROXY.replace("target bean", "target object"), PROXIES, OTHER, PROXIES);
        assertAt(resolve(from, edited), Anchoring.Method.POSITION, "b0", "declarative transactions");
    }

    @Test void aChangedBlockElsewhereIsFoundByItsUniqueQuoteAndContext() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var from = anchor(before, 0, "declarative transactions");
        var after = blocks(OTHER, PROXIES, "Inserted.", PROXIES, PROXY.replace("target bean", "real target object"), WEAVING);
        assertAt(resolve(from, after), Anchoring.Method.QUOTE, "b2", "declarative transactions");
    }

    @Test void aLightlyEditedPassageIsFoundFuzzilyWithItsNewText() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var from = anchor(before, 0, "implements declarative transactions using a proxy");
        var after = blocks(PROXY.replace("declarative transactions", "declarative transaction"), PROXIES, OTHER, PROXIES);
        assertAt(resolve(from, after), Anchoring.Method.FUZZY, "b0", "implements declarative transaction using a proxy");
    }

    @Test void aDeletedPassageIsOrphanedEvenWhenItsQuoteStillAppearsElsewhere() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES, LAST, WEAVING);
        var from = anchor(before, 0, "proxy");
        assertTrue(resolve(from, blocks(OTHER, PROXIES, LAST, WEAVING)).isEmpty());
    }

    @Test void aRewrittenPassageIsOrphaned() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var from = anchor(before, 0, "declarative transactions using a proxy");
        var after = blocks("Spring now weaves transactional behaviour into classes at build time instead.", PROXIES, OTHER, PROXIES);
        assertTrue(resolve(from, after).isEmpty());
    }

    @Test void identicalPassagesAreAmbiguousUnlessTheHeadingDecides() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var from = anchor(before, 0, "declarative transactions");
        String edited = PROXY.replace("target bean", "bean");
        assertTrue(resolve(from, blocks(LAST, PROXIES, edited, PROXIES, edited, PROXIES)).isEmpty(), "two copies under one heading");
        assertAt(resolve(from, blocks(LAST, PROXIES, edited, WEAVING, edited, PROXIES)), Anchoring.Method.QUOTE, "b2", "declarative transactions");
    }

    @Test void aPassageWithAnIdenticalContextRivalIsFoundOnlyByItsUnchangedBlock() {
        String row = "2025-10-28 16:19:52 UTC";
        var before = blocks(row, PROXIES, row, PROXIES, LAST, PROXIES);
        var from = anchor(before, 1, row);
        assertEquals(1.0, from.rivalContext());
        assertNull(from.blockSha());
        // A block inserted above shifts the other identical row into the old position: that is not evidence.
        assertTrue(resolve(from, blocks("new", PROXIES, row, PROXIES, row, PROXIES, LAST, PROXIES)).isEmpty());
    }

    @Test void aDeletedPassageDoesNotMoveOntoAnExistingLookAlike() {
        String original = "Fallback to unfiltered search when the filter index is down.";
        String lookAlike = "Fall back to unfiltered search when the filter index is slow.";
        var before = blocks(original, PROXIES, lookAlike, PROXIES);
        var from = anchor(before, 0, "Fallback to unfiltered search");
        assertTrue(from.rivalQuote() > Anchoring.MEASURED.fuzzyQuote(), "the look-alike was already this close");
        assertTrue(resolve(from, blocks(lookAlike, PROXIES)).isEmpty());
    }

    @Test void anAnchorWithUnknownDistinguishabilityIsOnlyKeptByAnUnchangedPosition() {
        var before = blocks(PROXY, PROXIES, OTHER, PROXIES);
        var known = anchor(before, 0, "declarative transactions");
        var legacy = new Annotations.Anchor(OLD, known.blockId(), known.exactText(), known.prefixText(), known.suffixText(),
            known.startOffset(), known.endOffset(), known.headingPath(), null, null, null, null);
        assertTrue(resolve(legacy, blocks(LAST, WEAVING, PROXY, PROXIES, OTHER, PROXIES)).isEmpty(), "moved: no evidence it is the same passage");
        assertTrue(resolve(legacy, blocks(PROXY, PROXIES, OTHER, PROXIES)).isEmpty(), "unchanged position, but uniqueness unknown");
    }

    @Test void fuzzySearchIsBoundedByQuoteLengthAndByHowManyBlocksShareItsWords() {
        String long1 = "word ".repeat(Anchoring.MAX_FUZZY_QUOTE_CHARS / 5 + 1).strip();
        var before = blocks("Intro " + long1 + " outro.", PROXIES);
        var from = anchor(before, 0, long1);
        assertTrue(resolve(from, blocks("Intro " + long1.replaceFirst("word", "ward") + " outro.", PROXIES)).isEmpty());

        var many = new ArrayList<Object>();
        for (int i = 0; i <= Anchoring.MAX_FUZZY_BLOCKS; i++) {
            many.add("By default, Spring implements declarative transaction using a proxy around the target bean, variant " + i + ".");
            many.add(PROXIES);
        }
        var crowded = blocks(many.toArray());
        var fromCrowded = anchor(blocks(PROXY, PROXIES), 0, "implements declarative transactions using a proxy");
        assertTrue(resolve(fromCrowded, crowded).isEmpty());
    }

    @Test void aPassageAmongTooManyLookAlikesToCompareIsNotMovedOntoOneOfThemLater() {
        var rows = new ArrayList<Object>();
        for (int i = 0; i <= Anchoring.MAX_FUZZY_BLOCKS; i++) {
            rows.add("Read chapter " + i + " of SICP and do the exercises at the end.");
            rows.add(PROXIES);
        }
        var from = anchor(blocks(rows.toArray()), 7, "chapter 7 of SICP");
        // Too many rows resembled it to compare when it was made, so how alike they are is unknown, not zero.
        assertEquals(1.0, from.rivalQuote());
        // Every other row is deleted except one: resembling the passage is no evidence that it is the passage.
        assertTrue(resolve(from, blocks("Read chapter 3 of SICP and do the exercises at the end.", PROXIES)).isEmpty());
    }

    @Test void contextNeverSplitsACharacterSoStoredContextIsValidText() {
        String quote = "quoted passage";
        // The 32-unit windows on both sides would end inside an emoji's surrogate pair.
        String text = "😀" + "x".repeat(31) + quote + "y".repeat(31) + "😀";
        var from = anchor(blocks(text, PROXIES), 0, quote);
        for (String context : List.of(from.prefixText(), from.suffixText())) {
            assertEquals(context, new String(context.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        }
        assertEquals("x".repeat(31), from.prefixText());
        assertEquals("y".repeat(31), from.suffixText());
    }

    @Test void alignmentMapsTheQuoteAndKeepsBoundaryInsertionsOutside() {
        String pattern = "By default, Spring implements declarative";
        int from = "By default, ".length();
        int to = from + "Spring implements".length();
        String text = "Some intro. By default, modern Spring cleverly implements declarative transactions.";
        int[] span = Anchoring.align(pattern, from, to, text);
        assertNotNull(span);
        assertEquals("Spring cleverly implements", text.substring(span[0], span[1]));
    }
}
