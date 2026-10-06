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
| `POST /api/repositories/{id}/sync` | 2 (branch, recursive tree) | Publishes a complete snapshot of the default branch |
| `GET /api/repositories/{id}/documents` | 0 | Active Markdown documents and the last synced commit |
| `GET /api/documents/{id}/content` | 1 (raw blob at the stored SHA) | Sanitized reader HTML; not cached on the server |
| `GET /api/documents/{id}/image?path=` | 1 (file at the note's current commit) | A repository image referenced by the note; see below |
| `GET /api/reading-states` | 0 | Most recently read first, with the version last read and the current version |
| `PUT /api/documents/{id}/reading-state` | 0 | `{lastReadBlobSha, progressPercent, anchor:{headingPath, textPrefix, blockIndex}, lastReadAt}`; last write wins by `lastReadAt` |
| `GET /api/bookmarks` | 0 | Document bookmarks |
| `PUT` / `DELETE /api/documents/{id}/bookmark` | 0 | `PUT {sourceBlobSha}`; both idempotent |

Every GitHub call uses the user's token, 15-second connect/read timeouts, no redirects, no retries, no pagination, and no installation-token fallback. A failed call ends the operation. Failures are `{code, message}`: `SIGN_IN_REQUIRED` 401, `GITHUB_ACCESS_DENIED`/`REPOSITORY_NOT_AUTHORIZED` 403, `NOT_FOUND`/`DEFAULT_BRANCH_NOT_FOUND`/`SOURCE_NOT_FOUND` 404, `DOCUMENT_DELETED` 410, `UNSUPPORTED_CONTENT`/`DOCUMENT_LIMIT` 422, `GITHUB_INVALID_RESPONSE`/`GITHUB_RESPONSE_LIMIT`/`GITHUB_TREE_INCOMPLETE`/`GITHUB_*_LIMIT` 502, `GITHUB_UNAVAILABLE` 503. Upstream error bodies are not exposed.

Every connection, document, and content request is scoped to the signed-in user; another user's ids return 404 without a GitHub call.

### Server-owned limits

| Limit | Value | Failure |
| --- | --- | --- |
| GitHub responses (OAuth, metadata, raw note) | 1 MiB | 502 `GITHUB_RESPONSE_LIMIT`; a note over the limit is 422 `UNSUPPORTED_CONTENT` |
| Recursive tree response | 8 MiB (GitHub's own maximum is 7 MB) | 502 `GITHUB_RESPONSE_LIMIT` |
| Installations / repositories per installation | 10 / 100, complete lists only | 502 `GITHUB_INSTALLATION_LIMIT` / `GITHUB_REPOSITORY_LIMIT` |
| Markdown documents per repository | 5,000 | 422 `DOCUMENT_LIMIT` |
| Note render | 1 MiB UTF-8, 4,096 blocks, 16 diagrams of 20,000 UTF-16 units, 200 Mermaid edges | 422 `UNSUPPORTED_CONTENT` |
| Repository images | 64 per note; 5 MiB each | Extra images render as `[Image limit reached]`; larger images 422 `UNSUPPORTED_CONTENT` |
| Reading state | 6 headings of 500 chars, 200-char text prefix, block index 0–4095, `lastReadAt` at most 5 minutes ahead | 400 `INVALID_READING_STATE` |

These are chosen ceilings, not measured ones. Clients cannot change them.

### Sync semantics

Sync reads the default branch's commit, then its full recursive tree. Documents are regular-file blobs ending in `.md` (any case); symlinks and submodules are skipped. A truncated or malformed tree fails without changes and is never evidence of deletion. Only after complete validation does one transaction upsert documents by path, mark paths absent from the complete tree as deleted (rows are kept), and advance the connection's commit checkpoint. Repeating a sync is idempotent; concurrent syncs of one connection are serialized by a row lock. Moves, renames, and branch rewinds are Stage 4.

### Images

The renderer resolves relative and root-relative Markdown images against the note's directory, as GitHub does, and rewrites them to the same-origin path `/repo-image/<repository path>`. The Android reader serves that path from its offline cache or from `GET /api/documents/{id}/image`, attaching the bearer token in native code; the page's JavaScript never sees a credential. Only normalized repository paths ending in png, jpg/jpeg, gif, webp, or svg are served (`nosniff`). Remote (`https:`, `//`) images, references escaping the repository, and other file types render as visible `[… blocked]`/`[Unsupported image]` text.

### Reading state and bookmarks

Only the reader writes reading state, recording the blob SHA actually displayed; repository refreshes never change it. A write older than or equal to the stored `lastReadAt` is ignored and the current state is returned, so replaying a stale offline save cannot overwrite newer progress. Deleting a document upstream keeps its reading state and bookmarks. Bookmarks live in the `annotations` table as type `BOOKMARK` (one per user and document).

## Verify

```sh
./gradlew :backend:test --no-daemon
```

Tests run against a real PostgreSQL 17.11 container through Testcontainers (Docker must be running). GitHub is mocked with explicit test-only data; tests do not prove live GitHub authorization.

Dependencies: Spring Boot web MVC, Security OAuth2 Client, JDBC, and Flyway; the PostgreSQL driver; CommonMark with GFM extensions; jsoup for sanitizing and canonical block text; JUnit, Spring test support, and Testcontainers. Mermaid and code highlighting are client-side display only.
