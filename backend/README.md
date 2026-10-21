# RepoRead backend

Spring Boot owns user identity, app sessions, authorized repository connections, logical Markdown documents and their identity across moves, safe note rendering, reading state, bookmarks, highlights and review cards, re-anchoring, and the review log. GitHub owns the Markdown; PostgreSQL owns RepoRead's state. The server never writes to GitHub.

This README owns the backend's contracts and commands. Why things are this way is in the [ADRs](../docs/adrs.md), the rules every change keeps in [invariants](../docs/invariants.md), naming and decomposition are in [code style](../docs/code-style.md), and how the backend fits with the app is in the [architecture](../docs/architecture.md).

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
| PostgreSQL 17.11 | Podman Quadlet `/etc/containers/systemd/reporead-postgres.container` (service `reporead-postgres`, volume `reporead-postgres`, health-gated `Notify=healthy`, checked every 30 s: `HealthInterval=30s`, because Podman files one journal line per check and every 10 s was the largest share of the journal; a failing check is still filed). Host network with `listen_addresses=127.0.0.1` and no published port: a `firewall-cmd --reload` wipes Podman's NAT rules, which once left the backend hanging on connect. `log_checkpoints` is off (set 2026-10-21 with `podman exec reporead-postgres psql --username=reporead --dbname=reporead -c "ALTER SYSTEM SET log_checkpoints = off" -c "SELECT pg_reload_conf()"`): Podman files the container's stderr as *error*, and a checkpoint every 5 minutes filled the journal's error view. The setting lives in the volume's `postgresql.auto.conf`, so a new volume needs the command again; a restored dump does not carry it |
| Deploys | `/usr/local/sbin/reporead-deploy` (from `backend/deploy/reporead-deploy`); user `deploy`, whose only SSH key is forced to run it via `sudo` (`/etc/sudoers.d/reporead-deploy`), with no shell, PTY or forwarding |
| Backend | `/opt/reporead/backend.jar` (root-owned), service `/etc/systemd/system/reporead-backend.service` (from `backend/deploy/reporead-backend.service`), user `reporead`, starts after the database is healthy. Runs with `-Xms1g -XX:+AlwaysPreTouch`: RepoRead alone used ~16% of memory, and Oracle may reclaim an Always Free VM whose CPU, network and memory all stay under 20% for 7 days; the committed heap keeps memory near 33%. `SuccessExitStatus=143`: the JVM exits 143 on SIGTERM, so a normal stop or deploy is not logged as a failure |
| Configuration | `/etc/reporead/backend.env` (root 0600, includes the database password and `REPOREAD_ALLOWED_GITHUB_USER_IDS`), `/etc/reporead/github-client-secret` (`reporead` 0600), `/etc/reporead/github-token-key` (`reporead` 0600), `/etc/reporead/db-password` (root 0600, also the Podman secret `reporead-db-password`) |
| HTTPS | Caddy (`/etc/caddy/Caddyfile`), Let's Encrypt via HTTP-01, proxies only to `127.0.0.1:8081` |
| Backups | `reporead-backup.timer` runs `/usr/local/sbin/reporead-backup` nightly at 18:30 UTC (02:30 in Singapore; a run missed while the VM is down happens at boot): a `pg_dump` custom-format archive in `/var/lib/reporead-backups` (root 0700, files 0600), kept only if `pg_restore --list` reads it (the script then reads the rest of the dump, so conmon does not log a "Broken pipe" warning per chunk of it, about 300 journal lines for a 113 KB dump), newest 14 kept. Each deploy also writes a `deploy-*.dump` just before the restart (newest 14 kept, apart from the nightly ones). Dumps taken by hand before migrations there (`reporead-*.dump`) are never removed. The GitHub token key is not in a dump: restoring without it works, and each user signs in again. Copied off the VM every evening by the developer's Mac: launchd job `org.reporead.backup-pull` runs `~/.config/reporead/reporead-backup-pull` (from `backend/deploy/`) at 21:00, or at the next wake, into `~/Backups/reporead` (0700, newest 30). It logs in as `backup`, whose only key (`~/.ssh/reporead_backup` on the Mac) is forced to run `reporead-backup-latest`, and whose sudo rule (`/etc/sudoers.d/reporead-backup`) allows only that. Restoring is `pg_restore --no-owner -U reporead -d reporead` into an empty database; rehearsed on 2026-10-15 with the first Mac copy in a throwaway PostgreSQL 17.11 (schema version 12; 1 user, 871 documents, 19 annotations, 11 grades) |
| Logs | The journal is persistent and capped at 1 GB (`/etc/systemd/journald.conf.d/reporead.conf`, from `backend/deploy/journald-reporead.conf`), so a reboot keeps the backend's logs. Installed 2026-10-21; `/var/log/journal` already existed (dated 2026-10-16), so journald's default `Storage=auto` had been keeping them since then, uncapped |
| Log viewer | Cockpit (Oracle Linux's web console; package `cockpit`), `cockpit.socket` bound to `127.0.0.1:9090` by `/etc/systemd/system/cockpit.socket.d/reporead-loopback.conf` (from `backend/deploy/cockpit-loopback.conf`); no open port and no Caddy route, because the journal holds note text. Its **Logs** page shows and searches the journal. Under systemd each backend line starts with journald's priority prefix (`JournaldPriorityConverter` in `logback-spring.xml`: `<3>` ERROR, `<4>` WARN, `<6>` INFO), which journald strips, so the journal files every line at its level, the device's lines included: **Warning and above** shows the problems, and **Info and above** shows everything. A stack trace's later lines are *info*. The prefix is written only when stdout is the journal (systemd's `JOURNAL_STREAM`), so a terminal or test output has none. Reached through an SSH tunnel: the Mac's `~/.config/reporead/reporead-logs` forwards `localhost:19090` and opens `https://localhost:19090/system/logs` with that priority and the backend's identifier (`reporead-backend`, set by `SyslogIdentifier` in its unit) selected (a self-signed certificate warning the first time). Login: user `logview` (in `systemd-journal`, so it reads the journal without admin rights; no SSH key, and SSH password login is off) |

**Deploying.** A push to `main` that touches the backend or the build runs `.github/workflows/backend.yml`: the formatting check and backend tests in one job, `bootJar` in another, then a third pipes the jar over SSH to the `deploy` user (secret `DEPLOY_SSH_KEY`, held by the `production` environment, which only `main` may use; the VM's host key is pinned in the workflow). Only that third job holds the key, and it runs no third-party code: it checks out the repository, downloads the jar and runs `ssh`, so nothing a test or build dependency did on its own runner can reach the key or the jar a test ran beside. Pull requests run the first two jobs. `reporead-deploy` checks that stdin is a RepoRead boot jar, writes a deploy dump, keeps the running jar as `/opt/reporead/backend.previous.jar` if it answers its health check (so a failed deploy never replaces the last good one), restarts the service, and waits up to 120 s for Actuator health; otherwise it exits 1, failing the run, and leaves the new jar in place. It never puts the previous jar back itself, because the new jar may already have migrated the schema and the previous one cannot run on a newer schema. Going back is by hand: install the previous jar and restart, which the expand-only rule for migrations makes safe (ADR-24); restore the deploy dump into an empty database first only when the data itself must go back. It prints only outcomes and the jar's SHA-256, because the repository and its CI logs are public. To deploy by hand, build with `./gradlew :backend:bootJar --no-daemon`, then `ssh opc@152.69.219.238 'sudo /usr/local/sbin/reporead-deploy' < backend/build/libs/backend.jar`. Changes to `backend/deploy/` (the deploy and backup scripts, the backend and backup units, the journal setting and the Cockpit socket setting) take effect only once copied to the VM by hand (scripts root 0755 in `/usr/local/sbin`, units in `/etc/systemd/system`, the journal setting in `/etc/systemd/journald.conf.d`, the Cockpit socket setting in `/etc/systemd/system/cockpit.socket.d`). CI cannot change them: its key runs only the deploy script, and letting it replace root's scripts would give anything that runs in a CI build root on the VM.

Logs: in a browser, `~/.config/reporead/reporead-logs` on the Mac (Cockpit, Log viewer above); on the VM, `sudo journalctl -u reporead-backend`; one request's lines: `sudo journalctl -u reporead-backend | grep <requestId>` (see Observability). Restarts and deploys keep GitHub sign-ins (stored encrypted, see the sign-in contract).

## Sign-in contract (Android)

1. The app creates a PKCE verifier and opens `GET /app/sign-in?code_challenge=<S256 challenge>` in a Custom Tab. The server keeps the challenge in a browser session that lasts 5 minutes; at most 100 exist at once, and more sign-ins get 503 `SIGN_IN_BUSY` until some expire. A refused browser request opens no session, whatever its method: the browser routes are all GETs, so there is no CSRF token to store, and no logout.
2. The browser runs GitHub OAuth (Spring Security owns state, PKCE with GitHub, and code exchange). The callback saves the user, issues a **single-use, 60-second** code bound to the app's challenge, **ends the browser session**, and redirects to the one app that uses this server. On an HTTPS origin that is `<origin>/app/signed-in?code=…`, a verified App Link: `/.well-known/assetlinks.json` names the release app (`com.reporead.android`) and its signing certificate's SHA-256, and Android opens the link only in that app. A custom scheme would not do: any app can register one, start a sign-in of its own and receive the code, which PKCE does not stop because that app sends its own challenge. A browser that keeps the link instead of handing it over shows a page whose link names the release app's package (`intent://…;package=com.reporead.android`), so a tap opens only that app. On the Mac's loopback address, which has no domain to verify, the debug app receives `reporead-dev://auth?code=…`. A GitHub login starts only in a browser session that `/app/sign-in` began: `/oauth2/authorization/github` without that session's challenge is 401 and never reaches GitHub, so no other login reaches the callback, where Spring stores the user's GitHub token.
3. The app sends `POST /api/app-auth/token {code, codeVerifier}` and receives `{accessToken, expiresAt, user}`. A wrong verifier consumes the code. Sessions last 30 days; `DELETE /api/app-auth/session` signs out; signing out the user's last unexpired session also revokes RepoRead's GitHub authorization for them (`DELETE /applications/{client_id}/grant` as the App, which GitHub documents as deleting all of the app's tokens for that user; at most 2 GitHub calls, renewing an expired token first) and deletes the stored token. A failed revocation is logged and does not stop the sign-out; GitHub then expires the tokens itself. The next sign-in shows GitHub's authorization screen again.
4. `/api/**` accepts only `Authorization: Bearer <accessToken>`. It is stateless: browser session cookies never authenticate the API. Any 401 means "sign in again".

Only GitHub users listed in `REPOREAD_ALLOWED_GITHUB_USER_IDS` can use the server (`AllowedUsers`). Anyone else's sign-in fails while GitHub's answer is checked, before a RepoRead user or their GitHub token is stored, and ends at `/auth/failed`; a session of a user later removed from the list is 401.

Only SHA-256 hashes of codes and session tokens are stored, and expired ones are deleted whenever a new code or session is issued. The GitHub user token and its refresh token are stored in `github_tokens`, each encrypted with AES-256-GCM under the key in `REPOREAD_GITHUB_TOKEN_KEY_FILE` and bound to its GitHub user (`GitHubTokens`), and are never returned. GitHub App user tokens last 8 hours: before a GitHub-backed operation, a token expiring within a minute is renewed with one call to GitHub's token endpoint (serialized by the credential lifecycle gate, because GitHub replaces the refresh token on each use). If GitHub refuses the refresh token (`bad_refresh_token`, `invalid_grant`), the stored token is dropped and the operation returns 401 `SIGN_IN_REQUIRED`; GitHub answers that with HTTP 200 and an error body, which is read as the error it is. Any other refusal, such as `incorrect_client_credentials` from a wrong client secret, and no answer from GitHub (network failure, timeout, 429, 5xx) return 503 `GITHUB_UNAVAILABLE` and keep the token, so the next operation tries again. Restarts and deploys keep them. A stored token the key cannot decrypt (the key file was replaced, or the wrong file named) reads as no token, and that user signs in again, which overwrites it. It is not deleted, so naming the right key file again restores every sign-in.

`GitHubTokenLifecycle` owns one process-wide lifecycle gate shared by the whole OAuth callback (before token exchange),
renewal, app-code exchange, session revocation and account deletion. The callback's user is unknown before exchange;
a single gate avoids another lock registry and fits the one-process deployment (ADR-14). It does not serialize whole
repository reads. Lifecycle admission precedes database transactions; session-changing requests recheck their bearer
session after waiting. Last-device sign-out invalidates older unexchanged app codes in the same transaction as session
removal, so a callback ordered before sign-out cannot later issue a session whose GitHub credential was removed.
A callback ordered after removal performs a fresh exchange. Account deletion holds admission through commit and
remote revocation; a failed database transaction never revokes the grant. No additional GitHub calls or retries.

### Database runtime deadlines

`application.properties` owns fixed deadlines: **10 s connection login**, **15 s per JdbcTemplate/JdbcClient statement
(including row-lock waits)** and **20 s per established socket read**. The latter two are approved safety ceilings,
not measured optimal values. The socket ceiling allows the shorter statement cancellation to answer. These are phase
limits, not a whole-request deadline; pool admission and multiple statements remain separate waits. No new environment
knobs, database-server changes or retries are involved.

`DatabaseRuntimeTest` uses the configured application client and real PostgreSQL: long-query and lock cancellation
roll back earlier writes, and the next transaction works. A raw JDBC read deliberately bypasses the shorter template
cap to verify the actual socket deadline closes the connection and a subsequent operation works. SQL failures propagate
to Spring's existing server-error handling, not an item-refusal envelope: the app keeps uncertain work pending. There
is no alternate database/fallback, because it could not safely acknowledge that work. Programmer and integrity failures
are not caught as transient failures.

### Published API spec

`openapi.json` is the API the app's wire models are generated from. `OpenApiSpecTest` derives it from the `/api/**` controllers and records with springdoc (a test dependency; production serves no spec) and fails when the committed file differs, rewriting it: run it after changing a wire record, then commit the file with the change. Response properties are required because the application mapper writes every record component. Inputs explicitly declare permitted omission with `@Schema(requiredMode = NOT_REQUIRED)`; nullability comes from JSpecify `@Nullable`. Reading anchors have distinct request/response records because an input prefix may be omitted while its response key must be present. Controller metadata declares annotation creation's 200/201 results and binary `image/*` responses; it does not change runtime routing or validation. Schemas are named with their enclosing types (`LibrarySnapshot`). The same test serializes a sample of every published record with the application mapper and checks it against its schema. `src/test/resources/api-contract/item-refusals.json` lists the item refusals the app may treat as final; `ItemRefusalsTest` checks each pair against `ApiFailure.Code`.

```sh
./gradlew :backend:test --tests '*OpenApiSpecTest' --no-daemon
```

Full build, device and live verification evidence: [API contract PR #70](https://github.com/jaxwong/reporead/pull/70).

## API

| Route | GitHub calls (ceiling) | Notes |
| --- | --- | --- |
| `GET /api/auth/me` | 0 | `{id, githubUserId, login}` |
| `POST /api/app-logs` `{lines: [{at, appVersion, level, message}]}` | 0 | 204. Writes each device-log line to the server's log under `DeviceLogIngestController` (`observability`) and stores nothing. A batch is at most one request body; 400 `INVALID_REQUEST` for an empty batch, a missing field, a version other than 1–64 letters, digits, `.`, `+` or `-`, or a level other than `INFO`, `WARN` or `ERROR` |
| `GET /api/library` | 0 | Every list the phone saves, from one read-only `REPEATABLE READ` snapshot (ADR-08), in one request and a fixed 6 queries however many repositories; an answer over 8 MiB is refused whole (422 `LIBRARY_TOO_LARGE`). `repositories`: this user's connections, each with its sync checkpoint, document count, and `documents`: every Markdown document of the connection, each with `contentChangedAt` (see Sync semantics) and `deleted` when the latest complete refresh did not have it, the one place a client learns a note's title, path, current version and removal (the other lists name only the document id). `readingStates`: most recently read first, with the version last read. `bookmarks`: a note that left the repository keeps its bookmark. `notebook`: the complete highlight/card projection, the durable review log, and the server-owned `sessionLimit` (40) |
| `GET /api/repositories/available` | 1 + installations, at most 11 | Repositories GitHub says this user and the App can both access |
| `POST /api/repositories/{githubRepositoryId}/connect` `{installationId}` | 1 | GitHub re-verifies eligibility. Connecting again returns the same connection, with its data, under GitHub's current name and default branch: the app's **Update** after either is renamed on GitHub, whose redirects for the old name RepoRead does not follow (409 `REPOSITORY_RENAMED`) |
| `POST /api/repositories/{id}/sync` | 2 (branch, recursive tree) + at most 8 blob reads (moved-and-edited notes) | Publishes a complete snapshot of the default branch |
| `GET /api/repositories/{id}/stored-data` | 0 | What disconnecting would delete: counts of documents, reading states, bookmarks, highlights, and cards |
| `DELETE /api/repositories/{id}` | 0 | Disconnects: deletes the connection and everything stored for it (documents, reading states, bookmarks, highlights, cards and their review logs) in one transaction; returns the deleted document ids and counts; 404 once already disconnected. GitHub and the App installation are unchanged |
| `DELETE /api/account` | at most 2 (renew, revoke) | Deletes every connection and its data, the annotation mutation records, sign-in codes, sessions, the user, and the stored GitHub token in one transaction; once that has committed, revokes RepoRead's GitHub authorization as sign-out does (with the token read before the deletion; a failed revocation is logged and the account is still deleted) |
| `GET /api/documents/{id}/content` | 1 (raw blob at the stored SHA) | Sanitized reader HTML, canonical text, `renderFormat`, `sourceBlobSha` and `snapshotCommitSha`; not cached on the server |
| `GET /api/documents/{id}/image?path=&commitSha=` | 1 (file at the saved page's explicit commit) | Both parameters required; normalized image path and lowercase Git SHA-1. No latest-snapshot fallback. Access is per repository, not per reference; see below |
| `PUT /api/documents/{id}/reading-state` | 0 | `{lastReadBlobSha, progressPercent, anchor:{headingPath, textPrefix, blockIndex}, lastReadAt}`; last write wins by `lastReadAt` |
| `PUT` / `DELETE /api/documents/{id}/bookmark` | 0 | No body: a bookmark is of the note, whatever its version; both idempotent |
| `GET /api/documents/{id}/annotations` | 0 when every highlight is resolved against the current version; else 1 (that version) | This user's highlights and cards: original `anchor`, current `location`, `status` (`ANCHORED`, `REANCHORED`, `ORPHANED`) for `resolvedBlobSha`, and the creating `mutationId` |
| `POST /api/documents/{id}/annotations` | 1 (the selected version's blob); 0 on replay | `{mutationId, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}, note?, type?, question?}`; 201 created, 200 replay |
| `POST /api/annotations/{id}/reviews` | 0 | `{mutationId, grade:0..5, reviewedAt, blobSha}`; idempotent; any version of the card's note is recorded, because the phone decides whether that version still asks the card's question |
| `PATCH /api/annotations/{id}` | 0 | `{note, expectedVersion}`; 409 `ANNOTATION_CONFLICT` if another edit landed first |
| `DELETE /api/annotations/{id}?expectedVersion=` | 0 | 409 on a stale version; 400 `INVALID_ANNOTATION` without a version from 1 |
| `POST /api/annotations/{id}/reattach` | 1 (the selected version's blob) | `{expectedVersion, anchor:{sourceBlobSha, blockId, startOffset, endOffset, exactText}}`; places the highlight on the user's selection, `REANCHORED`; 409 on a stale version |

**Saving every note on the phone** uses `GET /api/documents/{id}/content` once per note whose saved copy is missing or outdated, one request at a time; there is no batch route. One run therefore makes at most one GitHub call per such note, bounded by the repository's Markdown document count (server limit 5,000), and none for up-to-date saved pages. Freshness includes the repository checkpoint, not just the Markdown blob: an image-only edit must refresh the page too. This deliberately also refetches unchanged pages after unrelated repository commits rather than adding another change index. A note the server refuses to render (`UNSUPPORTED_CONTENT`) is reported and skipped without replacing an older saved copy; every run asks for its unsupported current version again. The phone owns which notes it lacks; the server owns the per-request ceiling and the document limit. A first run on a 654-note repository makes 654 calls, within GitHub's documented 5,000 requests per hour for user tokens (not measured for this App's token).

Every GitHub call uses the user's token, 15-second connect/read timeouts, no redirects (a redirect of a call naming a repository follows a rename and is 409 `REPOSITORY_RENAMED`; of a listing, `GITHUB_INVALID_RESPONSE`), no retries, no pagination, and no installation-token fallback. The ceilings in the table exclude the token renewal (at most one per operation, see the sign-in contract). A failed call ends the operation. Failures are `{code, message}`, including requests a route cannot read (malformed JSON, a missing parameter, a mistyped path value: 400 `INVALID_REQUEST`). A message names no client's screens or controls; each client words codes for its own users. A response the server itself could not read is never one of those: it is a 500. `ApiFailure.Code` lists every code with the one HTTP status it is sent with; `NOT_FOUND` does not tell an absent id from another user's. Upstream error bodies are not exposed. A route, method or body type the server does not have (404, 405, 415) is answered with Spring Boot's own error body, which has no `code`, deliberately: a missing route is not an item refusal (`ApiErrorsTest`). The app's `Sync.refusesItem()` authorizes item rejection only for its known item-refusal status/code pairs, not a status set or a code prefix. Unknown codes, known codes at the wrong status, malformed envelopes, oversized responses and 409 `REPOSITORY_RENAMED` cannot authorize dropping pending work. Shared test-only `api-contract/item-refusals.json` checks those cases against `ApiFailure.Code.status` and the client's decision; it is not a second production error registry.

Consumed nullable response fields are **present with explicit null**, not omitted: available connection ids, repository checkpoints, document change times, annotation optional fields and reading anchor prefixes. `OpenApiSpecTest` checks this against the configured application mapper. Android requires those keys, rejects scalar coercion and unrepresentable timestamps, and tolerates unknown extra fields. Additive fields are compatible; a new required field, enum value or changed meaning is not automatically compatible. Optional input fields may be omitted or explicitly null; generated requests omit null defaults. Reading-state tests verify both prefix forms still return a present nullable key. `DELETE /api/account` confirms with 200 JSON; only session revocation, bookmark removal and annotation deletion use 204. See [ADR-26](../docs/adrs.md#adr-26--one-owner-for-the-apps-side-of-the-http-contract).

Every connection, document, and content request is scoped to the signed-in user; another user's ids return 404 without a GitHub call.

Writes that add or change a document's rows (annotation creation, edits, reattachments, card confirmations and grades, reading-state saves, bookmarks) share the connection's lock and read their result back under it, so a concurrent sync or disconnect is waited for; a write that arrives during a disconnect then finds nothing (404) instead of breaking it. Account deletion locks the user first, and `connect` takes the same lock before adding its connection, so a connection being added at the same time is either deleted with the rest or, arriving after, refused with 401 `SIGN_IN_REQUIRED`. A re-anchoring computed before the user edits, reattaches, or confirms an annotation does not apply over that change.

### Observability

Each request has an id the server makes (`RequestLoggingFilter`): every line logged while it runs carries it in brackets after the thread name, the response returns it in `X-Request-Id`, and one `Request;` line records its method, raw request target, status and duration, including requests Spring Security refuses. The target includes query values, which can name a private note, except that the full query is redacted on the GitHub OAuth callback and the app-code landing page so their one-time `code` and `state` values never enter the journal. That diagnostic visibility is an accepted trade-off for this single-user system, so keep the journal private. One that fails unhandled logs `Request failed unhandled` at ERROR with its root exception's type; Tomcat's stack trace follows on the same thread, without the id. The app logs the id with every failed answer (`Backend refused; … requestId=…`).

The device's own lines reach this log too, so the phone's are read here without adb. `DeviceLog` stores them on the phone and the app sends them (`POST /api/app-logs`) when it starts and when it leaves the screen, adding Android's record of why its processes ended (`Process exited; … reason=ANR`, `CRASH_NATIVE`, `LOW_MEMORY`, …), any crash (`Crash; thread=…` with its classes, messages and frames), and the reader page's console errors and warnings (`Page console; level=… source=… line=… message=…`). Lines too frequent to keep (`Reading position saved` at every scroll stop, code copies, study-view toggles) go to logcat only. Each arrives as `Device log line; userId=… version=<the version that logged it> at=<the phone's time> <line>` under the `DeviceLogIngestController` logger, after the fact and in batches, so the server's own time on those lines is when they arrived. They are kept apart by logger name; follow one failure across both with its request id:

```sh
sudo journalctl -u reporead-backend -o cat | awk '/ com\.reporead\.observability\.DeviceLogIngestController /{a=1;print;next} /^\t/{if(a)print;next} {a=0}'   # device lines
sudo journalctl -u reporead-backend -o cat | awk '/ com\.reporead\.observability\.DeviceLogIngestController /{a=1;next} /^\t/{if(!a)print;next} {a=0;print}'  # server lines
sudo journalctl -u reporead-backend | grep <requestId>                                                                                 # one failure, both sides
```

The server's own lines name ids, SHAs, counts and outcomes; GitHub HTTP and repository-sync logs omit repository paths, and oversized-response errors omit request paths, because they can name private notes; never tokens, authorization headers, note text, quotes, or annotation notes. Device log lines are the exception: exception messages and page console output can quote a note, and are logged as sent (ADR-21, 2026-10-21). Metrics (Micrometer, Spring Boot Actuator) are served only on the management port `127.0.0.1:8082`, which exposes `health`, `metrics` and `info` and nothing else; `adb reverse` forwards only 8081, so the phone cannot reach it. Tags never carry paths or content. `info` names the Git commit the running jar was built from (`build.commit`, from `springBoot.buildInfo` in `backend/build.gradle.kts`), so a deploy can be checked on the host.

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
| Library answer | 8 MiB of JSON, measured after serializing (`LibrarySnapshotReader.MAX_RESPONSE_BYTES`); the app's build reads this line as its own ceiling for the answer | 422 `LIBRARY_TOO_LARGE`, nothing of the library sent |
| Note source | 1 MiB UTF-8, 4,096 blocks, 16 diagrams of 20,000 UTF-16 units | 422 `UNSUPPORTED_CONTENT` |
| Saved page | 1 MiB UTF-8 across HTML, canonical search block ids/text, path, title, blob SHA and image snapshot SHA (`MarkdownRenderer.MAX_PAGE_BYTES`); `DocumentController.Content` is the sole complete-page byte check after serialization (not a peak-heap bound) | 422 `UNSUPPORTED_CONTENT`; no partial content sent |
| Mermaid edges | 200 per diagram, passed to the reader as `data-max-edges` | Enforced by Mermaid on the phone: that diagram shows as an error with its source; the note itself renders (200) |
| Heading paths | Each heading in a block's path at most 500 UTF-16 units; a longer heading (for example a long paragraph directly followed by `---`) is cut and ends in `…`. The heading's own block text is not cut | None: presentation only; reading anchors have no heading-length limit |
| Repository images | 64 per note; 5 MiB each | Extra images render as `[Image limit reached]`; larger images 422 `UNSUPPORTED_CONTENT` |
| Annotation | selection, note, and card question at most 10,000 UTF-16 units each; one block per selection; a selection, note or question with NUL or an unpaired surrogate (a selection that splits a character), which PostgreSQL cannot store exactly | 400 `INVALID_ANNOTATION` |
| Highlight/card count | 500 combined per document and 5,000 per user, excluding bookmarks; `Annotations` checks both atomically after replay detection. Existing rows are not pruned; exact replays, edits and deletions stay available | 422 `ANNOTATION_LIMIT`; no insertion or mutation claim retained. The capped creation blocks later creations/their grades and costs one GitHub blob read each sync until existing annotations are deleted to free capacity |
| Review | grades 0–5; client UUID; millisecond timestamp clamped to [1970, server now], original retained for replay checks | 400 `INVALID_REVIEW` for malformed input |
| Review session | 40 cards, reported as `sessionLimit` in the notebook; the phone has no limit of its own and starts no session without it | Applied where sessions are built, on the phone, because scheduling runs there so review works offline (ADR-13). Grades are not refused for it: refusing a grade can only discard a review already done |
| Reading state | Blob SHA, progress 0–100, timestamp and a typed anchor; anchor text has no NUL or unpaired surrogate. No anchor-size limit beyond the request body; `lastReadAt` is clamped to [1970, server now] | 400 `INVALID_REQUEST` for malformed input; 404 for a note not owned by the user |

Clients cannot change these ceilings. The saved-page ceiling was checked against real Room reads on the Pixel 8a, Android 14: complete string payloads of 1 MiB and 2 MiB minus 4 KiB read successfully; 2 MiB failed with `SQLiteBlobTooBigException`. The 1 MiB ceiling leaves space for row overhead, and even sixfold JSON escaping stays below the app's existing 8 MiB content-response limit. Other limits remain chosen ceilings, not measured ones. The renderer still parses the bounded source into a DOM; the output ceiling is not a claim that total render heap usage is 1 MiB.

### Sync semantics

Sync reads the default branch's commit, then its full recursive tree. Documents are regular-file blobs ending in `.md` (any case); symlinks and submodules are skipped. A truncated or malformed tree fails without changes and is never evidence of deletion. Only after complete validation does one transaction upsert documents by path, mark paths absent from the complete tree as deleted (rows are kept), and advance the connection's commit checkpoint. Repeating a sync is idempotent; concurrent syncs of one connection are serialized by a row lock. A sync that fetched its branch before another sync published a different commit, or whose default branch connecting again changed meanwhile, changes nothing and fails with 409 `SYNC_SUPERSEDED`, so an older snapshot, or one of a branch no longer followed, never replaces the current one; refreshing again picks up the latest. A document whose path left the snapshot keeps its id at a path that has never had a document when both share a blob SHA unique on each side (a path-only move); identical-content duplicates are never merged, and a path that once had a document resumes it. A vanished document carrying the user's data also keeps its id at a new path whose content is alike enough (measured thresholds in [ADR-07](../docs/adrs.md#adr-07--document-identity-from-complete-snapshots)), checked only when vanished documents plus new paths number at most 8. Because every sync is a complete snapshot, a branch rewind is reconciled like any other: paths absent from it are marked deleted and return to their documents if they reappear.

Each sync also records the snapshot's image file paths (`repository_images`), replaced every time, for resolving Obsidian embeds.

`contentChangedAt` is when a sync found the note new, with a different blob, or back after being deleted (a path-only move is not a change). It is null for notes from a connection's first sync and for notes RepoRead has not seen change since change times were added (Flyway V5): it is when RepoRead noticed, not the commit time.

### Images

The renderer resolves relative and root-relative Markdown images against the note's directory, as GitHub does, and rewrites them to the same-origin path `/repo-image/<repository path>`. The content boundary captures the document/checkpoint and image index in one short read-only `REPEATABLE READ` transaction, fetches the exact Markdown blob outside it, binds embeds in the DOM, then serializes once; `DocumentController.Content` checks the complete saved-page bytes before sending any content. The Android reader serves that path from its snapshot-keyed offline cache or from `GET /api/documents/{id}/image?path=…&commitSha=…`, using the displayed page's `snapshotCommitSha` and attaching the bearer token in native code; the page's JavaScript never sees a credential. Only normalized repository paths ending in png, jpg/jpeg, gif, webp, or svg are served (`nosniff`). Remote (`https:`, `//`) images, references escaping the repository, and other file types render as visible `[… blocked]`/`[Unsupported image]` text.

### Obsidian links and embeds

The renderer turns `[[note]]`, `[[note|alias]]`, `[[note#Heading]]` (block references `#^id` open the note), same-note `[[#Heading]]`, and relative links to `.md` files into `/note-link?target=…|path=…&heading=…` links that the app intercepts; code is never linked. `![[image.png]]` (optionally `|300` or `|300x200`) binds to a resolved `/repo-image/<repository path>` in the captured content snapshot; `![[note]]` becomes a link. The source text stays in the page and only the brackets and targets are hidden (`.wl-hidden`), so canonical block text — and every highlight anchored in it — is unchanged. An embed name resolves against that captured snapshot's image files: a name with a folder must match the end of a path, a bare name a file name (case-insensitively); several matches resolve only if exactly one is in the note's folder. Missing or still-ambiguous embeds become visible unavailable labels without changing canonical text. Image fetching never resolves names or consults the latest image index. At most 64 image references are accepted per page; content makes one blob call, and each uncached image request makes one image-file call (each operation may separately renew credentials once). Footnotes (`text[^label]` and `[^label]: definition` paragraphs, as Obsidian writes them) are marked without a parser extension, which would split and move definitions and so change blocks of unchanged blobs: a paragraph starting with `[^label]:` defines footnotes, each later marker after whitespace starting the next (consecutive definitions are one CommonMark paragraph); references to defined labels outside code and links become `sup.fn-ref`; both keep their source text hidden and carry `data-footnote` (label) and `data-label` (number, in definition order). The content response's `renderFormat` (`MarkdownRenderer.FORMAT`, now 4: presentation fixes and complete-page bound) tells the app when a saved page predates these features.

### Reading state and bookmarks

Only the reader writes reading state, recording the blob SHA actually displayed; repository refreshes never change it. The controller clamps `lastReadAt` into [1970, server now] before last-write-wins storage. A write older than or equal to the stored time is ignored and the current state is returned, so replaying a stale offline save cannot overwrite newer progress. Anchor strings are stored exactly, with no heading-count/length, prefix-length or block-index bounds beyond their types and the request-body limit; NUL and unpaired surrogates are malformed, not sanitized. Each save makes zero GitHub calls. Deleting a document upstream keeps its reading state and bookmarks. Bookmarks live in the `annotations` table as type `BOOKMARK` (one per user and document).

### Annotations

A highlight (type `HIGHLIGHT`, with an optional note) is anchored to one source version using the [text contract](../docs/adrs.md#adr-05--anchors-are-block-ids-and-utf-16-offsets-into-canonical-text-of-one-version): block id, UTF-16 offsets into that block's canonical text, and the exact text. On creation the server fetches that exact blob, renders it with the same renderer as the reader, and rejects a selection that does not name real text in it (422 `INVALID_ANCHOR`). It derives the 32-character prefix/suffix context and heading path itself. Source Markdown is never written.

Creation is idempotent per client `mutationId` (a UUID): the first request records the mutation, annotation, and anchor in one transaction. Replaying the same request returns the original annotation (200) without a GitHub call — including after a lost acknowledgement or concurrently with the original. Reusing the id for different content is 409 `MUTATION_ID_REUSED`; replaying after the annotation was deleted is 410 `ANNOTATION_DELETED`, so a late replay cannot resurrect it. A failure before commit stores nothing, and the same mutation can be retried. Note edits and deletion use optimistic versions and never silently overwrite.

Capacity admission serializes new highlights/cards across the user's repositories on the user row before taking the shared connection lock. It makes no GitHub call under either lock; a new creation still fetches at most one blob before admission. The count ceilings do not guarantee the separate 8 MiB library answer: large annotation texts and the unbounded review log can still exceed it.

**Re-anchoring.** The original anchor never changes. Each highlight also has a current `location` (where it was made, re-anchored, or reattached) and a `status` for `resolvedBlobSha`. Listing a document's highlights resolves any that were resolved against an older version, using `Anchoring`: the location's block unchanged anywhere, then unchanged position, then the exact quote singled out by context and heading, then a bounded fuzzy match. Anything weaker is `ORPHANED`, keeping its last location so the next version is resolved from there; the user can reattach it. Re-anchoring does not change the user-edit `version`. Thresholds and their measurement on a real notes repository are in [ADR-06](../docs/adrs.md#adr-06--re-anchor-only-on-measured-evidence-otherwise-orphan). A deleted document's highlights are listed as last resolved, without GitHub calls.

The server does not yet check that `sourceBlobSha` is a version of this particular document, only that it is a blob in the document's repository that renders to the selected text.

### Review cards (P4)

`CARD` is an anchored annotation with a required, non-blank `question` (10,000 UTF-16 units maximum); `HIGHLIGHT` remains the default when `type` is omitted. Existing highlight creation fingerprints are unchanged so old mutation ids still replay. Cards use the same verification, original anchor, current location, optimistic edit/delete, and orphan/reattach path as highlights. V7 adds the type, question, `checked_blob_sha` (dropped by V16), and `review_log`; no source Markdown is changed and no note bodies are retained.

The library's `notebook` (`GET /api/library`) is read in the library's one repeatable database snapshot: this user's anchored annotations, review log and `sessionLimit=40`. It does **not** fetch versions or re-anchor: opening a note uses the existing annotation listing to resolve it. A card whose checked answer SHA differs from the current document SHA cannot be graded, even before re-anchoring has run. Any re-anchoring or reattachment clears confirmation. `POST .../check` confirms only a non-orphaned location resolved against the current, non-deleted document and increments the optimistic version. Conservatively, **every note-version change requires checking its cards**, including edits outside their answer section; this also covers changed-section uncertainty without fetching extra versions. Confirmation preserves review history.

`POST .../reviews` persists one immutable grade, timestamp and verified SHA per user/client UUID. Repeating an identical mutation returns the same log entry; different content is 409 `MUTATION_ID_REUSED`. A committed replay still succeeds after the note changes. A new grade of any version is recorded with that version: the phone grades a card only while its saved current bank still asks the card's Canonical line, which the server cannot read ([ADR-13](../docs/adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling)). Cards are never confirmed. Grades serialize with repository refresh/disconnect via the existing connection lock, then with annotation changes. Deleting a card cascades its review log; a late submission is 404 and cannot recreate a deleted card. Disconnect counts cards and deletes them/logs; account deletion also removes mutation records. These routes have **no alternate strategy or retry** on failure.

**Review time and replay.** A new grade's `reviewedAt` is clamped to [1970, server now] at millisecond precision; clock skew alone never refuses a grade. Acknowledgements and the notebook return that stored scheduling time. V15 adds nullable `review_log.client_reviewed_at`, holding the original client instant as text so even an instant outside PostgreSQL's timestamp range can be accepted and clamped. Replay comparison uses that original instant, not a new server time: a lost acknowledgement can be replayed unchanged, while reusing the UUID with a different original time still gives `MUTATION_ID_REUSED`. Existing rows are not rewritten: a null client time means their unchanged `reviewed_at` was the original time. The nullable addition also permits the previous release's inserts; the upgrade and old insert are covered by `MigrationTest`.

**Known limit (recorded, not paged).** `GET /api/library` returns every annotation and every grade in one response with the other lists, and the phone reads at most 8 MiB of JSON per response. At about 200 bytes per grade that is roughly 40,000 grades (about 3 years at 40 a day). Past it, the server refuses the library (422 `LIBRARY_TOO_LARGE`, at `LibrarySnapshotReader.MAX_RESPONSE_BYTES`, which the app's build reads as its own ceiling) and no list loads (before 2026-10-18 the notebook was read on its own, last, so the other lists still loaded); pending changes are still sent, since a sync sends before it reads. Paging is deferred until the log approaches the limit.

The server stores no due dates, scores derived from history, or scheduling state. The phone alone computes each card's step on its gap ladder from the log, retaining pending grades until acknowledgement. In a phone session, the first grade of each card is saved; a card graded Again comes back later in the same session, unsaved, until answered right, so the server receives one grade per card per session. A saved grade below 3 restarts the card's interval at one day ([ADR-13](../docs/adrs.md#adr-13--anchored-cards-server-review-log-phone-only-scheduling)). No AI or FSRS dependency is included; the phone sends grades with its other pending changes (ADR-08).

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
# Every note rendered to <dir>; run before and after a renderer change and diff the blocks (ADR-18). The app's InterviewBankCorpus reads <dir>/pages.
REPOREAD_MEASURE_CORPUS=<clone> REPOREAD_MEASURE_OUT=<dir> ./gradlew :backend:test --tests '*RenderedCorpus' --rerun --no-daemon
```

Dependencies: Spring Boot web MVC, Security OAuth2 Client, JDBC, Actuator, and Flyway; the PostgreSQL driver; CommonMark with the GFM tables, strikethrough and task-list extensions; jsoup (its version is the root `jsoupVersion`, shared with the app) for sanitizing and canonical block text; JUnit, Spring test support, and Testcontainers. Mermaid and code highlighting are client-side display only.
