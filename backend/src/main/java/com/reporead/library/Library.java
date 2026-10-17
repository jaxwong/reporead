package com.reporead.library;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.reporead.annotation.Bookmarks;
import com.reporead.annotation.Reviews;
import com.reporead.document.Documents;
import com.reporead.reading.ReadingStates;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the one read of a user's whole library: what the phone's offline copy holds, from one database snapshot. Each
 * list comes from its owner; this class decides only that they are read together.
 */
@Component
public class Library {
    private final RepositoryConnections connections;
    private final Documents documents;
    private final ReadingStates readingStates;
    private final Bookmarks bookmarks;
    private final Reviews reviews;

    /**
     * One read-only REPEATABLE READ transaction: PostgreSQL takes its snapshot at the first query, so every later query
     * sees the database as that one did, and a write committed meanwhile is wholly in the next read, never partly in
     * this one.
     */
    private final TransactionTemplate snapshot;

    public Library(
            RepositoryConnections connections,
            Documents documents,
            ReadingStates readingStates,
            Bookmarks bookmarks,
            Reviews reviews,
            TransactionTemplate transaction) {
        this.connections = connections;
        this.documents = documents;
        this.readingStates = readingStates;
        this.bookmarks = bookmarks;
        this.reviews = reviews;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    /** A connected repository, as its connection reads, with every document of it, deleted ones too. */
    public record Repository(
            @JsonUnwrapped RepositoryConnections.Connection connection, List<Documents.Summary> documents) {}

    public record Snapshot(
            List<Repository> repositories,
            List<ReadingStates.State> readingStates,
            List<Bookmarks.Bookmark> bookmarks,
            Reviews.Notebook notebook) {}

    /** Database only: 1 + repositories + 4 queries, all in {@link #snapshot}. */
    Snapshot read(long userId) {
        return snapshot.execute(status -> new Snapshot(
                connections.list(userId).stream()
                        .map(connection -> new Repository(connection, documents.list(connection.id())))
                        .toList(),
                readingStates.list(userId),
                bookmarks.list(userId),
                reviews.notebook(userId)));
    }
}
