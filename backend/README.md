# RepoRead backend

Spring Boot owns user identity, app sessions, authorized repository connections, logical Markdown documents and their identity across moves, safe note rendering, reading state, bookmarks, highlights and review cards, re-anchoring, change summaries, and the review log. GitHub owns the Markdown; PostgreSQL owns RepoRead's state. The server never writes to GitHub.

This README owns the backend's contracts and commands. Why things are this way is in the [ADRs](../docs/adrs.md), the rules every change keeps in [invariants](../docs/invariants.md), and how the backend fits with the app in the [architecture](../docs/architecture.md).

Requirements: JDK 25, Docker, and the repository's Gradle wrapper.

## Local database

`compose.yaml` runs a RepoRead-only PostgreSQL 17.11 bound to `127.0.0.1:5435` (5432 is used by unrelated services on the development Mac). Its password is a development-only value for a loopback container.

```sh
docker compose -f backend/compose.yaml -p reporead up -d
```

Flyway applies `src/main/resources/db/migration` at startup. Schema changes are new versioned files; never edit an applied migration. From V15 a migration only adds, so the previous release keeps running on the schema it leaves; dropping, renaming, retyping or requiring a column waits for a release after the one that stops using it and carries a `-- Contract:` line (`ExpandOnlyMigrationsTest`, ADR-24).

## Run

Save the GitHub App client secret as a single token in a file **outside any Git repository**, mode **0600**. The App PEM is not the OAuth secret. Create the key that encrypts stored GitHub tokens the same way, once per server: `(umask 077; openssl rand -hex 32 > /absolute/private/path/github-token-key)`; replacing it signs every user out of GitHub. `REPOREAD_ALLOWED_GITHUB_USER_IDS` lists the numeric GitHub user ids that may use this server, comma-separated (`gh api user --jq .id` prints yours). No fallback credentials or database exist; missing/malformed/unsafe configuration fails startup.

```sh
DEBUG=false \
REPOREAD_PUBLIC_URL='http://127.0.0.1:8081' \
REPOREAD_ALLOWED_GITHUB_USER_IDS='<your-github-user-id>' \
REPOREAD_GITHUB_CLIENT_ID='<your-app-client-id>' \
REPOREAD_GITHUB_CLIENT_SECRET_FILE='/absolute/private/path/client-secret' \
REPOREAD_GITHUB_TOKEN_KEY_FILE='/absolute/private/path/github-token-key' \
REPOREAD_DB_URL='jdbc:postgresql://127.0.0.1:5435/reporead' \
REPOREAD_DB_USERNAME='reporead' \
REPOREAD_DB_PASSWORD='reporead-local-dev' \
./gradlew :backend:bootRun --no-daemon
```

`DEBUG=false` overrides this Mac's inherited `DEBUG=release`, which otherwise enables verbose framework logging. Do not enable HTTP/security debug logging with real credentials.

The server always binds to `127.0.0.1:8081` (an unrelated Docker service owns 8080) and refuses any other address. `REPOREAD_PUBLIC_URL` is where the phone and GitHub reach it, and the OAuth callback is `<REPOREAD_PUBLIC_URL>/login/oauth2/code/github` — never derived from a request's `Host`. It must be `http://127.0.0.1:<server.port>` or an `https://` origin without a path; anything else fails startup. Both callbacks are registered on the GitHub App:

| Where | `REPOREAD_PUBLIC_URL` | Phone reaches it through |
| --- | --- | --- |
| Mac (debug builds) | `http://127.0.0.1:8081` | `adb -s <device-serial> reverse tcp:8081 tcp:8081` |
| Hosted VM (release builds) | `https://reporead.duckdns.org` | Caddy on the VM, which terminates HTTPS and forwards to loopback |

Behind Caddy, `server.forward-headers-strategy=native` lets Tomcat honour `X-Forwarded-Proto` from loopback, so redirects stay on HTTPS and the session cookie is `Secure`.

## Hosted VM

Oracle Cloud Always Free (ADR-14): `VM.Standard.A1.Flex`, 1 OCPU / 6 GB, Oracle Linux 9, home region `ap-singapore-1`, `ssh opc@152.69.219.238`. Only 22, 80 and 443 are open (Oracle security list and `firewalld`); PostgreSQL, 8081 and Actuator 8082 listen on loopback only.

| Piece | Where |
| --- | --- |
| PostgreSQL 17.11 | Podman Quadlet `/etc/containers/systemd/reporead-postgres.container` (service `reporead-postgres`, volume `reporead-postgres`, health-gated `Notify=healthy`). Host network with `listen_addresses=127.0.0.1` and no published port: a `firewall-cmd --reload` wipes Podman's NAT rules, which once left the backend hanging on connect |
| Deploys | `/usr/local/sbin/reporead-deploy` (from `backend/deploy/reporead-deploy`); user `deploy`, whose only SSH key is forced to run it via `sudo` (`/etc/sudoers.d/reporead-deploy`), with no shell, PTY or forwarding |
| Backend | `/opt/reporead/backend.jar` (root-owned), service `/etc/systemd/system/reporead-backend.service`, user `reporead`, starts after the database is healthy. Runs with `-Xms1g -XX:+AlwaysPreTouch`: RepoRead alone used ~16% of memory, and Oracle may reclaim an Always Free VM whose CPU, network and memory all stay under 20% for 7 days; the committed heap keeps memory near 33%. `SuccessExitStatus=143`: the JVM exits 143 on SIGTERM, so a normal stop or deploy is not logged as a failure |
| Configuration | `/etc/reporead/backend.env` (root 0600, includes the database password and `REPOREAD_ALLOWED_GITHUB_USER_IDS`), `/etc/reporead/github-client-secret` (`reporead` 0600), `/etc/reporead/github-token-key` (`reporead` 0600), `/etc/reporead/db-password` (root 0600, also the Podman secret `reporead-db-password`) |
| HTTPS | Caddy (`/etc/caddy/Caddyfile`), Let's Encrypt via HTTP-01, proxies only to `127.0.0.1:8081` |
| Backups | `reporead-backup.timer` runs `/usr/local/sbin/reporead-backup` nightly at 18:30 UTC (02:30 in Singapore; a run missed while the VM is down happens at boot): a `pg_dump` custom-format archive in `/var/lib/reporead-backups` (root 0700, files 0600), kept only if `pg_restore --list` reads it, newest 14 kept. Each deploy also writes a `deploy-*.dump` just before the restart (newest 14 kept, apart from the nightly ones). Dumps taken by hand before migrations there (`reporead-*.dump`) are never removed. The GitHub token key is not in a dump: restoring without it works, and each user signs in again. Copied off the VM every evening by the developer's Mac: launchd job `org.reporead.backup-pull` runs `~/.config/reporead/reporead-backup-pull` (from `backend/deploy/`) at 21:00, or at the next wake, into `~/Backups/reporead` (0700, newest 30). It logs in as `backup`, whose only key (`~/.ssh/reporead_backup` on the Mac) is forced to run `reporead-backup-latest`, and whose sudo rule (`/etc/sudoers.d/reporead-backup`) allows only that. Restoring is `pg_restore --no-owner -U reporead -d reporead` into an empty database; rehearsed on 2026-10-15 with the first Mac copy in a throwaway PostgreSQL 17.11 (schema version 12; 1 user, 871 documents, 19 annotations, 11 grades) |

**Deploying.** A push to `main` that touches the backend or the build runs `.github/workflows/backend.yml`: the formatting check and backend tests in one job, `bootJar` in another, then a third pipes the jar over SSH to the `deploy` user (secret `DEPLOY_SSH_KEY`, held by the `production` environment, which only `main` may use; the VM's host key is pinned in the workflow). Only that third job holds the key, and it runs no third-party code: it checks out the repository, downloads the jar and runs `ssh`, so nothing a test or build dependency did on its own runner can reach the key or the jar a test ran beside. Pull requests run the first two jobs. `reporead-deploy` checks that stdin is a RepoRead boot jar, writes a deploy dump, keeps the running jar as `/opt/reporead/backend.previous.jar` if it answers its health check (so a failed deploy never replaces the last good one), restarts the service, and waits up to 120 s for Actuator health; otherwise it exits 1, failing the run, and leaves the new jar in place. It never puts the previous jar back itself, because the new jar may already have migrated the schema and the previous one cannot run on a newer schema. Going back is by hand: install the previous jar and restart, which the expand-only rule for migrations makes safe (ADR-24); restore the deploy dump into an empty database first only when the data itself must go back. It prints only outcomes and the jar's SHA-256, because the repository and its CI logs are public. To deploy by hand, build with `./gradlew :backend:bootJar --no-daemon`, then `ssh opc@152.69.219.238 'sudo /usr/local/sbin/reporead-deploy' < backend/build/libs/backend.jar`. Changes to `backend/deploy/` (the deploy and backup scripts and units) take effect only once copied to the VM by hand (scripts root 0755 in `/usr/local/sbin`, units in `/etc/systemd/system`). CI cannot change them: its key runs only the deploy script, and letting it replace root's scripts would give anything that runs in a CI build root on the VM.

Logs: `sudo journalctl -u reporead-backend`. Restarts and deploys keep GitHub sign-ins (stored encrypted, see the sign-in contract).

## Sign-in contract (Android)

1. The app creates a PKCE verifier and opens `GET /app/sign-in?code_challenge=<S256 challenge>` in a Custom Tab. The server keeps the challenge in a browser session that lasts 5 minutes; at most 100 exist at once, and more sign-ins get 503 `SIGN_IN_BUSY` until some expire. A refused browser request opens no session, whatever its method: the browser routes are all GETs, so there is no CSRF token to store, and no logout.
2. The browser runs GitHub OAuth (Spring Security owns state, PKCE with GitHub, and code exchange). The callback saves the user, issues a **single-use, 60-second** code bound to the app's challenge, **ends the browser session**, and redirects to the one app that uses this server. On an HTTPS origin that is `<origin>/app/signed-in?code=…`, a verified App Link: `/.well-known/assetlinks.json` names the release app (`com.reporead.android`) and its signing certificate's SHA-256, and Android opens the link only in that app. A custom scheme would not do: any app can register one, start a sign-in of its own and receive the code, which PKCE does not stop because that app sends its own challenge. A browser that keeps the link instead of handing it over shows a page whose link names the release app's package (`intent://…;package=com.reporead.android`), so a tap opens only that app. On the Mac's loopback address, which has no domain to verify, the debug app receives `reporead-dev://auth?code=…`. A GitHub login starts only in a browser session that `/app/sign-in` began: `/oauth2/authorization/github` without that session's challenge is 401 and never reaches GitHub, so no other login reaches the callback, where Spring stores the user's GitHub token.
3. The app sends `POST /api/app-auth/token {code, codeVerifier, expectedUserId?}` and receives `{accessToken, expiresAt, user}`. A wrong verifier consumes the code. `expectedUserId` names the account whose data the app holds; a code for another account consumes the code and issues no session (409 `ACCOUNT_MISMATCH`). Sessions last 30 days; `DELETE /api/app-auth/session` signs out; signing out the user's last unexpired session also revokes RepoRead's GitHub authorization for them (`DELETE /applications/{client_id}/grant` as the App, which GitHub documents as deleting all of the app's tokens for that user; at most 2 GitHub calls, renewing an expired token first) and deletes the stored token. A failed revocation is logged and does not stop the sign-out; GitHub then expires the tokens itself. The next sign-in shows GitHub's authorization screen again.
4. `/api/**` accepts only `Authorization: Bearer <accessToken>`. It is stateless: browser session cookies never authenticate the API. Any 401 means "sign in again".

Only GitHub users listed in `REPOREAD_ALLOWED_GITHUB_USER_IDS` can use the server (`AllowedUsers`). Anyone else's sign-in fails while GitHub's answer is checked, before a RepoRead user or their GitHub token is stored, and ends at `/auth/failed`; a session of a user later removed from the list is 401.

Only SHA-256 hashes of codes and session tokens are stored, and expired ones are deleted whenever a new code or session is issued. The GitHub user token and its refresh token are stored in `github_tokens`, each encrypted with AES-256-GCM under the key in `REPOREAD_GITHUB_TOKEN_KEY_FILE` and bound to its GitHub user (`GitHubTokens`), and are never returned. GitHub App user tokens last 8 hours: before a GitHub-backed operation, a token expiring within a minute is renewed with one call to GitHub's token endpoint (one at a time per user, because GitHub replaces the refresh token on each use; the lock is in-process, and the backend runs as one process by design, ADR-14). If GitHub refuses the refresh token (`bad_refresh_token`, `invalid_grant`), the stored token is dropped and the operation returns 401 `SIGN_IN_REQUIRED`; GitHub answers that with HTTP 200 and an error body, which is read as the error it is. Any other refusal, such as `incorrect_client_credentials` from a wrong client secret, and no answer from GitHub (network failure, timeout, 429, 5xx) return 503 `GITHUB_UNAVAILABLE` and keep the token, so the next operation tries again. Restarts and deploys keep them. A stored token the key cannot decrypt (the key file was replaced, or the wrong file named) reads as no token, and that user signs in again, which overwrites it. It is not deleted, so naming the right key file again restores every sign-in.

## API

| Route | GitHub calls (ceiling) | Notes |
| --- | --- | --- |
| `GET /api/auth/me` | 0 | `{id, githubUserId, login}` |
| `GET /api/library` | 0 | Every list the phone saves, from one read-only `REPEATABLE READ` snapshot (ADR-08), in one request and a fixed 6 queries however many repositories; an answer over 8 MiB is refused whole (422 `LIBRARY_TOO_LARGE`). `repositories`: this user's connections, each with its sync checkpoint, document count, and `documents`: every Markdown document of the connection, each with `contentChangedAt` (see Sync semantics) and `deleted` when the latest complete refresh did not have it, the one place a client learns a note's title, path, current version and removal (the other lists name only the document id). `readingStates`: most recently read first, with the version last read. `bookmarks`: a note that left the repository keeps its bookmark. `notebook`: the complete highlight/card projection, the durable review log, and the server-owned `sessionLimit` (40) |
| `GET /api/repositories` | 0 | This user's connections, sync checkpoint, document count. Kept only for app releases before `GET /api/library`; the library contract step deletes it (ADR-08) |
| `GET /api/repositories/available` | 1 + installations, at most 11 | Repositories GitHub says this user and the App can both access |
| `POST /api/repositories/{githubRepositoryId}/connect` `{installationId}` | 1 | GitHub re-verifies eligibility. Connecting again returns the same connection, with its data, under GitHub's current name and default branch: the app's **Update** after either is renamed on GitHub, whose redirects for the old name RepoRead does not follow (409 `REPOSITORY_RENAMED`) |
| `POST /api/repositories/{id}/sync` | 2 (branch, recursive tree) + at most 8 blob reads (moved-and-edited notes) | Publishes a complete snapshot of the default branch |
| `GET /api/repositories/{id}/stored-data` | 0 | What disconnecting would delete: counts of documents, reading states, bookmarks, highlights, and cards |
| `DELETE /api/repositories/{id}` | 0 | Disconnects: deletes the connection and everything stored for it (documents, reading states, bookmarks, highlights, cards and their review logs) in one transaction; returns the deleted document ids and counts; 404 once already disconnected. GitHub and the App installation are unchanged |
| `DELETE /api/account` | at most 2 (renew, revoke) | Deletes every connection and its data, the annotation mutation records, sign-in codes, sessions, the user, and the stored GitHub token in one transaction; once that has committed, revokes RepoRead's GitHub authorization as sign-out does (with the token read before the deletion; a failed revocation is logged and the account is still deleted) |
| `GET /api/repositories/{id}/documents` | 0 | Every Markdown document of the connection and the last synced commit, as in the library. Kept only for app releases before `GET /api/library`; the library contract step deletes it |
| `GET /api/documents/{id}/content` | 1 (raw blob at the stored SHA) | Sanitized reader HTML; not cached on the server |
| `GET /api/documents/{id}/image?path=` or `?embed=` | 1 (file at the note's current commit); 0 when an embed name is missing or ambiguous | An image in the note's repository, by path or by Obsidian embed name; access is per repository, not per reference, as the user's GitHub token reads the whole repository anyway; see below |
| `GET /api/documents/{id}/changes?since=&to=` | 2 (both versions' blobs); 0 when `since` = `to` | Sections changed between the version last read and the version on screen; see Changes since last read |
| `GET /api/reading-states` | 0 | As the library's `readingStates`. Kept only for app releases before `GET /api/library`; the library contract step deletes it |
| `PUT /api/documents/{id}/reading-state` | 0 | `{lastReadBlobSha, progressPercent, anchor:{headingPath, textPrefix, blockIndex}, lastReadAt}`; last write wins by `lastReadAt` |
| `GET /api/bookmarks` | 0 | As the library's `bookmarks`. Kept only for app releases before `GET /api/library`; the library contract step deletes it |
| `PUT` / `DELETE /api/documents/{id}/bookmark` | 0 | No body: a bookmark is of the note, whatever its version; both idempotent |
| `GET /api/documents/{id}/annotations` | 0 when every highlight is resolved against the current version; else 1 (that version) | This user's highlights and cards: original `anchor`, current `location`, `status` (`ANCHORED`, `REANCHORED`, `ORPHANED`) for `resolvedBlobSha`, and the creating `mutationId` |
| `POST /api/documents/{id}/annotations` | 1 (the selected version's blob); 0 on replay | `{mutationId, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}, note?, type?, question?}`; 201 created, 200 replay |
| `GET /api/notebook` | 0 | As the library's `notebook`, in a repeatable snapshot of its own. Kept only for app releases before `GET /api/library`; the library contract step deletes it |
| `POST /api/annotations/{id}/reviews` | 0 | `{mutationId, grade:0..5, reviewedAt, blobSha}`; idempotent, changed/unconfirmed answers rejected |
| `POST /api/annotations/{id}/check` | 0 | `{expectedVersion, blobSha}`; explicitly confirms a resolved, non-orphaned card in the current version; 409 `ANNOTATION_CONFLICT` on a stale version, 409 `CARD_CHANGED` when the card is not resolved against the current version |
| `PATCH /api/annotations/{id}` | 0 | `{note, expectedVersion}`; 409 `ANNOTATION_CONFLICT` if another edit landed first |
| `DELETE /api/annotations/{id}?expectedVersion=` | 0 | 409 on a stale version; 400 `INVALID_ANNOTATION` without a version from 1 |
| `POST /api/annotations/{id}/reattach` | 1 (the selected version's blob) | `{expectedVersion, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}}`; places the highlight on the user's selection, `REANCHORED`; 409 on a stale version |

**Saving every note on the phone** uses `GET /api/documents/{id}/content` once per note whose saved copy is missing or outdated, one request at a time; there is no batch route. One run therefore makes at most one GitHub call per such note, bounded by the repository's Markdown document count (server limit 5,000), and none for notes already saved; a note the server refuses to render (`UNSUPPORTED_CONTENT`) has no saved copy, so every run asks for it again. The phone owns which notes it lacks; the server owns the per-request ceiling and the document limit. A first run on a 654-note repository makes 654 calls, within GitHub's documented 5,000 requests per hour for user tokens (not measured for this App's token).

Every GitHub call uses the user's token, 15-second connect/read timeouts, no redirects (a redirect of a call naming a repository follows a rename and is 409 `REPOSITORY_RENAMED`; of a listing, `GITHUB_INVALID_RESPONSE`), no retries, no pagination, and no installation-token fallback. The ceilings in the table exclude the token renewal (at most one per operation, see the sign-in contract). A failed call ends the operation. Failures are `{code, message}`, including requests a route cannot read (malformed JSON, a missing parameter, a mistyped path value: 400 `INVALID_REQUEST`). A message names no client's screens or controls; each client words codes for its own users. A response the server itself could not read is never one of those: it is a 500. `ApiFailure.Code` lists every code with the one HTTP status it is sent with; `NOT_FOUND` does not tell an absent id from another user's. Upstream error bodies are not exposed. A route, method or body type the server does not have (404, 405, 415) is answered with Spring Boot's own error body, which has no `code`, deliberately: the app treats a RepoRead 400/404/409/410/422 (except 409 `REPOSITORY_RENAMED`, which only reconnecting fixes) as the server refusing that item for good and drops or marks the pending change, so an app newer than the server must not read a missing route as RepoRead's answer (`ApiErrorsTest`).

Every connection, document, and content request is scoped to the signed-in user; another user's ids return 404 without a GitHub call.

Writes that add or change a document's rows (annotation creation, edits, reattachments, card confirmations and grades, reading-state saves, bookmarks) share the connection's lock and read their result back under it, so a concurrent sync or disconnect is waited for; a write that arrives during a disconnect then finds nothing (404) instead of breaking it. Account deletion locks the user first, and `connect` takes the same lock before adding its connection, so a connection being added at the same time is either deleted with the rest or, arriving after, refused with 401 `SIGN_IN_REQUIRED`. A re-anchoring computed before the user edits, reattaches, or confirms an annotation does not apply over that change.

### Observability

Logs name ids, SHAs, counts, outcomes, and repository paths; never tokens, authorization headers, note text, quotes, or annotation notes. Metrics (Micrometer, Spring Boot Actuator) are served only on the management port `127.0.0.1:8082`, which exposes `health`, `metrics` and `info` and nothing else; `adb reverse` forwards only 8081, so the phone cannot reach it. Tags never carry paths or content. `info` names the Git commit the running jar was built from (`build.commit`, from `springBoot.buildInfo` in `backend/build.gradle.kts`), so a deploy can be checked on the host.

| Meter | Tags | Counts |
| --- | --- | --- |
| `reporead.github.requests` | `outcome`: `2xx`/`3xx`/`4xx`/`5xx`, `too_large`, `failed` | Every GitHub HTTP request, including sign-in |
| `reporead.github.syncs` | `outcome`: `success`/`failure`; `code`: the failure code or `NONE` | Repository syncs (the spec's sync total and sync failures) |
| `reporead.documents.synced` | — | Documents in each successful sync |
| `reporead.annotations.created` | — | New anchored annotations (highlights/cards; replays are not counted) |
| `reporead.annotation.reanchor` | `outcome`: `BLOCK`/`POSITION`/`QUOTE`/`FUZZY`/`ORPHANED` | Applied re-anchoring decisions (the spec's orphaned total is `outcome=ORPHANED`) |
| `reporead.annotation.reanchor.duration` | — | Time per resolution (timer) |

```sh
curl -s http://127.0.0.1:8082/actuator/metrics/reporead.github.requests
curl -s http://127.0.0.1:8082/actuator/info   # {"build":{"commit":"<sha>",...}}
```

Meters appear after their first use. They are in memory and reset when the server restarts: a projection, never application state.

### Server-owned limits

| Limit | Value | Failure |
| --- | --- | --- |
| Request body | 256 KiB, read before anything parses it, signed in or not; the largest valid body (a card's three texts) is under 180 KB. Form and multipart bodies are never parsed | 413 `REQUEST_TOO_LARGE` |
| GitHub responses (OAuth, metadata, raw note) | 1 MiB | 502 `GITHUB_RESPONSE_LIMIT`; a note over the limit is 422 `UNSUPPORTED_CONTENT` |
| Recursive tree response | 8 MiB (GitHub's own maximum is 7 MB) | 502 `GITHUB_RESPONSE_LIMIT` |
| Installations / repositories per installation | 10 / 100, complete lists only | 502 `GITHUB_INSTALLATION_LIMIT` / `GITHUB_REPOSITORY_LIMIT` |
| Markdown documents per repository | 5,000 | 422 `DOCUMENT_LIMIT` |
| Library answer | 8 MiB of JSON, measured after serializing (`Library.MAX_RESPONSE_BYTES`); the app's build reads this line as its own ceiling for the answer | 422 `LIBRARY_TOO_LARGE`, nothing of the library sent |
| Note render | 1 MiB UTF-8, 4,096 blocks, 16 diagrams of 20,000 UTF-16 units | 422 `UNSUPPORTED_CONTENT` |
| Mermaid edges | 200 per diagram, passed to the reader as `data-max-edges` | Enforced by Mermaid on the phone: that diagram shows as an error with its source; the note itself renders (200) |
| Heading paths | Each heading in a block's path at most 500 UTF-16 units; a longer heading (for example a long paragraph directly followed by `---`) is cut and ends in `…`. The heading's own block text is not cut | None: rendered pages always fit the reading-state limit below |
| Repository images | 64 per note; 5 MiB each | Extra images render as `[Image limit reached]`; larger images 422 `UNSUPPORTED_CONTENT` |
| Annotation | selection, note, and card question at most 10,000 UTF-16 units each; one block per selection; a selection, note or question with NUL or an unpaired surrogate (a selection that splits a character), which PostgreSQL cannot store exactly | 400 `INVALID_ANNOTATION` |
| Review | grades 0–5; client UUID; millisecond timestamp from 1970 through 5 minutes ahead | 400 `INVALID_REVIEW`; changed answers 409 `CARD_CHANGED` |
| Review session | 40 cards, reported as `sessionLimit` in the notebook; the phone has no limit of its own and starts no session without it | Applied where sessions are built, on the phone, because scheduling runs there so review works offline (ADR-13). Grades are not refused for it: refusing a grade can only discard a review already done |
| Reading state | 6 headings of 500 chars, 200-char text prefix, block index 0–4095, `lastReadAt` from 1970 to at most 5 minutes ahead | 400 `INVALID_READING_STATE` |
| Changes comparison | 20,000 lines per version; 1,000 inserted plus deleted lines | `TOO_LARGE` status, no sections |

These are chosen ceilings, not measured ones. Clients cannot change them.

### Sync semantics

Sync reads the default branch's commit, then its full recursive tree. Documents are regular-file blobs ending in `.md` (any case); symlinks and submodules are skipped. A truncated or malformed tree fails without changes and is never evidence of deletion. Only after complete validation does one transaction upsert documents by path, mark paths absent from the complete tree as deleted (rows are kept), and advance the connection's commit checkpoint. Repeating a sync is idempotent; concurrent syncs of one connection are serialized by a row lock. A sync that fetched its branch before another sync published a different commit, or whose default branch connecting again changed meanwhile, changes nothing and fails with 409 `SYNC_SUPERSEDED`, so an older snapshot, or one of a branch no longer followed, never replaces the current one; refreshing again picks up the latest. A document whose path left the snapshot keeps its id at a path that has never had a document when both share a blob SHA unique on each side (a path-only move); identical-content duplicates are never merged, and a path that once had a document resumes it. A vanished document carrying the user's data also keeps its id at a new path whose content is alike enough (measured thresholds in [ADR-07](../docs/adrs.md#adr-07--document-identity-from-complete-snapshots)), checked only when vanished documents plus new paths number at most 8. Because every sync is a complete snapshot, a branch rewind is reconciled like any other: paths absent from it are marked deleted and return to their documents if they reappear.

Each sync also records the snapshot's image file paths (`repository_images`), replaced every time, for resolving Obsidian embeds.

`contentChangedAt` is when a sync found the note new, with a different blob, or back after being deleted (a path-only move is not a change). It is null for notes from a connection's first sync and for notes RepoRead has not seen change since change times were added (Flyway V5): it is when RepoRead noticed, not the commit time.

### Images

The renderer resolves relative and root-relative Markdown images against the note's directory, as GitHub does, and rewrites them to the same-origin path `/repo-image/<repository path>`. The Android reader serves that path from its offline cache or from `GET /api/documents/{id}/image`, attaching the bearer token in native code; the page's JavaScript never sees a credential. Only normalized repository paths ending in png, jpg/jpeg, gif, webp, or svg are served (`nosniff`). Remote (`https:`, `//`) images, references escaping the repository, and other file types render as visible `[… blocked]`/`[Unsupported image]` text.

### Obsidian links and embeds

The renderer turns `[[note]]`, `[[note|alias]]`, `[[note#Heading]]` (block references `#^id` open the note), same-note `[[#Heading]]`, and relative links to `.md` files into `/note-link?target=…|path=…&heading=…` links that the app intercepts; code is never linked. `![[image.png]]` (optionally `|300` or `|300x200`) becomes an image at `/repo-embed/<name>`, fetched with `image?embed=<name>`; `![[note]]` becomes a link. The source text stays in the page and only the brackets and targets are hidden (`.wl-hidden`), so canonical block text — and every highlight anchored in it — is unchanged. An embed name resolves like Obsidian against the latest snapshot's image files: a name with a folder must match the end of a path, a bare name a file name (case-insensitively); several matches resolve to the one in the note's folder, otherwise 409 `IMAGE_AMBIGUOUS`; none is 404 `IMAGE_NOT_FOUND`. Footnotes (`text[^label]` and `[^label]: definition` paragraphs, as Obsidian writes them) are marked without a parser extension, which would split and move definitions and so change blocks of unchanged blobs: a paragraph starting with `[^label]:` defines footnotes, each later marker after whitespace starting the next (consecutive definitions are one CommonMark paragraph); references to defined labels outside code and links become `sup.fn-ref`; both keep their source text hidden and carry `data-footnote` (label) and `data-label` (number, in definition order). The content response's `renderFormat` (`MarkdownRenderer.FORMAT`, now 3: footnotes) tells the app when a saved page predates these features.

### Reading state and bookmarks

Only the reader writes reading state, recording the blob SHA actually displayed; repository refreshes never change it. A write older than or equal to the stored `lastReadAt` is ignored and the current state is returned, so replaying a stale offline save cannot overwrite newer progress. Deleting a document upstream keeps its reading state and bookmarks. Bookmarks live in the `annotations` table as type `BOOKMARK` (one per user and document).

### Changes since last read

The phone sends `since`, the version the user last read as it knows it (its unsent reading saves can be newer than the server's), and `to`, the version on screen. Both must be blobs in the note's repository. The server fetches both, splits the Markdown into lines as the parser numbers them, computes a minimal line diff (Myers), and assigns each inserted or deleted line to the heading section it is in: from a heading's line to the next heading of any level, with lines before the first heading as the beginning of the note. Sections are told apart by position, so duplicate headings are separate. A section is `ADDED` when its heading line is new and `CHANGED` otherwise, with its heading's `blockId` in `to` for navigation; an old section whose heading line was deleted is `REMOVED` and listed where it used to be. A renamed heading is a removed and an added section. Counts are source lines, so blank-line edits count.

`status` is `CHANGED` (with `sections`, possibly empty when only line endings differ), `UNCHANGED` (same version, no GitHub call), `SINCE_UNAVAILABLE` with a `reason` (GitHub no longer has the older version, or it can no longer be read as a note), or `TOO_LARGE` (over the limits above). Only `CHANGED` lists sections; a too-large comparison is never shown as a partial list. Any other failure, such as a GitHub outage or the newer version missing, fails the request; a missing or malformed `since`/`to` is 400 `INVALID_VERSIONS`, and a deleted note is 410 `DOCUMENT_DELETED`. Nothing is cached. A note never read has no `since`, and the phone does not ask.

The cost is proportional to lines × changed lines; constructed worst cases at both limits took at most 25 ms.

### Annotations

A highlight (type `HIGHLIGHT`, with an optional note) is anchored to one source version using the [text contract](../docs/adrs.md#adr-05--anchors-are-block-ids-and-utf-16-offsets-into-canonical-text-of-one-version): block id, UTF-16 offsets into that block's canonical text, and the exact text. On creation the server fetches that exact blob, renders it with the same renderer as the reader, and rejects a selection that does not name real text in it (422 `INVALID_ANCHOR`). It derives the 32-character prefix/suffix context and heading path itself. Source Markdown is never written.

Creation is idempotent per client `mutationId` (a UUID): the first request records the mutation, annotation, and anchor in one transaction. Replaying the same request returns the original annotation (200) without a GitHub call — including after a lost acknowledgement or concurrently with the original. Reusing the id for different content is 409 `MUTATION_ID_REUSED`; replaying after the annotation was deleted is 410 `ANNOTATION_DELETED`, so a late replay cannot resurrect it. A failure before commit stores nothing, and the same mutation can be retried. Note edits and deletion use optimistic versions and never silently overwrite.

**Re-anchoring.** The original anchor never changes. Each highlight also has a current `location` (where it was made, re-anchored, or reattached) and a `status` for `resolvedBlobSha`. Listing a document's highlights resolves any that were resolved against an older version, using `Anchoring`: the location's block unchanged anywhere, then unchanged position, then the exact quote singled out by context and heading, then a bounded fuzzy match. Anything weaker is `ORPHANED`, keeping its last location so the next version is resolved from there; the user can reattach it. Re-anchoring does not change the user-edit `version`. Thresholds and their measurement on a real notes repository are in [ADR-06](../docs/adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan). A deleted document's highlights are listed as last resolved, without GitHub calls.

The server does not yet check that `sourceBlobSha` is a version of this particular document, only that it is a blob in the document's repository that renders to the selected text.

### Review cards (P4)

`CARD` is an anchored annotation with a required, non-blank `question` (10,000 UTF-16 units maximum); `HIGHLIGHT` remains the default when `type` is omitted. Existing highlight creation fingerprints are unchanged so old mutation ids still replay. Cards use the same verification, original anchor, current location, optimistic edit/delete, and orphan/reattach path as highlights. V7 adds the type, question, `checked_blob_sha`, and `review_log`; no source Markdown is changed and no note bodies are retained.

The library's `notebook` (`GET /api/library`, and `GET /api/notebook` until the library contract step) is one repeatable database snapshot of this user's anchored annotations, review log and `sessionLimit=40`. It does **not** fetch versions or re-anchor: opening a note uses the existing annotation listing to resolve it. A card whose checked answer SHA differs from the current document SHA cannot be graded, even before re-anchoring has run. Any re-anchoring or reattachment clears confirmation. `POST .../check` confirms only a non-orphaned location resolved against the current, non-deleted document and increments the optimistic version. Conservatively, **every note-version change requires checking its cards**, including edits outside their answer section; this also covers changed-section uncertainty without fetching extra versions. Confirmation preserves review history.

`POST .../reviews` persists one immutable grade, timestamp and verified SHA per user/client UUID. Repeating an identical mutation returns the same log entry; different content is 409 `MUTATION_ID_REUSED`. A committed replay still succeeds after the note changes; a new stale grade rolls back with 409 `CARD_CHANGED`. Grades serialize with repository refresh/disconnect via the existing connection lock, then with annotation changes. Deleting a card cascades its review log; a late submission is 404 and cannot recreate a deleted card. Disconnect counts cards and deletes them/logs; account deletion also removes mutation records. These routes have **no alternate strategy or retry** on failure.

**Known limit (recorded, not paged).** `GET /api/library` returns every annotation and every grade in one response with the other lists, and the phone reads at most 8 MiB of JSON per response. At about 200 bytes per grade that is roughly 40,000 grades (about 3 years at 40 a day). Past it, the server refuses the library (422 `LIBRARY_TOO_LARGE`, at `Library.MAX_RESPONSE_BYTES`, which the app's build reads as its own ceiling) and no list loads (before 2026-10-18 the notebook was read on its own, last, so the other lists still loaded); pending changes are still sent, since a sync sends before it reads. Paging is deferred until the log approaches the limit.

The server stores no due dates, scores derived from history, or scheduling state. The phone alone computes each card's step on its gap ladder from the log, retaining pending grades until acknowledgement. Logs use millisecond timestamps so PostgreSQL timestamp precision cannot change an idempotent replay's content. In a phone session, the first grade of each card is saved; a card graded Again comes back later in the same session, unsaved, until answered right, so the server receives one grade per card per session. A saved grade below 3 restarts the card's interval at one day ([ADR-13](../docs/adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling)). No AI or FSRS dependency is included; the phone sends grades with its other pending changes (ADR-08).

## Verify

```sh
./gradlew :backend:spotlessCheck --no-daemon   # formatting; spotlessApply rewrites the files instead
./gradlew :backend:test --no-daemon
```

Spotless with palantir-java-format (`backend/build.gradle.kts`) owns the backend's layout, Javadoc included; CI runs `spotlessCheck` before the tests.

Tests run against a real PostgreSQL 17.11 container through Testcontainers (Docker must be running). GitHub is mocked with explicit test-only data; tests do not prove live GitHub authorization.

Three opt-in harnesses run only when pointed at a local clone of a notes repository, and are skipped otherwise. Their output contains private note text: write it outside the repository and never commit it ([ADR-23](../docs/adrs.md#adr-23--how-a-change-is-verified)).

```sh
# Re-anchoring thresholds (ADR-06); rerun whenever Anchoring changes. REPOREAD_MEASURE_SEED picks another sample.
REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<private file> ./gradlew :backend:test --tests '*AnchorMeasurement' --rerun --no-daemon
# Moved-and-edited similarity (ADR-07).
REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<private file> ./gradlew :backend:test --tests '*MoveMeasurement' --rerun --no-daemon
# Every note rendered to <dir>; run before and after a renderer change and diff the blocks (ADR-18). The app's StudyCorpus reads <dir>/pages.
REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon
```

Dependencies: Spring Boot web MVC, Security OAuth2 Client, JDBC, Actuator, and Flyway; the PostgreSQL driver; CommonMark with the GFM tables, strikethrough and task-list extensions; jsoup (its version is the root `jsoupVersion`, shared with the app) for sanitizing and canonical block text; JUnit, Spring test support, and Testcontainers. Mermaid and code highlighting are client-side display only.
