package com.reporead.android

import android.os.Bundle
import android.os.Parcel
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavBackStack
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reporead.android.data.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The back stack as the app saves it ([BackStackSerializer]), carried through a Parcel as process death does, and which
 * saved state the activity restores at all ([compatibleSavedState]).
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    private val sha = "a".repeat(40)

    @Test
    fun everyDestinationAndEntryIdSurvivesProcessDeath() {
        val stack =
            NavBackStack(
                Entry("root", Screen.Repositories),
                Entry("available", Screen.Available),
                Entry("folder", Screen.Folder(7, "test-only/notes", "deep/er")),
                Entry("ordinary", Screen.Reader(1, "Ordinary")),
                Entry("linked", Screen.Reader(2, "Linked", OpenAt.Heading("Answer"))),
                Entry("card", Screen.Reader(3, "Card", OpenAt.Highlight("mutation"))),
                Entry("warning", Screen.Reader(4, "Warning", OpenAt.Block(sha, "b20"))),
                Entry("search", Screen.Reader(5, "Search", OpenAt.Search(Passage(sha, "b1", 4, 10, "needle")))),
                Entry("notebook", Screen.Notebook),
                Entry("review-topics", Screen.ReviewTopics),
                Entry("review", Screen.Review("topic")),
                Entry("every-topic-review", Screen.Review(null)),
                Entry("topic", Screen.Practice("topic")),
                Entry("every-topic", Screen.Practice(null)),
                Entry("search", Screen.Search),
                // The same destination as "ordinary", told apart by its id alone.
                Entry("ordinary-again", Screen.Reader(1, "Ordinary")),
            )
        val parcel = Parcel.obtain()
        val restored =
            try {
                parcel.writeBundle(encodeToSavedState(BackStackSerializer, stack))
                parcel.setDataPosition(0)
                decodeFromSavedState(BackStackSerializer, checkNotNull(parcel.readBundle(javaClass.classLoader)))
            } finally {
                parcel.recycle()
            }
        assertEquals(stack.toList(), restored.toList())
    }

    @Test
    fun nonCurrentSavedStateFormatsAreNotRestored() {
        val saved = legacyNavigationState()
        assertNull(compatibleSavedState(saved))
        for (format in 0..(NAVIGATION_STATE_FORMAT + 1)) {
            if (format == NAVIGATION_STATE_FORMAT) continue
            saved.putInt(NAVIGATION_STATE_FORMAT_KEY, format)
            assertNull("Saved UI format $format must not restore", compatibleSavedState(saved))
        }
    }

    @Test
    fun compatibleStateDoesNotDependOnTheBuildNumber() {
        val saved =
            Bundle().apply {
                putInt(NAVIGATION_STATE_FORMAT_KEY, NAVIGATION_STATE_FORMAT)
                // Historical branch marker: compatible formats must not be tied to a commit count.
                putInt("savedByBuild", BuildConfig.VERSION_CODE - 1)
            }
        assertSame(saved, compatibleSavedState(saved))
        assertNull(compatibleSavedState(null))
    }
}

/**
 * Test-only literal StackSaver output from c3a55f9 (Library → folder → reader), not a retained legacy encoder. Android
 * writes its enclosing saved-instance-state Bundle. This constructs the historical format; it is not an APK capture.
 */
internal fun legacyNavigationState(): Bundle {
    TestHostActivity.content = {
        val stack = rememberSaveable {
            arrayListOf(
                "1",
                "repositories",
                "4",
                "folder",
                "7",
                "test-only/notes",
                "deep",
                "5",
                "reader",
                "1",
                "Note A",
                "",
                "",
            )
        }
        Text("Test-only legacy stack: $stack")
    }
    try {
        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            return saveActivityState(scenario).apply {
                assertFalse(isEmpty)
                remove(NAVIGATION_STATE_FORMAT_KEY)
            }
        }
    } finally {
        TestHostActivity.content = null
    }
}
