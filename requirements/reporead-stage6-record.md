# RepoRead — Stage 6 verification record (in progress)

Evidence for the [build plan](reporead-build-plan.md)'s Stage 6. Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs; decisions are in the [ADRs](reporead-adrs.md). **The exit gate has not passed**: it needs two weeks of voluntary use, at least 20 real highlights, the deferred fixture demo, and the full acceptance review.

## User decisions (2026-10-07)

- Disconnect deletes everything stored for the repository; account deletion deletes everything and makes no GitHub call (ADR-11).
- Search: local substring search with Room v5 search text; metrics: Spring Boot Actuator on a loopback-only port.
- Release: one personal signing key for every build; the backend stays on the Mac through `adb reverse` (ADR-12).
- The labelled fixture demo is deferred until after two weeks of real use.
- Added at the user's request: a dark theme and redesigned reader and app shell; Obsidian `[[links]]` and `![[image]]` embeds (Flyway V6, Room v6).

## Automated (2026-10-07)

- `./gradlew :backend:test --no-daemon`: exit 0, **166 tests, 0 failures, 2 skipped** (the opt-in measurement harnesses). Stage 6 adds: content `text` and `renderFormat`; disconnect deleting only that repository's data (with its image list) after showing counts, no GitHub call, ownership; account deletion removing all the user's rows, sessions, and in-memory GitHub token but no one else's; GitHub request, sync, highlight-creation, and re-anchoring meters; image files recorded per snapshot; embed resolution by name (folder preference, ambiguous, missing without GitHub); Obsidian links and embeds leaving canonical text unchanged; relative `.md` links.
- `ANDROID_HOME=… ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon`: exit 0 (unit tests include LIKE escaping, snippets, and Obsidian link resolution). Device tests via `am instrument` on the Pixel 8a: **12 tests, OK** (search queries, forgetting a disconnected repository including pending rows, library change lists); not re-run after the link change.
- Signing: debug, release, and device-test APKs all carry certificate SHA-256 `2049…293f`; the release APK is not debuggable; packaging without the key properties fails with an explicit message.
- Reader look: rendered through the real Java renderer and bundle in headless Chrome at phone width, light and dark. This reproduced a pre-existing reader failure — an unavailable image's label inserted text into a block, breaking the canonical-text check — fixed by drawing the label from an attribute.

## On the phone and server (2026-10-07)

Pixel 8a, backend on the Mac with the development database (backed up before each migration: V6 server, Room v4→v5→v6).

- **Search (user-reported):** empty-query explanation with counts, a word found only in a note's text with a snippet, a title, no-match message, and opening a result.
- **Disconnect:** a throwaway private repository (`jaxwong/reporead-disconnect-test`, two test-only notes, created with the user's approval) was connected, read, highlighted, bookmarked, and disconnected. The first run showed **0** reading states and bookmarks because they were still unsent on the phone, though the phone deleted them — a defect, fixed by syncing before counting (`c96a299`). The second run's dialog showed 2 notes, 2 reading states, 1 bookmark, 1 highlight (user-reported) and the server logged exactly that; the phone kept only `zw_obsidian`.
- **Account deletion:** after a `pg_dump`, the user deleted the account on the phone. Server log `Account deleted; repositories=1 readingStates=10 bookmarks=3 highlights=10`; every user table had 0 rows; the phone's Room tables and image folder were empty and its session preferences cleared. The dump was then restored (counts identical to before) and the user signed in again.
- **Metrics:** `127.0.0.1:8082/actuator/health` 200; `/actuator/env` 404 (not exposed); `/actuator/health` on 8081 401.
- **Release APK:** installed after one uninstall of the old debug-signed app (no unsent changes, nothing saved at that moment); later builds install over each other.
- **Dark theme and new layout (user-reported):** the reader and app follow the phone's dark setting; the Library's tabs, menus, and top bars replaced the crowded button row.
- **Obsidian links (user-reported, "it works"):** `[[links]]` open their notes. The refresh recorded the repository's 3 image files; no embedded image was requested in the server log, so **embeds are not yet verified on the phone**.

## Not yet done or verified

- Two weeks of voluntary use and at least 20 real highlights (definition of done).
- The labelled fixture demo (deferred by the user) and the separate real demo run.
- The full F-01–F-13 / US-01–US-11 acceptance review.
- An Obsidian image embed on the phone; a name shared by two notes (the chooser); a heading link — automated only.
- A sync racing a disconnect (now 404 by design) and a highlight created concurrently with a disconnect (would fail on the foreign key) — not exercised.
