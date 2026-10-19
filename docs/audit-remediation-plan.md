# Codebase audit and root-cause remediation plan

Audited: **2026-10-18**. Reconciled: **2026-10-19**, while Android foundations PR4 is underway. **Freeze scope decisions D1–D7 are settled in section 5.** Steps **1, 2, 3, 5, 8, 9 and 10** have implementation evidence within the scopes recorded below; steps **4, 6, 7 and 11** and the final-candidate gates remain open. **This is the reconciled execution plan, not a claim that remediation is finished or code is frozen.**

## Freeze scope and closure

This document owns audit scope, remediation disposition and freeze acceptance. [Invariants](invariants.md), [ADRs](adrs.md), the module READMEs and [feature status](feature-list.md) retain their existing owners; link to their contracts rather than introducing competing ones here. The [Android foundations plan](android-foundations-plan.md) owns PR4's detailed lifecycle acceptance cases. Its original baseline/proposal wording is not evidence that implementation has not begun: PR4 is underway, and ADR-27 records the implemented Navigation 3 direction.

### Required sequence

1. **Finish and merge foundations PR4.** Require its T1–T17 and D1–D3 checks under the recorded policies, complete screen conversions and replacement cleanup, full device execution, and its ADR/architecture/invariant updates. Record the actual B1 refresh-on-return decision; neither this reconciliation nor silence approves B1. Do not reopen Navigation 3, the approved saved-state format policy or the existing mutation/retirement owner.
2. **Rebase the remaining remediation onto that merged code.** Re-grep owners, callers and tests. Step 4 precedes step 6's question-to-current-passage matching; step 7 extends PR4's annotation operation ownership. Extend any first-grade operation introduced by PR4 rather than creating another one. Complete steps 4, 6, 7 and 11. Package/file moves and formatter changes are excluded by D1.
3. **Nominate an exact candidate and perform the final audit.** Section 8 defines evidence and pass criteria. Stop discretionary code changes during verification, but permit fixes for failed gates. A code, schema, generated-contract or packaged-asset change creates a new candidate and invalidates affected evidence; rerun mandatory combined checks/device execution on the final executable candidate. Never freeze a known failed candidate to meet a date.
4. **Freeze only after every required gate passes and every retained limit has an explicit disposition.** The matching signed release APK and hosted-backend checks are required by D3; deployment, installation and live mutations still need separately scoped approval. Later changes reopen affected audit gates; a documentation-only evidence update identifies the unchanged executable revision.

The target remains the existing single-user Android app, supported phone and backend—not a multi-user scale redesign or an exhaustive security proof. The fixture demo, two-week usage/product gates, paging and other existing product deferrals remain visible in feature-list.md; audit closure does not mark those product gates complete. New audit defects join the closure table before sign-off rather than disappearing into an unowned exclusions paragraph.

### Closure table

“Implemented” means the recorded slice passed its stated checks, **not** that the future freeze candidate has passed. Final-candidate results belong to section 8. An accepted limit needs the user's decision, impact, named owner and revisit condition; a pending decision is not a waiver.

| Item | Current disposition / evidence owner | Required before freeze |
| --- | --- | --- |
| A1 — delivered page size | Implemented, section 9 | Retain exact-limit/Save all regressions; legacy unreadable rows close under step 4, not A1's new-output cap. |
| A2 — immutable image identity | Implemented, section 10 | Preserve snapshot/legacy-cache/deletion behavior and verify the packaged image path. |
| A3 — removal lifetime | Implemented with follow-ups, sections 15–16 | Recheck against PR4's admitted mutations, pending work, exports, startup and fresh local-copy owners. |
| A4 — saved/canonical compatibility | Required, step 4; D2/D4 decided | Narrow unusable-cache retirement and demonstrated shipped incompatibilities only; freeze canonical rules, no general migration system. |
| A5 — search membership/limit | Implemented, section 11 | Preserve distinct-note, rename and removed-note behavior. |
| A6 — terminal reading refusal | Required, step 4; schema change previously approved | Preserve local resume across library replacement/restart; unchanged work is not resent or counted waiting. |
| A7 — study continuity/first grade | Required, step 6; D5 decided | One question-matching rule and one atomic/idempotent local command; no identity based on mutable prompt text. |
| A8 — confirmation boundary | Product decision recorded in section 5; implementation closure in step 6 | Canonical line alone is confirmed; linked saved explanation and its stale/unavailable state are explicit. |
| A9 — page-independent management | Required, step 7 | Edit/delete from Notebook without fetching content; preserve conflicts and PR4's entry lifetime. |
| A10 — library replacement | Implemented, section 13 | No legacy routes/callers/old-only suites; matching app/backend contract. |
| A11 — HTTP/failure/credential boundaries | Implemented, Step 9 verification | Preserve generated contract, whole-answer validation, positive refusals, lifecycle ordering and runtime deadlines. |
| A12 — verification wiring | Implemented, section 12 | Current workflow triggers run packaged-asset/contract checks; device compilation is not execution. |
| C1 — cached image read versus deletion | Implemented in step 2 | Retain file-interleaving coverage; no failed-read network fallback. |
| C2 — baseline creation versus acknowledgement | Required, step 4 and PR4 T16 | Both completion orders and covered/left/reopened entries; only presentation acknowledges. |
| C3 — credential writers/removal ordering | Implemented in step 9 | Keep alternate/encoded callback, renewal and removal race coverage. |
| C4 — unowned local data | Decided and implemented in step 3 | Valid callback before cleanup; fresh navigation root for the fresh copy. |
| C5 — library allocation/history limit | Accepted under D6, measurement required in step 11 | Record actual candidate/corpus usage, the pre-check allocation risk and the existing size-based revisit condition; no paging/new cap. |
| C6 — runtime SQL/socket failure | Implemented in step 9 | Preserve real PostgreSQL cancellation/rollback/recovery tests; do not claim a total HTTP deadline. |
| C7 — retained backups/privacy wording | Implemented in step 10 | Wording matches retention; current-schema recovery evidence is addressed in section 8. No incidental backup deletion. |
| C8 — note content in logs | Newly code-derived; required, step 11 | Remove content-bearing logging at its owner and verify the missing-heading failure path without exposing note text. |
| C9 — dependency/advisory assessment | Required, step 11; current assessment not executed | Inventory resolved dependencies, assess advisories, and fix or explicitly accept each applicable finding. |
| C10 — document-history provenance | Accepted under D7 | Preserve authorized-repository and exact-selection validation; make no document-history claim. Revisit if that stronger guarantee is required. |
| Foundations PR4 | Underway, companion plan | Merge evidence covers actual screen owners, mutation completion, UI-result consumption, restoration and replacement cleanup. |

Historical branch names, totals and exclusions below describe their own revisions. They are not the current branch state, a current test count, or automatic exemptions from final gates.

### Implementation progress

- **Step 9 complete and verified:** typed responses, safe item refusals, structured operation results, credential lifecycle ordering and fixed runtime SQL/socket deadlines. The standalone API plan is retired; [step 9 verification](#step-9-verification) owns the evidence.

- **Step 1 complete:** `MarkdownRenderer.MAX_PAGE_BYTES` owns a 1 MiB UTF-8 ceiling. HTML generation stops at that ceiling; `DocumentController.Content` checks the complete HTML + search text + path + title + blob SHA before delivery. These are different checks: bounded generation and complete-row acceptance, not two competing limits. One content request still makes at most **one note-blob request** (credential renewal, when required, is separate); no alternative fetch or retry strategy was added.
- **Root-cause replacement:** removed the intermediate HTML serialize-and-reparse path and the unbounded final serializer. One bounded serializer now receives the existing DOM nodes directly. Its small private output stream satisfies the named generation-bound requirement; this safety fix adds production lines rather than being a net-line-reducing mechanical refactor. No old renderer, compatibility wrapper, alternate parser, old-only test suite or temporary measurement class remains in the repository. Existing behavior assertions remain.
- **Evidence:** the initial HTTP regression failed with `Status expected:<422> but was:<200>`; it now passes. Exact-limit/+1-byte tests cover complete content and HTML serialization, including multi-byte UTF-8 and supplementary characters. On the approved Pixel 8a (Android 14/API 34), complete 1 MiB and 2 MiB minus 4 KiB string payloads read successfully; 2 MiB and both larger probes fail with `SQLiteBlobTooBigException`. The initial no-device blocker was resolved by connecting that phone.
- **Device outcome:** all **80 device tests passed**. The new Save all test uses production Api/Sync/Room against a labelled loopback fixture: a complete 1 MiB page round-trips, oversized notes are skipped, an older saved page is not overwritten, progress continues, and a second run fetches only the remaining unsupported versions. Full commands/results and remaining exclusions are in section 9.


- **Step 2 complete:** saved pages carry `snapshotCommitSha`; content captures the document/checkpoint and image index together, resolves embeds in the DOM, and serializes once. Image requests and files use the displayed snapshot plus resolved path. `RepositoryRow` owns the latest checkpoint; `NoteRow` owns the saved snapshot; navigation preserves the displayed snapshot. No new manager, dependency, retry, resolution fallback, or image-change index was added.
- **Approved retirement:** the user approved the coordinated image contract, Room migration and legacy-cache deletion, preserving saved text, annotations and pending changes. Room v14 leaves legacy image identity null; images are unavailable until refreshed. The old embed endpoint shape, URI, client method, fetch-time resolver, ambiguity error code and blob-keyed cache lookup are removed. Existing behavioral assertions moved onto snapshot/content tests; historical schema files remain migration evidence, not parallel implementations.
- **Simplicity trade-off:** any repository commit makes a saved page outdated, even if its Markdown did not change. This may refetch unchanged pages but avoids another image-dependency index. Snapshot identity is a required fact, not another mutable copy of “latest.” The first draft's extra HTML parse/serialize pass was removed after the user approved returning a DOM; canonical-only consumers no longer serialize unused HTML. A replacement review also updated the opt-in corpus writer to bind paths from its Git tree before serialization.
- **Step 2 evidence:** **279 backend tests, 72 Android JVM tests and all 87 device tests passed**; four opt-in corpus tests remain skipped. An image-only refresh, refresh during blob fetch, old/new cached reads, legacy namespace isolation, cache-read/deletion interleavings, v13 migration, figure recreation and real navigation saving are covered. Full commands/results and exclusions are in section 10.
- **Step 5 complete:** `LibraryDao.searchNotes` returns one row per note from `documents` left-joined to `notes`, so the 50-note limit counts distinct notes and names come from the current document; Kotlin's post-query de-duplication is deleted. A removed note still matches while its saved copy is kept, as the existing test asserted. The fixture of a saved copy with no `documents` row was replaced by a removed row: the server deletes document rows only on disconnection, when the phone forgets their saved copies too. Evidence in section 11.
- **Step 10 complete:** Android CI's test job runs `:app:mergeDebugAssets`, which runs `bundleReader` (`npm run build`: syntax check + bundle) through the packaged app's asset wiring; the workflow also triggers on `Library.java` and `assetlinks.json`. The `MAX_RESPONSE_BYTES` scrape is kept and contained by that trigger: it fails loudly, both files name each other, and a shared config file would be a second mechanism, not a simpler single owner. Deletion wording in both dialogs, ADR-11 and the privacy invariant now names retained server backups. Evidence in section 12.
- **Step 8 complete:** the five legacy GET routes, their response records, `Documents.list`, `DocumentController`'s unused `connections` and `Reviews`' standalone notebook snapshot are deleted; `Library.read` is the one snapshot owner. Every test assertion that used them reads `GET /api/library`, with no test deleted. ADR-08/24 record that older releases are unsupported once the backend deploys. Evidence in section 13.
- **Step 3 follow-up:** PR #66's initial DAO existence check was insufficient for a re-created same-account store, exports or background startup. The user approved retiring the old `Sync`/image lifetime and publishing a fresh instance only after successful cleanup. Local writes and exports now share that lifetime's existing lock; stale calls cancel instead of gaining access to a new copy. `Account` owns startup for both activity and worker. Section 15 records the callback/notice fixes; section 16 records lifetime verification and remaining exclusions. The original evidence in section 14 is historical, not proof of the follow-up's acceptance criteria.
- **Historical branches:** steps 1–2 were published on `fix/saved-page-integrity` (renamed from `audit-root-cause-cleanup` before publication); step 3 and its audit fixes were on PR #66, `fix/removal-one-owner`. Step 1 is commit `dbf4b10`; the user's approved local-main correction at that time left `main` at `f8ccf82`. No existing remote history was rewritten.

## Goal and verdict

Make each feature understandable through one entry point, one decision owner, and a small number of explicit states, while preserving the reading, offline, privacy, and source-integrity guarantees in [invariants.md](invariants.md).

The codebase does **not** need a wholesale rewrite or more design-pattern machinery. The backend's feature packages, explicit JDBC transactions, pure rendering/anchoring algorithms, and the isolated WebView boundary are useful foundations. Android has the greater cognitive-load problem: unrelated responsibilities accumulate in screen files and `Sync`, and several operations require coordinating state owned by different lifetimes.

The principal problems are incomplete identities, incomplete state transitions, and incomplete replacements—not a shortage of interfaces, factories, repositories, or managers. Moving files alone would not fix them.

This plan is a repository-wide structural audit with deeper traces of rendering, images, authentication/removal, local storage/sync, search, annotations, Practice/Review, and CI. It is not an exhaustive security proof or a claim that every test and deployment path was executed.

### Evidence labels

- **Reproduced:** executed a synthetic probe against production code or the exact production SQL.
- **Code-derived:** traced the relevant callers and implementation; the described failure/interleaving was not executed on a phone.
- **Documented debt:** already acknowledged in the repository; acknowledgment does not make the replacement or feature complete.

Synthetic inputs were deliberately labelled and used only in temporary files, in-memory SQLite, and a throwaway loopback server. They were not put into application data or a shippable path.

## 1. Findings, ordered by consequence

The findings below describe the audit baseline, before remediation. Implementation status is tracked above; historical probes are not a second implementation or a claim that a fixed defect remains active.

| ID | Priority | Finding and evidence | Root cause |
| --- | --- | --- | --- |
| A1 | P1 | **Accepted Markdown can produce an answer the phone cannot consume.** A 36,039-byte synthetic note with 3,006 blocks produced 9,544,792 bytes of HTML, despite satisfying both renderer input limits. `Api` accepts at most 8 MiB for content. A smaller probe produced 3,192,792 bytes of HTML, also exposing the repository's acknowledged large-Room-row risk; that device failure was not reproduced. See `MarkdownRenderer.java:179–189,283–325`, `Api.kt:28,143–147`, `Rows.kt:55–65`, and `feature-list.md:133`. **Reproduced output-size mismatch.** | Input size and block count do not bound generated output to the transport/storage contract. |
| A2 | P1 | **Image identity does not identify the fetched bytes.** `NoteImages.kt:35–49` caches under document + displayed note blob, but sends no version. `DocumentController.java:215–217` and `Attachments.java:35–37` fetch at the latest document/connection commit. Different displayed blobs sent identical image URLs in the probe. An image-only repository edit can also leave the note blob unchanged, keeping an obsolete cached image indefinitely. **Reproduced client requests; server behavior traced.** | A Markdown blob is not the identity of a repository snapshot or an image. |
| A3 | P1 | **Removal does not close admission to all work it intends to remove.** `RepoReadApp.kt:199–212,234` starts removal but keeps ordinary screens available while a stored session exists. `Sync.kt:451–465,548–571` permits local saves outside the lock used by `clearAll`. `ReaderSession.kt:373–394` deliberately launches final saves in the application scope. A reader entered during removal can release and save while clearing proceeds. **Code-derived race exposure, not a reproduced data leak.** | Removal's job, persisted intent, screen gate, and local-write admission have different owners/lifetimes. The sync lock protects remote-result writes, not every writer. |
| A4 | P2 | **Page compatibility is not consistently invalidated.** `Sync.kt:245–264` treats current-blob/path pages as usable based on a fixed minimum format and suppresses a refresh failure for a same-version cached page. ADR-18 explicitly records that the heading cap changed without a format bump. Commit `b11410a` also changed parsing for BOM/URL-only footnote cases without a format bump because those cases were absent from the measured personal corpus. Existing pages outside that corpus are not thereby compatible. `AnnotationService.java:85–90` resolves by blob alone. **Documented debt and code-derived compatibility gap.** | Source version, presentation format, and canonical-anchor compatibility are different facts but have no complete evolution policy. |
| A5 | P2 | **Search limits rows before resolving duplicate representations.** `LibraryDao.kt:217–225` unions listed-note and saved-note rows; `Search.kt:62–67` deduplicates afterward. With 60 matching notes, the 50-row SQL limit left only 25 distinct notes. The saved-note branch also returned an obsolete title/path after the current document was renamed. **Reproduced with the exact DAO SQL.** | Two representations compete to name one result, and the application repairs the query's cardinality after the limit. |
| A6 | P2 | **A refused reading position stays in the send queue.** `Sync.kt:512–520` leaves non-404 refusals pending; `LibraryDao.kt:206–211,305` continues counting/selecting them. `LocalSyncTest.kt:113–140` explicitly asserts this behavior. It differs from refused annotations and grades, and can be permanent for an old page's overlong heading. **Documented debt; code and existing test traced.** | Reading state has no representation for “kept locally, rejected remotely, do not resend unchanged.” |
| A7 | P2 | **Question identity and grading are weaker than their UI claims.** `Practice.kt:106–115` identifies an existing question through document + mutable prompt + mutable answer text. Editing either can create another card, as `feature-list.md:130` acknowledges. `gradeNewQuestion` at `146–168` performs the existence check, card creation, and first grade as separate operations. `NonCancellable` at `327` is not a database transaction or an idempotency constraint. **Documented identity debt; code-derived atomicity risk.** | There is no stable authored identity, and no single atomic owner of “make this question's card and first grade.” |
| A8 | P2 | **Bank-card freshness covers the bank, not necessarily the displayed answer.** Cards anchor the bank's Canonical line, but `Answer.kt:109–159` displays a separately saved linked note. `AnnotationRow.cardBlock` and server `Annotations.Card` check the card's own document version only. A linked answer can change without changing that bank line. **Code-derived product/safety gap.** | The product has not made the boundary between authoritative answer and supplementary linked explanation sufficiently explicit. |
| A9 | P2 | **Managing an annotation unnecessarily depends on opening its page.** `Notebook.kt:154–171` offers Open passage, while edit/delete controls are inside `Reader.kt:355–434`, after content loads. On another device, an annotation can be synced for a removed or unsupported note with no saved page; fetching that page fails, so its database-only management actions are unreachable. **Code-derived missing feature path.** | User-owned annotations are treated as controls on a successfully rendered page rather than independently manageable records. |
| A10 | P2 | **The library replacement still has five legacy read routes and callers in tests.** The current Android production code uses `/api/library`, not `/api/repositories`, `/api/repositories/{id}/documents`, `/api/reading-states`, `/api/bookmarks`, or `/api/notebook`. Backend tests still call them, and `Documents.list` and `Reviews`' standalone snapshot remain. ADR-08/24 explicitly defer their removal for the installed older APK. **Verified caller search; documented policy debt.** | A replacement is intentionally unfinished. The ADR's compatibility exception conflicts with the stricter no-parallel-replacement rule in `AGENTS.md`. |
| A11 | P2 | **Historical finding, resolved within step 9's recorded scope: successful-response and failure-response validation were inconsistent.** `ServerJson.kt:18` accepts missing nullable fields as null; `annotationRow` accepts a CARD with no question, which later meets `checkNotNull(row.question)` in `Review.kt:402`. Numeric `org.json` getters remain coercive. Error codes/messages use `optString` at `Api.kt:109–112`, while `refusesItem` trusts any non-`HTTP_` code at selected statuses. `Api.kt:114–119` also discards the original I/O cause and request context. **Code-derived boundary gaps.** | The boundary validates some scalar types but not the complete response or its cross-field invariants. Recovery policy partly relies on a string-prefix heuristic. |
| A12 | P2 | **CI omits dependencies of the behavior it claims to check.** The Android test job installs npm dependencies but runs neither the reader check nor bundle build; compiling device tests does not execute reader behavior. Its path filters also omit `backend/.../library/Library.java`, which `android/build.gradle.kts:43–52` parses to generate a client constant. **Verified workflow/build trace.** | Build dependencies are hidden across module boundaries, and compilation is mistaken for broader verification. |

Additional concerns at the original audit baseline are listed below. The closure table above owns their current disposition; statements about missing checks/writers here are historical, not claims that completed fixes remain absent.

- The image cache's hit path checks `isFile` and then reads outside its deletion lock (`NoteImages.kt:45`). A sweep can delete between those operations. Existing tests exercise deletion during an HTTP fetch, not this cached-read interleaving.
- Change-summary acknowledgement and baseline creation are ordered in different owners: `Reader.kt:140–142` can clear a baseline before `ReaderSession.open` saves the newly displayed version and `LibraryDao.saveDisplayedReading` creates that baseline. Test both completion orders; do not infer “seen” merely from a completed request.
- GitHub credential renewal, callback persistence, and revocation/removal are not one serialized lifecycle. `AppAuthController.java:82–85` explicitly acknowledges a simultaneous sign-in losing its new token. `GitHubCredentials`' renewal lock does not cover every credential writer. Characterize these races before claiming stronger guarantees.
- `SignIn.kt:62` adopts legacy unowned local data as the next signed-in account's. That is an assumption, not verified ownership; define an explicit upgrade policy rather than silently extending it.
- `Library.answer` measures the serialized response only after loading every row and serializing it. The 8 MiB wire limit is not an allocation/work bound. The unpaged-history limit is already documented; do not add pagination machinery without a size/usage decision, but do not describe memory as bounded by that response limit.
- `application.properties:39` sets database **login** timeout. This audit found no explicit query/lock deadline. The startup timeout test does not establish a deadline for an already-connected database query. Runtime timeout behavior remains unverified.
- Deletion/privacy wording must distinguish live data from retained nightly, deploy, Mac, and manual backups. Backup retention is intentional; do not delete backups as an incidental “cleanup.”

## 2. Ownership and cognitive-load audit

### What to retain

| Boundary | Verdict |
| --- | --- |
| Backend feature packages | Retain. The production Java package-import graph inspected here is acyclic. Do not replace it with horizontal controller/service/repository directories. |
| `Annotations` / `AnnotationService` / `Anchoring` | Retain their distinct responsibilities: persistence and concurrency; version fetching/verification/orchestration; pure matching. They are not interchangeable wrappers. |
| `Bookmarks` sharing the annotations table | Not automatically a defect. Ownership is partitioned by record type. Describe that accurately instead of claiming every physical table has exactly one writer class. |
| `LocalStore` + `LibraryDao` | Retain a single local transaction boundary for complete library replacement. Splitting one DAO into ten DAOs would add navigation and cross-DAO coordination without fixing these defects. |
| `ReaderSession` / `ReaderPage` / `ReaderWebView` | Retain the separation between one live page, shared page hosting/isolation, and Android's selection-menu adapter. Shared hosting is already factored out. |
| `ReviewSchedule` | Retain pure scheduling over the log; do not introduce persisted due-date state, a scheduler dependency, or a second scheduling implementation. |
| WorkManager, immutable mutation IDs, original anchors, migration history | Retain. They satisfy named requirements; they are not disposable merely because queues, state, or historical schemas exist. |

### Where the current structure increases working memory

These are original-audit observations. Account/navigation ownership has since changed, and PR4 owns the screen-lifetime replacement. The superseded package moves are excluded by D1; this historical diagnosis does not authorize reintroducing removed code or expanding freeze scope.

1. **`library/` no longer means library browsing.** It also owns Practice, Review, scheduling, session mechanics, and export. Meanwhile bank parsing and bank answers live under `reader/`. Understanding one study session requires searching both folders.
2. **Some shared behavior lives in a misleading feature file.** `ReviewSchedule.dueSession` calls `interleaveByNote` in `Practice.kt`; Notebook calls `practiceTopics` and `inPracticeFolder` there too. These are real callers, not dead helpers. Move them to their actual concern rather than deleting them based on their names.
3. **`core/network/` contains Compose presentation.** `Load.kt` owns spinners/buttons and `Api.describe()` words failures for screens. “Core network” therefore suggests a dependency boundary the files do not have. The auth/network imports also form a package cycle through `StoredSession`; this is a data-type dependency, not proof of a runtime object cycle, and does not justify an interface just to make a diagram acyclic.
4. **The root composable owns too much application behavior.** `RepoReadApp.kt` combines navigation encoding, back-stack state, sign-in completion, account deletion, sign-out, cleanup outcomes, and screen rendering. Application-scoped jobs capture composition-owned state.
5. **The reader is a distributed state machine.** `ReaderScreen` has 11 remembered mutable variables, in addition to produced/observed data; `ReaderSession` has six observable fields and five mutable view/job/touch fields. These are not all redundant, but their lifetimes and transitions are not apparent from one owner.
6. **`Sync` is an offline-data boundary, not merely a sync job.** It coordinates opening/saving pages, pending mutations, complete snapshots, change requests, disconnection, and file cleanup. Keep one coordinator, but remove screen wording and unrelated model/formatting work from it when changing those operations. Do not respond by creating six single-implementation managers.
7. **`Rows.kt` mixes storage shape, projections, and card policy.** Keeping the schema discoverable together is useful. The problem is not the line count; it is that nullable fields permit invalid combinations and callers must know which combinations are valid. Fix construction/transition invariants before considering a file split.

### Facts that must remain distinct

Do not “simplify” these into one ambiguous `version` or one `loaded` Boolean:

| Fact | Existing source of truth |
| --- | --- |
| Latest known source/path/removal | `DocumentRow` |
| Saved rendered page and its format/path | `NoteRow` / `SavedVersion` |
| Version actually displayed | The immutable note owned by a `ReaderSession` |
| Original selected passage | Annotation's original source/offset/text fields |
| Last trusted passage location | Annotation's location |
| Version the location was resolved against | `resolvedBlobSha`, including an unsuccessful resolution |
| Version whose answer was confirmed | `checkedBlobSha` |
| Last version actually read | `ReadingRow.lastReadBlobSha` |
| Oldest changes not yet acknowledged | `ChangeBaselineRow.sinceBlobSha` |
| Latest repository snapshot | `RepositoryRow.lastSyncedCommitSha` |
| Saved/displayed image identity | `NoteRow.snapshotCommitSha` plus resolved paths in that saved HTML; null only for legacy unknown identity |

Reduce cognitive load by giving each transition an owner and taking these facts as a coherent input—not by erasing necessary distinctions or spreading their comparisons through screens.

## 3. Implementation sequence

The user approved proceeding with this sequence and committing each completed step. Concrete migration, deletion, route, infrastructure and product-policy gates in section 5 still apply. For each approved step: reproduce first, fix the owner, migrate every caller, remove superseded paths/tests, execute checks, and commit the working slice. Do not combine correctness fixes with a repository-wide move or reformat.

Steps already implemented below retain their original requirements as a behavior checklist. They are not instructions to reintroduce removed code. The post-PR4 work is steps 4, 6, 7 and 11, followed by section 8's final-candidate audit. Recheck all locations against merged PR4 before implementation; its changes can satisfy part of a requirement but do not waive the remaining acceptance behavior.

### Step 1 — Bound render output at its owner (A1)

**Owner:** backend document rendering/content boundary.

- Add regressions using small source inputs that expand through repeated heading attributes and escaping, including the two measured cases.
- Establish the supported complete page/row size on the actual supported Android runtime. Measure HTML plus search text, not HTML alone or source bytes alone.
- Enforce the resulting server-owned bound before sending/saving an unusable page. Preserve the existing typed unsupported-content behavior so Save all can report and skip it.
- Do not simply raise `MAX_JSON_BYTES`, catch an eventual Room exception in every reader, or add a second content-fetch strategy.
- Bound intermediate generation if the measured expansion requires it; a final byte check alone is not a memory bound.

**Exit:** every successfully delivered supported page fits both transport and local read constraints; oversized pages fail explicitly without replacing the previous saved page. Test the exact limit, one byte beyond it, Save all continuing, and the second run.

### Step 2 — Make image bytes addressable by immutable identity (A2)

**Owners:** backend `Attachments` for source identity/fetching; phone `NoteImages` for cached files; `Sync` for when saved artifacts are retained or removed.

- Give a saved page an explicit snapshot context for its image requests. The backend must serve that context, not silently substitute the latest connection checkpoint.
- Ensure image-only edits and changed embed resolution cannot reuse an old cache identity. The source of truth is the repository snapshot/image identity, not another cache-generation counter.
- Update content, image lookup, persisted saved-page metadata, file keys, sweep inputs, and every reader/preview/answer/figure caller together. A schema/route change requires approval.
- Serialize cache-hit lookup/read with deletion using the existing file owner. Preserve the existing protection against a late fetched image being written after deletion.
- Keep one fetch strategy and one cache layout; retire the superseded layout through an explicit approved upgrade policy, not parallel lookup fallbacks.

**Exit:** an old displayed page never receives a new snapshot's image; an unchanged Markdown blob can display an updated image after refresh; repeated reads and deletion interleavings are tested. State the ceiling on image and embed-resolution calls before implementing.

### Step 3 — Give removal one application-lifetime owner (A3)

**Owner:** `RepoReadApplication` for the operation and observable outcome; `SessionStore` only for persisted session/ownership/removal facts; `Sync`/DAO only for their I/O and transactions.

- Move sign-out/account-removal orchestration out of composable closures into that existing application owner; do not add a `RemovalManager` around it. *As built:* one `Account` class that the Application constructs is that owner (not a wrapper around another one); the Application's own lazy properties open the app's real database, so tests need the owner over isolated stores.
- On accepted removal intent, close admission to ordinary screens immediately, not only once the session is gone. The one authorized removal request can still use its credential.
- Prevent a delayed local writer from repopulating the store after successful cleanup. Do not scatter `if (removing)` checks across individual screens. *Follow-up after PR #66 audit:* document existence alone was not a lifetime boundary. `Account` retires the old `Sync` and its image owner; local writes, network answers and exports share its lock, so clearing waits for admitted work and all later calls on that instance cancel. Only successful cleanup publishes a fresh instance. The DAO predicates remain for the different concern of a note disconnected within a still-active copy; no epoch is threaded through callers and no screen-job registry is added.
- Represent operation kind and stage explicitly where restart behavior needs them. A few mutually exclusive states are preferable to independent `signedIn`, `removing`, nullable outcome, and screen-local busy flags.
- Preserve local data on a refused/unconfirmed account-deletion response unless the user explicitly chose otherwise. A 401 is not evidence that the server deleted an account. *As built (decision 3):* the removal is recorded only after the server confirms, so an interrupted deletion also keeps everything.
- Resolve the legacy unowned-data assumption explicitly before issuing a session over nonempty unowned storage. *As built (decision 4):* a sign-in with no recorded owner runs the removal first; clearing an empty store is a no-op, so no emptiness check exists.
- Exercise rotation, process interruption, unreadable session storage, database failure, undeletable files, duplicate taps, delayed reader saves, background sync, and a second cleanup attempt through the real application wiring in an isolated test host.

**Exit:** after removal reports success, no pre-removal operation can write old-account data or artifacts, and another account cannot sign in while removal is unfinished. Tests must cover `SessionStore` + application owner + `Sync`, not only the Sync clear in isolation.

### Step 4 — Complete saved-page and reading-state transitions (A4, A6)

**Owners:** renderer for page compatibility; `Sync` for fetch/send decisions; DAO for atomic local transitions; reader for reporting display/acknowledgement events.

- **Presentation compatibility:** enumerate shipped saved-page formats and the actual heading-cap/BOM/URL-only-footnote changes. Bump the existing format when presentation or metadata requires refetch, with pre-upgrade fixtures, Unicode and boundary cases. Neither the current personal corpus nor an unchanged source SHA proves that every old page is compatible.
- **Canonical contract (D4 decided):** keep annotations and automatic re-anchoring for ordinary source edits. Freeze the current canonical block/text rules; presentation-only upgrades must not alter them. Limit upgrade work to demonstrated incompatibilities in already-shipped heading-cap/BOM/URL-only-footnote behavior, using old-page/selection fixtures and stored evidence. Preserve original selections/history and verify exact correspondence before using a location. If correspondence cannot be established for an affected case, use the existing conservative orphan/reattach and card-confirmation behavior rather than guess offsets; do not delete annotations or silently grade an uncertain answer. Do not introduce a general anchor-migration framework, new canonical-version registry or parallel old renderer. If the concrete cases cannot be contained without a new schema/API contract or broad migration, stop and present that trade-off before implementation; such a change is not pre-approved by D4.
- **Unusable legacy cache (D2 approved):** retire only rendered-page caches demonstrated unusable under the supported storage contract, together with their derived links/images as appropriate. Preserve readable saved pages, original annotations, review history, reading positions, bookmarks and pending work. Do not first materialize an oversized row to decide whether it is safe: characterize the existing full-row reads, including reader/preview/figure/answer, Practice and search projections. Use the existing storage owner for the narrow cleanup and its existing image sweep; no second cache layout or startup manager. Do not guess image identity. Offline, or when refetch is refused, show that the page is unavailable and needs a supported online replacement; do not claim the text was preserved after retiring it.
- **Upgrade failure:** a supported but old-format saved page remains readable when possible and visibly states the compatibility limitation if refetch fails, even for the same source blob. A failed fetch never replaces a readable complete copy. Test zero backend calls offline where applicable, one content request for an admitted refresh, typed unsupported content, failed fetch, second open and Save all's second run.
- **Terminal reading refusal:** add a durable refusal state/reason at the existing reading-row owner. Preserve the position locally, exclude unchanged refused work from `pendingReading` and waiting counts, and let a genuinely newer local save replace the refusal. A non-pending flag alone is insufficient: `clearAcknowledgedReading` currently deletes non-pending rows. Define library merge/deletion and newer-server-write behavior coherently with last-write-wins; an older server value cannot erase a newer refused local resume point. Preserve intentional disconnection/removal behavior and distinguish unsent-loss counts from sendable waiting counts. Unknown/malformed responses remain uncertainty, not terminal refusal.
- **Display acknowledgement:** baseline creation and acknowledgement are one coherent transition across DAO/Sync and PR4's Reader entry owner. A fetched summary is not a presented summary. Cover request-before-page-ready and page-ready-before-request, covered/popped entries, offline opening, reopening and a baseline changed while an earlier result was in flight. Acknowledge only the exact version/baseline actually presented.
- **Migration and regression:** exercise the approved reading-refusal upgrade from the last shipped schema, pending/acknowledged rows, a refused value surviving library replacement and process restart, a later user save, partial sending failure and the second sync. Start a separate legacy-page regression with a pre-cap oversized row and prove affected entry points cannot crash before the upgrade/refetch decision. Replace the old resend assertion, retaining coverage that unrelated pending work proceeds. Test retirement interrupted and run again; never erase user-owned data as cache cleanup.

**Exit:** demonstrated shipped incompatibilities are contained without broadening the canonical contract; no untrusted offset is silently reused; legacy unusable rows cannot crash a full-row read; local resume, refusal, waiting/loss counts and unseen-change status agree after failure, restart and a second opening/sync. A general renderer-evolution subsystem is explicitly out of scope.

### Step 5 — Fix search at the query (A5)

**Owner:** `LibraryDao.searchNotes` for result membership, current identity, and the distinct-note limit.

- Produce one row per document before applying the limit. Join saved content to current document facts; do not let cached page metadata compete with the current document's name.
- Preserve the intended policy for searching saved copies of removed notes; characterize it first rather than accidentally removing that behavior through the new join.
- Remove the Kotlin post-query deduplication once SQL guarantees the result shape. Do not fetch an arbitrarily inflated limit and deduplicate again.
- Test more than 50 title-and-text matches, renamed/moved notes with older saved pages, missing search text, removed notes, literal wildcards, no results, and truncation wording.

**Exit:** 50 distinct matching notes can occupy the 50-note result budget, and result names have one owner.

### Step 6 — Implement the decided study identity boundary (A7, A8)

**Owners:** existing study extraction for the current bank question; existing `Sync` mutation boundary/DAO for question-to-card matching and atomic card + first-grade creation; `Annotations` for server confirmation/gradeability; `ReviewSchedule` only for scheduling. PR4's entry owner owns the session and visible outcome, not another durable mutation implementation.

- **Continuity is decided:** an edited prompt is not a new question. A card follows its authored Canonical passage through the server's existing conservative re-anchoring; show the current bank prompt only when the current question-to-location correspondence is unambiguous. Use the source version and resolved passage identity, not prompt/answer-text equality, a version-local block id treated as eternal, or new fuzzy matching. No new authored-ID syntax or global question registry is required by this plan.
- **D5 guarantee:** prevent duplicate creation on this phone, including a card already synced to it. Independently created offline cards on different devices may remain separate records; global question uniqueness is not promised. Existing duplicates/history are preserved, not automatically merged or deleted. If several cards match, or correspondence is ambiguous/orphaned, show the problem and the existing management/reattachment route rather than silently selecting one, merging logs or creating another card to hide it.
- **One local transaction:** extend PR4's existing first-grade command if present. Validate grade, current saved/listed source, question correspondence and existing local card inside the same accepted lifetime/Room transaction that writes a new card and its first grade. Concurrent actions/entries and a second attempt cannot create a second pair. A lock or `NonCancellable` alone does not roll back a card when the grade write fails. Failure before commit leaves neither row; no screen-level precheck is the uniqueness authority.
- **Remote delivery remains separate:** local creation makes zero HTTP calls and publishes pending work through the existing sender. A remotely accepted card followed by a refused/uncertain grade is not an atomic remote transaction: preserve and explain the existing typed refusal/pending behavior, with no compensation request, automatic history merge or alternate strategy. Retain the existing immutable mutation ids and per-pass ceilings.
- **Confirmation is decided:** only the bank's Canonical line is confirmed. A linked note is supplementary and shown from its saved copy; stale, missing or unrenderable linked content is visibly labelled, never described as confirmed current. The author keeps bank/linked notes in step on GitHub. Test Review as well as Practice; an unavailable supplementary section does not silently strengthen or change what the grade claims to confirm.
- Keep scheduling derived from the immutable log. Do not introduce duplicate persisted scheduling state to patch identity problems.
- Test prompt-only, Canonical-line, link-target and linked-note edits independently; identical prompts/passages in different locations; multiple existing cards; orphaned correspondence; same-blob page upgrades under step 4; interruption/write failure between card and grade; concurrent entries/submissions; rotation/pop/removal; server refusal; library reload and the second attempt. Preserve a manually authored non-bank card's behavior. Run the actual Practice/Review entry owners and real DAO transaction, not only a matching helper.

**Exit:** prompt edits preserve an unambiguously matched card/history, uncertain mappings are explicit, the local card/first-grade operation is atomic and idempotent, and UI wording matches the Canonical-only confirmation boundary. Cross-device duplicates remain the named D5 limitation, not a hidden promise.

### Step 7 — Manage annotations without requiring a page (A9)

**Owner:** annotation operations, presented from Notebook as well as the reader; rendering owns only page-dependent actions.

- Expose existing edit/delete behavior for synced annotations independently of content loading. Reuse the existing operations and relevant dialogs rather than building a second annotation implementation.
- Keep reveal/selection/reattachment page-dependent; do not disable unrelated database-only management when the page is absent.
- Test a second-device notebook containing an annotation on a removed or unsupported note with no saved HTML. Verify ownership checks and optimistic conflicts remain unchanged and management makes no GitHub content request.
- Extend PR4's actual Notebook/Reader entry-owned outcomes and current-resumed UI consumption. Reuse existing dialogs/operations without introducing another annotation service or bypassing `Sync` retirement. Keep acknowledged edits/deletes online and existing pending-deletion behavior offline; do not add question editing or new authoring features. Exercise conflict, refusal, actual timeout, rotation/cover/pop, account removal, Cancel, partial failure and an explicit second attempt. A missing page must not prevent deletion of an otherwise manageable card and its explicitly confirmed history loss.

**Exit:** users can manage their records even when RepoRead cannot render their source.

### Step 8 — Finish the library replacement (A10)

**Owner:** backend `Library` for the complete snapshot; existing table/type owners for its contents.

- Confirm the installed app has the `/api/library` contract, or explicitly approve ending support for the older app. The repository cannot establish the installed version.
- Migrate test helpers/assertions in `RepositorySyncTest`, `ReadingStateTest`, `BookmarkTest`, `AnnotationTest`, `AvailableRepositoriesTest`, and `DataControlTest` to the library response where they still use legacy lists. Preserve the mutation, privacy, removal, move, and timestamp assertions—not just the response's existence.
- Remove the five legacy GET mappings, their response-only types, `Documents.list`, and the standalone notebook snapshot wrapper. Retain `Reviews`' log ownership and the notebook data assembly needed by `Library`.
- Remove tests that solely exercise the deleted endpoint shape after moving surviving behavior coverage. Remove obsolete route contracts and the open contract-step item from the authoritative docs.
- Grep production, tests, docs, and scripts before and after. No aliases, compatibility routes, or copied snapshot implementations remain.

**Exit:** one server list-read contract, one database snapshot owner, and one corresponding behavior suite. No database migration is inherently required by this endpoint cleanup.

### Step 9 — Complete error boundaries and lifecycle checks (A11)

**Status:** complete and verified (2026-10-18), within the isolated/runtime/device scope and live/release exclusions recorded below. [Step 9 verification](#step-9-verification) owns the evidence, limits and exclusions.

**Owners:** backend controllers/records and their derived `backend/openapi.json` for published shapes; build-generated Android wire models plus `Api`/`WireJson`/`WireRules` for wire validation, correspondence and transport/envelope failures; `ServerRows` for typed row mapping; `Sync` for pending-item disposition; the existing operation owner for other expected failures; UI for wording. `Contract.kt` is retired, not an owner to recreate.

- **Complete:** validate complete server records once: strict scalar types, required-vs-nullable field presence, and cross-field constraints such as CARD requiring a question. Reject a broken response before any replacement transaction. Android's [JSONObject contract](https://developer.android.com/reference/org/json/JSONObject) explicitly permits scalar coercion and treats an absent mapping as null in `isNull`; those methods alone do not enforce RepoRead's stricter contract.
- **Complete:** define refusal dispositions from the known server contract. Unknown/malformed error envelopes are not evidence that an individual mutation should be permanently dropped.
- **Complete:** keep the underlying exception and safe method/path/status context for transport failures. Do not log bodies, bearer credentials, quoted passages, or arbitrary response content.
- **Complete:** return structured operation outcomes instead of assembling screen-specific refusal sentences inside `Sync`. Keep contextual UI wording at the consuming UI boundary; do not introduce a generic error framework.
- **Complete:** characterize concurrent token refresh/callback/sign-out/deletion. Make lifecycle decisions pass through the existing credential owner if the current race is to be removed; preserve encrypted persistence and the stated external-call ceilings. Do not add a second credential store or distributed locking for a single-process deployment.
- **Complete:** verify runtime database query/lock failure behavior before selecting any constant deadline. Login-timeout coverage is not query-timeout coverage. Any configuration/infra change remains separately approved.

**Exit:** expected external failures have one explicit disposition; malformed responses do not become programmer-state crashes or destructive mutation refusals; unrelated programmer errors still raise.

### Step 10 — Close verification gaps (A12)

**Owner:** the existing build scripts/workflows; no new test framework by default.

- Run the existing reader syntax check and bundle build in the Android verification job, not only while packaging a release after merge.
- Make Android workflow triggers cover backend files consumed by its build/signature checks. Source scraping of a Java expression is a fragile dependency; contain it explicitly first, and replace it only with a demonstrably simpler single-owner contract—not a new configuration system.
- Distinguish device-test compilation, device-test execution, real-corpus measurement, and live integration evidence in reporting.
- Add the regressions specified above to the owning existing suites. Do not count temporary probes as shipped regression coverage.
- For removed implementations, migrate behavior coverage and delete old-path-only tests in the same slice.
- Update backup/deletion wording to name retained backup copies and retention. Do not change backup deletion or deployment behavior as part of an unrelated code cleanup.

**Exit:** a reader or consumed-contract change cannot bypass its relevant existing verification, and a green report accurately names what ran.

### Step 11 — Close remaining audit concerns and nominate the candidate (C5, C8–C10)

**Owners:** existing Library/transaction owners for capacity, existing logging call sites for privacy, build/lockfiles for resolved dependency identity, NoteVersions/AnnotationService for provenance, and this document for the audit disposition. No new production subsystem is implied.

- **C5, accepted limit:** measure the real candidate/corpus, not invented seeded records. Record library bytes and list/log counts, measurement environment and observed resource behavior. Synthetic near-limit/worst-case regression data stays labelled and isolated. Preserve whole-answer refusal/previous complete phone snapshot behavior. D6 accepts pre-check allocation and unpaged growth, not an observed OOM or a false claim that the wire cap bounds heap. A resource failure reopens the candidate; present the smallest containment versus broader fix before expanding scope.
- **C8, required privacy fix:** `reader/Answer.kt` logs the linked note's heading on the missing-heading path. This is note-derived content, contrary to the privacy invariant. Reproduce/characterize that path with a labelled fixture, remove content from logging at its current owner and verify useful safe ids/state remain. Inspect adjacent reader/network/server failure logs and exception logging for content/credential exposure; report actual findings, not a blanket safe-logs claim. Do not add a logging framework, new telemetry or content-bearing measurement output to the repository.
- **C9, required current assessment:** inventory actual resolved direct/transitive npm, backend and Android dependencies plus packaged assets and pinned delivery actions/runtime inputs. Use `npm audit` as documented by reader-web/README.md; no dedicated JVM/Android advisory scanner was found in the inspected verification scripts. Record the chosen verified assessment method, versions/data date, coverage and gaps rather than inventing a Gradle task or adding a scanner dependency by default. Historical npm results are not the current assessment. Each applicable finding gets a fix or explicit user-accepted impact/reason/revisit condition; dependency upgrades require approval. An unavailable assessment or unanswered applicable risk is not a green security gate.
- **C10, accepted limit:** carry D7 into the source-traceability wording; retain repository isolation, byte-SHA/selection validation and current failure/call ceilings. Do not add history fetching or imply document-history verification was performed.
- **Evidence reconciliation:** ADR-06 reports an October 15 corpus rerun but its following paragraph and feature-list.md still describe post-October-9 measurement as outstanding. Bind any reusable report to its actual source/corpus revision; do not infer current coverage from the date or counts alone. The final corpus gate below resolves candidate coverage. Amend the stale chronology/status in the owning docs with verified evidence, without inventing a rerun. Likewise update old composition-owned/No ViewModel descriptions from PR4's actual completed wiring.
- **Replacement sweep:** grep production, tests, docs and scripts for retired routes/types/helpers and for screen-owned work forbidden by PR4. Update all surviving callers and behavior assertions; do not keep aliases, wrappers, parallel old-path suites or stale active instructions. Preserve migration history and labelled negative tests of refused old shapes where those remain current behavior.

**Exit:** C8 is fixed and verified, C9 is assessed/dispositioned, C5/C10 have the recorded accepted limits and required measurements, and the exact candidate has no unowned audit finding. This step does not execute release/deployment actions or create an automated live-write harness.

## 4. Targeted folder and file simplification

**D1, decided 2026-10-19: no optional package/file moves or formatter migration before freeze.** The earlier target tree and move table are superseded, not a second backlog to execute during correctness work. Keep the current packages, file names and Palantir/Kotlinlang formatters. A small ownership extraction required by a correctness fix still needs its concrete justification and caller/test migration; it does not authorize the former package reorganization.

Navigation 3 owns the back stack, saved-state decoration and entry ViewModel stores (ADR-27). Do not recreate or move the retired `StackSaver`, legacy encoder/decoder, state maps or handlers. PR4's entry-scoped ViewModels are the demonstrated narrow state holders, not an invitation to add a repository layer or generic MVVM scaffolding. Keep the backend's feature packages and existing transaction owners; `backend/deploy/` remains the deployment-script owner, not `notes/`.

### State patterns to use—and patterns not to introduce

- **Explicit transitions:** use the existing sealed/enum approach for mutually exclusive operations and refusal. Independent tabs/drafts remain independent; no state-machine framework.
- **Immutable facts:** retain the displayed snapshot, original/current anchor and confirmed source separately; never collapse them into an ambiguous version or loaded flag.
- **Atomic/idempotent commands:** existing Room/PostgreSQL transactions and mutation identities own multi-row correctness. A busy flag or non-cancellable coroutine is not a transaction.
- **Entry-owned work:** since PR4 ([ADR-28](adrs.md#adr-28--a-screens-work-belongs-to-its-entrys-viewmodel-a-confirmed-change-belongs-to-sync), the demonstrated case for a state holder), loads/progress/operation outcomes belong to the entry's ViewModel, accepted mutations to Sync's existing boundary, and account operations/current-copy publication to Account. Compose observes state and emits actions. Only the current resumed UI consumes navigation/share results; no retained composition callbacks or second event queue. Saveable drafts, minimal restoration inputs and the live WebView keep PR4's specified owners.
- **Pure core, explicit I/O:** keep scheduling, anchoring and diff logic pure. No factories, event buses, generic use-case classes, service locators, retry frameworks, operation managers or wrappers around the same DAO.

The [foundations owner table](android-foundations-plan.md#owners-after-this-plan) owns the detailed lifecycle contract. Follow it when implementing steps 4, 6 and 7; do not reintroduce composition-owned jobs, blanket non-cancellable sync, automatic mutation replay or refresh-on-return as a repair for a lost confirmed local commit.

## 5. Decisions/approvals required before implementation

### Previously decided — preserved

1. **Old app support (2026-10-18):** drop the legacy read routes. The replaced image contract already requires a matching app/backend; no backward-compatibility endpoint is restored.
2. **Persistence/API changes:** image identity, Room v14, image-contract/cache retirement and the single-serialization DOM refactor were explicitly approved and completed in step 2. The reading-refusal schema/contract in step 4 was approved 2026-10-18. These approvals do not cover unspecified later migrations.
3. **Account-deletion interruption (2026-10-18):** keep local data unless the server confirms deletion. An interrupted/unknown result or 401 is not evidence of deletion.
4. **Legacy unowned data (2026-10-18):** remove unowned local data before issuing a session over it, only after validating the pending sign-in callback.
5. **Study semantics (2026-10-18):** editing a prompt does not create a new question; the card follows its Canonical line through existing re-anchoring and shows the bank's current prompt when matched. Only the bank's Canonical line is confirmed. The linked section is supplementary, displayed as saved, and the author keeps bank/linked notes in step. Step 6 verifies stale/missing linked copies in both Practice and Review; it does not introduce authored IDs or reopen this decision.

### Freeze decisions — user responses on 2026-10-19

These D-identifiers are audit decisions, not the foundations plan's D1–D3 confirmation tests.

| Decision | User disposition | Impact, owner and scope |
| --- | --- | --- |
| D1 — optional moves/style | **Decided: correctness only; keep current layout and formatters.** | Section 4's prior move program is retired. No exact Google/Android style migration or new style tool. |
| D2 — unusable legacy page cache | **Approved: narrowly targeted cache retirement.** | Sync/DAO may retire only demonstrated unusable oversized rendered caches and derived artifacts. User-owned annotations/grades/positions/bookmarks/pending work and readable saved pages survive; affected text needs a supported online refetch. Step 4 verifies interruption and second run. |
| D3 — freeze versus deployment | **Decided: release/hosted checks before freeze.** | Require matching signed release APK and hosted-backend smoke evidence, not Dev/loopback alone. Actual deploy/install, phone settings, account actions and live writes still need specific execution approval; this decision does not perform or authorize them implicitly. |
| D4 — canonical compatibility scope | **Decided: keep annotations/re-anchoring; narrow renderer-upgrade scope.** | Freeze canonical block/text rules and address only demonstrated shipped incompatibilities, preserving original selections/history and conservative existing reattachment/confirmation behavior. No general renderer-migration/versioning system or removal of the annotation feature. A broader schema/API/migration requirement needs a new concrete approval. |
| D5 — question uniqueness | **Decided: local/synced-card guarantee, no global cross-device uniqueness promise.** | Existing Sync/DAO command owns local atomicity and matching. Independently authored offline duplicates can remain separate; no automatic deletion or history merging. Revisit only if global uniqueness becomes a requirement. |
| D6 — whole-library allocation/history | **Accepted for this single-user freeze, subject to measuring actual usage.** | Library retains the unpaged response and 8 MiB wire ceiling; reads/serialization can allocate before refusal. Measure current corpus, response size and observed resource behavior; do not call heap/work bounded by 8 MiB. Revisit as actual size approaches the existing response limit or measured resource failure occurs; no paging or new cap now. |
| D7 — source provenance | **Accepted: authorized-repository-level provenance.** | NoteVersions/AnnotationService still validate exact blob bytes and selected canonical text in the authorized repository, but do not prove membership in this document's history. Retain the documented limit and no stronger claim. Revisit before requiring document-history provenance; no hidden history walk or new GitHub calls. |

### Execution approvals are not scope decisions

Before running a concrete migration, dependency upgrade, deployment, app update, destructive live mutation or phone-setting change, state its exact changes/data impact and obtain the required approval. Use isolated stores for destructive failure coverage. A newly found advisory needs a current applicability/risk decision; D6/D7 do not waive unrelated security findings. B1 remains owned by foundations PR4: record its actual decision and behavior there before final audit, rather than asking again or assuming approval here.

## 6. Style, redundancy, and feature-completeness disposition

- `backend/build.gradle.kts:12–15` selects Palantir Java formatting. Java sources use four-space block indentation; numerous tests keep wildcard static imports. That is not exact [Google Java style](https://google.github.io/styleguide/javaguide.html), which specifies two-space blocks, a 100-column limit, and no wildcard imports.
- `android/build.gradle.kts:10–14` selects Kotlinlang ktfmt with 120 columns. The [Android Kotlin guide](https://developer.android.com/kotlin/style-guide) specifies 100 columns and braces for multiline conditional expressions; existing code contains multiline braceless expressions. Spotless passing proves conformance to the current formatter, not those full guides.
- D1 retains the existing formatters and package layout for this freeze. Their passing checks do not claim exact Google/Android style. A future formatter/import/build-script/JavaScript style change is a separately approved mechanical slice, not unfinished freeze work.
- No evidence was found of a second current scheduler or a second production Markdown parser. Mirrored wire types, intentionally separate original/current anchors, shipped migrations, and test-only fixtures are not by themselves duplicated implementations.
- The five legacy read endpoints and search union/post-deduplication were concrete parallel paths; steps 8 and 5 removed them. Preserve those replacements rather than reopening the old paths.
- Explicitly deferred scope remains deferred unless approved: fixture demo, two-week real-use gates, editable card questions, notebook/history paging, and offline fetching of never-opened images. Do not silently label the entire product complete because unit tests pass. See [feature-list.md](feature-list.md) for authoritative current status.
- Keep behavioral tests. Remove old-implementation-only tests only after transferring surviving assertions. The refused-reading test needs a deliberate behavior change, not deletion to obtain green.

## 7. Verification performed during this audit

### Documented build/test checks

Executed from the repository root, exit **0** after granting Gradle access to its existing cache/local test services:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

Terminal output:

```text
BUILD SUCCESSFUL in 48s
46 actionable tasks: 28 executed, 18 up-to-date
```

JUnit XML aggregate, read after that run:

```text
backend {'tests': 274, 'failures': 0, 'errors': 0, 'skipped': 3}
android JVM {'tests': 71, 'failures': 0, 'errors': 0, 'skipped': 1}
```

That is **271 backend tests passed** and **70 Android JVM tests passed**. Skipped: `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus`, and `StudyCorpus`. Device tests **compiled only**.

Executed from `android/reader-web`, both exit **0**:

```sh
npm run check
npm run build -- /tmp/reporead-audit-2026-10-18/assets
```

Build output:

```text
> build
> npm run check && node build.mjs /tmp/reporead-audit-2026-10-18/assets

> check
> node --check reader.js && node --check diagrams.js && node --check build.mjs && node --check check-restore.mjs
```

### Temporary reproductions

Executed twice successfully during the audit, with the final combined command exiting **0**:

```sh
python3 /tmp/reporead-audit-2026-10-18/run_probes.py
```

Output excerpts:

```text
SYNTHETIC exact DAO query: 60 matching notes; SQL returned 50 rows; Kotlin grouping leaves 25 distinct notes, not 50
SYNTHETIC renderer input: source=14039 bytes blocks=1006 HTML=3192792 bytes; over 2MiB row=true; over 8MiB client=false
SYNTHETIC renderer input: source=36039 bytes blocks=3006 HTML=9544792 bytes; over 2MiB row=true; over 8MiB client=true
SYNTHETIC production image client: different displayed note versions send: [/api/documents/1/image?path=picture.png, /api/documents/1/image?path=picture.png]
All synthetic audit probes reproduced; no application data or repository source changed.
```

These local probes depend on existing compiled classes/dependency caches and are temporary evidence, not portable regression tests. The output-size probe measured renderer output; it did not provoke a Room cursor failure. The image probe exercised the real client against a synthetic server, not real GitHub image contents.

Initial sandboxed Gradle/loopback attempts were denied; the standalone image probe also initially lacked the Android `org.json` classpath. Those were execution-environment/setup failures, not application-test failures. The successful commands above used the required permissions and verified platform jar.

### Not verified

At the original audit's completion, no physical-device run, live OAuth/GitHub run, hosted deployment, restore exercise, dependency/advisory scan, full historical canonical-compatibility corpus, removal race reproduction, credential-lifecycle race reproduction, or runtime database-query timeout experiment had been performed. Later device verification is recorded in sections 9 and 10. No real account/session, production data, infrastructure, dependencies, migrations, or existing user notes were changed.

## 8. Completion rule for the cleanup

A slice is complete only when its owner and invariant are explicit, its failure is reproduced or characterized, its regression passes through real wiring, every affected caller is updated, the replaced implementation and old-only tests/docs are removed, and exact verification commands/results are recorded. Fewer files, more patterns, or a green pre-existing suite alone do not satisfy that rule.

### Final-candidate gates

**Current state: not executed for a final candidate; PR4 and remaining remediation are unfinished.** D1–D7 are settled. The records in sections 9–16 and Step 9 verification remain historical slice evidence. They cannot be added together to manufacture a current full-system pass. Record one final result per gate here, referencing the owning artifacts/reports rather than duplicating every historical transcript.

| Gate | Required evidence and pass condition |
| --- | --- |
| F1 — candidate identity and scope | Exact committed source revision, PR4 merge revision and tracked acceptance/policy record, generated spec identity, resolved dependency/lockfile identity, app variant/version/signing identity and APK/test-APK/JAR hashes. No unexplained dirty production/generated files or reliance on an unpublished/untracked companion plan for acceptance. Closure items are verified fixes or explicitly accepted limits, not unanswered exclusions. |
| F2 — combined automated checks | Run backend format/tests and Android format/Lint/JVM tests, packaged reader assets and debug/test APK builds against this candidate. Record compiler/typecheck success, terminal output, exit codes, test totals and every skip. Generated spec/refusal-registry checks run with the backend suite; stale generated output means fix/commit and a new candidate, not an artifact edited around the gate. No format-apply command in a verification-only pass. |
| F3 — full supported-device suite | Install the matching Dev/test APKs preserving data and run the whole suite exclusively on the supported phone. Success is terminal `OK (n tests)`, with failures/skips accounted for—not adb exit 0 or device-test compilation. Exercise PR4's actual entry owners plus the step 4/6/7 regressions, admission/concurrency, real configured timeouts, cancellation/partial failure, retirement, restoration and second attempts. Isolated stores/loopback fixtures never become claims of live GitHub coverage. |
| F4 — canonical/study/anchoring corpus | Record source and private corpus revisions, eligible/excluded counts, seeds, exact commands and private report locations. Compare unchanged canonical blocks against the supported baseline and shipped edge-case fixtures; zero unexplained differences or wrong attachments. Explicitly account for D4's demonstrated legacy cases rather than declaring them compatible from source SHA alone. Run RenderedCorpus/StudyCorpus and resolve current anchoring measurement coverage with AnchorMeasurement; ordinary suite skips are not this gate. Run MoveMeasurement if move logic or its canonical inputs changed. Review ambiguous/failure samples without committing private notes. |
| F5 — privacy/security boundaries | C8 regression/failure-path evidence and C9's current dependency assessment/dispositions. Review actual auth/session/PKCE/callback routing, repository ownership, WebView/resource isolation, backup/export exclusions and release debug/network policy against existing tests/config. No claim of exhaustive security proof, and no advisory downgrade/upgrade or waived finding without its own approval. |
| F6 — real Dev end-to-end | Approved Dev → real backend → real GitHub check: browse/refresh, content and snapshot-pinned embedded image, navigation/reading-position resume, a changed-version summary, offline read and same-id pending replay, and Practice/Review/Notebook outcomes affected by remediation. Use a specifically approved scratch-only write scope; enumerate pre-existing pending work before any operation that may send it. No revival of the deleted unsafe live-write harness. Capture observable outcomes and request/log counts, not screenshots alone. Destructive failure/interruption cases stay isolated unless separately approved. |
| F7 — upgrade and recovery | Actual last-shipped Room schema → candidate tests preserve user data/pending work and implement only D2's approved cache retirement. Verify repeat/interrupted startup and all affected saved-page consumers. For backend schema changes, verify expand-only/previous-release compatibility and rehearse restoring a current-schema backup into an isolated empty database, including the documented token-key/sign-in consequence. If no backend schema/recovery-path change occurred, name the applicable prior restore/schema evidence and its scope instead of claiming a fresh exercise. No restore against live data. |
| F8 — release/hosted pairing, mandatory under D3 | Build/identify the signed release APK and backend JAR; verify signing certificate, backend origin, release manifest/non-debug policy and absence of the debug test host. With specific execution approval, install/update preserving data and deploy/identify the matching hosted backend, record served revision/health and perform release sign-in/App Link, library/content/image/cache smoke checks. Distinguish an existing-session deploy-survival check from a fresh OAuth check. The release APK and deployed backend must implement the matching contract; Dev success alone cannot close this gate. A missing approval/access leaves it unverified, not waived. |
| F9 — final review and freeze sign-off | Inspect the complete final diff, coupled callers/shared-state/ordering and replaced-code absence, not just green tests. Reconcile ADR/architecture/invariant/module/feature docs with actual ownership and accepted limits; keep product gates distinct. List each remaining test-only/live limitation from feature-list.md with its disposition and actual evidence; no blanket carry-forward of “not verified.” The user confirms the final scope/accepted risks and freeze only after F1–F8 pass. |

All measurements must name what they bound: response size is not heap/work, a statement/socket timeout is not a total request deadline, `NonCancellable` is not crash durability, and mocked authorization is not a second real user's GitHub access. For real-OS termination, hardware/Keystore failure and other non-deterministic cases, state the observed or simulated scope explicitly. Do not manufacture destructive live faults simply to turn an exclusion green; a necessary new acceptance decision reopens the gate before freeze.

### Commands and evidence format

Commands below compose existing README/plan verification tasks; run them only during the approved final audit, not as evidence of this documentation reconciliation. Confirm any additional diagnostic command/flag with `--help` before using it. Signing credentials, private corpus paths, phone serial and live action scope must be supplied/verified, never guessed.

```sh
# From repository root, with the documented PostgreSQL/Testcontainers and Android prerequisites available.
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --no-daemon

# Supported phone, exclusive automation access; preserve the installed Dev app's data.
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner

# Release artifacts; signing setup is owned by android/README.md. Deployment/install needs separate approval.
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:bootJar :app:assembleRelease --no-daemon
```

Corpus commands and environment variables are owned by [backend Verify](../backend/README.md#verify) and [Android Real-corpus check](../android/README.md#real-corpus-check). Reader restore commands are owned by [reader-web's device check](../android/reader-web/README.md#device-check-reading-position-restore). Release signing/install and hosted health/revision/deploy/backup procedures remain in their module READMEs; do not copy credentials or create a second operational runbook here. Dependency assessment uses the actually selected method from step 11, not an invented scanner task.

For each gate, record **candidate/source and artifact identity; environment; exact command/action; process exit; pasted terminal outcome; test counts/skips; result; evidence scope; remaining accepted limits**. A transport interruption is not a captured terminal success. Record user-reported results as such. Any code fix gets its own reproduction/regression evidence and a new candidate; rerun F2/F3 and every other gate affected by the change. Reuse unaffected evidence only with an explicit source/artifact comparison and reason. Docs-only evidence amendments identify the unchanged executable candidate and do not imply its APK was rebuilt or installed.

### Verification of the 2026-10-19 plan reconciliation

This revision changes documentation only and records the user's D1–D7 responses. Production code, generated models, dependencies, schemas, app data, phone settings and hosting are unchanged by this work. No Gradle/typecheck/device/corpus/advisory/live/release/deployment checks are claimed. Documentation checks and the exact reconciliation commit, if made, are recorded with the final handoff; the user's ongoing PR4 changes and untracked companion plan are not committed incidentally.

Documentation checks, each exit **0**:

```sh
python3 /tmp/reporead-plan-check-2026-10-19.py
git diff --check -- docs/audit-remediation-plan.md
```

```text
PASS: 12 local Markdown links/anchors; fences/newlines/whitespace.
PASS: A1-A12/C1-C10 closure rows; D1-D7 decided; F1-F9 gates; retired instructions absent.
PASS: original audit and implementation verification transcripts unchanged from HEAD.
NOT RUN: 3 external links, product tests/typecheck, device/corpus/advisory/live/release checks.
```

The checker is a temporary documentation probe outside the repository, not shipped regression coverage. Its initial predicate falsely treated “pending work” in a decision's impact column as an unanswered decision; checking the disposition column corrected that probe. No product test or evidence assertion was weakened. `git diff --check` produced no output.

## 9. Step 1 verification — 2026-10-18

### Red regression, then green

```sh
./gradlew :backend:test --tests '*DocumentContentTest.smallSourcesWhoseRenderedPagesExpandPastStorageLimitsAreUnsupported' --no-daemon
```

Before the fix, exit **1**:

```text
java.lang.AssertionError: Status expected:<422> but was:<200>
1 test completed, 1 failed
BUILD FAILED in 9s
```

After the fix, this test and the added complete-page/HTML exact-limit tests pass in the full suite below.

### Final checks

From the repository root, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

```text
BUILD SUCCESSFUL in 27s
46 actionable tasks: 3 executed, 43 up-to-date
backend {'tests': 278, 'failures': 0, 'errors': 0, 'skipped': 3}
android JVM {'tests': 71, 'failures': 0, 'errors': 0, 'skipped': 1}
```

That is **275 backend tests passed**. The Android JVM task was initially up-to-date, so it was also explicitly rerun, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --rerun --no-daemon
```

```text
BUILD SUCCESSFUL in 5s
28 actionable tasks: 1 executed, 27 up-to-date
```

**70 Android JVM tests passed**. The four opt-in corpus/measurement tests listed in section 7 remain skipped; no personal-corpus run is claimed. The SDK XML-version warning remains an environment/tooling warning, not a failed compile.

### Pixel verification

Built the debug/test APKs, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

The final combined build (also running backend tests and both format checks) ended with `BUILD SUCCESSFUL in 35s`, `90 actionable tasks: 13 executed, 77 up-to-date`. The reader bundle/check ran successfully during the initial APK build.

Installed with the documented `adb -s <Pixel-serial> install -r ...app-debug.apk` and `install -r -t ...app-debug-androidTest.apk`; both reported `Success`, exit **0**. The serial below is parameterized to avoid recording the phone's wireless-debugging identifier. Only the separate Dev app/test APKs were installed; the release app and its database were untouched.

```sh
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> shell am instrument -w -e class com.reporead.android.data.LocalSyncTest,com.reporead.android.data.LocalStoreTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Both commands exited **0**, with terminal success signals (not exit codes alone):

```text
Time: 9.755
OK (46 tests)

Time: 58.315
OK (80 tests)
```

The temporary in-memory measurement class was replaced by the permanent Save all size regression, not retained alongside it. Its observed output was:

```text
SDK=34 completeStrings=1048576 readable=true
SDK=34 completeStrings=2093056 readable=true
SDK=34 completeStrings=2097152 readable=false
SDK=34 completeStrings=3192792 readable=false
SDK=34 completeStrings=9544792 readable=false
```

Each unreadable row raised `SQLiteBlobTooBigException: Row too big to fit into CursorWindow`. This is one measured Android runtime, not an assertion about every OEM's cursor implementation.

### Replacement/compatibility check

A temporary probe compared the pre-fix renderer from Git with the new renderer on 18 supported synthetic fixture/path combinations (Unicode, nested lists, tables, tasks, raw HTML, links, embeds, footnotes, Mermaid and BOM). Command, exit **0**:

```sh
python3 /tmp/reporead-audit-2026-10-18/render-compatibility/run.py
```

```text
PASS: 18 supported renders have byte-identical HTML and unchanged canonical blocks/headings.
```

The old renderer copy exists only in that temporary comparison directory, not in production or the committed test suite. No format bump is claimed or needed for a new representation: the supported fixtures retain their representation. This check does not substitute for step 4's historical compatibility work.

### Explicit exclusions

- No deployed backend, live GitHub/OAuth session, release app, personal notes, schema, dependency or infrastructure was changed.
- No existing cached page was rewritten or deleted. Previously stored oversized rows and general page-format upgrade policy remain for `Sync`'s saved-page work in step 4.
- The cap prevents newly delivered unusable pages; it is not a 1 MiB bound on the entire parser/DOM heap or a fix for other findings.
- The temporary measurement harness initially used an unsupported Room `.use` lifecycle; it was corrected to the existing tests' explicit close convention before the successful run, then removed. The temporary compatibility launcher initially used a mismatched package path; that setup was corrected before its successful run. Neither failure was hidden as an application regression.


## 10. Step 2 verification — 2026-10-18

### Root cause and deletion review

`DocumentController` captures the document/checkpoint and `Attachments` image paths in one short read-only `REPEATABLE READ` transaction; GitHub I/O happens afterwards. Rendering returns a DOM and canonical facts. `Attachments.bind` resolves names once; zero/many candidates are normal visible-unavailable outcomes, not exceptions. One bounded serializer runs after binding. The existing `repository_images` table now serves that one capture-time index; it is not a fetch-time resolver or another snapshot history.

The required image contract is `path` + `commitSha`. Android validates the returned snapshot SHA at its existing JSON error boundary before writing Room. The one `NoteImages.image` function uses document/`commit-<sha>`/hashed-path files, so even an identical SHA string cannot alias the retired bare-blob namespace. Null legacy identity makes no disk lookup or HTTP call. Cache-hit reads share deletion's existing lock; an unreadable file is unavailable with a logged cause, not a trigger for another fetch strategy. Existing late-fetch publication protection remains.

Searches of production and tests confirmed all affected callers: `DocumentController`, `NoteVersions` consumers (`AnnotationService`, `RepositorySync`, changes and reading tests), renderer tests and `RenderedCorpus`; `Sync`, DAO projections, shared `ReaderPage` resources (reader, preview, answers and figures), `Screen.Figure` creation/restoration, and all `SavedVersion` constructors. There is no old rendered-HTML accessor, embed-image method, alternate image selector, fetch-time name resolver or old-cache read path. Negative tests of the retired request shape remain because refusal is a current behavior, not an old implementation.

The production Java/Kotlin diff adds a net **122 lines**. This is a correctness change, not a net-deletion mechanical refactor: the saved immutable identity, atomic capture, boundary validation, migration wiring and safe cached-read failure path were previously absent. The named requirement is preventing cross-snapshot images without guessing. No additional tracking subsystem was introduced. Broader package moves and style changes remain separate steps.

**External-call ceiling:** content: one exact note-blob request; uncached image: one image-file request; cached or unknown-identity image: zero. At most 64 image references per page; each operation can separately renew credentials once. Save all is sequential and at most one content request per outdated note, bounded by the existing 5,000-document server limit. No failure starts an alternative strategy.

### Red, then green

The backend regression `DocumentContentTest.repositoryImageIsServedAtTheRequestedSnapshotWithItsType` first failed (Gradle exit **1**): it requested an older `b…b` snapshot but the GitHub mock received the latest `c…c` ref. The corresponding real Android image-client regression first failed on device because requests omitted the snapshot; its expected bytes were null. Both now pass through the existing HTTP/Room/file boundaries. Loopback and mock GitHub responses are labelled test fixtures, not production stand-ins.

The content exact-limit fixture initially omitted the new 40-byte snapshot from its budget; it was corrected to count both SHAs without changing the limit or weakening assertions. The first full device run reported `Tests run: 87, Failures: 1`: the new figure test inferred a non-void JUnit method. Its signature was corrected to explicit `Unit`; the final full run below passed unchanged behavioral assertions.

### Final checks

From the repository root, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessApply :app:spotlessApply :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 31s
90 actionable tasks: 13 executed, 77 up-to-date
backend {'tests': 282, 'failures': 0, 'errors': 0, 'skipped': 3}
android JVM {'tests': 73, 'failures': 0, 'errors': 0, 'skipped': 1}
```

After updating the opt-in corpus writer to bind image paths from its Git tree, the backend was checked again, exit **0**:

```sh
./gradlew :backend:spotlessApply :backend:spotlessCheck :backend:test --no-daemon
```

```text
BUILD SUCCESSFUL in 26s
8 actionable tasks: 4 executed, 4 up-to-date
```

The aggregate counts are from the generated JUnit XML: **279 backend and 72 Android JVM tests passed**. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` are opt-in and skipped. Compilation/type checking and current configured Spotless checks passed; this is not a claim of exact Google style conformance.

On the approved Pixel 8a, Android 14/API 34, each command exited **0**:

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r android/build/outputs/apk/debug/app-debug.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Performing Streamed Install
Success
Performing Streamed Install
Success
Time: 59.168
OK (87 tests)
```

The instrumentation terminal success matters: `am instrument` also returned process exit 0 for the earlier failed run. Tests use scratch directories, in-memory/exported-schema databases and the existing isolated test activity, not the release app's data.

The exported v13/v14 schemas were compared: only nullable `notes.snapshotCommitSha` was added; no existing field, table or index was removed. `ImageSnapshotMigrationTest` executes the actual upgrade and verifies saved HTML/search text/links plus pending reading, bookmark and annotation data survive. The legacy image cache uses a disjoint namespace and is retired by the existing sweep; it is not migrated by guessing an identity.

Temporary compatibility probe, exit **0**:

```sh
python3 /tmp/reporead-audit-2026-10-18/render-compatibility/run.py
```

```text
PASS: 18 renders retain canonical blocks/headings; 16 retain identical HTML; 2 differ only by the approved resolved-image URI and image attribute order.
```

This uses a pre-fix renderer only in `/tmp`, not a retained repository implementation. The launcher initially failed on package-private access across Java source-launcher class loaders; ordinary compilation fixed that setup. Its first comparison then identified image attribute order as well as the intentional URI change; both differences are now explicitly checked rather than claiming byte identity. This small synthetic comparison is not the full historical/private corpus.

### Explicit exclusions and next gate

- No backend deployment, live GitHub/OAuth request, release APK install, real saved-note rewrite, dependency or infrastructure change was performed. The approved schema upgrade and old-image retirement run when this app version opens the store/sweeps; saved text is preserved.
- The image contract is deliberately replaced, not backward compatible. Deploying requires the matching app/backend; no compatibility endpoint remains.
- The full private corpus and browser visual measurement were not run. No JavaScript or CSS changed.
- Account-removal admission/ownership, historical canonical compatibility, reading refusals, search duplication and answer-confirmation policy remain open plan items. Correct snapshot identity does not solve those separately owned defects.
- Step 3 needs two explicit policies before implementation: what happens locally after an interrupted account-deletion call whose server result is unknown, and what to do with nonempty legacy local data whose account owner cannot be proven. No policy is inferred from a missing credential or a 401.
- Unrelated `.gitignore` and untracked `notes/` edits are excluded from the cleanup commits.


## 11. Step 5 verification — 2026-10-18

### Red, then green

`LocalStoreTest.searchListsEachMatchingNoteOnceUnderItsCurrentName` lists 60 notes matching by title and saved text, plus a note whose saved copy predates its rename and move. Against the old query it failed on the Pixel 8a (Android 14/API 34):

```text
java.lang.AssertionError: expected:<50> but was:<26>
Tests run: 1,  Failures: 1
```

It now passes, together with the existing search test, whose removed-note fixture now has the `documents` row that production always has, plus a removed note without a saved copy that must not match.

### Final checks

From the repository root, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 17s
android JVM {'tests': 73, 'failures': 0, 'errors': 0, 'skipped': 1}
```

Installed the Dev app and test APKs with `adb install -r` / `install -r -t` (both `Success`) and ran the full device suite with `am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner`:

```text
OK (88 tests)
```

No backend, schema, route or dependency changed; the backend suite was not rerun. Search's UI wording and highlight search are unchanged.


## 12. Step 10 verification — 2026-10-18

### Red, then green

With a syntax error appended to `android/reader-web/reader.js` (`function (`), the commands the pull-request job ran exited **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

```text
BUILD SUCCESSFUL in 4s
```

The added CI step on the same tree exited **1**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:mergeDebugAssets --no-daemon
```

```text
> Task :app:bundleReader FAILED
reader.js:547
SyntaxError: Function statements require a function name
BUILD FAILED in 3s
```

Replacing `Library.java`'s `MAX_RESPONSE_BYTES = 8 * 1024 * 1024;` with the equivalent valid Java `Math.multiplyExact(8, 1 << 20)` made `:app:testDebugUnitTest` fail at configuration (``Library.java has no line `MAX_RESPONSE_BYTES = <digits and *>;` ``, exit **1**): a backend-only edit breaks every Android build, so that file now triggers the Android workflow. Both probes were reverted; `git status` showed no change to either file.

### Final checks

From the repository root, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:mergeDebugAssets :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
> Task :app:bundleReader
BUILD SUCCESSFUL in 16s
82 actionable tasks: 54 executed, 28 up-to-date
```

The workflow parses as YAML, with identical pull-request and push path lists that include both backend files. On the Pixel 8a (Android 14/API 34), after `adb install -r` / `install -r -t` (`Success`), the full `am instrument` run printed `OK (88 tests)`.

### Not verified

- A backend-only change triggering the workflow on GitHub. This pull request changes the workflow file itself, so it runs regardless. Its hosted `test` job did run the new step: `> Task :app:bundleReader`, the four `node --check` calls, `BUILD SUCCESSFUL in 9s` (run 39104986891).
- The two dialogs' new sentence was compiled and not viewed on the phone.
- The reader's behaviour in a WebView still runs only in the device tests on the phone; CI checks syntax and bundling only.


## 13. Step 8 verification — 2026-10-18

### Callers

Before the change, `git grep` found no Android production caller of `GET /api/repositories`, `/api/repositories/{id}/documents`, `/api/reading-states`, `/api/bookmarks` or `/api/notebook`; only backend handlers and tests in `RepositorySyncTest`, `ReadingStateTest`, `BookmarkTest`, `AnnotationTest`, `AvailableRepositoriesTest`, `DataControlTest` and `ApiErrorsTest`. `Documents.list` and `DocumentController.connections` were called only by the deleted list handler, and `ReviewController.notebook` was the only caller of `Reviews.notebook` other than `Library.read`. After the change, the same search over `backend` and `android` code returns nothing.

### Moved assertions

- `RepositorySyncTest`: a `listed` helper reads the library and asserts that its one repository is the test's connection; checkpoint, path, title, blob, removal and content-change assertions moved under `$.repositories[0]`. Another user's library lists no repositories; the deleted route's 404 for an unknown id has no successor.
- `ReadingStateTest` and `BookmarkTest`: the library's `readingStates` / `bookmarks` keys are unchanged; their unauthenticated checks now use the write routes. The library's own 401 remains in `LibraryTest`.
- `AnnotationTest`: notebook assertions moved under `$.notebook`. `AvailableRepositoriesTest` and `DataControlTest`: repository lists come from the library.
- `ApiErrorsTest`: the trailing-slash and wrong-method fixtures use `/api/library/` and `PUT /api/library`, still 404 and 405.

Production Java: 7 files, 13 insertions and 80 deletions.

### Checks

From the repository root, exit **0**:

```sh
./gradlew :backend:spotlessApply :backend:spotlessCheck :backend:test --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck :app:mergeDebugAssets :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 27s
backend {'tests': 282, 'failures': 0, 'errors': 0, 'skipped': 3}
BUILD SUCCESSFUL in 24s
```

That is the same 279 passing backend tests as before the change. On the Pixel 8a (Android 14/API 34), the full `am instrument` run printed `OK (88 tests)`; the app code is unchanged.

### Not verified

- No backend was deployed. Once it is, any release older than `/api/library` and the snapshot-pinned images is unsupported.
- The phone's own release was not checked against a local backend built from this branch; device tests use labelled loopback fixtures.


## 14. Original PR #66 verification — 2026-10-18

Historical evidence at `539da8e`. The review found gaps despite these passing tests; sections 15–16 replace its incomplete lifetime design and verification exclusions.

### Plan check before building

The drafted owners, explicit states, screen gate and the two decided policies were kept. Two parts were corrected against the code: the admission gate plus drain for local writes (replaced by the DAO rule above, which deletes the failure instead of coordinating around it), and account deletion's pending removal being recorded before the server call (contradicting decision 3; `cancelRemoval` is deleted).

### Red, then green

`LocalSyncTest.aLocalChangeArrivingAfterAClearWritesNothing`, run first against the old code with its signatures, on the Pixel 8a (Android 14/API 34):

```text
java.lang.AssertionError: expected null, but was:<ReadingRow(documentId=1, … pending=true)>
java.lang.AssertionError: A late highlight must not crash: java.lang.IllegalStateException: Cannot create an annotation without its displayed saved note; documentId=1
```

A late reading position repopulated the cleared store as pending work, and a late highlight crashed. Both now write nothing (false/null). The interruption and ownership policies are new behaviour; their tests assert it rather than reproduce an old failure. The old orchestration ran in composables, which no test could drive.

### Tests through the real wiring

`AccountTest` (11 tests) builds `Account` over a test-only `SessionStore` preferences file, the production `Api`, `Sync` and an in-memory Room, a scratch folder and a loopback server:

- sign-out closes screens at once and removes everything when revocation fails (503) or the session is unreadable (`SESSION_UNREADABLE`, no request sent);
- a second sign-out, a deletion or a sign-in code while one runs sends nothing;
- reading and bookmark saves while sign-out waits are cleared, and later ones write nothing;
- a refused (503), 401 or unreachable deletion keeps notes, unsent work, images and the owner (a 401 also ends the session); a confirmed one removes everything;
- a deletion cut short before its answer keeps everything at the next start; a sign-out cut short is finished at the next start;
- files that will not delete stop the removal, sign-in waits, and **Retry** finishes it;
- sign-in with no recorded owner removes the old data before saving the new session and owner; with an owner, a refused sign-in keeps the data and reports it until dismissed.

The first full run of the new class failed 3 tests on one wrong fixture count (`expected:<1> but was:<2>`): the kept phone holds the unsent highlight and the pending reading position. The helper now asserts both explicitly; no behaviour assertion was weakened.

### Final checks

From the repository root, exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 10s
android JVM {'tests': 73, 'failures': 0, 'errors': 0, 'skipped': 1}
```

On the Pixel, after `adb install -r` / `install -r -t`: `AccountTest` alone printed `OK (11 tests)` four times in a row, and the full `am instrument` run printed `OK (100 tests)`.

Production Kotlin: +325 / −176 lines, net +149, of which `Account.kt` is 183 (44 comment lines). The named requirement is one application-lifetime owner with explicit states that tests can drive over isolated stores; it replaces the composable closures, `RepoReadApplication.removeCopy`/`removal`, the Library deletion coroutine, `SessionStore.cancelRemoval` and `removalPendingChanges`, and the code exchange's `REMOVAL_PENDING` refusal.

**External calls:** sign-out 1 (revoke), account deletion 1, sign-in 1 (code exchange); none retried, and a failure starts no other strategy.

### Not verified

- The root screen's gate, its texts and a rotation during an operation were not exercised on screen; the state they render is app-owned and tested, the composable mapping was read only.
- No test produces a database failure while clearing.
- A phone with a live session and no recorded owner (upgraded without signing in again) keeps its data and is untouched until its next sign-in, which then removes the data first. Whether the user's phone is in that state is unknown.
- A late change from a stale screen of an account that has since signed out and signed back in as the same account can still land in that account's new store; it cannot reach another account.
- No backend change; the backend suite was not rerun.


## 15. PR #66 follow-up — callback validation and notice ownership

The audit reproduced two failures in permanent `AccountTest` regressions before the fixes: an unstarted callback deleted the legacy page (`expected:<private text> but was:<null>`), and the account-deletion failure notice disappeared on Activity recreation (`Tests run: 13, Failures: 2`). These tests use the production Account/SessionStore/Api/Sync/Room and real LibraryScreen in the existing isolated activity; no app-owned session or database is touched.

`Account` now validates and consumes the pending verifier before authorizing any legacy cleanup, then passes it to the single code-exchange function. The old later validation/consumption is deleted. `LibraryScreen` renders the account-owned notice directly, separately from refresh status, until explicit **Dismiss account message**. The screen-local notice copy and automatic dismissal effect are deleted. Every caller of `completeSignIn`, `LibraryScreen` and the dismissal callback was searched and updated.

Verification, all commands from the PR branch worktree, process exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r android/build/outputs/apk/debug/app-debug.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 shell am instrument -w -e class com.reporead.android.AccountTest com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
BUILD SUCCESSFUL in 14s
82 actionable tasks: 18 executed, 64 up-to-date
Time: 2.902
OK (13 tests)
```

The strengthened notice regression also checks refresh cannot dismiss the account notice and that an explicit dismissal does. Broader lifetime, export and background-entry fixes are the next approved slice, not claimed solved here. No backend, dependency, schema or infrastructure change; no live OAuth or release app access.


## 16. PR #66 follow-up — one local-copy lifetime

### Approval, root cause and ownership

The user approved the complete replacement rather than a containment patch that left same-account late writes unresolved. The permanent regressions first reproduced both remaining audited state failures (`Tests run: 15, Failures: 2`): background startup sent `PUT /api/documents/1/reading-state` despite persisted removal, and an old save overwrote new progress 10 with 90 after an actual same-account sign-in and library reload.

`Account` owns the current `Sync` instance. Deciding local removal retires it permanently; old screens keep that retired instance. Its existing lock serializes local mutations, network answers and atomic export publication, and `clearRetiredCopy` waits for an already admitted operation. Later calls cancel with `CancellationException`, not a network fallback or a new identity check against the next store. Only a successful clear forgets pending removal/ownership and publishes a fresh instance. Stopped removals keep the same retired instance for Retry. The image owner is retired under its existing file lock too, so an old WebView cannot read the next copy's files or fetch again; the existing deletion generation still protects in-flight HTTP publication.

The listed-note DAO predicates remain solely for disconnection inside an active copy, now explicitly regression-tested; they are not parallel lifetime authority. No schema, epoch, screen-job registry, second lock, dependency or manager was added. The simple `freshCopy` construction reuses the existing immutable dependencies; it is not a configurable factory or alternate implementation. Local saves can now wait behind an admitted network operation on the existing lock: this is the deliberate simplicity/ordering trade-off, not a claim of unchanged latency.

Exports have one writer in Sync. `NotebookScreen` still owns Markdown formatting and Android sharing, but the old private IO writer is deleted. A real admitted exporter is held before its file write, removal is requested, and only releasing it permits cleanup to finish; its real file is gone afterwards. Both a queued exporter and a later exporter on that old copy cancel without formatting/writing. This is new executable ordering coverage; the original review had only code-derived evidence for the old export race.

`Account.startUp` owns one startup job shared by Activity and worker. Worker entry joins it before considering pending work. The old Application startup flag, direct worker-to-Sync entry, app-lifetime `app.sync` reference and single-use Sync account-deletion transport wrapper are removed. Account performs its one deletion request directly. The real root receives its existing objects explicitly from MainActivity, rather than casting application context; it is exercised with those same types over isolated stores in TestHostActivity. Navigation starts afresh when the Sync lifetime changes without adding another generation counter.

All changed callers were searched before replacement: `completeSignIn`, `LibraryScreen`, `RepoReadApp`, `NotebookScreen`, background entry, clear/startup, local saves, image fetches, and all direct screen DAO mutations. Change-summary acknowledgement and dismissal of refused grades also go through the lifetime boundary, rather than letting old screen work mutate a fresh store.

### Permanent behavior coverage

- The unsolicited-callback and persistent-notice tests from section 15 remain; the notice survives recreation and refresh until explicit dismissal.
- Same-account sign-out/sign-in/library reload leaves the newer reading save intact; the suspended old operation cancels. This replaces the original PR's documented exception, not merely its wording.
- Persisted removal at the actual background function entry performs cleanup and sends zero ordinary requests.
- Real root removal text/gate survives Activity recreation; saved preferences/Room/files are cleared before returning to sign-in.
- A real SQLite `PRAGMA query_only = ON` write refusal returns `LocalCleanup.DatabaseFailed`, keeps ownership/pending removal, blocks sign-in, and succeeds on Retry once writes are restored. No test assertion is mocked away.
- The old image owner cannot read a fresh owner's cached bytes or request an uncached path. Existing late-fetch and sweep regressions remain.
- Existing local-save assertions were moved from the superseded “false/null after clear” behavior to cancellation on the retired instance; disconnection in an active instance still returns false/null. Existing remote-result ordering and retry assertions remain.

### Verification and exclusions

The final commands and terminal results are recorded below. An intermediate targeted run was invalidated by concurrent UI automation: Android reported `UiAutomationService already registered`, then its instrumentation process crashed while disconnecting that service. Logcat confirmed repeated external UI-automation launchers. The user paused the other automation; no test or production code was patched around this environment conflict.

After exclusive access, a complete run printed:

```text
Time: 69.241
OK (108 tests)
```

No live OAuth/GitHub, release APK, deployed backend, schema migration, dependency or infrastructure change was involved. The tests use labelled loopback fixtures, isolated preferences, scratch files, in-memory/exported-schema Room and the existing debug-only host. No actual OS process-kill/full-disk experiment was performed; database refusal is real SQLite but deliberately induced via query-only mode. Exact Google-style changes, unrelated remaining plan steps and Add repository's server-only API ownership are out of this local-copy fix. User `.gitignore` and `notes/` changes remain untouched in the main worktree.


The lifetime slice adds **194** and removes **159** production Kotlin lines (net **+35**). This is not a net-deletion mechanical refactor: the named requirements are permanent local-copy retirement, one worker/activity startup boundary, serialized export publication and executable root-screen verification. It deletes the replaced entry points and exporter rather than retaining wrappers. Package moves and unrelated cleanup were not mixed in.


The coupled caller review found that retiring a final reading save could skip `WebView.destroy()`. `ReaderSession.release` now destroys in `finally`, including cancellation. Its real ReaderLifecycle regression first failed with `TimeoutCancellationException: Timed out waiting for 5000 ms` (`Tests run: 1, Failures: 1`): the released view retained its public renderer-process handle. The unchanged regression checks that destruction releases that handle and no reading row is recreated. This is resource cleanup on the replaced cancellation path, not an alternative save strategy.


### Final executed checks for the lifetime replacement

From the `fix/removal-one-owner` worktree, process exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 11s
82 actionable tasks: 13 executed, 69 up-to-date
Android JVM {'tests': 73, 'failures': 0, 'errors': 0, 'skipped': 1}
```

The JVM count is the generated JUnit XML aggregate: **72 passed**, the opt-in `StudyCorpus` skipped. This verifies the configured formatter, not an exact Google-style migration. No backend code changed and the backend suite was not rerun for these fixes.

On the approved Pixel 8a, Android 14/API 34, installs and both instrumentation commands exited **0**:

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r android/build/outputs/apk/debug/app-debug.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 shell am instrument -w -e class com.reporead.android.reader.ReaderLifecycleTest#releasingARetiredReaderStillDestroysItsWebView com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
"$HOME/Library/Android/sdk/platform-tools/adb" -s 10.203.4.241:39513 shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Performing Streamed Install
Success
Performing Streamed Install
Success
Time: 1.24
OK (1 test)
Time: 68.983
OK (109 tests)
```

The terminal test outcome, not `am instrument`'s exit 0, establishes success. Two prior full exclusive runs passed `OK (108 tests)` before the added WebView-retirement regression. That regression failed first and was not weakened; release now always destroys the actual WebView, verified using its public renderer-process handle. `git diff --check` also passed. The old cancellation behavior is not retained as another path or test suite.

## Step 9 verification

### Current implementation and ownership

Step 9 and its 2026-10-19 review fixes are implemented, with automated verification complete. The user confirmed the
full device suite passed on `d753714`; device verification is no longer blocked. This section is the sole active
verification record, with live/release exclusions below. The removed API plan and detailed intermediate runs remain
historical Git evidence:
`git show 9b42c17:docs/api-contract-plan.md` and `git show cdbc0df:docs/audit-remediation-plan.md`.

- Backend controllers/records own the published shapes. `OpenApiSpecTest` derives `backend/openapi.json`, checks actual
  Jackson serialization (including flattened/value records), and fails/replaces a stale artifact. Android generates
  models during its build; the spec is committed so Android builds need not start Spring/PostgreSQL. It is marked
  generated in review diffs, not hand-edited. `Contract.kt`, nine producer-agreement suites, `ContractFixtures`, 27
  shape-only examples, the old raw verbs, `ServerJson.kt` and its old-only tests are deleted. Item-refusal examples stay
  because disposition is a client decision, checked against the server's status registry.
- `Api` owns exact success statuses, bounded/framed transport and echoed request correspondence. `WireJson` owns strict
  scalar/time decoding and required-nullable response presence. `WireRules` rejects duplicate identities and broken
  library/notebook references before publication; `ServerRows` maps validated results before the Room transaction.
  Unknown/malformed error answers cannot authorize permanent refusal. SignIn owns local-account correspondence; Sync
  owns a review's local card correspondence and positive refusal pairs. No retry or alternate strategy was added.
- Optional input is not incomplete output. Controller `@Schema` declarations allow omission separately from JSpecify
  nullability; response keys remain required. Reading has distinct request/response anchor records because only input
  may omit the prefix; both Kotlin shapes are generated. HTTP tests preserve missing/explicit-null owner and prefix
  behavior. Metadata declares creation/replay 201/200 and binary `image/*` responses. Mandatory query inputs remain
  server-validated with their existing typed error codes, while the spec now marks them required. No routing/validation
  policy changed.
- `GitHubCredentials` owns one process-wide lifecycle gate before OAuth exchange/storage, renewal, code exchange,
  last-device sign-out and committed deletion/revocation. Admission precedes SQL transactions; queued session changes
  reauthenticate. Last-device session removal atomically invalidates older handoff codes. The gate now uses Spring's
  own callback matcher, including alternate and encoded paths; there is no competing raw-URI check. Existing encrypted
  storage remains the only token store. Renewal is at most **1** GitHub call; sign-out/deletion at most **2**.
- Fixed application settings bound SQL/lock waits at **15 s**, established socket reads at **20 s**, and login at **10 s**.
  These approved safety ceilings are not measured optimal values or a total HTTP deadline. Real PostgreSQL tests prove
  cancellation/rollback and the next transaction, plus socket closure/recovery. The recorded whole-build measurements
  were 15.087 s query, 15.048 s lock and 20.050 s socket. SQL failures propagate as server errors, never item refusals;
  uncertain work stays pending. Programmer/integrity failures are not caught as transient failures.
- Sync returns transient refusal/fetch facts; Library/Folder/Reader own wording. Existing durable rejection text and
  step 4's reading-refusal resend policy are unchanged. Changed source/type ownership and non-obvious boundaries are
  documented. No Room/Flyway migration, dependency upgrade, new environment knob or infrastructure change.

### Review-driven deletion

The **183-line live-write harness and its run instructions are removed**, not guarded by another subsystem: its
recovery method could flush unrelated pending annotations from the real store. This intentionally removes automated
live-write replay. Isolated HTTP/Room/credential/failure tests remain. No hidden replacement script or parallel suite.
Repeated historical build transcripts are consolidated here; the Git references above preserve the full old evidence.
This is a correctness change, not a mechanical refactor: missing snapshot checks and different input/output presence
rules add production lines. No old implementation or test-only agreement suite is retained to pad the coverage.

### Reproductions before fixes — 2026-10-18/19

All commands ran from the current workspace with `DEBUG=false` to avoid inherited verbose framework logging.

```sh
DEBUG=false ./gradlew :backend:test --tests 'com.reporead.auth.GitHubLoginTest.credentialRemovalWaitsForAnAlreadyAdmittedOAuthCallback' --no-daemon
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests '*ContractTest.inconsistentSnapshotIdentitiesAndReferencesCannotAuthorizeReplacement' --no-daemon
DEBUG=false ./gradlew :backend:test --tests '*OpenApiSpecTest.requestOptionalityAndDynamicResponsesMatchTheHttpContract' --no-daemon
```

Each exited **1** on the old code: callback `6 tests completed, 4 failed` (alternate/encoded paths passed removal);
snapshot `1 test completed, 1 failed` (inconsistent identities accepted); metadata `1 test completed, 1 failed`
(optional input wrongly required). The earlier isolated audit also proved callback completion resurrected the removed
credential after both sign-out and account deletion. No live credentials/data were used.

After the fixes, existing suites passed with the stronger assertions: credential/data-control checks
`BUILD SUCCESSFUL in 14s`; snapshot/JVM/device-test compile `BUILD SUCCESSFUL in 14s`; schema, actual HTTP input/image/
annotation behavior, regenerated model/JVM/device-test compile `BUILD SUCCESSFUL in 28s`; final spec check
`BUILD SUCCESSFUL in 12s`, all exit **0**. The stale-spec check intentionally rewrote the artifact and failed once before
its green rerun. Implementation checks also caught a Content annotation name collision and a flattened-accessor lookup;
both were corrected without relaxing assertions. No generic schema fallback or alternate parsing path was introduced.

### Final checks — 2026-10-19

Code head: `1e0aaa6`. From the current workspace, the full command exited **0**:

```sh
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:mergeDebugAssets :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

```text
BUILD SUCCESSFUL in 1m 47s
99 actionable tasks: 33 executed, 66 up-to-date
backend {'tests': 300, 'failures': 0, 'errors': 0, 'skipped': 3}
android JVM {'tests': 83, 'failures': 0, 'errors': 0, 'skipped': 1}
```

Lint: **0 errors, 0 warnings, 4 dependency-version hints**; no versions were upgraded. The query-metadata regression
also failed first (same `OpenApiSpecTest` method, exit 1), and the artifact checker rewrote/failed once as designed.
The final full suite above checks that metadata and actual route failure behavior, not only record serialization.
After the build-file ownership comment, `DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew
:backend:spotlessCheck :app:spotlessCheck --no-daemon` exited **0**, `BUILD SUCCESSFUL in 6s`,
`4 actionable tasks: 4 up-to-date`. No executable configuration changed.

The approved Pixel 8a (Android 14/API 34) completed the first follow-up run with `Time: 130.769` / `OK (129 tests)`.
The assistant subsequently lost ADB result streams (exit **255**) and once could not start a run (exit **1**, device
not found). Those transport outcomes did not establish that on-device instrumentation stopped or failed; the earlier
report overstated that conclusion. Detailed connection history remains in `d753714`.

**Final device evidence, reported by the user:** the full suite passed on **`d753714`** in **128.7 s**:

```text
OK (129 tests)
```

All 129 tests ran, with no failures or skips, including `AccountTest` (20), `ApiTest` (7), `LocalSyncTest` (34), and all
migration and reader tests. The count is 129, not 132: `671aec8` removed the three previously skipped live tests.
The user confirmed RepoRead Dev updated from build 347 to 350, built from `d753714`, preserving its data; the test APK
was byte-identical to the one already installed, and both APKs remained installed after the run. The assistant did not
independently capture this final terminal result, process exit code or APK comparison; these are explicitly attributed
to the user's confirmation, not the interrupted ADB logs. No further rerun is needed for this documentation update.
The documented device commands preserve data:

```sh
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

### Historical live evidence and exclusions

The approved scratch-only live sequence passed before generation: real SessionStore/Api/Sync/Room and GitHub reads,
highlight/card create/edit/conflict/reattach/confirm/grade/delete, bookmark on/off, unchanged diff, cached offline read
and pending same-id replay, with test-owned cleanup. The pre-existing backend was not restarted or deployed. Detailed
commands, APK/source comparisons and exclusions remain in the Git records above; those results do not establish safety
for arbitrary future runs of the deleted harness.

The prior generated-model device run at `c161625` printed `Time: 138.637` / `OK (132 tests)` (129 executed, 3 live opt-ins
skipped). It is historical, not the final run for this follow-up. No new live writes, live sign-out/deletion/disconnect,
GitHub push, release APK, hosted deployment or post-deploy smoke check is claimed. Live images, reading-position UI,
changed-version diff, reconnect and repeated whole-repository Save all were not verified by this contract work.

Backend tests use isolated PostgreSQL 17.11 and labelled GitHub HTTP fakes; device tests use isolated stores and loopback
HTTP, not the user's session/database. Existing reading-refusal debt (step 4), navigation/lifecycle foundations and npm
advisories are out of scope. IDE serialization imports compile; the editor's own reload/diagnostic state was not
verified. The user's untracked Android foundations plan remains untouched by this follow-up. No push or deployment.
