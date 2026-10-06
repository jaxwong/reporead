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
5. Bookmarking in airplane mode: worked locally — passed. **Reaching the server after reconnect: not yet verified** (see below).
6. Repository images: not tested; the user has no note with an image.

## Server evidence

After the session, `reading_states` holds `core/backend engineering/01-api-boundaries-and-contracts.md` at 14%, block 26, heading path `01 — API Boundaries and Contracts › 1. Why API boundaries matter › Follow one request across the boundary`, recorded against the note's current blob SHA (synced at 10:35:19 UTC). `annotations` holds no bookmark yet.

Airplane mode turned off Wi-Fi, which ended wireless adb debugging and its `adb reverse` forwarding; the phone was no longer listed by adb afterwards. The offline bookmark therefore most likely remains pending on the phone, which is the intended behavior. The reconnect-and-sync step must be repeated after re-enabling wireless debugging and `adb reverse`.

## Not yet verified on the real system

- One successful synchronization of the offline bookmark after reconnecting, and a second sync producing no duplicate.
- Repository images, online then offline (no real note contains one).
- Process death by the system (only force-stop was tested) and backend failure while a cached note is open.
