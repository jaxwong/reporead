package com.reporead.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Builds a test-only v6 database from the exported schema, then exercises the production v6→v7 migration. */
@RunWith(AndroidJUnit4::class)
class ReviewMigrationTest {
    @Test fun migratingPreservesSavedNotesAndUnsentHighlightsAndCreatesEmptyReviewTables() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "test-only-review-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val schema = JSONObject(instrumentation.context.assets.open("com.reporead.android.data.LocalStore/6.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indexes = entity.optJSONArray("indices")
                    if (indexes != null) for (j in 0 until indexes.length()) {
                        db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("insert into notes(documentId, blobSha, commitSha, path, title, html, fetchedAt, renderFormat) values (1, 'sha', 'commit', 'a.md', 'a', '<html/>', 0, 3)")
                db.execSQL("""insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, pending, rejection) values ('offline', null, 1, 'sha', 'b1', 0, 4, 'text', 'mine', 1, 0, 1, null)""")
                db.version = 6
            }
            val store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
            try {
                val dao = store.library()
                assertEquals("<html/>", dao.note(1)!!.html)
                val highlight = checkNotNull(dao.annotation("offline"))
                assertEquals("HIGHLIGHT", highlight.type)
                assertEquals("mine", highlight.note)
                assertTrue(highlight.pending)
                assertNull(highlight.question)
                assertTrue(dao.reviews().first().isEmpty())
                assertNull(dao.reviewLimit().first())
                dao.saveReviewLimit(ReviewLimitRow(sessionLimit = 40))
                assertEquals(40, dao.reviewLimit().first())
            } finally { store.close() }
        } finally { check(context.deleteDatabase(name)) { "Cannot delete test-only migration database $name" } }
    }
}
