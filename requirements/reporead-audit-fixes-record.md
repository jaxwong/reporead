# Audit fixes — record

Branch `audit` (from `main` at 72f663b, P4 merged), 2026-10-09. The user asked to fix every bug from the whole-app audit and test end to end. Each fix below reproduced as a failing test first unless marked otherwise. GitHub was written only in `zw_obsidian/scratch/audit/`, three approved commits.

## Decisions the user made

- Room v8 (phone only): keep the change-summary baseline until a summary is shown; mark deletions of unacknowledged creations instead of deleting locally.
- Notebook size: record the limit, do not page (backend README, Review cards).
- Full end-to-end testing on the Pixel, including pushes to `scratch/audit/` only.

## Fixes, by root cause

| Area | Root cause | Fix | Commit |
| --- | --- | --- | --- |
| Anchoring | A bounded look-alike search was stored as "no look-alike" (0); context windows could split a surrogate pair | Bounded → 1.0 (indistinguishable); windows stop short of a split | 7bdd18e |
| Sync | A sync that fetched before another published could publish an older snapshot | Checkpoint compared under the lock; 409 `SYNC_SUPERSEDED` | 9ddc790 |
| Disconnect races | Document writes did not take the connection lock; account deletion listed before locking | Writes share the lock (FOR SHARE); user row locked first | 9ddc790 |
| Re-anchoring | A resolution read before a reattachment could overwrite it | Also guarded on the user-edit version | 9ddc790 |
| Limits | Renderer emitted headings of any length; endpoint capped at 500 | Renderer owns `MAX_HEADING_CHARS`; limits and messages derive from owners; typed `INVALID_REQUEST`; fingerprint mapper pinned | 68cc0ce |
| Phone sync | One refused item failed every later sync and blocked Disconnect | `refusesItem()`; GitHub-backed creations wait while database-only work runs; notebook last; reconciliation with complete lists | 2cfdfe2 |
| Sign-in | Token stored before another account's data was cleared | Clear, set owner, then store the token | 07260a0 |
| Backup | `allowBackup=false` does not stop device transfer on Android 12+ | `data_extraction_rules` exclude all domains | 07260a0 |
| Export | `Files.writeString` needs API 36.1 (min SDK 34) | `File.writeText` | 07260a0 |
| Scheduler | Lapses lowered ease (SM-2 step 6 keeps it); due compared milliseconds | Lapses keep ease; due from the start of the local day | 7e809bb |
| Change summary | Baseline was the reading row, overwritten on display | Phone-only `change_baselines` (Room v8) | fbb784f |
| Unsynced deletes | Local delete of a creation the server may already have | `deleting` mark; replay then delete | fbb784f |
| Reader navigation | Covered screens lost their saved state; heading target had no flag | Per-entry saved state; one "target shown" flag | 9165075 |
| Note links | Server RFC 3986 encoding vs phone form decoding (`+`) | One decoder; contract tests both sides | 9165075 |
| Reader page | `#heading` links, emoji-split prefixes, `/copy-code` crash, folded selection errors, stale menu actions | Page handles `#`; safe prefix; null code text; typed outcomes | 9165075 |
| Study | Tight list items with nested points had their text in no block; questions showed hidden link source | One candidate per top-level item; text as shown | 5a4b505 |
| Destructive actions | Delete with one tap; Practice Back exited the app | Confirm dialog; Back returns to topics | 45339c8 |

## Executed verification

Fresh full build after the last production change (45339c8), exit **0**:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :backend:test --no-daemon --rerun-tasks
```

```text
BUILD SUCCESSFUL in 1m 33s
142 actionable tasks: 142 executed
backend/build/test-results/test {'tests': 189, 'skipped': 3, 'failures': 0, 'errors': 0}
android/build/test-results/testDebugUnitTest {'tests': 39, 'skipped': 0, 'failures': 0, 'errors': 0}
```

Device tests on the Pixel 8a (Android 14, API 34), debug and test APKs installed with `adb install -r` / `-r -t`, then:

```sh
adb -s <serial> shell am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner
```

First run: 37 tests, 1 failure, in a new fixture (a block on the note's last screen cannot scroll to the top); fixed in 5863cda without changing production code. Then `OK (37 tests)` twice in a row.

Real app on the phone (debug build, real account, backend on this branch, `adb reverse`). The real app database migrated v7→v8 (657 notes and 40 reading positions kept); the dev PostgreSQL applied P4's V7 at backend start.

- Sign-in after the backend restart completed and kept the same account's data.
- `[[C++ templates]]` rendered as `/note-link?target=C++%20templates`; tapping it previewed the note. Before: "No note “C   templates”".
- Study view on `scratch/audit/questions.md`: "Why does a tight item with sub-points still appear?" and "How does C++ templates read in a question?".
- Change summary, online: shown; baseline used up. Offline (app force-stopped so its connection pool closed, `adb reverse` removed): "Couldn't compare"; the phone kept baseline 81ec972 while recording 01fe072 as read; Library still listed the note under Updated since you read. Back online: "Second (+2 lines)" shown, baseline gone, note no longer listed.
- Offline highlight, then Delete (confirmation dialog shown): row hidden and marked deleting. Next sync: server log `Annotation created … annotationId=18`, then `Annotation deleted … annotationId=18`; the mutation record remains without an annotation; the phone row is gone.
- Sign out asks first (Cancel kept everything); Practice Back returns to topics; Review shows no zero-count line.
- All 40 pre-existing reading positions identical to the backup taken before testing; three test-note positions added. Release build reinstalled; data kept.

## Not verified, or not changed

- Not reproduced on this phone: the export crash. Its ART module (371000140) provides `Files.writeString` on Android 14; the fix removes the dependency.
- Not exercised with real data: a >500-character heading, a 403 on a pending creation, `SYNC_SUPERSEDED`, disconnect and account deletion (device and backend tests only; not run against the real account to avoid deleting data), sign-in under process death, device-to-device transfer.
- Anchoring thresholds were not re-measured with `AnchorMeasurement` (needs a notes clone, blocked this session). Existing anchors keep evidence stored before the fix.
- `MarkdownRenderer.FORMAT` was not bumped: saved copies with a heading over 500 characters keep sending it until refetched; the server refuses those positions and the phone keeps them pending and says so.
- Not changed: a `connect` racing account deletion can still fail with a 500 for the connect; `check-restore.mjs` keeps its own copies of reader functions; whether a card can be graded is decided in `cardCheckReason`, `recordReview`, and the Notes label; a tight list item's own text is still in no block, so it cannot be highlighted.
- Left in place: `scratch/audit/` notes (commits 3303af1, c44bde7, 0005bcc) and their three reading positions; the backend running on 127.0.0.1:8081; `adb reverse tcp:8081`.
