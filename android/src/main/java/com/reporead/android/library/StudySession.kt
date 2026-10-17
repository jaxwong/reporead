package com.reporead.android.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reporead.android.ui.counted
import java.io.Serializable

/**
 * One Practice or Review session's progress, and the one owner of how a session moves: the items it [started] with, the
 * ones still to ask ([queue], the front one asked now), and whether that one's answer is [revealed]. Pure, and kept
 * whole across recreation. Which items already have a saved first grade is read from what is stored, never kept here,
 * so a grade saved just before the screen was recreated is never saved twice.
 */
internal data class StudySession(
    val started: List<String>,
    val queue: List<String> = started,
    val revealed: Boolean = false,
) : Serializable {
    /** The item asked now; null once the session is complete. */
    val current: String?
        get() = queue.firstOrNull()

    fun reveal() = copy(revealed = true)

    /** On past this item: skipped, or it cannot be asked as it is. */
    fun skip() = copy(queue = queue.drop(1), revealed = false)

    /** This item goes to the back, still to be answered: missed, or just confirmed. */
    fun requeue() = copy(queue = queue.drop(1) + queue.take(1), revealed = false)

    /**
     * After grading this item: Again, a miss, sends it to the back, to be answered again until it is answered right;
     * Hard was right, so it leaves the session and keeps its gap.
     */
    fun graded(grade: Int) = if (grade < 3) requeue() else skip()

    /** How many of the items it started with have a saved first grade, given the [graded] ones in storage. */
    fun completed(graded: Set<String>) = started.count { it in graded }
}

/** A session's first row: what is left and what the item asked now is ([label]), and [skip] to move past it. */
@Composable
internal fun SessionHeader(label: String, skip: String, enabled: Boolean, onSkip: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = enabled, onClick = onSkip) { Text(skip) }
    }
}

/** The question alone first: its answer shows only after an attempt. */
@Composable
internal fun AttemptFirst(onReveal: () -> Unit) {
    Text("Answer it aloud or in writing first, then check it.")
    Button(onClick = onReveal) { Text("Show answer") }
}

/** Above the answer of an item graded earlier in this session, whose grade now is not saved. */
@Composable
internal fun AskedAgain() {
    Text(
        "Asked again: its first grade is saved. This grade only decides whether it comes back in this session.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The grades in one row under the answer, so grading never needs scrolling past it; Again, the only failing grade, is
 * set apart from the three that pass. Each shows the days until the card is due again ([gaps], by grade); null when
 * this grade is not saved, so does not schedule.
 */
@Composable
internal fun GradeBar(enabled: Boolean, gaps: Map<Int, Long>?, onGrade: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(GRADE_MEANING, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((grade, label) in GRADES) {
                val modifier = Modifier.weight(1f)
                val padding = PaddingValues(horizontal = 4.dp)
                val content: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label)
                        gaps?.let {
                            Text(
                                counted(it.getValue(grade).toInt(), "day"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (grade < 3)
                    OutlinedButton(
                        enabled = enabled,
                        modifier = modifier,
                        contentPadding = padding,
                        onClick = { onGrade(grade) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        content()
                    }
                else
                    Button(
                        enabled = enabled,
                        modifier = modifier,
                        contentPadding = padding,
                        onClick = { onGrade(grade) },
                    ) {
                        content()
                    }
            }
        }
    }
}
