# RepoRead — First MVP Build Plan

This is the implementation sequence, not a new feature specification. The [user stories](reporead-user-stories.md) own acceptance criteria, the [feature list](reporead-feature-list.md) owns MVP scope, and the [implementation spec](reporead-product-engineering-spec.md) owns technical design. Resolve conflicts with those documents before coding; do not silently reduce scope.

## 1. What we are building

An Android app that lets the developer connect selected GitHub notes repositories, read technical Markdown, resume reading, annotate offline without changing source files, and review source changes without losing trustworthy annotations.

Use the agreed stack: Kotlin/Compose and Room on Android; Java/Spring Boot and PostgreSQL on the backend. Start with one backend deployment. Exact dependency versions must be verified and pinned when each component is introduced.

**Two delivery checkpoints:**

- **First usable release — end of Stage 2:** real repository browsing, safe technical Markdown rendering, offline reading, progress, bookmarks, and Continue Reading. Start daily use here.
- **Complete first MVP — end of Stage 6:** all features F-01 through F-13, including offline annotations, conservative re-anchoring, manual reattachment, changed-since-last-read, cached search, and data controls. The first usable release is not a replacement for this scope.

## 2. How we will build it

Build vertical slices, not a complete backend followed by a complete app:

```text
Authorized GitHub repository
    → Spring Boot API
    → PostgreSQL application state
    → Android library and reader
    → Room offline projection
```

| Owner | Responsibility |
| --- | --- |
| GitHub | Authored Markdown, repository access, and Git history |
| Spring Boot/PostgreSQL | User authorization, connections, logical document identity, durable reading/annotation state, source reconciliation, and re-anchoring decisions |
| Android/Room | Rendering, text selection, offline projections, and pending local mutations |
| Reader parsing boundary | A documented text/block mapping so selections and restored locations mean the same thing to renderer and backend |

Rules throughout:

- Reading actions never write to source Markdown.
- GitHub credentials stay on the backend; renderer JavaScript receives no credentials or native bridge.
- Android and backend use explicit HTTP/JSON contracts, not shared JVM domain classes.
- Introduce only the schema, packages, and dependencies needed for the current slice.
- Add authorization checks, boundary validation, and secret-safe logs with the first real API, not during final polish.
- The server owns file, diagram, traversal, pagination, and external-call limits. Document a hard call ceiling for each operation before implementing it, including token acquisition and permission checks. Numeric limits are not settled by this draft.
- A failed call terminates that operation; it does not trigger another fetching strategy. Cached content may remain readable, explicitly marked stale. Do not persist partial responses as successful refreshes.
- Keep refresh and mutation replay as explicit operations. Add background scheduling only after the foreground path works; no generic job or retry framework.

## 3. Stage overview

| Stage | Result | Scope | Depends on |
| --- | --- | --- | --- |
| 0 | Authorization and renderer boundaries proven | Foundations for F-01, F-03, F-04, F-07 | Existing proofs and remaining boundary tests |
| 1 | One real note opens through the real backend | F-01–F-04 | Stage 0 |
| 2 | Dependable daily reading, including offline | F-05, F-06 | Stage 1 |
| 3 | Durable online and offline annotations | F-07, F-08 | Stage 2 and the anchor contract |
| 4 | Annotations survive edits or become explicit orphans | F-09, F-10 | Stage 3 |
| 5 | Meaningful changes since the version actually read | F-11 | Version reconciliation from Stage 4 |
| 6 | Search, data controls, release, and MVP evidence | F-12, F-13; full acceptance review | Stages 1–5 |

Stage 0 is partially verified. Stage 1's exit gate passed on 2026-10-06: a real note opened on the phone through the real backend from the connected private repository (see the [Stage 1 record](reporead-stage1-record.md), including what is verified only by automated tests). Stages 2–6 are planned, not implemented.

## Stage 0 — Finish proving the risky boundaries

**Goal:** establish real authorization and mixed-content selection before durable application state depends on them.

### Current evidence as of 2026-10-02

See the [reader spike evidence](../spikes/reader-android/README.md).

- Read-only GitHub App installation access to the selected real repository has been demonstrated separately.
- On the Pixel 8a, the local HTML spike returns the expected quote, block, and offsets, repeats the result after a cold launch, and rejects cross-block selections. These are user-reported on-device results.
- The selected real Markdown note has been located and inspected for format coverage.
- The backend now parses/sanitizes Markdown and exports canonical, versioned block text; Java behavior tests and Android compilation pass. The user confirmed real browser sign-in returning GitHub ID/login on 2026-10-02 and supplied the user-scoped repository response showing private repository `jaxwong/zw_obsidian` (ID `1160483465`) in installation `166757310`. Real authorization passed based on these user-reported browser results; automated isolation/failure tests pass separately.
- The approved targeted lodash-es update cleared the npm audit without changing Mermaid 11.17.2. The new reader is installed on the Pixel 8a, and the real-note export was verified in private app storage. The user reported a passing real-note renderer status: ready, 200 blocks, four diagrams, zero diagram errors, and matching canonical text. Subsequent user screenshots show passing automated DOM checks for empty selection, all 200 canonical block ranges, and cross-block rejection. A native prose selection of `untrusted requests` in `b1` at UTF-16 offsets `[131,149)` also passed, independently checked against the exported source version/text. Diagram legibility, the separate code/image/Unicode fixture and its DOM checks, other native touch-selection cases, repeat cold-launch behavior, and live backend note delivery remain unverified. See the [Stage 0 contract and execution record](reporead-stage0-boundaries.md).

### Build next

1. **Authorization proof passed:** the real backend verifies GitHub user identity and user-scoped repository eligibility, independently of installation-token access. Keep this wiring for Stage 1; durable connections and Android session handoff are not implemented yet.
2. Render the chosen real Markdown note with prose, syntax-highlighted code, tables, and Mermaid. It has no Markdown image, so add a separate image-bearing note test; label any synthetic image fixture clearly.
3. Verify raw HTML is disabled/sanitized, dangerous navigation is blocked, Mermaid runs in strict isolation, and oversized or invalid content produces a visible error.
4. Decide parser placement and document the mapping from rendered selections to anchorable text: block identity, text normalization, offset units, and source version. Current DOM offsets must not be mistaken for offsets in raw Markdown.
5. Repeat selection checks with the real renderer, including formatted text, code, table cells, Unicode, and a selection spanning unsupported blocks. Stable offsets in a synthetic paragraph alone do not prove this mapping.

**Exit gate:** real user authorization and real repository read access are proven; the phone safely renders representative technical content and returns a reproducible, versioned selection location.

**Decision:** Compose chrome plus an isolated WebView remains the leading approach, not a final choice until mixed-content tests pass. Record the evidence and renderer/anchor decision.

## Stage 1 — Open one real note end to end

**Goal:** establish the smallest production-shaped reading path.

### Backend

- Add the Java/Spring Boot entry point and local PostgreSQL development setup.
- Define the first HTTP/JSON contract using the implementation spec's repository/document API sketch. The auth/session contract (Custom Tab + PKCE-bound single-use code → bearer session) is specified in the backend README.
- Persist users, authorized repository connections, and logical document metadata using reviewed migrations. Do not create every future table now.
- Let the user connect one or more eligible repositories; use one real repository for the first acceptance test without hardcoding it into product behavior.
- Discover Markdown paths and fetch a requested note body on demand. Do not download the whole repository.
- Resolve discovery against a known source revision. Publish a refresh and advance its checkpoint only after complete validation and persistence.
- Treat truncated discovery as incomplete, never as proof of deletion. Repeating the same complete refresh must not create duplicate documents.
- Enforce user ownership on every connection, document, and content request. Keep tokens encrypted where stored and out of logs.

### Android

- Add the real app entry point, sign-in/connection screens, repository/folder/note library, and reader.
- Connect through the backend, never directly with the GitHub App key.
- Render the safe technical content proven in Stage 0, including links, task lists, quotes, images, code, tables, and Mermaid.
- Show loading, empty, access-denied, unavailable, and unsupported-content states explicitly.

**Verify:** real phone → real backend → real authorized repository → opened note; a second user cannot read it; an unauthorized repository is unavailable; empty repositories work; partial discovery does not remove records; the second refresh is idempotent; source files remain unchanged.

**Exit gate:** the real note opens from the library on the phone through the backend, with no fixture, seeded connection, or hardcoded repository substituting for integration.

**Cleanup:** when the product reader replaces the experiment, obtain approval and remove the spike module and its build references in the same replacement step. Do not maintain two renderer implementations.

## Stage 2 — Make reading dependable

**Goal:** deliver the first release worth using every day.

### Build

- Store repository/document metadata and opened note bodies in Room, identified by document and source version.
- Cache the assets needed for supported offline rendering. Define how relative/private images are resolved without exposing tokens, and what happens to uncached remote images.
- Open cached notes immediately. A refresh failure preserves the previous complete cache and shows stale/offline status; it does not overwrite good content with a partial response.
- Save semantic reading anchors, approximate progress, and the version actually displayed/read. A background fetch must not update the last-read version.
- Restore using the implementation spec's heading/text/block strategy and label approximate restoration when necessary.
- Add Continue Reading and bookmarks. Use the existing annotation domain's `BOOKMARK` ownership rather than inventing a separate bookmark source of truth.
- Persist reading state locally and synchronize it through the backend. Reading-progress conflict policy follows the implementation spec; annotation text will use a different policy.

**Verify:** open → read → close → airplane mode → reopen → resume near the same passage. Also test process death, rotation, changed font size, no saved progress, an uncached note, missing assets, backend failure, and a second synchronization.

**Exit gate:** cached technical notes are useful offline; progress and bookmarks survive restart; the library offers a working Continue action.

**Checkpoint:** start daily dogfooding and record actual reading friction before adding polish or cache tuning.

## Stage 3 — Annotate online, then offline

**Goal:** make study state durable without changing source Markdown.

### 3A — Online annotations

- Add annotation and anchor persistence, including source blob/version, exact quote, nearby context, heading path, and defined offsets.
- Add highlight creation, optional note text, document-context display, editing, and deletion through the existing API sketch.
- Validate ownership and anchors at the boundary. Display highlights using the agreed render-to-anchor mapping.
- Add optimistic annotation updates; surface a stale-version conflict rather than silently overwriting another edit.

**Gate:** create a real highlight and note, reopen the document, and recover both in context. GitHub source and Git history remain unchanged.

### 3B — Offline annotations

- Persist local annotation changes and their pending mutation record atomically in Room before claiming they are saved.
- Start with annotation creation: one stable client mutation ID, replay of the same operation, and a durable backend idempotency record.
- A duplicate ID with the same operation returns the original result; reuse with different content is rejected.
- Mark a mutation acknowledged only after terminal server success. Preserve pending state across process death and lost responses.
- Offline creation is the required first path. The stories do not explicitly require offline editing/deletion; settle that scope before enabling it. Online editing/deletion and visible annotation-version conflicts are covered in Stage 3A. Do not build generalized mutation replay in anticipation of broader scope.
- First synchronize on explicit refresh or foreground reconnect. Add WorkManager only for the named background-delivery requirement after replay is correct.

**Verify:** offline create, process death, reconnect, server rejection, server commit followed by lost acknowledgement, duplicate submission, concurrent duplicate requests, interrupted replay, edit conflict, and a second reconnect.

**Exit gate:** replaying a real offline annotation submission produces exactly one durable annotation; pending work survives restart; conflicting text is not silently lost; source Markdown remains untouched.

## Stage 4 — Preserve identity and annotations through source edits

**Goal:** handle changing documents without confident-looking wrong answers.

### Build in order

1. Track document versions and reconcile changed Markdown from a known repository revision.
2. Recognize a unique path-only move with the same blob SHA. Identical-content duplicates must not be merged merely because their hashes match.
3. Resolve anchors by unchanged position plus exact text, unique exact quote, then quote plus heading/context.
4. Add measured matching for lightly edited passages and strongly supported move-with-edit cases. Do not ship example scoring weights as established confidence.
5. Mark uncertain anchors `ORPHANED`, retaining original selection/context. Add manual reattachment.
6. Mark genuinely deleted notes without silently deleting reading history or annotations.
7. Handle missing history and branch rewinds explicitly. Reconciliation decisions belong to the backend; incomplete comparisons must not imply absent files were deleted.

**Verify:** inserted paragraph, moved section, duplicate quote, lightly edited passage, path-only move, ambiguous duplicate-content move, move plus edit, destructive rewrite, source deletion, branch rewind, and a second sync.

**Exit gate:** strong-evidence edits preserve identity and anchors; ambiguous edits produce an orphan; manual reattachment works. Orphaning every lightly edited passage is not a substitute for US-07's survival acceptance criteria.

## Stage 5 — Show changes since the version actually read

**Goal:** deliver RepoRead's Git-aware reading differentiator.

### Build

- Compare the current source version with the user's last-read source version, not the previous sync checkpoint.
- Compute changed line ranges and map them to Markdown heading sections.
- Add recently updated/unread-change indicators in the library.
- Show added, changed, or removed sections in a mobile-friendly summary and navigate to a current section or the full note.
- Give never-read notes, unchanged versions, unavailable old content, and incomplete/oversized comparisons honest states.
- Bound source-version fetching and comparison work; do not present a limited GitHub response as a complete diff.

**Verify:** read version A → edit/push from laptop → refresh version B → inspect summary → open changed section. A refresh without reading must leave A as the last-read baseline. Also test deleted sections, duplicate headings, missing old blobs, and repeat refresh.

**Exit gate:** the phone reliably shows what changed since the user's actual reading session, with no fabricated baseline or completeness.

## Stage 6 — Complete and release the first MVP

**Goal:** finish remaining scope and demonstrate reliability, not add new product ideas.

### Complete scope

- Add basic search over cached note titles/content with clear empty-query and no-match states. Start locally; a backend search index is not required for this story.
- Add repository disconnect and account deletion, with confirmation and correctly scoped cleanup of backend/mobile content and access state. Do not remove a shared GitHub App key or another repository's state.
- Decide any unspecified retention behavior for annotations/history on disconnect before coding it. Account deletion and upstream document deletion are different operations.
- Create a signed release APK and install it directly on the physical phone. Google Play is not required.

### Harden and demonstrate

- Expand the tests already introduced in each stage: PostgreSQL/Testcontainers integration, authorization isolation, sync atomicity, mutation idempotency/concurrency, malicious rendering input, lifecycle/offline behavior, and re-anchoring.
- Finish the specified secret-safe logs and metrics for GitHub calls, sync failures, and anchor outcomes. Monitoring remains a projection, not application state.
- Document local setup, actual build/test/install commands, failure states, and the key ADRs.
- Provide a deliberately selected, clearly labeled fixture demo when GitHub is unavailable. Do not silently switch a failed live integration to demo data.
- Run the real demo separately: repository browse → Mermaid note → annotation → laptop source edit → re-anchor or orphan → changes since read → offline creation → one successful synchronization.

**Exit gate:** all F-01–F-13 and US-01–US-11 acceptance criteria pass, plus the [personal-project definition of done](reporead-feature-list.md#personal-project-definition-of-done): two weeks of voluntary real use, at least 20 real highlights/annotations, a signed APK, real source edits, offline synchronization, PostgreSQL tests, and documented decisions.

## 4. Verification and delivery discipline

For every working slice:

1. Read the behavior's owner, callers, and existing tests.
2. Specify the smallest observable acceptance test and any external-call ceiling.
3. Run the failing/new test before implementing the behavior.
4. Implement one connected path; add failure, empty/missing input, concurrency, and second-run cases where applicable.
5. Build/typecheck, run the relevant tests, and exercise platform behavior on the phone.
6. Record exact commands, exit codes, and output; distinguish automated checks from user-reported device observations.
7. Commit the verified step without including unrelated work. Do not advance a stage on mocks alone or leave its superseded implementation running in parallel.

Verified run/test commands live in [backend/README.md](../backend/README.md) and [android/README.md](../android/README.md). Add commands there only after running them.

## 5. Decisions to settle at their owning stage

| Decision | When |
| --- | --- |
| Parser/renderer libraries, selection coordinate contract, and safe image policy | Stage 0, finalized for Stage 1 |
| Auth callback/session contract, secret provisioning, and repository eligibility verification | Stage 0/1 |
| Server-owned source/diagram limits and a complete external-call budget per operation | Before each integration operation is implemented |
| Cache retention/eviction and foreground/background behavior | Stage 2, based on real use |
| Durable mutation/idempotency schema, annotation conflict UI, and whether offline edit/delete is required | Stage 3 |
| Confidence criteria for fuzzy anchors and move-with-edit recognition | Stage 4, from test evidence |
| Disconnect/history retention, account cleanup, and release signing | Stage 6; before external use |

This draft schedules future schema/infrastructure work; it does not execute migrations, delete files, change infrastructure, or approve destructive actions.

## 6. Explicitly out of this MVP

Keep the feature list's exclusions: source editing/write-back, AI features, collaboration, iOS, Play publication, other Git providers, optional math, webhooks, and AST-aware diffing. No Elasticsearch, Kafka, microservices, generic sync engine, or cache framework without a demonstrated requirement.

**Next verification task:** verify the separate code/image/Unicode fixture and remaining formatted-text, code, table-cell, Unicode, and manual cross-block touch selections; inspect diagram legibility, then cold-reopen and repeat. Real browser authorization, real-note renderer/automated DOM-range checks, and one native prose selection have passed based on user evidence. Do not repeat the already-passed synthetic paragraph tests as if they prove the remaining boundaries.
