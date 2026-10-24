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
| F-11 | Library “Updated since you read” and “Recently changed” lists | US-02, US-09 | 4 — Git-aware reading |
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

Keep the Library’s **Updated since you read** and **Recently changed** lists, computed locally from saved document/reading metadata with no additional backend calls. Only section summaries were retired on 2026-10-19 (ADR-09).

**Evidence:** The developer can find notes changed since the previous study session and reread them.

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
| 5 | Notes changed since the version actually read | F-11 | Passed 2026-10-07; section summaries retired 2026-10-19 |
| 6 | Search, data controls, metrics, signed release, ADRs | F-12, F-13 | **Pending** |

Stage 6 is built and checked on the phone, along with two additions: a dark theme with a redesigned reader and app shell, and Obsidian `[[links]]` and `![[embeds]]`. Its gate still needs two weeks of voluntary use, at least 20 real highlights, the labelled fixture demo (deferred until after the two weeks), and a full review against F-01–F-13 and US-01–US-11.

## After the MVP

Post-MVP work started on 2026-10-07, before the Stage 6 gate. Each stage reverses or extends an MVP decision only with an explicit decision, recorded in the ADRs.

| Stage | What | Story | Status |
| --- | --- | --- | --- |
| P1 | Long and wide notes: outline and current section, footnotes, code Copy and Wrap, bars that hide while reading | US-12 | Built 2026-10-07; unused full-screen figures retired 2026-10-19 (audit PR1), in-page sideways scrolling retained; gate pending: reading long notes on the phone without switching to GitHub or the laptop because of layout |
| P2 | Every note saved on the phone, link previews, Linked from (ADR-19) | US-13 | Gate passed 2026-10-07 for the cases tried |
| P3 | LeetCode study view and Practice (ADR-20) | US-14 | Built 2026-10-09; its question source was replaced by interview banks (2026-10-13) and its question list by Practice sessions (2026-10-14). Gate pending: the developer uses it in real sessions and says whether it beats rereading |
| P4 | Review cards with phone-side scheduling, the Notebook (ADR-13) | US-15 | Built 2026-10-09; unused Markdown export retired 2026-10-19 (audit PR1); review moved into the Practice tab 2026-10-14; SM-2 replaced by a fixed gap ladder 2026-10-15. Gate pending: two weeks of real review sessions |
| — | GitHub sign-ins renewed, stored encrypted, and revoked on the last sign-out (ADR-03); hosted backend (ADR-14); releases through Obtainium (ADR-15) | — | Built 2026-10-13/14 |

## Out of scope

**Excluded from the MVP and still excluded:**

- Markdown authoring, WYSIWYG editing, Git commits, pull requests, or comments written back to GitHub.
- Collaboration, public profiles, or team knowledge management.
- AI summaries or chat with notes.
- Other Git providers, arbitrary binary formats, and rich drawing/canvas tools.
- iOS, Google Play distribution, and LaTeX/math rendering.
- Webhook-triggered sync, AST-aware diffing, Elasticsearch, Kafka, microservices, a generic sync engine, and cache or retry frameworks (beyond WorkManager's backoff for the phone's background sync and Save all, ADR-08, ADR-19).

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
| Scheduler | Fixed gap ladder (replaced SM-2 on 2026-10-15); a new card starts on its first step since 2026-10-21 (Hard 1, Good 3, Easy 7 days); Hard steps back one rung since 2026-10-23; consider FSRS only if review data shows a need (ADR-13) |
| Notebook paging | Not paged; revisit as the review log nears about 40,000 grades, past which the library is refused (422 `LIBRARY_TOO_LARGE`, ADR-13). On 2026-10-19 the real hosted library was 260,497 / 8,388,608 bytes (3.11%): 3 repositories, 100 reading states, 1 bookmark, 19 annotations, 12 reviews; document count was not logged. Revisit as bytes approach the ceiling; serialized bytes are not peak heap |
| Dependency advisories | Two low-severity KaTeX/Mermaid findings accepted at the 2026-10-19 audit; no dependency upgrade approved and Dependabot alerts remain disabled by user decision. The manual Spring-advisory review was not a transitive-graph scan; evidence is in [PR #81](https://github.com/jaxwong/reporead/pull/81) |
| AI-drafted questions | Intent recorded; not designed (ADR-20) |

## Known gaps

Noticed and deliberately not changed:

- Notes over 4,096 blocks cannot be opened or saved (one in the real repository).
- Save all completion counts pages, not separate image totals. Permanent content/image refusals count the page once as skipped; unreadable cache files or a full disk can still leave an image unavailable offline (local file handling is unchanged). Network/server/session failures do not report a successful run; see ADR-19.
- Highlights and cards share the server capacity (500 per document, 5,000 per user; bookmarks excluded). A capped creation stays at the head of the pending queue, so every later creation and its grades wait; each sync spends one GitHub blob call revalidating that same creation until the user deletes existing annotations to free capacity. Grades for already-acknowledged cards and reading/bookmark sends can still proceed. Existing annotations and exact replays are retained. Counts do not guarantee an 8 MiB library answer: large texts or an accumulating review log can still exceed that separate limit.
- One highlight per phrase is the phone's rule only: two highlights can still share a phrase when made on two devices, placed by Reattach, or moved there by re-anchoring, and duplicates saved before 2026-10-22 stay, as does a highlight saved then with surrounding whitespace, which a later selection of its text does not match (ADR-17).
- RepoRead Dev still receives its sign-in code through a custom scheme (`reporead-dev://auth`), which another app could also register: its backend on the Mac has no domain to verify. The release app uses a verified App Link ([backend README](../backend/README.md#sign-in-contract-android)).
- Bank-question identity is local to this phone's bank document and drawn Canonical text; a re-anchored line edit can retain its card, while a larger edit can leave an orphan beside a new question. Independently created cross-device cards can remain duplicates; no cross-device deduplication is promised (ADR-20).
- After a bank prompt edit, Review uses the current saved-bank prompt, but Notebook and the reader's notes panel still show the prompt saved on the card. Review also parses all saved bank pages before its first card; accepted in the PR4 branch audit, not changed by PR5.
- A card made in the reader before 2026-10-21, if any exists, is not a bank question: Review shows its saved prompt and offers its note, where it can be deleted. Whether the hosted database holds any was not checked (reading it was not permitted from the session).
- Review's completion count can under-report when the phone clock moves backwards during a session: the PR5 clock-ahead/restore check showed 0 reviewed although the real grade was accepted and scheduled. `Review.kt` filters the displayed count by the session start timestamp; grade persistence and scheduling succeeded; no completion-counter fix was made in PR5.
- A card on a removed or unsupported note cannot be deleted from Notebook: its only action is Open passage, while deletion belongs to the reader. Deferred until encountered on this phone; the containment is Notebook deletion through `PhoneSync.deleteAnnotation`, not editing.
- Unsent changes made before switching GitHub accounts can be sent under the new account. Accepted for one user with one account on this phone; revisit before a second account is used (ADR-22).

The ADRs record further limits: anchors (ADR-05), presentation-only page upgrades (ADR-18), the unpaged notebook and uneditable questions (ADR-13).

## Earlier implementation status

- **Save all background work (2026-10-18):** verified in [PR #68](https://github.com/jaxwong/reporead/pull/68); current behavior is owned by ADR-19/ADR-22, and audit PR2 supersedes its removal lifecycle.
- **Typed HTTP boundary (2026-10-18):** implemented and verified in [PR #70](https://github.com/jaxwong/reporead/pull/70); current contract and failure behavior are owned by ADR-26.
- **Navigation 3 (2026-10-19):** implemented and verified in [PR #72](https://github.com/jaxwong/reporead/pull/72); ADR-27 owns state compatibility, amended by audit PR1/PR2.
- **Screen work in its entry (2026-10-19):** implemented and verified in [PR #73](https://github.com/jaxwong/reporead/pull/73); ADR-28 owns entry work, amended by audit PR1/PR2/PR4.

## Audit PR1 — Feature retirements (2026-10-19)

Merged and verified in [PR #75](https://github.com/jaxwong/reporead/pull/75): section summaries, full-screen figures and Notebook export retired; saved user data and both Library change lists retained.

## Audit PR2 — Sessions keep the phone's notes (2026-10-19)

Merged and verified in [PR #76](https://github.com/jaxwong/reporead/pull/76): sign-out and account deletion end the session without clearing phone data; ADR-22 owns the retained one-account limitation.

## Audit PR3 — Reading positions and saved-page retirement (2026-10-19)

Merged and verified in [PR #78](https://github.com/jaxwong/reporead/pull/78): well-formed reading positions accepted, page format 4 and Room v16 retire unreadable saved pages without losing user work; PR #81 completed grade-clock and documentation corrections.

## Audit PR4 — Question identity and atomic first grade (2026-10-19)

Merged and verified in [PR #80](https://github.com/jaxwong/reporead/pull/80), with admission/recognition corrections in [PR #81](https://github.com/jaxwong/reporead/pull/81): prompt edits keep their cards, first grades are atomic, and confirmation covers only the Canonical line; cross-device duplicates and saved-prompt differences remain accepted limits.

## Audit PR5 — Closure check (2026-10-19)

Merged and verified in [PR #81](https://github.com/jaxwong/reporead/pull/81): local gates, isolated device suites and approved real-account smoke completed; the clock-rollback completion counter, accepted dependency findings and remaining live-system gaps are not closed by that evidence.

## Mechanical moves — Local verification (2026-10-20)

Merged and verified in [PR #82](https://github.com/jaxwong/reporead/pull/82): study/UI/folder code and tests moved to their owners, the reader–study cycle removed, and saved-UI-state format 4 resets navigation/drafts/sessions once without changing Room or pending work.

## Images in Save all (2026-10-20)

Merged and verified in [PR #83](https://github.com/jaxwong/reporead/pull/83): every listed saved page, including current text, gets its missing-image pass before progress; permanent refusals count the page once, other API failures reach the worker, and ADR-19 owns limits and lock costs.

Performance PR1 (2026-10-20, [PR #86](https://github.com/jaxwong/reporead/pull/86)): Save all and opening a note reuse unchanged text-only pages across repository commits. Pages containing `/repo-image/` or `image-blocked` still require a known matching snapshot; blob/path changes and render-format upgrades still fetch. The shared freshness rule uses already-loaded HTML, with no schema, API or concurrency change. Android gates and the isolated Pixel suite passed (185 tests); `PhoneSyncTest` coverage includes commit-only reuse, legacy snapshots, blocked-embed rebinding, image-only refresh and format upgrades. The real-account note-edit journal/count/wall-time check is deferred by user decision; no live speedup is claimed. That run should fetch the edited note plus any snapshot-dependent pages, not necessarily exactly one note.

Performance PR2 (2026-10-20, [PR #87](https://github.com/jaxwong/reporead/pull/87)): Practice and Review prepare a complete answer during the attempt and reveal it only after its native page is ready; the reader stays hidden until its opening position reaches a native frame. One owner now controls each page's visibility and rendering lifetime. Android gates and the isolated Pixel suite passed (191 tests), including the early-reveal and unready-reader regressions alone. No real-account recording was run. An earlier isolated unready-reader failure did not recur alone or in the full suite, and its omitted assertion diagnostics leave its cause unknown.

## Cleanup freeze (2026-10-20)

Implemented, locally verified and merged in [PR #84](https://github.com/jaxwong/reporead/pull/84) (`cleanup-freeze`): batch image sweeping, cancellation-protected review dismissal, visible bookmark rejection, midnight-aware Review completion, one page-script decoding boundary, one complete-page limit, shared OAuth/text rules, GitHub HTTP path redaction, and server-owned annotation capacity. Backend/Android gates, the isolated Pixel suite and release assembly pass; all six docs are reconciled with the code. Exact commands/results stay in the commit and PR, not this feature list. Deployment, release installation and the Stage 6 gate are not claimed by that PR.

## Follow-up groups as one question (2026-10-20)

Implemented and locally verified, then merged in [PR #85](https://github.com/jaxwong/reporead/pull/85) after the freeze, by user decision: a `Canonical path` group is one question, its heading and numbered items, and a path without items asks its heading; `Canonical answers:` is no longer accepted (ADR-20 Recognition). On the real notes InterviewBankCorpus went from 404 warnings to 150 (109 headings sharing another question's line, 38 practice-bank prompts without a line, and 3 plural lines until renamed); the remaining warnings are the notes' to fix. Android JVM gates pass; device suite, release and phone install not run.

## Closed question shape (2026-10-21)

Implemented by user decision and merged in [PR #92](https://github.com/jaxwong/reporead/pull/92): every bank H3 follows one closed shape (ADR-20 Recognition), Practice and Review show prompt material with the question and answer with the bank's Short answer and checklist, and linked sections open in the reader (ADR-20 Session). Android JVM gates pass. After the notes' conversion (notes commit `72a3447`), InterviewBankCorpus reads all 1,740 questions with no warnings, and every Canonical line is unchanged from before the conversion, so existing cards keep their history. The isolated device suite passes on the Pixel (191 tests); release, phone install and a real session on converted notes not run.

## Request ids and device logs (2026-10-21)

Implemented, locally verified and merged in [PR #94](https://github.com/jaxwong/reporead/pull/94) (sooner uploads: [PR #101](https://github.com/jaxwong/reporead/pull/101); a quieter journal: [PR #97](https://github.com/jaxwong/reporead/pull/97)): the server names each request (`RequestLoggingFilter`), every log line of it and its `X-Request-Id` header carry the id, one line records its outcome with the raw request target except for redacted authentication-callback queries, and the app logs the id with every failed answer (ADR-21). The device's own lines, crashes and Android's exit history are stored on the phone and sent to the server's log (`POST /api/app-logs`) when the app starts, when it leaves the screen, 2 s after a warning while no batch is claimed, and after a crash without the app being opened again (a failed upload waits for the next request, never a backoff); checked on the Pixel against the Mac backend, and the device suite passed (206 tests, including a page console error becoming a device line, the upload and store tests, and the request-id and published-route checks). The VM's journal setting (`backend/deploy/journald-reporead.conf`), installed there 2026-10-21, keeps logs across reboots with a 1 GB cap. Cockpit (`backend/deploy/cockpit-loopback.conf`, loopback only, through an SSH tunnel from `~/.config/reporead/reporead-logs`) shows them in a browser.

## Review by topic, without confirmation (2026-10-21)

Merged in [PR #96](https://github.com/jaxwong/reporead/pull/96), whose backend deployed on 2026-10-21; V16 then dropped the unused `annotations.checked_blob_sha` ([PR #98](https://github.com/jaxwong/reporead/pull/98), ADR-24's contract step); the backend deploy of 2026-10-21 (12:04 UTC, commit `6e742d6`, which contains V16) passed its health check. By user decision: the reader no longer makes cards, so a card is a bank question and is never confirmed in a new version; Review grades it from the bank's current saved version while that version still asks its Canonical line, and offers the bank otherwise (ADR-13 Safety). Review opens on the cards due by topic and a session covers one topic or all of them (ADR-20 Review). A new card starts on the ladder's first step (ADR-13 Scheduling). The API change ships with the matching app: the old app requires `checkedBlobSha` and cannot read the notebook from the new server until it updates (user decision, as ADR-24 allows). Verified locally: backend tests and formatting, app unit tests, lint, formatting, the reader assets and the contract tests; the device suite passed on the Pixel 8a (206 tests, RepoRead Dev from this branch).

## Bookmarks in their own table (2026-10-22)

Merged in [PR #102](https://github.com/jaxwong/reporead/pull/102): V17 moves bookmarks from `annotations` into `bookmarks`, `Bookmarks` owns them and their deletion on disconnect, and `BOOKMARK` leaves the published API (ADR-17). Verified locally: backend tests and formatting (a migration test moves a V16 bookmark with its time and runs the previous release's bookmark write and disconnect on the new schema; a deletion test disconnects a repository holding a bookmark the previous release left in `annotations`; a sync test keeps a moved note's identity for a bookmark alone), and app unit tests, lint, formatting and debug builds. Not run for that PR: the device suite. V17 reached the hosted database in the backend deploy of 2026-10-22 (11:45 UTC, commit `ce30c81`, which contains V17), which passed its health check. Open: ADR-17's contract step, now that this release is deployed.

## One highlight per phrase (2026-10-22)

Merged in [PR #105](https://github.com/jaxwong/reporead/pull/105), by user decision: the phone saves no second highlight on a phrase; the reader's selection leaves out surrounding whitespace, and a refused highlight leaves its phrase free. Highlight says the passage is already highlighted and Add note opens that highlight's note (ADR-17). Verified: app unit tests (`AnnotationEntityTest` covers what counts as one phrase), lint, formatting, and the device suite on the Pixel 8a (210 tests, including the new `PhoneSyncTest` and `ReaderStudyModeDeviceTest` cases). Not tried by hand in the reader on the phone.

## Search opens the match, and Find in note (2026-10-21)

Merged in [PR #90](https://github.com/jaxwong/reporead/pull/90) and [PR #91](https://github.com/jaxwong/reporead/pull/91) (ADR-10): a note-text search result opens its note at the matched text, drawn through the reader's highlight pipeline and in view before the page shows; one note gives one result; and **Find in note** (the reader's search icon) counts the further occurrences and steps through them. Verification is recorded in the PRs.

## Content move reads (2026-10-22)

Merged in [PR #104](https://github.com/jaxwong/reporead/pull/104) (ADR-07): a refresh recognizes a note that was moved and edited when its vanished documents plus new paths number up to 128 (8 before), reading the candidates' blobs 16 at a time. On the largest real reorganization (99 candidates) the reads took 36–37 s one at a time and 2.3–2.8 s at 16, measured from the Mac against real GitHub, not from the VM. The backend deploy of 2026-10-22 (08:00 UTC, commit `29f5be52`) passed its health check.

## Unsent count without reading positions (2026-10-22)

Merged in [PR #106](https://github.com/jaxwong/reporead/pull/106) (ADR-08): the Library's unsent count leaves out reading positions, which are still sent. Verified in the PR: `spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` exit 0, and the device suite on the Pixel 8a, `OK (209 tests)`.

## Session size and grade range, one owner each (2026-10-23)

[PR #109](https://github.com/jaxwong/reporead/pull/109), by user decision (ADR-13): the phone owns the review session size (`SESSION_LIMIT`, 40) and drops Room's `review_limits` (v19); the server alone checks a grade's range (`ReviewController` and the `review_log.grade` `CHECK`), and the phone's second check is deleted. Verified in the PR: `spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` and `:backend:spotlessCheck :backend:test`, exit 0. Device suite on the Pixel 8a (`am instrument`, 2026-10-23, debug APK sha256 e0c19f8f…): `OK (211 tests)`, including `SessionLimitMigrationTest` (v18 to v19). The ADR-24 follow-up, [PR #111](https://github.com/jaxwong/reporead/pull/111), removes the retired `sessionLimit` from the server response and published contract after that release was installed.

## Library bar names the selected tab (2026-10-24)

[PR #112](https://github.com/jaxwong/reporead/pull/112): the Library's top bar shows the selected tab's label instead of "Library" on every tab, and the Notebook is an icon in the bar beside Search instead of a text button above every tab's content. Verified in the PR: `spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest`, exit 0. Device suite on the Pixel 8a (`am instrument`, 2026-10-24, debug APK sha256 8a94c749…): `OK (211 tests)`; the bar was looked at on the phone.

## Naming and ownership (2026-10-21 to 2026-10-22)

Merged in [PR #95](https://github.com/jaxwong/reporead/pull/95), [PR #99](https://github.com/jaxwong/reporead/pull/99), [PR #100](https://github.com/jaxwong/reporead/pull/100) and [PR #103](https://github.com/jaxwong/reporead/pull/103): one [naming guide](code-style.md), Android packages named for their owners (`network`, `logging`, `database`), the device-log flow named alike on the phone and the server, and "sync" split into delivering pending changes and refreshing a repository snapshot. The PRs changed no route, JSON field, table or column; the one approved behaviour change, request targets keeping their query in the server's log, is in ADR-21.

## Not yet verified on the real system

Covered by automated tests only, or not at all:

- Cockpit on the VM (2026-10-21): installed, listening on `127.0.0.1:9090` only (`ss -ltn`), and `logview` reads the journal through the tunnel. The unit with `SyslogIdentifier=reporead-backend` was installed and the backend restarted the same day (the user ran the commands; their output was not checked here). The backend has been deployed since (its deploy job passed on 2026-10-21 and 2026-10-22); what Cockpit then shows for the priority codes, request ids and phone lines was not looked at here.
- Request ids and device logs on the hosted backend with the release app (2026-10-21): the Dev app on the Pixel sent its start-up exit history and its session's lines to the Mac backend, with each line's own version, and request ids are covered by `RequestLoggingFilterTest` and the device test `ApiTest`. A real crash (`am crash <pid>`, 2026-10-21, Dev app against the Mac backend) reached the server about 2 s later from a background start, the app not reopened, also right after a failed upload; a warning started its upload 2 s later, and with the backend stopped one failed upload was all that ran. An ANR or native crash reaching the server, and the release app against the hosted backend, are not tried. Retested on the Pixel 8a on 2026-10-22: the two device-log test classes passed (14 tests); a real crash uploaded from a background-only process with no activity reopened; with the receiver unavailable, one failed POST stayed one over 55 s and kept the batch, which the next app start replayed before sending its two stored failure warnings. The warning's 2 s delay was not rechecked. The journal setting is installed, but no reboot has been tried since.
- The format reset in the live `MainActivity` across an app update (ADR-27): Android 14 discarded state before the earlier update could exercise it. Isolated tests use the debug Activity boundary; `MainActivity` wiring is reviewed only. Account gates and local-data preservation are tested with loopback data, never by deleting the live phone copy.
- Connecting a repository and landing on its folder above the Library against live GitHub (ADR-27, ADR-28): a device test drives Add repository against a loopback server only.
- A second real GitHub user being refused this repository; truncated discovery against live GitHub.
- Airplane mode itself: it ends wireless debugging, so offline checks removed `adb reverse` instead.
- Save all stopped by a real execution limit or by the system killing the process mid-run: only forced job timeouts were tried (ADR-19). Real-account image-prefetch timing/storage, actual image HTTP timeouts and off-screen WorkManager lifecycle for the image pass remain unverified; PR #83 used isolated Room/loopback fixtures.
- Process death by the system (only force-stop and `am kill` were tried). PR5 verified a real Obsidian image embed online and with the Dev backend unreachable.
- Sign-ins surviving a deploy, and GitHub revocation on the last sign-out, with the real phone and hosted backend.
- Natural next-day Practice/Review cadence and two weeks of voluntary sessions. PR5 verified one real Practice grade and one Review grade, using the clock-ahead check to make the otherwise-not-yet-due card selectable; the completion-counter clock-rollback defect remains under Known gaps.
- A WebView renderer that stops for real: tested with `chrome://crash` only.
- Account operations survive screen recreation in the application scope. Their network/failure paths use isolated loopback fixtures, not live account deletion; an actual OS process kill during an account operation remains unverified.
- Audit PR2 retires pending-removal, Retry, ownerless-sign-in and retired-copy tests with those paths. Normal startup/disconnect image sweep coverage remains in `PhoneSyncTest`; account preservation and retained navigation are checked with isolated data. No live phone copy is deleted.
- Reading the stored session (2026-10-18, ADR-22): unit tests cover the stored-text checks, which Keystore exceptions delete it, and an unreadable one failing the request unsent; reading Keystore's transient and system-error marks from the exception is untested, since JVM tests cannot build a `KeyStoreException`; the exceptions were read in Android's sources, not provoked: historical device-suite evidence is in git, but none of its tests makes the Keystore fail.
- A deploy that fails on the VM: the deploy and backup scripts were installed there on 2026-10-17 and the deploy dump ran there, but a failing deploy was only checked in a container with stubbed services.
- The reading-position restore harness (`android/reader-web/check-restore.mjs`) has historical headless-Chrome evidence in git, including a deliberately broken position check, but has not run on the phone's WebView.

## Personal-project definition of done

1. At least one real personal GitHub notes repository is connected.
2. A signed APK is installed on the developer's physical Android phone without Google Play.
3. The Kotlin/Compose client uses the real Spring Boot backend.
4. The developer voluntarily uses RepoRead for at least two weeks.
5. Mermaid renders correctly and reading position reliably resumes.
6. At least 20 real highlights or annotations are created; source Markdown remains untouched.
7. A real note edit exercises re-anchoring without silently misplacing an annotation.
8. Offline reading and annotation work, including synchronization after reconnect.
9. A laptop edit marks its note as updated in the library.
10. Backend integration tests run against PostgreSQL; important decisions are documented as ADRs.
11. A local demo can run with clearly labeled fixtures if GitHub is unavailable.

## Demo evidence and interview focus

A short demo should show the same note in GitHub Mobile and RepoRead; folder browsing; Markdown, code, and Mermaid; a new annotation without a source-file change; re-anchoring after a real edit; an orphan after an ambiguous rewrite; the updated-note lists; and offline annotation followed by one successful sync. Then show the GitHub/source, Spring Boot/durable state, and Room/offline-cache boundaries.

The most useful code and tests to show are anchor resolution, complete-snapshot sync, idempotent mutation handling, optimistic annotation updates, GitHub permission and token handling, safe rendering, and annotation-survival integration tests—not generic CRUD. The demo should also answer why GitHub remains canonical, why a backend is needed, how duplicates, moves, force-pushes, conflicts, malicious Markdown, and GitHub outages are handled, what would fail at much larger scale, and what was deliberately not built.
