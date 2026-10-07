# RepoRead

An Android reading and study layer for technical Markdown notes kept in GitHub: browse a repository, read code and Mermaid on the phone, resume where you stopped, highlight and annotate without touching the source, keep annotations trustworthy across edits, see what changed since you last read, search saved notes, and work offline. GitHub stays the source of truth; RepoRead never writes to it.

| Part | What it is | Setup and contracts |
| --- | --- | --- |
| `backend/` | Spring Boot 4 / Java 25 / PostgreSQL 17: sign-in, connections, sync, rendering, reading state, annotations, re-anchoring, change summaries | [backend/README.md](backend/README.md) |
| `android/` | Kotlin/Compose app with Room offline storage and an isolated WebView reader | [android/README.md](android/README.md) |
| `android/reader-web/` | The reader's bundled display JavaScript (highlighting, Mermaid) | [android/reader-web/README.md](android/reader-web/README.md) |
| `requirements/` | Stories, feature list, spec, build plan, [ADRs](requirements/reporead-adrs.md), and the per-stage verification records | [build plan](requirements/reporead-build-plan.md) |

## Local setup, in order

1. Start PostgreSQL (`docker compose -f backend/compose.yaml -p reporead up -d`) and the backend with the GitHub App client id and secret file — [backend/README.md](backend/README.md#run).
2. Install the reader's npm dependencies once and set up the signing key — [android/README.md](android/README.md).
3. Build and install the app, then `adb reverse tcp:8081 tcp:8081` so the phone reaches the Mac's loopback backend.

## Tests

```sh
./gradlew :backend:test --no-daemon                       # PostgreSQL via Testcontainers; Docker must be running
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
```

Device tests (Room on the phone) run with `adb shell am instrument`, never `connectedDebugAndroidTest` on a phone you read on — see [android/README.md](android/README.md#device-tests). Two backend measurement harnesses (`AnchorMeasurement`, `MoveMeasurement`) are skipped unless pointed at a local clone of a notes repository; see the [Stage 4 record](requirements/reporead-stage4-record.md).

## What failures look like

| Situation | Behaviour |
| --- | --- |
| Backend unreachable (Mac off, `adb reverse` gone) | Saved notes, highlights, and progress work; new highlights and reading saves wait as pending; the library says it is showing saved data |
| GitHub unavailable or rate limited | 503 `GITHUB_UNAVAILABLE`; nothing changes; saved copies stay readable and are marked as saved copies |
| Server restarted or GitHub token expired | 401: the app asks to sign in again; saved data stays |
| Incomplete GitHub tree | Sync fails without changes; notes are never marked deleted from an incomplete listing |
| Note deleted upstream | Marked "Removed from the repository"; its progress, bookmark, and highlights are kept |
| Edit makes a highlight's place uncertain | `ORPHANED` with its original context; reattach it by selecting the passage |
| Old version gone from GitHub, or a huge edit | The change summary says so; no partial or invented summary |
| Oversized or unsafe note content | A visible error or blocked element; never executed |

No fixture or demo mode exists yet; every screen shows real GitHub data. A clearly labelled fixture demo is deferred until after two weeks of real use (Stage 6).
