# RepoRead — Invariants

Rules every change keeps. Each says what must always hold, where it is enforced, and how it is checked; the linked ADR says why. A change that needs to break one starts with a new or amended ADR, not with code.

These come from the original implementation spec's engineering invariants and the rules carried into post-MVP work. [Architecture](architecture.md) shows the parts they constrain; [ADRs](adrs.md) record the decisions behind them.

## Data and ownership

### Source integrity

**RepoRead never modifies source Markdown or anything else in GitHub.**

- Enforced by: the GitHub App has read-only permissions, and `GitHubApi` reads with GET only. The other GitHub calls are OAuth token exchange and renewal, and `GitHubCredentials` revoking RepoRead's own authorization for a user (`DELETE /applications/{client_id}/grant`), none of which touches repository content. Highlights, cards, reading state and practice live in RepoRead's own tables.
- Checked by: no route writes to GitHub; verification edits to the notes repository are made by hand, only under `scratch/`.
- Why: [ADR-02](adrs.md#adr-02--github-is-canonical-and-read-only-note-bodies-are-not-stored-on-the-server), [ADR-16](adrs.md#adr-16--a-read-only-github-app-the-users-own-token-decides-what-can-be-connected).

### One owner per piece of state

**Each piece of state has exactly one owner, and every other copy is a projection of it.**

| State | Owner |
| --- | --- |
| Note content and history | GitHub |
| Users, sessions, connections, document identity, reading state, annotations, the review log, the session limit, every reconciliation decision | Backend and PostgreSQL |
| Saved copies, pending changes, change baselines, the review schedule | Phone (Room) |
| Metrics and logs | Projections only, never read back as state |

- Enforced by: Room replaces server lists only with complete responses; the server stores no due dates; the phone computes no reconciliation.
- Why: [ADR-01](adrs.md#adr-01--a-spring-boot-backend-between-the-phone-and-github), [ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes), [ADR-13](adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling), [ADR-21](adrs.md#adr-21--metrics-and-logs-are-projections-on-a-loopback-port).

### The phone's lists are one server snapshot

**Every list the phone saves from the server comes from one read of one database snapshot, and is written with the others in one Room transaction: all of one answer, or none of it.**

- Enforced by: `GET /api/library` is the only route this app reads them from, one request per read and no GitHub call, and the server has no other route for them (ADR-08); `Library` reads every list in one read-only `REPEATABLE READ` transaction; `Api` decodes and validates the whole answer, then `Sync` maps it before `LibraryDao.replaceFromServer` writes it, and deletes images only after the commit.
- Checked by: `LibraryTest` (a write committed during the read is wholly out of it and wholly in the next); `LocalSyncTest` on a device (a failed request or a broken answer leaves every saved list and image as it was).
- Why: [ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes).

### Source traceability

**Every annotation keeps the blob it was created against, and its original anchor never changes.**

- Enforced by: `annotation_anchors` is written once at creation; re-anchoring and reattachment write only `annotation_locations`.
- Why: [ADR-05](adrs.md#adr-05--anchors-are-block-ids-and-utf-16-offsets-into-canonical-text-of-one-version), [ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan).

### An incomplete listing is never evidence of deletion

**A sync publishes a complete snapshot or changes nothing; a missing path is marked deleted, never removed, and only from a complete tree.**

- Enforced by: `RepositorySync` validates the whole tree before one transaction; neither an older snapshot nor one of a branch the connection no longer follows can replace the current one (409 `SYNC_SUPERSEDED`).
- Checked by: `RepositorySyncTest`.
- Why: [ADR-07](adrs.md#adr-07--document-identity-from-complete-snapshots).

## Correctness

### Annotation correctness

**An annotation is never attached to a place RepoRead cannot identify with measured confidence; it is orphaned instead, with its original selection and context.**

- Enforced by: `Anchoring` with thresholds from `AnchorMeasurement`, never chosen by hand; every location records how distinguishable its passage was in its own version, which `Anchoring` requires (not null since V12).
- Checked by: re-running `AnchorMeasurement` whenever `Anchoring` changes; the bar is 0 wrong attachments.
- Why: [ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan).

### Canonical text

**A rendering change leaves an unchanged blob's canonical block text identical.**

- Enforced by: presentation is added after parsing, with source text hidden or controls placed outside `[data-block-id]`; the reader treats any mismatch between page text and exported canonical text as a failure, not an approximation.
- Checked by: `RenderedCorpus` before and after a renderer change, then `diff -r` of the blocks. Bump `MarkdownRenderer.FORMAT` when saved pages must be fetched again.
- Why: [ADR-05](adrs.md#adr-05--anchors-are-block-ids-and-utf-16-offsets-into-canonical-text-of-one-version), [ADR-18](adrs.md#adr-18--rendering-changes-never-change-an-unchanged-blobs-canonical-text).

### Offline idempotency

**Replaying a client mutation id never creates a second annotation or grade.**

- Enforced by: `annotation_mutations` and `review_log` are keyed by user and client UUID; a replay returns the original, reuse with different content is 409 `MUTATION_ID_REUSED`, and a replay after deletion is 410.
- Why: [ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes), [ADR-13](adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling).

### No silent overwrite

**Annotation text is never overwritten by a concurrent edit; a conflict is shown.** Reading position is the exception: last write wins by the client's time, because losing a progress update costs little.

- Enforced by: optimistic `version` on annotations (409 `ANNOTATION_CONFLICT`); a re-anchoring computed before a user edit does not apply over it.
- Why: [ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes).

### An outdated answer is never drilled

**A card whose note changed is not graded until its answer is resolved in the new version and confirmed.**

- Enforced by: the server rejects a new grade of an outdated answer (409 `CARD_CHANGED`); the phone leaves such cards out of grading until checked.
- Why: [ADR-13](adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling).

## Security and privacy

### Repository isolation

**A user reaches only repositories GitHub authorizes for that user.**

- Enforced by: eligibility comes from the user's own token; every connection, document and content request is scoped to the signed-in user, and another user's ids are 404 without a GitHub call.
- Why: [ADR-16](adrs.md#adr-16--a-read-only-github-app-the-users-own-token-decides-what-can-be-connected).

### Token secrecy

**GitHub credentials never appear in logs, metrics, rendered pages, page JavaScript, or on the phone, and are never readable from the database alone.**

- Enforced by: tokens are stored only on the server, AES-256-GCM-encrypted under a key file kept outside the database (`GitHubTokens`); the phone holds only an opaque app session, encrypted with a Keystore key; native code attaches it to image requests so the page never sees it; logs and metric tags carry ids, SHAs and counts only.
- Why: [ADR-03](adrs.md#adr-03--github-user-tokens-stay-on-the-server-encrypted-at-rest), [ADR-21](adrs.md#adr-21--metrics-and-logs-are-projections-on-a-loopback-port).

### Repository content is untrusted

**Note content never runs as code and never reaches native code.**

- Enforced by: raw HTML is escaped and output passes a jsoup safelist; the WebView loads only bundled assets and the note's own images, with network, file and content access blocked; Mermaid runs in strict mode within server-owned bounds; there is no JavaScript bridge, and the page reaches native code only through intercepted same-origin links (`/note-link`, `/copy-code`, `/full-screen`).
- Why: [ADR-04](adrs.md#adr-04--the-server-renders-an-isolated-webview-displays), [ADR-18](adrs.md#adr-18--rendering-changes-never-change-an-unchanged-blobs-canonical-text).

### A displayed page never borrows another snapshot's images

**Every repository image belongs to the displayed page's immutable repository commit and resolved path, even when only images changed.**

- Enforced by: the content boundary captures document/checkpoint and image paths together, binds embeds once, and returns `snapshotCommitSha`; `NoteRow`, shared reader resources and figure navigation preserve it; image requests require that commit, and `NoteImages` uses one commit-keyed layout. An unknown legacy identity means unavailable images, never a latest-snapshot fallback.
- Checked by: `DocumentContentTest` (explicit older commit and refresh during blob fetch); `LocalSyncTest`, `NoteImagesTest`, `ImageSnapshotMigrationTest`, `FigureSnapshotTest` and `NavigationTest` (image-only refresh, repeat reads, cache/deletion interleavings, preserved legacy data and saved navigation).
- Why: [Images contract](../backend/README.md#images), [ADR-22](adrs.md#adr-22--the-phones-data-stays-on-the-phone-and-belongs-to-one-account).

### The user's data leaves only when the user sends it

**Notes and annotations are not backed up off the phone, transferred, logged, or kept in live data after the user removes them; the server's database backups keep their copies until replaced.**

- Enforced by: Android backup and device transfer excluded; logs carry no content; disconnect and account deletion remove everything stored for them in the live database and on the phone, and their dialogs say that existing backups keep a copy until replaced (retention in backend/README.md, Backups); exports exist only in a private folder until shared, and are deleted at each start, disconnect and clear; a sign-out, or an account deletion the server confirmed, stays pending on disk until Room's rows and every file are gone, and only then forgets the data's owner; Account permanently retires the old Sync/image lifetime before clearing and publishes a new one only after successful cleanup; local writes, server answers and exports share that lifetime's lock, so no stale caller can recreate rows or files, including after same-account re-sign-in; worker and activity share Account startup before sending; a sign-in over unowned data requires a pending verifier before cleanup; saved images are derived from Room's saved snapshots, cache reads share the deletion lock, and a fetch that began before a deletion does not write.
- Why: [ADR-11](adrs.md#adr-11--disconnecting-deletes-everything-stored-for-that-repository-account-deletion-deletes-everything), [ADR-22](adrs.md#adr-22--the-phones-data-stays-on-the-phone-and-belongs-to-one-account).

## Failure behaviour

### Graceful degradation

**Saved notes stay readable, and local changes queue, when GitHub or the backend is unavailable.** A failed refresh never replaces saved lists or notes.

- Why: [ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes).

### An uncertain answer never acknowledges or rejects pending work

**A complete, valid, request-matching response is required before acknowledging a mutation. Only a known item-refusal status/code pair authorizes its permanent rejection.**

- Enforced by: `Api` owns route statuses, strict scalar/time decoding, required-nullable presence (generated from `backend/openapi.json`), the value rules (`WireRules.kt`) and echoed identity checks; `SignIn` owns the recorded account match, and `Sync` owns a review's local card match. `Sync.refusesItem()` owns the positive refusal decision. Malformed/oversized responses, unknown codes and known codes at the wrong status leave work pending; a later run uses the same mutation id. No alternate fetch/retry strategy is introduced.
- Checked by: `OpenApiSpecTest` (the committed spec is the backend's, and its mapper's actual output conforms), `ContractTest`, `ItemRefusalsTest` with `RefusalTest`, `ApiTest`, `LocalSyncTest` and `AccountTest`. A broken reading-save answer cannot acknowledge the save; an unconfirmed account deletion cannot authorize local removal.
- Why: [ADR-26](adrs.md#adr-26--one-owner-for-the-apps-side-of-the-http-contract).

### Server-owned limits and no fallback

**Every operation has a stated ceiling on GitHub calls, set by the server; a failed call ends the operation.**

- A route's ceiling is written in the [backend README](../backend/README.md#api) before the route is built.
- A client cannot choose its own cap.
- No retry, backoff, alternate fetching strategy, or installation-token fallback. A comparison or listing that hits a limit says so; it never presents a partial result as complete.
- Why: [ADR-02](adrs.md#adr-02--github-is-canonical-and-read-only-note-bodies-are-not-stored-on-the-server).

### Error wording has one owner per client

**The server sends a code and a neutral message that names no client's screens or controls; the app words codes for its screens only in `describe()` (`Api.kt`).**

- Checked by: `DescribeTest`.
- Why: [ADR-01](adrs.md#adr-01--a-spring-boot-backend-between-the-phone-and-github).

### The previous release runs on the current schema

**A migration never removes or requires what the previous release uses, so going back after a deploy is putting the previous jar back.**

- Enforced by: from V15, migrations only add; a step that removes or requires something waits for a release after the one that stops using it, and its migration says so in a `-- Contract:` line.
- Checked by: `ExpandOnlyMigrationsTest`, which fails on such a step without that line, and which V13 and V14 would have failed.
- Why: [ADR-24](adrs.md#adr-24--migrations-keep-the-previous-release-working-a-failed-deploy-is-undone-by-hand).

### Bounded memory on the phone

**No phone query or screen loads every saved page at once.**

- Why: with every note saved (ADR-19), all rendered pages exceed the app's 256 MB heap. On 2026-10-14 the app crashed with `OutOfMemoryError` three times while Practice loaded: its query read every saved page (about 800) to find 92 interview banks, and re-ran whenever Library's sync changed the tables.
- Enforced by: queries select only the pages they need (`bankPages` reads only notes in interview folders); what is needed from every page is kept beside it when it is saved, so it is never read from the pages (`note_links`, for Linked from).
- Checked by: `LocalStoreTest`, and the Java heap on the phone while the screen opens (Practice peaks at 40 MB).
- Decisions: [ADR-19](adrs.md#adr-19--every-note-can-be-saved-on-the-phone-one-note-at-a-time-with-no-eviction), [ADR-20](adrs.md#adr-20--practise-from-interview-banks-in-sessions-that-show-the-linked-answer).

### Saved screen state belongs to its entry and its format

**A back-stack entry's saved UI state and ViewModel store are its own from push to pop; compatible saved state survives ordinary build updates, and a new local copy starts a fresh root.**

- Enforced by: Navigation 3's saved-state and ViewModel-store decorators above the account gate, keyed by each `Entry`'s id; the back stack's `Account.sync` input; `compatibleSavedState` at the Activity boundary.
- Checks: `AppNavigationTest` drives real screens/account gates, fresh-copy resets and Activity restoration; `NavigationTest` checks every destination and format acceptance; `EntryLifetimeTest` probes real decorated Navigation 3 owners, including cover/rotation/pop/removal and fresh-Activity restoration. The debug host uses the production format policy at its Activity boundary. `MainActivity` wiring itself remains checked by review; tests do not launch the live app's store. Execution status is recorded in ADR-27.
- Why: Compose keeps saved state by place, so incompatible formats must be rejected without discarding compatible state at every update; [ADR-27](adrs.md#adr-27--navigation-3-owns-the-back-stack-saved-screen-state-belongs-to-its-entry-and-its-format).

## Constraints on reader code

These are facts of the platform, not decisions, but breaking them breaks the reader:

- In the app's WebView, `100vh`, `100dvh` and `100svh` measure 0. Size page elements in percent of the viewport. Headless Chrome resolves them normally, so check sizing on the phone.
- `scrollIntoView` does nothing for a Mermaid source collapsed under its diagram; measure and scroll through the rendered diagram.
- Showing or hiding bars while a finger is on the page moves the WebView under it and reads as a scroll the other way; change bars only when the finger lifts.
- A WebView's renderer can die (a crash, or Android reclaiming it). Unless the WebView's client handles `onRenderProcessGone` (returns true and takes the WebView out of the layout), the app dies with it. `chrome://crash` triggers it for a test.
