# Codebase audit and remediation plan

Audited **2026-10-18**. Rebased **2026-10-19** after Android foundations PR4 (#73) merged and was deployed. Steps 1, 2, 3, 5, 8, 9 and 10 and the foundations work are closed; their evidence is in the merged pull requests (#62–#66, #69–#73) and in this file's history (`git show 35af494:docs/audit-remediation-plan.md` is the last full record). This document owns only what remains: the open findings, the five pull requests that close them (one of which deletes three features), the decisions they rest on, the final check, and the complexity reductions that follow.

[Invariants](invariants.md), [ADRs](adrs.md), [architecture](architecture.md) and [feature status](feature-list.md) keep their owners; this plan links to them and does not restate them. ADR-27 and ADR-28 own the Android foundations; the uncommitted `docs/android-foundations-plan.md` is superseded by them and is deleted, not committed.

## 1. What is still open

Line numbers are on `main` at `35af494`.

| ID | Finding | Root cause | Closes in |
| --- | --- | --- | --- |
| A6 | A reading position the server refuses stays pending: `Sync.kt:481–487` keeps non-404 refusals, `LibraryDao.kt:201,212,311` count and resend them, and `LocalSyncTest.kt:187` asserts it. Highlights (`AnnotationState.REFUSED`) and grades (`review_log.rejection`) already have a refusal mark; reading rows do not. | The server refuses positions it could accept (a clock up to 5 minutes ahead, an anchor over its limits), and the phone has no state for a refusal. | PR 3 |
| A4 | Saved pages are judged usable by `renderFormat >= RENDER_FORMAT` (`Sync.kt:254`), but the heading cap (ADR-18), BOM and URL-only-footnote changes shipped without bumping `MarkdownRenderer.FORMAT` (`3`). Pages saved before the 1 MiB complete-page cap can exceed the 2 MiB row Room can read on the Pixel 8a. | Presentation changes were not recorded in the one format number the gate already checks. | PR 3 |
| A7 | `Practice.kt:126–136` identifies a question by document, prompt and answer text, so editing a prompt creates a second card. `gradeNewQuestion` (`Practice.kt:166`) validates outside Sync, then `Sync.gradeNewCard` (`Sync.kt:542`) writes the card and its grade in two Room transactions; ADR-28 records that cancellation protection does not make them atomic. | No stable question identity, and no single transaction for "card plus first grade". | PR 4 |
| A8 | The bank's Canonical line is the confirmed answer, but Practice/Review wording and the linked note's saved section do not say which is which. | Product boundary decided (decision 5) but not stated in the UI. | PR 4 |
| A9 | Edit and delete live in the reader's ViewModel (`Reader.kt:356–404`); the Notebook offers only **Open passage** (`Notebook.kt:218`). A card on a removed or unsupported note cannot be opened, so it cannot be deleted. | User-owned records are treated as controls on a rendered page. | Deferred: one phone, so the case needs a card on a note that was removed or is unsupported. Built when it first appears: `NotebookViewModel.delete` over `Sync.deleteAnnotation`, `DeleteDialog` moved to `ui/`, no edit. Recorded as a known gap in feature-list. |
| C8 | `Answer.kt:222` logs the linked note's heading, which is note content. | Content in a log line. | PR 1 |
| C9 | No current dependency-advisory assessment exists. | Not done. | PR 5 |
| C5 | `Library.answer` serializes the whole library before checking the 8 MiB ceiling (D6 accepted). | Accepted limit; needs the real measurement. | PR 5 |
| C10 | Annotation provenance is authorized-repository level, not document-history level (D7 accepted). | Accepted limit; needs the wording. | PR 5 |

Closed since the audit baseline, and not reopened here: A1, A2, A3, A5, A10, A11, A12, C1, C3, C4, C6, C7. C2 (baseline creation versus acknowledgement) closed with PR4: a summary's baseline is cleared only when the resumed reader presents it (ADR-28; `AppNavigationTest.aChangeSummaryFetchedWhileCoveredIsAcknowledgedOnlyOnceShown`, `ReaderViewModelTest.signingOutAndPoppingCannotCancelAPresentedAcknowledgementWaitingForTheLock`).

## 2. Decisions already made

Do not re-ask these. Each is recorded in, or is moved into, the owning ADR by the PR that implements it.

| Decision | Disposition |
| --- | --- |
| Old app support (2026-10-18) | Dropped. Backend and app must match; no compatibility endpoints. |
| Account deletion interrupted (2026-10-18) | Keep local data unless the server confirmed. A 401 is not confirmation. Implemented (#66). |
| Unowned local data (2026-10-18) | Removed before a session is issued over it, after validating the callback. Implemented (#66). |
| Question identity (decision 5, 2026-10-18; D5, 2026-10-19) | Editing a prompt is not a new question; the card follows its Canonical line and shows the bank's current prompt. Only the Canonical line is confirmed; the linked section is supplementary. Uniqueness is local to this phone's cards; cross-device duplicates may remain separate. |
| D1 — moves and formatters | Correctness only before the freeze; current packages and formatters stay. The moves are listed in section 5 as the first work after the freeze, not retired. |
| D2 — unusable legacy page cache | Retire only rows demonstrated unreadable (2 MiB on the Pixel 8a). Annotations, grades, positions, bookmarks and pending work survive. |
| D3 — freeze versus deployment | Release app and hosted backend are checked before the freeze. Deploy and install need their own go-ahead. |
| D4 — canonical compatibility | Canonical block/text rules are frozen; annotations and re-anchoring stay. Presentation upgrades must not change them. No renderer-migration system. |
| D6 — whole-library allocation | 8 MiB wire ceiling, unpaged. Measure the real library; revisit when it approaches the ceiling. |
| D7 — provenance | Authorized-repository level. No history walk, no new GitHub calls. |
| B1 — refresh on return | Not approved (ADR-28). Library and the folder root still reload on return. |
| Deleted features (2026-10-19) | Changes since last read, the full-screen figure and the Notebook file export go. Each note is final to its author, who does not mind rereading; the figure view and the export were never used. PR 2. |
| Reading positions (2026-10-19) | The server accepts every well-formed position instead of refusing one it could store. PR 3. |

## 3. The five remaining pull requests

Every PR: one step, red test first, replaced code and its tests deleted in the same PR, every caller updated, device suite run, one docs commit (ADR amendment, feature-list status, invariants) and no new dependency, manager, wrapper or configuration knob. A refactor commit removes more production lines than it adds or says why not.

### PR 1 — Rebase the plan and close C8

- This document, as above.
- `Answer.kt`: drop `heading=` from the log line; the on-screen message keeps it. No new test: the line's absence is the fix.
- Delete the uncommitted `docs/android-foundations-plan.md`.

### PR 2 — Delete three features

Deletions only, before any fix: the reader's ViewModel and `Sync` get smaller before PRs 3 and 4 change them. Net-negative by construction; no behaviour is replaced.

- **Changes since last read.** Android: `reader/Changes.kt`, the reader's summary load, presentation-time acknowledgement and baseline/reattachment saved state in `ReaderViewModel`, `ChangeBaselineRow` with its DAO queries and the `Sync` calls that create and clear baselines, `BaselineMigrationTest`, the two acknowledgement tests named in section 1, and the `changes` wire models. Backend: `GET /api/documents/{id}/changes`, `NoteChanges.java`, `NoteChangesTest`, the response records in `DocumentController`. `ContentMoves` stays: `RepositorySync` uses it for renames. The `change_baselines` table is dropped in Room **v15** (the same migration PR 3 extends); the backend has no table for it.
- **Full-screen figure.** `reader/Figure.kt`, `Screen.Figure` with its snapshot fields, `FigureSnapshotTest`, `reader.js`'s Full screen link in `addFigureTools` and its figure-only page function, the `/full-screen` interception and `onFullScreen` parameter in `ReaderPage`, and the `onFigure` parameters threaded through `ReaderSession`, `Answer.kt` and `Reader.kt`. Tables and diagrams scroll sideways in the page as they do today.
- **Notebook export.** `Sync`'s export writer and lock path, `exportDirectory`, `deleteExports` at its four call sites, `res/xml/export_paths.xml` and the FileProvider manifest entry, `NotebookViewModel.export`/`claimExport` and the Export button, `notebookMarkdown`, `NotebookExportTest`. ADR-22's "a shared export cannot be revoked" consequence goes with it.
- Every caller grepped before and after; `openapi.json` regenerated by `OpenApiSpecTest`; the generated wire models follow.
- Tests: the existing suites minus the deleted ones; the v14 → v15 migration test asserts user-owned rows survive the dropped table. Nothing new to assert beyond absence.
- Docs: ADR-09 marked retired with the date and reason; ADR-13 (export) and ADR-22 amended; feature-list P1/P4 rows and architecture §4/§5 updated; `invariants.md`'s ownership table loses change baselines.

### PR 3 — Reading positions accepted, saved pages retired (A4, A6, D2, D4)

**Owners:** `ReadingController` for what a position must look like; `MarkdownRenderer.FORMAT` for what a page format means; `Sync` for fetch decisions; `LibraryDao`/`LocalStore` for the rows and the migration.

Backend first, then app; the current app accepts a higher format and any 200, so the deploy order is backend, then app.

- **The server stops refusing positions it can store.** `lastReadAt` is clamped into [1970, server now] instead of refused; the anchor-shape limits in `ReadingController` (`MAX_HEADINGS`, `MAX_PREFIX_CHARS`, heading length, block index) go, since the request-body limit already bounds the request and the anchor is only ever matched on the phone that wrote it. The remaining refusals are 404 for a note that is not the user's, already handled, and 400 for a malformed body, which is a programmer error on the phone and stays loud. The `pending`-only reading row then needs no refusal state: `Sync.sendPending`'s non-404 branch, its `RefusedChange.Kind.READING` and `LocalSyncTest.kt:187`'s resend assertion are deleted, and the "refused reading position stays pending" known gap closes without a column. If a refusal the server cannot accept is found while doing this, stop and report before adding state.
- `MarkdownRenderer.FORMAT` becomes **4**. The bump is presentation-only: `MarkdownRendererTest` asserts canonical blocks and headings of the existing fixtures are unchanged. Every page saved before the heading-cap, BOM and footnote changes is now outdated at the gate that already exists, so no per-case fixture set or compatibility registry is needed. ADR-18 is amended: the debt it recorded is paid.
- `RENDER_FORMAT` becomes **4**. Behaviour at the gate is unchanged and is characterized, not rewritten: an outdated current-version page is fetched once online, shown without a warning offline, and an oversized page the server now refuses keeps showing the saved copy with its reason.
- Room **v15** (shared with PR 2's dropped table): delete `notes` rows whose `html` plus `searchText` length reaches **2,097,152** characters, the bound measured on the Pixel 8a. Their image files go through the existing startup sweep. Readable rows, and everything user-owned, survive.
- Tests: backend: a position 10 minutes ahead is stored at server now; an anchor over the old limits is stored as sent. Android: migration from the exported v14 schema with an oversized row, a readable row, pending and acknowledged reading rows, bookmarks and annotations; a position the old server refused is sent once and acknowledged; unrelated pending work proceeds; Save all's second run fetches only what is still outdated.
- Docs: ADR-18 amended, feature-list's reading-refusal and legacy-page gaps removed, `invariants.md` "Bounded memory on the phone" names the retirement, ADR-08's pending-change list loses reading refusals.
- Out of scope: any renderer-evolution subsystem, per-page fixtures, canonical-rule changes, image-identity changes.

### PR 4 — One question identity and one card-plus-grade transaction (A7, A8, D5)

**Owners:** `Sync.gradeNewCard` for validation, matching and the write; `LibraryDao` for the transaction; `ReviewSchedule` unchanged.

- `scheduledQuestions` matches a question to a card by document and Canonical line text only; the prompt is dropped from the key. Two prompts over one Canonical line in one bank are one question; one prompt over two lines stays two.
- Validation moves into `Sync.gradeNewCard` under `committed`: bank unchanged since asked, no existing reviewable card for that line, grade in range. `Practice.gradeNewQuestion`'s `withContext(NonCancellable)` and its "step 6 consolidates" comment are deleted; the screen only words the result.
- One `@Transaction` DAO function writes the card and its first grade. Failure before commit leaves neither row; a second attempt after restoration finds the card and writes nothing.
- Review shows the bank's current prompt for a card whose Canonical line matches a current bank question, from the bank pages the DAO already reads, and the card's saved prompt otherwise. If this needs more than a lookup over those pages, stop and report.
- Wording: the grade confirms the Canonical line; the linked section is labelled as the saved copy. The existing "no section"/"could not be shown" messages stay and are not described as confirmed. Check Review as well as Practice.
- Tests: JVM: prompt edit keeps the card and the question is not asked again; Canonical-line edit is a new question. Device, through the real Practice entry and DAO: card and grade appear together; a forced failure of the grade insert leaves no card; concurrent double tap writes one pair; Review labels a stale linked section.
- Docs: ADR-13 and ADR-20 amended (identity and confirmation boundary; D5 limitation named); feature-list's "renaming its heading makes it a new question" gap removed.
- Out of scope: authored question ids, fuzzy matching, cross-device deduplication, question editing.

### PR 5 — Close the audit (C5, C9, C10) and run the final check

- **C9:** `npm audit` in `android/reader-web` as its README documents; enable GitHub Dependabot alerts on the repository (a setting, not a dependency; needs your go-ahead). Record Spring Boot, AGP, Compose BOM and Navigation 3 versions with the result in feature-list; each applicable finding gets a fix or an accepted reason, as the KaTeX advisory already has.
- **C5:** read the hosted backend's `Library read; userId=… bytes=…` log line for the real account; record bytes and counts beside the 8 MiB ceiling in feature-list with the revisit trigger. No new code.
- **C10:** one sentence under "Source traceability" in `invariants.md` stating the authorized-repository scope.
- **Sweep:** grep production, tests and docs for the names retired by PRs 2–4 (`Changes`, `baseline`, `Figure`, `export`, `INVALID_READING_STATE`, `RefusedChange.Kind.READING`) and by PR4 (`RefreshOnEntry`, `rememberLoad`, `StackSaver`, legacy format wording); fix ADR-06's measurement chronology against feature-list.
- **Final check**, one row each in feature-list with command, exit and terminal result:
  1. CI green on `main` for the last code PR (backend format and tests; Android format, Lint, JVM tests, reader bundle, both assemblies).
  2. One full device suite on the Pixel 8a Dev app: terminal `OK (n tests)`.
  3. Dev app against the Mac backend and real GitHub: open a note with an embedded image and a wide table, one Practice grade, one Review, an offline open, a reading position saved with the phone's clock set ahead.
  4. Release build installed over the phone's app, and the hosted backend's revision and health read as the backend README documents. Deploy and install need your go-ahead at the time.
  Room migration coverage is PR 2/3's v15 migration test and PR 4's. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` are not rerun: no PR changes `Anchoring`, `ContentMoves` or the canonical rules.
- **Then delete this file.** Decisions live in the ADRs, status in feature-list, the record in git. Feature-list's pasted build output (Save all audit, typed HTTP boundary, Navigation 3, screen work sections) is reduced to one status line each naming the PR.

## 4. Guardrails for PRs 3–5

### Facts that must remain distinct

| Fact | Source of truth |
| --- | --- |
| Latest known source/path/removal | `DocumentRow` |
| Saved rendered page and its format | `NoteRow` / `SavedVersion` |
| Version actually displayed | The note owned by a `ReaderSession` |
| Original selected passage | Annotation's original source/offset/text fields |
| Last trusted passage location | Annotation's location |
| Version the location was resolved against | `resolvedBlobSha` |
| Version whose answer was confirmed | `checkedBlobSha` |
| Last version actually read | `ReadingRow.lastReadBlobSha` |
| Latest repository snapshot | `RepositoryRow.lastSyncedCommitSha` |
| Saved image identity | `NoteRow.snapshotCommitSha`; null is legacy unknown, not permission to fetch current images |

### Patterns

- Mutually exclusive states as sealed types or enums; no state-machine framework. A busy flag or `NonCancellable` is not a transaction; Room and PostgreSQL transactions own multi-row correctness.
- Loads and outcomes belong to the entry's ViewModel, accepted mutations to `Sync.committed`, account operations to `Account` (ADR-28). Compose observes and emits actions; only the resumed UI consumes a result.
- One coordinator in `Sync`; no single-implementation managers, repositories, use cases, event buses, retry frameworks or wrappers around the DAO.
- Server-owned limits; a failed call never starts another strategy; call ceilings stated per operation.
- Keep: backend feature packages (acyclic), `Annotations`/`AnnotationService`/`Anchoring` separation, one `LibraryDao`, `ReaderSession`/`ReaderPage`/`ReaderWebView` separation, pure `ReviewSchedule`, WorkManager's two jobs, immutable mutation ids, original anchors, the Room migration history.

## 5. Reducing the app's complexity

Everything here comes after PR 5. The rule: a feature or layer is deleted when real use shows it unused or when a simpler mechanism gives the same result; it goes with its rows, routes, tests and docs in one PR. Files move only in PRs that move files. New features are built only where the reading or study loop is actually broken.

### Build: images in Save all

The one functional gap worth a slot. An embedded image is unavailable offline until its note is opened online once (feature-list, known gaps). Save all already fetches each outdated note sequentially; it fetches that note's images through the existing `NoteImages` owner in the same pass, bounded by the existing 64 references per page. No new mechanism, no new table.

### Decision for you: sign-out that keeps the phone's notes

The largest deletion available. `Account`'s Removing/Retry/FilesLeft/DatabaseFailed states, the retired-and-republished `Sync` lifetime, `LocalCleanup`, the removal screen and most of `AccountTest` (762 lines) exist so that sign-out and in-app account deletion wipe the phone. If sign-out only ended the session and account deletion became a backend script run by the server's owner, that machinery goes. The cost: a lost phone keeps saved notes readable behind its lock screen, and ADR-22 and the privacy invariant need amending. Not done until you decide.

### Mechanical moves (D1 lifted; one PR each, no behaviour change)

1. `core/network/Load.kt` is Compose UI; move to `ui/`. Two importers after PR 2: `Reader.kt`, `Available.kt`.
2. Practice, Review, `ReviewSchedule`, `StudySession`, `reader/Study.kt` and `reader/Answer.kt` into a `study/` package. `library/` then means library browsing again.
3. `interleaveByNote`, `practiceTopics` and `inPracticeFolder` leave `Practice.kt` for the file that owns their concern (`StudySession.kt`; `Folder.kt`).

### Next deletion to decide by use

**Bookmarks.** A separate table, backend endpoint, pending flag, send pass, migration and Library tab, so a note can be starred. Reading positions already give "continue where I was". Decide after two weeks of use; if deleted, highlights remain the one annotation model.

### Not simplified, and why

- Navigation 3 and the seven entry ViewModels: standard, just landed, and the alternative was the hand-written stack they replaced.
- Two refusal shapes (`AnnotationState`, `review_log.rejection`): one pending table would be a larger migration than the duplication costs. Revisit if a third kind of pending change appears.
- `NoteImages`' own file lock: images load outside the sync lock so a page's images never wait behind a sync.
- Backend auth (about 1,100 lines across `GitHubCredentials`, `GitHubTokens`, `GitHubSecurity`, `AppSessions`, `AppAuthController`): GitHub App user tokens expire, so renewal, encrypted storage and revocation are requirements, not optional layers.

### Documentation

This plan goes with PR 5. `docs/` then holds the ADRs, architecture, invariants, feature status and user stories, each the owner of one kind of fact, with evidence in the merged PRs and git history rather than pasted transcripts.
