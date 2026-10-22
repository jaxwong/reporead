package com.reporead.annotation;

import com.reporead.ApiFailure;
import com.reporead.document.Documents;
import com.reporead.repository.RepositoryConnections;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Document-level bookmarks, one per user and document in the bookmarks table. Setting and clearing are idempotent. */
@Component
public class Bookmarks {
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final RepositoryConnections connections;
    private final Documents documents;

    public Bookmarks(
            JdbcClient db, TransactionTemplate transaction, RepositoryConnections connections, Documents documents) {
        this.db = db;
        this.transaction = transaction;
        this.connections = connections;
        this.documents = documents;
    }

    /** A note deleted upstream keeps its bookmark; the note's title, path and removal are in its document list. */
    public record Bookmark(long documentId, Instant createdAt) {}

    private static final String SELECT = """
        SELECT b.document_id, b.created_at FROM bookmarks b WHERE b.user_id = :userId""";

    /**
     * Bookmarking an already bookmarked document keeps the original bookmark. Holds the document's connection lock
     * while it writes, so a disconnect waits for it; a document that is not this user's, or was disconnected meanwhile,
     * is 404.
     */
    Bookmark set(long userId, long documentId) {
        return transaction.execute(status -> {
            connections.lockForDocumentWrite(userId, documentId);
            db.sql("""
                    INSERT INTO bookmarks (user_id, document_id, created_at) VALUES (:userId, :documentId, :now)
                    ON CONFLICT (user_id, document_id) DO NOTHING""")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .param("now", Timestamp.from(Instant.now()))
                    .update();
            return db.sql(SELECT + " AND b.document_id = :documentId")
                    .param("userId", userId)
                    .param("documentId", documentId)
                    .query(Bookmark.class)
                    .single();
        });
    }

    /**
     * Clearing is a delete scoped to the user, which no disconnect can break, so it takes no lock; the note's ownership
     * is checked for the 404.
     */
    void clear(long userId, long documentId) {
        documents.find(userId, documentId).orElseThrow(ApiFailure::notFound);
        db.sql("DELETE FROM bookmarks WHERE user_id = :userId AND document_id = :documentId")
                .param("userId", userId)
                .param("documentId", documentId)
                .update();
    }

    public List<Bookmark> list(long userId) {
        return db.sql(SELECT + " ORDER BY b.created_at DESC")
                .param("userId", userId)
                .query(Bookmark.class)
                .list();
    }

    /** Deletes the bookmarks on a connection's documents, returning how many. */
    public int deleteOnConnection(long connectionId) {
        return db.sql(
                        "DELETE FROM bookmarks WHERE document_id IN (SELECT id FROM documents WHERE repository_connection_id = :connectionId)")
                .param("connectionId", connectionId)
                .update();
    }
}
