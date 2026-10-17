# RepoRead architecture

How RepoRead is built today: the parts, who owns what, and how data moves between them. The *why* behind each decision is in the [ADRs](adrs.md); the exact HTTP contract, limits and error codes are in the [backend README](../backend/README.md); phone behaviour is in the [Android README](../android/README.md). The rules every change keeps are in [invariants](invariants.md). This document links to them instead of repeating them.

As built on `main`, 2026-10-17.

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
        Deploy["reporead-deploy<br/>health-checked, rolls back"]
        Caddy --> Svc --> VMPG
        Deploy -- "replace jar, restart" --> Svc
    end

    subgraph GHA["GitHub Actions on push to main"]
        BW["backend.yml<br/>tests → bootJar"]
        AW["android.yml<br/>unit tests → signed release APK"]
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

Arrows point from a package to the packages it calls (from the imports in each file). The packages form a stack with no cycles: each calls only packages below it, and `datacontrol`, which deletes across every owner, and `library`, which reads the phone's lists across their owners in one snapshot, are on top. Every package with routes also reads the signed-in user (`auth.AppUser`); those arrows are left out. The stack is of imports: SQL reads do cross owners (`Documents` checks reading and annotation rows, `RepositoryConnections` counts documents), which ADR-01 allows, since each table's writes stay with its one owner. Controllers call owners only; no controller calls GitHub.

```mermaid
flowchart TB
    subgraph HTTP["Controllers"]
        direction LR
        AuthC["AppAuthController"]
        RepoC["RepositoryController"]
        SyncC["SyncController"]
        DocC["DocumentController"]
        ReadC["ReadingController"]
        AnnC["AnnotationController<br/>BookmarkController<br/>ReviewController"]
        DataC["DataControlController"]
        LibC["LibraryController"]
    end

    datacontrol["datacontrol<br/>disconnect, stored data,<br/>account deletion"]
    library["library<br/>Library: every list the phone saves,<br/>one read-only snapshot"]
    sync["sync<br/>RepositorySync: snapshot publish<br/>ContentMoves: moved-and-edited"]
    annotation["annotation<br/>Annotations, Anchoring,<br/>Bookmarks, Reviews"]
    reading["reading<br/>ReadingStates"]
    document["document<br/>MarkdownRenderer, NoteVersions,<br/>NoteChanges, Attachments"]
    repository["repository<br/>connections, per-connection lock"]
    auth["auth<br/>sign-in, app sessions,<br/>bearer auth, GitHub token renewal"]
    github["github<br/>GitHubApi: every GitHub read<br/>GitHubHttp: timeouts, size limits"]

    AuthC --> auth
    RepoC --> repository
    SyncC --> sync
    DocC --> document
    ReadC --> reading
    AnnC --> annotation
    DataC --> datacontrol
    LibC --> library

    datacontrol --> annotation
    datacontrol --> reading
    datacontrol --> document
    datacontrol --> repository
    datacontrol --> auth
    datacontrol --> github
    library --> annotation
    library --> reading
    library --> document
    library --> repository
    sync --> document
    sync --> repository
    sync --> github
    annotation --> document
    annotation --> repository
    reading --> document
    reading --> repository
    document --> repository
    document --> github
    repository --> auth
    repository --> github
    auth --> github

    github ==> GH(["GitHub"])
```

| Package | Owns | Key classes |
| --- | --- | --- |
| `auth` | GitHub OAuth (Spring Security), single-use sign-in codes bound to a PKCE challenge, 30-day bearer sessions (only hashes stored), the encrypted stored GitHub token (`GitHubTokens`) and its renewal and revocation (`GitHubCredentials`) | `AppSecurity` (loopback only, the API and management chains), `GitHubSecurity` (browser sign-in), `GitHubTokens`, `GitHubCredentials`, `AppSignInSuccess`, `AppSessions`, `BearerAuthentication` |
| `github` | Every GitHub REST read, with the user's token; the HTTP clients every GitHub call goes through: 15 s timeouts, no redirects, no retries, response size limits (the largest note RepoRead reads), and the count of every GitHub request | `GitHubApi`, `GitHubHttp` |
| `repository` | Which repositories a user may connect (GitHub decides, through the user's token) and has connected; the connection row lock every write shares | `RepositoryConnections` |
| `sync` | Publishing a complete tree snapshot of the default branch; document identity across moves ([ADR-07](adrs.md#adr-07--document-identity-from-complete-snapshots)) | `RepositorySync`, `ContentMoves` |
| `document` | Rendering and sanitizing Markdown into reader HTML with canonical block text; fetching a version by blob SHA; changes since last read ([ADR-09](adrs.md#adr-09--changed-since-last-read-compares-the-version-actually-read-by-source-lines)); images and Obsidian embeds | `MarkdownRenderer`, `NoteVersions`, `NoteChanges`, `Attachments` |
| `reading` | Last-read version, position and progress per note; last write wins by the client's time | `ReadingStates` |
| `annotation` | Highlights, cards and bookmarks; idempotent creation by mutation id; re-anchoring ([ADR-06](adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan)); the immutable review log | `Annotations`, `AnnotationService` (creation, reattachment and re-anchoring against note versions), `Anchoring`, `Bookmarks`, `Reviews` |
| `datacontrol` | Disconnecting a repository and deleting the account: what it would delete, and deleting it through each table's owner in one transaction ([ADR-11](adrs.md#adr-11--disconnecting-deletes-everything-stored-for-that-repository-account-deletion-deletes-everything)) | `ConnectionData`, `AccountDeletion`, `DataControlController` |
| `library` | The phone's one read of the server's lists: repositories with every document, reading states, bookmarks and the notebook, each from its owner, in one read-only `REPEATABLE READ` transaction ([ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes)) | `Library`, `LibraryController` |
| root | Error responses (`{code, message}`), client clock bounds | `ApiErrors`, `ApiFailure`, `ClientClock` |

### Data model (PostgreSQL)

Bookmarks, highlights and cards share the `annotations` table, told apart by `type`. A highlight or card has an original anchor that never changes and a current location that re-anchoring moves.

```mermaid
erDiagram
    users ||--o{ app_sessions : "signs in with"
    users ||--o{ app_sign_in_codes : "exchanges"
    users ||--o{ repository_connections : connects
    repository_connections ||--o{ documents : "has (path-keyed, soft-deleted)"
    repository_connections ||--o{ repository_images : "snapshot image paths"
    users ||--o{ reading_states : reads
    documents ||--o{ reading_states : "read as"
    users ||--o{ annotations : owns
    documents ||--o{ annotations : "annotated by"
    annotations ||--o| annotation_anchors : "original selection"
    annotations ||--o| annotation_locations : "current location"
    users ||--o{ annotation_mutations : "idempotency records"
    annotations |o--o| annotation_mutations : "created by (outlives a deletion)"
    annotations ||--o{ review_log : "graded in"
    users ||--o| github_tokens : "signs in to GitHub with (by github_user_id, no FK)"

    users {
        bigint id PK
        bigint github_user_id UK
        text login
    }
    github_tokens {
        bigint github_user_id PK
        bytea access_token "AES-256-GCM"
        bytea refresh_token "AES-256-GCM"
        timestamptz access_token_expires_at
    }
    repository_connections {
        bigint id PK
        bigint user_id FK
        bigint github_repository_id
        text default_branch
        text last_synced_commit_sha
    }
    documents {
        bigint id PK
        bigint repository_connection_id FK
        text path
        text current_blob_sha
        timestamptz content_changed_at
        timestamptz deleted_at
    }
    reading_states {
        bigint user_id PK
        bigint document_id PK
        text last_read_blob_sha
        jsonb anchor_json
        timestamptz last_read_at
    }
    annotations {
        bigint id PK
        text type "BOOKMARK | HIGHLIGHT | CARD"
        uuid mutation_id "highlights and cards"
        text status "ANCHORED | REANCHORED | ORPHANED"
        text resolved_blob_sha
        text question "cards only"
        text checked_blob_sha "cards only"
        int version "optimistic"
    }
    annotation_anchors {
        bigint annotation_id PK
        text source_blob_sha
        text block_id
        int start_offset
        int end_offset
        text exact_text
    }
    annotation_locations {
        bigint annotation_id PK
        text source_blob_sha
        text block_id
        int start_offset
        int end_offset
    }
    annotation_mutations {
        bigint user_id PK
        uuid mutation_id PK
        bytea request_hash
    }
    review_log {
        bigint user_id PK
        uuid mutation_id PK
        bigint annotation_id FK
        int grade "0..5"
        text blob_sha
    }
```

Concurrency: syncs, reconnects (whose upsert locks the row), disconnects and every write that adds or changes a document's rows take the same `repository_connections` row lock (`RepositoryConnections.lockForSync` and its siblings), so a write never lands half-way through a sync or a disconnect. Each table's owner takes the lock inside its own transaction, and the lock methods refuse to run outside one.

## 4. Android app

One Activity (`MainActivity`) hosting Compose (`RepoReadApp`), with its own back stack of `Screen` values encoded as strings so it survives process death. No navigation library and no DI framework; the only background work is the background sync of local changes (WorkManager, ADR-08).

### Screens and layers

```mermaid
flowchart TB
    Main["MainActivity<br/>receives the sign-in redirect"]
    App["RepoReadApp<br/>back stack of Screen"]
    Main --> App

    subgraph Screens["Screens"]
        Library["Library<br/>tabs: Reading · Bookmarks · Practice · Repositories"]
        Folder["Folder"]
        Available["Available repositories"]
        Reader["Reader<br/>one saved overlay (ADR-25)"]
        Figure["Full-screen figure"]
        Notebook["Notebook"]
        Practice["Practice session"]
        Review["Review session"]
        Search["Search"]
    end
    App --> Screens

    subgraph Core["Shared code"]
        Sync["sync/Sync<br/>owns every server call<br/>whose answer reaches Room"]
        Store["data/LocalStore<br/>Room database + DAOs"]
        Api["core/network/Api<br/>HTTP + JSON, 8 MiB cap"]
        Images["sync/NoteImages<br/>saved notes' images,<br/>outside the lock"]
        Session["auth/SessionStore<br/>Keystore AES-GCM bearer token"]
        Sched["library/ReviewSchedule<br/>pure review scheduler"]
        StudyS["library/StudySession<br/>pure session progress"]
        StudyK["reader/Study<br/>bank / study-view recognition"]
    end

    subgraph ReaderHost["Reader hosting"]
        Page["reader/ReaderPage<br/>isolation + resource interception<br/>+ the one page-script boundary"]
        WebV["WebView<br/>reader.js · Mermaid · highlight.js"]
        Page --> WebV
    end

    Screens --> Sync
    Screens --> Store
    Practice --> StudyK
    Practice --> Sched
    Review --> Sched
    Practice --> StudyS
    Review --> StudyS
    Reader --> Page
    Figure --> Page
    Sync --> Api
    App -- "sign-in, sign-out" --> Api
    Available -- "list, connect" --> Api
    Sync --> Store
    Api --> Session
    Page -- "images" --> Images
    Sync --> Images
    Images --> Api
```

| Package | Owns |
| --- | --- |
| `auth` | PKCE sign-in through a Custom Tab; the encrypted bearer session |
| `core/network` | `Api`: one HTTP client, bounded responses, `ApiException(status, code)` from the server's error body; any 401 ends the stored session (`SessionStore.endRefused`), which signs the app out wherever the call was made |
| `data` | `LocalStore`: the Room database and its migrations; `Rows.kt`: its tables and query results; `LibraryDao.kt`: its queries (schema history in `android/schemas/`) |
| `sync` | `Sync`: refreshes, opening notes, the sync of local changes, annotation and card mutations, disconnect and account deletion, all under sign-out's lock (ADR-22); `NoteImages`: saved notes' images, fetched for the reader's WebView outside the lock (Sync deletes them with their notes); `ServerJson.kt`: server responses as Room rows; `BackgroundSync.kt`: the WorkManager job that runs that sync after each local change. Sign-in, sign-out and **Add repository** call `Api` directly: they write nothing to Room, and a newly connected repository's note list is then loaded through `Sync` when it opens |
| `library` | Library tabs, Notebook with its Markdown export (`Notebook.kt`), Practice sessions (`Practice.kt`), Review sessions (`Review.kt`) with the review scheduler (`ReviewSchedule.kt`), both moved by `StudySession.kt`, the folder screen (`Folder.kt`), and Add repository (`Available.kt`) |
| `reader` | The reader screen, page hosting and isolation, outline, notes sheet, backlinks, link resolution and previews, changes since last read, study view, answer views, full-screen figures |
| `search` | Local substring search over saved titles, note text and highlights ([ADR-10](adrs.md#adr-10--search-is-local-substring-matching-over-what-the-phone-has-saved)) |
| `reader-web/` | The page's display JavaScript and CSS, bundled by esbuild into the APK; parses no Markdown and makes no network requests |

### Room: a projection plus pending changes

Room never decides anything the server owns; it mirrors complete server responses and queues the user's own changes until acknowledged ([ADR-08](adrs.md#adr-08--offline-a-room-projection-plus-explicit-pending-changes)).

| Table | Comes from | Pending local changes? |
| --- | --- | --- |
| `repositories`, `documents` | Server's complete lists (replaced, never merged), with every other list from one library answer in one transaction | No |
| `notes` | Latest rendered copy of each opened note, keyed by document and blob SHA, with its canonical text for search | No |
| `reading_states`, `bookmarks` | Server lists, merged with local saves | Yes, last write wins by time |
| `annotations` | Notebook and per-note listings | Yes: creations (with mutation id) and deletions of unacknowledged creations |
| `review_log` | Server's durable log | Yes: grades (with mutation id) |
| `review_limits` | Server-owned session limit (40) | No |
| `change_baselines` | Phone only: the oldest read version whose changes haven't been shown yet | n/a |

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
    API-->>Tab: redirect https://…/app/signed-in?code=… (a verified App Link;<br/>reporead-dev://auth for the debug app)
    Tab-->>App: Android opens the link in MainActivity
    App->>API: POST /api/app-auth/token {code, codeVerifier, expectedUserId?}
    API-->>App: {accessToken, expiresAt, user} (30 days)
    App->>App: encrypt token with Keystore key
    Note over App,API: every /api/** call: Authorization: Bearer token.<br/>Any 401 means sign in again, and saved data stays readable.
```

### Refreshing a repository (sync)

A sync publishes one complete snapshot of the default branch or changes nothing ([ADR-07](adrs.md#adr-07--document-identity-from-complete-snapshots)).

```mermaid
sequenceDiagram
    autonumber
    participant App as Phone (Sync)
    participant API as RepositorySync
    participant DB as PostgreSQL
    participant GH as GitHub

    App->>API: POST /api/repositories/{id}/sync
    API->>GH: default branch → commit SHA
    API->>GH: recursive tree at that commit
    alt tree truncated or malformed
        API-->>App: 502 GITHUB_TREE_INCOMPLETE, nothing changed
    else complete tree
        API->>API: keep regular .md blobs, match documents by path,<br/>then unique-blob moves
        opt vanished documents with user data (≤ 8 candidates)
            API->>GH: up to 8 blob reads
            API->>API: shingle similarity → moved and edited?
        end
        API->>DB: lock connection row (FOR UPDATE)
        alt another sync already published a newer commit, or a reconnect changed the default branch
            API-->>App: 409 SYNC_SUPERSEDED
        else
            API->>DB: one transaction: upsert documents, mark absent ones deleted,<br/>replace image paths, advance checkpoint
            API-->>App: 200
        end
    end
    App->>API: GET /api/library
    API->>DB: one read-only REPEATABLE READ snapshot:<br/>repositories, documents, reading states, bookmarks, notebook
    API-->>App: every list
    App->>App: replace Room's lists in one transaction
```

### Opening and displaying a note

```mermaid
sequenceDiagram
    autonumber
    participant R as Reader screen
    participant S as Sync
    participant Room as Room
    participant API as Backend
    participant GH as GitHub
    participant WV as Isolated WebView

    R->>S: openNote(documentId)
    S->>Room: saved copy for this document?
    alt saved copy matches current blob SHA, path and page format
        Room-->>S: saved note (no network)
    else
        S->>API: GET /api/documents/{id}/content
        API->>GH: raw blob at the stored SHA (1 call)
        API->>API: CommonMark/GFM → escape raw HTML → jsoup safelist<br/>→ block ids + canonical text, limits enforced
        API-->>S: reader HTML, canonical text, renderFormat
        S->>Room: replace saved copy
        Note over S,Room: if the fetch fails, the older saved copy is shown with the reason
    end
    S-->>R: note
    R->>WV: load HTML at https://appassets.androidplatform.net
    WV->>R: request /assets/… (bundled JS/CSS)
    WV->>R: request /repo-image/… or /repo-embed/…
    R->>S: image bytes (cache, else backend with bearer token in native code)
    R->>WV: evaluateJavascript: restore position, draw highlights, read selection
    Note over WV: no JS bridge, no credentials,<br/>network/file/content access blocked,<br/>Mermaid in strict mode
```

Links the page needs native help with (`/note-link`, `/copy-code`, `/full-screen`) are same-origin URLs the app intercepts; the page itself can't call native code.

### Highlights, cards and the sync

Local changes are written to Room first. Each one asks WorkManager for a background sync once the phone has a network (5 s after the last change, tried again with backoff on an unreachable server, 5xx, or GitHub unavailable, at most 10 attempts); opening the Library, pull to refresh, **Sync now**, and opening a note also send them, and the first three then load the server's library in one request, which the background sync leaves alone. Every path runs the same `Sync` code under one per-process lock that sign-out also takes, as does every other `Sync` operation that writes the server's answers into Room; the mutation id makes resending safe.

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant P as Phone (Sync + Room)
    participant API as Backend
    participant GH as GitHub

    U->>P: select text in one block → Highlight
    P->>P: one Room write: pending annotation + new mutation id
    P->>API: POST /api/documents/{id}/annotations {mutationId, anchor}
    API->>API: replay? return the original (200, no GitHub call)
    API->>GH: fetch the selected version's blob
    API->>API: render, verify block + offsets + exact text,<br/>derive context and heading path
    API-->>P: 201 annotation
    P->>P: mark acknowledged

    Note over P,API: Library open / Sync runs syncLocalChanges, in this order
    P->>API: 1. pending highlight and card creations (need GitHub, so they wait if it fails)
    P->>API: 2. pending grades
    P->>API: 3. pending reading positions
    P->>API: 4. pending bookmark changes
    P->>API: 5. GET /api/library (one snapshot) → replace every list in one Room transaction,<br/>deleting saved notes nothing refers to any more
    P->>P: 6. delete the forgotten notes' images
```

A refused item (400/404/409/410/422) is marked and the run continues; any other failure ends the run with everything unacknowledged still pending.

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

Cards are anchored annotations with a question. The server owns the immutable review log and the session limit; the phone owns scheduling ([ADR-13](adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling)).

```mermaid
flowchart LR
    subgraph Sources["Where cards come from"]
        Bank["Interview bank question<br/>first grade in a Practice session"]
        Sel["Selected passage<br/>→ Make a question"]
        HL["Existing highlight<br/>→ Make a question"]
    end

    Card["Pending CARD<br/>(mutation id)"]
    Sources --> Card
    Card -- "sync" --> SrvCard["Server: annotations<br/>type CARD"]

    Grade["Grade 0 / 3 / 4 / 5<br/>pending, client UUID"]
    Grade -- "sync" --> Log["Server: review_log<br/>immutable, idempotent"]
    Log -- "GET /api/library" --> Proj["Room: review_log projection"]
    Proj --> Ladder["library/ReviewSchedule.kt<br/>gap ladder over the log"]
    Ladder --> Due["Due cards → Review session<br/>capped at 40 (server-owned)"]
    Due --> Grade

    Changed{"Note version changed<br/>since the answer was checked?"}
    SrvCard --> Changed
    Changed -- "yes" --> Check["Blocked until reattached if orphaned<br/>and confirmed online:<br/>POST /annotations/{id}/check"]
    Changed -- "no" --> Due
```

Practice questions are recognized on the phone from saved copies of notes under an `interview/` folder (`reader/Study.kt`); a Practice session makes no network calls.

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
