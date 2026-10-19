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
| Notebook paging | Not paged; revisit as the review log nears about 40,000 grades, past which the library is refused (422 `LIBRARY_TOO_LARGE`, ADR-13). On 2026-10-19 the real hosted library was 260,497 / 8,388,608 bytes (3.11%): 3 repositories, 100 reading states, 1 bookmark, 19 annotations, 12 reviews; document count was not logged. Revisit as bytes approach the ceiling; serialized bytes are not peak heap |
| KaTeX advisory | Accepted low-severity risk retained; the 2026-10-19 audit offers a breaking Mermaid 12.2.0 upgrade, not the earlier downgrade. No dependency upgrade approved; PR5 evidence is in git history (`44c71f7`) |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all completion counts pages, not separate image totals. Permanent content/image refusals count the page once as skipped; unreadable cache files or a full disk can still leave an image unavailable offline (local file handling is unchanged). Network/server/session failures do not report a successful run; see ADR-19.
- RepoRead Dev still receives its sign-in code through a custom scheme (`reporead-dev://auth`), which another app could also register: its backend on the Mac has no domain to verify. The release app uses a verified App Link ([backend README](../backend/README.md#sign-in-contract-android)).
- Bank-question identity is local to this phone's bank document and drawn Canonical text; a re-anchored line edit can retain its card, while a larger edit can leave an orphan beside a new question. Independently created cross-device cards can remain duplicates; no cross-device deduplication is promised (ADR-20).
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

Merged as [#78](https://github.com/jaxwong/reporead/pull/78): positions clamp timestamps, renderer format is 4, and Room v16 retires unreadable pages without losing user-owned work. Evidence is in the PR and git history.

**Gap found during PR5 review:** grade timestamps still used the deleted reading refusal policy, and the initial sweep missed stale Android reading-refusal and backend format-3 README text. This branch corrects both, clamps grades and preserves exact immutable replay checks with additive V15; `CARD_CHANGED` refusals stay.

## Audit PR4 — Question identity and atomic first grade (2026-10-19)

Merged as [#80](https://github.com/jaxwong/reporead/pull/80): prompt edits keep their cards, first grades are atomic, and only the Canonical line is confirmed; production was net +48 lines after its follow-up. Evidence is in the PR and git history.

**Gaps corrected during PR5 review:** both admission decisions now live in DAO write transactions; the sole grade-range check is the phone's write boundary, not five layers; repeated Canonical-line prompts are warnings from Study's recognizer, not silent Practice drops. ADR-20 now states drawn-text identity accurately. Cross-device duplicates, orphan/new-question coexistence, saved-prompt differences and bank-parse cost remain accepted limits above.

## Audit PR5 — Closure check (2026-10-19)

Initial closure checks passed: main CI/hosted health at `b6e3c82`, local backend/Android gates, Pixel `OK (171 tests)`, approved Dev real-account smoke and release 0.1.390 update; evidence is in git history (`44c71f7`). The user explicitly retained the plan for Section 5. Review corrections passed local gates (292 backend / 82 JVM tests, no failures), the full Pixel suite (171 tests) and final focused regressions (82 tests). Exact commands and outputs stay in git history. The user confirmed PR5 merged and pending deployment on 2026-10-19; no subsequent deployment, release install or real-account smoke test is claimed here.

**Coverage limits:** npm audit reported two low-severity KaTeX/Mermaid findings (accepted, no upgrade); Dependabot alerts remain disabled at the user's request. The version inventory (Boot 4.1.1 / Security 7.1.1, AGP 9.1.1, Compose BOM 2026.08.00, Navigation 3 1.2.0) and bounded manual Spring-advisory check are not a transitive-graph scan. The live clock-rollback completion counter and other real-system gaps remain listed above/below. Canonical/anchoring opt-in measurements were not rerun because these rules did not change.

## Mechanical moves — Local verification (2026-10-20)

**Current status:** the user confirmed this work merged and pending deployment on 2026-10-20. The local evidence below remains historical; no subsequent deployment or release install is claimed.

### Initial implementation (`92ee00f`)

Implemented locally on `mechanical-moves`, code commit `92ee00f`: Compose load UI moves to `ui/`; Practice, Review, recognition, answer views, scheduling and session progression move to `study/`; folder helpers move to `library/Folder.kt`. Their callers and JVM/device tests follow, without aliases or duplicate implementations. Production Kotlin is net **+3 lines**, solely the imports needed across the new package boundaries; all 38 production file bodies were compared before/after, allowing only the three helper relocations and the approved saved-state format change. All existing test bodies/assertions are preserved, with the two folder tests moved to `FolderTest`.

**Compatibility:** the user approved saved-UI-state format 4 because a serialized session names its old package (ADR-27). Navigation, drafts and in-progress sessions reset once; Room, saved pages/images, the stored sign-in session and pending work are not cleared. The format-3 rejection test was run before the bump and failed (`Tests run: 1, Failures: 1`); after the bump it ended with `OK (1 test)`.

Executed Gradle gates (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:mergeDebugAssets :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# BUILD SUCCESSFUL in 29s
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:mergeDebugAssets --rerun-tasks --no-daemon
# BUILD SUCCESSFUL in 1m 5s; 51 actionable tasks: 51 executed
```

The fresh JVM report contains **82 tests, 0 failures, 0 errors, 1 skipped** (the opt-in private-corpus harness). Lint has no errors or warnings, only seven informational dependency/target hints; the installed SDK XML-version notice remains unchanged. Reader JavaScript syntax checks and packaging also ran again. The compiled runtime JAR contains all 26 relocated top-level types/file facades under their new owners, with no former types, nested/lambda classes or Compose singletons left behind. Backend, dependencies, schema history and workflows are unchanged.

The debug APKs were installed with the Android README's data-preserving `install -r` / `install -r -t` commands; no release install or live-account operation was performed. The first full `am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner` run ended with **`Tests run: 172, Failures: 1`**: `AppNavigationTest.anUnansweredDisconnectKeepsTheCopyUntilTheUserTriesAgain` timed out after 60 seconds. ADB exited **0**, which is not a passing test result. All moved study suites and the new format regression passed in that run. An isolated rerun of that disconnect test passed without code changes (`OK (1 test)`, 49.364 s); the original timeout's cause is not established. No timeout, assertion or production behavior was changed to hide the failure.

The second full run passed without code changes (exit **0**). `ANDROID_SERIAL` selected the user-provided phone; its address is intentionally omitted here. Command and terminal output:

```sh
~/Library/Android/sdk/platform-tools/adb shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Time: 243.049

OK (172 tests)
```

`git diff --check` also exited **0** with no output. Device coverage uses the existing isolated Room/loopback fixtures, not the live account; no production stub was introduced.

**Limits:** backend tests and opt-in private-corpus measurements were not rerun because their code/rules did not change. Actual `MainActivity` restoration across a release update remains unverified; format tests use a constructed marker, not captured old-APK state. Images in Save all now proceed below; the remaining Section 5 work stays deferred. Ignored local `notes/` files are outside this change. No push or deployment was performed.

### Audit correction (2026-10-20)

Code commit `15ca142` corrects the new `reader` ↔ `study` import cycle and stale labels found by the audit. The reader's Study-view model/parser now stays in `reader/Study.kt`; bank recognition stays in `study/Study.kt`. Both existing reader-study JVM test methods moved intact to `reader/StudyTest.kt` (this checkout had two, not three). The trivial private heading predicate is duplicated rather than introducing a shared layer. The architecture diagram/table now name both owners; the only dependency between these packages is `study` → `reader` for link parsing and isolated-page hosting.

Notebook's helpers are now `ancestorFolders` and `inFolder`, with all three production call sites and both tests updated and no aliases. The four format-rejection methods are consolidated into one: the actual unmarked legacy Activity state, all prior markers including 1/2/3, and an unknown future marker are still refused. Compatible-format/build-number independence, null state and the destination/entry-id round trip remain covered. Device-test method count is intentionally **169**, down from 172 without losing a rejection case. Format **4**, session persistence, database state and network behavior are unchanged.

This correction is net **+4 production lines** (**+7** for the branch against `main`): a separate reader-owned file/imports and the small duplicated heading predicate remove the cycle without another abstraction. Source-body comparisons confirmed no algorithm changes beyond splitting that owner and renaming the folder helpers. The import-direction check first failed on the four `reader` → `study` imports, then passed after the split; no former folder-helper names remain in production or tests. The compiled JAR has `StudyNote` only in `reader`, while bank types remain in `study`.

Executed local gates (exit **0**):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:mergeDebugAssets :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# BUILD SUCCESSFUL in 42s; 92 actionable tasks: 27 executed, 65 up-to-date
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck :app:testDebugUnitTest --rerun :app:compileDebugAndroidTestKotlin --no-daemon
# BUILD SUCCESSFUL in 20s; 40 actionable tasks: 18 executed, 22 up-to-date
```

JVM reports: **82 tests, 0 failures, 0 errors, 1 skipped** (the opt-in corpus harness). Both new owner-specific Study suites compile/run, and the consolidated Navigation test compiles. `git diff --check` exits **0** with no output.

**Device verification completed (2026-10-20):** after the user connected/unlocked the Pixel 8a, read-only preflight reported `mWakefulness=Awake` and keyguard `showing=false`. The committed tree at `9ddf6ff` (code `15ca142`) was rebuilt, installed over RepoRead Dev without clearing data, and the full corrected suite passed. No wake/unlock or settings change was performed by the agent. The earlier `OK (172 tests)` and the reviewer's separate 25-test attempt with 13 lock-screen timeouts remain historical evidence for the initial build, not this correction.

Commands and outputs (all exit **0**; the authorized phone identifier is omitted and represented by `$ANDROID_SERIAL`):

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# BUILD SUCCESSFUL in 22s; 74 actionable tasks: 19 executed, 55 up-to-date
~/Library/Android/sdk/platform-tools/adb -s "$ANDROID_SERIAL" install -r android/build/outputs/apk/debug/app-debug.apk
# Success
~/Library/Android/sdk/platform-tools/adb -s "$ANDROID_SERIAL" install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Success
~/Library/Android/sdk/platform-tools/adb shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Time: 252.707

OK (169 tests)
```

SHA-256 of the installed APK artifacts: app `c9ca05befecd88ad0829512545cbcbfe06c9c73d60f0176e25a81d22c8f7ce63`; device tests `956a515e52204fe00d92086038f7aa170edf3cf4ef2a606d43c4dccf97df9356`. This was the isolated Room/loopback suite, with no live-account smoke test or release install. No production/test source changed for this verification run.

**Deferred/not touched:** class-name-independent session persistence remains a separate follow-up decision; Java-Serializable state and the approved format-4 gate stay. `Load`/`LoadContent`'s pre-existing public visibility is unchanged. Backend tests and private-corpus measurements were not rerun; no dependency, migration, production fixture, release install, push or deployment is part of this correction.

## Images in Save all (2026-10-20)

Implemented locally on `save-all-images`, initial code commit `bf43647`, with error-boundary correction `f549846` below. Save all checks the images of every listed saved page, including already-current pages (user decision), before reporting that page's progress. Current text is not fetched again. `NoteImages` reuses its existing snapshot-pinned files under the app's private `filesDir`, not SQLite BLOBs; no schema, dependency or new download/retry mechanism. The unused metadata-only `savedVersions` query is removed. ADR-19 owns limits, per-page ordering, cancellation and image-failure behavior; completion still counts pages, not guaranteed image availability.

**Initial verification, before the error-boundary correction below.** The new offline-reader regression first failed on the unchanged implementation with `Images must precede progress` (`Tests run: 1, Failures: 1`; ADB exit **0** does not mean a passing suite). After implementation, focused sync/image/committed-mutation suites passed (`OK (54 tests)`, 21.889 s). Five new device tests cover Save all then offline resource access, encoded/duplicate paths, current-page backfill, partial image failure, cancellation/restart, empty input and disconnect during prefetch; the existing legacy-snapshot and image-only-refresh assertions are retained and extended.

Local Gradle gates passed (exit **0**): format, Lint, JVM tests, Kotlin/device-test compilation, reader packaging and debug/device-test/release assemblies (`BUILD SUCCESSFUL in 26s`). A subsequent fresh run of format/Lint/JVM/device-test compilation/reader packaging passed with all 51 tasks executed (`BUILD SUCCESSFUL in 45s`): **82 JVM tests, 0 failures, 0 errors, 1 opt-in skip**; Lint **0 errors, 0 warnings, 7 informational hints**. The initial new-code KTX lint error and final-comment formatting failure were corrected without rule changes. Exact commands and terminal results stay in this change's git evidence.

The first full Pixel run ended **`Tests run: 174, Failures: 1`** (266.234 s): `PracticeSessionTest.doubleTappingTheGradeWritesOneCardAndOneGrade` failed at the second accessibility-click assertion, before its card/grade assertions. All 41 `LocalSyncTest` methods, including all five new regressions, and both image/committed-mutation suites passed. The unchanged Practice test passed alone (`OK (1 test)`, 1.613 s). Its intermittent failure's cause is not established; no assertion, timeout or production behavior was changed to hide it. ADB exited **0** for both runs, which does not itself establish success.

The second full run passed without source/test changes (exit **0**): **`OK (174 tests)`**, 255.82 s. Debug and test APKs were installed with data-preserving `install -r` / `install -r -t`; the release app was not installed. `git diff --check` also exited **0** with no output. The passing rerun does not explain the earlier Practice failure; that evidence remains above and in git.

### Image error-boundary correction (2026-10-20)

Review found the initial pass reused the reader's API-error catch, so image 503/unreachable/401 failures incorrectly reported a successful run. Corrected in `f549846`: the batch pass uses the throwing fetch; only the reader's `image()` converts API failures to unavailable. `Sync` counts only the three permanent content/image refusal status/code pairs as skipped pages; all other failures reach the existing worker retry/stop boundary. Per the user's confirmation, a permanent refusal stops that page's remaining image pass, keeps its text, counts the page once (not also fetched/already saved), then continues at the next page. The existing skip label now includes unsupported/unavailable image content. No new state or retry mechanism; local file-cache read/write handling is unchanged.

Replaced the old successful-on-503 assertion, preserving its current-page/cache/repeat-run checks on the corrected path. Before production changed, the 503, real-listener-closed unreachable, and real-SessionStore 401 regressions all failed (`Tests run: 3, Failures: 3`, 0.562 s; ADB exit **0** is not a pass). After correction, format/Lint/JVM/Kotlin/device-test compilation/reader packaging and all APK assemblies passed (exit **0**, `BUILD SUCCESSFUL in 36s`); JVM reports **82 tests, 0 failures, 0 errors, 1 opt-in skip**, Lint **0 errors, 0 warnings, 7 hints**. Focused sync/image/committed-mutation suites passed **`OK (59 tests)`**, 19.821 s. Their regressions cover retry and terminal attempts, no later calls after failure, session termination, preserved files/text, permanent skip counts and the reader's unchanged unavailable behavior. Exact commands/outputs are in the code commit.

The full corrected Pixel suite passed on its first run (exit **0**): **`OK (178 tests)`**, 269.452 s. The release app and live account were not used. `git diff --check` exited **0** with no output. The earlier 174-test results and intermittent Practice failure remain historical evidence for the initial implementation, not this correction.

ADR-19 names the accepted lock cost: `openNote` and all `committed` writes can wait behind one current page's content call plus up to 64 image calls. Separate image totals/progress remain out of scope; completion still counts pages. No full-disk/unreadable-cache policy change, new dependency, schema, deployment or release installation.

**Limits:** tests use labelled isolated Room/loopback fixtures, not the live account; no production stub was introduced. Backend tests and opt-in private-corpus measurements were not rerun because their code/rules did not change. Real-account image-prefetch timing/storage, actual image HTTP timeouts and off-screen WorkManager lifecycle remain unverified for this image pass. No push, deployment or release installation was performed. PR5 and the mechanical moves are merged and pending deployment, per the user's confirmation; other Section 5 work remains deferred.

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
