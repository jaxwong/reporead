# RepoRead backend

Spring Boot owns user identity, app sessions, authorized repository connections, logical Markdown documents, and safe note rendering. GitHub owns the Markdown; PostgreSQL owns RepoRead's state. The server never writes to GitHub.

Requirements: JDK 25, Docker, and the repository's Gradle wrapper.

## Local database

`compose.yaml` runs a RepoRead-only PostgreSQL 17.11 bound to `127.0.0.1:5435` (5432 is used by unrelated services on the development Mac). Its password is a development-only value for a loopback container.

```sh
docker compose -f backend/compose.yaml -p reporead up -d
```

Flyway applies `src/main/resources/db/migration` at startup. Schema changes are new versioned files; never edit an applied migration.

## Run

Save the GitHub App client secret as a single token in a file **outside any Git repository**, mode **0600**. The App PEM is not the OAuth secret. No fallback credentials or database exist; missing/malformed/unsafe configuration fails startup.

```sh
DEBUG=false \
REPOREAD_GITHUB_CLIENT_ID='<your-app-client-id>' \
REPOREAD_GITHUB_CLIENT_SECRET_FILE='/absolute/private/path/client-secret' \
REPOREAD_DB_URL='jdbc:postgresql://127.0.0.1:5435/reporead' \
REPOREAD_DB_USERNAME='reporead' \
REPOREAD_DB_PASSWORD='reporead-local-dev' \
./gradlew :backend:bootRun --no-daemon
```

`DEBUG=false` overrides this Mac's inherited `DEBUG=release`, which otherwise enables verbose framework logging. Do not enable HTTP/security debug logging with real credentials.

The server binds to `127.0.0.1:8081` (an unrelated Docker service owns 8080). Register this GitHub App callback: `http://127.0.0.1:8081/login/oauth2/code/github`. The phone reaches the same loopback address through adb, so the callback is identical on the Mac and phone:

```sh
~/Library/Android/sdk/platform-tools/adb -s <device-serial> reverse tcp:8081 tcp:8081
```

## Sign-in contract (Android)

1. The app creates a PKCE verifier and opens `GET /app/sign-in?code_challenge=<S256 challenge>` in a Custom Tab.
2. The browser runs GitHub OAuth (Spring Security owns state, PKCE with GitHub, and code exchange). The callback saves the user, issues a **single-use, 60-second** code bound to the app's challenge, **ends the browser session**, and redirects to `reporead://auth?code=…`. A GitHub login not started from `/app/sign-in` gets no code.
3. The app sends `POST /api/app-auth/token {code, codeVerifier}` and receives `{accessToken, expiresAt, user}`. A wrong verifier consumes the code. Sessions last 30 days; `DELETE /api/app-auth/session` signs out.
4. `/api/**` accepts only `Authorization: Bearer <accessToken>`. It is stateless: browser session cookies never authenticate the API. Any 401 means "sign in again".

Only SHA-256 hashes of codes and session tokens are stored. The GitHub user token stays **in server memory** and is never returned. Restarting the server or GitHub expiring the token leaves the app session valid for database-only reads, but GitHub-backed operations return 401 `SIGN_IN_REQUIRED` until the user signs in again. There is no refresh-token handling or durable GitHub-token storage yet; adding either requires an encryption-at-rest key decision.

## API

| Route | GitHub calls (ceiling) | Notes |
| --- | --- | --- |
| `GET /api/auth/me` | 0 | `{id, githubUserId, login}` |
| `GET /api/repositories` | 0 | This user's connections, sync checkpoint, document count |
| `GET /api/repositories/available` | 1 + installations, at most 11 | Repositories GitHub says this user and the App can both access |
| `POST /api/repositories/{githubRepositoryId}/connect` `{installationId}` | 1 | GitHub re-verifies eligibility; connecting twice returns the same connection |
| `POST /api/repositories/{id}/sync` | 2 (branch, recursive tree) + at most 8 blob reads (moved-and-edited notes) | Publishes a complete snapshot of the default branch |
| `GET /api/repositories/{id}/documents` | 0 | Active Markdown documents and the last synced commit; each with `contentChangedAt` (see Sync semantics) |
| `GET /api/documents/{id}/content` | 1 (raw blob at the stored SHA) | Sanitized reader HTML; not cached on the server |
| `GET /api/documents/{id}/image?path=` or `?embed=` | 1 (file at the note's current commit); 0 when an embed name is missing or ambiguous | A repository image referenced by the note, by path or by Obsidian embed name; see below |
| `GET /api/documents/{id}/changes?since=&to=` | 2 (both versions' blobs); 0 when `since` = `to` | Sections changed between the version last read and the version on screen; see Changes since last read |
| `GET /api/reading-states` | 0 | Most recently read first, with the version last read and the current version |
| `PUT /api/documents/{id}/reading-state` | 0 | `{lastReadBlobSha, progressPercent, anchor:{headingPath, textPrefix, blockIndex}, lastReadAt}`; last write wins by `lastReadAt` |
| `GET /api/bookmarks` | 0 | Document bookmarks, each with `deleted` when the note left the repository (the bookmark is kept) |
| `PUT` / `DELETE /api/documents/{id}/bookmark` | 0 | `PUT {sourceBlobSha}`; both idempotent |
| `GET /api/documents/{id}/annotations` | 0 when every highlight is resolved against the current version; else 1 (that version) plus 1 per older version holding a pre-Stage-4 location | This user's highlights: original `anchor`, current `location`, `status` (`ANCHORED`, `REANCHORED`, `ORPHANED`) for `resolvedBlobSha`, and the creating `mutationId` |
| `POST /api/documents/{id}/annotations` | 1 (the selected version's blob); 0 on replay | `{mutationId, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}, note?}`; 201 created, 200 replay |
| `PATCH /api/annotations/{id}` | 0 | `{note, expectedVersion}`; 409 `ANNOTATION_CONFLICT` if another edit landed first |
| `DELETE /api/annotations/{id}?expectedVersion=` | 0 | 409 on a stale version |
| `POST /api/annotations/{id}/reattach` | 1 (the selected version's blob) | `{expectedVersion, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}}`; places the highlight on the user's selection, `REANCHORED`; 409 on a stale version |

Every GitHub call uses the user's token, 15-second connect/read timeouts, no redirects, no retries, no pagination, and no installation-token fallback. A failed call ends the operation. Failures are `{code, message}`: `SIGN_IN_REQUIRED` 401, `GITHUB_ACCESS_DENIED`/`REPOSITORY_NOT_AUTHORIZED` 403, `NOT_FOUND`/`DEFAULT_BRANCH_NOT_FOUND`/`SOURCE_NOT_FOUND` 404, `DOCUMENT_DELETED` 410, `UNSUPPORTED_CONTENT`/`DOCUMENT_LIMIT` 422, `GITHUB_INVALID_RESPONSE`/`GITHUB_RESPONSE_LIMIT`/`GITHUB_TREE_INCOMPLETE`/`GITHUB_*_LIMIT` 502, `GITHUB_UNAVAILABLE` 503. Upstream error bodies are not exposed.

Every connection, document, and content request is scoped to the signed-in user; another user's ids return 404 without a GitHub call.

### Observability

Logs name ids, SHAs, counts, outcomes, and repository paths; never tokens, authorization headers, note text, quotes, or annotation notes. Metrics (Micrometer, Spring Boot Actuator) are served only on the management port `127.0.0.1:8082`, which exposes `health` and `metrics` and nothing else; `adb reverse` forwards only 8081, so the phone cannot reach it. Tags never carry paths or content.

| Meter | Tags | Counts |
| --- | --- | --- |
| `reporead.github.requests` | `outcome`: `2xx`/`3xx`/`4xx`/`5xx`, `too_large`, `failed` | Every GitHub HTTP request, including sign-in |
| `reporead.github.syncs` | `outcome`: `success`/`failure`; `code`: the failure code or `NONE` | Repository syncs (the spec's sync total and sync failures) |
| `reporead.documents.synced` | — | Documents in each successful sync |
| `reporead.annotations.created` | — | New highlights (replays are not counted) |
| `reporead.annotation.reanchor` | `outcome`: `BLOCK`/`POSITION`/`QUOTE`/`FUZZY`/`ORPHANED` | Applied re-anchoring decisions (the spec's orphaned total is `outcome=ORPHANED`) |
| `reporead.annotation.reanchor.duration` | — | Time per resolution (timer) |

```sh
curl -s http://127.0.0.1:8082/actuator/metrics/reporead.github.requests
```

Meters appear after their first use. They are in memory and reset when the server restarts: a projection, never application state.

### Server-owned limits

| Limit | Value | Failure |
| --- | --- | --- |
| GitHub responses (OAuth, metadata, raw note) | 1 MiB | 502 `GITHUB_RESPONSE_LIMIT`; a note over the limit is 422 `UNSUPPORTED_CONTENT` |
| Recursive tree response | 8 MiB (GitHub's own maximum is 7 MB) | 502 `GITHUB_RESPONSE_LIMIT` |
| Installations / repositories per installation | 10 / 100, complete lists only | 502 `GITHUB_INSTALLATION_LIMIT` / `GITHUB_REPOSITORY_LIMIT` |
| Markdown documents per repository | 5,000 | 422 `DOCUMENT_LIMIT` |
| Note render | 1 MiB UTF-8, 4,096 blocks, 16 diagrams of 20,000 UTF-16 units, 200 Mermaid edges | 422 `UNSUPPORTED_CONTENT` |
| Repository images | 64 per note; 5 MiB each | Extra images render as `[Image limit reached]`; larger images 422 `UNSUPPORTED_CONTENT` |
| Annotation | selection and note at most 10,000 UTF-16 units each; one block per selection | 400 `INVALID_ANNOTATION` |
| Reading state | 6 headings of 500 chars, 200-char text prefix, block index 0–4095, `lastReadAt` at most 5 minutes ahead | 400 `INVALID_READING_STATE` |
| Changes comparison | 20,000 lines per version; 1,000 inserted plus deleted lines | `TOO_LARGE` status, no sections |

These are chosen ceilings, not measured ones. Clients cannot change them.

### Sync semantics

Sync reads the default branch's commit, then its full recursive tree. Documents are regular-file blobs ending in `.md` (any case); symlinks and submodules are skipped. A truncated or malformed tree fails without changes and is never evidence of deletion. Only after complete validation does one transaction upsert documents by path, mark paths absent from the complete tree as deleted (rows are kept), and advance the connection's commit checkpoint. Repeating a sync is idempotent; concurrent syncs of one connection are serialized by a row lock. A document whose path left the snapshot keeps its id at a path that has never had a document when both share a blob SHA unique on each side (a path-only move); identical-content duplicates are never merged, and a path that once had a document resumes it. A vanished document carrying the user's data also keeps its id at a new path whose content is alike enough (measured thresholds in the [Stage 4 record](../requirements/reporead-stage4-record.md)), checked only when vanished documents plus new paths number at most 8. Because every sync is a complete snapshot, a branch rewind is reconciled like any other: paths absent from it are marked deleted and return to their documents if they reappear.

Each sync also records the snapshot's image file paths (`repository_images`), replaced every time, for resolving Obsidian embeds.

`contentChangedAt` is when a sync found the note new, with a different blob, or back after being deleted (a path-only move is not a change). It is null for notes from a connection's first sync and for notes RepoRead has not seen change since Stage 5: it is when RepoRead noticed, not the commit time.

### Images

The renderer resolves relative and root-relative Markdown images against the note's directory, as GitHub does, and rewrites them to the same-origin path `/repo-image/<repository path>`. The Android reader serves that path from its offline cache or from `GET /api/documents/{id}/image`, attaching the bearer token in native code; the page's JavaScript never sees a credential. Only normalized repository paths ending in png, jpg/jpeg, gif, webp, or svg are served (`nosniff`). Remote (`https:`, `//`) images, references escaping the repository, and other file types render as visible `[… blocked]`/`[Unsupported image]` text.

### Obsidian links and embeds

The renderer turns `[[note]]`, `[[note|alias]]`, `[[note#Heading]]` (block references `#^id` open the note), same-note `[[#Heading]]`, and relative links to `.md` files into `/note-link?target=…|path=…&heading=…` links that the app intercepts; code is never linked. `![[image.png]]` (optionally `|300` or `|300x200`) becomes an image at `/repo-embed/<name>`, fetched with `image?embed=<name>`; `![[note]]` becomes a link. The source text stays in the page and only the brackets and targets are hidden (`.wl-hidden`), so canonical block text — and every highlight anchored in it — is unchanged. An embed name resolves like Obsidian against the latest snapshot's image files: a name with a folder must match the end of a path, a bare name a file name (case-insensitively); several matches resolve to the one in the note's folder, otherwise 409 `IMAGE_AMBIGUOUS`; none is 404 `IMAGE_NOT_FOUND`. The content response's `renderFormat` (`MarkdownRenderer.FORMAT`, now 2) tells the app when a saved page predates these features.

### Reading state and bookmarks

Only the reader writes reading state, recording the blob SHA actually displayed; repository refreshes never change it. A write older than or equal to the stored `lastReadAt` is ignored and the current state is returned, so replaying a stale offline save cannot overwrite newer progress. Deleting a document upstream keeps its reading state and bookmarks. Bookmarks live in the `annotations` table as type `BOOKMARK` (one per user and document).

### Changes since last read

The phone sends `since`, the version the user last read as it knows it (its unsent reading saves can be newer than the server's), and `to`, the version on screen. Both must be blobs in the note's repository. The server fetches both, splits the Markdown into lines as the parser numbers them, computes a minimal line diff (Myers), and assigns each inserted or deleted line to the heading section it is in: from a heading's line to the next heading of any level, with lines before the first heading as the beginning of the note. Sections are told apart by position, so duplicate headings are separate. A section is `ADDED` when its heading line is new and `CHANGED` otherwise, with its heading's `blockId` in `to` for navigation; an old section whose heading line was deleted is `REMOVED` and listed where it used to be. A renamed heading is a removed and an added section. Counts are source lines, so blank-line edits count.

`status` is `CHANGED` (with `sections`, possibly empty when only line endings differ), `UNCHANGED` (same version, no GitHub call), `SINCE_UNAVAILABLE` with a `reason` (GitHub no longer has the older version, or it can no longer be read as a note), or `TOO_LARGE` (over the limits above). Only `CHANGED` lists sections; a too-large comparison is never shown as a partial list. Any other failure, such as a GitHub outage or the newer version missing, fails the request; a missing or malformed `since`/`to` is 400 `INVALID_VERSIONS`, and a deleted note is 410 `DOCUMENT_DELETED`. Nothing is cached. A note never read has no `since`, and the phone does not ask.

The cost is proportional to lines × changed lines; constructed worst cases at both limits took at most 25 ms.

### Annotations

A highlight (type `HIGHLIGHT`, with an optional note) is anchored to one source version using the [Stage 0 text contract](../requirements/reporead-stage0-boundaries.md#rendering-and-selection-contract): block id, UTF-16 offsets into that block's canonical text, and the exact text. On creation the server fetches that exact blob, renders it with the same renderer as the reader, and rejects a selection that does not name real text in it (422 `INVALID_ANCHOR`). It derives the 32-character prefix/suffix context and heading path itself. Source Markdown is never written.

Creation is idempotent per client `mutationId` (a UUID): the first request records the mutation, annotation, and anchor in one transaction. Replaying the same request returns the original annotation (200) without a GitHub call — including after a lost acknowledgement or concurrently with the original. Reusing the id for different content is 409 `MUTATION_ID_REUSED`; replaying after the annotation was deleted is 410 `ANNOTATION_DELETED`, so a late replay cannot resurrect it. A failure before commit stores nothing, and the same mutation can be retried. Note edits and deletion use optimistic versions and never silently overwrite.

**Re-anchoring.** The original anchor never changes. Each highlight also has a current `location` (where it was made, re-anchored, or reattached) and a `status` for `resolvedBlobSha`. Listing a document's highlights resolves any that were resolved against an older version, using `Anchoring`: the location's block unchanged anywhere, then unchanged position, then the exact quote singled out by context and heading, then a bounded fuzzy match. Anything weaker is `ORPHANED`, keeping its last location so the next version is resolved from there; the user can reattach it. Re-anchoring does not change the user-edit `version`. Thresholds and their measurement on a real notes repository are in the [Stage 4 record](../requirements/reporead-stage4-record.md). A deleted document's highlights are listed as last resolved, without GitHub calls.

The server does not yet check that `sourceBlobSha` is a version of this particular document, only that it is a blob in the document's repository that renders to the selected text.

## Verify

```sh
./gradlew :backend:test --no-daemon
```

Tests run against a real PostgreSQL 17.11 container through Testcontainers (Docker must be running). GitHub is mocked with explicit test-only data; tests do not prove live GitHub authorization.

Dependencies: Spring Boot web MVC, Security OAuth2 Client, JDBC, and Flyway; the PostgreSQL driver; CommonMark with GFM extensions; jsoup for sanitizing and canonical block text; JUnit, Spring test support, and Testcontainers. Mermaid and code highlighting are client-side display only.
