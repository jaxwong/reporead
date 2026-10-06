package com.reporead.annotation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Document-level bookmarks, stored in the annotation domain as type BOOKMARK. Setting and clearing are idempotent. */
@Component
public class Bookmarks {
    private final JdbcClient db;

    public Bookmarks(JdbcClient db) {
        this.db = db;
    }

    public record Bookmark(long documentId, long repositoryId, String path, String title, String sourceBlobSha, Instant createdAt) {}

    private static final String SELECT = """
        select a.document_id, d.repository_connection_id, d.path, d.title, a.source_blob_sha, a.created_at
        from annotations a join documents d on d.id = a.document_id
        where a.type = 'BOOKMARK' and a.user_id = :userId""";

    /** Bookmarking an already bookmarked document keeps the original bookmark. */
    Bookmark set(long userId, long documentId, String sourceBlobSha) {
        db.sql("""
                insert into annotations (user_id, document_id, source_blob_sha, type, created_at)
                values (:userId, :documentId, :sha, 'BOOKMARK', :now)
                on conflict (user_id, document_id) where type = 'BOOKMARK' do nothing""")
            .param("userId", userId).param("documentId", documentId).param("sha", sourceBlobSha)
            .param("now", Timestamp.from(Instant.now())).update();
        return db.sql(SELECT + " and a.document_id = :documentId").param("userId", userId).param("documentId", documentId)
            .query(Bookmarks::bookmark).single();
    }

    void clear(long userId, long documentId) {
        db.sql("delete from annotations where type = 'BOOKMARK' and user_id = :userId and document_id = :documentId")
            .param("userId", userId).param("documentId", documentId).update();
    }

    List<Bookmark> list(long userId) {
        return db.sql(SELECT + " order by a.created_at desc").param("userId", userId).query(Bookmarks::bookmark).list();
    }

    private static Bookmark bookmark(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Bookmark(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5), row.getTimestamp(6).toInstant());
    }
}
