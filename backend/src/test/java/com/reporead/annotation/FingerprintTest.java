package com.reporead.annotation;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mutation fingerprints are stored with each creation, so their bytes can never change: offline replays of a recorded
 * mutation would be refused as MUTATION_ID_REUSED. These values were produced by the application's JSON mapper before
 * fingerprints got their own.
 */
class FingerprintTest {
    private static final AnnotationController.Selection SELECTION = new AnnotationController.Selection("a".repeat(40), "b2", 12, 54,
        "Spring implements “declarative” transactions 😀");

    @Test void highlightAndCardFingerprintsAreStable() {
        assertEquals("86569a8ca04ef48254e6dc99e30372a8523fd36b068f38ad8621d4fd2cd50e23",
            HexFormat.of().formatHex(AnnotationController.fingerprint(7, SELECTION, "a note", "HIGHLIGHT", null)));
        assertEquals("779d7fce9950241133df0ad4809322bb15a3a2da935a811b5ec6b2b1acb169cd",
            HexFormat.of().formatHex(AnnotationController.fingerprint(7, SELECTION, null, "CARD", "Why?")));
    }
}
