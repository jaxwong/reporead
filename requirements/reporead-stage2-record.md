# RepoRead — Stage 2 verification record

Evidence for the [build plan](reporead-build-plan.md)'s Stage 2 exit gate. Contracts and commands live in the [backend](../backend/README.md) and [Android](../android/README.md) READMEs.

## Automated (2026-10-06)

- `./gradlew :backend:test --no-daemon`: exit 0, **98 tests, 0 failures** against PostgreSQL 17.11 (Testcontainers). Adds reading state (version recorded, last write wins including equal and concurrent writes, refresh does not change the last-read version, deleted documents keep history, validation, per-user isolation), bookmarks (idempotent set/clear in the annotation domain, isolation, validation), and images (resolution like GitHub, escape/remote/unsupported blocked, per-note limit, endpoint ownership/deletion/path checks, missing vs oversized).
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`: exit 0, 4 folder tests.
- `./gradlew :app:connectedDebugAndroidTest` on the Pixel 8a (Android 14): **6 tests, 0 failures** — pending local progress vs older/newer server state, acknowledging only the save that was sent, bookmark list replacement keeping pending toggles, and complete-list replacement of documents/repositories.
- Flyway applied V2 to the development database on restart.

## On the phone (user-reported, 2026-10-06)

Pixel 8a, debug app reinstalled after the device tests uninstalled it, backend restarted with V2.

1. Reading a note and leaving it showed it under **Continue reading** with a percentage — passed.
2. Airplane mode, force-stop, reopen from Continue reading: resumed at the same progress from the saved copy — passed.
3. Rotation and a system font-size change: same passage — passed.
4. Uncached note while offline: explicit error "Can't reach RepoRead's server…" rather than a blank or partial note — passed.
5. Bookmarking in airplane mode: worked locally, survived reconnecting, and synchronized exactly once — passed (see below).
6. Repository images: not tested; the user has no note with an image.

## Server evidence

After the session, `reading_states` holds `core/backend engineering/01-api-boundaries-and-contracts.md` at 14%, block 26, heading path `01 — API Boundaries and Contracts › 1. Why API boundaries matter › Follow one request across the boundary`, recorded against the note's current blob SHA (synced at 10:35:19 UTC). `annotations` holds no bookmark yet.

Airplane mode turned off Wi-Fi, which ended wireless adb debugging and its `adb reverse` forwarding, so the first post-reconnect sync could not reach the backend. After the user re-enabled wireless debugging and `adb reverse` was restored:

- **Before sync**, the phone's Room database (read through `run-as` from the debuggable app) held the bookmark on document 70 as `pending` and a newer reading save at 68% as `pending`; the server still had 14% and no bookmark. Pending work survived the offline period and reconnection.
- **After the first Sync**, the server held exactly one `BOOKMARK` annotation for document 70 against the current blob SHA (10:47:05 UTC) and reading state 68% (10:47:07 UTC); both phone rows were acknowledged (`pending = 0`).
- **After a second Sync**, the server still held one bookmark with the same `created_at` and one reading state at 68%; the server log had no warnings or errors.

## Exit gate

Passed on 2026-10-06: cached technical notes are readable offline, progress and bookmarks survive restart and synchronize once after reconnecting, and the library offers a working Continue reading action.

## Defect found after the gate (2026-10-06)

While testing Stage 3 the user reported that reopening a note no longer resumed. Reader logs on the Pixel showed `mode=exact` restores of block 141 followed by a save at block 0. Block 141 is a Mermaid source collapsed under its rendered diagram; WebView DevTools showed `scrollIntoView` silently does nothing for exactly the four collapsed sources among the note's 200 blocks. The Stage 2 resume check passed only because the saved position was not at a diagram. Fixed in `bd7f4e7` (anchors measured and restored through the rendered diagram, explicit scrolling, a restore only claims a mode when its target is on screen); the device check `android/reader-web/check-restore.mjs` restored all 200 blocks with 0 wrong and 0 approximate, and process death online and with the backend unreachable resumed exactly at block 141.

## Not yet verified on the real system

- Repository images, online then offline (no real note contains one).
- Process death by the system (only force-stop was tested) and backend failure while a cached note is open.
