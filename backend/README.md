# Backend Stage 0 boundaries

Stage 0 contains the real backend's Java Markdown parser/sanitizer, Spring Boot GitHub user sign-in, and a user-scoped repository eligibility check. There is no database or Android session handoff yet. The command-line exporter is a development proof tool, not a substitute for authenticated backend delivery.

Requirements: JDK 25 and the repository's Gradle wrapper.

## Local GitHub user sign-in

Spring Boot/Security owns OAuth state, PKCE, code exchange, user identity, the HttpOnly/SameSite=Lax session cookie, and server-side authorized clients. The callback is derived from the server's address and port; Stage 0 refuses a non-loopback bind. No broad OAuth scopes are requested: GitHub App user tokens are constrained by both App permissions and user access.

Save a single client-secret token in a file **outside any Git repository**, with permissions **0600**. The App PEM is not the OAuth secret. Never put either credential in Android assets, source control, a browser URL, or logs. No fallback credentials exist; missing/malformed/unsafe files fail startup.

```sh
DEBUG=false \
REPOREAD_GITHUB_CLIENT_ID='<your-app-client-id>' \
REPOREAD_GITHUB_CLIENT_SECRET_FILE='/absolute/private/path/client-secret' \
./gradlew :backend:bootRun --no-daemon
```

Default local URLs:

- Register this callback in the GitHub App settings: `http://127.0.0.1:8081/login/oauth2/code/github`
- Begin sign-in **on the Mac running the server**: `http://127.0.0.1:8081/`
- Successful sign-in redirects to `GET /api/auth/me`, returning only `{id, login}`.
- Unauthenticated API calls return 401. An invalid/failed callback redirects to `/auth/failed` (401); no partial authentication result is published. Start a fresh sign-in explicitly; there is no automatic retry or alternate authentication strategy.

The development port is 8081 because an unrelated Docker service already owns 8080; that service is left untouched. `application.properties` owns the port, and the OAuth registration derives its callback from it rather than accepting a caller's Host header.

The explicit `DEBUG=false` overrides this Mac's inherited `DEBUG=release`, which otherwise enables verbose Spring framework logging. Do not enable HTTP/security debug logging with real OAuth credentials.

Login redirects make zero backend GitHub requests. The callback makes at most **two application-level GitHub requests** (code exchange, then user identity), with 15-second connect/read timeouts, no HTTP redirects, a 1 MiB response ceiling per request, and no application retry. OAuth exchange/errors are handled by Spring Security; logs contain endpoint/status/size or failure type, never tokens or response bodies. Fake identities/credentials exist only in test sources and do not prove real GitHub authorization.

Sessions and authorized clients are in memory and disappear on restart. This is a loopback-only development proof, not production session storage; PostgreSQL, encryption-at-rest, secure HTTPS cookies, Android login, and refresh-token handling remain later work. Installation-token access alone is not evidence of signed-in user eligibility.

## Check signed-in repository eligibility

After signing in, open `GET /api/auth/installations/{installationId}/repositories` in the **same Mac browser**. For the supplied development installation, use:

`http://127.0.0.1:8081/api/auth/installations/166757310/repositories`

Look for `fullName: "jaxwong/zw_obsidian"` in `repositories`. The response contains the installation ID and repository metadata (`id`, `fullName`, `privateRepository`), never credentials or note contents. An empty complete list is a real empty result, not a fixture. This check does not persist a connection or open a note.

The endpoint loads only the current user's authorized client from the same Spring-owned service used by login. It makes **one** [GitHub user-token repository request](https://docs.github.com/en/rest/apps/installations?apiVersion=2026-03-10#list-repositories-accessible-to-the-user-access-token), which restricts results to repositories accessible to both the App installation and the user. The shared HTTP boundary enforces 15-second connect/read timeouts, no redirects, and a 1 MiB response cap. The server requests at most 100 repositories; oversized or incomplete lists fail explicitly without fetching another page or publishing partial results. Caller-supplied pagination/limit parameters do not change this bound.

Missing, expired, or rejected user tokens return 401 (`SIGN_IN_REQUIRED`); sign in again explicitly. Inaccessible installations return 403 (`GITHUB_ACCESS_DENIED`), transport/timeouts/rate limits/upstream server failures return 503 (`GITHUB_UNAVAILABLE`), and malformed/incomplete/over-limit responses return 502. There is no retry, token refresh, installation-token fallback, or alternate fetch strategy. Restarting the server clears the development session, so repeat sign-in before checking eligibility.

## Verify and export Markdown

```sh
./gradlew :backend:test --no-daemon
./gradlew :backend:run --args='--help' --no-daemon
```

Export a UTF-8 Markdown file outside the repository:

```sh
./gradlew :backend:run --args='/private/tmp/reporead-note.md <git-blob-sha> <source-label> /private/tmp/reporead-note.html' --no-daemon
```

The exporter requires the SHA of the exact Markdown bytes and verifies it using Git's blob hashing. It does not fetch GitHub, log note content, or accept a caller-selected size cap. Its HTML is for the isolated Stage 0 reader only. Real private note inputs and exports must stay outside tracked files.

Dependencies: Spring Boot web MVC and Security OAuth2 Client provide the specified backend and standard authentication flow; CommonMark and its GFM extensions parse Markdown; jsoup sanitizes output and establishes canonical block text; JUnit and Spring's test support verify behavior. Mermaid and code highlighting are client-side display only and must preserve the exported text.
