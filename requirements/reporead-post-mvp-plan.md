# RepoRead — Post-MVP Plan (proposal)

This is a proposed sequence of improvements to build **after** the first MVP. It is based on research into comparable apps, learning science, and mobile reading UX, plus measurements of the real notes repository and of real use. It changes no MVP scope: the [feature list](reporead-feature-list.md) owns MVP scope and its exclusions, and the [build plan](reporead-build-plan.md) owns the MVP sequence.

Nothing here starts until the Stage 6 exit gate passes and the user approves the stage. Several stages reverse a recorded decision; those are listed in [§5](#5-decisions-needed-before-starting) and need an explicit user decision first.

## 1. Goal

Make RepoRead the place where the developer **understands and retains** their notes, not just reads them. That means three things:

- reading long, wide technical notes comfortably on a phone;
- moving between related notes;
- practising recall using the questions the notes already contain.

**Why:** the MVP's study tools are highlighting, rereading, and resuming. The research rates these low for retention and finds they make students overconfident. Self-testing and spaced review work much better, and 39% of the notes already contain questions to test against.

## 2. Evidence

### 2.1 The notes repository

**What was measured:** `jaxwong/zw_obsidian` at commit `c9c80fa` (2026-10-07): 654 Markdown files, 6.2 MB.
**How:** grep/awk over a read-only shallow clone. Counts exclude fenced code where noted. Widths are estimates.

| Finding | Measure | Leads to |
| --- | --- | --- |
| Long notes | Lines per note: median 77, 90th percentile 327, max 2,323. Headings per note: median 5, 90th percentile 23, max 101 | Outline (P1) |
| Wide code | 15,019 code lines; **28%** are over 44 characters. About 40 fit (375 px of content at 14.4 px monospace on the Pixel 8a), so 28% is a lower bound | Copy, wrap toggle, full screen (P1) |
| Wide tables | 10,703 table rows in 252 notes. 50% have ≥ 4 columns; 45% are over 120 source characters (a rough proxy for width) | Full screen, landscape (P1) |
| Diagrams | 232 Mermaid diagrams in 115 notes; 79 are left-to-right | Zoom (P1) |
| Footnotes | 39 notes reference footnotes; 233 definitions outside code. The renderer has no footnote extension (`backend/build.gradle.kts` loads only tables, strikethrough, and task lists), so they show as literal `[^1]` text | Footnotes (P1) |
| Links | 148 `[[links]]` in 88 notes; 45 links to a heading in 20 notes | Previews, backlinks (P2) |
| Study sections | Notes containing each heading:<br>• `Questions this file answers`: 90 (607 questions)<br>• `Review and practice`: 29<br>• `Mistakes`: 168 (166 of the 204 LeetCode notes)<br>• `Problem`: 51<br>• `TLDR`: 27<br>• `Pitfalls`: 25<br>258 notes (39%) have at least one of `Questions…`, `Review and practice`, `Mistakes`, or `Problem` | Practice recall (P3) |
| Math | LaTeX outside code in about 4 machine-learning notes | Later (§4) |
| Not used | Callouts 0, Dataview 0, frontmatter tags 0, `==highlight==` about 0, raw `<br>` outside code 15 lines in 5 notes | Do not build (§4) |

### 2.2 Use so far (development database, 2026-10-07)

- **Reading:** 17 notes have a reading state: 6 test notes in `scratch/`, 4 in `core/`, 4 in `leetcode/`, 2 in `books/`, and 1 at the repository root.
- **Highlights:** 10 highlights, only 2 with a note attached. There are 3 bookmarks.
- **Search coverage:** search matches every note's title and path, but body text only for notes saved on the phone ([LocalStore.kt:174-180](../android/src/main/java/com/reporead/android/data/LocalStore.kt#L174-L180)). That is roughly 17 of 654 notes.

### 2.3 Research findings

Sources are listed in [§7](#7-sources).

- **Learning science:**
  - Dunlosky et al. (2013) rate practice testing and spacing "high utility", and highlighting, rereading, and summarization "low".
  - Testing beats restudy: meta-analyses report g ≈ 0.50 (Rowland 2014) and g = 0.61 (Adesope et al. 2017). Recall tests beat recognition tests.
  - Rereading makes learners believe they know the material better than they do (Roediger & Karpicke 2006; Karpicke et al. 2009).
  - Flashcards learners write themselves beat ready-made ones, d ≈ 0.29–0.45 (Pan et al. 2022).
  - Fill-in-the-blank items add little for coherent prose (de Jonge et al. 2015).
  - Prompts embedded in an essay helped on hard items: 42% recall without vs about 70% with (Quantum Country; small sample).
  - LLM-written review prompts: with the best model, 36% were unusable and 35% ready to use (Memory Machines, 2026).
- **Comparable apps:**
  - The most common mobile request on the Obsidian forum is a link preview that works by touch.
  - Every Markdown reader competitor ships an outline.
  - Users of Readwise and Kindle value two things most: review of past highlights, and a notebook view of all highlights.
  - GitVault's one review complains that it can't really be used offline.
- **Mobile reading UX:**
  - In Wikipedia's tests, users preferred a table of contents that stays available and a header showing the current section.
  - Wikipedia link previews raised the number of links followed per page by 20% on Android.
  - Adding a line-wrap toggle was GitHub Mobile's top community request.
  - Android names books as a good fit for immersive mode, with bars that reappear on a swipe.

## 3. Stage overview

| Stage | Result | Depends on | Reverses a recorded decision |
| --- | --- | --- | --- |
| P1 | Long and wide notes read comfortably | MVP | No |
| P2 | Every note on the phone; link previews; backlinks | MVP | Yes: build plan Stage 1, "Do not download the whole repository" ([build plan:96](reporead-build-plan.md)) |
| P3 | Practise recall from the notes' own questions | P1. The cross-note queue needs P2 | Borderline: the feature list excludes "spaced-repetition flashcards" ([feature list:61](reporead-feature-list.md)). P3 adds no scheduling, but confirm it |
| P4 | Spaced review of chosen passages; highlights notebook | P3 | Yes: spaced repetition is excluded from the MVP |
| — | Small independent items | MVP | No |

Recommended order: P1, then P3's in-note study view, then P2, then the rest of P3, then P4. Collect friction notes during the Stage 6 two-week use and re-rank this plan before P1 starts.

## P1 — Read long and wide notes comfortably

**Goal:** a 100-heading note with wide tables, code, and diagrams is easy to navigate and read on the phone.

### Build

1. **Outline and current section.**
   - A button in the top bar opens a sheet of the note's headings, with the current one marked. Tapping a heading jumps to it.
   - The top bar shows the current section's name as a subtitle.
   - The reader lists headings from blocks it already marks with `data-heading-N`. The app reads them through `evaluateJavascript`, the same way `position()` works today; the page still gets no native bridge. No backend change.
2. **Footnotes.**
   - ~~Add `org.commonmark:commonmark-ext-footnotes` 0.30.0~~ Built without it: the extension splits and moves definitions, changing blocks of unchanged blobs; see the [P1 record](reporead-p1-record.md).
   - Tapping a footnote reference shows its text in a sheet, without leaving the passage.
   - **Constraint:** an unchanged blob must keep the same canonical block text, or existing anchors break (ADR-05). Keep the `[^1]` source text in the block and hide it, as Obsidian links already do (`.wl-hidden`).
   - Bump `MarkdownRenderer.FORMAT` (now 2, [MarkdownRenderer.java:52](../backend/src/main/java/com/reporead/document/MarkdownRenderer.java#L52)) so saved copies are fetched again.
3. **Code blocks.** Code blocks that overflow get a copy button and a wrap toggle.
   - **Constraint:** controls sit outside `[data-block-id]`, because the reader fails when a block's text changes ([reader.js:9-11](../android/reader-web/reader.js#L9-L11)).
   - Native actions such as copying to the clipboard go through an intercepted same-origin path, the pattern used by `/note-link` ([Reader.kt:340](../android/src/main/java/com/reporead/android/reader/Reader.kt#L340)). Do not add a JavaScript bridge.
4. **Full-screen tables and diagrams.**
   - Tapping a table or a Mermaid diagram opens it full screen, with pinch zoom and landscape.
   - The full-screen view has the same isolation as the reader: no bridge, and nothing loaded from outside the page's own origin.
   - Highlights are created only in the reader.
5. **Bars hide while reading.** Scrolling down hides the top bar; scrolling up shows it again. System bars appear briefly on a swipe.
   - Whether the WebView passes scrolling to Compose is not verified. If it doesn't, drive this from the WebView's native scroll callback.

### Verify

- **Outline:** in the longest note (2,323 lines, 101 headings), open the outline, jump to a heading, and check the current-section subtitle.
- **Footnotes:**
  - In a note with footnotes, the canonical-text check passes, existing highlights in that note still appear, and tapping a reference shows its footnote.
  - After the format bump, a saved copy is fetched again online. Offline, it is shown as a saved copy.
- **Wide content:** the widest table and a left-to-right diagram open full screen in landscape, and code copies exactly.
- **Display settings:** check at the largest Android font scale (200%; [Reader.kt:336](../android/src/main/java/com/reporead/android/reader/Reader.kt#L336) maps font scale to `textZoom` linearly), in light and dark mode, and after rotation.

**Exit gate:** the developer reads long technical notes on the phone without switching to GitHub or the laptop because of layout.

## P2 — Every note on the phone, link previews, and backlinks

**Goal:**

- every note is readable offline and searchable by its text, not only the notes already opened;
- links can be previewed without leaving the page;
- each note shows the notes that link to it.

### Build

1. **Download all notes.** This needs the decision in §5 first. The recommended approach:
   - The phone fetches, through the existing `GET /api/documents/{id}/content` (ceiling: 1 GitHub call each), every note whose saved copy is missing or older than the listed blob.
   - After the first run, only changed notes are fetched.
   - The server owns how many notes one request may cover; state that ceiling in the [backend README](../backend/README.md#api) before building.
   - The first run costs about 654 GitHub calls. The [GitHub rate-limit docs](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) give 5,000 requests per hour for user tokens; this has not been checked against this App's token.
   - Bodies stay off the server (ADR-02).
   - **Not recommended:** the archive endpoint. It answers `302` to `codeload.github.com` (checked 2026-10-07 on a public repository). The backend follows no redirects and caps GitHub responses at 1 MiB, and the archive also carries files that aren't notes.
2. **Search.** Once notes are saved, the existing local search (ADR-10) covers them with no change. It states how many notes are saved, for example "Searching 654 of 654 notes".
3. **Link preview.** Tapping a note link opens a sheet showing:
   - the target's title and folder;
   - the start of the note, or of the linked section, from the saved copy;
   - an Open button.

   It uses the existing resolver, [Links.kt:11](../android/src/main/java/com/reporead/android/reader/Links.kt#L11). Use a tap, not a long-press, because long-press in the WebView selects text.
4. **Backlinks.**
   - A "Linked from" list in the notes panel, computed on the phone from saved notes' links with the same resolver.
   - When not every note is saved, it says it covers only the saved ones.
   - This belongs on the phone because the server keeps no bodies.

### Verify

- **Offline:** in airplane mode after a full download, open a note never opened before and search a word that appears only in its body.
- **Repeat runs:**
  - A second run with no changes fetches 0 notes.
  - After a laptop edit to one note, only that note is fetched.
- **Failures:**
  - If GitHub fails part-way, notes already saved stay, the run reports how many it saved, and no partial note is stored.
  - A note over the size limits is reported, not skipped silently.
- **Data controls:** disconnecting deletes the downloaded notes (ADR-11).
- **Links:**
  - A link whose name matches two notes asks which note to open.
  - A heading link previews the right section.
- **Storage:** measure rendered storage on the phone after a full download.

**Exit gate:** with the backend unreachable, every note opens and is found by its text; links preview offline.

## P3 — Practise recall from the notes' own questions

**Goal:** turn the questions already in 258 notes into active recall. No writing to the notes, no AI, and no new authoring.

### Build

1. **Study view in the reader.** A toggle, shown only on notes with a recognized study heading.
   - **LeetCode notes:** `Problem` stays visible. `Approach`, `Brute Force Approach`, `Optimized Approach`, `Complexities`, and `Mistakes` collapse until tapped.
   - **Core notes:** `Questions this file answers` and `Review and practice` items appear one at a time, followed by "Read the answer", which shows the note.
   - **Constraints:**
     - Collapsing is CSS only; block text stays unchanged.
     - Saving and restoring the reading position must handle collapsed blocks, as it already does for Mermaid sources ([reader.js:83-89](../android/reader-web/reader.js#L83-L89)).
     - Highlighting works inside revealed sections.
2. **Practice queue in the Library.**
   - A list of question items from saved notes, shuffled across notes (interleaving), with a folder filter.
   - Each item opens its note at that question.
   - It is complete only once P2 has saved every note; until then it says how many notes it covers.
3. **Recognized headings.** A fixed list taken from the measurement in §2.1, not a user setting. Change it only when the notes change.

P3 stores no scores and no schedule; spacing is P4.

### Verify

- **LeetCode note in study view:**
  - The right sections collapse and expand.
  - The canonical-text check passes.
  - A highlight can be made in a revealed section.
  - Position is saved and restored with sections collapsed.
- **Other notes:** a note without study headings shows no toggle.
- **Queue:** built offline from saved notes, with an honest count.

**Exit gate:** the developer uses the study view or the queue in real sessions during daily use, and reports whether they prefer it to rereading.

## P4 — Spaced review of chosen passages, and the highlights notebook

**Goal:** schedule review of what the developer chooses to remember, and survive note edits as highlights already do.

### Build

1. **Cards.**
   - **From a highlight ("Make a question"):** the developer writes the question; the answer is the highlighted passage, shown in context.
   - **From a P3 question item ("Add to review").**
   - A card is anchored like a highlight, so it gets re-anchoring, orphaning, and idempotent offline creation from Stages 3–4.
   - It is proposed as a new annotation type. The `annotations.type` check constraint would need a migration, which needs approval.
2. **Review queue.**
   - Due cards, shuffled across notes, with a capped session. A median review takes about 6 seconds, so roughly 40 cards fit in 5 minutes; that is an estimate.
   - Each card goes: question → recall → reveal → grade.
   - Grades made offline are pending changes, synced through the Stage 3 idempotency path.
3. **Scheduler.**
   - **One owner:** the review log is durable on the server. Due dates come from one pure function over that log, run on the phone so offline review works. The server stores the log and does not schedule.
   - **Algorithm:** SM-2 (no new dependency) or an FSRS Kotlin port (lower error in the public benchmark; a new dependency that needs justification). See §5.
4. **Changed answers.** A card is flagged "Check this card", not scheduled, when its passage was re-anchored, orphaned, or falls in a section listed as changed since last read. An outdated answer is never drilled.
5. **Highlights notebook.**
   - Every highlight and card across the repository, filterable by folder, note, or orphan status, with a jump to the passage.
   - Export as Markdown through the Android share sheet; nothing is written to GitHub.

### Verify

- **Offline sync:** review 10 cards offline, reconnect, and each grade is stored once, including when it is sent again.
- **Edits:**
  - A laptop edit to a card's passage flags the card.
  - A rewrite orphans it, and it can be reattached.
- **Data controls:** disconnect and account deletion remove cards and review logs (ADR-11).
- **Export:** an exported Markdown file round-trips its quotes exactly.

**Exit gate:** at least two weeks of real review sessions with the cards. Source Markdown is untouched.

## Small independent items

These can be built any time after the MVP.

- **Share to RepoRead.** Share a `github.com/.../blob/...` URL from a connected repository to open that note; any other URL gets a clear message.
  - The manifest has no share filter today ([AndroidManifest.xml:14-24](../android/src/main/AndroidManifest.xml#L14-L24)).
  - RepoRead can't be the default handler for github.com links: Android 12+ requires a verified domain.
- **Continue reading shortcut.** A dynamic launcher shortcut to the last note read.
- **Typography sheet.** Line height and font family. No justified text.

## 4. Later, and not planned

**Later, if real use asks for it:**

- **Math (KaTeX).** About 4 notes use it, and KaTeX already carries an open advisory through Mermaid ([reader-web README](../android/reader-web/README.md)). It is also an MVP exclusion.
- **Text-to-speech** that announces or skips code and tables.
- **A home-screen widget.**
- **AI-drafted questions:** about a third were unusable in the 2026 evaluation, and they need the network. If built, they produce drafts the developer edits; nothing enters review automatically.

**Not planned:**

- **Graph view:** little value on a phone screen.
- **Swiping between notes:** clashes with code and tables that scroll sideways and with the system back gesture.
- **Justified text.**
- **Callouts, Dataview, frontmatter tags:** 0 uses measured.
- **AI chat, collaboration, editing:** MVP exclusions, still excluded.

## 5. Decisions needed before starting

| Decision | Options | Recommendation | Before |
| --- | --- | --- | --- |
| When post-MVP work starts | Now / after the Stage 6 gate | After the gate; log reading friction during the two weeks and re-rank this plan | P1 |
| Footnotes' canonical text | Keep the source text hidden / change canonical text | Keep it hidden, as wikilinks do; changing it breaks existing anchors | P1 |
| Reverse "Do not download the whole repository" | Per note through the content route, incremental / repository archive | Per note: existing route and limits; the archive needs redirects and over 1 MiB | P2 |
| Phone storage for every note | Keep all / cap and evict | Measure rendered size first; the build plan left eviction to real use ([build plan:237](reporead-build-plan.md)) | P2 |
| Study features vs the "spaced-repetition flashcards" exclusion | Approve P3 only / P3 and P4 / neither | Approve P3 first; decide P4 after using P3 | P3, P4 |
| Recognized study headings | The measured list in P3 / other | The measured list, as constants | P3 |
| Card storage | New annotation type plus migration / separate table | New annotation type, reusing anchors and idempotency | P4 |
| Scheduler | SM-2 / FSRS port | SM-2 first; switch only if review data shows a need | P4 |
| Cards on disconnect | Delete, per ADR-11 / keep | Delete, as ADR-11 does for everything else | P4 |

## 6. Rules that carry over

- GitHub stays canonical and read-only. Nothing in this plan writes to it.
- **Canonical text:** a rendering change keeps canonical block text identical for an unchanged blob (ADR-05). Bump `MarkdownRenderer.FORMAT` whenever saved pages must be fetched again.
- **No JavaScript bridge:** the page reaches native actions only through intercepted same-origin paths.
- **Server-owned limits:** each new route states its GitHub-call ceiling in the backend README before it is built. The server owns limits, a failed call ends the operation, and nothing falls back to another strategy.
- **Verification:** follow [§4 of the build plan](reporead-build-plan.md#4-verification-and-delivery-discipline) and AGENTS.md. Use phone evidence, record exact commands, and never use `connectedDebugAndroidTest` on the phone used for reading.

## 7. Sources

**Learning science**

- Dunlosky et al. 2013, via APS: https://www.psychologicalscience.org/news/releases/which-study-strategies-make-the-grade.html
- Roediger & Karpicke 2006: https://psychology.ecu.edu/wp-content/pv-uploads/sites/216/2019/03/Roediger-Karpicke-2006.pdf
- Adesope et al. 2017 (Rowland 2014 is discussed there): https://education.wsu.edu/documents/2018/01/rethinking-use-tests.pdf/
- Pan et al. 2022, via Matuschak's notes: https://notes.andymatuschak.org/z3X7hMWZcQnyMdgNevS5BBq
- de Jonge et al. 2015: https://repub.eur.nl/pub/89344
- Cepeda et al. 2006: https://www.evullab.org/pdf/CepedaPashlerVulWixtedRohrer-PB-2006.pdf
- FSRS benchmark: https://github.com/open-spaced-repetition/srs-benchmark
- FSRS ports: https://github.com/open-spaced-repetition/awesome-fsrs
- Quantum Country results: https://notes.andymatuschak.org/zHMuXkWrGpDVQ6j82YNK2iD
- Writing good prompts: https://andymatuschak.org/prompts/
- Memory Machines 2026 report: https://memory-machines.com/report

**Comparable apps**

- Obsidian forum, mobile page preview request: https://forum.obsidian.md/t/page-preview-in-obsidian-mobile-app/68622
- Obsidian 1.11.6 changelog: https://obsidian.md/changelog/2026-01-26-mobile-v1.11.6/
- Obsidian Spaced Repetition plugin: https://community.obsidian.md/plugins/obsidian-spaced-repetition
- Readwise Mastery: https://docs.readwise.io/readwise/guides/mastery
- Read.md: https://apps.apple.com/us/app/read-md/id6760943472
- GitVault: https://apps.apple.com/us/app/id6759488713
- Vault Reader: https://apps.apple.com/us/app/vault-reader-markdown-notes/id6762577564
- Quartz: https://quartz.jzhao.xyz/

**Mobile reading UX**

- Wikipedia sticky header and table-of-contents testing: https://www.mediawiki.org/wiki/Reading/Web/Desktop_Improvements/Repository/Sticky_Header_and_Table_of_Contents_User_Testing
- Wikipedia Page Previews: https://www.mediawiki.org/wiki/Page_Previews
- GitHub Mobile line-wrap toggle: https://github.blog/news-insights/product-news/even-better-code-review-in-github-for-mobile/
- Android immersive mode: https://developer.android.com/training/system-ui/immersive
- Android 12 web-link behavior changes: https://developer.android.com/about/versions/12/behavior-changes-all
- Android 14 font scaling: https://developer.android.com/about/versions/14/features
