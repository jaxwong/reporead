package com.reporead.android.study

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.saveNoteWithoutSearch
import com.reporead.android.network.Api
import com.reporead.android.sync.Sync
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real Practice grading entry, Sync and Room; explicitly test-only notes and no network access. */
@RunWith(AndroidJUnit4::class)
class PracticeMutationTest {
    private lateinit var store: LocalStore
    private lateinit var files: File
    private lateinit var sync: Sync
    private val notifications = AtomicInteger()
    private val sha = "a".repeat(40)
    private val question =
        PracticeQuestion(
            1,
            "Test-only bank",
            "test-only/interview/bank.md",
            sha,
            StudyQuestion(
                "b1",
                "Test-only prompt?",
                StudyAnswer("b2", "Canonical answer: Test-only", emptyList()),
                "Test-only short answer.",
                "Test-only recall.",
                listOf("Test-only point"),
            ),
        )

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        files = File(context.cacheDir, "test-only-practice-mutation-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val dao = store.library()
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, question.path, question.title, sha)))
        dao.saveNoteWithoutSearch(NoteRow(1, sha, question.path, question.title, "<main id=note></main>", 0))
        sync =
            Sync(
                Api("http://127.0.0.1:1", { error("Practice mutation test must not call the network") }) {},
                store,
                files,
            ) {
                notifications.incrementAndGet()
            }
    }

    @After
    fun close() {
        store.close()
        check(files.deleteRecursively())
    }

    @Test
    fun aFailedFirstGradeInsertLeavesNeitherCardNorGradeAndTheNextAttemptSavesBoth() = runBlocking {
        val dao = store.library()
        store.openHelper.writableDatabase.execSQL(
            """create trigger test_only_refuse_first_grade before insert on review_log
               begin select raise(ABORT, 'Test-only first-grade failure'); end"""
        )
        try {
            gradeNewQuestion(sync, question, 4)
            fail("Test-only grade insert must fail")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().contains("Test-only first-grade failure"))
        }
        assertTrue("A failed grade must roll back its card", dao.annotations(1).first().isEmpty())
        assertTrue(dao.reviews().first().isEmpty())
        assertEquals(0, notifications.get())
        store.openHelper.writableDatabase.execSQL("drop trigger test_only_refuse_first_grade")
        assertNull(gradeNewQuestion(sync, question, 4))
        val card = dao.annotations(1).first().single()
        assertEquals(listOf(card.mutationId), dao.reviews().first().map { it.cardMutationId })
        assertEquals(1, notifications.get())
    }

    @Test
    fun concurrentGradesAndARestoredPromptEditWriteOnePair() = runBlocking {
        val dao = store.library()
        (1..2).map { async(Dispatchers.Default) { gradeNewQuestion(sync, question, 4) } }.awaitAll()
        val edited = question.copy(question = question.question.copy(text = "Test-only edited prompt?"))
        assertNull(gradeNewQuestion(sync, edited, 5))
        val card = dao.annotations(1).first().single()
        assertEquals(question.question.prompt, card.question)
        assertEquals(listOf(card.mutationId), dao.reviews().first().map { it.cardMutationId })
        assertEquals(listOf(4), dao.reviews().first().map { it.grade })
        assertEquals(1, notifications.get())
    }

    @Test
    fun aChangedOrMissingBankWritesNothing() = runBlocking {
        val dao = store.library()
        dao.insertDocuments(listOf(DocumentRow(1, 7, question.path, question.title, "b".repeat(40))))
        assertTrue(checkNotNull(gradeNewQuestion(sync, question, 4)).contains("changed since it was asked"))
        dao.insertDocuments(listOf(DocumentRow(1, 7, question.path, question.title, sha, deleted = true)))
        assertNotNull(gradeNewQuestion(sync, question, 4))
        dao.clearDocuments(7)
        assertNotNull(gradeNewQuestion(sync, question, 4))
        dao.insertDocuments(listOf(DocumentRow(1, 7, question.path, question.title, sha)))
        dao.deleteNotes(listOf(1))
        assertNotNull(gradeNewQuestion(sync, question, 4))
        assertTrue(dao.annotations(1).first().isEmpty())
        assertTrue(dao.reviews().first().isEmpty())
        assertEquals(0, notifications.get())
    }

    @Test
    fun aRefusedCardDoesNotPreventANewAttempt() = runBlocking {
        val dao = store.library()
        gradeNewQuestion(sync, question, 4)
        val refused = dao.annotations(1).first().single()
        dao.rejectAnnotation(refused.mutationId, "Test-only refusal")
        assertNull(gradeNewQuestion(sync, question, 3))
        assertEquals(2, dao.annotations(1).first().size)
        assertEquals(1, dao.annotations(1).first().count { it.state == AnnotationState.UNSENT })
        assertEquals(2, dao.reviews().first().size)
    }
}
