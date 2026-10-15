package com.reporead.annotation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Document-level bookmarks, stored in the annotation domain as type BOOKMARK. Setting and clearing are idempotent. */
@Component
public class Bookmarks {
    private final JdbcClient db;

    public Bookmarks(JdbcClient db) {
        this.db = db;
    }

    /** A note deleted upstream keeps its bookmark; the note's title, path and removal are in its document list. */
    public record Bookmark(long documentId, Instant createdAt) {}

    private static final String SELECT = """
        select a.document_id, a.created_at from annotations a where a.type = 'BOOKMARK' and a.user_id = :userId""";

    /** Bookmarking an already bookmarked document keeps the original bookmark. */
    Bookmark set(long userId, long documentId) {
        db.sql("""
                insert into annotations (user_id, document_id, type, created_at, updated_at)
                values (:userId, :documentId, 'BOOKMARK', :now, :now)
                on conflict (user_id, document_id) where type = 'BOOKMARK' do nothing""")
                .param("userId", userId)
                .param("documentId", documentId)
                .param("now", Timestamp.from(Instant.now()))
                .update();
        return db.sql(SELECT + " and a.document_id = :documentId")
                .param("userId", userId)
                .param("documentId", documentId)
                .query(Bookmarks::bookmark)
                .single();
    }

    void clear(long userId, long documentId) {
        db.sql("delete from annotations where type = 'BOOKMARK' and user_id = :userId and document_id = :documentId")
                .param("userId", userId)
                .param("documentId", documentId)
                .update();
    }

    List<Bookmark> list(long userId) {
        return db.sql(SELECT + " order by a.created_at desc")
                .param("userId", userId)
                .query(Bookmarks::bookmark)
                .list();
    }

    private static Bookmark bookmark(ResultSet row, int n) throws SQLException {
        return new Bookmark(
                row.getLong("document_id"), row.getTimestamp("created_at").toInstant());
    }
}
