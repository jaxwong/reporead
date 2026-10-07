# RepoRead — P2 verification record (every note offline, link previews, backlinks)

Evidence for P2 of the [post-MVP plan](reporead-post-mvp-plan.md). Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs. **The exit gate passed on the Pixel for the cases tried** (below); several items of the plan's verify list are not done yet and are listed at the end.

## User decisions (2026-10-07)

- Download **per note, incrementally**, through the existing `GET /api/documents/{id}/content`, not the repository archive: bodies stay off the server (ADR-02), and later runs fetch only what changed.
- **Keep every note and measure** storage on the Pixel before deciding any eviction.
- Built on branch `p2`, created by the user from P1's tip.

## Built

| Item | What | Where |
| --- | --- | --- |
| Save all notes | A repository's overflow menu → **Save all notes on this phone**: fetches, one at a time, each note whose saved copy is missing, of another version, or of an older page format. Progress "X of Y"; a note the server refuses (too large) is listed and skipped; any other failure, or leaving the screen, stops and keeps what was saved | `Sync.saveAllNotes`, `Library.kt` `FolderScreen` |
| Ceiling | One GitHub call per note to fetch, none for saved ones; bounded by the 5,000-document limit; no batch route | `backend/README.md` |
| Empty notes | An empty Markdown file opened with 502 `GITHUB_INVALID_RESPONSE` (GitHub returns a 200 with no body, which RestTemplate gives as null); it now shows "This note is empty." Found when the first run stopped at note 117 | `GitHubApi.get`, `DocumentContentTest` |
| Search coverage | "Searching 653 of 654 notes by their text, all 654 by title and path, and your highlights." N counts only saved copies of listed notes | `Search.kt`, `LocalStore.searchableNoteCount` |
| Link preview | A link that resolves to one note opens a sheet: title, folder, the saved copy from its start or from the linked heading in a separate isolated page where nothing navigates, Close and Open. Several matches still ask which note | `reader/LinkPreview.kt`, `Reader.kt` |
| Heading anchors | `#Heading` matches the heading's text (Obsidian) or its GitHub anchor (`#2-separate-the-contracts-and-checks`, repeats `-1`, `-2`) | `reader.js` `showHeading` |
| Linked from | The notes panel lists the notes whose saved pages link to this one, resolved as a tap would; computed on the phone in the background; says when only some notes are saved | `reader/Backlinks.kt`, `Notes.kt` |

**Defect found while measuring — Markdown heading links never reached their heading** (`a50e3af`). Of 146 Markdown links with a heading in the saved notes, 145 carry GitHub's anchor, and `showHeading` compared heading text only, so the note opened at its start with "No heading …". This predates P2 (Stage 6 links) and affected Open as well as the new preview. The 146th link is broken in the notes themselves (`#recommended-learning-paths` for the heading "4. Recommended learning paths").

**Defect fixed with the user's approval — state carried over between screens of the same kind.** The navigation `when` in `RepoReadApp.kt` was not keyed by screen, so a reader opened from another reader kept its remembered state (the notes panel stayed open in the next note) and a subfolder showed its parent's status line ("Saved 0 notes…"). Predates P2; P2 made reader-to-reader moves common. The switch is now keyed by the screen; on the phone a note opened from Linked from had the panel closed, and `core` no longer showed the root's status.

## Automated (2026-10-07)

- `./gradlew :backend:test --no-daemon`: exit 0, **170 tests, 0 failures, 3 skipped** (the opt-in harnesses). Adds an empty note shown as empty.
- `ANDROID_HOME=… ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`: exit 0, **16 tests, 0 failures**. Adds `BacklinksTest`: links decoded as the reader decodes them (`+` as a space), same-note heading links left out, folder preference, exact paths, an ambiguous link counting for each candidate, a note not counting itself, unlisted pages ignored.

## On the phone (2026-10-07)

Pixel 8a, debug build over the release build (same key, data kept), backend restarted on `4fa6ff3` (the user signed in again).

- **First full run** (after the empty-note fix; 119 notes were saved before): `toFetch=535`, **534 fetched, 1 refused** (`venti/eta model/data diagnostics/negative_difference_lanes.md`, over the 4,096-block limit), in **5 min 43 s**. The screen said "Saved 534 notes on this phone; 119 were already saved. 1 can't be shown (too large): …".
- **Second run:** `toFetch=1` (the refused note; it has no saved copy, so every run asks for it once more), **0 fetched**; "Saved 0 notes on this phone; 653 were already saved."
- **Partial failure:** the first attempt stopped at note 117 with `GITHUB_INVALID_RESPONSE` (the empty-note defect) and kept the 114 notes fetched before it; running again continued from there. No partial note is stored: each note is one `saveNote` of a complete response.
- **Storage:** 653 saved notes, all in page format 3. Database **28.8 MB** (`du -sk databases` 28,840) and files 12 KB; rendered pages 21.7 MB, search text 5.1 MB, largest page 603 KB. Before the first run: 1.2 MB.
- **Preview:** a Markdown heading link in `core/backend engineering/interview/01-api-boundaries-and-contracts.md` previewed "2. Separate the contracts and checks" (before the anchor fix it showed the start with a notice); **Open** opened the note with that heading at the top. A plain link previewed the note's start. A link tapped inside the preview did nothing; Close dismissed it.
- **Linked from:** `core/backend engineering/01-api-boundaries-and-contracts.md` listed **30** notes, the same 30 a script computed from the phone's database; the matching took 393–677 ms over the 251 pages with links. It said "Searched the 653 of 654 notes saved on this phone." Tapping an entry opened that note.
- **Exit gate, backend unreachable** (`adb reverse` removed; `nc` to 127.0.0.1:8081 refused; app restarted): searching "competencies", a word only in the body of `core/web/knowledge-architecture-research.md`, which had never been opened, found it with a snippet; it opened `ready`, 340 blocks, 0 canonical-text mismatches; its link to `core/web/00-orientation.md` previewed from the saved copy. Forwarding was restored afterwards.

## Not done or not verified

- **After a laptop edit, only that note is fetched:** needs an edit pushed to `jaxwong/zw_obsidian` (`scratch/` only, with approval). Save all uses the saved note list, so the repository is refreshed first.
- **An ambiguous link asks which note:** no link in the real notes is ambiguous (1,690 resolve to one note, 147 more do so with a heading, 16 to none); the chooser is unchanged from Stage 6. Needs test notes in `scratch/`.
- **The preview of a note with no saved copy** ("Not saved on this phone yet…"): every note is saved, so it did not occur.
- **Disconnect deletes the downloaded notes:** not run; it deletes the 653 saved copies (a new run takes about 6 minutes).
- **Airplane mode** itself: the backend was made unreachable by removing `adb reverse` instead, because airplane mode drops wireless debugging.
- **Images:** Save all does not fetch images; three notes have one embedded image each (`books/the pragmatic programmer/…` T12, T15, T33), which show "[Image unavailable]" offline until opened online once.
- The release build is not reinstalled yet.

## Noticed, not changed

- **A note path containing `+`** (one in the repository) could not be reached by a link: the server leaves `+` unencoded in `/note-link?` and Android decodes it as a space. No current link uses one.
- The preview shows the page's **Full screen** and code **Copy** controls; they are links, which the preview does not follow, so they should do nothing there (not tried).
- The preview's subtitle shows the link's heading as written, so a GitHub anchor appears as `# 2-separate-the-contracts-and-checks`.
