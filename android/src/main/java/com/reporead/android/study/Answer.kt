package com.reporead.android.study

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NoteRow
import com.reporead.android.reader.resolveNoteLink
import com.reporead.android.reader.showIsolatedPage
import com.reporead.android.sync.Sync
import org.json.JSONObject

/** The most of the screen height the bank's checklist takes above the section; it scrolls inside that. */
private const val GUIDE_HEIGHT = 0.3f

/** Where a link on an answer line leads, among the bank's repository's saved notes. */
private sealed interface LinkedNote {
    data object Loading : LinkedNote

    data class Problem(val text: String) : LinkedNote

    data class Found(val document: DocumentRow, val saved: NoteRow?) : LinkedNote
}

/**
 * Prepares the linked section while the question is attempted, but exposes no answer until [revealed]. Then shows the
 * bank's grading checklist and its linked core section (one at a time), from the saved copy, filling [modifier]. [push]
 * opens the linked note at that heading.
 */
@Composable
internal fun BankAnswer(
    question: StudyQuestion,
    revealed: Boolean,
    bankDocumentId: Long,
    bankPath: String,
    sync: Sync,
    dao: LibraryDao,
    modifier: Modifier,
    push: (Screen) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (revealed) {
            Text(question.answer.text, style = MaterialTheme.typography.titleMedium)
            Text(
                "Your grade confirms only this Canonical line. The linked section is supplementary.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (revealed && question.guide.isNotEmpty()) {
            val windowHeight = LocalWindowInfo.current.containerSize.height
            val most = with(LocalDensity.current) { (windowHeight * GUIDE_HEIGHT).toDp() }
            Column(Modifier.heightIn(max = most).verticalScroll(rememberScrollState())) {
                for (line in question.guide) Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val links = question.answer.links
        if (links.isEmpty()) {
            if (revealed)
                Text(
                    "This answer line links no section: “${question.answer.text}”. Add a link to the section that answers it."
                )
            return@Column
        }
        var selected by rememberSaveable(question.blockId) { mutableIntStateOf(0) }
        if (revealed && links.size > 1)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                links.forEachIndexed { index, link ->
                    FilterChip(
                        selected = selected == index,
                        onClick = { selected = index },
                        label = { Text("${index + 1}. ${link.text}") },
                    )
                }
            }
        key(bankDocumentId, question.blockId, selected) {
            AnswerSection(links[selected], revealed, bankDocumentId, bankPath, sync, dao, push)
        }
    }
}

@Composable
private fun ColumnScope.AnswerSection(
    link: AnswerLink,
    revealed: Boolean,
    bankDocumentId: Long,
    bankPath: String,
    sync: Sync,
    dao: LibraryDao,
    push: (Screen) -> Unit,
) {
    val linked by
        produceState<LinkedNote>(LinkedNote.Loading, link) {
            val bank = dao.document(bankDocumentId)
            value =
                if (bank == null)
                    LinkedNote.Problem(
                        "This bank isn't in a saved note list, so its links can't be followed. Refresh the repository."
                    )
                else {
                    val matches =
                        resolveNoteLink(link.target, link.path, bankPath, dao.listedDocuments(bank.repositoryId))
                    when (matches.size) {
                        0 ->
                            LinkedNote.Problem(
                                "No note “${link.path ?: link.target}” in this repository's saved list. Refresh the repository if it is new."
                            )
                        1 -> LinkedNote.Found(matches.single(), dao.note(matches.single().id))
                        else ->
                            LinkedNote.Problem(
                                "Several notes are named “${link.target}”: ${matches.joinToString { it.path }}. Link one by its path."
                            )
                    }
                }
        }
    when (val note = linked) {
        LinkedNote.Loading -> Spacer(Modifier.weight(1f))
        is LinkedNote.Problem -> if (revealed) Text(note.text)
        is LinkedNote.Found -> {
            if (revealed)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(note.document.title, link.heading?.let { "# ${link.text}" }).joinToString(" "),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            push(
                                Screen.Reader(note.document.id, note.document.title, link.heading?.let(OpenAt::Heading))
                            )
                        }
                    ) {
                        Text("Open note")
                    }
                }
            val saved = note.saved
            if (saved == null) {
                if (revealed)
                    Text("Not saved on this phone yet, so this section can't be shown here. Open the note to fetch it.")
                return
            }
            var ready by remember(saved.blobSha, link) { mutableStateOf(false) }
            var notice by remember(saved.blobSha, link) { mutableStateOf<String?>(null) }
            var failed by remember(saved.blobSha, link) { mutableStateOf(false) }
            if (revealed) {
                Text("Linked section · saved copy", style = MaterialTheme.typography.labelMedium)
                if (saved.blobSha != note.document.blobSha)
                    Text("The saved copy is an older version of this note.", style = MaterialTheme.typography.bodySmall)
                notice?.let { Text(it) }
            }
            if (!failed)
                AndroidView(
                    factory = { context ->
                        sectionView(
                            context,
                            sync,
                            saved,
                            link.heading,
                            onReady = { ready = true },
                            onProblem = { notice = it },
                            onFailed = { failed = true },
                        )
                    },
                    update = { view ->
                        val visible = revealed && ready
                        view.alpha = if (visible) 1f else 0f
                        view.importantForAccessibility =
                            if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        view.isFocusableInTouchMode = visible
                        view.isFocusable = visible
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
        }
    }
}

/**
 * The saved [note] as an isolated reader page showing only the section under [heading] (the whole note for null). It
 * stays hidden until the rest is gone, so the top of the note never flashes first, and hidden when the heading is not
 * in this version: the problem is reported instead of showing an unrelated part. Composition owns visibility once
 * [onReady] runs, so preparation cannot reveal an answer. The page never navigates.
 */
// Consumes hidden-page touches only; visible WebView gestures are unchanged.
@SuppressLint("ClickableViewAccessibility")
private fun sectionView(
    context: Context,
    sync: Sync,
    note: NoteRow,
    heading: String?,
    onReady: () -> Unit,
    onProblem: (String) -> Unit,
    onFailed: () -> Unit,
): WebView =
    object : WebView(context) {
            // WebView's virtual nodes bypass NO_HIDE_DESCENDANTS for services that include unimportant views.
            override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? =
                if (alpha == 0f) null else super.getAccessibilityNodeProvider()
        }
        .apply {
            setOnTouchListener { view, _ -> view.alpha == 0f }
            showIsolatedPage(
                sync.images,
                note,
                "Answer section",
                "window.reporead.showSection(${heading?.let(JSONObject::quote) ?: "null"})",
                onFailed = {
                    onProblem("${note.title} could not be shown.")
                    onFailed()
                },
            ) { found ->
                if (found) onReady()
                else {
                    Log.w(
                        "RepoRead",
                        "Answer heading not found; documentId=${note.documentId} blobSha=${note.blobSha}",
                    )
                    onProblem(
                        "No section “$heading” in the saved copy of ${note.title}; the answer link may be out of date."
                    )
                }
                false
            }
        }
