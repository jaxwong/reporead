# RepoRead — Stage 1 verification record

This records evidence for the [build plan](reporead-build-plan.md)'s Stage 1 exit gate. The [backend README](../backend/README.md) owns the API/auth contract and commands; the [Android README](../android/README.md) owns install commands.

## Automated (2026-10-06)

- `./gradlew :backend:test --no-daemon`: exit 0, **84 tests, 0 failures**, against PostgreSQL 17.11 via Testcontainers. Covers app sign-in (PKCE binding, single use, expiry, concurrent exchange, revocation, browser session cannot authenticate `/api`), repository eligibility and connect (unauthorized repository rejected, idempotent connect, per-user isolation, GitHub failure mapping without retries), sync (Markdown filtering, idempotent second run, truncated tree changes nothing, absent paths marked deleted not removed, empty repository, failure after the branch call keeps the previous snapshot, tree ceiling, branch encoding, concurrent syncs), and note content (ownership, deleted, SHA mismatch, oversized/non-UTF-8/over-limit content, vanished blob vs. outage). GitHub is mocked with test-only data.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`: exit 0, 4 folder-derivation tests.
- Local server probes (no GitHub calls): Flyway applied V1 on the compose database; `/app/sign-in` 302, invalid challenge 400, unauthenticated `/api/*` and `/` 401, bogus sign-in code 400.

## Real phone → real backend → real repository (2026-10-06)

Pixel 8a, debug app `com.reporead.android`, `adb reverse tcp:8081 tcp:8081`, backend on the Mac with the compose database.

- **Defect found and fixed:** the first real sign-ins stayed on "Finishing sign-in" although the server log showed sessions issued (17:09:08, 17:09:19). The sign-in effect cancelled its own in-flight exchange; fixed in `6573428`.
- **User-reported:** after the fix, the user signed in, connected `jaxwong/zw_obsidian`, refreshed, and opened `01-api-boundaries-and-contracts`; a screenshot shows the note's prose and a legible Mermaid flowchart in the app.
- **Server log for that session** (17:11): app session issued for user 1; installations listed (1); repositories listed (1, installation `166757310`); repository `1160483465` connected after a second eligibility call; branch `main` → commit `296d34a9d37f2478138e6b03163ecf2402fcfcef`, recursive tree 195,266 bytes / 766 entries → **641 Markdown documents**; note blob `f92434ddd08673a76234168f4becc82f04fdcd6c` read (16,318 bytes) and rendered as 200 blocks and 4 diagrams. Each operation stayed within its documented GitHub-call ceiling.
- **Database after the session:** 1 user; 1 connection to `jaxwong/zw_obsidian` at commit `296d34a9…`; 641 documents, all active.
- **Source integrity:** the backend's GitHub client issues only GET requests (the one other outbound request is Spring Security's OAuth token exchange); the GitHub App has read-only repository permissions.

## Not yet verified on the real system

- A second real GitHub user being unable to read this repository (covered by automated isolation tests only).
- A second real refresh producing no new documents (covered by automated idempotency tests only).
- Truncated discovery and empty repositories against live GitHub (automated only; the real tree was complete).
- Process death or rotation while reading, and backend restart followed by re-sign-in on the phone.

Repository images remain blocked until Stage 2 defines authenticated image delivery.
