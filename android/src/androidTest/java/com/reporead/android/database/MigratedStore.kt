package com.reporead.android.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONObject

/**
 * Test-only: the version Room migrates to, as the newest exported schema names it (Room writes one file per
 * `@Database(version)`), so a migration test never hard-codes a version the next schema change would make wrong.
 */
internal fun currentSchemaVersion(): Int =
    checkNotNull(
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .list("com.reporead.android.database.RepoReadDatabase")
        ) {
            "Exported Room schemas are missing from the androidTest assets"
        }
        .maxOf { it.removeSuffix(".json").toInt() }

/**
 * Test-only: builds a database at the exported schema [version], lets [fill] add rows, then opens it with the current
 * schema, which runs every production migration since [version], and hands its DAO to [verify]. The database is deleted
 * afterwards.
 */
internal suspend fun withMigratedStore(
    version: Int,
    fill: (SQLiteDatabase) -> Unit,
    verify: suspend (RepoReadDao) -> Unit,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val name = "test-only-migration-from-$version-${UUID.randomUUID()}.db"
    val file = context.getDatabasePath(name)
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val schema =
        JSONObject(
                instrumentation.context.assets
                    .open("com.reporead.android.database.RepoReadDatabase/$version.json")
                    .bufferedReader()
                    .use { it.readText() }
            )
            .getJSONObject("database")
    try {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices")
                if (indexes != null)
                    for (j in 0 until indexes.length()) {
                        db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            fill(db)
            db.version = version
        }
        val store = RepoReadDatabase.open(context, name)
        try {
            verify(store.dao())
        } finally {
            store.close()
        }
    } finally {
        check(context.deleteDatabase(name)) { "Cannot delete test-only migration database $name" }
    }
}
