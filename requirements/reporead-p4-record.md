# P4 — Anchored review cards and highlights notebook

Implementation and verification record, 2026-10-09. **Device execution and the two-week real-review exit gate remain pending.** Implementation is not evidence of that gate passing.

## Approved decisions and scope

- The user approved starting P4 with a new `CARD` annotation, backend and Room migrations, SM-2 without a new dependency, and deletion of cards/review logs on disconnect/account deletion. P3's daily-use preference gate is still pending; the request explicitly starts P4 now.
- The user confirmed that P3 **Add to review** keeps the authored prompt and requires a user-selected answer passage. There is no inferred answer mapping.
- GitHub remains read-only. No real cards, grades, demo data, or source edits were made during verification. All new test data is explicitly test-only; the production app has no fixture mode.
- This is additive feature work, not replacement/refactor work. The existing annotation implementation is extended, not wrapped or kept in parallel. No new dependencies, upgrades, background jobs, retry/fallback strategies, AI, or FSRS port were added.

## Ownership and wiring

- `backend/annotation/Annotations.java` owns CARD/HIGHLIGHT anchors, trusted locations, confirmation and creation idempotency. `AnnotationController` validates selections/questions. V7 extends the type constraint, adds `question`/`checked_blob_sha`, and creates `review_log` with cascading deletion. Existing highlight request fingerprints remain unchanged so already-known mutation ids replay.
- `backend/annotation/Reviews.java` owns immutable grading history and the server session ceiling (40). Notebook reads are a complete repeatable database snapshot, not a GitHub fetch or re-anchoring pass. Grades use one client UUID, millisecond timestamp and verified SHA; identical concurrent/repeated submissions return the same result. A committed replay succeeds after a note edit; a new outdated grade rolls back. Grade writes use the existing connection/sync lock before locking the annotation, matching disconnect/refresh ordering.
- `android/data/LocalStore.kt` version 7 projects server annotations/logs/limit and owns pending local rows. A grade persists only while the card, known current note and saved copy still agree. Complete remote snapshots retain unsent creations/grades and acknowledge matching mutation ids; deletions remove associated logs. Repository forgetting removes pending grades as well as cards.
- `android/sync/Sync.kt` pushes creations before grades, then reads the complete notebook at the existing Library foreground sync point. Each call is made once; a transient failure ends the run with remaining work pending. Typed permanent refusals stay visible and are not retried. Private exports are cleared by existing sign-out/disconnect/account-deletion paths.
- `android/library/Review.kt` is the sole pure scheduler: initial ease 2.5, intervals 1/6/ceil(previous × prior ease), ease floor 1.3, failures reset repetitions. Log order is timestamp then mutation id, independent of arrival order. Numeric overflow saturates instead of producing a past due date. The interval/ease definition was checked against [Wozniak's primary SM-2 description](https://www.super-memory.com/english/ol/sm2.htm). **Bounded-session adaptation:** each selected card appears once; failures become due next day instead of adding unbounded same-day repeats.
- `android/library/Notebook.kt` owns the native Review/Notebook screens. Sessions interleave shuffled note groups and use only the server-provided cap. The log owns completion across recreation; the captured local write finishes even when the UI scope is cancelled. Reveal verifies canonical answer context; a context from another passage cannot enable grading. Notebook filters include descendant folders, note and orphan status; Open passage targets the saved annotation in Reader.
- Reader/Notes selection actions create cards from highlights or selected text. Study reads the displayed question's actual block identity through `currentStudyQuestion()` even after Read the answer, then asks for a selected answer. Question/answer authoring is saveable across recreation. No native JavaScript bridge or canonical-text changes were introduced; HTML render format stays 3. Notes offers explicit confirmation and the existing orphan reattachment path. Questions are authored at creation; correcting a question currently requires deleting/recreating its card, while existing annotation-note editing remains supported.
- Markdown export prefixes each quote line without trimming or escaping away source characters, preserving Unicode, CRLF, blank lines and trailing whitespace. It atomically finishes a unique UTF-8 file before granting a share URI. The non-exported FileProvider serves only `files/exports/`, never the database or note cache. Files already copied to another app cannot be revoked.

### Changed-answer policy

Every known note-version change blocks its cards until the current answer has been resolved, saved and explicitly confirmed. This conservative policy also covers changed sections and uncertainty without another diff/fetch strategy. Re-anchoring/reattachment clears confirmation; orphaned/deleted cards are never scheduled. A stale local note list cannot override newer server metadata. Confirmation preserves review history. Offline review can only use the last known version; unseen GitHub edits are unknowable until repository refresh.

### Call sites and coupled behavior checked

Before changing the shapes, grep identified `AnnotationController` as the production caller of `Annotations.create`; Android `Sync` as the creation/response parser; `ReaderScreen` and `NotesPanel` as authoring/edit/delete/reattach consumers; `LocalStore` and search as cached annotation consumers; `ConnectionData`, `RepositoryController`, `AccountController` and `Sync.StoredData` as deletion/count consumers. `Screen.Reader` callers are Library, Practice, search, reader links, navigation serialization and the lifecycle tests. All are updated in this change; bookmarks keep their type-specific behavior. `Documents` move detection already counts every annotation type, so cards retain logical-note identity without a second path. Existing highlight/bookmark, auth, sync, reading-state, rendering and move tests remain intact.

GitHub-call ceilings were documented in the backend README before route implementation: notebook, grade and confirmation **0**; card creation **1**, replay **0**; resolution/reattachment use the existing annotation ceilings. No source writes exist.

## Executed verification

Initial unprivileged Gradle attempts exited **1** because the sandbox could not open the existing Gradle wrapper lock under `~/.gradle`. Approved escalated runs used the installed Gradle cache, Android SDK and Docker; no replacement environment or dependency installation was invented.

Baseline README commands exited **0**, with tasks UP-TO-DATE:

```sh
./gradlew :backend:test --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
```

New P4 API tests reproduced the missing behavior before production changes (exit **1**):

```text
173 tests completed, 3 failed, 3 skipped
BUILD FAILED in 22s
```

The first implementation run passed creation/grading/boundary tests and caught a test-harness error: MockRestServiceServer expectations were added after a request. Reset the completed expectation phase before the reattachment expectation; no behavior assertion was weakened. The expanded instrumentation compilation also caught misuse of Kotlin `use` on RoomDatabase; explicit `try/finally` now closes those test databases. Both failures are retained as failures, not counted as passes.

Fresh full verification, exit **0** (log outside the repository at `/private/tmp/reporead-p4-build.log`):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :backend:test --no-daemon --rerun-tasks
```

```text
BUILD SUCCESSFUL in 42s
134 actionable tasks: 134 executed
```

Final build after context-identity/action-layout review, exit **0** (`/private/tmp/reporead-p4-final-build.log`):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :backend:test --no-daemon
```

```text
BUILD SUCCESSFUL in 24s
134 actionable tasks: 28 executed, 106 up-to-date
```

XML result-count check, exit **0**:

```text
backend/build/test-results/test {'tests': 176, 'failures': 0, 'errors': 0, 'skipped': 3}
android/build/test-results/testDebugUnitTest {'tests': 31, 'failures': 0, 'errors': 0, 'skipped': 0}
```

Backend tests ran on real Testcontainers PostgreSQL 17.11 with Flyway V7; GitHub responses were explicitly test-only mocks. Coverage includes card creation/anchor reuse, creation and grade concurrency, ten stored grades plus identical replay, mismatched mutation contents, privacy, missing/invalid fields, timestamp precision/future limits, stale-grade rollback, committed replay after edits, re-anchoring/orphan/reattach, and card/log deletion on disconnect/account deletion while preserving other users. Three existing opt-in corpus/measurement checks remain skipped.

Android unit tests cover new-card due state, interval/ease arithmetic, lapses, rejected-grade exclusion, arrival-order independence, empty queues, note interleaving, the server cap, stale/unconfirmed/orphaned/deleted/missing-copy exclusion, UTF-16 context verification and exact Markdown quote round-trip. Kotlin/Java compilation is the typecheck; release vital lint and bundled `npm run check` passed.

`git diff --check`: exit **0**, no output. Python APK/manifest checks: exit **0**:

```text
PASS: debug test-host packaging = True
PASS: release test-host packaging = False
PASS: release manifest excludes the test host and includes the private export provider
```

## Device checks: compiled, not executed

```sh
~/Library/Android/sdk/platform-tools/adb devices -l
```

Exit **0**:

```text
192.168.1.214:34499    offline product:akita model:Pixel_8a device:akita transport_id:3
```

The phone was offline. No APK was installed, no app data was read/cleared, and no release restoration was needed. Device-install approval was requested separately; execution was not assumed. The existing safe `adb install -r`/`shell am instrument` procedure is in the Android README; **never** use connectedDebugAndroidTest on this phone.

Compiled but **unexecuted** regressions include: v6→v7 migration from the exported schema retaining saved notes/unsent highlights; Room pending-grade merge/deletion/current-answer rules; test-only loopback HTTP Sync with five successes then failure, subsequent completion, duplicate replay and zero-call empty run; native selection-based P3 card authoring with dialog recreation; ten native offline grades with reveal/session recreation; and the existing reader/canonical-text lifecycle suite.

## Still unverified / out of scope

- Device execution, real airplane-mode grading/reconnection, real laptop edits, share-sheet delivery to another app, large-font/rotation visual checks of the new screens, and two weeks of actual review sessions. Compiled tests do not establish these outcomes.
- The new migrations were exercised only in test databases, not applied to the live backend or the phone. Deployment and restarting the real backend are not performed here; the running old backend needs the new build before it can serve these APIs.
- No real-account cards or grades were created. No GitHub source was changed. AI question generation/multiple choice, FSRS, arbitrary question editing, log pagination/history pruning and background synchronization are not included.
- Existing source-SHA provenance limitation (the server verifies a blob in the repository, not its membership in that particular document's history), documented dependency advisories and SDK XML-version warning were noticed but not changed. No new vulnerability audit is claimed.
