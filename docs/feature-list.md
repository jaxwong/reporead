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
| F-11 | Recently changed notes and section-level “changed since last read” | US-02, US-09 | 4 — Git-aware reading |
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

Add recently changed notes and section-level changes since the last-read version.

**Evidence:** The developer can go directly to material changed since the previous study session.

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
| 5 | Changes since the version actually read | F-11 | Passed 2026-10-07 |
| 6 | Search, data controls, metrics, signed release, ADRs | F-12, F-13 | **Pending** |

Stage 6 is built and checked on the phone, along with two additions: a dark theme with a redesigned reader and app shell, and Obsidian `[[links]]` and `![[embeds]]`. Its gate still needs two weeks of voluntary use, at least 20 real highlights, the labelled fixture demo (deferred until after the two weeks), and a full review against F-01–F-13 and US-01–US-11.

## After the MVP

Post-MVP work started on 2026-10-07, before the Stage 6 gate. Each stage reverses or extends an MVP decision only with an explicit decision, recorded in the ADRs.

| Stage | What | Story | Status |
| --- | --- | --- | --- |
| P1 | Long and wide notes: outline and current section, footnotes, code Copy and Wrap, full-screen tables and diagrams, bars that hide while reading | US-12 | Built 2026-10-07; gate pending: reading long notes on the phone without switching to GitHub or the laptop because of layout |
| P2 | Every note saved on the phone, link previews, Linked from (ADR-19) | US-13 | Gate passed 2026-10-07 for the cases tried |
| P3 | LeetCode study view and Practice (ADR-20) | US-14 | Built 2026-10-09; its question source was replaced by interview banks (2026-10-13) and its question list by Practice sessions (2026-10-14). Gate pending: the developer uses it in real sessions and says whether it beats rereading |
| P4 | Review cards with phone-side scheduling, the Notebook, Markdown export (ADR-13) | US-15 | Built 2026-10-09; review moved into the Practice tab 2026-10-14; SM-2 replaced by a fixed gap ladder 2026-10-15. Gate pending: two weeks of real review sessions |
| — | GitHub sign-ins renewed, stored encrypted, and revoked on the last sign-out (ADR-03); hosted backend (ADR-14); releases through Obtainium (ADR-15) | — | Built 2026-10-13/14 |

## Out of scope

**Excluded from the MVP and still excluded:**

- Markdown authoring, WYSIWYG editing, Git commits, pull requests, or comments written back to GitHub.
- Collaboration, public profiles, or team knowledge management.
- AI summaries or chat with notes.
- Other Git providers, arbitrary binary formats, and rich drawing/canvas tools.
- iOS, Google Play distribution, and LaTeX/math rendering.
- Webhook-triggered sync, AST-aware diffing, Elasticsearch, Kafka, microservices, a generic sync engine, and cache or retry frameworks (beyond WorkManager's backoff for the phone's background sync, ADR-08).

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
| KaTeX advisory | npm's only fix is downgrading Mermaid to 10.8.0; not applied, waiting for a Mermaid release |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all does not fetch images; an embedded image shows as unavailable offline until its note is opened online once.
- RepoRead Dev still receives its sign-in code through a custom scheme (`reporead-dev://auth`), which another app could also register: its backend on the Mac has no domain to verify. The release app uses a verified App Link ([backend README](../backend/README.md#sign-in-contract-android)).
- A reading position the server refuses (for example, with the phone's clock more than 5 minutes ahead) stays pending, counted as waiting, until a newer one replaces it: reading rows have no refusal mark, unlike highlights and grades. Fixing it is a Room migration.
- A bank question is matched to its card by bank, prompt and answer line, so renaming its heading or editing its Canonical line makes it a new question; grading it then starts a second card on the same answer.
- Exports are deleted at each app start, disconnect, sign-out and account deletion, so an app that reads a shared export only after that finds it gone; sharing again writes a new one.
- A sign-out cut short, or an account deletion cut short after the server confirmed it, is finished at the next start without saying which it was. A deletion cut short before its answer keeps the phone's copy, and the next start says nothing about it; deleting again shows whether the account still exists (a 401 if it is gone).
- A late change for a note no longer listed (just after a clear or a disconnect) is dropped without telling the user, who has just removed that note's data.
- Saved pages predating the server's complete-page limit are not rewritten or deleted automatically. New content is limited to 1 MiB of UTF-8 across HTML, search text and metadata; complete 2 MiB string payloads were reproduced as unreadable in Room on the Pixel 8a, Android 14. Cache/page-format evolution is tracked in the [remediation plan](audit-remediation-plan.md#step-4--complete-saved-page-and-reading-state-transitions-a4-a6).

The ADRs record further limits: anchors (ADR-05), the unbumped page format (ADR-18), the unpaged notebook and uneditable questions (ADR-13).

## Not yet verified on the real system

Covered by automated tests only, or not at all:

- A branch rewind or a missing old blob against live GitHub (`SINCE_UNAVAILABLE`), and a too-large comparison.
- A second real GitHub user being refused this repository; truncated discovery against live GitHub.
- Airplane mode itself: it ends wireless debugging, so offline checks removed `adb reverse` instead.
- An Obsidian image embed on the phone, and process death by the system (only force-stop and `am kill` were tried).
- Sign-ins surviving a deploy, and GitHub revocation on the last sign-out, with the real phone and hosted backend.
- Practice and Review with real grades (fixture tests only), a successful **Answer is still right** against the real server, and the Practice tab's due badge.
- Re-measuring anchoring after the 2026-10-09 look-alike fix (ADR-06).
- A WebView renderer that stops for real: tested with `chrome://crash` only.
- Signing out or deleting the account while the phone rotates: both now run in the application's scope, checked by reading the code; the root screen uses the app's own session and database, which tests never touch.
- The pending removal and the start's sweep, on the phone: `AccountTest` drives `Account` with `SessionStore`, `Api`, `Sync` and Room against a loopback server (sign-out with an unrevokable or unreadable session, duplicate taps, refused, 401, unreachable, confirmed and interrupted deletion, a removal finished at the next start, files left with **Retry** and sign-in waiting, late local changes, sign-in with and without a recorded owner); with Sync's clear and disconnect tests the device suite passes on the phone (100 of 100, 2026-10-18). The root screen's gate and a rotation during an operation were checked by reading the code only, not on screen. No test produces a database failure while clearing.
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
9. A laptop edit appears in the mobile changed-since-last-read workflow.
10. Backend integration tests run against PostgreSQL; important decisions are documented as ADRs.
11. A local demo can run with clearly labeled fixtures if GitHub is unavailable.

## Demo evidence and interview focus

A short demo should show the same note in GitHub Mobile and RepoRead; folder browsing; Markdown, code, and Mermaid; a new annotation without a source-file change; re-anchoring after a real edit; an orphan after an ambiguous rewrite; changed-since-last-read; and offline annotation followed by one successful sync. Then show the GitHub/source, Spring Boot/durable state, and Room/offline-cache boundaries.

The most useful code and tests to show are anchor resolution, complete-snapshot sync, idempotent mutation handling, optimistic annotation updates, GitHub permission and token handling, safe rendering, and annotation-survival integration tests—not generic CRUD. The demo should also answer why GitHub remains canonical, why a backend is needed, how duplicates, moves, force-pushes, conflicts, malicious Markdown, and GitHub outages are handled, what would fail at much larger scale, and what was deliberately not built.
