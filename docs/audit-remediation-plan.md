# Codebase audit and remediation plan

Audited **2026-10-18**. Rebased **2026-10-19** after Android foundations PR4 (#73) merged and was deployed. Steps 1, 2, 3, 5, 8, 9 and 10 and the foundations work are closed; their evidence is in the merged pull requests (#62–#66, #69–#73) and in this file's history (`git show 35af494:docs/audit-remediation-plan.md` is the last full record). This document owns only what remains: the open findings, the five pull requests that close them, the decisions they rest on, the final check, and the complexity reductions that follow.

[Invariants](invariants.md), [ADRs](adrs.md), [architecture](architecture.md) and [feature status](feature-list.md) keep their owners; this plan links to them and does not restate them. ADR-27 and ADR-28 own the Android foundations; the uncommitted `docs/android-foundations-plan.md` is superseded by them and is deleted, not committed.

## 1. What is still open

Line numbers are on `main` at `35af494`.

| ID | Finding | Root cause | Closes in |
| --- | --- | --- | --- |
| A6 | A reading position the server refuses stays pending: `Sync.kt:481–487` keeps non-404 refusals, `LibraryDao.kt:201,212,311` count and resend them, and `LocalSyncTest.kt:187` asserts it. Highlights (`AnnotationState.REFUSED`) and grades (`review_log.rejection`) already have a refusal mark; reading rows do not. | `reading_states` has no representation for "kept locally, refused remotely, do not resend unchanged". | PR 2 |
| A4 | Saved pages are judged usable by `renderFormat >= RENDER_FORMAT` (`Sync.kt:254`), but the heading cap (ADR-18), BOM and URL-only-footnote changes shipped without bumping `MarkdownRenderer.FORMAT` (`3`). Pages saved before the 1 MiB complete-page cap can exceed the 2 MiB row Room can read on the Pixel 8a. | Presentation changes were not recorded in the one format number the gate already checks. | PR 2 |
| A7 | `Practice.kt:126–136` identifies a question by document, prompt and answer text, so editing a prompt creates a second card. `gradeNewQuestion` (`Practice.kt:166`) validates outside Sync, then `Sync.gradeNewCard` (`Sync.kt:542`) writes the card and its grade in two Room transactions; ADR-28 records that cancellation protection does not make them atomic. | No stable question identity, and no single transaction for "card plus first grade". | PR 3 |
| A8 | The bank's Canonical line is the confirmed answer, but Practice/Review wording and the linked note's saved section do not say which is which. | Product boundary decided (decision 5) but not stated in the UI. | PR 3 |
| A9 | Edit and delete live in the reader's ViewModel (`Reader.kt:356–404`); the Notebook offers only **Open passage** (`Notebook.kt:218`). A card on a removed or unsupported note cannot be opened, so it cannot be deleted. | User-owned records are treated as controls on a rendered page. | PR 4 |
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

## 3. The five remaining pull requests

Every PR: one step, red test first, replaced code and its tests deleted in the same PR, every caller updated, device suite run, one docs commit (ADR amendment, feature-list status, invariants) and no new dependency, manager, wrapper or configuration knob. A refactor commit removes more production lines than it adds or says why not.

### PR 1 — Rebase the plan and close C8

- This document, as above.
- `Answer.kt`: drop `heading=` from the log line; the on-screen message keeps it. No new test: the line's absence is the fix.
- Delete the uncommitted `docs/android-foundations-plan.md`.

### PR 2 — Reading refusal and saved-page retirement (A4, A6, D2, D4)

**Owners:** `MarkdownRenderer.FORMAT` for what a page format means; `Sync` for fetch decisions; `LibraryDao`/`LocalStore` for the rows and the migration.

Backend first, then app; the current app accepts a higher format, so the deploy order is backend, then app.

- `MarkdownRenderer.FORMAT` becomes **4**. The bump is presentation-only: `MarkdownRendererTest` asserts canonical blocks and headings of the existing fixtures are unchanged. Every page saved before the heading-cap, BOM and footnote changes is now outdated at the gate that already exists, so no per-case fixture set or compatibility registry is needed. ADR-18 is amended: the debt it recorded is paid.
- `RENDER_FORMAT` becomes **4**. Behaviour at the gate is unchanged and is characterized, not rewritten: an outdated current-version page is fetched once online, shown without a warning offline, and an oversized page the server now refuses keeps showing the saved copy with its reason.
- Room **v15**, one migration:
  - `reading_states.rejection TEXT NULL`. A refused save is `pending = 0, rejection = <code>`. `pendingReading()` and both waiting counts select `pending` only; `clearAcknowledgedReading` deletes `not pending and rejection is null`; the library merge treats a refused local row like a pending one, so an older server value cannot erase a newer refused local position. A newer local save sets `pending = 1, rejection = null`. 404 keeps today's acknowledge-and-drop.
  - Delete `notes` rows whose `html` plus `searchText` length reaches **2,097,152** characters, the bound measured on the Pixel 8a. Their image files go through the existing startup sweep. Readable rows, and everything user-owned, survive.
- `Sync.sendPending` records the rejection instead of leaving the row pending. The `RefusedChange` report is unchanged.
- Tests: migration from the exported v14 schema with an oversized row, a readable row, pending and acknowledged reading rows, bookmarks and annotations; a refused position is not resent on the second sync, is not counted waiting, survives a library replacement and a restart, and is replaced by a newer local save; `LocalSyncTest.kt:187`'s resend assertion is replaced, keeping the assertion that unrelated pending work proceeds; Save all's second run fetches only what is still outdated.
- Docs: ADR-18 amended, feature-list's reading-refusal and legacy-page gaps removed, `invariants.md` "Bounded memory on the phone" names the retirement.
- Out of scope: any renderer-evolution subsystem, per-page fixtures, canonical-rule changes, image-identity changes.

### PR 3 — One question identity and one card-plus-grade transaction (A7, A8, D5)

**Owners:** `Sync.gradeNewCard` for validation, matching and the write; `LibraryDao` for the transaction; `ReviewSchedule` unchanged.

- `scheduledQuestions` matches a question to a card by document and Canonical line text only; the prompt is dropped from the key. Two prompts over one Canonical line in one bank are one question; one prompt over two lines stays two.
- Validation moves into `Sync.gradeNewCard` under `committed`: bank unchanged since asked, no existing reviewable card for that line, grade in range. `Practice.gradeNewQuestion`'s `withContext(NonCancellable)` and its "step 6 consolidates" comment are deleted; the screen only words the result.
- One `@Transaction` DAO function writes the card and its first grade. Failure before commit leaves neither row; a second attempt after restoration finds the card and writes nothing.
- Review shows the bank's current prompt for a card whose Canonical line matches a current bank question, from the bank pages the DAO already reads, and the card's saved prompt otherwise. If this needs more than a lookup over those pages, stop and report.
- Wording: the grade confirms the Canonical line; the linked section is labelled as the saved copy. The existing "no section"/"could not be shown" messages stay and are not described as confirmed. Check Review as well as Practice.
- Tests: JVM: prompt edit keeps the card and the question is not asked again; Canonical-line edit is a new question. Device, through the real Practice entry and DAO: card and grade appear together; a forced failure of the grade insert leaves no card; concurrent double tap writes one pair; Review labels a stale linked section.
- Docs: ADR-13 and ADR-20 amended (identity and confirmation boundary; D5 limitation named); feature-list's "renaming its heading makes it a new question" gap removed.
- Out of scope: authored question ids, fuzzy matching, cross-device deduplication, question editing.

### PR 4 — Delete a card or highlight from the Notebook (A9)

**Owners:** `NotebookViewModel` for admission and outcome; `Sync.deleteAnnotation` for the mutation, unchanged.

- `NotebookViewModel.delete(row)` with the same one-operation admission its export uses; the Notebook row gains **Delete**. `DeleteDialog` moves from `reader/Notes.kt` to `ui/Components.kt`, its one definition for its two callers.
- No content request is made. Online, acknowledged deletes behave as in the reader; offline, the existing pending-deletion state applies. A card on a note with no saved page deletes, and the dialog's history-loss confirmation is the one the reader already shows.
- Edit from the Notebook is not added: there is no demonstrated case.
- Tests: device, through the real Notebook entry and a loopback server: an annotation on a note without saved HTML is deleted with zero content requests; a second tap starts nothing; rotation during the delete keeps the outcome; a refusal is reported and the row stays.
- Docs: architecture §4 Notebook row; ADR-13 amended.

### PR 5 — Close the audit (C5, C9, C10) and run the final check

- **C9:** `npm audit` in `android/reader-web` as its README documents; enable GitHub Dependabot alerts on the repository (a setting, not a dependency; needs your go-ahead). Record Spring Boot, AGP, Compose BOM and Navigation 3 versions with the result in feature-list; each applicable finding gets a fix or an accepted reason, as the KaTeX advisory already has.
- **C5:** read the hosted backend's `Library read; userId=… bytes=…` log line for the real account; record bytes and counts beside the 8 MiB ceiling in feature-list with the revisit trigger. No new code.
- **C10:** one sentence under "Source traceability" in `invariants.md` stating the authorized-repository scope.
- **Sweep:** grep production, tests and docs for the names retired by PRs 2–4 and by PR4 (`RefreshOnEntry`, `rememberLoad`, `StackSaver`, legacy format wording); fix ADR-06's measurement chronology against feature-list.
- **Final check**, one row each in feature-list with command, exit and terminal result:
  1. CI green on `main` for the last code PR (backend format and tests; Android format, Lint, JVM tests, reader bundle, both assemblies).
  2. One full device suite on the Pixel 8a Dev app: terminal `OK (n tests)`.
  3. Dev app against the Mac backend and real GitHub: open a note with an embedded image, a changed-version summary, one Practice grade, one Review, an offline open, a Notebook delete.
  4. Release build installed over the phone's app, and the hosted backend's revision and health read as the backend README documents. Deploy and install need your go-ahead at the time.
  Room migration coverage is PR 2's and PR 3's migration tests. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` are not rerun: no PR changes `Anchoring`, `ContentMoves` or the canonical rules.
- **Then delete this file.** Decisions live in the ADRs, status in feature-list, the record in git. Feature-list's pasted build output (Save all audit, typed HTTP boundary, Navigation 3, screen work sections) is reduced to one status line each naming the PR.

## 4. Guardrails for PRs 2–5

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
| Oldest changes not yet acknowledged | `ChangeBaselineRow.sinceBlobSha` |
| Latest repository snapshot | `RepositoryRow.lastSyncedCommitSha` |
| Saved image identity | `NoteRow.snapshotCommitSha`; null is legacy unknown, not permission to fetch current images |

### Patterns

- Mutually exclusive states as sealed types or enums; no state-machine framework. A busy flag or `NonCancellable` is not a transaction; Room and PostgreSQL transactions own multi-row correctness.
- Loads and outcomes belong to the entry's ViewModel, accepted mutations to `Sync.committed`, account operations to `Account` (ADR-28). Compose observes and emits actions; only the resumed UI consumes a result.
- One coordinator in `Sync`; no single-implementation managers, repositories, use cases, event buses, retry frameworks or wrappers around the DAO.
- Server-owned limits; a failed call never starts another strategy; call ceilings stated per operation.
- Keep: backend feature packages (acyclic), `Annotations`/`AnnotationService`/`Anchoring` separation, one `LibraryDao`, `ReaderSession`/`ReaderPage`/`ReaderWebView` separation, pure `ReviewSchedule`, WorkManager's two jobs, immutable mutation ids, original anchors, the Room migration history.

## 5. Reducing the app's complexity

Everything here comes after PR 5. The rule: a feature or layer is deleted when two weeks of real use show it unused or when a simpler mechanism gives the same result; it goes with its rows, routes, tests and docs in one PR. Files move only in PRs that move files.

### Mechanical moves (D1 lifted; one PR each, no behaviour change)

1. `core/network/Load.kt` is Compose UI; move to `ui/`. Three importers: `Reader.kt`, `Changes.kt`, `Available.kt`.
2. Practice, Review, `ReviewSchedule`, `StudySession`, `reader/Study.kt` and `reader/Answer.kt` into a `study/` package. `library/` then means library browsing again.
3. `interleaveByNote`, `practiceTopics` and `inPracticeFolder` leave `Practice.kt` for the file that owns their concern (`StudySession.kt`; `Folder.kt`).

### Feature deletions to decide by use

| Candidate | What goes | What you lose | How to decide |
| --- | --- | --- | --- |
| Changes since last read | `reader/Changes.kt` (107), `ChangeBaselineRow`, its DAO queries and migration test, the reader's summary load and presentation-time acknowledgement, `GET /api/documents/{id}/changes`, `NoteChanges.java` (213) and `NoteChangesTest` (181). `ContentMoves` stays: `RepositorySync` uses it for renames. | The banner saying what changed since you last read a note. | Count summaries opened over two weeks from the backend's `DocumentController` log. Fewer than a handful: delete. |
| Notebook export as a file | `Sync`'s export writer and its lock path, `exportDirectory`, `deleteExports` at four lifecycle points, `export_paths.xml` and the FileProvider, `NotebookExportTest`. Replaced by sharing the Markdown as `Intent.EXTRA_TEXT`. | Nothing, if the export fits a share intent. | Measure the real export's size first; the Binder budget is about 500 KB. Larger: keep the file. |
| Full-screen figure | `reader/Figure.kt` (110), `Screen.Figure` and its snapshot fields, the second `ReaderPage` host, `FigureSnapshotTest`. Replaced by pinch zoom in the reader's WebView. | The dedicated full-screen table/diagram view. | Try zoom on the three widest real tables and the largest Mermaid diagram; if readable, delete. |

### Not simplified, and why

- Navigation 3 and the seven entry ViewModels: standard, just landed, and the alternative was the hand-written stack they replaced.
- Three refusal shapes (`AnnotationState`, `review_log.rejection`, `reading_states.rejection` after PR 2): one pending table would be a larger migration than the duplication costs. Revisit if a fourth kind of pending change appears.
- `NoteImages`' own file lock: images load outside the sync lock so a page's images never wait behind a sync.
- Backend auth (about 1,100 lines across `GitHubCredentials`, `GitHubTokens`, `GitHubSecurity`, `AppSessions`, `AppAuthController`): GitHub App user tokens expire, so renewal, encrypted storage and revocation are requirements, not optional layers.

### Documentation

This plan goes with PR 5. `docs/` then holds the ADRs, architecture, invariants, feature status and user stories, each the owner of one kind of fact, with evidence in the merged PRs and git history rather than pasted transcripts.
