package com.reporead.library;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.annotation.Bookmarks;
import com.reporead.annotation.Reviews;
import com.reporead.document.Documents;
import com.reporead.reading.ReadingStates;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the one read of a user's whole library: what the phone's offline copy holds, from one database snapshot. Each
 * list comes from its owner; this class decides only that they are read together.
 */
@Component
public class LibrarySnapshotReader {
    /**
     * The largest library answer the server sends; android/build.gradle.kts reads this line, so the app reads exactly
     * as much and no second number exists.
     */
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(LibrarySnapshotReader.class);
    private final RepositoryConnections connections;
    private final Documents documents;
    private final ReadingStates readingStates;
    private final Bookmarks bookmarks;
    private final Reviews reviews;
    private final JsonMapper json;

    /**
     * One read-only REPEATABLE READ transaction: PostgreSQL takes its snapshot at the first query, so every later query
     * sees the database as that one did, and a write committed meanwhile is wholly in the next read, never partly in
     * this one.
     */
    private final TransactionTemplate snapshot;

    public LibrarySnapshotReader(
            RepositoryConnections connections,
            Documents documents,
            ReadingStates readingStates,
            Bookmarks bookmarks,
            Reviews reviews,
            JsonMapper json,
            TransactionTemplate transaction) {
        this.connections = connections;
        this.documents = documents;
        this.readingStates = readingStates;
        this.bookmarks = bookmarks;
        this.reviews = reviews;
        this.json = json;
        this.snapshot = new TransactionTemplate(transaction.getTransactionManager());
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    /**
     * The user's library as the JSON answer, serialized once with the application's mapper and measured; one over
     * {@link #MAX_RESPONSE_BYTES} is refused whole (422 LIBRARY_TOO_LARGE) before any of it is sent.
     */
    byte[] answer(long userId) {
        var snapshot = read(userId);
        byte[] body = json.writeValueAsBytes(snapshot);
        LOG.info(
                "Library read; userId={} bytes={} repositories={} readingStates={} bookmarks={} annotations={} reviews={}",
                userId,
                body.length,
                snapshot.repositories().size(),
                snapshot.readingStates().size(),
                snapshot.bookmarks().size(),
                snapshot.notebook().annotations().size(),
                snapshot.notebook().reviews().size());
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new ApiFailure(
                    Code.LIBRARY_TOO_LARGE,
                    "The library is " + body.length + " bytes; the server sends at most " + MAX_RESPONSE_BYTES + ".");
        }
        return body;
    }

    /**
     * Database only, and a fixed 6 queries however many repositories and notes: connections, documents, reading states,
     * bookmarks, and the notebook's annotations and review log, all in {@link #snapshot}.
     */
    private LibrarySnapshot read(long userId) {
        return snapshot.execute(status -> {
            var repositories = connections.list(userId);
            var documentsByConnection = documents.listForUser(userId);
            return new LibrarySnapshot(
                    repositories.stream()
                            .map(connection -> new LibraryRepository(
                                    connection, documentsByConnection.getOrDefault(connection.id(), List.of())))
                            .toList(),
                    readingStates.list(userId),
                    bookmarks.list(userId),
                    reviews.notebook(userId));
        });
    }
}
