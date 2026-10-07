# RepoRead — P1 verification record (long and wide notes)

Evidence for P1 of the [post-MVP plan](reporead-post-mvp-plan.md#p1--read-long-and-wide-notes-comfortably). Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs. **The exit gate has not passed**: it needs the developer to read long technical notes on the phone without switching to GitHub or the laptop because of layout.

## User decisions (2026-10-07)

- Start P1 now, before the Stage 6 exit gate (the plan recommended after it).
- Accept the plan's P1 recommendations, including keeping footnote source text hidden.
- Run the phone check with the debug build over DevTools, then reinstall the release build.

## Built

| Item | What | Where |
| --- | --- | --- |
| Outline and current section | Outline sheet of the page's headings with the current one marked; tapping one jumps to it. The top bar's subtitle is the section at the top of the screen | `reader/Outline.kt`, `reader.js` `outline()` |
| Footnotes | `[^label]` references and `[^label]: …` definitions marked after rendering, source text hidden; the number opens the definition in a sheet. Page format 3 | `MarkdownRenderer.markFootnotes`, `reader.js`, `reader.css` |
| Code blocks | Language, **Copy** (intercepted `/copy-code`, copies the canonical text without the final line break) and **Wrap** while wider than the screen | `reader.js` `addCodeTools`, `Reader.kt` |
| Full screen | **Full screen** above wide tables and every rendered diagram; a navigation entry that reloads the saved copy in a second isolated page and keeps only that figure; pinch zoom; follows the phone's rotation | `reader/Figure.kt`, `reader/ReaderPage.kt` |
| Bars hide while reading | Top bar and system bars hide after a swipe down and return after a swipe up or at the top | `Reader.kt` |

**Deviation from the plan — footnotes without the extension.** The plan proposed `commonmark-ext-footnotes`. Measured on `jaxwong/zw_obsidian` at `c9c80fa`: 233 definitions, of which 190 follow another definition and 43 a blank line; none follow prose or continue on indented lines, and no reference is followed by `:`. Without the extension CommonMark makes consecutive definitions one paragraph, and the extension would split and move them, changing blocks of unchanged blobs (ADR-05). So footnotes are marked after rendering, like Obsidian links, with no new dependency. Numbers follow definition order because definitions stay in place; reference order (GitHub's) produced a list reading 1, 2, 15, 3….

## Automated (2026-10-07)

- `./gradlew :backend:test --no-daemon`: exit 0, **169 tests, 0 failures, 3 skipped** (the opt-in harnesses). Adds footnotes leaving canonical text unchanged, numbering, undefined labels, code and link text untouched, and a paragraph that does not start with a definition.
- `ANDROID_HOME=… ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon` and `… :app:testDebugUnitTest :app:assembleRelease --no-daemon`: exit 0 (adds `OutlineTest`).
- **Whole notes repository, before and after** (`RenderedCorpus`, new opt-in harness): `REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon`, run on the renderer before footnotes and after; `diff -r before/blocks after/blocks` exit 0: **canonical blocks identical for all 653 renderable notes**. 382 references and 233 definitions in 43 notes are marked, matching a grep count of the sources.
- **Reader pages in headless Chrome** at Pixel 8a size with the real bundle (scratchpad harness, not committed), all 653 pages: every page `ready` with 0 canonical-text mismatches; 1,673 code blocks get tools, none inside a block, each copy equal to the block's canonical text minus the final line break; 1,348 code blocks overflow and show Wrap, and Wrap removed the overflow in all 307 notes tried; 415 of 860 tables overflow and show Full screen, all 230 rendered diagrams do; `figure()` isolated the figure in 257 of 257 notes; tapping every footnote reference at both edges and the middle opened its own definition (1,146 taps, light and dark).

## On the phone (2026-10-07)

Pixel 8a, debug build over the release build (same key, data kept), backend restarted on the new code (the user signed in again).

- **Outline:** `TerraLens_Academy_Business_Plan.md` (1,575 lines, 101 headings) opened with the subtitle "2.2 Why an opportunity may still exist…"; the outline marked it; tapping "3.1 The basic system" jumped there and the subtitle followed. The plan's 2,323-line note could not be used: the server rejects it (4,096-block limit).
- **Copy:** Android's clipboard preview appeared; the app logged 452 characters, equal to the source fence without its final line break. **Wrap** wrapped the long line and toggling did not move the page.
- **Footnotes:** `core/databases/knowledge-architecture-research.md` fetched as format 3, `ready` with 0 mismatches (18 references, 22 definitions); a real tap on "2" opened definition 2. A selection spanning two references captured offsets whose canonical slice equals the exact text (read with `capture()`; nothing saved). The 4 highlights of `01-api-boundaries-and-contracts.md` were all drawn on its format-3 page (its fifth server row is a bookmark). No highlighted note has footnotes, so "existing highlights in a footnote note" was not checkable on real data.
- **Full screen:** the widest table (`docker-compose env resolution.md`) opened alone with system bars hidden. The user rotated to landscape and back and pinch-zoomed ("looks good"): the figure was rebuilt once per rotation in the same process, no loop; closing restored the reader exactly and RepoRead's requested orientation was `UNSPECIFIED` again.
- **Bars:** hide on a swipe down and return on a swipe up (see defects).
- **Reading position after a background kill** (HOME, then `am kill`, then relaunch): restored exactly (`mode=exact`) with the bars shown (block 181) and hidden (block 186).
- **Display:** dark mode throughout; at font scale 200% the reader rendered with 0 mismatches and no sideways overflow; the font scale was set back to 100%.
- The release build was reinstalled afterwards.

## Defects found and fixed

- **Bars oscillated under the finger** (`a9e846a`): showing the bars moved the WebView under a finger still on it, the page read that as scrolling the other way and hid them again. Page timeline: viewport 800 → 854 px and `scrollY` 6281 → 6321 while the finger moved up; repeated upward swipes drifted the page over 1,000 px down. Bars now change only when the finger lifts (or a fling reaches the top); afterwards swipes showed 0 reversals and a constant viewport while the finger was down.
- **Footnote sheet clipped to its padding** (`5d49f99`): in the app's WebView `100vh`, `100dvh` and `100svh` measure 0, so `max-height: 45vh` was 0; headless Chrome resolves `vh` normally. Sized in percent of the viewport; 86 px for 85 px of content on the phone.
- **Adjacent references' tap areas overlapped** (before commit, in headless Chrome): `[^a][^b]` opened the wrong definition; each reference is now widened only by its own padding.

## Not done or not verified

- The exit gate: real reading on the phone.
- A saved copy in format 2 being fetched again: the user's Sign out (before signing in again) had cleared the phone's copies, so none existed; the check is the same `renderFormat` comparison verified in Stage 6, with the constant raised to 3.
- A left-to-right diagram full screen on the phone (checked in headless Chrome in landscape only).
- Light mode on the phone (headless Chrome only).
- When the system bars hide, the area of the camera cutout shows as a black band, because the app is not drawn edge to edge. Left as is; a design decision for the user.
- `venti/eta model/data diagnostics/negative_difference_lanes.md` cannot be opened: over the server's 4,096-block limit. Unchanged.
- One unexplained event: after the system killed the app at 19:41:59 (before the bars fix), the reader reopened earlier in the note than the last scroll; the logs had rotated. Not reproduced afterwards (both kill tests above restored exactly).
