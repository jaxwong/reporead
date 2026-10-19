package com.reporead.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test-only ViewModels in RepoReadApp's own decorated entries and display (rememberEntries, ShownEntry). No
 * direct-screen Activity-scoped viewModel(), production hooks, network or application data. AppNavigationTest drives
 * the production screen ViewModels through the real account gate.
 */
@RunWith(AndroidJUnit4::class)
class EntryLifetimeTest {
    private class OwnerProbe : ViewModel() {
        val cleared = AtomicBoolean()

        override fun onCleared() {
            cleared.set(true)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Observations only: the library's ViewModelStore, not this map, owns the probes.
    private val probes = ConcurrentHashMap<String, OwnerProbe>()
    private val covered = mutableStateOf(false)
    private lateinit var backStack: NavBackStack<Entry>
    private var scenario: ActivityScenario<TestHostActivity>? = null

    private fun launch() {
        TestHostActivity.content = {
            val stack =
                rememberSerializable(serializer = BackStackSerializer) {
                    NavBackStack(Entry("test-only-root", Screen.Repositories))
                }
            SideEffect { backStack = stack }
            val entries =
                rememberEntries(stack) { entry ->
                    val probe = viewModel { OwnerProbe() }
                    SideEffect { probes[entry.id] = probe }
                    var draft by rememberSaveable { mutableStateOf("test-only-draft:${entry.id}") }
                    Column {
                        Text("test-only-entry:${entry.id}")
                        TextField(draft, onValueChange = { draft = it })
                    }
                }
            if (covered.value) Text("test-only-account-gate") else ShownEntry(entries) { stack.removeLastOrNull() }
        }
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
        TestHostActivity.content = null
        TestHostActivity.restoredState = null
    }

    private fun awaitEntry(id: String) {
        awaitText("test-only-entry:$id")
        instrumentation.waitForIdleSync()
    }

    private fun probe(id: String) = checkNotNull(probes[id])

    private fun awaitCleared(vararg owners: OwnerProbe) = runBlocking {
        withTimeout(10_000) { while (owners.any { !it.cleared.get() }) delay(20) }
    }

    @Test
    fun equalDestinationsKeepIndependentOwnersUntilPopOrRemovalEvenWhileCovered() {
        launch()
        awaitEntry("test-only-root")
        val root = probe("test-only-root")
        val reader = Screen.Reader(1, "Test-only note")
        scenario!!.onActivity { backStack += Entry("test-only-first", reader) }
        awaitEntry("test-only-first")
        val first = probe("test-only-first")
        type("Test-only first draft")
        scenario!!.onActivity { backStack += Entry("test-only-second", reader) }
        awaitEntry("test-only-second")
        val second = probe("test-only-second")
        assertNotSame(first, second)
        type("Test-only second draft")
        scenario!!.onActivity { covered.value = true }
        awaitText("test-only-account-gate")
        scenario!!.recreate()
        scenario!!.onActivity { covered.value = false }
        awaitEntry("test-only-second")
        awaitText("Test-only second draft")
        assertSame(second, probe("test-only-second"))
        assertFalse(first.cleared.get())
        assertFalse(root.cleared.get())
        scenario!!.onActivity { backStack.removeLast() }
        awaitEntry("test-only-first")
        awaitText("Test-only first draft")
        assertSame(first, probe("test-only-first"))
        awaitCleared(second)
        assertFalse(first.cleared.get())
        assertFalse(root.cleared.get())
        scenario!!.onActivity { covered.value = true }
        awaitText("test-only-account-gate")
        scenario!!.onActivity {
            backStack.clear()
            backStack += Entry("test-only-fresh-root", Screen.Repositories)
        }
        awaitCleared(root, first)
        scenario!!.onActivity { covered.value = false }
        awaitEntry("test-only-fresh-root")
        assertNotSame(root, probe("test-only-fresh-root"))
    }

    @Test
    fun freshActivityRestoresEntryIdsAndDraftsButCreatesNewOwners() {
        launch()
        awaitEntry("test-only-root")
        val root = probe("test-only-root")
        scenario!!.onActivity { backStack += Entry("test-only-reader", Screen.Reader(1, "Test-only note")) }
        awaitEntry("test-only-reader")
        val reader = probe("test-only-reader")
        type("Test-only restored draft")
        val saved = saveActivityState(scenario!!)
        scenario!!.close()
        scenario = null
        awaitCleared(root, reader)
        TestHostActivity.restoredState = saved
        launch()
        awaitEntry("test-only-reader")
        awaitText("Test-only restored draft")
        assertNotSame(reader, probe("test-only-reader"))
        scenario!!.onActivity { assertEquals(listOf("test-only-root", "test-only-reader"), backStack.map { it.id }) }
        scenario!!.onActivity { backStack.removeLast() }
        awaitEntry("test-only-root")
        assertNotSame(root, probe("test-only-root"))
    }
}
