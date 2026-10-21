# Cognitive-load refactor plan

Status: PR 1 implemented on `establish-language-remove-ambiguous-local-names`; PR 2 implemented on
`android-ownership-structure`; PR 3 implemented on `backend-roles-device-log-contract`.
Audit baseline: `main` at `92ca61b8830d71e855ecfc5fb8ea64d4d4ccbbcd` on 2026-10-21.

## Goal

Make the Android app, backend and deployment code easier to navigate and understand without changing user behaviour,
HTTP routes, JSON fields, database tables, scheduling rules, retry rules or external-call ceilings. A reader should be
able to infer a package, file, type and variable's responsibility from its name without first reading its implementation.

The main problems are generic containers (`core`, `data`, `Rows`), overloaded domain words (`document`, `note`,
`page`, `library`, `study`, `AppLog`), files with several independent owners, and generic local names (`id`, `row`,
`current`, `result`, `body`) in long coordination functions. Spotless and Android Lint already own formatting; this plan
adds vocabulary and decomposition rules rather than another formatter.

## Rules for all three PRs

- Each PR is behaviour-preserving. If implementation exposes a pre-existing defect, stop and handle it as a separately
  approved behaviour change. PR 1 has one such approved exception: `RequestLog` now retains raw request queries because
  this single-user system prioritizes diagnostic context over log privacy.
- A replacement updates every production caller, test, document and generated contract in the same PR. Do not leave
  aliases, forwarding wrappers, duplicate files or the old package in parallel.
- Keep HTTP paths and JSON field names stable. When a backend Java type rename changes an OpenAPI schema name, update
  `backend/openapi.json` and every Android wire-model caller in that PR.
- Keep Room table and column names stable. Package and Kotlin type moves must update the exported schemas and migration
  tests without creating a database migration unless Room proves one is required.
- Preserve WorkManager unique-work names and data keys so queued work remains readable across an app update.
- Preserve the device-log filenames and persisted JSON shape so logs written by an earlier app version remain uploadable.
- Do not add Detekt, Checkstyle or another dependency. Add automation only after repeated violations identify a rule that
  the existing formatter, compiler and Android Lint cannot enforce.
- Do not split `Sync`: it remains the one owner of synchronization ordering and its lock. Do not split the Room DAO merely
  by feature: it remains the transaction owner. Do not rewrite `Anchoring`'s algorithmic variables solely to avoid short
  mathematical names.
- A large file is not itself a defect. Extract only an existing responsibility that can be named without inventing a
  framework or general-purpose abstraction.

## Target vocabulary

The style guide introduced by PR 1 will own this glossary:

| Term | Meaning |
| --- | --- |
| repository | One connected GitHub repository. |
| document | The server-owned identity and current source metadata for one Markdown file. |
| saved page | The phone's rendered, offline copy of one document version. |
| annotation | A highlight or study card anchored to a passage. |
| review | One immutable grade event for a card. |
| library snapshot | The backend's complete response used to refresh the phone's local projection. |
| device log | A diagnostic line created and durably buffered by the Android app for backend ingestion. |

Boundary identifiers name their resource (`repositoryId`, `documentId`, `annotationId`, `cardId`), never just `id`.
HTTP values use `request` and `response` until validated or mapped; database values use their domain role rather than
`row` outside a short mapping. Booleans begin with `is`, `has`, `can` or `should`.

## PR 1 — Establish the language and remove ambiguous local names

### Objective

Create one written naming standard and apply the low-blast-radius renames that do not move packages, files, database
types or HTTP DTO owners. This gives later structural changes a stable vocabulary.

### Changes

1. Add `docs/code-style.md` and link it from the root, Android and backend READMEs. Keep it short and repo-specific:
   - the glossary above;
   - resource-qualified boundary identifiers;
   - boolean naming;
   - `request`/`response` and typed boundary names;
   - generic names allowed only in short, obvious scopes;
   - framework-role suffixes (`Controller`, `Filter`, `Worker`, `Configuration`, `Database`, `Dao`);
   - one primary responsibility per file;
   - early returns and named phases instead of deep nesting;
   - explicit exceptions for mathematical algorithms and short transformations.
2. In `android/core/network/Api.kt`, rename boundary parameters and operations with all callers:
   - plain `id` to `repositoryId`, `documentId`, `annotationId` or `cardId`;
   - `library()` to `fetchLibrarySnapshot()`;
   - `storedData()` to `repositoryDeletionCounts()`;
   - `noteContent()` to `fetchDocumentContent()`;
   - `json()` to `executeJsonRequest()`;
   - `Failure` to `ErrorResponse`;
   - locals such as `statuses`, `answer` and decoded `error` to `expectedStatuses`, `response` and `errorResponse`.
3. In Android feature and sync code:
   - delete the `ready = items`, `log = reviews` and `cap = limit` aliases in `study/Review.kt`; rename the source
     values once to `cards`, `reviewLog` and `sessionLimit`;
   - replace long-scope `row`, `item`, `current`, `id`, `json`, `answer` and `result` with the role actually held, such as
     `annotation`, `review`, `listedDocument`, `currentCardId`, `deletionCounts`, `revealedAnswer` and `saveResult`;
   - rename `triesAgain()` to `shouldRetry()`, `passing` to `retryable`, `after` to `lastLoggedAt`, `seen` to
     `lastLoggedExitFile`, and `toFetch` to `totalNotes` without changing WorkManager data-key strings in this PR;
   - rename parser symbols in `study/Study.kt`: `interviewBank()` to `parseInterviewBank()`, `bankQuestion()` to
     `parseQuestion()`, `Read` to `QuestionParseResult`, and its outcomes to `Parsed` and `Invalid`;
   - rename private `Sync` locals by responsibility while keeping `Sync` and its locking boundary intact.
4. In backend code, make long-scope locals state their role:
   - `AnnotationService`: `all` to `storedAnnotations`, `current` to `currentBlobSha`, `stale` to
     `annotationsToResolve`, `from` to `previousLocation`, `resolved` to `resolution`, and `hash` to
     `requestFingerprint`;
   - `GitHubApi`: distinguish response objects, installation nodes/ids, repository nodes/results, branch responses and
     tree entries instead of repeating `page`, `items`, `body` and `result`;
   - `RepositorySync`: use `markdownFiles`, `imagePaths`, `detectedMoves`, `publishedMoves`, `lockedConnection`,
     `publishedCommitSha` and `appliedMoves`.
5. In `backend/deploy`, retain every script and unit filename but expand shell identifiers:
   - `JAR`, `PREVIOUS`, `HEALTH`, `DIR`, `KEEP`, `KIND` to `BACKEND_JAR`, `PREVIOUS_BACKEND_JAR`, `HEALTH_URL`,
     `BACKUP_DIR`, `RETENTION_COUNT`, `BACKUP_KIND`;
   - `incoming`, `size`, `copy` to `incoming_jar`, `jar_size_bytes`, `backup_path`;
   - `healthy()` to `wait_until_healthy()` and `not_a_jar()` to `reject_non_backend_jar()`.

### Deliberately excluded

- No package, source-file or primary class moves.
- No `AppLog`/device-log rename; it crosses the generated API contract and belongs in PR 3.
- No control-flow extraction except deleting aliases and using early returns where behaviour is visibly identical.

### Verification

```sh
./gradlew :backend:spotlessCheck --no-daemon
./gradlew :backend:test --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:lintDebug --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:mergeDebugAssets --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
bash -n backend/deploy/reporead-deploy backend/deploy/reporead-backup \
  backend/deploy/reporead-backup-latest backend/deploy/reporead-backup-pull
```

PR 1 is complete when the guide is discoverable, all renamed callers and tests use the new vocabulary, the deleted
aliases are absent, and the full existing behaviour suite passes.

## PR 2 — Make Android navigation reflect ownership

### Objective

Remove generic package and filename layers, then separate files that already contain independently named Android
responsibilities. This PR changes source organization, not application behaviour or wire contracts.

### Changes

1. Flatten infrastructure packages:
   - `core/network/` to `network/`;
   - `core/log/` to `logging/`;
   - update the OpenAPI generator's Android model package and every main/test import;
   - remove the empty `core/` directory.
2. Rename the persistence package and types:
   - `data/` to `database/`;
   - `LocalStore` to `RepoReadDatabase`;
   - `LibraryDao` to `RepoReadDao`, while retaining it as the single cross-entity transaction owner;
   - `DocumentRow` to `DocumentEntity`, `NoteRow` to `SavedPageEntity`, `ReadingRow` to `ReadingStateEntity`, and the
     other Room records consistently from `*Row` to `*Entity`;
   - split `Rows.kt` into `Entities.kt` for Room tables and `QueryResults.kt` for projections/value results;
   - update Room schema exports, migrations, saved-state references, tests and documentation together.
3. Split `sync/BackgroundSync.kt` by its three existing WorkManager jobs:
   - `PendingSyncWorker.kt`;
   - `DeviceLogUploadWorker.kt` (the public symbols remain `AppLog*` until PR 3);
   - `SaveAllNotesWorker.kt`.
   Preserve unique-work names, retry limits, backoff, input/output keys and process-exit checkpoint semantics.
4. Rename ambiguous files:
   - `sync/ServerRows.kt` to `sync/WireMappings.kt`;
   - `study/Study.kt` to `study/InterviewBank.kt`;
   - `reader/Study.kt` to `reader/ReaderStudyMode.kt`.
5. Decompose large UI files by existing responsibility:
   - keep `ReaderScreen` as state/effect owner and extract `ReaderToolbar`, reader content, and `ReaderOverlayHost` into
     plainly named sibling files;
   - keep the review screen/session owner in `Review.kt`, and move answer loading/rendering and schedule outlook into
     `ReviewAnswer.kt` and `ReviewOutlook.kt`;
   - replace overlay payload names such as `row`, `current` and `mine` with `annotation`, `latestAnnotation` and
     `submittedNote`.
6. Keep `Sync.kt` as the coordinator. Rename its result types where they clarify intent (`LocalSync` to
   `PendingSyncResult`, `SavedAll` to `SaveAllResult`) but do not distribute its ordering decisions across new classes.

### Deliberately excluded

- No backend moves or OpenAPI schema-name changes beyond the Android package configured for generated models.
- No database table/column rename and no deliberate Room migration.
- No new view-state framework, use-case layer, repository wrapper or per-feature DAO.
- No arbitrary line-count target; the extracted files must each have one named owner.

### Verification

Run every Android gate because package moves touch production, unit-test, device-test and generated sources:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:lintDebug --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:mergeDebugAssets --no-daemon
./gradlew :backend:test --tests '*OpenApiSpecTest' --tests '*ItemRefusalsTest' --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```

Run the documented device-test command from `android/README.md` for the Room migration, sync and navigation suites on
the dedicated test app. Test a second launch against the same installed database so package/type changes do not only
work on a fresh install.

PR 2 is complete when the old packages/files/types are gone, generated models compile in their new package, existing
Room data opens without a destructive migration, queued workers remain readable, and the UI/navigation tests retain
their previous behaviour assertions.

## PR 3 — Name backend roles and the device-log contract end to end

### Objective

Finish the cross-process names that cannot safely be changed on only one side, clean the backend root package, and split
the one renderer function that combines unrelated transformations.

### Changes

1. Add `com.reporead.observability` and move/rename:
   - `RequestLog` to `RequestLoggingFilter`;
   - `JournalPriority` to `JournaldPriorityConverter`;
   - `AppLogController` to `DeviceLogIngestController`;
   - update Logback configuration, Spring tests, README references and generated OpenAPI ownership.
2. Rename the device-log flow end to end:
   - Android `AppLog` to `DeviceLog`;
   - `batch()` to `claimPendingBatch()`;
   - `sent()` to `finishClaimedBatch()` (it intentionally covers both accepted and permanently refused batches);
   - `uploadAppLog()` to `uploadDeviceLogBatch()`;
   - `AppLogUploadWorker` to `DeviceLogUploadWorker`;
   - backend/wire `Line` and `LineLevel` to `DeviceLogLine` and `DeviceLogLevel`;
   - backend `readable()` to `formatForJournal()`.
   Keep `/api/app-logs`, payload field names, stored filenames and stored JSON compatible.
3. Make backend framework roles visible:
   - `GitHubHttp` to `GitHubHttpConfiguration`, with bean names that say HTTP client rather than API;
   - `Library` to `LibrarySnapshotReader`; move its response data to a plainly named `LibrarySnapshot` record so the
     published schema remains meaningful;
   - replace nested `RepositorySync.Result` with `RepositorySyncResult`, and rename `publish()` to
     `fetchAndPublishSnapshot()`;
   - keep the plural persistence components (`Documents`, `Annotations`, `Bookmarks`, `Reviews`, `ReadingStates`,
     `RepositoryConnections`) because that convention is already consistent.
4. Simplify `MarkdownRenderer.linkNotes()` into named phases for Markdown-link rewriting, candidate text-node selection,
   wikilink parsing/replacement and embed construction. Remove its ignored integer return; preserve the existing image
   limit and rendered HTML exactly. Rename transformation locals (`whole`, `parts`, `last`, `inner`, `bar`, `hash`) by
   their parsing role.
5. Update `docs/architecture.md`, Android/backend READMEs and this plan's status. Remove references to every superseded
   name rather than documenting both.

### Deliberately excluded

- No route, JSON-field, logging-level, retry-policy, request-limit or renderer-output change.
- No retry/fallback added to device-log upload.
- No broad split of `MarkdownRenderer`, `Annotations`, `GitHubApi` or `Anchoring`; only the independently named
  `linkNotes()` phases move.
- `AnnotationService` keeps its type name unless the PR can identify one precise owner name that covers create,
  reattach and re-anchor without using another generic suffix.

### Verification

Run the full backend and Android gates because controller/record names feed the generated client:

```sh
./gradlew :backend:spotlessCheck --no-daemon
./gradlew :backend:test --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:lintDebug --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:mergeDebugAssets --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --no-daemon
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
```

In addition, extend the existing device-log tests to prove:

- a pending line written in the pre-rename persisted shape uploads after the update;
- a lost acknowledgement leaves one replayable claimed batch;
- success removes the claimed batch;
- the one permanent `INVALID_REQUEST` refusal removes it under the accurately named completion operation;
- signed-out execution leaves the batch untouched;
- a second run does not resend a successfully completed batch.

Retain renderer fixture assertions byte-for-byte while splitting `linkNotes()`. PR 3 is complete when the old backend and
Android log names are absent, the committed OpenAPI document matches the renamed source types, the generated app models
compile, persisted logs remain readable, and all existing behavioural assertions pass.

## End state

The Android source tree has direct `network`, `logging` and `database` packages; worker and UI files name one existing
responsibility; persistence types distinguish server metadata from saved rendered pages. The backend root contains app
configuration and cross-cutting primitives rather than unnamed observability implementations. Variables name their
domain role at boundaries and in coordination code, while short mappings and algorithms keep appropriately compact
names. The codebase has one concise style guide and no parallel compatibility vocabulary.
