package com.reporead.android.study

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.database.CardBlock
import com.reporead.android.database.NotebookItem

/**
 * The question a card's Canonical line asks in its bank's current saved version, so the card follows its current prompt
 * and answer; null when that version no longer asks it.
 */
internal fun reviewQuestion(card: NotebookItem, questions: List<PracticeQuestion>): PracticeQuestion? =
    questions.firstOrNull {
        it.documentId == card.annotation.documentId && it.question.answer.text == card.annotation.drawn.exactText
    }

/**
 * A due card that cannot be graded, for [cardBlock], with what fixes it from here: opening its bank to save the current
 * version, to let the server find an edited Canonical line, or to delete the card.
 */
@Composable
internal fun FixCard(card: NotebookItem, cardBlock: CardBlock, push: (Screen) -> Unit) {
    val annotation = card.annotation
    Text(
        when (cardBlock) {
            CardBlock.REFUSED ->
                "The server refused this card, so it can't be reviewed: ${annotation.rejection} Delete it in its note's Highlights and notes."
            CardBlock.REMOVED ->
                "Its note was removed from the repository, so this card can't be reviewed. Delete it in the note's Highlights and notes, or skip it."
            CardBlock.NOT_SAVED ->
                "The note's current version isn't saved on this phone. Open the note while online to save it, then review this card."
            // Opening the bank lists its highlights, so the server looks for the line in the new version.
            CardBlock.NOT_ASKED ->
                "Its bank no longer has this question's Canonical line. Open the bank while online: if the line was only edited, this card follows it. Otherwise delete the card in the bank's Highlights and notes."
        }
    )
    TextButton(
        onClick = { push(Screen.Reader(annotation.documentId, card.title, OpenAt.Highlight(annotation.mutationId))) }
    ) {
        Text("Open note")
    }
}
