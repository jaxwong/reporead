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
| KaTeX advisory | Accepted low-severity risk retained; the 2026-10-19 audit offers a breaking Mermaid 12.2.0 upgrade, not the earlier downgrade. No dependency upgrade approved; see the PR5 assessment below |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all does not fetch images; an embedded image shows as unavailable offline until its note is opened online once.
- RepoRead Dev still receives its sign-in code through a custom scheme (`reporead-dev://auth`), which another app could also register: its backend on the Mac has no domain to verify. The release app uses a verified App Link ([backend README](../backend/README.md#sign-in-contract-android)).
- Bank-question identity is local to this phone's bank document and Canonical line. Independently created cross-device cards can remain duplicates; no cross-device deduplication is promised (ADR-20).
- After a bank prompt edit, Review uses the current saved-bank prompt, but Notebook and the reader's notes panel still show the prompt saved on the card. Review also parses all saved bank pages before its first card, including for hand-written-only sessions; accepted in the PR4 branch audit, not changed by PR5.
- Review's completion count can under-report when the phone clock moves backwards during a session: the PR5 clock-ahead/restore check showed 0 reviewed although the real grade was accepted and scheduled. `Review.kt` filters the displayed count by the session start timestamp; grade persistence and scheduling succeeded; no completion-counter fix was made in PR5.
- A card on a removed or unsupported note cannot be deleted from Notebook: its only action is Open passage, while deletion belongs to the reader. Deferred until encountered on this phone; the containment is Notebook deletion through `Sync.deleteAnnotation`, not editing.
- Unsent changes made before switching GitHub accounts can be sent under the new account. Accepted for one user with one account on this phone; revisit before a second account is used (ADR-22).

The ADRs record further limits: anchors (ADR-05), presentation-only page upgrades (ADR-18), the unpaged notebook and uneditable questions (ADR-13).

## Earlier implementation status

- **Save all background work (2026-10-18):** verified in [PR #68](https://github.com/jaxwong/reporead/pull/68); current behavior is owned by ADR-19/ADR-22, and audit PR2 supersedes its removal lifecycle.
- **Typed HTTP boundary (2026-10-18):** implemented and verified in [PR #70](https://github.com/jaxwong/reporead/pull/70); current contract and failure behavior are owned by ADR-26.
- **Navigation 3 (2026-10-19):** implemented and verified in [PR #72](https://github.com/jaxwong/reporead/pull/72); ADR-27 owns state compatibility, amended by audit PR1/PR2.
- **Screen work in its entry (2026-10-19):** implemented and verified in [PR #73](https://github.com/jaxwong/reporead/pull/73); ADR-28 owns entry work, amended by audit PR1/PR2/PR4.

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

## Audit PR5 — Closure check (2026-10-19)

Application verification covers merged audit PR4 [#80](https://github.com/jaxwong/reporead/pull/80), `bf921855aebda65b86293ae62fcbb10a241610a3`. PR5 is documentation and verification only: no production code, API, schema or dependency change, and no hosted deployment was initiated by this work. The required live actions and approved release installation are now executed. A clock-rollback Review completion-counter defect was observed and is recorded below, not fixed or represented as a clean display pass. The user explicitly retained [the plan](audit-remediation-plan.md), especially Section 5, for post-PR5 work; it must not be deleted. Local main was updated and this branch rebased onto `b6e3c82`; the two intervening main commits only track the systemd unit and update the backend README, with no application-source/build/dependency difference from the tested code.

### Dependency assessment (C9)

| Component | Version and owner at verification |
| --- | --- |
| Spring Boot | **4.1.1**, root `build.gradle.kts` |
| Android Gradle Plugin | **9.1.1**, root `build.gradle.kts` |
| Compose BOM | **2026.08.00**, `android/build.gradle.kts` |
| Navigation 3 runtime/UI | **1.2.0**, `android/build.gradle.kts` |
| Reader dependencies | highlight.js **11.12.0**, Mermaid **11.17.2**, build-only esbuild **0.28.2**; `android/reader-web/package.json`, resolved transitives in its lockfile |

`cd android/reader-web && npm audit` exited **1**: `2 low severity vulnerabilities`, KaTeX [GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7) and Mermaid's dependency on it; no moderate/high/critical findings were reported. The existing accepted low-severity risk is retained, not fixed: exploitation needs pre-existing prototype pollution or attacker control of renderer-option prototypes. Mermaid uses strict mode/disabled HTML labels and the WebView blocks external resources; this is not proof of safety. npm now offers the breaking **Mermaid 12.2.0** upgrade, not the earlier 10.8.0 downgrade. No forced fix, transitive override or unapproved major upgrade was applied.

`gh api -i repos/jaxwong/reporead/vulnerability-alerts` exited **1**, `HTTP 404: Vulnerability alerts are disabled`. The user explicitly chose to leave that setting unchanged on 2026-10-19; no settings mutation was sent. The version inventory is not a Maven/Gradle transitive advisory scan, and disabled alerts provide no ongoing coverage. No claim that the whole dependency graph is vulnerability-free is made.

Manual Spring Security check: Boot 4.1.1's cached `spring-boot-dependencies-4.1.1.pom` declares **7.1.1**. The official advisories [CVE-2026-41707](https://spring.io/security/cve-2026-41707/), [CVE-2026-47841](https://spring.io/security/cve-2026-47841/), [CVE-2026-47842](https://spring.io/security/cve-2026-47842/) and [CVE-2026-47877](https://spring.io/security/cve-2026-47877/) are fixed in 7.1.1; no additional upgrade is indicated by those four findings. This bounded manual check is not an exhaustive scan of Maven or Android transitives.

### Hosted library measurement (C5)

Read-only `ssh opc@152.69.219.238 'sudo journalctl -u reporead-backend --no-pager -n 1500 | grep "Library read;" | tail -5'` exited **0**. The latest retained real-account line was **2026-10-19 19:04:47 SGT** (`11:04:47.574Z`): **260,497 bytes** of **8,388,608 bytes** (8 MiB), **3.11%**; **3 repositories, 100 reading states, 1 bookmark, 19 annotations, 12 reviews**. The log does not report a document count. `Library.answer` still allocates the complete serialized answer before checking its ceiling; wire bytes are not a peak-heap measurement. D6 remains accepted: no paging or new code. Revisit as observed bytes approach the ceiling or the review log nears the existing approximately 40,000-grade trigger (ADR-13).

### Documentation and retired-symbol sweep (C10)

Source traceability in `invariants.md` now explicitly means authorized-repository provenance, not proof of this document's history; no history walk or additional GitHub calls. The production/test/docs sweep found only required Room migration history and absence tests, the literal historical navigation fixture, unrelated names such as `android:exported`, and explicitly retired/historical ADR evidence. Active ADR-28 wording was corrected; dead links to the removed step-9 record now point to [PR #70](https://github.com/jaxwong/reporead/pull/70). The four earlier implementation sections above retain one status line each, linked to their owning PR. ADR-06 and the real-system verification list now agree that the 2026-10-15 rerun followed the 2026-10-09 look-alike fix. None of these audit PRs changes anchoring or canonical rules; `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` are intentionally not rerun.

### Executed final gates

| Check / exact command | Exit | Terminal result |
| --- | --- | --- |
| `gh run view 39263379383 -R jaxwong/reporead --exit-status --json conclusion,status,headSha,jobs,url` | 0 | [Android on PR4 main](https://github.com/jaxwong/reporead/actions/runs/39263379383): `completed`, `success`; test and release jobs green at `bf92185`. |
| `gh run view 39253086119 -R jaxwong/reporead --exit-status --json conclusion,status,headSha,jobs,url` | 0 | [Backend main run at the start of verification](https://github.com/jaxwong/reporead/actions/runs/39253086119): `completed`, `success`; test/build/deploy green at `1deac6a`. PR4 is Android-only, so the backend path filter correctly schedules no new run; local backend tests below were rerun on merged main. |
| `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon` | 0 | `BUILD SUCCESSFUL in 2m 25s`; `149 actionable tasks: 149 executed`. Backend XML: **289 tests**, 0 failures/errors, 3 opt-in skips. Android JVM XML: **83 tests**, 0 failures/errors, 1 opt-in skip. Includes compilation/type checks, Lint, reader syntax/bundle and both signed assemblies. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r android/build/outputs/apk/debug/app-debug.apk` | 0 | `Success`; RepoRead Dev data preserved. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 0 | `Success`. |
| `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `Time: 254.502`; **`OK (171 tests)`**. Full isolated Pixel 8a suite after the user unlocked the phone, with real Room, WebView and loopback HTTP; no real-account mutations. |
| `ssh opc@152.69.219.238 'curl -fsS http://127.0.0.1:8082/actuator/health; echo; curl -fsS http://127.0.0.1:8082/actuator/info; echo'` | 0 | Health `UP`; hosted backend build commit `1deac6a9af8742791145201179cf5c20dffeffdf`, the hosted revision at the start of verification. Read only; no deployment by this work. |

Room migration coverage remains the preserved v15 migration and v16 upgrades from exported v14/v15. No new schema or migration was added. The complete isolated device run is recorded above; a transport exit alone is not a pass. The live work below was separately authorized, uses the real Dev app/Mac backend/GitHub, and is not loopback-fixture evidence.

### Approved live check and release update

The Mac backend was restarted with the existing environment documented in [Run locally](../notes/run-locally.md#2-backend-own-terminal-serves-on-1270018081). Its build metadata reported `e5d1cea` (PR5 documentation before the requested rebase; application code is PR4's `bf92185`), health `UP`, and `Schema "public" is up to date. No migration necessary.` It is left running. The installed Dev/test APKs remain the exact binaries from the full isolated run above; the already-built release APK is **0.1.390**, not an untested branch build.

In the live-command rows, `adb` denotes `~/Library/Android/sdk/platform-tools/adb -s adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp`. UI commands ran sequentially with screen inspection between actions; no live-write replay harness, direct mutation HTTP call, source edit or direct database write was introduced. UI dumps/screenshots and temporary diagnostic output stay outside the repository.

| Action / command | Exit | Observed result |
| --- | --- | --- |
| `adb install -r android/build/outputs/apk/release/app-release.apk` | 0 | `Performing Streamed Install`, `Success`; package inspection changed **0.1.387 → 0.1.390**. No uninstall or data clear. |
| Real Practice: Show answer, then `adb shell input tap 160 2210` (Again) | 0 | One existing bank question became a card and first grade; backend logged `Annotation created` and `Review stored or replayed ... grade=0`. Local-server library changed **10 → 11 annotations**, **0 → 1 reviews**; Practice showed **1591 new / 1 scheduled**, **1 tomorrow**. Canonical-line-only confirmation and supplementary saved-section labels were visible. |
| Real image note: normal Search/open/scroll; `adb reverse --remove tcp:8081`, reopen saved note, then `adb reverse tcp:8081 tcp:8081` | 0 | The real Obsidian image embed displayed; GitHub image response **200**, **395,448 bytes**, then `Document image served`. Reopening without the Dev tunnel kept text and the image readable and showed the expected saved-highlights/unreachable-server notice. This is backend-unreachable simulation, not airplane mode. Image and table coverage used two existing authored notes; none was fabricated. |
| Real wide table: documented WebView `forward tcp:9333 ...`, then one `Runtime.evaluate` via `node --input-type=module` | 0 | `{"tables":3,"viewport":375,"tableWidth":719,"before":0,"after":343.23809814453125}`; the real table was visible and horizontally scrollable. This one-off layout diagnostic only scrolled the page; the temporary forward was removed afterwards. |
| Clock-ahead position: `adb shell cmd time_detector set_auto_detection_enabled false`; `adb shell cmd alarm set-time <host epoch millis + 86400000>`; reader scroll/Back/normal Library sync | 0 | Phone time was verified one day ahead. The normal reading save changed progress **54% → 55%**; a read-only PostgreSQL query of that document returned **2026-10-19 14:18:23.835687Z**, `not_in_future = t`, rather than the phone's future time. The original automatic-time setting (**true**) and current time were restored in cleanup and confirmed again after the smoke check. |
| Real Review: select the card while clock-ahead made it due, restore normal time, Show answer, then `adb shell input tap 669 2209` (Good) | 0 | Backend logged `Review stored or replayed ... grade=4`; the local-server library now held **2 reviews**, and the next due date was **Thu 22 Oct**. Both grades are real retained user-data mutations. The terminal UI said **`Session complete: 0 cards reviewed.`** after the clock rollback; persistence/scheduling succeeded, but the completion count did not. This is not a natural next-day session or a clean completion-counter pass. |
| Real Sign out confirmation, then Sign in with GitHub through the normal browser flow; Search before/while signed out/after sign-in | 0 | Search consistently reported **`Searching 840 of 841 notes`**. The card and its schedule survived. Local active sessions went **16 → 15 → 16**; backend logged actual revocation (`otherSessions=15 githubTokenRemoved=false`), GitHub OAuth responses **200**, `GitHub sign-in completed`, `App session issued`, and the restored library with **11 annotations / 2 reviews**. No last-device grant revocation, account deletion or loss of saved notes was exercised. |

The clock-save read-only check was `docker compose -f backend/compose.yaml -p reporead exec -T postgres psql -X -U reporead -d reporead -v ON_ERROR_STOP=1 -c "select document_id, progress_percent, last_read_at, last_read_at <= current_timestamp as not_in_future from reading_states where user_id = 1 and document_id = 11606;"` (exit **0**). Counts/session checks also used read-only SELECTs; no credentials or session hashes were read. The phone has normal time, automatic detection **true**, the Dev `tcp:8081` reverse restored, the temporary `tcp:9333` forward removed, and RepoRead Dev left signed in on Library.

### Main advancement during verification

A final hosted check briefly received `curl: (7) Failed to connect ... port 8082`. Its original SSH shell ended in `echo`, so SSH's exit **0** did not mean health passed; this attempt is explicitly not counted green. Read-only service/log inspection showed a separate Actions restart at **22:24:01 SGT** and successful startup at **22:24:14 SGT**. No restart, deployment or VM configuration change was performed by this work.

| Final command | Exit | Terminal result |
| --- | --- | --- |
| `gh run view 39268164867 -R jaxwong/reporead --exit-status --json conclusion,status,headSha,jobs,url` | 0 | [Latest main Backend](https://github.com/jaxwong/reporead/actions/runs/39268164867), `b6e3c82`: `completed`, `success`; test/build/deploy green. |
| `gh run view 39268164802 -R jaxwong/reporead --exit-status --json conclusion,status,headSha,jobs,url` | 0 | [Latest main Android](https://github.com/jaxwong/reporead/actions/runs/39268164802), `b6e3c82`: `completed`, `success`; test/release green. |
| `ssh opc@152.69.219.238 'set -e; curl -fsS http://127.0.0.1:8082/actuator/health; echo; curl -fsS http://127.0.0.1:8082/actuator/info; echo'` | 0 | After confirmed startup, health **`UP`**, revision **`b6e3c82b443aa6657fdd4fc95b786188750c02ff`**. Read only. |
| `git diff --check` | 0 | No output. |

The requested local-main fast-forward and `git rebase main` exited **0**. The dirty audit plan was stashed by explicit path and restored byte-for-byte afterwards (SHA-256 **c593b38f8fcfa1603d43831d1d4170ef165348cf02b6dd377593a657232a2c20**); its Section 5 work remains planned, not implemented by PR5.


## Not yet verified on the real system

Covered by automated tests only, or not at all:

- The format reset in the live `MainActivity` across an app update (ADR-27): Android 14 discarded state before the earlier update could exercise it. Isolated tests use the debug Activity boundary; `MainActivity` wiring is reviewed only. Account gates and local-data preservation are tested with loopback data, never by deleting the live phone copy.
- Connecting a repository and landing on its folder above the Library against live GitHub (ADR-27, ADR-28): a device test drives Add repository against a loopback server only.
- A second real GitHub user being refused this repository; truncated discovery against live GitHub.
- Airplane mode itself: it ends wireless debugging, so offline checks removed `adb reverse` instead.
- Save all stopped by a real execution limit or by the system killing the process mid-run: only forced job timeouts were tried (ADR-19).
- Process death by the system (only force-stop and `am kill` were tried). PR5 verified a real Obsidian image embed online and with the Dev backend unreachable.
- Sign-ins surviving a deploy, and GitHub revocation on the last sign-out, with the real phone and hosted backend.
- Natural next-day Practice/Review cadence and two weeks of voluntary sessions. PR5 verified one real Practice grade and one Review grade, using the clock-ahead check to make the otherwise-not-yet-due card selectable; the completion-counter clock-rollback defect remains under Known gaps.
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
