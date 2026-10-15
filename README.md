# RepoRead

An Android reading and study layer for technical Markdown notes kept in GitHub: browse a repository, read code and Mermaid on the phone, resume where you stopped, highlight and annotate without touching the source, keep annotations trustworthy across edits, see what changed since you last read, search saved notes, work offline, and practise interview questions with spaced review. GitHub stays the source of truth; RepoRead never writes to it.

| Part | What it is | Setup and contracts |
| --- | --- | --- |
| `backend/` | Spring Boot 4 / Java 25 / PostgreSQL 17: sign-in, connections, sync, rendering, reading state, annotations, re-anchoring, change summaries | [backend/README.md](backend/README.md) |
| `android/` | Kotlin/Compose app with Room offline storage and an isolated WebView reader | [android/README.md](android/README.md) |
| `android/reader-web/` | The reader's bundled display JavaScript (highlighting, Mermaid) | [android/reader-web/README.md](android/reader-web/README.md) |
| `docs/` | The single source of truth for decisions, scope and status: [architecture](docs/architecture.md), [invariants](docs/invariants.md), [ADRs](docs/adrs.md), [feature list](docs/feature-list.md), [user stories](docs/user-stories.md) | [architecture](docs/architecture.md) |

## Local setup, in order

1. Start PostgreSQL (`docker compose -f backend/compose.yaml -p reporead up -d`) and the backend with the GitHub App client id and secret file — [backend/README.md](backend/README.md#run).
2. Install the reader's npm dependencies once and set up the signing key — [android/README.md](android/README.md).
3. Build and install the debug app, then `adb reverse tcp:8081 tcp:8081` so the phone reaches the Mac's loopback backend. Release builds use the hosted backend at `https://reporead.duckdns.org` instead — [backend/README.md](backend/README.md#hosted-vm).

## Tests

```sh
./gradlew :backend:test --no-daemon                       # PostgreSQL via Testcontainers; Docker must be running
./gradlew :backend:spotlessApply --no-daemon              # format the backend; CI fails on unformatted code (spotlessCheck)
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply --no-daemon   # format the app's Kotlin; CI fails on unformatted code
```

Device tests (Room on the phone) run with `adb shell am instrument`, never `connectedDebugAndroidTest` on a phone you read on — see [android/README.md](android/README.md#device-tests). The opt-in real-corpus harnesses (`AnchorMeasurement`, `MoveMeasurement` and `RenderedCorpus` in the backend, `StudyCorpus` in the app) are skipped unless pointed at a local clone of a notes repository; their outputs contain private note text and stay outside the repository ([ADR-23](docs/adrs.md#adr-23--how-a-change-is-verified)). The commands are in [backend/README.md](backend/README.md#verify).

## What failures look like

| Situation | Behaviour |
| --- | --- |
| Backend unreachable (no internet, VM down; for debug builds, Mac off or `adb reverse` gone) | Saved notes, highlights, and progress work; new highlights and reading saves wait as pending; the library says it is showing saved data |
| GitHub unavailable or rate limited | 503 `GITHUB_UNAVAILABLE`; nothing changes; saved copies stay readable and are marked as saved copies |
| GitHub sign-in can no longer be renewed (refresh token expired after about 6 months, or revoked on GitHub) | 401: the app asks to sign in again; saved data stays. Restarts and deploys keep sign-ins, and the 8-hour GitHub token is renewed automatically |
| Incomplete GitHub tree | Sync fails without changes; notes are never marked deleted from an incomplete listing |
| Note deleted upstream | Marked "Removed from the repository"; its progress, bookmark, and highlights are kept |
| Edit makes a highlight's place uncertain | `ORPHANED` with its original context; reattach it by selecting the passage |
| Old version gone from GitHub, or a huge edit | The change summary says so; no partial or invented summary |
| Oversized or unsafe note content | A visible error or blocked element; never executed |

No fixture or demo mode exists yet; every screen shows real GitHub data. A clearly labelled fixture demo is deferred until after two weeks of real use (Stage 6).
