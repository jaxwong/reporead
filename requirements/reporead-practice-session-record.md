# Practice session — record

Branch `practice-session` (from `main` at f079540), 2026-10-14. Supersedes the reader's question Study view and Practice's question list from the [interview questions record](reporead-interview-questions-record.md).

## Why

The user reported two bugs in practising interview questions and asked for a design that is not fragile and that follows the evidence on active recall and spaced repetition.

1. **Tapping a question showed the bank note's text before the question.** Cause: the reader drew the bank page with its text visible ([Reader.kt](../android/src/main/java/com/reporead/android/reader/Reader.kt) `loadDataWithBaseURL`), and Study's recall mode was applied only after the page reported `ready` and the app's 150 ms readiness poll saw it (`configureStudy`/`setStudy`, then `studyQuestion` picked the target). Diagnosed from the code; not reproduced on the phone before the fix. The flow that had the bug is deleted (below), not patched.
2. **Read the answer went back to the question.** It scrolled to the bank's own question heading (reader.js `showBlock(question.blockId)`), and Review's reveal showed the bank's lines. Each bank says the opposite: "The note owns every technical answer" (zw_obsidian `core/LEARNING-NOTES-PROGRESSION-PLAN.md` §3–4). The answer is the core section the `Canonical answer` link points to.

The question rule was fragile: it read only five measured section names (`BANK_SECTIONS`), so a bank with a new section silently lost its questions, and an unnumbered H3 counted as a follow-up group whose bullets became questions. The bank contract's own template (an unnumbered H3 with a bulleted checklist) would have lost its real question and asked the four checklist bullets instead.

## Decisions the user made

- Practice shows only the folders holding an `interview/` folder; its questions are those folders' questions.
- The recommended Practice session was chosen over fixing the existing screens: the question alone, then the bank's checklist and the linked section, then a grade; the first grade schedules the question; a missed question comes back in the session.
- Recognition keys on the `Canonical answer`/`Canonical path` line every question already has, not on section names or numbering; a heading that slips the format is listed rather than dropped.

## Research behind the session

Each design point and its source; numbers are as the sources state them.

- **Question alone first.** Attempting before seeing the answer helps even when the attempt fails, provided the answer follows: Kornell, Hays & Bjork (2009), *Unsuccessful retrieval attempts enhance subsequent learning*; Richland, Kornell & Kao (2009) with prose.
- **Answer aloud or in writing.** Covert retrieval is as good as overt for single words, but overt wins for complex material such as definitions; a meta-analysis of 18 studies found covert retrieval only slightly helpful (g = 0.23): [Educational Psychology Review, 2025](https://link.springer.com/article/10.1007/s10648-025-10040-4).
- **Feedback is the real answer.** Feedback after a test further improves retention: Butler, Karpicke & Roediger (2007); Butler & Roediger (2008).
- **Grade against a standard.** Overconfidence undermines learning (Dunlosky & Rawson 2012); seeing the correct answer improves self-scoring (Rawson & Dunlosky 2007), and idea-unit standards improve it further (Dunlosky, Hartwig, Rawson & Lipko 2011). The bank's "A complete answer should" is such a standard.
- **Relearn within the session, then space.** Successive relearning (Rawson & Dunlosky): retrieve to criterion, then repeat in spaced sessions. SM-2 itself says to repeat, the same day, items graded below 4 until they reach 4 (super-memory.com/english/ol/sm2.htm, step 7).
- **Again set apart.** Anki users often press Hard for a wrong answer, but schedulers count Hard as a pass (AnkiWeb forum threads on Hard misuse).
- **Scheduler unchanged.** FSRS predicts recall better on the open benchmark (10,000 Anki users, about 727M reviews; github.com/open-spaced-repetition/srs-benchmark), but no primary source for a review-count saving was found. SM-2 stays.

## What changed

- `reader/Study.kt`: `interviewBank(html, path)` replaces the bank half of `studyNote`. Each bank H3 whose section has a Canonical line is a question (`answer` is now required and carries the line's links as `AnswerLink`s: path or Obsidian target, heading, text); the H2 name is its `level`, for display; the guide no longer repeats the answer line. `unrecognized` lists H3s with no Canonical line, or a path with nothing to ask. `interviewTopic(path)` names a bank's topic, and `isInterviewBank` derives from it. `studyNote(html)` keeps only LeetCode collapsing.
- `reader/Answer.kt` (new): `BankAnswer` shows the guide (scrolling within at most 30% of the screen), one tab per link, and each linked section alone, from the saved copy, in a reader page that stays hidden until only the section is left (as the link preview does). A missing note, an unsaved note, an ambiguous name, a link-less answer line, or a heading not in the saved copy is said in words, never shown as an unrelated part.
- `reader.js`: `showSection(heading)` hides everything outside the section (layout only; canonical text unchanged); `findHeading` is shared with `showHeading`. The question panel is deleted (`studyQuestions`, `studyQuestion`, `currentStudyQuestion`, the recall branches of `position`/`restore`, its CSS).
- `library/Practice.kt`: topics are the folders holding interview folders, each with new and scheduled counts and saved/listed banks, plus named non-questions. `PracticeScreen` (new `Screen.Practice(topic)`) asks the topic's new questions; `scheduledQuestions` matches a card by bank, prompt and answer line (so identical prompts stay distinct and a card that moved to a newer version still counts); `gradeNewQuestion` saves the card then the grade locally, refuses if the bank's saved or listed version moved, and never saves twice; `afterGrade` sends a grade below 4 to the back.
- `library/Notebook.kt` + `Review.kt`: Review reveals a bank card with `BankAnswer`; `GradeBar` (one row: Again, Hard, Good, Easy with one line on what each means) sits under the answer in both screens, and neither screen scrolls as a whole, so grading never needs scrolling past a scrolling page. `GRADES` and `GRADE_MEANING` are the one list of grades.
- `reader/Reader.kt`, `RepoReadApp.kt`: the question path is deleted: `Screen.Reader.question`/`review`, `StudyTarget`, `offerCard`, **Add question to review**, `openAtQuestion`, `currentQuestion`. A bank opens as an ordinary note. A saved back stack from the previous encoding (8 parts) decodes without crashing; its question entry becomes an unmatched highlight target, which does nothing.
- Production lines +571 / −316. The additions are the session screen and the answer view, which did not exist before; the replaced question panel, question list and Add-to-review path are deleted. No backend, schema, API or render-format change.

## Call sites checked

`studyNote`: Reader, StudyReaderTest, StudyTest. `interviewBank`: Practice, Review, StudyCorpus, StudyTest. `Screen.Reader(`: Library, Search, Notebook, Reader, Practice, Answer callers and ReaderLifecycleTest, none using the removed fields. `ReviewScreen(`: RepoReadApp, ReviewLifecycleTest. `practiceTopics`/`inPracticeFolder` stay, used by Notebook's folder filter.

## Verification

App build and unit tests (README command without `:backend:test`; the backend is unchanged), exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon
```

```text
BUILD SUCCESSFUL in 21s
```

Unit tests: **46 run, 0 failures, 1 skipped** (the opt-in corpus check).

Real corpus (zw_obsidian 7eb8bbc, read-only shallow clone in the session scratchpad), both exit **0**, `StudyCorpus` `tests="1" skipped="0" failures="0"`:

```sh
REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon
REPOREAD_STUDY_PAGES=<dir>/pages ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*StudyCorpus' --rerun --no-daemon -i
```

```text
310 questions in core/backend engineering/interview
319 questions in core/cloud and devops/interview
227 questions in core/databases/interview
261 questions in core/distributed systems and data/interview
190 questions in core/networking/interview
99 questions in core/operating systems and concurrency/interview
341 questions in core/security/interview
1747 questions in 7 folders
```

The same 1,747 questions per folder as under the old rule. The check now also fails, by name, on any H3 that is no question, any answer line without a link, or a link to a path with no page. None occurred. It does not check headings inside linked notes; the phone reports those when shown.

Device (Pixel 8a, Android 14, wireless adb; debug and test APKs installed with `adb install -r`, data kept; `am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner`): **OK (36 tests)**. New `PracticeSessionTest`: the question shows with no answer page; the revealed page shows only the linked section; two identical prompts each get a card with their own answer line; Again saves grade 0 and brings the question back after the other one, also across activity recreation; the repeat's grade saves nothing; a new session finds no new questions. `StudyReaderTest` covers `showSection` (GitHub anchor, heading text, section end, missing heading, canonical text). Test faults fixed on the way: a `runBlocking` test body returning a value (JUnit rejected it), a page poll that read `document.body` before it existed. The first device run also caught the layout bug above (Good and Easy below the fold); the grade bar fixed it.

Real data on the phone (debug build): Practice lists exactly the 7 interview topics, **1,747 new** questions, 92/92 banks saved, per-topic counts equal to the corpus check, no non-questions. A `core/databases` session showed a follow-up question alone; **Show answer** showed tabs for its Canonical path's sections and only "The scaling options" from `13-partitioning-growth-and-the-tuning-method`, with the grade bar on screen. Nothing was graded: the phone DB had 10 highlights, 0 cards, 0 grades before and after. A bank note opened from Continue reading as an ordinary note with no Study button. Final state: release build of this tree installed (0.1.101, data kept), test APK removed.

**Full screen in answer pages (follow-up, same day).** Tapping **Full screen** on a table or diagram in an answer page did nothing: the answer page refused every navigation. It now handles a tapped `/full-screen` link as the reader does (`FULL_SCREEN` is the reader's one constant), opening `Screen.Figure` for the linked note's saved version; the page numbers figures over the whole note, so the id matches the full-screen view. Reproduced first: the new device test `fullScreenInAnAnswerOpensThatFigureOfTheLinkedNote` (a wide table in each section, a real injected tap, because links need a user gesture) failed with the fix set aside (timed out with nothing opened) and passes with it. A full-suite run also exposed a race in the session test's own poll (it read the page before checking it was visible); with the order fixed, three full runs were **OK (37 tests)**. Not checked with a real note's table.

Not verified on the phone: grading real questions and their later Review reveal (covered by the device tests with test-only rows), the not-saved and missing-heading messages with real notes, light mode.
