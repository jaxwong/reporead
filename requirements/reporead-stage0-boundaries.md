# RepoRead — Stage 0 boundaries and evidence

This records implemented boundaries, not a declaration that Stage 0 is complete. The [build plan](reporead-build-plan.md) owns the exit gate; the [backend setup](../backend/README.md) owns verified startup/export commands.

## Rendering and selection contract

- **Java owns parsing, sanitization, canonical text, heading context, and limits.** CommonMark/GFM parses Markdown; raw HTML is escaped, then jsoup applies an explicit tag/attribute/URL safelist. The exported Git blob SHA must match the exact UTF-8 source bytes.
- **Anchor offsets are not raw Markdown offsets.** Inline formatting contributes its visible text, soft line breaks become spaces, hard breaks become one LF, and code whitespace is preserved. No Unicode normalization is performed.
- Anchorable leaf blocks are paragraphs, headings, code fences, table cells, and list items without nested candidate blocks. Blocks do not overlap. Non-leaf list text and other unsupported structures may not be anchorable; the proof does not claim arbitrary multi-block annotations.
- Block IDs `b0`, `b1`, … are deterministic **within this source version only**, not durable cross-version identities. Every selection carries `sourceBlobSha`, `blockId`, `exactText`, `startOffset`, `endOffset`, `headingPath`, and `offsetUnit: UTF-16`. Offsets use a zero-based, start-inclusive/end-exclusive range in exported canonical block text. A surrogate pair occupies two UTF-16 units.
- JavaScript owns display and DOM-range capture only. It compares `textContent` with the Java-exported canonical text before/after highlighting and validates the selected substring. Broken mapping is an invariant failure, not an approximate anchor. Cross-block selections are explicitly rejected; empty selections produce no anchor.
- Mermaid SVG is display-only; its expandable source remains the anchorable canonical block. Diagram labels are not assigned prose offsets. Mermaid uses strict security, disables HTML labels, and inherits Java's diagram bounds. A diagram error is visible with its original source retained; no alternate renderer is attempted.
- The isolated WebView uses a local HTTPS asset origin, CSP, app-owned bundled scripts/styles, blocked navigation/network/file/content access, and no native JavaScript bridge or credentials. No CDN requests occur.
- **Stage 0 image policy is deliberately limited:** only the labeled app-owned proof SVG is allowed; other images show `Image blocked` text. This proves a local image-display boundary, not authenticated/relative repository image support. Stage 1/2 must implement that feature without substituting fixture images.

### Hard bounds

| Operation | Owner and ceiling | Failure behavior |
| --- | --- | --- |
| Markdown render | Java: 1 MiB UTF-8 source, 4,096 blocks | Reject before publishing an export |
| Diagrams | Java: 16 per note, 20,000 UTF-16 units each; Mermaid: 200 edges | Explicit input rejection or visible diagram failure/source |
| Snapshot read | Development tool: 3 GitHub calls, 15-second timeout each, 4 MiB API response, 1 MiB note | Stop on failure; no alternate fetch; verify blob SHA before writing |
| Sign-in redirect | Spring Security: 0 GitHub backend calls | No authenticated result until callback completes |
| OAuth callback | Backend: at most 2 application-level GitHub calls, 15-second connect/read timeout, 1 MiB response each | Spring Security rejects login; no partial authenticated result, retry, or alternate auth strategy |
| Repository eligibility | Backend: 1 GitHub user-token call, 15-second connect/read timeout, 1 MiB response, complete list of at most 100 repositories | Explicit failure for denied, unavailable, malformed, incomplete, or oversized results; no retry, next page, refresh, or installation-token fallback |

These are Stage 0 constants, not client-selectable limits. No database objects, background jobs, source writes, refresh-token strategy, or live note API are introduced.

## Authentication boundary

Spring Security owns state/PKCE checks, code exchange, user identity, the session, and server-side authorized clients. A successful callback redirects to `/api/auth/me`, which publishes only GitHub ID/login. Unauthenticated API access returns 401; invalid callbacks redirect to the visible `/auth/failed` page.

The local server binds to `127.0.0.1:8081`; Docker already owns 8080 and is untouched. Its registration derives the callback from the server address/port, not a caller's Host header. The App client ID/secret file are required configuration; no production credentials are in source. Secret loading requires a regular, non-symlink, mode-0600 file containing one token, bounded to 4 KiB. The original user-supplied secret file is not edited.

In-memory sessions/authorized clients are **development-only** and disappear on restart. This browser proof is separate from an eventual Android session handoff. App installation-token read access does not prove that a signed-in user may connect a repository.

`GET /api/auth/installations/{installationId}/repositories` now loads the current user's token from the same Spring-owned authorized-client service that login uses. GitHub's user-token endpoint owns repository authorization; only a complete, validated metadata list is returned. No connection or note is persisted. The user confirmed actual browser sign-in and supplied the real repository response on 2026-10-02: installation `166757310` includes private repository `jaxwong/zw_obsidian` (repository ID `1160483465`). The real sign-in and repository-eligibility checks passed based on user-reported browser results, not merely installation-token access or mocks.

## Verification record

Run from the repository root. This Mac uses `GRADLE_USER_HOME=/private/tmp/reporead-build/gradle-home`; Android builds also use `ANDROID_HOME="$HOME/Library/Android/sdk"` and `ANDROID_USER_HOME=/private/tmp/reporead-build/android-user`. Private inputs and exports stay in `/private/tmp/reporead-build`, never tracked or bundled.

### Executed on 2026-10-02

- New login tests before implementation: `./gradlew :backend:test --tests '*GitHubLoginTest' --no-daemon`, exit **1**, missing Spring Boot application (expected failing baseline).
- Java renderer baseline reproduced a doubled hard-break LF; its normalization was corrected. All nine renderer behavior tests then passed.
- `./gradlew :backend:test :backend:bootJar --no-daemon`, exit **0**: **19 tests passed**, `BUILD SUCCESSFUL in 14s`. Includes redirect/state/PKCE, second sign-in, invalid callback, unauthenticated API, test-only identity projection, unsafe/missing/malformed secret files, oversized OAuth response, timeout/no retry, second response, and Markdown/security cases. Test-only credentials/mocked identity do not prove real GitHub OAuth.
- `./gradlew :reader-spike:assembleDebug help --task :backend:bootRun --no-daemon`, exit **0**, `BUILD SUCCESSFUL in 40s`. Java/Kotlin compilation passed; generated assets are now owned by their producer tasks through the AGP Variant API. The build reported an SDK XML version mismatch and an unstripped graphics library; no SDK/dependency upgrade was performed to hide those warnings.
- Snapshot tool, with the explicitly supplied App/installation/repository/note and private output paths: exit **0**, 3 GitHub calls; verified SHA `f92434ddd08673a76234168f4becc82f04fdcd6c`, 16,318 bytes. This proves App installation read access only.
- npm syntax checks/bundle build passed. Audit after the approved Mermaid 11.17.2 change: exit **1**, **one high-severity advisory** remains through lodash-es 4.17.23. Phone installation is paused pending the separate targeted-update approval/audit.
- First `bootRun` failed, exit **1**: the Application plugin still selected `RenderNote`. The main-class owner was corrected, preserving the CLI task separately. The accidental HTTP probe of 8080 found an unrelated Docker listener; it did not verify RepoRead.
- After that correction: `./gradlew :backend:test :backend:bootJar :backend:run --args='--help' --no-daemon`, exit **0**, **19 tests passed**, `BUILD SUCCESSFUL in 20s`. The packaged manifest now identifies `com.reporead.RepoReadApplication`; the separate CLI still prints its real help.
- Corrected `:backend:bootRun` started on loopback 8081 with the supplied private secret file. Live probes observed home 200, unauthenticated API 401, and a GitHub redirect 302 with the correct callback/state/PKCE/HttpOnly cookie. One probe assertion failed because it expected a relative redirect; inspection confirmed Tomcat's equivalent absolute local redirect to `/auth/failed`, followed by a visible 401 failure page. No GitHub HTTP requests occurred during these probes.
- Source/APK-asset secret checks passed; the supplied client secret was not found in source or assets. The real Markdown snapshot is not packaged in the APK. `git diff --check` exited **0** (the current new files are untracked, so this alone is not a complete whitespace check).
- The shell exports `DEBUG=release`, which enabled verbose Spring logging. The server was stopped intentionally (exit 130) before actual OAuth, and its documented startup now overrides that inherited variable with `DEBUG=false`.
- Final startup command: `DEBUG=false REPOREAD_GITHUB_CLIENT_ID=Iv23liNQEj4BJfZShFKe REPOREAD_GITHUB_CLIENT_SECRET_FILE=/private/tmp/reporead-build/github-client-secret GRADLE_USER_HOME=/private/tmp/reporead-build/gradle-home ./gradlew :backend:bootRun --no-daemon`. The server remains running for user verification (no terminal exit code yet). Output: `Tomcat started on port 8081 (http)` and `Started RepoReadApplication in 2.314 seconds`; framework DEBUG logging is absent.
- Final Python `http.client` loopback assertions, exit **0**: `/ HTTP 200`, `/api/auth/me HTTP 401`, `/oauth2/authorization/github HTTP 302`, correct callback/state/PKCE/cookie, invalid callback 302 to the local `/auth/failed`, visible failure page 401, and `LIVE_LOCAL_CALLBACK_CHECKS_PASSED; no GitHub HTTP calls`. Neither the authorization redirect nor cookies/state were printed or followed.
- Final Android rebuild: `./gradlew :reader-spike:assembleDebug --no-daemon` with the Android/Gradle environment above, exit **0**, `BUILD SUCCESSFUL in 35s`; `npm run check` also ran successfully. The same SDK/graphics warnings remain. **No phone installation was performed.**

### Signed-in repository eligibility follow-up (2026-10-02)

- The user confirmed browser sign-in returned GitHub ID/login. The previous server also logged successful token-exchange and user-info HTTP 200 responses, without exposing their bodies. This does not prove user-scoped repository access.
- Before implementing the new route, `DEBUG=false GRADLE_USER_HOME=/private/tmp/reporead-build/gradle-home ./gradlew :backend:test --tests '*repositoryAccessRequiresAServerSideGitHubUserToken' --no-daemon` exited **1**, expected 401 but received 404 (missing route).
- New repository tests initially failed compilation because inbound and outbound test matchers both exported `jsonPath`/`content`. Narrowing only the outbound imports corrected it; assertions were not weakened. A build started before that correction reported the same error.
- `DEBUG=false GRADLE_USER_HOME=/private/tmp/reporead-build/gradle-home ./gradlew :backend:test :backend:bootJar :backend:run --args='--help' --no-daemon` exited **0**, **50 tests passed**, `BUILD SUCCESSFUL in 10s`.
- After explicitly wiring login to the same authorized-client service used by the repository endpoint, the final combined verification was:

```sh
DEBUG=false \
ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_USER_HOME=/private/tmp/reporead-build/android-user \
GRADLE_USER_HOME=/private/tmp/reporead-build/gradle-home \
./gradlew :backend:test :backend:bootJar :backend:run --args='--help' :reader-spike:assembleDebug --no-daemon
```

Exit **0**. Output:

```text
BUILD SUCCESSFUL in 7s
45 actionable tasks: 7 executed, 38 up-to-date
```

Test XML confirmed **50 tests, zero failures/errors**: 5 login, 6 HTTP/secret, 30 repository eligibility, and 9 renderer cases. Repository tests cover separate/concurrent users, missing/expired/rejected tokens, empty lists, second requests, caller-limit rejection, denied access, rate limits/timeouts, no retries/redirects/pagination, malformed/incomplete/oversized responses, and safe metadata projection. These tests use explicit test-only credentials and mocked GitHub responses, not real authorization. The existing SDK XML and JVM class-sharing warnings remain; no dependency upgrade or phone installation was performed.

- The previous server was stopped intentionally (exit **130**) and restarted with the same documented `DEBUG=false` startup command and existing private secret copy. Its replacement remains running (no terminal exit code). Output: `Tomcat started on port 8081 (http)` and `Started RepoReadApplication in 1.401 seconds`. Restart cleared the in-memory development session.
- `python3 /private/tmp/reporead-build/check-repository-boundary.py` exited **0**. This local-only probe did not follow redirects, print credentials/cookies/state, or make external requests. Output:

```text
/ HTTP 200
/api/auth/me HTTP 401
/api/auth/installations/166757310/repositories HTTP 401
/oauth2/authorization/github HTTP 302
/login/oauth2/code/github HTTP 302
/auth/failed HTTP 401
LIVE_LOCAL_REPOSITORY_BOUNDARY_CHECKS_PASSED; no external requests made by this probe
```

- Source checks against the configured secret bytes and trailing-whitespace checks (including untracked source/docs) exited **0**: `SOURCE_SECRET_AND_WHITESPACE_CHECKS_PASSED`. `git diff --check` also exited **0**. At that point the real authenticated repository response still needed user verification.
- The user then supplied this browser response from the repository check, completing the real user-scoped eligibility proof for the selected repository:

```json
{"installationId":166757310,"repositories":[{"id":1160483465,"fullName":"jaxwong/zw_obsidian","privateRepository":true}]}
```

This is user-reported real integration evidence, not a seeded record. It proves eligibility at the time of the request, not a durable connection, note delivery, Android login, or continued access after permission changes.

### Audited reader installation and private-note import (2026-10-02)

The user's `continue` approved the targeted lodash-es update requested immediately beforehand; Mermaid remains 11.17.2. No production configuration, OAuth credentials, schema, or source Markdown was changed.

From `spikes/reader-web`:

```sh
npm audit --json > /private/tmp/reporead-build/reader-audit-before.json
npm view lodash-es version
npm update lodash-es --ignore-scripts --no-audit
npm ci --ignore-scripts && npm audit && npm run check && npm run build -- ../reader-android/build/generated/reader-assets
```

- Baseline audit exited **1** with one high-severity vulnerable package, lodash-es 4.17.23. Registry verification exited **0**, returning `4.18.1`, compatible with dagre-d3-es's existing `^4.17.21` dependency range.
- Targeted update exited **0**: `changed 1 package in 947ms`. Comparing the entire lockfile against its private pre-update copy confirmed that only `node_modules/lodash-es` changed; `package.json`, Mermaid, and every other package entry remained unchanged. No override, new dependency, install script, `npm audit fix`, or unrelated upgrade was used.
- A separate `npm audit` exited **0**, then clean install/check/build exited **0**. Output:

```text
added 116 packages, and audited 117 packages in 2s
found 0 vulnerabilities
```

The explicit audit following that clean install also reported `found 0 vulnerabilities`; both JavaScript syntax checks and the bundled build passed. This records the advisory state at verification time, not a future security guarantee.

From the repository root, with `DEBUG=false` and the Gradle/Android environment documented above:

```sh
./gradlew :backend:run --args='/private/tmp/reporead-build/stage0-note.md f92434ddd08673a76234168f4becc82f04fdcd6c jaxwong/zw_obsidian /private/tmp/reporead-build/reader-proof.html' :backend:test --no-daemon
./gradlew :reader-spike:assembleDebug --no-daemon
```

Export exited **0**, `BUILD SUCCESSFUL in 4s`, with output:

```text
RENDERED blocks=200 diagrams=4 sourceBlobSha=f92434ddd08673a76234168f4becc82f04fdcd6c
```

The backend test task was **UP-TO-DATE**, not a fresh test execution; its prior 50-test pass remains the automated backend evidence. Android rebuild exited **0**, `BUILD SUCCESSFUL in 8s`; the reader bundle was regenerated from the audited lockfile. The existing SDK XML mismatch warning remains; no SDK upgrade was performed.

On the connected Pixel 8a (serial `adb-3C221JEKB12747-x5KL7i._adb-tls-connect._tcp`), the install, private import, and cold-start commands in the [reader README](../spikes/reader-android/README.md#run) each exited **0**. Output included:

```text
Performing Streamed Install
Success
Status: ok
LaunchState: COLD
Activity: com.reporead.readerspike/.ReaderSpikeActivity
Complete
```

The note export's **75,234 bytes** matched the imported `files/reader-proof.html` byte-for-byte, SHA-256 `d3aa6e1255e1142258b5f8d702f6e5346165b8956e3b055faa3715e7c7a50330`. The verified original Markdown remained 16,318 bytes with its expected Git blob SHA. The local HTML export was set to mode 0600. APK-asset checks exited **0**, confirming no private-note export or configured client secret in the assets; only the deliberate synthetic note fixture is packaged. No credentials were imported into Android.

The initial screen capture was black. A successful launch/import is therefore **not** recorded as visual rendering or DOM/touch-selection success; the user was asked to wake/unlock the phone and report **Status**. This is the selected real GitHub snapshot rendered by Java and manually imported into private phone storage, not live authenticated backend note delivery. Screenshot and private snapshot/export files remain outside the repository.

### Real-note renderer status (user-reported, 2026-10-02)

The user reported **Status** from the imported real note:

```json
{"state":"ready","blocks":200,"diagrams":4,"diagramErrors":0,"canonicalText":true}
```

This matches the Java export's 200 blocks and four Mermaid sources. The real-note renderer-status check passed based on the user's phone result: Mermaid completed without reported errors, and Java/DOM canonical text matched. It does not yet prove DOM-range capture, native touch selection, diagram legibility, the separate code/image fixture, or repeat behavior after another cold launch. No code/dependency changes or build rerun were needed to record this observation.

### Real-note DOM-range checks (user screenshots, 2026-10-02)

The two user-supplied screenshots together show the real-note **Run checks** result:

```json
{"checks":["empty selection","200 canonical blocks","cross-block rejection"],"status":{"state":"ready","blocks":200,"diagrams":4,"diagramErrors":0,"canonicalText":true},"touchSelectionVerified":false}
```

All three automated DOM-range checks passed on the Pixel, based on screenshot-backed user evidence. The screenshots also show the real note's prose and heading layout. `touchSelectionVerified: false` is deliberate: the check constructs DOM ranges programmatically and does not test native touch handles. Diagram legibility, the separate code/image/Unicode fixture, finger selection, and repeat cold-launch behavior remain unverified. No code, dependencies, or runtime configuration changed to record this result; no build/tests were rerun.

### Real-note finger-selection check (user screenshots, 2026-10-02)

The user supplied two screenshots showing the native selection handles around `untrusted requests` and this captured anchor:

```json
{"sourceBlobSha":"f92434ddd08673a76234168f4becc82f04fdcd6c","blockId":"b1","exactText":"untrusted requests","startOffset":131,"endOffset":149,"headingPath":["01 — API Boundaries and Contracts"],"offsetUnit":"UTF-16"}
```

An executed Python check parsed the existing private Java HTML export, verified its source SHA against the Markdown Git blob hash, and checked that block `b1`'s UTF-16 range `[131,149)` is exactly `untrusted requests`. Its heading path also matched. Exit **0**, output:

```text
TOUCH_ANCHOR_CHECK_PASSED; b1; UTF-16 [131,149); untrusted requests; source SHA and heading match
```

The real-note **prose finger-selection** check passed based on screenshot-backed user evidence and independent export validation. This does not prove formatted-text, code, table-cell, Unicode, manual cross-block, or repeated cold-launch selection behavior. The automated `Run checks` field remains `touchSelectionVerified: false` by design; manual evidence is recorded here rather than changing that programmatic-test flag. No application code or dependencies changed; no build/tests were rerun.

### Still required before Stage 0 can close

1. Inspect the real note's Markdown layout and diagram legibility; its renderer-status check is user-reported passed.
2. Check the synthetic code/image/table fixture separately and label it as such.
3. Extend actual touch selection to formatted text, code, table cells, Unicode, and a manual cross-block range, then cold-reopen and repeat. Real-note automated DOM checks and one prose finger selection have passed; the old `transaction`/`intro`/2–13 proof remains valid historical evidence, not evidence for these remaining new-renderer cases.

No live backend-to-phone note delivery, PostgreSQL persistence, production sessions, or Stage 1–6 completion is claimed. Only `.gitignore` has been committed (`578d730`). A Stage 0 source commit is deferred because the existing foundation is largely untracked; unrelated files and the user's original-spec deletion have not been staged.
