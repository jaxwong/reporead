# RepoRead — Feature list, scope and status

This is the scope, priority and status checklist. User value and acceptance criteria live in [user stories](user-stories.md); decisions and their evidence in the [ADRs](adrs.md); how the parts fit in the [architecture](architecture.md). An item appearing here does not authorize a broader implementation than its linked story.

## MVP features

| ID | Feature | User stories | Delivery milestone |
| --- | --- | --- | --- |
| F-01 | GitHub sign-in, read-only connection, and selection of one or more repositories | US-01, US-11 | 1 — Read |
| F-02 | Markdown discovery and folder/note browsing | US-01, US-02 | 1 — Read |
| F-03 | Android mobile reader for GitHub-flavored Markdown | US-03 | 1 — Read |
| F-04 | Fenced code syntax highlighting and safe Mermaid rendering | US-03 | 1 — Read |
| F-05 | Local cache for previously opened notes and offline reading | US-06 | 1 — Read |
| F-06 | Semantic reading progress, bookmarks, and Continue Reading | US-02, US-04 | 2 — Remember |
| F-07 | Highlights and textual annotations stored outside Markdown | US-05 | 2 — Remember |
| F-08 | Offline annotation mutation persistence and idempotent sync | US-06 | 2 — Remember |
| F-09 | Source-version tracking, file-move recognition, and conservative annotation re-anchoring | US-07, US-08 | 3 — Survive edits |
| F-10 | Orphan state with original context and manual reattachment | US-07 | 3 — Survive edits |
| F-11 | Library “Updated since you read” and “Recently changed” lists | US-02, US-09 | 4 — Git-aware reading |
| F-12 | Basic search over cached note titles and content | US-10 | MVP; schedule after core reading path |
| F-13 | Repository disconnect and account-deletion path | US-11 | MVP; before external use |

Annotation-text conflict handling and the [invariants](invariants.md) apply throughout, even where they are not separate UI features.

## Delivery milestones

### 1 — Read

Build the Spring Boot backend and Kotlin/Jetpack Compose Android app; connect a GitHub repository; list Markdown files; render technical Markdown, code, and Mermaid; cache opened notes with Room. Install on a physical Android phone, by development install or signed APK—Google Play publication is not needed.

**Evidence:** The developer prefers RepoRead to GitHub Mobile for reading their own notes.

### 2 — Remember

Add reading progress, bookmarks, highlights, annotations, and pending offline mutations. Reading actions must leave source Markdown untouched.

**Evidence:** The developer can read for a week and reliably resume and retrieve annotations.

### 3 — Survive edits

Track Git versions; resolve exact/context anchors; conservatively handle fuzzy matches; show orphaned annotations and support manual reattachment. Recognize safe file moves.

**Evidence:** Common edits neither destroy annotations nor move them to an incorrect passage.

### 4 — Git-aware reading

Keep the Library’s **Updated since you read** and **Recently changed** lists, computed locally from saved document/reading metadata with no additional backend calls. Only section summaries were retired on 2026-10-19 (ADR-09).

**Evidence:** The developer can find notes changed since the previous study session and reread them.

### 5 — Polish and demonstrate

Add integration coverage, metrics, architecture decision records, security review, and failure/offline demonstrations. Provide a documented local demo setup using explicitly labeled fixtures; do not treat demo data as evidence of real GitHub integration.

**Evidence:** The product can be demonstrated without depending on a live GitHub response, and the real integration has been verified separately.

## Build stages and their gates

The milestones were built as vertical slices (GitHub → backend → PostgreSQL → app → Room) in seven stages. The first usable release was the end of Stage 2, when daily use started; the complete MVP is the end of Stage 6.

| Stage | Result | Features | Gate |
| --- | --- | --- | --- |
| 0 | Authorization and renderer boundaries proven (ADR-04, ADR-05, ADR-16) | Foundations | Passed 2026-10-06 (checked by hand on the phone) |
| 1 | One real note opens through the real backend | F-01–F-04 | Passed 2026-10-06 |
| 2 | Dependable reading, including offline | F-05, F-06 | Passed 2026-10-06 |
| 3 | Durable online and offline annotations | F-07, F-08 | Passed 2026-10-06 |
| 4 | Annotations survive edits or become explicit orphans | F-09, F-10 | Passed 2026-10-07 |
| 5 | Notes changed since the version actually read | F-11 | Passed 2026-10-07; section summaries retired 2026-10-19 |
| 6 | Search, data controls, metrics, signed release, ADRs | F-12, F-13 | **Pending** |

Stage 6 is built and checked on the phone, along with two additions: a dark theme with a redesigned reader and app shell, and Obsidian `[[links]]` and `![[embeds]]`. Its gate still needs two weeks of voluntary use, at least 20 real highlights, the labelled fixture demo (deferred until after the two weeks), and a full review against F-01–F-13 and US-01–US-11.

## After the MVP

Post-MVP work started on 2026-10-07, before the Stage 6 gate. Each stage reverses or extends an MVP decision only with an explicit decision, recorded in the ADRs.

| Stage | What | Story | Status |
| --- | --- | --- | --- |
| P1 | Long and wide notes: outline and current section, footnotes, code Copy and Wrap, bars that hide while reading | US-12 | Built 2026-10-07; unused full-screen figures retired 2026-10-19 (audit PR1), in-page sideways scrolling retained; gate pending: reading long notes on the phone without switching to GitHub or the laptop because of layout |
| P2 | Every note saved on the phone, link previews, Linked from (ADR-19) | US-13 | Gate passed 2026-10-07 for the cases tried |
| P3 | LeetCode study view and Practice (ADR-20) | US-14 | Built 2026-10-09; its question source was replaced by interview banks (2026-10-13) and its question list by Practice sessions (2026-10-14). Gate pending: the developer uses it in real sessions and says whether it beats rereading |
| P4 | Review cards with phone-side scheduling, the Notebook (ADR-13) | US-15 | Built 2026-10-09; unused Markdown export retired 2026-10-19 (audit PR1); review moved into the Practice tab 2026-10-14; SM-2 replaced by a fixed gap ladder 2026-10-15. Gate pending: two weeks of real review sessions |
| — | GitHub sign-ins renewed, stored encrypted, and revoked on the last sign-out (ADR-03); hosted backend (ADR-14); releases through Obtainium (ADR-15) | — | Built 2026-10-13/14 |

## Out of scope

**Excluded from the MVP and still excluded:**

- Markdown authoring, WYSIWYG editing, Git commits, pull requests, or comments written back to GitHub.
- Collaboration, public profiles, or team knowledge management.
- AI summaries or chat with notes.
- Other Git providers, arbitrary binary formats, and rich drawing/canvas tools.
- iOS, Google Play distribution, and LaTeX/math rendering.
- Webhook-triggered sync, AST-aware diffing, Elasticsearch, Kafka, microservices, a generic sync engine, and cache or retry frameworks (beyond WorkManager's backoff for the phone's background sync and Save all, ADR-08, ADR-19).

**Excluded from the MVP, built afterwards:** spaced-repetition review, added as P3 and P4 on 2026-10-09 (ADR-13, ADR-20).

**Later, if real use asks for it:**

- Math (KaTeX): about 4 notes use it, and KaTeX carries an open advisory through Mermaid ([reader-web README](../android/reader-web/README.md)).
- Text-to-speech that announces or skips code and tables.
- A home-screen widget.
- AI-drafted questions and multiple choice, as drafts the developer edits (ADR-20). Needs a provider, privacy, cost and acceptance decision first.
- Share to RepoRead (a `github.com/…/blob/…` URL from a connected repository opens that note; RepoRead cannot be the default handler without a verified domain), a launcher shortcut to the last note read, and a typography sheet (line height and font, no justified text). None are built.

**Not planned:** a graph view (little value on a phone screen); swiping between notes (clashes with sideways-scrolling code and tables and the system back gesture); justified text; callouts, Dataview and frontmatter tags (0 uses measured in the notes).

These are exclusions, not hidden stretch goals.

## Open decisions

| Decision | Current state |
| --- | --- |
| Drawing edge to edge | When the reader hides the system bars, the camera cutout area shows as a black band. Not decided |
| Phone storage eviction | None; every saved note is kept (ADR-19). Revisit if storage matters |
| Scheduler | Fixed gap ladder (replaced SM-2 on 2026-10-15); consider FSRS only if review data shows a need (ADR-13) |
| Notebook paging | Not paged; revisit as the review log nears about 40,000 grades, past which the library is refused (422 `LIBRARY_TOO_LARGE`, ADR-13) |
| KaTeX advisory | npm's only fix is downgrading Mermaid to 10.8.0; not applied, waiting for a Mermaid release |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all does not fetch images; an embedded image shows as unavailable offline until its note is opened online once.
- RepoRead Dev still receives its sign-in code through a custom scheme (`reporead-dev://auth`), which another app could also register: its backend on the Mac has no domain to verify. The release app uses a verified App Link ([backend README](../backend/README.md#sign-in-contract-android)).
- Bank-question identity is local to this phone's bank document and Canonical line. Independently created cross-device cards can remain duplicates; no cross-device deduplication is promised (ADR-20).
- Unsent changes made before switching GitHub accounts can be sent under the new account. Accepted for one user with one account on this phone; revisit before a second account is used (ADR-22).

The ADRs record further limits: anchors (ADR-05), presentation-only page upgrades (ADR-18), the unpaged notebook and uneditable questions (ADR-13).

## Save all background-work audit — 2026-10-18

Historical verification before audit PR2 (the removal lifecycle below is retired). Current behavior is in ADR-19 and ADR-22: account operations keep saved data and background work; repository Stop remains, and persisted failure text fits WorkManager's bound.

No dependency, schema, alternate fetching path or resume checkpoint was added.

Verified from the PR worktree:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck :app:testDebugUnitTest :app:mergeDebugAssets :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon
# Exit 0: BUILD SUCCESSFUL in 22s; 41 actionable tasks: 41 executed.
# Unit XML: tests=73, failures=0, errors=0, skipped=1 (the opt-in StudyCorpus).

ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# Exit 0. Install both APKs as documented in android/README.md, preserving Dev data.
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
# Transport exit 0; terminal test output: OK (115 tests), Time: 53.433.
```

Before the fix, the new `LocalSyncTest.aLongServerFailureStillReportsTheSavedNotesAndABoundedReason` failed on the
Pixel with `IllegalStateException: Data cannot occupy more than 10240 bytes when serialized` (1 test, 1 failure).
Afterwards the full suite passes, including that multibyte error with already-saved progress. Account tests hold the
existing removal hook until completion and inject a labelled, test-only SQLite failure there: neither can publish a
fresh copy or forget pending removal early, and Retry completes removal without another revoke request. These hook
faults are not a real WorkManager disk failure; the separate Room `query_only` refusal remains a real database test.

Live E2E used RepoRead Dev on the Pixel 8a and the real Mac backend, with 841 listed notes. Stop worked during a run
and during a genuine unreachable-server backoff. Afterwards Disconnect opened its normal confirmation; it was
cancelled, not executed. Saving continued after entering a subfolder and showing the launcher. A forced
`cmd jobscheduler timeout` restarted the same work with 659 notes missing instead of 768. It completed with 658
fetched, 182 already saved and 1 oversized note skipped: 840 of 841 saved. A second run fetched 0, reported 840
already saved and retried only the unsupported note; its screenshot, UI dump and worker log agreed on the terminal
outcome. The wireless ADB endpoint changed during testing; the Dev backend tunnel was restored afterwards. No
release APK, live sign-out, account deletion, dependency or server configuration was changed.

A natural execution deadline and an actual OS process kill during a run remain unverified. Live account removal was
not repeated; its ordering, interruption and failure paths ran against the isolated loopback fixtures in the full
device suite. Backend tests were not rerun because no backend implementation changed.

## Typed HTTP boundary (2026-10-18)

Implemented [audit step 9](audit-remediation-plan.md#step-9-verification): one typed route owner, strict whole-response validation and request matching before local acknowledgement, positive item-refusal pairs, wire models generated from the backend's published spec (ADR-26, amended the same day) and retained transport causes. The old raw verbs and network JSON parsers are removed together. Automated malformed/status/identity/time/timeout/truncation/second-run tests and the whole device suite passed; the record distinguishes those from live evidence. Transient sync refusals are structured results with UI-owned wording; credential lifecycle changes share one backend owner, and configured SQL/socket deadlines are tested against real PostgreSQL.

The live Dev check used an existing scratch note and the real Mac backend/GitHub: library and repository refresh, content/cache reads, available repositories, bookmark on/off, highlight create/edit/conflict/reattach/delete, card confirmation/grade, annotation reload, unchanged diff and stored-data counts. Removing the ADB tunnel kept the cached page readable and a labelled highlight pending; restoring it sent the same mutation once, then removed only that test highlight. Existing user annotations/bookmarks were not deleted. Live destructive routes, reconnect, changed-version diff, images and reading-position UI were not exercised by this contract check. No release/VM deployment was performed.

## Navigation 3 (2026-10-19)

Navigation 3 owns the back stack and each entry's saved state/ViewModel store; compatible-format Activity state survives ordinary build updates ([ADR-27](adrs.md#adr-27--navigation-3-owns-the-back-stack-saved-screen-state-belongs-to-its-entry-and-its-format)). Audit PR2 removes the local-copy reset; ending or replacing a session keeps the stack and entry state. This is PR 3 of the Android foundations plan. The ADR records test scope and execution evidence.

## Screen work in its entry (2026-10-19)

Each screen that starts work keeps it in its back-stack entry's ViewModel: a rotation or a covering screen no longer cancels or repeats a refresh, preview, connect, grade, card confirmation or highlight change, or loses what it reported; a mutation the server confirmed, or a local write accepted, is committed even if its screen is left, and only the screen shown navigates, once ([ADR-28](adrs.md#adr-28--a-screens-work-belongs-to-its-entrys-viewmodel-a-confirmed-change-belongs-to-sync)). Library and the folder root still sync on return (B1 not approved). This is PR 4 of the Android foundations plan; the ADR records the evidence and what remains unverified.

PR4's audit follow-up protects Practice's pre-write validation and retains rejected/submitted reader drafts. Audit PR1
retires summary loading/acknowledgement and advances the saved UI format to 3 for the removed figure destination;
Room v15 drops only summary baselines, preserving saved pages and user-owned work. Reattachment restoration stays.
Audit PR4 replaces that temporary Practice protection with validation under Sync and an atomic first-grade write
(ADR-13, ADR-20). ADR-28 records the foundations evidence.

## Audit PR1 — Feature retirements (2026-10-19)

Implemented in `8685dec`: section summaries, full-screen figures and Notebook export retired; saved data and both Library change lists retained; verification is in [PR #75](https://github.com/jaxwong/reporead/pull/75).

## Audit PR2 — Sessions keep the phone's notes (2026-10-19)

Sign-out and confirmed account deletion end only the session; every outcome preserves pages, images and user-owned pending work. `Account` no longer knows about Sync or the local store; the application owns one Sync per process. Retired-copy/owner/removal/Retry paths and the `expectedUserId`/`ACCOUNT_MISMATCH` contract are removed. Sign-in and account operations retain navigation. The accepted account-switch limit is under Known gaps. Verified locally: backend format/tests (280 tests, 3 opt-in skips), Android format/Lint/JVM tests (78 tests, 1 opt-in skip), reader bundle, both assemblies and device-test compilation passed. The latest Pixel 8a full isolated suite, including the review follow-up's confirmation-restoration regression, ended `OK (154 tests)` in 226.718 s. Exact commands and terminal results are in the implementation PR. No release install, deployment or live GitHub smoke run is claimed.

## Audit PR3 — Reading positions and saved-page retirement (2026-10-19)

`ReadingController` clamps timestamps to [1970, server now], accepts anchors beyond the old shape limits, and makes zero GitHub calls. Malformed bodies remain loud 400 errors; the reproduced NUL/unpaired-surrogate exception was approved as malformed input, never sanitized. The retired reading-refusal code and UI result kind are removed, with bookmark refusal wording preserved. Each pending position is sent at most once per pass; a clamped response is acknowledged and merged, and a confirmed 404 no longer waits. A malformed pending position stops bookmark sends behind it on every run (ADR-08).

Renderer format **4** records the already-shipped presentation fixes without changing canonical blocks, headings or rendering. Android derives `BuildConfig.RENDER_FORMAT` directly from `MarkdownRenderer.FORMAT`, using the existing build-time source-reading pattern; the hand copy and literal-only alignment checks are removed with every caller updated. The fetch gate is unchanged: a format-3 current copy refreshes once online, opens without warning offline, and a refused newer version leaves the saved copy with its reason. Save all's second run fetches only remaining outdated pages.

Room **v16** preserves the already-shipped v15 migration. Retirement runs inside SQLite when `length(cast(html as blob)) + coalesce(length(cast(searchText as blob)), 0)` reaches 2,097,152 UTF-8 bytes; character counts were wrong for multi-byte pages. It then explicitly removes derived links whose page is gone because Room enables foreign keys after migration. Smaller pages and all user-owned rows survive; images use the existing startup sweep. The exported v16 schema is committed and changes only the version, not row shapes. Production including the build wiring is net-negative (53 added, 69 deleted); no dependency, retry path, format registry or refusal-state column was added.

Reproduced first: the original backend regressions failed 8 of 41, the three malformed-text cases failed before validation, and the initial Android regressions failed. The review's byte-cutoff bug was reproduced with the actual migration SQL against isolated host SQLite: two 1,048,576-character rows were each 2,097,152 UTF-8 bytes and incorrectly survived; both retire after the fix while a below-cutoff row stays. Device regressions now include multi-byte HTML and search text alongside ASCII boundaries, nullable search text, empty stores, both exported v14/v15 upgrade paths, user-owned rows and derived-link integrity. The redundant combined migration/image-sweep test is removed; `LocalSyncTest.startingDeletesEveryImageNoSavedPageShows` continues to cover the existing sweep.

Review-follow-up gates, executed after `10b5cd4` (all fixtures are isolated; no live-account data is used):

| Exact command | Exit | Terminal result |
| --- | --- | --- |
| `./gradlew :backend:spotlessCheck :backend:test --no-daemon` | 0 | `BUILD SUCCESSFUL in 1m 36s`; 289 tests, 0 failures/errors, 3 opt-in skips. Includes OpenAPI and shared-refusal checks; OpenAPI is unchanged. |
| `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin --no-daemon` | 0 | `BUILD SUCCESSFUL in 51s`; 78 JVM tests, 0 failures/errors, 1 opt-in skip. Lint, Kotlin/Java compilation and both assemblies passed. The reader syntax check and bundle executed. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.data.FeatureRemovalMigrationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` (saved pre-fix APKs) | 0 | Expected red: `Tests run: 2, Failures: 1`; multi-byte rows 5/6 survived (`expected [1, 4]`, got `[1, 4, 5, 6]`). adb's exit alone is not a test pass. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r android/build/outputs/apk/debug/app-debug.apk` | 0 | `Success`; corrected Dev app installed with data kept, release app untouched. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 0 | `Success`. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.data.FeatureRemovalMigrationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` (corrected APKs) | 0 | `Time: 1.613` and `OK (2 tests)`; empty and populated v14/v15 upgrades pass. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 256.927` and `OK (161 tests)`; full isolated Pixel 8a suite after the review fixes. |

Device gate **passed** after reconnecting the authorized Pixel 8a: the saved pre-fix APKs (`/tmp/reporead-pr3-review-red-{app,test}.apk`) reproduced the multi-byte-row failure, then the corrected APK passed the focused migration regression and full suite above. The earlier `OK (162 tests)` predates the review fixes and is historical only; the current suite has 161 tests after deleting the redundant migration/image-sweep composition. APK SHA-256 for the corrected full-suite app: `533bee4a36303f35c54fcb721756d9e35e9271dcd87245be514583a9811120f1`. Hosted CI on code head `33febe5` passed both test jobs and the backend build; release/deploy jobs were skipped as expected for the PR.

Backend implementation `070d1a4`, initial Android implementation `15de0c0`, and review follow-up `1e780ac` are on `accept-reading-retire-legacy-pages`; current evidence supersedes the earlier gate table in this file's history. Deploy backend before app. The release APK was built, not installed; no hosted deployment, live GitHub smoke check or real phone-clock change is claimed. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` remain opt-in skips because canonical rules and anchoring did not change. Existing SDK XML-version and JVM class-sharing warnings were not changed.

## Audit PR4 — Question identity and atomic first grade (2026-10-19)

Implemented locally on `atomic-practice-cards`, from `main` at `1deac6a`. A question follows its bank document and Canonical line, so a prompt edit keeps its card and Review shows the current saved-bank prompt. Two prompts over one line use the first in bank order; changing the line creates a new question. D5's limit is explicit in ADR-20: uniqueness is local to this phone, not cross-device.

`Sync.gradeNewCard` now validates inside `committed` and writes through one Room transaction. The old caller-side validation/`NonCancellable`, partial-card result and two-write path are removed. A failed grade insert rolls back its card, concurrent/restored submissions are no-ops once scheduled, and a committed pair notifies background sync once. Practice and Review name the Canonical line as the only confirmed answer; linked sections are supplementary saved copies with the older-copy/missing-section/failure messages retained. No backend/API, schema (still Room v16), scheduler, dependency, configuration or saved-state-format change.

Red evidence against the pre-fix production code:

- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*PracticeTest' :app:assembleDebug :app:assembleDebugAndroidTest --continue --no-daemon` exited **1**: `7 tests completed, 2 failed`; prompt-only identity and two-prompts/one-line assertions failed. Both APKs built.
- `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.library.PracticeMutationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` exited **0** but ended `Tests run: 5, Failures: 3` (expected red). The forced SQLite grade-insert failure left a card, concurrent/restored submissions produced multiple cards, and the invalid-grade check failed. adb's exit alone is not a pass.

Initial PR4 implementation checks (test-only Room stores and loopback HTTP; no real-account writes). The ownership review follow-up below records the latest Android/device evidence.

| Exact command | Exit | Terminal result |
| --- | --- | --- |
| `./gradlew :backend:spotlessCheck :backend:test --no-daemon` | 0 | `BUILD SUCCESSFUL in 1m 38s`; XML: 289 tests, 0 failures/errors, 3 opt-in skips. Includes the consumed OpenAPI/refusal checks; API spec unchanged. |
| `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon` | 0 | `BUILD SUCCESSFUL in 1m 8s`; XML: 80 JVM tests, 0 failures/errors, 1 opt-in skip. Kotlin/Java compilation, Lint, reader syntax/bundle and both signed assemblies executed. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r android/build/outputs/apk/debug/app-debug.apk` | 0 | `Success`; Dev app data kept, release app untouched. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 0 | `Success`. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.library.PracticeMutationTest,com.reporead.android.sync.CommittedMutationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 2.217`; `OK (14 tests)`. Includes rollback, second attempt, invalid/missing/changed-bank checks, concurrent/restored submissions, cancellation and validation after an earlier sync commits. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.library.PracticeMutationTest,com.reporead.android.library.PracticeSessionTest,com.reporead.android.library.ReviewLifecycleTest,com.reporead.android.sync.CommittedMutationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 23.612`; `OK (27 tests)`. Real Practice double-tap and queued-pop, current Review prompt and stale linked-section wording pass. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 251.377`; `OK (169 tests)`. Full isolated Pixel 8a suite, including migrations, sync, navigation, reader, Practice and Review. |
| `git diff --check` | 0 | No output. |

**Device UI/full-suite gate passed after the user unlocked the Pixel.** The earlier four-class focused command exited 0 but ended `Tests run: 25, Failures: 13`: twelve screen tests timed out while accessibility showed the lock screen; read-only diagnostics confirmed `mWakefulness=Dozing` and keyguard `showing=true`. The remaining failure was the cancellation fixture's reuse of an existing card's line; it now uses a genuinely new line, keeps its assertions and passes. The first resumed run (`Tests run: 27, Failures: 4`) found the notification shade covering four screen tests. `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell cmd statusbar collapse` exited 0 and dismissed only the shade; no phone setting was changed. With the test Activity visible, the unchanged focused suite and then the full suite passed as recorded above. These green runs supersede the earlier device limitation, not the recorded failed attempts.

Initial implementation's tested APK SHA-256: Dev app `46db198fffcc7362f103e30b67ccc5746466acf8aa43562e2f448dca8d938e2a`; test APK `c8477c37400319f6210bfe881c98e6539c5e3b772102126818b90fc97912849b`. Android README behavior now agrees with ADR-13/ADR-20 on identity, atomic persistence, current Review prompts and the confirmation boundary.

No push, hosted CI/deployment, release installation, phone-setting change, real GitHub smoke test or actual OS process-death test is claimed. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` remain opt-in skips because canonical rules and anchoring did not change. Existing SDK XML-version and JVM class-sharing warnings were not changed.

### PR4 review follow-up — One owner for schedule identity and grade bounds

`Rows.kt` now owns `reviewable` next to `NotebookItem` and the single `scheduledLines(cards)` projection consumed by Practice and Sync. The old predicate and Sync's import from `library` are removed, not aliased. The approved grade-range owner is also the data model: `REVIEW_GRADE_RANGE` beside `ReviewRow`, consumed by WireRules, both ReviewSchedule validations, `LibraryDao.recordReview` and `Sync.gradeNewCard`. Error messages derive their expected range from the same declaration. Putting the range in ReviewSchedule was declined because that screen-package import would recreate the cycle. The offered buttons and scheduling algorithm stay in ReviewSchedule; no behavior, schema, API, dependency, configuration or external-call ceiling changes.

This refactor is net **+7 production lines**: the shared range declaration, explicit imports and formatter wrapping outweigh the deleted duplicate matching code. No new control-flow branch, framework or dependency is added. Before the change, inspection found five grade-range literals and the reverse `Sync → library.reviewable` import. The existing JVM baseline passed before refactoring; expanded characterization tests then passed before range consolidation. New cases cover empty schedules, refused/removed/highlight exclusions, prompt-independent deduplication and drawn/re-anchored text, plus accepted grade values and both invalid edges across all five validation boundaries. Tests keep independent literal expectations rather than deriving their expected bounds from the production constant.

Executed review-follow-up checks (isolated data only):

| Exact command | Exit | Terminal result |
| --- | --- | --- |
| `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` (expanded characterization, before range consolidation) | 0 | `BUILD SUCCESSFUL in 12s`; 83 JVM tests, 0 failures/errors, 1 opt-in skip. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w -e class com.reporead.android.data.LocalStoreTest,com.reporead.android.library.PracticeMutationTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` (before range consolidation) | 0 | `Time: 3.685`; `OK (30 tests)`. |
| `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon` | 0 | `BUILD SUCCESSFUL in 1m 4s`; 83 JVM tests, 0 failures/errors, 1 opt-in skip. Lint, Kotlin/Java compilation, reader syntax/bundle and both signed assemblies passed. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r android/build/outputs/apk/debug/app-debug.apk` | 0 | `Success`; Dev data kept, release app untouched. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 0 | `Success`. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 250.216`; `OK (171 tests)` on the Pixel 8a. |
| `rg -n -F '0..5' android/src/main` | 0 | Only `Rows.kt`'s `REVIEW_GRADE_RANGE` declaration remains. |
| `rg -n -F 'import com.reporead.android.library' android/src/main/java/com/reporead/android/sync android/src/main/java/com/reporead/android/data android/src/main/java/com/reporead/android/core/network` | 1 (expected) | No matches; the reverse screen-package imports are gone. |
| `git diff --check` | 0 | No output. |

Tested review-follow-up APK SHA-256: Dev app `96166764f70301536b9068896ba63f6f872739de7568954ecaf77d9c1d717af4`; test APK `843035ee83d2c9a3d0345836b4a76f61cdca85a60512eb387ca94da9f459ae3d`. These Android/device results supersede the initial PR4 binaries and counts above. The one JVM skip is `StudyCorpus`; existing SDK XML-version warnings remain unchanged.

No backend test rerun, release install, live GitHub write or deployment is part of this Android-only follow-up. The user's pre-existing edits to `docs/audit-remediation-plan.md` are preserved and are not part of this change.

## Not yet verified on the real system

Covered by automated tests only, or not at all:

- The format reset in the live `MainActivity` across an app update (ADR-27): Android 14 discarded state before the earlier update could exercise it. Isolated tests use the debug Activity boundary; `MainActivity` wiring is reviewed only. Account gates and local-data preservation are tested with loopback data, never by deleting the live phone copy.
- Connecting a repository and landing on its folder above the Library against live GitHub (ADR-27, ADR-28): a device test drives Add repository against a loopback server only.
- A second real GitHub user being refused this repository; truncated discovery against live GitHub.
- Airplane mode itself: it ends wireless debugging, so offline checks removed `adb reverse` instead.
- Save all stopped by a real execution limit or by the system killing the process mid-run: only forced job timeouts were tried (ADR-19).
- An Obsidian image embed on the phone, and process death by the system (only force-stop and `am kill` were tried).
- Sign-ins surviving a deploy, and GitHub revocation on the last sign-out, with the real phone and hosted backend.
- Practice and Review UI with real grades and the Practice tab's due badge. The scratch-only live contract check confirmed a card and submitted a real grade through Sync; that is not verification of the full sessions or their UI.
- Re-measuring anchoring after the 2026-10-09 look-alike fix (ADR-06).
- A WebView renderer that stops for real: tested with `chrome://crash` only.
- Account operations survive screen recreation in the application scope. Their network/failure paths use isolated loopback fixtures, not live account deletion; an actual OS process kill during an account operation remains unverified.
- Audit PR2 retires pending-removal, Retry, ownerless-sign-in and retired-copy tests with those paths. Normal startup/disconnect image sweep coverage remains in `LocalSyncTest`; account preservation and retained navigation are checked with isolated data. No live phone copy is deleted.
- Reading the stored session (2026-10-18, ADR-22): unit tests cover the stored-text checks, which Keystore exceptions delete it, and an unreadable one failing the request unsent; reading Keystore's transient and system-error marks from the exception is untested, since JVM tests cannot build a `KeyStoreException`; the exceptions were read in Android's sources, not provoked: the device suite passes with this change (79 of 79 on the phone, 2026-10-18), but none of its tests makes the Keystore fail.
- A deploy that fails on the VM: the deploy and backup scripts were installed there on 2026-10-17 and the deploy dump ran there, but a failing deploy was only checked in a container with stubbed services.
- The reading-position restore check (`android/reader-web/check-restore.mjs`) since it became a round trip through the reader's own `restore` and `position` (2026-10-17): not yet run on the phone's WebView. On 2026-10-18 it ran in headless Chrome 155 at 412×915 on three server-rendered notes from zw_obsidian (127, 582 and 422 blocks, with tables, lists, code and an emoji): exit 0, no `wrong` or `approximate` block, `sameRow` only for table cells. With the page's `position()` deliberately broken on every tenth block it exited 1 and named exactly those blocks.

## Personal-project definition of done

1. At least one real personal GitHub notes repository is connected.
2. A signed APK is installed on the developer's physical Android phone without Google Play.
3. The Kotlin/Compose client uses the real Spring Boot backend.
4. The developer voluntarily uses RepoRead for at least two weeks.
5. Mermaid renders correctly and reading position reliably resumes.
6. At least 20 real highlights or annotations are created; source Markdown remains untouched.
7. A real note edit exercises re-anchoring without silently misplacing an annotation.
8. Offline reading and annotation work, including synchronization after reconnect.
9. A laptop edit marks its note as updated in the library.
10. Backend integration tests run against PostgreSQL; important decisions are documented as ADRs.
11. A local demo can run with clearly labeled fixtures if GitHub is unavailable.

## Demo evidence and interview focus

A short demo should show the same note in GitHub Mobile and RepoRead; folder browsing; Markdown, code, and Mermaid; a new annotation without a source-file change; re-anchoring after a real edit; an orphan after an ambiguous rewrite; the updated-note lists; and offline annotation followed by one successful sync. Then show the GitHub/source, Spring Boot/durable state, and Room/offline-cache boundaries.

The most useful code and tests to show are anchor resolution, complete-snapshot sync, idempotent mutation handling, optimistic annotation updates, GitHub permission and token handling, safe rendering, and annotation-survival integration tests—not generic CRUD. The demo should also answer why GitHub remains canonical, why a backend is needed, how duplicates, moves, force-pushes, conflicts, malicious Markdown, and GitHub outages are handled, what would fail at much larger scale, and what was deliberately not built.
