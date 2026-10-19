# RepoRead

An Android reading and study layer for technical Markdown notes kept in GitHub: browse a repository, read code and Mermaid on the phone, resume where you stopped, highlight and annotate without touching the source, keep annotations trustworthy across edits, search saved notes, work offline, and practise interview questions with spaced review. GitHub stays the source of truth; RepoRead never writes to it.

| Part | What it is | Setup and contracts |
| --- | --- | --- |
| `backend/` | Spring Boot 4 / Java 25 / PostgreSQL 17: sign-in, connections, sync, rendering, reading state, annotations, re-anchoring | [backend/README.md](backend/README.md) |
| `android/` | Kotlin/Compose app with Room offline storage and an isolated WebView reader | [android/README.md](android/README.md) |
| `android/reader-web/` | The reader's bundled display JavaScript (highlighting, Mermaid) | [android/reader-web/README.md](android/reader-web/README.md) |
| `docs/` | The single source of truth for decisions, scope and status: [architecture](docs/architecture.md), [invariants](docs/invariants.md), [ADRs](docs/adrs.md), [feature list](docs/feature-list.md), [user stories](docs/user-stories.md) | [architecture](docs/architecture.md) |

## Local setup, in order

1. Start PostgreSQL (`docker compose -f backend/compose.yaml -p reporead up -d`) and the backend with the GitHub App client id and secret file — [backend/README.md](backend/README.md#run).
2. Install the reader's npm dependencies once and set up the signing key — [android/README.md](android/README.md).
3. Build and install the debug app, then `adb reverse tcp:8081 tcp:8081` so the phone reaches the Mac's loopback backend. Release builds use the hosted backend at `https://reporead.duckdns.org` instead — [backend/README.md](backend/README.md#hosted-vm).

## Tests

```sh
./gradlew :backend:spotlessCheck --no-daemon              # fails on unformatted code; :backend:spotlessApply fixes it
./gradlew :backend:test --no-daemon                       # PostgreSQL via Testcontainers; Docker must be running
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck --no-daemon   # fails on unformatted Kotlin; :app:spotlessApply fixes it
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:mergeDebugAssets --no-daemon   # the reader's syntax check and bundle, as packaged
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon   # device tests compile; they run on the phone
```

These are the checks CI runs. The app's wire models are generated from `backend/openapi.json`, which the backend derives from its controllers and records; the Android job also runs `./gradlew :backend:test --tests '*OpenApiSpecTest' --tests '*ItemRefusalsTest' --no-daemon`, so a spec that no longer matches the backend fails the gate. No production fixture mode is involved. Ownership and executed evidence are in [the API contract implementation](docs/audit-remediation-plan.md#step-9-verification).

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
| Oversized or unsafe note content | A visible error or blocked element; never executed |
| The reader's page renderer crashes, or Android reclaims it | That page is replaced by a message and the app keeps running; the reading position saved last stands |
| A backend deploy is not healthy | The CI run fails and the new jar stays; going back is by hand: the previous jar runs on the schema the new one left (ADR-24), and a dump taken just before the restart is on the VM |

No fixture or demo mode exists yet; every screen shows real GitHub data. A clearly labelled fixture demo is deferred until after two weeks of real use (Stage 6).
