package com.reporead.datacontrol;

import com.reporead.annotation.Annotations;
import com.reporead.document.Documents;
import com.reporead.reading.ReadingStates;
import com.reporead.repository.RepositoryConnections;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Everything RepoRead stores for one repository connection: its documents (paths, titles, versions) and the user's
 * reading progress, bookmarks, highlights, and cards with their review logs on them, which quote the notes.
 * Disconnecting deletes all of it, each table through its owner; GitHub is never modified. Note bodies are never stored
 * on the server.
 */
@Component
public class ConnectionData {
    private final JdbcClient db;
    private final RepositoryConnections connections;
    private final Documents documents;
    private final ReadingStates readingStates;
    private final Annotations annotations;

    public ConnectionData(JdbcClient db, RepositoryConnections connections, Documents documents, ReadingStates readingStates,
                          Annotations annotations) {
        this.db = db;
        this.connections = connections;
        this.documents = documents;
        this.readingStates = readingStates;
        this.annotations = annotations;
    }

    public record Counts(int documents, int readingStates, int bookmarks, int highlights, int cards) {}
    /** [documentIds]: every deleted document, so the phone can delete its own rows for them. */
    public record Deleted(List<Long> documentIds, int readingStates, int bookmarks, int highlights, int cards) {}

    /** What disconnecting would delete; a read-only projection of the owners' tables. */
    public Counts count(long connectionId) {
        return db.sql("""
                select (select count(*) from documents d where d.repository_connection_id = :id) as documents,
                       (select count(*) from reading_states r join documents d on d.id = r.document_id where d.repository_connection_id = :id)
                           as reading_states,
                       (select count(*) from annotations a join documents d on d.id = a.document_id where d.repository_connection_id = :id and a.type = 'BOOKMARK')
                           as bookmarks,
                       (select count(*) from annotations a join documents d on d.id = a.document_id where d.repository_connection_id = :id and a.type = 'HIGHLIGHT')
                           as highlights,
                       (select count(*) from annotations a join documents d on d.id = a.document_id where d.repository_connection_id = :id and a.type = 'CARD')
                           as cards""")
            .param("id", connectionId)
            .query((row, n) -> new Counts(row.getInt("documents"), row.getInt("reading_states"), row.getInt("bookmarks"), row.getInt("highlights"),
                row.getInt("cards"))).single();
    }

    /** Deletes the connection and everything stored for it. Must run in a transaction holding the connection's sync lock. */
    public Deleted delete(long connectionId) {
        var deletedAnnotations = annotations.deleteOnConnection(connectionId);
        int deletedReading = readingStates.deleteOnConnection(connectionId);
        var documentIds = documents.deleteConnection(connectionId);
        connections.delete(connectionId);
        return new Deleted(documentIds, deletedReading, deletedAnnotations.bookmarks(), deletedAnnotations.highlights(), deletedAnnotations.cards());
    }
}
