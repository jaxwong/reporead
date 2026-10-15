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
| 0 | Authorization and renderer boundaries proven (ADR-04, ADR-05, ADR-16) | Foundations | Passed 2026-10-06 (user-reported) |
| 1 | One real note opens through the real backend | F-01–F-04 | Passed 2026-10-06 |
| 2 | Dependable reading, including offline | F-05, F-06 | Passed 2026-10-06 |
| 3 | Durable online and offline annotations | F-07, F-08 | Passed 2026-10-06 |
| 4 | Annotations survive edits or become explicit orphans | F-09, F-10 | Passed 2026-10-07 |
| 5 | Changes since the version actually read | F-11 | Passed 2026-10-07 |
| 6 | Search, data controls, metrics, signed release, ADRs | F-12, F-13 | **Pending** |

Stage 6 is built and checked on the phone, along with two additions the user asked for: a dark theme with a redesigned reader and app shell, and Obsidian `[[links]]` and `![[embeds]]`. Its gate still needs two weeks of voluntary use, at least 20 real highlights, the labelled fixture demo (deferred by the user until after the two weeks), and a full review against F-01–F-13 and US-01–US-11.

## After the MVP

The user chose to start post-MVP work on 2026-10-07, before the Stage 6 gate. Each stage reverses or extends an MVP decision only with an explicit user decision, recorded in the ADRs.

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

**Excluded from the MVP, built afterwards:** spaced-repetition review, approved by the user as P3 and P4 on 2026-10-09 (ADR-13, ADR-20).

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
| Drawing edge to edge | When the reader hides the system bars, the camera cutout area shows as a black band. Left for the user to decide |
| Phone storage eviction | None; every saved note is kept (ADR-19). Revisit if storage matters |
| Scheduler | Fixed gap ladder (replaced SM-2 on 2026-10-15); consider FSRS only if review data shows a need (ADR-13) |
| Notebook paging | Not paged; revisit as the review log nears about 40,000 grades (ADR-13) |
| KaTeX advisory | npm's only fix is downgrading Mermaid to 10.8.0; not applied, waiting for a Mermaid release |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- `android/reader-web/check-restore.mjs` keeps its own copies of reader functions.
- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all does not fetch images; an embedded image shows as unavailable offline until its note is opened online once.

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

The most useful code and tests to show are anchor resolution, incremental sync, idempotent mutation handling, optimistic annotation updates, GitHub permission and token handling, safe rendering, and annotation-survival integration tests—not generic CRUD. Be ready to explain why GitHub remains canonical, why a backend is needed, how duplicates, moves, force-pushes, conflicts, malicious Markdown, and GitHub outages are handled, what would fail at much larger scale, and what was deliberately not built.
