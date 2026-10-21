package com.reporead;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs the migrations over data an earlier version stored, which the suite's freshly migrated database never holds. */
class MigrationTest {
    private static final String SHA = "a".repeat(40);
    private static final String HIGHLIGHT_MUTATION = "11111111-1111-1111-1111-111111111111";
    private static final String CARD_MUTATION = "22222222-2222-2222-2222-222222222222";
    private static final String DELETED_MUTATION = "33333333-3333-3333-3333-333333333333";

    private static Flyway flyway(DataSource database, String target) {
        return Flyway.configure().dataSource(database).target(target).load();
    }

    /** Test-only V8 data: one user's note with a bookmark, and a highlight and a card created by mutations. */
    private static long noteAtV8(JdbcClient db) {
        db.sql("INSERT INTO users (github_user_id, login) VALUES (42, 'test-only')")
                .update();
        db.sql("""
                INSERT INTO repository_connections (user_id, github_repository_id, installation_id, owner, name, default_branch, last_synced_commit_sha)
                VALUES (1, 11, 7, 'test-only', 'notes', 'main', :sha)""").param("sha", SHA).update();
        long note = db.sql("""
                INSERT INTO documents (repository_connection_id, path, title, current_blob_sha, current_commit_sha, last_synced_at)
                VALUES (1, 'note.md', 'note', :sha, :sha, now()) RETURNING id""").param("sha", SHA).query(Long.class).single();
        db.sql(
                        "INSERT INTO annotations (user_id, document_id, source_blob_sha, type, created_at, updated_at) VALUES (1, :note, :sha, 'BOOKMARK', now(), now())")
                .param("note", note)
                .param("sha", SHA)
                .update();
        return note;
    }

    private static long annotationAtV8(JdbcClient db, long note, String type) {
        return db.sql("""
                INSERT INTO annotations (user_id, document_id, source_blob_sha, type, created_at, updated_at, resolved_blob_sha, question)
                VALUES (1, :note, :sha, :type, now(), now(), :sha, CASE WHEN :type = 'CARD' THEN 'Test-only question?' END) RETURNING id""")
                .param("note", note)
                .param("sha", SHA)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private static void mutationAtV8(JdbcClient db, String mutationId, Long annotationId) {
        db.sql(
                        "INSERT INTO annotation_mutations (user_id, mutation_id, request_hash, annotation_id, created_at) VALUES (1, CAST(:mutation AS uuid), '\\x00', :id, now())")
                .param("mutation", mutationId)
                .param("id", annotationId)
                .update();
    }

    private static boolean hasColumn(JdbcClient db, String table, String column) {
        return db.sql(
                                "SELECT count(*) FROM information_schema.columns WHERE table_name = :table AND column_name = :column")
                        .param("table", table)
                        .param("column", column)
                        .query(Integer.class)
                        .single()
                == 1;
    }

    @Test
    void existingHighlightsAndCardsAreNamedByTheMutationThatCreatedThemAndDeletedOnesStayRefused() {
        var database = TestEnvironment.emptyDatabase();
        var db = JdbcClient.create(database);
        flyway(database, "8").migrate();
        long note = noteAtV8(db);
        mutationAtV8(db, HIGHLIGHT_MUTATION, annotationAtV8(db, note, "HIGHLIGHT"));
        mutationAtV8(db, CARD_MUTATION, annotationAtV8(db, note, "CARD"));
        // The record of a highlight deleted before the upgrade: no annotation left to name it.
        mutationAtV8(db, DELETED_MUTATION, null);

        flyway(database, "latest").migrate();

        assertEquals(
                Arrays.asList(null, HIGHLIGHT_MUTATION, CARD_MUTATION),
                db.sql("SELECT mutation_id::text FROM annotations ORDER BY id")
                        .query(String.class)
                        .list());
        assertEquals(
                List.of(HIGHLIGHT_MUTATION, CARD_MUTATION, DELETED_MUTATION),
                db.sql("SELECT mutation_id::text FROM annotation_mutations ORDER BY mutation_id")
                        .query(String.class)
                        .list());
        assertFalse(hasColumn(db, "annotation_mutations", "annotation_id"));
    }

    @Test
    void columnsNothingReadAreDroppedAndTheirRowsKept() {
        var database = TestEnvironment.emptyDatabase();
        var db = JdbcClient.create(database);
        flyway(database, "8").migrate();
        noteAtV8(db);
        db.sql("UPDATE repository_connections SET last_synced_at = now()").update();

        flyway(database, "latest").migrate();

        assertFalse(hasColumn(db, "repository_connections", "last_synced_at"));
        assertFalse(hasColumn(db, "repository_connections", "installation_id"));
        assertFalse(hasColumn(db, "annotations", "source_blob_sha"));
        assertEquals(
                "BOOKMARK",
                db.sql("SELECT type FROM annotations").query(String.class).single());
        assertEquals(
                SHA,
                db.sql("SELECT last_synced_commit_sha FROM repository_connections")
                        .query(String.class)
                        .single());
    }

    @Test
    void reviewClientTimeExpansionPreservesOldRowsAndThePreviousReleasesInsert() {
        var database = TestEnvironment.emptyDatabase();
        var db = JdbcClient.create(database);
        flyway(database, "8").migrate();
        long card = annotationAtV8(db, noteAtV8(db), "CARD");
        mutationAtV8(db, CARD_MUTATION, card);
        flyway(database, "14").migrate();
        String oldInsert = """
                INSERT INTO review_log(user_id, mutation_id, annotation_id, grade, reviewed_at, blob_sha)
                VALUES (1, CAST(:mutation AS uuid), :card, 4, '2026-10-19T00:00:00Z', :sha)""";
        db.sql(oldInsert)
                .param("mutation", HIGHLIGHT_MUTATION)
                .param("card", card)
                .param("sha", SHA)
                .update();

        flyway(database, "latest").migrate();
        assertEquals(
                1,
                db.sql(
                                "SELECT count(*) FROM review_log WHERE client_reviewed_at IS NULL AND reviewed_at = '2026-10-19T00:00:00Z'")
                        .query(Integer.class)
                        .single());
        db.sql(oldInsert)
                .param("mutation", DELETED_MUTATION)
                .param("card", card)
                .param("sha", SHA)
                .update();
        assertEquals(
                2,
                db.sql("SELECT count(*) FROM review_log WHERE client_reviewed_at IS NULL")
                        .query(Integer.class)
                        .single());
        flyway(database, "latest").migrate();
        assertEquals(
                2,
                db.sql("SELECT count(*) FROM review_log").query(Integer.class).single());
    }

    @Test
    void aHighlightWithoutAMutationRecordStopsTheMigrationAndChangesNothing() {
        var database = TestEnvironment.emptyDatabase();
        var db = JdbcClient.create(database);
        flyway(database, "8").migrate();
        annotationAtV8(db, noteAtV8(db), "HIGHLIGHT");

        assertThrows(FlywayException.class, () -> flyway(database, "9").migrate());

        assertEquals("8", flyway(database, "9").info().current().getVersion().getVersion());
        assertFalse(hasColumn(db, "annotations", "mutation_id"));
        assertTrue(hasColumn(db, "annotation_mutations", "annotation_id"));
    }
}
