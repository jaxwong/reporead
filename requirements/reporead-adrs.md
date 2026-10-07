# RepoRead — Architecture decision records

The decisions that shape RepoRead's MVP, as built. Each says what was decided, why, and what it costs; evidence lives in the stage records and contracts in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs. Status of all: **accepted**, as of 2026-10-07.

## ADR-01 — A Spring Boot backend between the phone and GitHub

**Context.** The phone could call GitHub directly. But annotations, reading state, document identity across edits, and re-anchoring need durable server-side state and decisions, and a GitHub credential on the phone would sit next to untrusted rendered Markdown.

**Decision.** One Spring Boot/PostgreSQL service (a modular monolith: `auth`, `repository`, `sync`, `document`, `reading`, `annotation`) owns users, connections, logical documents, reading state, annotations, and every reconciliation decision. The Android app (Kotlin/Compose, Room) talks to it only through explicit HTTP/JSON contracts; no JVM classes are shared.

**Consequences.** The phone needs the backend for refreshes and sync; saved content stays readable without it. The service runs on the developer's Mac and is reached through `adb reverse` (ADR-12).

## ADR-02 — GitHub is canonical and read-only; note bodies are not stored on the server

**Decision.** RepoRead never writes to GitHub. The server stores document metadata (paths, titles, blob SHAs) and the user's own state, and fetches a note's exact blob by SHA whenever it renders or compares it; nothing is cached server-side. Every GitHub operation has a stated call ceiling; a failed call ends the operation without an alternative strategy.

**Consequences.** Opening a note online costs one GitHub request. A version GitHub no longer has (rewritten history) is reported as unavailable, never reconstructed. Highlights necessarily store their quoted text (ADR-11).

## ADR-03 — GitHub user tokens live only in server memory

**Context.** The GitHub App's user token is the only credential that reads private notes. Storing it at rest needs an encryption-key decision; giving it to the phone exposes it beside untrusted content.

**Decision.** The backend keeps the token in memory (Spring's authorized-client service) and gives the phone an opaque bearer session (hash stored, Keystore-encrypted on the phone). No refresh tokens, no installation-token fallback.

**Consequences.** Restarting the server or the token expiring requires signing in again (401 `SIGN_IN_REQUIRED`); database-only operations keep working. Account deletion leaves RepoRead holding no credential. Evidence: [Stage 0](reporead-stage0-boundaries.md#authentication-boundary).

## ADR-04 — The server renders; an isolated WebView displays

**Decision.** Java (CommonMark/GFM, escaped raw HTML, jsoup safelist) is the single owner of parsing, sanitizing, canonical block text, and limits. The phone shows that HTML in a WebView with app-bundled scripts only (highlight.js, Mermaid in strict mode), a CSP, blocked network/file/content access, no JavaScript bridge, and no credentials; repository images are fetched by native code.

**Consequences.** One parser defines what a selection means for both reader and server. Unsupported or oversized content is a visible error, not executed. Evidence: [Stage 0](reporead-stage0-boundaries.md#rendering-and-selection-contract).

## ADR-05 — Anchors are block ids and UTF-16 offsets into canonical text of one version

**Decision.** A selection is `{sourceBlobSha, blockId, startOffset, endOffset, exactText}` within one block, offsets in UTF-16 units of the Java-exported canonical text. The server verifies it against that exact version and derives context and heading path itself. Block ids are stable only within a version.

**Consequences.** Cross-block selections are refused. Every later version needs re-anchoring (ADR-06).

## ADR-06 — Re-anchor only on measured evidence; otherwise orphan

**Context.** US-07: a wrong attachment is worse than a lost one, but orphaning every light edit fails the story.

**Decision.** Resolve in order — whole block unchanged, unchanged position with unchanged context, exact quote singled out by context, bounded fuzzy match — with thresholds chosen by measurement on the user's real repository and real edit history (zero wrong attachments in 88,000+ labelled cases, ~95% of should-attach cases attached). Anything weaker is `ORPHANED`, keeping the original selection and context for manual reattachment. The original anchor never changes.

**Consequences.** Some lightly edited short passages orphan (one-word edits: 56% attached). Evidence: [Stage 4](reporead-stage4-record.md).

## ADR-07 — Document identity from complete snapshots

**Decision.** Each sync publishes one complete tree snapshot in a transaction. A path keeps its document; a document whose unique blob reappears at exactly one never-used path moved; a vanished document with user data whose content is alike enough (measured shingle similarity) moved and was edited. Absent paths are marked deleted, never removed; an incomplete tree is a failure, never evidence of deletion.

**Consequences.** Branch rewinds reconcile like any other snapshot. Identical-content duplicates are never merged. Evidence: [Stage 4](reporead-stage4-record.md).

## ADR-08 — Offline: a Room projection plus explicit pending changes

**Decision.** Room holds the server's complete lists and the latest rendered copy of each opened note, replaced only by complete responses. Local changes are rows marked pending and sent at explicit sync points (library open, Sync, note open): reading state is last-write-wins by the client's time; a highlight creation carries a client mutation id the server makes idempotent; edits and deletes are online with optimistic versions and visible conflicts. No background sync or retry framework.

**Consequences.** Offline creation never duplicates; offline edit/delete is not supported (Stage 3 scope decision). Evidence: [Stage 2](reporead-stage2-record.md), [Stage 3](reporead-stage3-record.md).

## ADR-09 — "Changed since last read" compares the version actually read, by source lines

**Decision.** The phone sends the version it last displayed (captured before the reader records the new one; its unsent saves can be newer than the server's) and the version on screen. The server diffs the two blobs' source lines (Myers, bounded) and maps changes to heading sections by position; oversized comparisons say so instead of listing part of them. "Recently changed" uses the time a refresh noticed the change, not commit times.

**Consequences.** Not AST-aware: a renamed heading is a removed plus an added section, and blank-line edits count. Evidence: [Stage 5](reporead-stage5-record.md).

## ADR-10 — Search is local substring matching over what the phone has saved

**Decision.** Search runs on the phone over note titles in the saved lists, the canonical text of saved notes (sent with each note), and highlights — SQLite `LIKE` with escaped wildcards, no full-text index or server search. The phone's saved data belongs to one account: signing in as another clears it first.

**Consequences.** Notes never opened on the phone match by title only; non-ASCII letters match case-sensitively. Adequate for one person's repository; revisit with FTS if it is slow or too literal.

## ADR-11 — Disconnecting deletes everything stored for that repository; account deletion deletes everything

**Decision** (the user's, Stage 6). Disconnect deletes, in one transaction under the sync lock, the connection, its documents, and the user's reading states, bookmarks, and highlights (whose quotes are repository content) on them; the phone removes its copies including unsent changes. Account deletion does that for every connection, plus sessions, sign-in codes, mutation records, the user, and the in-memory GitHub token. Neither calls GitHub: the user revokes RepoRead's authorization or uninstalls the App on GitHub.

**Consequences.** Reconnecting starts fresh; nothing is recoverable from RepoRead. Upstream note deletion is different: it keeps history (ADR-07).

## ADR-12 — Personal distribution: one signing key, backend over adb reverse

**Decision** (the user's, Stage 6). Every build is signed with one personal key kept outside the repository, so release and development builds install over each other. The release build keeps the loopback backend address reached through `adb reverse`; no hosted deployment for the MVP.

**Consequences.** Refresh and sync need the phone on wireless debugging with the Mac; reading saved notes and annotating work anywhere. Losing the key means uninstalling to update. Hosting the backend is a separate future decision.
