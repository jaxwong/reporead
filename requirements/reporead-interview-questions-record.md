# Interview questions — record

Branch `interview-questions` (from `main` at 5d42c76), 2026-10-13. The user added an `interview/` folder (a question bank) beside the notes of seven `core` topics and asked that the questions used for active recall (Practice, Study) and spaced repetition (review cards) come from those folders.

## Decisions the user made

1. Drop the core notes' own `Questions this file answers` and `Review and practice` as a question source **everywhere**, including topics without a bank (`core/languages`, `core/web`, `ai`, `machine learning`); the user will write more interview folders.
2. A bank question's card takes its authored `Canonical answer` line as its answer without a selection. This reverses the P4 rule that a P3 prompt needs a user-selected answer passage: the bank's line is an authored mapping, not an inferred one.
3. Bank notes show their questions one at a time in Study, as P3 did for core notes.

## Measurement (zw_obsidian 7eb8bbc, read-only shallow clone in the session scratchpad)

- 85 banks in 7 topics. Every question is either a numbered H3 under `Foundations` (526), `Applied questions` (462), `Stretch` (366), `Diagnosis` (56) or `Design follow-ups` (16) — 1,426 — or one of 321 numbered list items under 64 unnumbered `After you …` H3s in `Design follow-ups`, which share one `Canonical path:` line. No other section has H3s.
- Every numbered question has `Interviewer is testing:`; all have `Canonical answer:` (3 say `Canonical answers:`). All 1,699 canonical links resolve to a heading of an existing core note; 240 answers link more than one section.
- The rendered page keeps each question H3 and list item as its own block; bold labels and link text flatten into canonical text (`Canonical answer: ACID in practical language`).
- Before this change Practice extracted 0 questions from the banks (781 from core notes, last measured in P3).

## What changed

- `reader/Study.kt` (sole owner of recognition) takes the note's path. Questions come only from notes in a folder named `interview`, from the five measured sections; each carries its answer line (`StudyAnswer`: block id + canonical text) and its guide (the section's other text, shown on reveal). The P3 heading rules and their instruction/prose extraction are deleted. LeetCode collapsing is unchanged.
- `reader/Reader.kt`: **Add question to review** (Study) and Practice's **Add to review** open the existing *Make a question* dialog with the question prefilled and the whole answer line of the displayed version as the selection, so creation goes through the unchanged `Sync.createAnnotation` path. The answer-selection mode it replaces is deleted: `answerPrompt`, `SelectionAction.ANSWER` ("Use as answer"), `selectingAnswer`, and `Screen.Reader.reviewPrompt` (now `review: Boolean`, offered once per opening; a saved-state entry from the old encoding decodes as `false`).
- `library/Practice.kt`: passes the path; **Add to review** only for questions with an answer line; empty-state text names interview folders.
- `library/Review.kt` + `Notebook.kt`: `revealedAnswer` verifies the passage exactly as before (`answerContext`), and for a card whose passage is a bank question's answer block also returns that question's guide, which Reveal shows instead of the bare line. Other cards reveal as before.
- No backend, schema, API, render-format or reader.js change: **Read the answer** already scrolls to the question's block, and the bank's canonical links already open the offline link preview.
- Production lines +110 / −82: the additions are the new answer/guide model, the reveal, and the card offer; the replaced selection mode is deleted.

## Call sites checked

`studyNote`: Reader, Practice, Review (new), StudyReaderTest, StudyTest, StudyCorpus. `StudyQuestion(`: Study.kt, PracticeTest (defaults keep it valid). `Screen.Reader(...reviewPrompt)`: Practice only, plus encode/decode in RepoReadApp. `SelectionAction.ANSWER`/`selectingAnswer`: Reader, ReaderWebView, ReaderLifecycleTest. All updated. Existing cards are untouched; cards made earlier from core-note questions reveal as before.

## Verification

Full build, README command, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :backend:test --no-daemon
```

```text
BUILD SUCCESSFUL in 5s
134 actionable tasks: 1 executed, 133 up-to-date
```

Test XML: Android unit **40 tests, 0 failures, 1 skipped** (the opt-in corpus check); backend **189 tests, 0 failures, 3 opt-in skips** (backend unchanged). The first unit run failed 1 test (39 run): a ReviewTest fixture whose visible text was `x` while the guide is the text the reader shows; the fixture now renders like a real page. No assertion was weakened.

Real-corpus check. Render every note with the backend's opt-in harness, then run the new opt-in `StudyCorpus` over the pages (outputs stay in the session scratchpad; private notes are never committed):

```sh
REPOREAD_MEASURE_CORPUS=<zw_obsidian clone> REPOREAD_MEASURE_OUT=<dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon
REPOREAD_STUDY_PAGES=<dir>/pages ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*StudyCorpus' --rerun --no-daemon -i
```

Both exit **0**; `StudyCorpus` XML `failures="0" skipped="0"`:

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

1,747 = 1,426 + 321, matching the Markdown measurement; every one has an answer line; no note outside an interview folder yields questions. Rerun these two commands after adding interview folders: a bank question without an answer line fails the check by name.

### Device (Pixel 8a, Android 14, wireless adb)

Installed the debug and test APKs over the release build with `adb install -r` (data kept; never `connectedDebugAndroidTest`), then `adb shell am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner`:

- First run: **38 tests, 1 failure** — `addingABankQuestionOffers…` timed out. Phone log showed the reading position saved at 16:05:47 (block 2) and next only at teardown (block 4): the test awaited a saved position right after **Next question**, but the reader saves on leaving the screen or after scrolling (the existing rotation test already relies on this). Test fault, not app fault: it now awaits the page's own position (`awaitReader(4, recall = true)`); the saved-position assertion after recreation is kept.
- Second run: **OK (38 tests)**.

Real-data check on the phone (backend unreachable at first: the dev Postgres container `reporead-postgres-1` had exited 23 hours earlier, so the backend running since 2026-10-09 returned HTTP 500 to Library sync; restarted with `docker compose -f backend/compose.yaml -p reporead up -d`, actuator `UP`). The phone had 39 of the 92 interview files saved (its note list predates the newer banks):

- Practice **All topics: 670 questions**, equal to the Markdown rule count for exactly those 39 saved notes; `core/backend engineering` **310** (equal to the corpus check); `core/ai/rag` **0** (decision 1).
- A queued bank question opens in Study as "Question 8 of 19" without its number; **Read the answer** shows its bank section; tapping its canonical link opens the offline preview of the core section.
- **Add question to review** (Study) and Practice's **Add to review** both opened *Make a question* with the question prefilled and the whole `Canonical answer: …` line as the quoted answer. Both were **cancelled**: the phone DB afterwards still has 10 highlights, 0 cards, 0 review log rows.
- Not checked on the phone: saving a card and its Review reveal (covered by `ReviewTest` and the device test that saves a card in an in-memory database), a follow-up item, and the absence of Study on a core note (unit-tested). The check left reading positions on two banks (`12-testing-and-verification-strategy` 38%, `13-release-engineering-and-change-safety` 84%).
- Final state: release build reinstalled (`install -r`, data kept), `adb reverse tcp:8081 tcp:8081` restored, Postgres container running.
