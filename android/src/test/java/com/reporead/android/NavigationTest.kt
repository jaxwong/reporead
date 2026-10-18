package com.reporead.android

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
    @Test
    fun figureSnapshotSurvivesTheRealBackStackSaver() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = value is String
            }
        val stack = listOf(Screen.Reader(1, "Note"), Screen.Figure(1, "a".repeat(40), "t0", "Note", "b".repeat(40)))
        val saved = with(StackSaver) { scope.save(stack) }!!
        assertEquals(stack, StackSaver.restore(saved))
    }

    @Test
    fun aPreUpgradeFigureDestinationHasNoInventedSnapshot() {
        val old = arrayListOf("5", "figure", "1", "a".repeat(40), "t0", "Note")
        assertEquals(listOf(Screen.Figure(1, "a".repeat(40), "t0", "Note", null)), StackSaver.restore(old))
    }
}
