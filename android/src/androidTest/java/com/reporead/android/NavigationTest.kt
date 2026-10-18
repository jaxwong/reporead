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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The back stack as the app saves it ([BackStackSerializer]), carried through a Parcel as process death does, and which
 * saved state the activity restores at all ([savedByThisBuild]).
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
                Entry("figure", Screen.Figure(1, sha, "t0", "Note", "b".repeat(40))),
                // From a page saved before its snapshot was recorded: none is invented.
                Entry("unknown-snapshot", Screen.Figure(1, sha, "t0", "Note", null)),
                Entry("notebook", Screen.Notebook),
                Entry("review", Screen.Review),
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

    /** A real saved instance state, as an activity saves one when it records no build: every build before ADR-27. */
    private fun savedWithoutBuild(): Bundle {
        TestHostActivity.content = { Text(rememberSaveable { "test-only" }) }
        try {
            ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                val saved = Bundle()
                scenario.onActivity {
                    InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
                }
                assertFalse(saved.isEmpty)
                return saved
            }
        } finally {
            TestHostActivity.content = null
        }
    }

    /**
     * The one-time reset across the change to Navigation 3: this build keeps its back stack where the previous one kept
     * its own in another format, so restoring that state threw a ClassCastException (shown with state recorded from
     * c3a55f9). An activity starts afresh instead, from any other build's state.
     */
    @Test
    fun stateSavedByAnotherBuildIsNotRestored() {
        val saved = savedWithoutBuild()
        assertNull(savedByThisBuild(saved))
        saved.putInt(SAVED_BY_BUILD, BuildConfig.VERSION_CODE - 1)
        assertNull(savedByThisBuild(saved))
    }

    @Test
    fun stateSavedByThisBuildIsRestoredAfterProcessDeath() {
        val saved = savedWithoutBuild().apply { putInt(SAVED_BY_BUILD, BuildConfig.VERSION_CODE) }
        val parcel = Parcel.obtain()
        val restored =
            try {
                parcel.writeBundle(saved)
                parcel.setDataPosition(0)
                checkNotNull(parcel.readBundle(javaClass.classLoader))
            } finally {
                parcel.recycle()
            }
        assertEquals(saved.keySet(), savedByThisBuild(restored)?.keySet())
    }
}
