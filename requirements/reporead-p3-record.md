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

- The follow-up checks below supersede the earlier pending topic/LeetCode/no-heading checks. The native rotation defect was subsequently fixed and verified in the final section; earlier failure evidence is retained.
- Daily-use preference, airplane mode itself, and future AI/privacy/cost/authoring decisions are not verified or implemented.
- Known npm KaTeX advisories already documented in `reader-web/README.md` were not changed; no dependency upgrades were authorized by this stage.

## Final phone state

The tested production source was committed as `43534a0`. The final non-debug release was reinstalled over the debug app, preserving saved data. Backend forwarding was restored (backend availability itself was not checked), and original rotation settings were confirmed: `accelerometer_rotation=1`, `user_rotation=0`.

```sh
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r android/build/outputs/apk/release/app-release.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 reverse tcp:8081 tcp:8081
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell am start -W -n com.reporead.android/.MainActivity
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell settings get system accelerometer_rotation
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell settings get system user_rotation
```

All exit **0**:

```text
Performing Streamed Install
Success
8081
Status: ok
LaunchState: COLD
Complete
1
0
```

## Follow-up phone verification (2026-10-09)

The user confirmed the phone was left untouched. Temporarily reinstalled the same debug APK with `adb install -r`, preserving data; no production code changed in this follow-up.

- **Topic queue: PASS.** Selected `core/backend engineering` from the topic picker. UI text reported 36/36 notes saved; a Python assertion over the approved UI dump verified every visible `.md` path starts with `core/backend engineering/`.
- **Instruction context: PASS.** Opened the “final telemetry flushes…” item in `05-configuration-and-observability.md`. DevTools confirmed its prompt contains the leading instruction separated by blank lines, zero canonical mismatches, and Previous changed Question 17 of 17 to Question 16 of 17 (block 183).
- **Native rotation: FAIL.** After rotating to landscape, the assertion expecting Question 16/block 183 exited **1**: actual Question 17/block 184. The queue target was correctly consumed (logs show the normal restore branch), but the persisted position remained older. Boundary logs from `adb -s 192.168.1.214:34499 logcat -d -s RepoRead:I '*:S'` (exit **0**):

```text
10:40:49.428 Reading position saved; documentId=74 percent=97 block=183 viewHeight=1942
10:40:49.429 Reading position saved; documentId=74 percent=97 block=183 viewHeight=1942
10:40:49.902 Reader restored; documentId=74 mode=exact savedPercent=97 savedBlock=184 viewHeight=711
```

Those “saved” logs occur before the database write. `ReaderSession.capture` launches that write in the Activity-bound `rememberCoroutineScope` passed by `RepoReadApp`; teardown cancels that scope. The write lifetime and premature success log need correction. Work stopped for user approval because this is the shared reading-position save path; no second speculative fix was applied.

- **Real LeetCode: PASS.** `leetcode/1d dp/house robber.md`: ready; Problem visible; `Brute Force Approach` and `Mistakes` collapsed; zero canonical mismatches. Documented checker command (exit **0**):

```sh
node android/reader-web/check-restore.mjs "$(curl -s http://127.0.0.1:9333/json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["webSocketDebuggerUrl"])')"
```

```text
{"blocks":34,"same":23,"sameRow":0,"collapsed":6,"endOfNote":5,"wrong":[],"approximate":[]}
```

- **No recognized headings: PASS.** Opened saved `scratch/p2/links.md`; a Python assertion over the approved UI dump confirmed the title and absence of both Study and Read toggles (exit **0**).
- Restored original rotation settings (`accelerometer_rotation=1`, `user_rotation=0`). Release reinstall follows the same command recorded above; saved data is retained. No new notes, annotations, schema changes, or dependencies were introduced.

## Approved rotation fix and verification (2026-10-09)

The user approved fixing the shared save path. Changed only `ReaderSession.capture` in `reader/Reader.kt`: an already-captured local position starts saving undispatched and completes inside `withContext(NonCancellable)`, even when Activity teardown has cancelled its UI scope. The completion callback (including final WebView destruction) and success log run after the Room write. No retry, alternate save path, new scope owner, network call, dependency, or schema/API change. Process termination before the local write completes is not covered by this Activity-rotation guarantee.

Build/typecheck/unit tests (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon
```

```text
BUILD SUCCESSFUL in 20s
130 actionable tasks: 22 executed, 108 up-to-date
```

Unit XML results: **25 tests, 0 failures, 0 errors, 0 skipped**. Release vital lint passed. Repeated the debug/test installs and instrumentation command recorded above (each exit **0**):

```text
Success
Success
com.reporead.android.data.LocalStoreTest:.............
com.reporead.android.reader.StudyReaderTest:..
Time: 2.386
OK (15 tests)
```

### Native regression checks, failing workflow now passing

Real saved `core/backend engineering/02-data-and-persistence.md` (document 71), opened from its topic queue. A temporary local DevTools runner (`node /private/tmp/reporead-eval.mjs '<Runtime.evaluate expression>'`) invoked the real bundled reader and asserted count/anchor/mode after native Activity recreation. The runner and snapshots stayed outside the repo; no new native-lifecycle instrumentation test was added. Existing isolated WebView tests alone do not cover this defect.

Rotation commands (exit **0**), first preserving the original settings:

```sh
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell settings put system accelerometer_rotation 0
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell settings put system user_rotation 1
# After asserting landscape, change question again, then rotate back:
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell settings put system user_rotation 0
```

- Previous changed Question 14 to Question 13 without scrolling; immediately rotated to landscape. Assertion exited **0**:

```text
{"rotationRestore":"PASS","count":"Question 13 of 18","block":207,"landscape":true,"recall":true,"canonicalMismatches":0}
```

- Next changed back to Question 14; rotated to portrait. Assertion exited **0**:

```text
{"reverseRotation":"PASS","count":"Question 14 of 18","block":208,"portrait":true}
```

- Read the answer, jumped to an ordinary paragraph, then rotated to landscape. Assertion exited **0**:

```text
{"ordinaryReadingRotation":"PASS","block":81,"recall":false,"landscape":true,"canonicalMismatches":0}
```

Boundary logs now confirm the database write completed before the new reader restored the same anchor (logcat command recorded above, exit **0**):

```text
10:51:01.160 Reading position saved; documentId=71 percent=97 block=207 viewHeight=2052
10:51:01.657 Reader restored; documentId=71 mode=exact savedPercent=97 savedBlock=207 viewHeight=711
10:51:35.583 Reading position saved; documentId=71 percent=96 block=208 viewHeight=711
10:51:36.117 Reader restored; documentId=71 mode=exact savedPercent=96 savedBlock=208 viewHeight=1942
10:51:46.498 Reading position saved; documentId=71 percent=35 block=81 viewHeight=1942
10:51:47.020 Reader restored; documentId=71 mode=exact savedPercent=35 savedBlock=81 viewHeight=711
```

Full-note restore checker, using the documented `node android/reader-web/check-restore.mjs ...` command above, exited **0**:

```text
{"blocks":221,"same":147,"sameRow":69,"collapsed":0,"endOfNote":5,"wrong":[],"approximate":[]}
```

Reinstalled the rebuilt release with the recorded `adb install -r android/build/outputs/apk/release/app-release.apk` command (exit **0**, `Success`) and started MainActivity (exit **0**, `Status: ok`, `LaunchState: COLD`). Saved data was preserved. Restored and read back `accelerometer_rotation=1`, `user_rotation=0` (exit **0**). `git diff --check` exited **0**, no output. Backend tests were not rerun for this reader-only fix; daily-use preference and abrupt process termination remain unverified.
