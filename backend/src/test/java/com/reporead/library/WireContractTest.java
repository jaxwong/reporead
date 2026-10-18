package com.reporead.library;

import static com.reporead.ContractFixtures.*;

import com.reporead.SharedSpringTest;
import com.reporead.annotation.Annotations;
import com.reporead.annotation.Bookmarks;
import com.reporead.annotation.Reviews;
import com.reporead.document.Documents;
import com.reporead.reading.ReadingStates;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class WireContractTest extends SharedSpringTest {
    @Autowired
    JsonMapper json;

    @Test
    void thePublishedLibraryUsesTheSharedExamplesIncludingExplicitNulls() throws Exception {
        var anchor = new Annotations.Passage(SHA, "b1", "text", "", "", 0, 4, List.of());
        var location = new Annotations.Location(anchor, null, 1, 0, 0);
        var highlight = new Annotations.Annotation(
                9,
                "00000000-0000-0000-0000-000000000001",
                1,
                Annotations.Type.HIGHLIGHT,
                null,
                Annotations.Status.ANCHORED,
                1,
                AT,
                AT,
                anchor,
                location,
                SHA,
                null,
                null);
        var card = new Annotations.Annotation(
                10,
                "00000000-0000-0000-0000-000000000003",
                1,
                Annotations.Type.CARD,
                null,
                Annotations.Status.ANCHORED,
                1,
                AT,
                AT,
                anchor,
                location,
                SHA,
                "Test-only question?",
                SHA);
        var reading = new ReadingStates.State(1, SHA, 50, new ReadingStates.Anchor(List.of(), null, 0), AT);
        var bookmark = new Bookmarks.Bookmark(1, AT);
        var review = new Reviews.Review("00000000-0000-0000-0000-000000000002", card.mutationId(), 4, AT, SHA);
        var connection = new RepositoryConnections.Connection(7, 11, "test-only", "notes", "main", null, 1);
        var document = new Documents.Summary(1, "n.md", "Test-only note", SHA, null, false);
        matches(
                json,
                "library",
                new Library.Snapshot(
                        List.of(new Library.Repository(connection, List.of(document))),
                        List.of(reading),
                        List.of(bookmark),
                        new Reviews.Notebook(List.of(highlight, card), List.of(review), 40)));
        matches(
                json,
                "library-empty",
                new Library.Snapshot(List.of(), List.of(), List.of(), new Reviews.Notebook(List.of(), List.of(), 40)));
        matches(json, "annotation", highlight);
        matches(json, "annotation-card", card);
        matches(json, "reading", reading);
        matches(json, "bookmark", bookmark);
        matches(json, "review", review);
        matches(json, "connection", connection);
    }
}
