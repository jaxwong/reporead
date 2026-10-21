package com.reporead.android.study

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.reader.resolveNoteLink
import com.reporead.android.ui.AppIcon

/** The most of the screen height a question's prompt takes above its answer; long prompt material scrolls inside it. */
private const val PROMPT_HEIGHT = 0.4f

/** Where a link on an answer line leads, among the bank's repository's saved notes. */
private sealed interface LinkedNote {
    data class Problem(val text: String) : LinkedNote

    data class Found(val document: DocumentEntity) : LinkedNote
}

/** A bank question as asked: its heading with any follow-ups, then the prompt material the bank shows with it. */
@Composable
internal fun QuestionPrompt(question: StudyQuestion) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val most = with(LocalDensity.current) { (windowHeight * PROMPT_HEIGHT).toDp() }
    Column(
        Modifier.heightIn(max = most).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(question.prompt, style = MaterialTheme.typography.titleLarge)
        for (block in question.material) when (block) {
            is PromptBlock.Paragraph -> Text(block.text)
            is PromptBlock.Items -> Points(block.items, block.ordered)
            is PromptBlock.Code ->
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                )
        }
    }
}

/**
 * A bank question's answer inside AnswerSlot: its Short answer, what the interviewer is testing and the checklist to
 * grade against, any worked solution, and one row per linked section that opens its note at that heading. All of it is
 * from the bank's saved version that the question (or card) was read from. [visible] gates keyboard focus and links;
 * AnswerSlot owns Compose exposure and pointer input. [onPrepared] reports the links resolved among the repository's
 * saved notes: two Room reads, no network call.
 */
@Composable
internal fun BankAnswer(
    question: StudyQuestion,
    visible: Boolean,
    bankDocumentId: Long,
    bankPath: String,
    dao: RepoReadDao,
    modifier: Modifier,
    push: (Screen) -> Unit,
    onPrepared: () -> Unit,
) {
    val linked by
        produceState<List<LinkedNote>?>(null, question, bankDocumentId) {
            value = linkedNotes(question.answer.links, bankDocumentId, bankPath, dao)
        }
    val notes = linked
    if (notes == null) {
        Spacer(modifier)
        return
    }
    LaunchedEffect(notes) { onPrepared() }
    Column(
        modifier.verticalScroll(rememberScrollState(), enabled = visible),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Short answer", style = MaterialTheme.typography.labelLarge)
                Text(question.shortAnswer, style = MaterialTheme.typography.titleMedium)
            }
        }
        Part("Interviewer is testing") { Text(question.testing) }
        Part("A complete answer should") { Points(question.checklist, ordered = false) }
        question.worked?.let { worked ->
            Part("Worked answer") {
                // Hidden links would still take keyboard focus, so the text links only once shown.
                Text(
                    buildAnnotatedString {
                        append(worked.text)
                        if (visible)
                            for (link in worked.links) {
                                val start = worked.text.indexOf(link.text)
                                if (start >= 0) addLink(LinkAnnotation.Url(link.url), start, start + link.text.length)
                            }
                    }
                )
            }
        }
        for ((link, note) in question.answer.links.zip(notes)) when (note) {
            is LinkedNote.Problem -> Text(note.text)
            is LinkedNote.Found ->
                OutlinedCard(
                    onClick = {
                        push(Screen.Reader(note.document.id, note.document.title, link.heading?.let(OpenAt::Heading)))
                    },
                    enabled = visible,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(R.drawable.ic_description, null)
                        Column(Modifier.weight(1f)) {
                            Text(link.text, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Linked section · ${note.document.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
        }
    }
}

/** The notes [links] on a bank's answer line lead to, among its repository's saved note list. */
private suspend fun linkedNotes(
    links: List<AnswerLink>,
    bankDocumentId: Long,
    bankPath: String,
    dao: RepoReadDao,
): List<LinkedNote> {
    val bank =
        dao.document(bankDocumentId)
            ?: return links.map {
                LinkedNote.Problem(
                    "This bank isn't in a saved note list, so its links can't be followed. Refresh the repository."
                )
            }
    val listed = dao.listedDocuments(bank.repositoryId)
    return links.map { link ->
        val matches = resolveNoteLink(link.target, link.path, bankPath, listed)
        when (matches.size) {
            0 ->
                LinkedNote.Problem(
                    "No note “${link.path ?: link.target}” in this repository's saved list. Refresh the repository if it is new."
                )
            1 -> LinkedNote.Found(matches.single())
            else ->
                LinkedNote.Problem(
                    "Several notes are named “${link.target}”: ${matches.joinToString { it.path }}. Link one by its path."
                )
        }
    }
}

/** One labelled part of an answer. */
@Composable
private fun Part(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** List items, bulleted or numbered. */
@Composable
private fun Points(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row {
                Text(if (ordered) "${index + 1}." else "•", Modifier.width(24.dp))
                Text(item, Modifier.weight(1f))
            }
        }
    }
}
