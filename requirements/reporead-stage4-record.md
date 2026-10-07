# RepoRead — Stage 4 verification record

Evidence for the [build plan](reporead-build-plan.md)'s Stage 4 gate. Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs.

## Document identity (2026-10-06)

A complete snapshot keeps a document's identity through a path-only move: an active document whose path left the snapshot takes a path that has never had a document when both have the same blob SHA, unique on each side. Identical-content duplicates are never merged; a path that once had a document resumes that document. Automated: `RepositorySyncTest` (move with reading state kept, second sync idempotent; duplicate SHAs on either side not merged; path reuse).

**Moved and edited.** A vanished document that carries the user's data (reading progress, bookmark, or highlight) keeps its identity at a new path when their contents are alike: Jaccard similarity of five-word shingles of the canonical text at least 0.5, both versions with at least 50 shingles, and the pair the only one above 0.5 for both its document and its path. Comparison costs one blob read per candidate and is skipped when vanished documents plus new paths exceed 8; a version GitHub no longer has, or one that cannot be read as a note, excludes only that candidate; any other GitHub failure fails the sync without changes. Automated: `RepositorySyncTest` (move with edit kept; unrelated note, ambiguous pair, note without user data, too many candidates, missing old version, outage).

Measured with `MoveMeasurement` on the same clone: the 38 real renames with edits in its history scored 0.54–1.00 and had at least 229 shingles in each version. Among the 616 notes with at least 10 shingles, the most similar pair of different notes scored 0.198. Every pair above 0.5 involved an empty note, a note of bare template headings (no five-word run), or identical template copies — hence the 50-shingle minimum. Block-text Dice was measured and not chosen: the 95th percentile of a note's most similar other note was 0.80 against 0.19 for shingles.

## Re-anchoring: measured thresholds (2026-10-06)

`Anchoring` resolves a highlight in a new version in this order: its block unchanged anywhere (whole-block SHA-256), unchanged position with unchanged context, the exact quote singled out by context and heading, then a bounded fuzzy match. Anything else is orphaned. `Anchoring.MEASURED = (quoteContext 0.5, uniqueQuoteChars 16, fuzzyQuote 0.85, fuzzyContext 0.8)` was chosen with `AnchorMeasurement`, not by hand.

**Corpus.** A read-only clone of the user's notes repository (`jaxwong/zw_obsidian`, cloned with the user's approval into `/private/tmp/reporead-build/zw_obsidian`; never committed): 640 notes rendered at HEAD and 599 real edited versions from its 50-commit history (modifications and renames with edits). Only aggregate numbers are recorded here.

**Cases with a known answer.** Real paragraphs with reader-like selections (a word, a phrase, a sentence, a short whole block), each under synthetic edits: a block inserted before, the block moved to another section, a word changed next to or far from the selection, a one-word or one-character edit inside it, the block duplicated (same section, other section), the quote repeated in a new paragraph, the block deleted, rewritten, or most selected words replaced. Real history adds every selection whose block survived verbatim exactly once. Wrong = attached to a different block or a non-overlapping span.

**Result at `MEASURED`** (tuning sample, then three held-out samples drawn with other seeds):

| Sample | Labelled cases | Wrong attachments | Should-attach cases attached |
| --- | --- | --- | --- |
| seed 20261006 (tuning) | 22,075 | 0 | 16,181 / 17,032 (95.0%) |
| seed 1 | 22,133 | 0 | 16,258 / 17,076 (95.2%) |
| seed 2 | 21,929 | 0 | 16,127 / 16,914 (95.3%) |
| seed 3 | 22,033 | 0 | 16,224 / 16,966 (95.6%) |

The next looser grid point (0.4, 16, 0.85, 0.7) is also zero-wrong on all four samples; (0.4, 12, 0.8, 0.6) makes one wrong attachment on seed 1. Per category on the tuning sample: unchanged real blocks 5,101/5,101; inserted block, moved block, duplicates, repeated quote 97–98% (the rest are orphaned because the passage was indistinguishable in its own version); edit far from the selection 100%; edit next to it 91%; one-character edit 94%; one-word edit 56% (a short quote with a changed word is often below the 0.85 quote similarity, and is orphaned rather than guessed); deletions, rewrites, and mostly-replaced selections are all orphaned.

**Design changes the measurement forced** (each found as wrong attachments, root cause fixed, then re-measured):

1. Text that was not unique even in its own version (repeated table cells, headings, command lines) attached to an identical sibling after edits. Each anchor now records, from its own version, how often its quote occurs and how similar the best other occurrence's context is; a match away from the original must beat that rival.
2. A copy of a block placed at the original index won over the original block, which had moved verbatim. Whole-block identity (SHA-256 of a block text that is unique in its version) now precedes position.
3. An identical neighbour shifted into the old index after an insertion. Position is now evidence only for passages with no identical-context rival.
4. A deleted passage moved onto a near-identical sentence that already existed elsewhere. Anchors also record their closest look-alike in another block; a fuzzy candidate must resemble the quote more than it did.
5. In real history, deleting one of two adjacent links pulled the other link's prefix into place. Context is now the weaker of prefix and suffix similarity, not their mean.
6. Fuzzy alignment could start a quote mid-word when text was inserted inside it; alignment now counts only insertions and deletions, so identical words pair up.

**Real edits without an automatic label.** At `MEASURED`, selections in blocks that changed in real history resolved as 731 orphaned, 104 exact quote, 20 fuzzy, 9 position (tuning sample). All 133 attachments were reviewed by hand against the old and new text (Claude, 2026-10-06): none attached to a different passage. Recall on real changed blocks is **not measured**: orphaned real cases have no automatic label, and a spot check of seven found their text absent from the current notes.

**Cost.** Gathering evidence took at most 2.3 ms per case on the corpus. A constructed worst case at every fuzzy bound (999-character quote, 16 related 20,000-character blocks) took 335 ms. Bounds: fuzzy matching is skipped for quotes over 1,000 characters, for blocks over 20,000 characters, and when more than 16 blocks share at least half of the passage's words.

To repeat: `REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<private file> [REPOREAD_MEASURE_SEED=<n>] ./gradlew :backend:test --tests '*AnchorMeasurement' --rerun --no-daemon`. The report and its `.samples.txt` contain private note text; keep them outside the repository.

## On the phone and server (2026-10-07)

Pixel 8a, debug app over `adb reverse`, backend on the Mac with the development database. Both databases were backed up before upgrading.

- **Upgrades on real data:** Flyway V4 applied to the development database (V3 → V4). The phone's Room database migrated v2 → v3 on first open, keeping 4 highlights, 2 reading states, and 1 bookmark. Device tests via `am instrument`: **9 tests, OK**. JVM unit tests: 7 passing.
- **Pre-Stage-4 highlights:** opening their note (document 70) filled the four locations' distinguishability from the current version with one GitHub blob read (16,318 bytes); all stayed `ANCHORED` at their original blocks (b142, b143), and their user-edit versions were unchanged (highlight 3 stayed at version 4).
- **Real path-only moves in the user's own commit `20357d3`:** two notes moved into folders (`110926_rockship.md`, `210826_rockship.md`) kept their document ids 1 and 2.
- **Scripted real edits.** With the user's approval, Claude pushed test-only notes under `scratch/` in `jaxwong/zw_obsidian` using the user's `gh` login, listing each edit before pushing. Setup `8d0cbfe`; the user then highlighted passages, bookmarked, and read on the phone. Edit commit `f16eb28`:

| Edit | Server outcome | Phone |
| --- | --- | --- |
| Paragraph inserted above a highlighted paragraph | `BLOCK` → b4 (was b3), `REANCHORED` | user-reported: drawn in place |
| One word changed inside a highlighted sentence ("same" → "identical") | `FUZZY` → b6, new text, `REANCHORED` | user-reported: drawn, Notes shows the new text |
| Highlighted paragraph rewritten | `ORPHANED`; original selection and note kept | user-reported: shown with original context; **reattached** to "smoked mackerel" → `REANCHORED`, version 2 |
| Highlighted sentence pasted into another paragraph (duplicate quote) | `BLOCK` → b10 (original paragraph), not the copy in b11 | user-reported: drawn on the original |
| Note moved unchanged | exact move: document 1195 kept, bookmark and 100% progress kept | user-reported: Bookmarks shows the new name |
| Note moved and one sentence edited | content move: document 1194 kept; its highlight `BLOCK` → b4 | user-reported: highlight drawn |
| Highlighted, read note deleted | document 1193 marked deleted; reading state (100%) and highlight kept | user-reported: "Removed from the repository" in Continue reading |

  The first refresh read 3 blobs (the two vanished notes with user data and one new path, within the 8-read ceiling) and logged `moves=2`; the **second refresh** logged `moves=0` with no blob reads. Opening the edited note made one blob read for resolution. The phone's Room rows matched the server (statuses, location blocks, versions; nothing pending).

## Gates

- **Exit gate passed (2026-10-07):** strong-evidence edits preserved identity and anchors (insertion, light edit, duplicate quote, path-only move, move with edit); a destructive rewrite produced an orphan with its original selection, and manual reattachment worked — on the real phone, backend, and repository.

## Not yet verified on the real system

- A branch rewind (force-push) and missing history against live GitHub — covered by automated tests only (`anOrphanIsFoundAgainWhenItsPassageReturns…`, `anOldVersionGitHubNoLongerHas…`).
- An ambiguous duplicate-content move, a moved section, and a lightly edited passage orphaned for weak evidence — covered by automated tests and the measurement only.
- Recall on real changed blocks (see the measurement section).
