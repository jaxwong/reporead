# P3 — Study view and topic practice

Implementation and verification record, 2026-10-09. The daily-use exit gate is **pending**: the user still needs to report whether this is preferable to rereading.

## Decisions and ownership

- P3 remains recall from authored notes, without AI, multiple choice, scores, scheduling, source edits, or schema/API changes. The user confirmed future AI intent; it is recorded separately in the post-MVP plan, not implemented here.
- Topics are the repository's real folder hierarchy, selected before questions. A folder includes descendants; **All topics** explicitly selects the cross-library queue. Root-level notes are included in All topics. Folders with no extracted questions show zero, not invented prompts.
- `Review and practice`: each top-level list item retains its leading instruction. For prose-only sections, use the first paragraph as one prompt. Trailing prose/navigation is not another prompt. Existing authored solutions are not detected or stripped; **Read the answer** reveals the note, without inferred question-to-answer mapping.
- `reader/Study.kt` owns fixed heading recognition and extraction for both native Practice and reader layout. `library/Practice.kt` derives topics/counts from the Room snapshot and interleaves questions one per note per round. Its superseded free-text folder filter is removed.
- `reader.js`/`reader.css` own presentation only. Study controls are outside canonical blocks. Hidden passages restore to a visible section heading and report `collapsed`; highlights and heading/block jumps reveal ancestors. Recall saves the current question anchor and measures progress against the note, not the short prompt panel.
- Added Android jsoup for DOM parsing of actual saved HTML, including loose/nested lists and escaped canonical attributes. It shares the backend's existing version 1.23.2 via the root Gradle property; no version upgrade. Regex/string parsing would not correctly represent those structures.
- Topic selection, extraction, shuffling, and study toggling make **0 external calls**. Opening notes retains existing reader fetch/annotation-sync behavior; Practice never downloads missing pages to fill coverage.

## Automated verification

Final build command (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :backend:test --no-daemon
```

```text
BUILD SUCCESSFUL in 47s
134 actionable tasks: 37 executed, 97 up-to-date
```

- Android XML results: **25 tests, 0 failures, 0 errors, 0 skipped**. Kotlin compilation provides the typecheck; release vital lint passed.
- Backend task was **UP-TO-DATE**, not a fresh rerun: existing report **170 tests, 0 failures, 3 opt-in skips**. Backend runtime code is unchanged; its jsoup version moved to a shared owner.
- Reader bundle invokes `npm run check` (`node --check reader.js && node --check build.mjs`) during the Android build.
- `git diff --check`: exit **0**, no output.

Pixel device commands (each exit **0**, same signing key, no uninstall):

```sh
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Performing Streamed Install
Success
Performing Streamed Install
Success
com.reporead.android.data.LocalStoreTest:.............
com.reporead.android.reader.StudyReaderTest:..
Time: 1.807
OK (15 tests)
```

Room tests cover empty/partial coverage, a second save, current list metadata, unlisted cached copies, and removal/disconnect. WebView tests use explicitly test-only pages in the real isolated reader; they do not create real annotations or seed the app database. They cover nested collapse/reveal, visible Problem, hidden restore, selection/highlights, current-question restore, instruction-aware prompts, missing questions, progress, and canonical-text invariants. Unit tests cover heading boundaries, nested/loose lists, ignored code/continuations, prose-only extraction, instruction groups, repeat shuffles, uneven queues, folder boundaries and ancestor topics.

### Failures corrected, not hidden

- Extraction tests first failed on the paragraph-heavy implementation (24 tests, 2 failures), then passed with the approved list/instruction and prose-only rules. The older section test now explicitly asserts instruction context rather than treating it as a separate question.
- DOM collection initially failed Kotlin compilation because jsoup `Element` is iterable; use explicit `add` rather than ambiguous `+=`.
- Topic test caught lexical sorting separating a parent from descendants; hierarchy-aware ordering now keeps descendants together.
- Initial WebView fixture rejected an internal non-asset request with `checkNotNull`, crashing that test run; the fixture now returns an explicit 404 like production. That crashed run was not counted as a pass.
- Real queued-question rotation reproduced a reset from Question 3 of 6 to the original Question 2 of 6. The queue target is now consumed once with saveable state, leaving subsequent restoration to the existing reading-position owner.

## Real-note inspection

User explicitly approved real-note UI/DevTools verification and copying the database locally for inspection. Private database/UI artifacts stayed under `/private/tmp`, outside the repository; no source content or full database is committed. Inspection was read-only; ordinary reader checks update reading progress through the app's existing path.

- Backend forwarding was absent and the app displayed “Can't reach RepoRead's server”; saved notes and Practice still opened. This is backend-unreachable verification, **not airplane mode**.
- Final topic picker: **781 extracted prompts**; `core/backend engineering`: **269 questions, 36/36 notes saved**. Prior paragraph-heavy extraction had 894 entries; that number is superseded.
- Full library coverage: **657/658 listed notes saved**. The P2 oversized refusal remains unsaved; Practice does not claim full coverage.
- Earlier real `core/networking/00-orientation.md` check: ready, Question 2 of 4, anchor block 5, progress 10%, zero canonical mismatches; Next changed the question, restore returned the same question, and Read the answer revealed the 112-block note.
- Documented restore-check command against that real note exited **0**:

```text
{"blocks":112,"same":69,"sameRow":33,"collapsed":0,"endOfNote":10,"wrong":[],"approximate":[]}
```

## Remaining verification / scope

- Final native rotation retest and additional real LeetCode/no-heading/topic queue checks are pending; isolated WebView cases pass. Device UI changed during the attempted retest, so that attempt is not reported as a pass.
- Daily-use preference, airplane mode itself, and future AI/privacy/cost/authoring decisions are not verified or implemented.
- Known npm KaTeX advisories already documented in `reader-web/README.md` were not changed; no dependency upgrades were authorized by this stage.
- Release reinstall and final phone status will be recorded below when complete.
