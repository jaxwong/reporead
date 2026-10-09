# P3 — AGENTS.md review fixes

2026-10-09. Scope: the three findings from the P3 review, approved by the user. This is not the general corpus/cleanup work; `docs/corpus-coverage-plan.md` and `docs/app-cleanup-plan.md` were not found in this checkout.

## Changes and owners

- `e20dae6`: `RepoReadApp.kt` retains Library saveable state with Compose's existing `SaveableStateHolder`. Explicit sign-out, account deletion, and account-owner changes clear it. `library/Practice.kt` saves a shuffle seed and deterministically rebuilds the queue from the current snapshot. No second queue/state owner or custom navigation manager.
- `8624bfe`: `reader/Study.kt` defines the plain `StudyTarget(blobSha, blockId)` identity. Room's Practice snapshot supplies the **saved** page SHA; Practice, the saved navigation stack, native reader, and JavaScript all use that identity. The superseded text-only target/lookup is removed, not wrapped. A different displayed version or absent block explicitly opens the note with a notice asking the user to choose again from Practice. No guessed classification or question-to-answer mapping. Older saved text-only navigation entries open as ordinary readers rather than being interpreted as block identities.
- `ReaderLifecycleTest.kt`: real `ReaderScreen`, Activity-bound Compose scope, `ActivityScenario.recreate`, and in-memory Room verify question advances, reverse advances, two recreations, ordinary reading, pending saves, displayed SHA, and canonical text. The test-only pages and non-exported `ReaderTestActivity` are confined to Android test/debug source sets. No added dependency or production test hook. The existing cancellation-safe `ReaderSession.capture` implementation is unchanged by this test addition.
- `android/README.md` describes Back retention, version-bound targets, and device-test isolation. No schema migration, API endpoint change, source-note edit, AI, or multiple-choice generation. Practice construction/shuffling still makes **0 external calls**; the lifecycle fixture also permits **0 network calls** and throws if one is attempted.

## Red → green evidence

### Library Back

Before the fix, an approved real-phone UI assertion exited **1**: `selectedTopicStillVisible=false`, `readingTabContent=true`. After the fix, selected `core/backend engineering`, recorded visible queue paths, opened a question, pressed Back, and compared the resulting UI dump. The assertion exited **0**:

```text
PASS: Back preserved Practice, selected topic, queue order and visible list position
```

Build/unit/typecheck command (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

```text
BUILD SUCCESSFUL in 12s
47 actionable tasks: 11 executed, 36 up-to-date
```

### Question identity

A labelled synthetic duplicate-prompt repro of the former lookup exited **1**: queue block `b2` opened reader block `b1`. The real isolated WebView test now opens the second identical prompt by its own block id, rejects the same block id with a different SHA, and rejects a removed block. Room asserts the snapshot uses the saved SHA and that an unsaved note has neither HTML nor a SHA. Existing instruction-context assertions remain.

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Exit **0**: `BUILD SUCCESSFUL in 19s`, `79 actionable tasks: 19 executed, 60 up-to-date`. The then-existing device suite passed **15 tests** (exit **0**, `Time: 1.976`).

### Actual Activity teardown

Temporarily restored the known old `scope.launch { sync.saveReading(row) }` behavior in the real capture path, with the completion callback outside that launch; no mock or alternate save strategy. Built debug/test APKs (exit **0**, `BUILD SUCCESSFUL in 23s`, `73 actionable tasks: 8 executed, 65 up-to-date`), installed debug with `adb install -r`, and ran:

```sh
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell am instrument -w -e class com.reporead.android.reader.ReaderLifecycleTest com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner
```

The shell exited **0**, but JUnit correctly **failed**; instrumentation shell status alone is not success:

```text
Reader did not restore block=3 recall=true; last={"block":2,"recall":true,"canonical":true}
Reader did not restore block=12 recall=false; last={"block":2,"recall":true,"canonical":true}
Time: 33.295
FAILURES!!!
Tests run: 2,  Failures: 2
```

Restored the fixed source exactly; `git diff -- Reader.kt` was empty. The temporary cancellation-prone build is not committed or left installed.

The first full green-attempt **crashed**, despite both lifecycle assertions passing: test cleanup called `ActivityScenario.close()` and closed its in-memory database before the asynchronous final save finished. The crash was `android.database.SQLException: connection is closed` in the app's instrumentation process; the user reported the interruption. It is **not counted as a passing run**. Immediately restored release and confirmed the saved library UI. Corrected only test cleanup: dispose the reader while the Activity scope remains alive, observe its newer final Room save, then close the Activity/database. No swallowed exception, arbitrary sleep, production workaround, or abandoned database.

## Final verification

Full build/typecheck/unit/release-vital-lint command (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon
```

```text
BUILD SUCCESSFUL in 34s
130 actionable tasks: 24 executed, 106 up-to-date
```

After the test-cleanup correction, reran the debug/unit/test-APK command above (exit **0**): `BUILD SUCCESSFUL in 14s`, `79 actionable tasks: 6 executed, 73 up-to-date`. The unit task was up-to-date on this last invocation; it executed in the full build. XML totals: **25 tests, 0 failures, 0 errors, 0 skipped**. In `android/reader-web`, `npm run check` exited **0**:

```text
> check
> node --check reader.js && node --check build.mjs
```

Final device commands, each exit **0**, same signing key, no uninstall:

```sh
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Run twice to verify cleanup and second-run behavior:
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 install -r android/build/outputs/apk/release/app-release.apk
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.214:34499 shell am start -W -n com.reporead.android/.MainActivity
```

```text
Success
Success
com.reporead.android.data.LocalStoreTest:.............
com.reporead.android.reader.ReaderLifecycleTest:..
com.reporead.android.reader.StudyReaderTest:..
Time: 6.159
OK (17 tests)
# Second full run:
Time: 5.155
OK (17 tests)
# Release restore:
Success
Status: ok
LaunchState: COLD
Complete
```

A temporary Python runner asserts both `OK (17 tests)` results and restores/launches release in `finally`, even on a failed assertion. Logs and approved UI snapshots remain under `/private/tmp`, not committed. Python APK inspection exited **0**: test host present in debug dex/manifest and absent from release dex/manifest. `git diff --check` exited **0**, no output.

Final release smoke check: dumped/pulled the Library UI, tapped the visible saved `05-configuration-and-observability` entry, verified its title and native Study control, pressed Back, and verified the Library's Practice tab. Commands (exit **0**) were `adb -s 192.168.1.214:34499 shell input tap 490 576`, `shell input keyevent KEYCODE_BACK`, and the documented `shell uiautomator dump /sdcard/reporead-p3-ui.xml` / `pull /sdcard/reporead-p3-ui.xml /private/tmp/reporead-p3-ui.xml` pair. Assertions exited **0**:

```text
PASS: release Library remains open with saved notes
PASS: release opens an existing saved note without crashing
PASS: release Back returns to the saved Library without crashing
```

The phone is left on Library in the non-debug release. No app-data clear or uninstall was used. This smoke check updates reading progress through the existing reader save path, not note content or annotations.

## Limits

Backend availability/new-version fetch, account switching, abrupt process termination, airplane mode itself, and daily-use preference were not newly verified. Backend tests were not rerun for these Android-only fixes. The saved library remains usable with the backend unreachable. Private source data is not committed; fixture data is explicitly test-only. Known dependency advisories and broader cleanup remain untouched. Future AI is still deferred.
