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

**Decision.** The backend keeps the token and GitHub's refresh token in memory (Spring's authorized-client service) and gives the phone an opaque bearer session (hash stored, Keystore-encrypted on the phone). GitHub App user tokens last 8 hours, so the backend renews one that expires within a minute before using it, as mobile apps with short-lived access tokens do (amended 2026-10-13; before, every expiry meant a new sign-in, about once a day). No installation-token fallback.

**Consequences.** Restarting the server, or GitHub refusing a renewal (the refresh token expired after about 6 months or was revoked), requires signing in again (401 `SIGN_IN_REQUIRED`); database-only operations keep working. A renewal GitHub does not answer fails that request (503 `GITHUB_UNAVAILABLE`) and the next one tries again. Account deletion leaves RepoRead holding no credential. Evidence: [Stage 0](reporead-stage0-boundaries.md#authentication-boundary), `GitHubTokenRefreshTest`.

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

**Decision** (the user's, Stage 6). Every build is signed with one personal key kept outside the repository, so release and development builds install over each other. The release build's backend address is superseded by ADR-14; debug builds keep the loopback backend reached through `adb reverse`.

**Consequences.** Losing the key means uninstalling to update.


## ADR-13 — Anchored cards; server review log, phone-only SM-2 scheduling

**Decision** (the user's, P4, 2026-10-09). CARD annotations reuse highlight anchors, re-anchoring, orphaning and idempotent offline creation. The question is written by the developer, or kept from a P3 prompt whose answer the developer selects explicitly. The server owns the immutable review log and the session ceiling (40); the phone owns the one pure SM-2 scheduler over the log so offline grading works. No scheduler dependency, persisted due dates, AI, inferred answers, background sync, or GitHub writes.

**Safety.** A card's confirmed answer SHA must match both the current note and its trusted location. Re-anchoring/reattachment clears confirmation. Every known note-version change blocks review until explicitly checked; this conservative rule includes changed-section uncertainty. A new grade of an outdated answer is rejected, but an identical already-committed replay remains successful. Grade writes use client UUIDs and serialize with the existing connection/sync lock. Offline review cannot know about edits that have not been refreshed.

**Data controls.** ADR-11 includes cards and review logs. Card deletion cascades its grades, disconnect removes the repository's cards/logs and phone copies (pending work and private export files included), and account deletion removes everything. Markdown share attachments leave GitHub untouched; copies delivered to other apps cannot be revoked.

**Evidence.** [P4 record](reporead-p4-record.md); its device and two-week real-use checks are separately stated, never inferred from a build.


## ADR-14 — Hosted backend on an Oracle Cloud Always Free VM; debug builds stay on the Mac

**Decision** (the user's, 2026-10-13). The release build talks to `https://reporead.duckdns.org`, an Oracle Cloud Always Free `VM.Standard.A1.Flex` (1 OCPU / 6 GB, home region `ap-singapore-1`) running the same backend and PostgreSQL 17.11, behind Caddy with a Let's Encrypt certificate. Debug builds keep `http://127.0.0.1:8081` through `adb reverse` for testing unmerged changes. The server still binds only to `127.0.0.1`; `REPOREAD_PUBLIC_URL` fixes the OAuth callback (never the request's `Host`), and forwarded headers are honoured from loopback so redirects and the session cookie stay on HTTPS. The Mac's database moves to the VM at switch-over so phone and server ids keep matching.

**Why this host.** Free hosts that sleep (Render, Koyeb) drop the in-memory GitHub token and Render deletes free databases; Fly.io and Railway have no free tier; Google's e2-micro has 1 GB for the JVM and database together. DuckDNS is free and on the Public Suffix List, so Let's Encrypt limits are not shared (sslip.io and nip.io are not listed).

**Consequences.** Refresh and sync work wherever the phone has internet. The backend is reachable from the internet for the first time; only Caddy (80/443) and SSH are open. Oracle may reclaim an Always Free VM idle for 7 days (CPU, network and memory all under 20%); RepoRead alone used ~16% of memory, so the backend commits a 1 GB heap at startup (`-Xms1g -XX:+AlwaysPreTouch` in its systemd unit), keeping memory near 33%. Oracle may also change free limits (halved in mid-2026). Every backend restart, including deploys, drops the in-memory GitHub token until sign-in. DuckDNS is a small volunteer service; losing the name means a new address and an app rebuild. Pushes to `main` that touch the backend deploy through GitHub Actions with a key that can only run the VM's deploy script, which rolls back an unhealthy jar; app builds are still installed by hand.
