package com.reporead.android.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.reporead.android.R
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.counted
import java.io.Serializable
import kotlin.random.Random

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

    /** This item goes to the back, still to be answered: it was missed. */
    fun requeue() = copy(queue = queue.drop(1) + queue.take(1), revealed = false)

    /**
     * After grading this item: Again, a miss, sends it to the back, to be answered again until it is answered right;
     * Hard or better is a pass, so it leaves the session. The scheduler separately determines its next gap.
     */
    fun graded(grade: Int) = if (isMiss(grade)) requeue() else skip()

    /** How many of the items it started with have a saved first grade, given the [graded] ones in storage. */
    fun completed(graded: Set<String>) = started.count { it in graded }
}

/**
 * Practice and Review sessions alternate notes: shuffled note groups, then one item per note per round, so remaining
 * items never disappear. [note] is each item's document.
 */
internal fun <T> interleaveByNote(items: List<T>, seed: Int, note: (T) -> Long): List<T> {
    val random = Random(seed)
    val groups = items.groupBy(note).values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round ->
        groups.mapNotNull { it.getOrNull(round) }
    }
}

/**
 * A session's first row: what is left and what the item asked now is ([label]), what each grade means on request, and
 * [skip] to move past it.
 */
@Composable
internal fun SessionHeader(label: String, skip: String, enabled: Boolean, onSkip: () -> Unit) {
    var grading by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { grading = true }) { AppIcon(R.drawable.ic_info, "How to grade") }
        TextButton(enabled = enabled, onClick = onSkip) { Text(skip) }
    }
    if (grading)
        AlertDialog(
            onDismissRequest = { grading = false },
            title = { Text("How to grade") },
            text = { Text(GRADE_MEANING) },
            confirmButton = { TextButton(onClick = { grading = false }) { Text("OK") } },
        )
}

/**
 * Measures the complete answer at its final size during the attempt. The keyed question owns this slot: [revealed]
 * requests exposure, while the content reports preparation complete (including a terminal error) via its callback.
 * Neither a pending reveal nor revealing a prepared answer changes the layout underneath.
 */
@Composable
internal fun ColumnScope.AnswerSlot(
    revealed: Boolean,
    onReveal: () -> Unit,
    content: @Composable ColumnScope.(visible: Boolean, onPrepared: () -> Unit) -> Unit,
) {
    var prepared by remember { mutableStateOf(false) }
    val visible = revealed && prepared
    Box(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (visible) 1f else 0f }
                .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content(visible) { prepared = true }
        }
        if (!visible) {
            // Alpha and cleared semantics don't stop touches reaching the already measured answer.
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (revealed) Text("Preparing answer…")
                else {
                    Text("Answer it aloud or in writing first, then check it.")
                    Button(onClick = onReveal) { Text("Show answer") }
                }
            }
        }
    }
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
 * this grade is not saved, so does not schedule. What each means is in the session header's help.
 */
@Composable
internal fun GradeBar(enabled: Boolean, gaps: Map<Int, Long>?, onGrade: (Int) -> Unit) {
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
            if (isMiss(grade))
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
