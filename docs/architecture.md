# RepoRead architecture

How RepoRead is built today: the parts, who owns what, and how data moves between them. The *why* behind each decision is in the [ADRs](adrs.md); the exact HTTP contract, limits and error codes are in the [backend README](../backend/README.md); phone behaviour is in the [Android README](../android/README.md). The rules every change keeps are in [invariants](invariants.md). This document links to them instead of repeating them.

As built on `main` at `b148d9d`, 2026-10-22. This describes source behavior, not the hosted or installed revision.

## 1. The system in one picture

RepoRead is a reading and study layer over Markdown notes in GitHub. Three things hold state, and each owns a different part of it:

| Owner | Holds | Never holds |
| --- | --- | --- |
| **GitHub** | The notes themselves (canonical, read-only to RepoRead) | Anything of RepoRead's |
| **Backend** (Spring Boot + PostgreSQL) | Users, sessions, each user's GitHub tokens (encrypted), repository connections, document identity and versions, reading state, bookmarks, highlights, cards, the review log; every reconciliation decision | Note bodies (fetched by blob SHA on demand, never cached) |
| **Phone** (Android + Room) | A projection of the server's lists, saved rendered copies of opened notes, pending local changes, the review schedule | GitHub credentials |

```mermaid
flowchart LR
    subgraph Phone["Android phone"]
        App["RepoRead app<br/>Kotlin / Compose"]
        Room[("Room<br/>offline projection<br/>+ pending changes")]
        WV["Isolated WebView<br/>reader-web bundle"]
        Tab["Custom Tab<br/>sign-in only"]
        App <--> Room
        App -- "evaluateJavascript only<br/>no JS bridge" --> WV
    end

    subgraph Server["Backend host"]
        API["Spring Boot backend<br/>binds 127.0.0.1:8081"]
        PG[("PostgreSQL 17<br/>loopback only")]
        API <--> PG
    end

    GH["GitHub<br/>REST API + OAuth<br/>canonical, never written"]

    App -- "HTTPS JSON<br/>Bearer app session" --> API
    Tab -- "OAuth + PKCE" --> API
    API -- "user token, no retries,<br/>stated call ceiling per route" --> GH
```

Two rules shape everything below:

- **GitHub is the source of truth and is never written to** ([ADR-02](adrs.md#adr-02--github-is-canonical-and-read-only-note-bodies-are-not-stored-on-the-server)). A note's content is always addressed by its blob SHA.
- **The server renders, the phone displays** ([ADR-04](adrs.md#adr-04--the-server-renders-an-isolated-webview-displays)). One Java renderer defines the HTML and each block's canonical text, so a selection on the phone means the same thing on the server.

## 2. Deployment and delivery

Release builds talk to a hosted backend; debug builds talk to the developer's Mac. Both run the same jar.

```mermaid
flowchart TB
    subgraph Dev["Developer Mac"]
        DevAPI["backend via bootRun<br/>127.0.0.1:8081"]
        DevPG[("PostgreSQL 17.11<br/>Docker, 127.0.0.1:5435")]
        DevAPI --> DevPG
    end

    subgraph VM["Oracle Cloud Always Free VM · reporead.duckdns.org"]
        Caddy["Caddy :443<br/>Let's Encrypt"]
        Svc["reporead-backend.service<br/>127.0.0.1:8081<br/>Actuator 127.0.0.1:8082"]
        VMPG[("reporead-postgres<br/>Podman Quadlet, loopback")]
        Deploy["reporead-deploy<br/>dumps the database, health-checked,<br/>never puts the previous jar back"]
        Caddy --> Svc --> VMPG
        Deploy -- "replace jar, restart" --> Svc
    end

    subgraph GHA["GitHub Actions on push to main"]
        BW["backend.yml<br/>tests → bootJar"]
        AW["android.yml<br/>unit tests, reader bundle → signed release APK"]
    end

    Rel["GitHub Release v0.1.N"]
    DebugApp["Debug build"]
    ReleaseApp["Release build"]
    Obt["Obtainium"]

    DebugApp -- "adb reverse tcp:8081" --> DevAPI
    ReleaseApp -- "HTTPS" --> Caddy
    BW -- "jar over SSH,<br/>forced-command key" --> Deploy
    AW --> Rel --> Obt -- "installs over the app" --> ReleaseApp
```

- Only 22, 80 and 443 are open on the VM; PostgreSQL, the API port and Actuator listen on loopback ([ADR-14](adrs.md#adr-14--hosted-backend-on-an-oracle-cloud-always-free-vm-debug-builds-stay-on-the-mac)).
- Every build is signed with one personal key, so each release installs over the last and keeps the phone's data; the debug build is a separate app beside it, with its own data and sign-in redirect ([ADR-12](adrs.md#adr-12--personal-distribution-one-signing-key-backend-over-adb-reverse), [ADR-15](adrs.md#adr-15--app-releases-through-github-releases-installed-by-obtainium)).
- Restarts and deploys keep GitHub sign-ins: tokens are stored encrypted in PostgreSQL under a key file kept on the host, outside the database ([ADR-03](adrs.md#adr-03--github-user-tokens-stay-on-the-server-encrypted-at-rest)).

## 3. Backend

One Spring Boot 4 / Java 25 service, a modular monolith with one package per concern (`backend/src/main/java/com/reporead/`). Data access is plain Spring JDBC; the schema is owned by Flyway migrations (`backend/src/main/resources/db/migration`).

### Packages and their dependencies

Arrows point from a package to the packages it calls (from the imports in each file). The packages form a stack with no cycles: each calls only packages below it, and `deletion`, which deletes across every owner, and `library`, which reads the phone's lists across their owners in one snapshot, are on top. Every package with routes also reads the signed-in user (`auth.AppUser`); those arrows are left out. The stack is of imports: SQL reads do cross owners (`Documents` checks reading and annotation rows, `RepositoryConnections` counts documents), which ADR-01 allows, since each table's writes stay with its one owner. Controllers call owners only; no controller calls GitHub.

```mermaid
flowchart TB
    subgraph HTTP["Controllers"]
        direction LR
        AuthC["AppAuthController"]
        RepoC["RepositoryController"]
        SnapshotRefreshC["RepositorySnapshotRefreshController"]
        DocC["DocumentController"]
        ReadC["ReadingController"]
        AnnC["AnnotationController<br/>BookmarkController<br/>ReviewController"]
        DelC["DeletionController"]
        LibC["LibraryController"]
        LogC["DeviceLogIngestController"]
    end

    deletion["deletion<br/>disconnect, stored data,<br/>account deletion"]
    observability["observability<br/>request and device-log boundaries"]
    library["library<br/>LibrarySnapshotReader: every list the phone saves,<br/>one read-only snapshot"]
    sync["sync<br/>RepositorySnapshotRefresh: publish GitHub-backed snapshot<br/>ContentMoves: moved-and-edited"]
    annotation["annotation<br/>Annotations, Anchoring,<br/>Bookmarks, Reviews"]
    reading["reading<br/>ReadingStates"]
    document["document<br/>MarkdownRenderer, NoteVersions,<br/>Attachments"]
    repository["repository<br/>connections, per-connection lock"]
    auth["auth<br/>sign-in, app sessions,<br/>bearer auth, GitHub token renewal"]
    github["github<br/>GitHubApi: every GitHub read<br/>GitHubHttpConfiguration: timeouts, size limits"]

    AuthC --> auth
    RepoC --> repository
    SnapshotRefreshC --> sync
    DocC --> document
    ReadC --> reading
    AnnC --> annotation
    DelC --> deletion
    LibC --> library
    LogC --> observability

    deletion --> annotation
    deletion --> reading
    deletion --> document
    deletion --> repository
    deletion --> auth
    library --> annotation
    library --> reading
    library --> document
    library --> repository
    sync --> document
    sync --> repository
    sync --> auth
    sync --> github
    annotation --> document
    annotation --> repository
    reading --> document
    reading --> repository
    document --> repository
    document --> auth
    document --> github
    repository --> auth
    repository --> github
    auth --> github

    github ==> GH(["GitHub"])
```

| Package | Owns | Key classes |
| --- | --- | --- |
| `auth` | GitHub OAuth (Spring Security), single-use sign-in codes bound to a PKCE challenge, 30-day bearer sessions (only hashes stored), the encrypted stored GitHub token (`GitHubTokens`) and its renewal and revocation (`GitHubTokenLifecycle`) | `ApiSecurity` (loopback only, the API and management chains), `GitHubBrowserSignIn` (browser sign-in), `GitHubTokens`, `GitHubTokenLifecycle`, `AppSignInSuccess`, `AppSessions`, `BearerAuthentication` |
| `github` | Every GitHub REST read, with the user's token; the HTTP clients every GitHub call goes through: 15 s timeouts, no redirects, no retries, response size limits (the largest note RepoRead reads), and the count of every GitHub request | `GitHubApi`, `GitHubHttpConfiguration` |
| `repository` | Which repositories a user may connect (GitHub decides, through the user's token) and has connected; the connection row lock every write shares | `RepositoryConnections` |
| `sync` | Refreshing and publishing a complete default-branch snapshot; document identity across moves ([ADR-07](adrs.md#adr-07--document-identity-from-complete-snapshots)) | `RepositorySnapshotRefresh`, `ContentMoves` |
| `document` | Rendering and sanitizing Markdown into reader HTML with canonical block text; fetching a version by blob SHA; images and Obsidian embeds | `MarkdownRenderer`, `NoteVersions`, `Attachments` |
| `reading` | Last-read version, position and progress per note; last write wins by the client's time | `ReadingStates` |
| `annotation` | Highlights, cards and bookmarks; idempotent creation by mutation id; re-anchoring ([ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan)); the immutable review log | `Annotations`, `AnnotationService` (creation, reattachment and re-anchoring against note versions), `Anchoring`, `Bookmarks`, `Reviews` |
| `deletion` | Disconnecting a repository and deleting the account: what it would delete, and deleting it through each table's owner in one transaction ([ADR-11](adrs.md#adr-11--disconnecting-deletes-everything-stored-for-that-repository-account-deletion-deletes-everything)) | `ConnectionDeletion`, `AccountDeletion`, `DeletionController` |
| `library` | The phone's one read of the server's lists: repositories with every document, reading states, bookmarks and the notebook, each from its owner, in one read-only `REPEATABLE READ` transaction of a fixed 6 queries, refused over 8 MiB ([ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes)) | `LibrarySnapshotReader`, `LibrarySnapshot`, `LibraryController` |
| `observability` | The request id and outcome boundary; journald priority conversion; authenticated ingestion of typed device-log batches into the server journal, with no stored copy | `RequestLoggingFilter`, `JournaldPriorityConverter`, `DeviceLogIngestController`, `DeviceLogLine` |
| root | Error responses (`{code, message}`), shared PostgreSQL text-storability and hash rules | `ApiErrors`, `ApiFailure`, `StoredText`, `Hashes` |

### Data model (PostgreSQL)

Highlights and cards share the `annotations` table, told apart by `type`; a bookmark is a row of `bookmarks` (ADR-17). Until ADR-17's contract step, the `annotations` type check still admits the `BOOKMARK` rows V17 moved out. A highlight or card has an original anchor that never changes and a current location that re-anchoring moves. Every column of every table is listed, in the database's order, as the Flyway migrations leave it.

```mermaid
erDiagram
    users ||--o{ app_sessions : "signs in with"
    users ||--o{ app_sign_in_codes : "exchanges"
    users ||--o{ repository_connections : connects
    repository_connections ||--o{ documents : "has (path-keyed, soft-deleted)"
    repository_connections ||--o{ repository_images : "snapshot image paths"
    users ||--o{ reading_states : reads
    documents ||--o{ reading_states : "read as"
    users ||--o{ bookmarks : bookmarks
    documents ||--o{ bookmarks : "bookmarked as"
    users ||--o{ annotations : owns
    documents ||--o{ annotations : "annotated by"
    annotations ||--o| annotation_anchors : "original selection"
    annotations ||--o| annotation_locations : "current location"
    users ||--o{ annotation_mutations : "idempotency records"
    annotations |o--o| annotation_mutations : "created by (user_id + mutation_id, no FK; the record outlives a deletion)"
    annotations ||--o{ review_log : "graded in"
    users ||--o{ review_log : grades
    users ||--o| github_tokens : "signs in to GitHub with (by github_user_id, no FK)"

    users {
        bigint id PK
        bigint github_user_id UK
        text login
        timestamptz created_at
    }
    app_sessions {
        bytea token_hash PK "SHA-256 of the session token"
        bigint user_id FK
        timestamptz created_at
        timestamptz expires_at "30 days"
    }
    app_sign_in_codes {
        bytea code_hash PK "SHA-256 of the sign-in code"
        bigint user_id FK
        text code_challenge "S256 PKCE challenge"
        timestamptz expires_at "60 seconds"
    }
    github_tokens {
        bigint github_user_id PK
        bytea access_token "AES-256-GCM"
        timestamptz access_token_issued_at "nullable"
        timestamptz access_token_expires_at "nullable"
        bytea refresh_token "AES-256-GCM, nullable"
        timestamptz refresh_token_issued_at "nullable"
        timestamptz updated_at
    }
    repository_connections {
        bigint id PK
        bigint user_id FK "unique with github_repository_id"
        bigint github_repository_id
        text owner
        text name
        text default_branch
        text last_synced_commit_sha "nullable until the first sync"
        timestamptz created_at
    }
    documents {
        bigint id PK
        bigint repository_connection_id FK "unique with path"
        text path
        text title
        text current_blob_sha
        timestamptz deleted_at "nullable; soft delete"
        timestamptz content_changed_at "nullable"
    }
    repository_images {
        bigint repository_connection_id PK, FK
        text path PK
    }
    reading_states {
        bigint user_id PK, FK
        bigint document_id PK, FK
        text last_read_blob_sha
        smallint progress_percent "0..100"
        jsonb anchor_json
        timestamptz last_read_at
    }
    bookmarks {
        bigint user_id PK, FK
        bigint document_id PK, FK
        timestamptz created_at
    }
    annotations {
        bigint id PK
        bigint user_id FK
        bigint document_id FK
        text type "HIGHLIGHT | CARD"
        timestamptz created_at
        text note "nullable"
        text status "ANCHORED | REANCHORED | ORPHANED"
        int version "optimistic"
        timestamptz updated_at
        text resolved_blob_sha "highlights and cards"
        text question "cards only"
        uuid mutation_id "highlights and cards; unique per user"
    }
    annotation_anchors {
        bigint annotation_id PK, FK
        text source_blob_sha
        text block_id
        text exact_text
        text prefix_text
        text suffix_text
        int start_offset
        int end_offset
        jsonb heading_path
    }
    annotation_locations {
        bigint annotation_id PK, FK
        text source_blob_sha
        text block_id
        text exact_text
        text prefix_text
        text suffix_text
        int start_offset
        int end_offset
        jsonb heading_path
        text block_sha "nullable: the block's text is not unique in that version"
        int quote_occurrences
        float8 rival_context
        float8 rival_quote
    }
    annotation_mutations {
        bigint user_id PK, FK
        uuid mutation_id PK
        bytea request_hash
        timestamptz created_at
    }
    review_log {
        bigint user_id PK, FK
        uuid mutation_id PK
        bigint annotation_id FK
        int grade "0..5"
        timestamptz reviewed_at "clamped scheduling time"
        text blob_sha
        text client_reviewed_at "nullable original request; V15"
    }
```

Concurrency: new highlight/card admission first serializes on the user row (`FOR NO KEY UPDATE`), then shares the connection lock, so the per-document and user-wide caps cannot be exceeded by concurrent creations across repositories. Exact replays bypass capacity; no GitHub I/O runs under these locks. Other document writes do not acquire this admission lock. Syncs, reconnects (whose upsert locks the row), disconnects and every write that adds or changes a document's rows take the same `repository_connections` row lock (`RepositoryConnections.lockForSync` and its siblings), so a write never lands half-way through a sync or a disconnect. Each table's owner takes the lock inside its own transaction, and the lock methods refuse to run outside one.

## 4. Android app

One Activity (`MainActivity`) hosting Compose (`RepoReadApp`), whose back stack Navigation 3 owns: `Entry(id, screen)` keys saved with the activity and across process death, each entry keeping saved UI state and a ViewModel store while in the stack. Activity state restores only when its saved format marker is compatible, independently of build number (ADR-27). Each screen that starts work has one ViewModel in its entry, which owns its loads, operations and outcomes; screens draw its state and call its actions (ADR-28). No DI framework; background work runs only in WorkManager: the background sync of local changes (ADR-08) and Save all (ADR-19).

### Screens and layers

Submitted edit/conflict state belongs to the reader's ViewModel, restoring only the submitted id/text and reloading
the annotation. In-progress reattachment also restores through its SavedStateHandle. Unsubmitted overlays remain
UI-owned; rejection cannot close their editor (ADR-28).

```mermaid
flowchart TB
    Main["MainActivity<br/>receives the sign-in redirect"]
    App["RepoReadApp<br/>Navigation 3 back stack of Entry"]
    Main --> App

    subgraph Screens["Screens"]
        Library["Library<br/>tabs: Reading · Bookmarks · Practice · Repositories"]
        Folder["Folder"]
        Available["Available repositories"]
        Reader["Reader<br/>one saved overlay (ADR-25)"]
        Notebook["Notebook"]
        Practice["Practice session"]
        Review["Review session"]
        Search["Search"]
    end
    App --> Screens

    Models["Their entries' ViewModels (ADR-28)<br/>Library · Folder · Available · Practice · Review · Reader"]
    Screens -- "state ↑ · actions ↓" --> Models

    subgraph Core["Shared code"]
        PhoneSync["sync/PhoneSync<br/>owns every server call<br/>whose answer reaches Room"]
        Store["database/RepoReadDatabase<br/>Room database + DAO"]
        Api["network/Api<br/>typed routes + bounded HTTP"]
        Images["sync/NoteImages<br/>saved notes' images,<br/>reader reads outside PhoneSync lock"]
        Session["auth/SessionStore<br/>Keystore AES-GCM bearer token"]
        Sched["study/ReviewSchedule<br/>pure review scheduler"]
        StudyS["study/StudySession<br/>pure session progress"]
        StudyK["study/InterviewBank<br/>bank recognition"]
    end

    subgraph ReaderHost["Reader hosting"]
        StudyView["reader/ReaderStudyMode<br/>LeetCode study-view recognition"]
        Page["reader/ReaderPage<br/>isolation + resource interception<br/>+ the one page-script boundary"]
        WebV["WebView<br/>reader.js · Mermaid · highlight.js"]
        Page --> WebV
    end

    Models --> PhoneSync
    Models --> Store
    Screens -- "Room flows" --> Store
    Practice --> StudyK
    Review --> StudyK
    Practice --> Sched
    Review --> Sched
    Practice --> StudyS
    Review --> StudyS
    Reader --> Page
    Reader --> StudyView
    PhoneSync --> Api
    App -- "sign-in, sign-out" --> Api
    Models -- "Add repository's list" --> Api
    PhoneSync --> Store
    Api --> Session
    Page -- "images" --> Images
    PhoneSync --> Images
    Images --> Api
```

| Package | Owns |
| --- | --- |
| (root) | `RepoReadApplication`: long-lived objects, including one `PhoneSync` per process and its startup image sweep; `Account`: sign-in, sign-out and account deletion, one at a time in the app's scope, and their outcomes (ADR-22); `RepoReadApp`: Navigation 3 back stack/entry owners (ADR-27), retained across account operations; `MainActivity`: sign-in redirect and compatible saved-state restoration |
| `auth` | PKCE sign-in through a Custom Tab and validated code exchange; `SessionStore`: encrypted bearer session and pending verifier, no local-data owner or removal state |
| `network` | `Api`: the only executor of backend paths, with one named function per app-used route, exact success statuses, bounded responses and request/response correspondence; `wire/`: the wire models, generated at build time from `backend/openapi.json` (never committed or edited); `WireRules.kt`: the value rules a generated shape cannot state; `WireJson.kt`: one decoding policy, strict scalar/time serializers and `INVALID_RESPONSE` with its cause. Unknown fields are compatible, missing required-nullable keys are not. `ApiException(status, code)` carries a strictly decoded server error or a local HTTP/I/O failure; any 401 ends the stored session (`SessionStore.endRefused`), which signs the app out wherever the call was made; a stored session it cannot read now fails the request unsent (`SESSION_UNREADABLE`) |
| `logging` | `DeviceLog`: logcat plus the bounded durable device-log store, whose `pending.jsonl` and `sending.jsonl` filenames and JSON fields remain stable across app updates. `DeviceLogUploadWorker` (in `sync/`, with the other WorkManager jobs) makes at most two upload requests per run (a failed run's batch, then the lines since) and never retries on a backoff; a warning requests a run only when no batch is claimed |
| `database` | `RepoReadDatabase`: the Room database and its migrations; `Entities.kt`: its tables; `QueryResults.kt`: its query projections and database-local results; `RepoReadDao.kt`: its queries and cross-entity transactions (schema history in `android/schemas/`) |
| `sync` | `PhoneSync`: the process's one local copy, refreshes, opening notes, pending/local changes, annotation/card mutations, disconnect and image sweeps. Its lock orders local mutations and server answers; `committed` protects accepted screen mutations through local commit (ADR-28). `NoteImages` retains its file lock and deletion generation, preventing a late image fetch from republishing files after a sweep. An open sweeps after its fetched page; Save all sweeps once after a successful loop under cancellable `locked`, rather than after every fetched page. Failure/cancellation defer cleanup to the next library refresh, Library open or startup. Library replacement, disconnect and startup retain their sweeps. Save all checks one page at a time, including already-current copies, and prefetches its distinct images under the existing per-page PhoneSync lock before progress; WebView image reads remain outside that lock (ADR-19). Only the reader's `NoteImages.image()` catches API failures as unavailable; Save all uses the throwing fetch, `PhoneSync` owns permanent page skips, and the existing worker owns retry/stop for all other API failures. `WireMappings.kt` maps validated wire records; `PhoneSync` owns disposition: `refusesItem()` classifies annotation, review and bookmark refusals; the reading send handles only 404 `NOT_FOUND` inline and raises every other failure, including a malformed-save 400. `PendingSyncWorker.kt`, `DeviceLogUploadWorker.kt` and `SaveAllNotesWorker.kt` own their independently scheduled WorkManager jobs; `WorkerRetry.kt` owns their shared fixed retry ceiling and backoff. Pending sync and Save all enter `PhoneSync` directly, without waiting for account operations; repository Stop awaits cancellation. Account operations never clear Room/images or cancel/prune work. Account calls Api for sign-in, sign-out and deletion; Add repository lists through Api and connects through PhoneSync |
| `library` | Library tabs, Notebook (`Notebook.kt`), the folder screen and folder filters (`Folder.kt`), and Add repository (`Available.kt`); each work-owning screen's ViewModel is in its file (ADR-28) |
| `study` | Practice (`Practice.kt`) and Review's screen/session owner (`Review.kt`) with their entry ViewModels; `ReviewAnswer.kt` owns answer loading/rendering, and `ReviewOutlook.kt` owns the midnight/time-zone-aware outlook used by Library, Practice and Review completion; the pure review scheduler (`ReviewSchedule.kt`), session progression and note interleaving (`StudySession.kt`), bank recognition (`InterviewBank.kt`) and bank answer views (`Answer.kt`) |
| `reader` | The reader state/effect owner and ViewModel (`Reader.kt`, including visible bookmark rejection for a disconnected note); its toolbar/system bars (`ReaderToolbar.kt`), note and notes-panel rendering (`ReaderContent.kt`), and saved overlays (`ReaderOverlayHost.kt`); page hosting and isolation with one JSON decode boundary in `ReaderPage.evaluate`; outline, notes, backlinks, link resolution and previews; and LeetCode study-view recognition (`ReaderStudyMode.kt`) |
| `ui` | Shared Compose components, including the screen load state and its retry presentation (`Load.kt`) |
| `search` | Local substring search over saved titles, canonical note blocks and highlights; body results carry their exact Reader block ([ADR-10](adrs.md#adr-10--search-is-local-substring-matching-over-what-the-phone-has-saved)) |
| `reader-web/` | The page's display JavaScript and CSS, bundled by esbuild into the APK; parses no Markdown and makes no network requests |

`study` uses `reader`'s link parsing and resolution; `reader` does not import `study`.

### Room: a projection plus pending changes

Room never decides anything the server owns; it mirrors complete server responses and queues the user's own changes until acknowledged ([ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes)).

| Table | Comes from | Pending local changes? |
| --- | --- | --- |
| `repositories`, `documents` | Server's complete lists (replaced, never merged), with every other list from one library answer in one transaction | No |
| `notes` | Latest rendered copy of each opened note, one per document with its blob SHA | No |
| `note_search_blocks` | Server-issued canonical block IDs/text for each saved page, written and deleted with it | No |
| `note_links` | The note links of each saved page, written and deleted with it, for **Linked from** | No |
| `reading_states`, `bookmarks` | Server lists, merged with local saves | Yes, last write wins by time |
| `annotations` | Notebook and per-note listings | Yes: creations (with mutation id) and deletions of unacknowledged creations |
| `review_log` | Server's durable log | Yes: grades (with mutation id) |

The columns, as the latest exported schema (`android/schemas/`) lists them, in its order. Only `note_search_blocks` and `note_links` have foreign keys, to `notes` with delete cascade; every other reference is by id and not enforced by the database. Booleans are stored as 0/1 integers, and an embedded passage is stored as columns prefixed with its field name (`location…`).

```mermaid
erDiagram
    repositories ||--o{ documents : "has (repositoryId, no FK)"
    documents ||--o| notes : "saved as (documentId, no FK)"
    notes ||--o{ note_search_blocks : "canonical blocks (FK, cascade)"
    notes ||--o{ note_links : "links out (FK, cascade)"
    documents ||--o| reading_states : "read as (no FK)"
    documents ||--o| bookmarks : "bookmarked as (no FK)"
    documents ||--o{ annotations : "annotated by (no FK)"
    annotations ||--o{ review_log : "graded in (cardMutationId, no FK)"

    repositories {
        integer id PK
        text fullName
        integer documentCount
        text lastSyncedCommitSha "nullable"
    }
    documents {
        integer id PK
        integer repositoryId "indexed"
        text path
        text title
        text blobSha
        integer changedAt "nullable; epoch ms the refresh found it new or changed"
        integer deleted "the latest complete refresh did not list it"
    }
    notes {
        integer documentId PK
        text blobSha
        text path
        text title
        text html
        integer fetchedAt
        integer renderFormat "0 = saved before formats were recorded"
        text snapshotCommitSha "nullable; null = legacy page, images never fetched"
    }
    note_search_blocks {
        integer documentId PK, FK
        text blockId PK
        integer position "unique with documentId"
        text text
    }
    note_links {
        integer documentId PK, FK
        integer position PK
        text query
    }
    reading_states {
        integer documentId PK
        text lastReadBlobSha
        integer progressPercent
        text anchorJson
        integer lastReadAt
        integer pending "unacknowledged local save"
    }
    bookmarks {
        integer documentId PK
        integer bookmarked "0 = pending removal"
        integer pending
        integer changedAt
    }
    annotations {
        text mutationId PK "client UUID"
        integer serverId "nullable"
        integer documentId "indexed"
        text sourceBlobSha "original selection: this, blockId, both offsets, exactText, prefixText, suffixText"
        text blockId
        integer startOffset
        integer endOffset
        text exactText
        text note "nullable"
        integer version
        integer createdAt
        text state "UNSENT | REFUSED | DELETING | ACKNOWLEDGED"
        text rejection "nullable; set only when REFUSED"
        text prefixText
        text suffixText
        text status "nullable; ANCHORED | REANCHORED | ORPHANED"
        text resolvedBlobSha "nullable"
        text type "HIGHLIGHT | CARD"
        text question "nullable; cards only"
        text locationblobSha "nullable; the server's location: this and the next four"
        text locationblockId "nullable"
        integer locationstartOffset "nullable"
        integer locationendOffset "nullable"
        text locationexactText "nullable"
    }
    review_log {
        text mutationId PK "client UUID, shared with the server's log"
        text cardMutationId "the card's annotations.mutationId; indexed"
        integer grade
        integer reviewedAt
        text blobSha
        integer pending
        text rejection "nullable"
    }
```

## 5. Key flows

### Sign-in

The phone never sees a GitHub credential. It gets an opaque app session in exchange for a one-time code bound to its own PKCE challenge.

```mermaid
sequenceDiagram
    autonumber
    participant App as Phone app
    participant Tab as Custom Tab
    participant API as Backend
    participant GH as GitHub

    App->>App: create PKCE verifier + S256 challenge
    App->>Tab: open /app/sign-in?code_challenge=…
    Tab->>API: GET /app/sign-in (challenge)
    API->>GH: OAuth authorize (Spring Security: state, PKCE)
    GH-->>Tab: redirect to /login/oauth2/code/github
    Tab->>API: callback with code
    API->>GH: exchange code → user token + refresh token
    API->>API: store tokens encrypted (github_tokens), upsert user,<br/>issue single-use 60 s code bound to challenge,<br/>end the browser session
    API-->>Tab: redirect https://…/app/signed-in?code=… (a verified App Link,<br/>reporead-dev://auth for the debug app)
    Tab-->>App: Android opens the link in MainActivity
    App->>API: POST /api/app-auth/token {code, codeVerifier}
    API-->>App: {accessToken, expiresAt, user} (30 days)
    App->>App: encrypt token with Keystore key
    Note over App,API: every /api/** call: Authorization: Bearer token.<br/>Any 401 means sign in again, and saved data stays readable.
```

### Refreshing a repository (sync)

A repository snapshot refresh publishes the default branch's complete snapshot or changes nothing ([ADR-07](adrs.md#adr-07--document-identity-from-complete-snapshots)).

```mermaid
sequenceDiagram
    autonumber
    participant App as Phone (PhoneSync)
    participant API as RepositorySnapshotRefresh
    participant DB as PostgreSQL
    participant GH as GitHub

    App->>API: POST /api/repositories/{id}/sync
    API->>GH: default branch → commit SHA
    API->>GH: recursive tree at that commit
    alt tree truncated or malformed
        API-->>App: 502 GITHUB_TREE_INCOMPLETE, nothing changed
    else complete tree
        API->>API: keep regular .md blobs, match documents by path,<br/>then unique-blob moves
        opt vanished documents with user data (≤ 128 candidates)
            API->>GH: up to 128 blob reads, ≤16 in flight process-wide
            API->>API: shingle similarity → moved and edited?
        end
        API->>DB: lock connection row (FOR UPDATE)
        alt another sync already published a newer commit, or a reconnect changed the default branch
            API-->>App: 409 SYNC_SUPERSEDED
        else
            API->>DB: one transaction: upsert documents, mark absent ones deleted,<br/>replace image paths, advance checkpoint
            API-->>App: 200
            App->>API: GET /api/library
            API->>DB: one read-only REPEATABLE READ snapshot:<br/>repositories, documents, reading states, bookmarks, notebook
            API-->>App: every list
            App->>App: replace Room's lists in one transaction
        end
    end
```

### Opening and displaying a note

```mermaid
sequenceDiagram
    autonumber
    participant R as Reader screen
    participant S as PhoneSync
    participant Room as Room
    participant API as Backend
    participant GH as GitHub
    participant WV as Isolated WebView

    R->>S: openNote(documentId)
    S->>Room: saved copy for this document?
    alt saved copy matches blob SHA, path and page format (snapshot too for repository images or blocked embeds)
        Room-->>S: saved note (no network)
    else
        S->>API: GET /api/documents/{id}/content
        API->>API: capture document/checkpoint and image paths<br/>in one short read-only REPEATABLE READ transaction
        API->>GH: raw blob at the captured SHA (1 call)
        API->>API: CommonMark/GFM → escape raw HTML → jsoup safelist<br/>→ block ids + canonical text → bind captured embed paths<br/>→ serialize once → complete-page byte check
        API-->>S: reader HTML, canonical text, renderFormat, snapshotCommitSha
        S->>Room: replace saved copy
        Note over S,Room: if the fetch fails, the older saved copy is shown with the reason
    end
    S-->>R: note
    R->>WV: load HTML at https://appassets.androidplatform.net
    WV->>R: request /assets/… (bundled JS/CSS)
    WV->>R: request /repo-image/<resolved path>
    R->>S: image bytes for displayed snapshot (cache, else backend path + commitSha,<br/>bearer token in native code, never latest-snapshot fallback)
    R->>WV: evaluateJavascript: restore position, draw highlights, read selection
    Note over WV: no JS bridge, no credentials,<br/>network/file/content access blocked,<br/>Mermaid in strict mode
```

Links the page needs native help with (`/note-link`, `/copy-code`) are same-origin URLs the app intercepts; the page itself can't call native code.

### Phone pending changes and repository snapshot refresh

Local changes are written to Room first. Each asks WorkManager for a background pending-change send once the phone has a network (5 s after the last change, retried by WorkManager for an unreachable server, 5xx, or GitHub unavailable, at most 10 attempts). Opening the Library, pull to refresh, or **Send changes** sends pending mutations and then loads the server's library in one request; the background sender leaves lists alone. Opening a note also sends waiting highlight and card creations. All local mutations and server answers use the process's one `PhoneSync` lock. Separately, **Check GitHub for updates** calls `RepositorySnapshotRefresh` through the stable `POST /api/repositories/{id}/sync` route; it reads GitHub's default-branch tree, publishes a complete server snapshot, then reloads the phone's library. Sign-out and confirmed account deletion clear the session, never Room or image files. Account operations retain entries and do not gate background work. A different account replaces acknowledged lists at its first library load; unsent work can be sent under that account (the accepted one-account limit, ADR-22). Mutation ids make resending safe.

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant P as Phone (PhoneSync + Room)
    participant API as Backend
    participant GH as GitHub

    U->>P: select text in one block → Highlight
    P->>P: one Room write: pending annotation + new mutation id
    P->>API: POST /api/documents/{id}/annotations {mutationId, anchor}
    API->>API: replay? return the original (200, no GitHub call)
    API->>GH: fetch the selected version's blob
    API->>API: render, verify block + offsets + exact text,<br/>derive context and heading path
    API->>API: transaction: lock user then connection,<br/>recheck replay, check highlight/card capacity, insert whole annotation
    API-->>P: 201 annotation (or 422 ANNOTATION_LIMIT, no insertion)
    P->>P: mark acknowledged

    Note over P,API: Library open / sendPendingChangesAndRefreshLibrary, in this order
    P->>API: 1. pending highlight and card creations (need GitHub, so they wait if it fails)
    P->>API: 2. pending grades
    P->>API: 3. pending reading positions
    P->>API: 4. pending bookmark changes
    P->>API: 5. GET /api/library (one snapshot) → replace every list in one Room transaction,<br/>deleting saved notes nothing refers to any more
    P->>P: 6. delete the images of every snapshot Room holds no page of
```

Only the known status/code pairs in `PhoneSync.refusesItem()` permanently refuse an annotation, grade or bookmark; a status family alone is not authority. Reading saves separately accept only 404 `NOT_FOUND` as final. In particular, 422 `ANNOTATION_LIMIT` and 409 `REPOSITORY_RENAMED` leave creations pending, because capacity or repository identity can change without changing the mutation. Any other failure leaves the item pending: sending a highlight or card stops the remaining creations but not the database-only work (ADR-03) unless the server is unreachable, and sending a grade, reading position or bookmark ends the run.

### Re-anchoring

When a note changes, each highlight or card is resolved against the new version the next time the note's annotations are listed, not at refresh ([ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan) says why). The original anchor never changes ([ADR-05](adrs.md#adr-05--anchors-are-block-ids-and-utf-16-offsets-into-canonical-text-of-one-version), [ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan)).

```mermaid
stateDiagram-v2
    [*] --> ANCHORED: created on a verified selection
    ANCHORED --> Resolving: note has a newer version
    REANCHORED --> Resolving: note has a newer version
    state Resolving {
        direction LR
        [*] --> Block: same block anywhere?
        Block --> Position: no
        Position --> Quote: no
        Quote --> Fuzzy: no
    }
    Resolving --> REANCHORED: block / position / quote / bounded fuzzy match
    Resolving --> ORPHANED: weaker evidence
    ORPHANED --> REANCHORED: user reattaches by selecting the passage
    ORPHANED --> Resolving: next version, from the last location
```

### Practice and review

Cards are anchored annotations with a question: interview-bank questions, anchored to their Canonical line. The server owns the immutable review log; the phone owns scheduling and the session size (40) ([ADR-13](adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling)).

```mermaid
flowchart LR
    Bank["Interview bank question<br/>first grade in a Practice session"]
    Card["Pending CARD<br/>(mutation id)"]
    Bank --> Card
    Card -- "sync" --> SrvCard["Server: annotations<br/>type CARD"]

    Grade["Grade 0 / 3 / 4 / 5<br/>pending, client UUID"]
    Grade -- "sync" --> Log["Server: review_log<br/>immutable, idempotent"]
    Log -- "GET /api/library" --> Proj["Room: review_log projection"]
    Proj --> Ladder["study/ReviewSchedule.kt<br/>gap ladder over the log"]
    Ladder --> Topics["Due cards by topic<br/>Review opens here"]
    Topics --> Due["Review session: one topic or all<br/>capped at 40 (a phone constant)"]
    Due --> Asks{"Saved current bank<br/>still asks the card's<br/>Canonical line?"}
    Asks -- "yes" --> Grade
    Asks -- "no" --> Open["Offer the bank: the server re-anchors<br/>an edited line, or delete the card"]
```

Practice questions are recognized on the phone from saved copies of notes under an `interview/` folder (`study/InterviewBank.kt`); a Practice session makes no network calls.

## 6. Trust boundaries

```mermaid
flowchart LR
    subgraph Untrusted["Untrusted: repository content"]
        MD["Markdown, Mermaid source,<br/>images, links"]
    end
    subgraph ServerSide["Backend"]
        R["MarkdownRenderer<br/>escape + jsoup safelist + limits"]
        Tok["GitHub user token<br/>encrypted at rest,<br/>key outside the database"]
    end
    subgraph PhoneSide["Phone"]
        WV2["WebView: CSP, bundled scripts,<br/>no bridge, no network"]
        Native["Native code<br/>bearer session, image fetches"]
    end
    MD --> R --> WV2
    Tok -. "never leaves the server" .- R
    Native -. "credentials never enter the page" .- WV2
```

- The GitHub token stays on the server; the phone holds only a revocable app session ([ADR-03](adrs.md#adr-03--github-user-tokens-stay-on-the-server-encrypted-at-rest)).
- Rendered content can't run code: raw HTML is escaped, output is sanitized, and the WebView only loads bundled assets and the note's own images through native code.
- Every route is scoped to the signed-in user; another user's ids return 404 without a GitHub call.
- The release signing key is also a GitHub Actions secret, so the GitHub account is part of the app's trust boundary ([ADR-15](adrs.md#adr-15--app-releases-through-github-releases-installed-by-obtainium)).

## 7. Where the details live

| Topic | Source |
| --- | --- |
| Why each decision was made, and what it costs | [adrs.md](adrs.md) |
| Rules every change keeps | [invariants.md](invariants.md) |
| Scope, stage gates, post-MVP status, known gaps, what is unverified | [feature-list.md](feature-list.md) |
| User needs and acceptance criteria | [user-stories.md](user-stories.md) |
| Every route, its GitHub call ceiling, error codes, server-owned limits, metrics | [backend/README.md](../backend/README.md) |
| Hosted VM layout and deploys | [backend/README.md § Hosted VM](../backend/README.md#hosted-vm) |
| Phone behaviour, offline rules, signing, device tests, Room schema versions | [android/README.md](../android/README.md) |
| Reader display bundle and its device check | [android/reader-web/README.md](../android/reader-web/README.md) |
