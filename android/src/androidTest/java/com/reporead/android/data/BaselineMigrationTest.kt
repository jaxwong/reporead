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

/** Builds a test-only v7 database from the exported schema, then exercises the production v7→v8 migration. */
@RunWith(AndroidJUnit4::class)
class BaselineMigrationTest {
    @Test fun migratingKeepsUnsentWorkAndAddsNoDeletionsOrBaselines() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "test-only-baseline-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val schema = JSONObject(instrumentation.context.assets.open("com.reporead.android.data.LocalStore/7.json").bufferedReader().use { it.readText() })
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
                db.execSQL("insert into documents(id, repositoryId, path, title, blobSha) values (1, 7, 'a.md', 'a', 'new')")
                db.execSQL("""insert into reading_states(documentId, title, path, lastReadBlobSha, progressPercent, anchorJson, lastReadAt, pending)
                    values (1, 'a', 'a.md', 'old', 40, '{}', 1, 1)""")
                db.execSQL("""insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, pending, rejection) values ('offline', null, 1, 'sha', 'b1', 0, 4, 'text', 'mine', 1, 0, 1, null)""")
                db.version = 7
            }
            val store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
            try {
                val dao = store.library()
                val highlight = checkNotNull(dao.annotation("offline"))
                assertTrue(highlight.pending)
                assertFalse(highlight.deleting)
                assertEquals(listOf("offline"), dao.annotations(1).first().map { it.mutationId })
                assertTrue(dao.reading(1)!!.pending)
                assertNull(dao.changeBaseline(1))
                // A note read in an older version is still listed as updated without a baseline.
                assertEquals(listOf(1L to "old"), dao.updatedSinceRead().first().map { it.documentId to it.lastReadBlobSha })
            } finally { store.close() }
        } finally { check(context.deleteDatabase(name)) { "Cannot delete test-only migration database $name" } }
    }
}
