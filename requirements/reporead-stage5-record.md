# RepoRead — Stage 5 verification record

Evidence for the [build plan](reporead-build-plan.md)'s Stage 5 gate. Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs.

## Design decisions

- **Baseline is the version actually read, as the phone knows it.** The reader saves the displayed version as read as soon as a note opens, and reading saves reach the server only at the next library sync. So the reader captures its local last-read version *before* opening the note and sends it as `since`, with the displayed version as `to`. The server's `reading_states` is not used as the baseline because it can lag behind pending phone saves. A never-read note has no `since`, and nothing is requested or shown.
- **Line diff, then sections.** Myers' minimal line diff over the Markdown source (same line numbering as the parser), with changed lines assigned to the heading section containing them. Sections are identified by position, not heading text, so duplicate headings stay distinct. A renamed heading is reported as removed + added. Not AST-aware (out of MVP scope).
- **Bounds and honest states.** At most 2 GitHub blob reads per comparison (0 for the same version), 20,000 lines per version and 1,000 changed lines; beyond them the status is `TOO_LARGE`, never a partial list. An older version GitHub no longer has is `SINCE_UNAVAILABLE` with the reason. Any other failure fails the request and the phone shows it with Try again. Constructed worst cases at both limits took at most 25 ms.
- **Recently changed** uses `documents.content_changed_at` (Flyway V5): when a refresh found a note new, with a new blob, or back after deletion. A connection's first snapshot and path-only moves set nothing, and notes before V5 have none — it is when RepoRead noticed, not the commit time, and the phone labels it "seen changed".

## Automated (2026-10-07)

- `./gradlew :backend:test --no-daemon`: exit 0, **158 tests, 0 failures, 2 skipped** (the opt-in measurement harnesses). New: change times on a later snapshot but not the first, not for a path-only move, unchanged on a repeated snapshot, set when a deleted note returns; renderer heading lines and blocks (ATX, setext, nested in quotes and lists, `#` in fenced code, CRLF, duplicates); section mapping (inserted paragraph, added/removed sections in place, duplicate headings, beginning of the note, deletion at a section end, renamed heading, minimal diff, both limits); the endpoint (sections and block ids match the displayed page, same version without GitHub, missing old blob, too large, GitHub outage on either version fails, ownership/deletion/validation before GitHub).
- `ANDROID_HOME=… ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`: exit 0. Device tests via `am instrument` on the Pixel 8a: **10 tests, OK**, including the library queries (updated since read, most recently changed first, untimed changes last, recently changed excludes notes updated since read, reading the current version moves a note between lists).

## On the phone and server (2026-10-07)

Pixel 8a, debug app over `adb reverse`, backend on the Mac with the development database. Both databases were backed up before upgrading. Flyway V5 applied (V4 → V5; 653 existing notes with no change time). The phone's Room database migrated v3 → v4 (`user_version` 4).

With the user's approval, Claude pushed test-only notes under `scratch/` in `jaxwong/zw_obsidian` using the user's `gh` login, listing each edit before pushing.

1. **Read version A.** Setup commit `e44cfd1` added `scratch/stage5-changes.md` (sections Setup, Duplicate › Notes, Another › Notes, To delete, Stable) and `scratch/stage5-unread.md`. The user refreshed and read stage5-changes (blob `e1a0753e`). The phone held that reading as a pending save until the library synced it.
2. **Edit and refresh without reading.** Edit commit `c9c80fa` changed the Setup sentence and the *second* Notes section's sentence, deleted "To delete", and added "Added later" before Stable (blob `cb365d8d`). After the user's refresh, the server had current `cb365d8d` and last read still `e1a0753e`, with a change time only on the two scratch notes. User-reported: Continue reading showed "Updated since you read"; the Updated since you read section listed stage5-changes; Recently changed listed stage5-unread as "Not read yet".
3. **Summary and navigation.** Opening the note made one comparison: server log `Changes compared … since=e1a0753e… to=cb365d8d… status=CHANGED sections=4 added=4 removed=4`. User-reported: the panel listed Setup (changed), Notes in Another (changed), Removed: To delete, New section: Added later, in that order; tapping a section scrolled to its heading — the second "Notes", not the first — and collapsed the panel; returning to the library removed the note from Updated since you read. No "section not found" warning was logged. The note is short, so later headings could not reach the top of the screen; which heading was shown rests on the user's report, not the logged scroll positions.
4. **Second refresh and reopen.** A repeated refresh synced the same commit with `moves=0` and left both change times unchanged. User-reported: reopening stage5-changes (now read in version B) and opening the never-read stage5-unread showed no panel; the server logged no further comparison.

## Gates

- **Exit gate passed (2026-10-07):** the phone showed what changed since the version the user actually read — the baseline survived a refresh without reading, the summary distinguished changed, removed, and added sections including duplicate headings, opened the current section, and claimed no baseline for a never-read note — on the real phone, backend, and repository.

## Not yet verified on the real system

- A missing old blob (`SINCE_UNAVAILABLE`), a too-large comparison, a line-ending-only change, and a branch rewind — automated only; producing them live would need rewriting the repository's history.
- The comparison failing offline or during a GitHub outage on the phone (the Try again state), and keeping the captured baseline across screen rotation.
- "Updated since you read" depends on the phone's saved note list for each repository; a repository whose folder has never been opened on the phone shows no indicators.
