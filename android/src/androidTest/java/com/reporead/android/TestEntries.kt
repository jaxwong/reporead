package com.reporead.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.navigation3.runtime.NavBackStack

/**
 * A screen test's content inside RepoReadApp's own decorated entries and display ([rememberEntries], [ShownEntry]), so
 * a screen's ViewModel belongs to a back-stack entry, as in the app, never to the Activity. The test-only stack starts
 * at [root]; setting [shown] false hides the display, as the account screen does, and keeps the entries.
 */
internal class TestEntries(root: Screen) {
    private val first = Entry("test-only-root", root)
    /** The stack as last composed; tests change it on the main thread (ActivityScenario.onActivity). */
    lateinit var stack: NavBackStack<Entry>
        private set

    val shown = mutableStateOf(true)

    /** Replaces the shown entry with a new one for [screen], as navigating away and to it would. */
    fun replaceTop(screen: Screen, id: String) {
        stack.removeAt(stack.lastIndex)
        stack += Entry(id, screen)
    }

    fun content(draw: @Composable (Entry) -> Unit): @Composable () -> Unit = {
        val backStack = rememberSerializable(serializer = BackStackSerializer) { NavBackStack(first) }
        SideEffect { stack = backStack }
        val entries = rememberEntries(backStack, draw)
        if (shown.value) ShownEntry(entries) { backStack.removeLastOrNull() }
    }
}
