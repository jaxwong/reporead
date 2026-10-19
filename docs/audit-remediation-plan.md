# Codebase audit and remediation plan

Audited **2026-10-18**. The foundations and earlier audit steps are closed; their evidence is in merged PRs #62–#66 and #69–#73 and this file's history (`git show 35af494:docs/audit-remediation-plan.md` is the last full record). Audit PRs 1–4 are merged (#75, #76, #78, #80); the user confirmed PR5 merged and pending deployment on 2026-10-19. PR5's executed checks and remaining known limits are in [feature status](feature-list.md#audit-pr5--closure-check-2026-10-19). **Retain this file after PR5**, as the user explicitly requested on 2026-10-19: Section 5 still owns images in Save all, the mechanical moves and other post-audit work. Grade-write ownership was pulled forward during PR5 review; the mechanical moves are implemented locally below, and the other Section 5 items remain deferred.

[Invariants](invariants.md), [ADRs](adrs.md), [architecture](architecture.md) and [feature status](feature-list.md) keep their owners; this plan links to them and does not restate them. ADR-27 and ADR-28 own the Android foundations; the uncommitted `docs/android-foundations-plan.md` is superseded by them and is deleted, not committed.

## 1. Audit baseline and disposition

Findings and line numbers below describe `main` at `35af494`, not the current implementation. The disposition column records what closed them; A9 remains deferred. PR5 review found A6 also affected grades: this branch clamps reviewedAt and removes ClientClock, retaining CARD_CHANGED refusals and immutable replay checks through the approved additive V15 column.

| ID | Finding at audit baseline | Root cause | Disposition |
| --- | --- | --- | --- |
| A6 | A reading position the server refuses stays pending: `Sync.kt:481–487` keeps non-404 refusals, `LibraryDao.kt:201,212,311` count and resend them, and `LocalSyncTest.kt:187` asserts it. Highlights (`AnnotationState.REFUSED`) and grades (`review_log.rejection`) already have a refusal mark; reading rows do not. | The server refuses positions it could accept (a clock up to 5 minutes ahead, an anchor over its limits), and the phone has no state for a refusal. | Closed in PR3 ([#78](https://github.com/jaxwong/reporead/pull/78)) |
| A4 | Saved pages are judged usable by `renderFormat >= RENDER_FORMAT` (`Sync.kt:254`), but the heading cap (ADR-18), BOM and URL-only-footnote changes shipped without bumping `MarkdownRenderer.FORMAT` (`3`). Pages saved before the 1 MiB complete-page cap can exceed the 2 MiB row Room can read on the Pixel 8a. | Presentation changes were not recorded in the one format number the gate already checks. | Closed in PR3 ([#78](https://github.com/jaxwong/reporead/pull/78)) |
| A7 | `Practice.kt:126–136` identifies a question by document, prompt and answer text, so editing a prompt creates a second card. `gradeNewQuestion` (`Practice.kt:166`) validates outside Sync, then `Sync.gradeNewCard` (`Sync.kt:542`) writes the card and its grade in two Room transactions; ADR-28 records that cancellation protection does not make them atomic. | No stable question identity, and no single transaction for "card plus first grade". | Closed in PR4 ([#80](https://github.com/jaxwong/reporead/pull/80)) |
| A8 | The bank's Canonical line is the confirmed answer, but Practice/Review wording and the linked note's saved section do not say which is which. | Product boundary decided (decision 5) but not stated in the UI. | Closed in PR4 ([#80](https://github.com/jaxwong/reporead/pull/80)) |
| A9 | Edit and delete live in the reader's ViewModel (`Reader.kt:356–404`); the Notebook offers only **Open passage** (`Notebook.kt:218`). A card on a removed or unsupported note cannot be opened, so it cannot be deleted. | User-owned records are treated as controls on a rendered page. | Deferred: one phone, so the case needs a card on a note that was removed or is unsupported. Built when it first appears: `NotebookViewModel.delete` over `Sync.deleteAnnotation`, `DeleteDialog` moved to `ui/`, no edit. Recorded as a known gap in feature-list. |
| C8 | `Answer.kt:222` logs the linked note's heading, which is note content. | Content in a log line. | Closed in [#74](https://github.com/jaxwong/reporead/pull/74) |
| C9 | No current dependency-advisory assessment exists. | Not done. | PR5 assessment/measurement/wording recorded in [feature status](feature-list.md#audit-pr5--closure-check-2026-10-19) |
| C5 | `Library.answer` serializes the whole library before checking the 8 MiB ceiling (D6 accepted). | Accepted limit; needs the real measurement. | PR5 assessment/measurement/wording recorded in [feature status](feature-list.md#audit-pr5--closure-check-2026-10-19) |
| C10 | Annotation provenance is authorized-repository level, not document-history level (D7 accepted). | Accepted limit; needs the wording. | PR5 assessment/measurement/wording recorded in [feature status](feature-list.md#audit-pr5--closure-check-2026-10-19) |

Closed since the audit baseline, and not reopened here: A1, A2, A3, A5, A10, A11, A12, C1, C3, C4, C6, C7. C2 (baseline creation versus acknowledgement) closed with foundations PR4; audit PR1 retires the summary feature and its acknowledgement tests entirely.

## 2. Decisions already made

Do not re-ask these. Each is recorded in, or is moved into, the owning ADR by the PR that implements it.

| Decision | Disposition |
| --- | --- |
| Old app support (2026-10-18) | Dropped. Backend and app must match; no compatibility endpoints. |
| Account deletion interrupted (2026-10-18) | Keep local data unless the server confirmed. A 401 is not confirmation. Implemented (#66). |
| Unowned local data (2026-10-18) | Removed before a session is issued over it, after validating the callback. Implemented (#66). |
| Question identity (decision 5, 2026-10-18; D5, 2026-10-19) | Editing a prompt is not a new question; the card follows its Canonical line and shows the bank's current prompt. Only the Canonical line is confirmed; the linked section is supplementary. Uniqueness is local to this phone's cards; cross-device duplicates may remain separate. |
| D1 — moves and formatters | Correctness only before the freeze; packages and formatters stayed then. Post-PR5 mechanical moves are implemented locally in Section 5; the formatters remain unchanged. |
| D2 — unusable legacy page cache | Retire only rows demonstrated unreadable (2 MiB on the Pixel 8a). Annotations, grades, positions, bookmarks and pending work survive. |
| D3 — freeze versus deployment | Release app and hosted backend are checked before the freeze. Deploy and install need their own go-ahead. |
| D4 — canonical compatibility | Canonical block/text rules are frozen; annotations and re-anchoring stay. Presentation upgrades must not change them. No renderer-migration system. |
| D6 — whole-library allocation | 8 MiB wire ceiling, unpaged. Measure the real library; revisit when it approaches the ceiling. |
| D7 — provenance | Authorized-repository level. No history walk, no new GitHub calls. |
| B1 — refresh on return | Not approved (ADR-28). Library and the folder root still reload on return. |
| Deleted features (2026-10-19) | Changes since last read, the full-screen figure and the Notebook file export go. Each note is final to its author, who does not mind rereading; the figure view and the export were never used. PR 1. |
| Sign-out keeps the phone's notes (2026-10-19) | Sign-out and account deletion end the session and no longer wipe the phone; the retired-copy lifetime, removal states and ownership records go. The phone holds one account's data at a time. PR 2. |
| Reading positions (2026-10-19) | The server accepts every well-formed position instead of refusing one it could store. NUL/unpaired-surrogate anchor strings are malformed input, not sanitized (approved 2026-10-19). PR 3. |

## 3. The five remediation pull requests

#74 rebased this plan and closed C8 (`Answer.kt` no longer logs the heading). Every PR below: one step, red test first, replaced code and its tests deleted in the same PR, every caller updated, device suite run, one docs commit (ADR amendment, feature-list status, invariants) and no new dependency, manager, wrapper or configuration knob. A refactor commit removes more production lines than it adds or says why not.

### PR 1 — Delete three features

**Merged as #75, 2026-10-19:** code `8685dec`; verification is in [feature status](feature-list.md#audit-pr1--feature-retirements-2026-10-19). Deployment was running when PR2 began; release installation is not claimed here. Reattachment and submitted-edit restoration stay; only baseline saved state is removed.

Deletions only, before any fix: the reader's ViewModel and `Sync` get smaller before PRs 2 and 3 change them. Net-negative by construction; no behaviour is replaced.

- **Changes since last read.** Android: `reader/Changes.kt`, the reader's summary load, presentation-time acknowledgement and baseline saved state in `ReaderViewModel`, `ChangeBaselineRow` with its DAO queries and the `Sync` calls that create and clear baselines, `BaselineMigrationTest`, the summary acknowledgement tests, and the `changes` wire models. Backend: `GET /api/documents/{id}/changes`, `NoteChanges.java`, `NoteChangesTest`, the response records in `DocumentController`. `ContentMoves` stays: `RepositorySync` uses it for renames. The `change_baselines` table is dropped in Room **v15** (preserved as shipped; PR3 adds v16); the backend has no table for it.
- **Full-screen figure.** `reader/Figure.kt`, `Screen.Figure` with its snapshot fields, `FigureSnapshotTest`, `reader.js`'s Full screen link in `addFigureTools` and its figure-only page function, the `/full-screen` interception and `onFullScreen` parameter in `ReaderPage`, and the `onFigure` parameters threaded through `ReaderSession`, `Answer.kt` and `Reader.kt`. Tables and diagrams scroll sideways in the page as they do today.
- **Notebook export.** `Sync`'s export writer and lock path, `exportDirectory`, `deleteExports` at its four call sites, `res/xml/export_paths.xml` and the FileProvider manifest entry, `NotebookViewModel.export`/`claimExport` and the Export button, `notebookMarkdown`, `NotebookExportTest`. ADR-22's "a shared export cannot be revoked" consequence goes with it.
- Every caller grepped before and after; `openapi.json` regenerated by `OpenApiSpecTest`; the generated wire models follow.
- Tests: the existing suites minus the deleted ones; the v14 → v15 migration test asserts user-owned rows survive the dropped table. Nothing new to assert beyond absence.
- Docs: ADR-09 marked retired with the date and reason; ADR-13 (export) and ADR-22 amended; feature-list P1/P4 rows and architecture §4/§5 updated; `invariants.md`'s ownership table loses change baselines.

### PR 2 — Sign-out and account deletion keep the phone's notes

**Merged, 2026-10-19:** implementation `534cf99`, with review follow-ups through `518c1e2`; verification is in [feature status](feature-list.md#audit-pr2--sessions-keep-the-phones-notes-2026-10-19). Backend/Android and device gates passed; exact results stay in the PR and git history. Deployment was running when PR3 began; no release installation is claimed here.

**Owners:** `Account` for sign-in, sign-out and account deletion, each at most one server call; `SessionStore` for the session alone; `RepoReadApplication` for the process's one `Sync` and startup image sweep. The retiring account-owner rule includes removing `expectedUserId`, `ACCOUNT_MISMATCH`, and their backend/Android callers and tests, with OpenAPI regenerated.

Saved notes are already readable signed out. This PR makes that the whole story: ending a session never touches Room or the image files.

- **Sign-out** revokes the server session (one call) and clears the stored session. A failed revocation is logged; the session expires on the server within 30 days and the `NotRevoked` notice goes.
- **Account deletion** makes its one call; on confirmation the session ends and the `AccountDeleted` notice stays. `DeletionNotConfirmed` stays: it is about the server, not the phone. Notes, highlights, grades and positions stay on the phone in every outcome.
- **Deleted:** `AccountState.Removing`, `LocalCleanup`, `Account.retryRemoval`, `Account.startUp` (it existed to finish interrupted removals; if the startup image sweep still needs a home, it shrinks to that), `Sync.retire`/`freshCopy`/`clearRetiredCopy` and the retired-instance cancellation in `locked`/`committed`, `NoteImages.retire`, `SessionStore.recordOwner`/`removalPending`/`startRemoval`/`finishRemoval`, `RemovalStatus` and the removing back-stack reset in `RepoReadApp`, the `rememberSerializable(account.sync, …)` key (one `Sync` per process again), `BackgroundSync`'s wait for an account operation, the sign-out dialog's "what signing out deletes" text and unsent count (`Library.kt:313–325`), and "and this phone" in the deletion dialog (`Library.kt:446`).
- **Ownership:** no owner is recorded. Signing in as a different GitHub account replaces the lists at the first sync (ADR-08's one-snapshot rule) and the existing sweep removes saved pages nothing lists. Accepted limit, recorded in feature-list: unsent changes made before switching accounts would be sent under the new account. One user, one account; revisit if a second account is ever used on the phone.
- Tests: `AccountTest` keeps sign-in, sign-out (revoked, refused, unreadable session) and deletion (confirmed, refused, 401, unreachable), each asserting notes, unsent work and images intact. The removal, Retry, interrupted-removal, late-writer-after-clear, same-account-reload, ownerless-sign-in and retirement cases go from `AccountTest`, `CommittedMutationTest`, `LocalSyncTest`, `NoteImagesTest` and `ReaderLifecycleTest`; the WebView-destroy assertion in `releasingARetiredReaderStillDestroysItsWebView` moves to an ordinary release test.
- Docs: ADR-22 amended (data stays across sign-out; the owner clause goes), ADR-11 (dialog wording), architecture §4 root/`auth`/`sync` rows, `invariants.md` privacy section, feature-list's interrupted sign-out/deletion and retired-copy known gaps replaced by the one accepted limit above. ADR-03 is unchanged: the GitHub authorization is still revoked on the last sign-out.
- Actual shape: `Account` has no Sync/local-store dependency; `AccountTest` keeps its real notice-recreation test and assertions for every pending kind rather than targeting the estimated line count. `Sync` loses retirement/clear paths and `RepoReadApp` loses `RemovalStatus`. Production code remains net-negative.

### PR 3 — Reading positions accepted, saved pages retired (A4, A6, D2, D4)

**Merged as [#78](https://github.com/jaxwong/reporead/pull/78), 2026-10-19.** [Feature status](feature-list.md#audit-pr3--reading-positions-and-saved-page-retirement-2026-10-19) records status and the grade-clock/docs gaps found during PR5 review; exact evidence stays in the PR and git history. Room v16 and the malformed-text exception below were approved; backend deployment precedes the app.

**Owners:** `ReadingController` for what a position must look like; `MarkdownRenderer.FORMAT` for what a page format means; `Sync` for fetch decisions; `LibraryDao`/`LocalStore` for the rows and the migration.

Backend first, then app; the current app accepts a higher format and any 200, so the deploy order is backend, then app.

- **The server stops refusing positions it can store.** `lastReadAt` is clamped into [1970, server now] instead of refused; the anchor-shape limits in `ReadingController` (`MAX_HEADINGS`, `MAX_PREFIX_CHARS`, heading length, block index) go, since the request-body limit already bounds the request and the anchor is only ever matched on the phone that wrote it. The remaining refusals are 404 for a note that is not the user's, already handled, and 400 for a malformed body, which is a programmer error on the phone and stays loud. NUL and unpaired-surrogate anchor strings are malformed (400 `INVALID_REQUEST`), not sanitized; this exception was reproduced against PostgreSQL and approved on 2026-10-19. The `pending`-only reading row then needs no refusal state: `Sync.sendPending`'s non-404 branch, its `RefusedChange.Kind.READING` and `LocalSyncTest.kt:187`'s resend assertion are deleted, and the "refused reading position stays pending" known gap closes without a column. If a refusal the server cannot accept is found while doing this, stop and report before adding state.
- `MarkdownRenderer.FORMAT` becomes **4**. The bump is presentation-only: `MarkdownRendererTest` asserts canonical blocks and headings of the existing fixtures are unchanged. Every page saved before the heading-cap, BOM and footnote changes is now outdated at the gate that already exists, so no per-case fixture set or compatibility registry is needed. ADR-18 is amended: the debt it recorded is paid.
- The app derives `BuildConfig.RENDER_FORMAT` from `MarkdownRenderer.FORMAT` (**4**) during its build; the hand-copied `sync.RENDER_FORMAT` and literal alignment tests are deleted. Behaviour at the gate is unchanged and is characterized, not rewritten: an outdated current-version page is fetched once online, shown without a warning offline, and an oversized page the server now refuses keeps showing the saved copy with its reason.
- Room **v16** (approved 2026-10-19: PR1/PR2 already ship v15, so their migration is not edited): delete `notes` rows whose combined `html` and nullable `searchText` UTF-8 size reaches **2,097,152** bytes, the bound measured on the Pixel 8a. Their image files go through the existing startup sweep. Readable rows, and everything user-owned, survive.
- Tests: backend: a position 10 minutes ahead is stored at server now; an anchor over the old limits is stored as sent. Android: migrations from the exported v14 and v15 schemas with an oversized row, a readable row, pending and acknowledged reading rows, bookmarks and annotations; a position the old server refused is sent once and acknowledged; unrelated pending work proceeds; Save all's second run fetches only what is still outdated.
- Docs: ADR-18 amended, feature-list's reading-refusal and legacy-page gaps removed, `invariants.md` "Bounded memory on the phone" names the retirement, ADR-08's pending-change list loses reading refusals.
- Out of scope: any renderer-evolution subsystem, per-page fixtures, canonical-rule changes, image-identity changes.

### PR 4 — One question identity and one card-plus-grade transaction (A7, A8, D5)

**Merged as [#80](https://github.com/jaxwong/reporead/pull/80), 2026-10-19.** [Feature status](feature-list.md#audit-pr4--question-identity-and-atomic-first-grade-2026-10-19) records status and the admission/recognition/documentation corrections pulled forward during PR5 review; exact evidence stays in the PR and git history. Phone identity still uses existing rows (Room v16), with no phone schema or scheduler change.

Production is net **+48 lines** after its review follow-up, not a refactor-only reduction: new validation outcomes, Review's current-prompt lookup and explicit confirmation wording are required behavior. The old writer and caller-side validation/protection are replaced, not kept in parallel; no manager, dependency or configuration is added.

**Owners (corrected during PR5 review):** `LibraryDao` for Practice and Review admission inside their write transactions, and the sole phone-side grade-range check in `saveReview`; `Sync.gradeNewCard` for ordering and cancellation protection; `ReviewSchedule` unchanged.

- `scheduledQuestions` matches a question to a card by document and Canonical line text only; the prompt is dropped from the key. Two prompts over one Canonical line in one bank are one question; one prompt over two lines stays two.
- Admission is inside `LibraryDao.saveCardWithFirstReview` under `Sync.committed`: bank unchanged since asked and no existing reviewable card for that line; `saveReview` alone checks grade range. `Practice.gradeNewQuestion`'s `withContext(NonCancellable)` and its "step 6 consolidates" comment are deleted; the screen only words the result.
- One `@Transaction` DAO function writes the card and its first grade. Failure before commit leaves neither row; a second attempt after restoration finds the card and writes nothing.
- Review shows the bank's current prompt for a card whose Canonical line matches a current bank question, from the bank pages the DAO already reads, and the card's saved prompt otherwise. If this needs more than a lookup over those pages, stop and report.
- Wording: the grade confirms the Canonical line; the linked section is labelled as the saved copy. The existing "no section"/"could not be shown" messages stay and are not described as confirmed. Check Review as well as Practice.
- Tests: JVM: prompt edit keeps the card and the question is not asked again; an unmatched Canonical-line edit is a new question. Device, through the real Practice entry and DAO: card and grade appear together; a forced failure of the grade insert leaves no card; concurrent double tap writes one pair; Review labels a stale linked section.
- Docs: ADR-13 and ADR-20 amended (identity and confirmation boundary; D5 limitation named); feature-list's "renaming its heading makes it a new question" gap removed.
- Out of scope: authored question ids, fuzzy matching, cross-device deduplication, question editing.
- Accepted by the 2026-10-19 branch audit, recorded here and not changed: Review shows the bank's current prompt, but Notebook (`Notebook.kt:122`) and the reader's notes panel (`Notes.kt:170`) still show the prompt saved on the card, so a prompt-edited card reads differently in the two places until the card is recreated; feature-list records this known limit. Review now reads and parses every saved bank page before showing its first card, even when every card is hand-written; Practice already pays that cost and the lookup is the one the plan allowed.

### PR 5 — Close the audit (C5, C9, C10) and run the final check

**Verified locally, 2026-10-19:** [feature status](feature-list.md#audit-pr5--closure-check-2026-10-19) owns concise status and gaps; exact commands, outcomes and live mutations stay in git/PR evidence. The initial approved local restart, Dev/GitHub smoke and release 0.1.390 update remain recorded in history. Review corrections are locally verified only, not pushed or deployed; the clock-rollback Review counter remains a known gap. The plan remains for Section 5, not for another execution of PR5.

- **C9:** `npm audit` in `android/reader-web` as its README documents; check GitHub Dependabot alerts on the repository. The user explicitly declined enabling them on 2026-10-19; leave the setting unchanged and record the coverage limit. Record Spring Boot, AGP, Compose BOM and Navigation 3 versions with the result in feature-list; each applicable finding gets a fix or an accepted reason, as the KaTeX advisory already has.
- **C5:** read the hosted backend's `Library read; userId=… bytes=…` log line for the real account; record bytes and counts beside the 8 MiB ceiling in feature-list with the revisit trigger. No new code.
- **C10:** one sentence under "Source traceability" in `invariants.md` stating the authorized-repository scope.
- **Sweep:** grep production, tests and docs for the names retired by PRs 1–4 (`Changes`, `baseline`, `Figure`, `export`, `LocalCleanup`, `clearRetiredCopy`, `removalPending`, `Removing`, `INVALID_READING_STATE`, `RefusedChange.Kind.READING`) and by PR4 (`RefreshOnEntry`, `rememberLoad`, `StackSaver`, legacy format wording); fix ADR-06's measurement chronology against feature-list.
- **Final check**, concise status in feature-list and exact commands, exits and terminal results in git/PR evidence:
  1. CI green on `main` for the last code PR (backend format and tests; Android format, Lint, JVM tests, reader bundle, both assemblies).
  2. One full device suite on the Pixel 8a Dev app: terminal `OK (n tests)`.
  3. Dev app against the Mac backend and real GitHub: open a note with an embedded image and a wide table, one Practice grade, one Review, an offline open, a reading position saved with the phone's clock set ahead, a sign-out and sign-in with every saved note still there.
  4. Release build installed over the phone's app, and the hosted backend's revision and health read as the backend README documents. Deploy and install need your go-ahead at the time.
  Room migration coverage is the preserved v15 migration and PR3's v16 migration (upgrades from exported v14 and v15); PR4 needs no schema change. `AnchorMeasurement`, `MoveMeasurement`, `RenderedCorpus` and `StudyCorpus` are not rerun: no PR changes `Anchoring`, `ContentMoves` or the canonical rules.
- **Retain this file after PR5 (user decision, 2026-10-19).** Section 5 is still needed for post-PR5 work, including images in Save all and mechanical moves. Grade-write ownership was pulled forward during review. Decisions live in the ADRs, executed status in feature-list and evidence in git/PRs; the four earlier implementation sections in feature-list now each have one status line naming their PR.

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
| Latest repository snapshot | `RepositoryRow.lastSyncedCommitSha` |
| Saved image identity | `NoteRow.snapshotCommitSha`; null is legacy unknown, not permission to fetch current images |

### Patterns

- Mutually exclusive states as sealed types or enums; no state-machine framework. A busy flag or `NonCancellable` is not a transaction; Room and PostgreSQL transactions own multi-row correctness.
- Loads and outcomes belong to the entry's ViewModel, accepted mutations to `Sync.committed`, sign-in/sign-out/deletion to `Account` (ADR-28). Compose observes and emits actions; only the resumed UI consumes a result.
- One coordinator in `Sync`; no single-implementation managers, repositories, use cases, event buses, retry frameworks or wrappers around the DAO.
- Server-owned limits; a failed call never starts another strategy; call ceilings stated per operation.
- Keep: backend feature packages (acyclic), `Annotations`/`AnnotationService`/`Anchoring` separation, one `LibraryDao`, `ReaderSession`/`ReaderPage`/`ReaderWebView` separation, pure `ReviewSchedule`, WorkManager's two jobs, immutable mutation ids, original anchors, the Room migration history.

## 5. Reducing the app's complexity

Except for the grade-write ownership correction pulled forward below, everything here comes after PR 5. The user approved the mechanical moves on local branch `mechanical-moves` while PR5 deployment is pending. The rule: a feature or layer is deleted when real use shows it unused or when a simpler mechanism gives the same result; it goes with its rows, routes, tests and docs in one PR. Files move only in PRs that move files. New features are built only where the reading or study loop is actually broken.

### Build: images in Save all

The one functional gap worth a slot. An embedded image is unavailable offline until its note is opened online once (feature-list, known gaps). Save all already fetches each outdated note sequentially; it fetches that note's images through the existing `NoteImages` owner in the same pass, bounded by the existing 64 references per page. No new mechanism, no new table.

### Mechanical moves: local implementation (D1 lifted)

One local change, with no study/network behaviour changes:

1. `Load.kt` now belongs to `ui/`; `Reader.kt` and `Available.kt` import it there, leaving no Compose code in `core/network`.
2. Practice, Review, `ReviewSchedule`, `StudySession`, bank recognition (`Study.kt`) and answer views (`Answer.kt`) now belong to `study/`, with their JVM/device test suites. The reader's LeetCode Study view and its two existing JVM test methods stay in `reader/Study.kt` and `reader/StudyTest.kt`: the import direction is only `study` → `reader`, not a cycle. `library/` means library browsing again. Every caller imports the new owner; no aliases or old implementations remain.
3. `interleaveByNote` now belongs to `study/StudySession.kt`; the Notebook-only folder helpers are named `ancestorFolders` and `inFolder` in `library/Folder.kt`, with their assertions in `FolderTest`.

**Approved compatibility exception (2026-10-19):** moving the Serializable `StudySession` changes its saved class name. The user approved advancing saved-UI-state format 3 → 4 rather than retaining the former class. Navigation, drafts and in-progress sessions reset once; Room, saved notes, images and pending mutations remain untouched (ADR-27). This is not a database migration. Verification and limits are recorded in [feature status](feature-list.md#mechanical-moves--local-verification-2026-10-20); this work is not pushed or deployed.

**Follow-up decision, not part of these moves:** replacing Java-Serializable session state with class-name-independent field encoding would avoid the same problem on future renames. Practice and Review's `SavedStateHandle` persistence remains unchanged here; format 4 still applies. No new persistence path or additional reset is introduced without that separate decision.

### Next deletion to decide by use

**Bookmarks.** A separate table, backend endpoint, pending flag, send pass, migration and Library tab, so a note can be starred. Reading positions already give "continue where I was". Decide after two weeks of use; if deleted, highlights remain the one annotation model.

### One owner for "may this grade be written" (from the PR4 audit, 2026-10-19)

**Pulled forward during PR5 review.** Both admission decisions now belong to the DAO transaction that writes the grade: `saveCardWithFirstReview` checks the saved bank version and existing schedule; `recordReview` checks the current card and answer. `saveReview` alone owns the phone's stored-grade range, including remote snapshots and acknowledgements. Sync's lock still orders operations but is not relied on for transactional admission. Direct-DAO, rollback and concurrent-submission regressions cover the boundary.

### Not simplified, and why

- Navigation 3 and the six remaining entry ViewModels: standard, just landed, and the alternative was the hand-written stack they replaced.
- Two refusal shapes (`AnnotationState`, `review_log.rejection`): one pending table would be a larger migration than the duplication costs. Revisit if a third kind of pending change appears.
- `NoteImages`' own file lock: images load outside the sync lock so a page's images never wait behind a sync.
- Backend auth (about 1,100 lines across `GitHubCredentials`, `GitHubTokens`, `GitHubSecurity`, `AppSessions`, `AppAuthController`): GitHub App user tokens expire, so renewal, encrypted storage and revocation are requirements, not optional layers.

### Documentation

This plan stays after PR5 at the user's explicit request. Section 5 remains the post-PR5 work plan; do not delete it while those items are still needed. The ADRs, architecture, invariants, feature status and user stories keep their own facts, with executed evidence in feature status, merged PRs and git history rather than duplicated transcripts here.
