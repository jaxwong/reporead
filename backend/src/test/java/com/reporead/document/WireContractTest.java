// Owns: Producer checks for rendered content and changed/unavailable comparison responses.

package com.reporead.document;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Owns producer checks for rendered content and changed/unavailable comparison responses. */
class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void contentAndChangeVariantsMatchTheirSharedExamples() throws Exception {
        matches(
                json,
                "content",
                new DocumentController.Content(
                        1, "n.md", "Test-only note", SHA, COMMIT, "<main id=\"note\"><p>text</p></main>", "text", 3));
        matches(
                json,
                "changes",
                DocumentController.Changes.changed(
                        1,
                        SHA,
                        COMMIT,
                        new NoteChanges.Comparison(
                                List.of(new NoteChanges.Section(NoteChanges.Change.ADDED, List.of(), null, 1, 0)),
                                1,
                                0)));
        matches(
                json,
                "changes-unavailable",
                DocumentController.Changes.sinceUnavailable(1, SHA, COMMIT, "Test-only version unavailable"));
    }
}
