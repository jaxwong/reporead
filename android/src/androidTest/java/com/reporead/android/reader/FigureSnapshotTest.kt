package com.reporead.android.reader

import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.Screen
import com.reporead.android.TestHostActivity
import com.reporead.android.awaitText
import com.reporead.android.core.network.Api
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.Sync
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** The real full-screen host must not substitute a newer image snapshot for an older displayed page. */
@RunWith(AndroidJUnit4::class)
class FigureSnapshotTest {
    @Test
    fun aFigureRefusesAnImageOnlyUpdateBeforeAndAfterRecreation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        val files = File(context.cacheDir, "test-only-figure-snapshot-${UUID.randomUUID()}")
        check(files.mkdirs())
        try {
            val sha = "a".repeat(40)
            store
                .library()
                .saveNote(
                    NoteRow(
                        1,
                        sha,
                        "a.md",
                        "Test-only figure",
                        "<main/>",
                        0,
                        snapshotCommitSha = "c".repeat(40),
                    )
                )
            val sync =
                Sync(
                    Api("http://127.0.0.1:1", { error("A replaced figure must not fetch images") }) {},
                    store,
                    files,
                ) {}
            val screen = Screen.Figure(1, sha, "t0", "Test-only figure", "b".repeat(40))
            TestHostActivity.content = { MaterialTheme { FigureScreen(sync, store.library(), screen, onBack = {}) } }
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                val message = "This note was updated since it was opened. Go back and open the table or diagram again."
                awaitText(message)
                scenario.recreate()
                awaitText(message)
            }
        } finally {
            TestHostActivity.content = null
            store.close()
            check(files.deleteRecursively())
        }
    }
}
