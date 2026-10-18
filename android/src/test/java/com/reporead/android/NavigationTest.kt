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
    fun everyReaderTargetSurvivesTheRealBackStackSaver() {
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = value is String
            }
        val stack =
            listOf(
                Screen.Reader(1, "Ordinary"),
                Screen.Reader(2, "Linked", OpenAt.Heading("Answer")),
                Screen.Reader(3, "Card", OpenAt.Highlight("mutation")),
                Screen.Reader(4, "Warning", OpenAt.Block("a".repeat(40), "b20")),
            )
        val saved = with(StackSaver) { scope.save(stack) }!!
        assertEquals(stack, StackSaver.restore(saved))
    }

    @Test
    fun preUpgradeReaderDestinationsStillRestoreWithoutBlockIdentity() {
        val old =
            arrayListOf(
                "5",
                "reader",
                "1",
                "Ordinary",
                "",
                "",
                "5",
                "reader",
                "2",
                "Linked",
                "Answer",
                "",
                "5",
                "reader",
                "3",
                "Card",
                "",
                "mutation",
            )
        assertEquals(
            listOf(
                Screen.Reader(1, "Ordinary"),
                Screen.Reader(2, "Linked", OpenAt.Heading("Answer")),
                Screen.Reader(3, "Card", OpenAt.Highlight("mutation")),
            ),
            StackSaver.restore(old),
        )
    }

    @Test
    fun aPreUpgradeFigureDestinationHasNoInventedSnapshot() {
        val old = arrayListOf("5", "figure", "1", "a".repeat(40), "t0", "Note")
        assertEquals(listOf(Screen.Figure(1, "a".repeat(40), "t0", "Note", null)), StackSaver.restore(old))
    }
}
