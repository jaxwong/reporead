package com.reporead.deletion;

import com.reporead.ApiFailure;
import com.reporead.annotation.Annotations;
import com.reporead.annotation.Bookmarks;
import com.reporead.document.Documents;
import com.reporead.reading.ReadingStates;
import com.reporead.repository.RepositoryConnections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything RepoRead stores for one repository connection: its documents (paths, titles, versions) and the user's
 * reading progress, bookmarks, highlights, and cards with their review logs on them, which quote the notes.
 * Disconnecting deletes all of it, each table through its owner; GitHub is never modified. Note bodies are never stored
 * on the server.
 */
@Component
public class ConnectionDeletion {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionDeletion.class);
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final ReadingStates readingStates;
    private final Bookmarks bookmarks;
    private final Annotations annotations;

    public ConnectionDeletion(
            JdbcClient db,
            TransactionTemplate transaction,
            RepositoryConnections connections,
            Documents documents,
            ReadingStates readingStates,
            Bookmarks bookmarks,
            Annotations annotations) {
        this.db = db;
        this.transaction = transaction;
        this.connections = connections;
        this.documents = documents;
        this.readingStates = readingStates;
        this.bookmarks = bookmarks;
        this.annotations = annotations;
    }

    public record Counts(int documents, int readingStates, int bookmarks, int highlights, int cards) {}
    /** {@code documentIds}: every deleted document, so the phone can delete its own rows for them. */
    public record Disconnected(List<Long> documentIds, int readingStates, int bookmarks, int highlights, int cards) {}

    /** What disconnecting this user's connection would delete; a read-only projection of the owners' tables. */
    Counts count(long userId, long connectionId) {
        connections.find(userId, connectionId).orElseThrow(ApiFailure::notFound);
        return db.sql("""
                SELECT (SELECT count(*) FROM documents d WHERE d.repository_connection_id = :id) AS documents,
                       (SELECT count(*) FROM reading_states r JOIN documents d ON d.id = r.document_id WHERE d.repository_connection_id = :id)
                           AS reading_states,
                       (SELECT count(*) FROM bookmarks b JOIN documents d ON d.id = b.document_id WHERE d.repository_connection_id = :id)
                           AS bookmarks,
                       (SELECT count(*) FROM annotations a JOIN documents d ON d.id = a.document_id WHERE d.repository_connection_id = :id AND a.type = 'HIGHLIGHT')
                           AS highlights,
                       (SELECT count(*) FROM annotations a JOIN documents d ON d.id = a.document_id WHERE d.repository_connection_id = :id AND a.type = 'CARD')
                           AS cards""").param("id", connectionId).query(Counts.class).single();
    }

    /**
     * Disconnects a repository: deletes the connection and everything stored for it in one transaction, under its sync
     * lock, so it is serialized with its syncs and every write to its documents waits for it. Another user's
     * connection, or one already gone, is 404.
     */
    Disconnected disconnect(long userId, long connectionId) {
        var deleted = transaction.execute(status -> {
            connections.lockForSync(userId, connectionId);
            return delete(connectionId);
        });
        LOG.info(
                "Repository disconnected; userId={} connectionId={} documents={} readingStates={} bookmarks={} highlights={} cards={}",
                userId,
                connectionId,
                deleted.documentIds().size(),
                deleted.readingStates(),
                deleted.bookmarks(),
                deleted.highlights(),
                deleted.cards());
        return deleted;
    }

    /** Deletes the connection and everything stored for it. Runs inside a transaction holding its sync lock. */
    public Disconnected delete(long connectionId) {
        var deletedAnnotations = annotations.deleteOnConnection(connectionId);
        int deletedBookmarks = bookmarks.deleteOnConnection(connectionId);
        int deletedReading = readingStates.deleteOnConnection(connectionId);
        var documentIds = documents.deleteConnection(connectionId);
        connections.delete(connectionId);
        return new Disconnected(
                documentIds,
                deletedReading,
                deletedBookmarks,
                deletedAnnotations.highlights(),
                deletedAnnotations.cards());
    }
}
