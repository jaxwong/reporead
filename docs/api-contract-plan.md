# API contract: implementation and verification

Implemented 2026-10-18 against `d3a0bd3`; original implementation `a043217`.
The review branch `codex/api-contract-review` separates producer checks, the atomic Android replacement,
the optional live harness and documentation. No production or test behavior changed during the split.

## Goal and ownership

A malformed or unrelated backend answer must not become saved data, acknowledge pending work or authorize its
permanent rejection. [ADR-26](adrs.md#adr-26--one-owner-for-the-apps-side-of-the-http-contract) records the decision;
[backend/README.md](../backend/README.md#api) and `Api.kt` own the route contract, not another registry here.

| Concern | Owner |
| --- | --- |
| Published meanings, status/code pairs and server limits | Backend records/controllers and `ApiFailure.Code` |
| Named routes, exact success statuses, caps and echoed identity checks | Android `core/network/Api.kt` |
| Consumed wire fields and construction invariants | `core/network/Contract.kt` |
| One JSON policy, strict scalar/time decoding and failure translation | `core/network/WireJson.kt` |
| Typed row mapping; pending-item disposition and acknowledgement | `sync/ServerRows.kt`; `Sync.refusesItem()` and existing Sync/Room operations |
| Recorded-account match; review-to-local-card match | `SignIn`; `Sync`, respectively |

### Enforced boundary

- All 22 app-used routes have named functions; execution is private. Each makes **at most one HTTP request**, with
  no retry, redirect or alternate strategy. Existing composite-operation ceilings and 401/session ownership remain.
- Wire records have no defaults. Nullable keys are required; unknown extra fields are allowed, unknown enum values
  are not. Boolean/Int/Long serializers reject quoted values, fractional integers and overflow, including inside lists
  and nullable fields. Generated serializers own structure; no second validation pass or fallback parser.
- Construction checks protect actual consumers: notebook limit > 0; progress 0..100; reading block index >= 0;
  nonempty UTF-16 passage ranges matching exact text; CARD question nonblank; review grade 0..5; snapshot SHA valid;
  annotation id/version and issued user id positive; access token and failure code nonblank. Server maxima stay server-owned.
- ISO instants must also fit epoch milliseconds. Parse/conversion failures retain their causes and become
  `INVALID_RESPONSE`, not crashes after acknowledgement. This does not copy the server's clock-acceptance window.
- Successful answers must match echoed request ids/versions before returning. A newer last-write-wins reading state
  for the correct document remains valid. Map the complete answer before acknowledgement or Room replacement.
- `Sync` authorizes permanent item refusal only from known status/code pairs. Unknown/malformed envelopes,
  oversized answers and known codes at the wrong status are not authority to discard work. An unreadable envelope
  gets `HTTP_<status>`; incomplete transport stays `BACKEND_UNAVAILABLE` with its I/O cause. No new retry policy.
- Transport logs carry method, safe path/status and exception class, never bodies, credentials or arbitrary queries.

The configured backend mapper emits explicit nulls across every consumed nullable family. Producer tests verify that;
Android keeps `explicitNulls = true`. Requests encode nulls explicitly; the sign-in controller suite verifies
`expectedUserId: null` preserves omission semantics. Account deletion requires **200 JSON**, not 204; session
revocation, bookmark removal and annotation deletion require **204 Unit**.

### Replacement and size

Deleted `ServerJson.kt`, `ServerJsonTest.kt`, the old public raw verbs, getter/contract helpers and HTTP JSON builders.
Every caller moved together; surviving old-test assertions now exercise the new decoder. Moved wire types and existing
pure enums are reused, not duplicated. The separately owned reader bridge retains its `org.json` protocol.

Production Kotlin: **+694 / −456, net +238**; build declarations **+8**. The named additions are complete wire
shapes (310 declaration lines), strict scalar/time rules, exact statuses, correspondence and safe refusal authority—not
a mechanical refactor. There is no new manager, transport, schema engine, runtime fixture mode or storage migration.
Serialization plugin **2.2.10** and JSON runtime **1.9.0** compiled with the existing toolchain; the dependency report
keeps stdlib 2.2.10 and AGP 9.1.1. No existing dependency/toolchain version was upgraded.

### Why these tests remain

`backend/src/test/resources/api-contract/` contains **19 response examples, 8 requests and 1 refusal-pair table**:
28 shared data files, not 28 suites. Actual backend records/configured serialization and controllers verify them;
Android consumes the same files in test source sets only. There is no duplicate Android fixture copy.

| Net new test methods | Purpose |
| --- | --- |
| 10 backend | Producer/request agreement and actual refusal status pairs |
| 6 JVM | Strict decoding, required-nullable presence, invariants and compatibility |
| 9 ordinary device | Route/transport behavior and no premature local acknowledgement or data replacement |
| 3 opt-in live | Real backend/GitHub scratch-only integration; isolated in its own optional commit |

Unit decoding checks, actual HTTP route checks and Room pending-state checks protect different boundaries. The live
harness is optional convenience, not required for CI or shipping. The old parser suite is removed, not run in parallel.
Both producer and consumer checks run in the existing Android CI job for backend/Android contract changes.
`backend.yml` was not given Android triggers: app-only edits must not deploy the backend.

## Implementation and verification

### Original red/green and drift evidence

The refusal regression failed on the old implementation (exit **1**):

```text
RefusalTest > failuresThatSayNothingAboutTheItemLeaveItPending FAILED
77 tests completed, 1 failed, 1 skipped
```

Three old-path device regressions ended `Tests run: 3, Failures: 3`: malformed library, uncertain bookmark answer and
broken reading-save time, including actual epoch-millisecond overflow. Positive annotation id/version rules also had
a failing-then-passing regression. Synthetic server fixtures missing producer-required fields were corrected, not
assertions weakened. The old parser's malformed-time and null/numeric-text assertions moved to the new decoder.

Temporary incompatible producer/request renames were restored after proving the gate:

```sh
# Content.documentId -> wrongDocumentId, then restore:
./gradlew :backend:test --tests com.reporead.document.WireContractTest --no-daemon
# ConnectRequest installationId -> wrongInstallationId, then restore:
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.reporead.android.core.network.ContractTest --no-daemon
```

Both exited **1**, respectively:

```text
WireContractTest > contentAndChangeVariantsMatchTheirSharedExamples() FAILED
ContractTest > requestsMatchTheBodiesVerifiedByBackendControllers FAILED
```

### Original automated validation

Working directory `/private/tmp/reporead-api-contract`; reader dependencies installed with
`npm ci --ignore-scripts` in `android/reader-web` (exit **0**). Two existing low-severity npm advisories were not fixed.
Executed full formatting/tests, reader assets, production/device compilation and APK packaging:

```sh
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessApply :app:spotlessApply :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:mergeDebugAssets :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Exit **0**:

```text
BUILD SUCCESSFUL in 34s
91 actionable tasks: 20 executed, 71 up-to-date
```

The exact CI producer subset and JVM suite also passed (exit **0**, 10 producer tests, no skips/failures):

```sh
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessApply :app:spotlessCheck :backend:spotlessCheck :backend:test --tests '*WireContractTest' :app:testDebugUnitTest --no-daemon
```

```text
BUILD SUCCESSFUL in 12s
39 actionable tasks: 7 executed, 32 up-to-date
```

Final complete-suite check after the additional JVM assertions (no production/device changes after packaging):

```sh
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:mergeDebugAssets :app:compileDebugAndroidTestKotlin --no-daemon
```

Exit **0**, `BUILD SUCCESSFUL in 26s`. XML totals: **292 backend, 0 failures/errors, 3 corpus skips**;
**83 JVM, 0 failures/errors, 1 StudyCorpus skip**. No real-corpus measurement is claimed.

### Original device validation

Pixel 8a, Android 14/API 34. Serial redacted consistently. Each command exited **0**; installs reported `Success`:

```sh
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Library/Android/sdk/platform-tools/adb -s <Pixel-serial> shell am instrument -w com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

```text
Time: 130.149
OK (132 tests)
```

129 executed tests; 3 live tests skipped **before** opening the real store, then run explicitly below. Coverage includes
wrong/missing/null/coerced fields, timestamp overflow, unknown fields/enums, every route/status/request, malformed
acknowledgements, wrong identities/cards, retained lists/images/pending state, 401/unreadable session, size limits,
partial work, retirement/removal concurrency and second/third runs. The actual **45-second** read timeout and truncated
framing retain I/O causes; a complete JSON prefix in an incomplete HTTP body is never accepted. No transport workaround.

### Original live validation (not a new run for the history split)

The pre-existing Mac backend reported `41ab0a1272dbde9b6993f9b9f85ef4d04636ae60`.
`git diff --exit-code 41ab0a1272dbde9b6993f9b9f85ef4d04636ae60 a043217 -- backend/src/main` was empty:
production source equivalence, not a claim that the running jar was built from this branch. It was not restarted.

The private driver compared the installed APK from `pm path com.reporead.android.dev` to the built APK:
SHA-256 **1da65352393f7bab7f30fbd3e429bc3f1b3f0232c81a10c0449023ef971e968d** matched.
Before each live method it used `shell am start -W -n com.reporead.android.dev/com.reporead.android.MainActivity`
and checked `dumpsys window` for the Dev foreground package. An initial preflight without `-W` stopped before writes;
waiting for initial display fixed that harness race, without bypassing a guard.

Executed commands below used ids reported by instrumentation; the ADB tunnel was restored in `finally` on failure too:

```sh
adb -s <Pixel-serial> logcat -T 1 -s RepoRead
adb -s <Pixel-serial> reverse tcp:8081 tcp:8081
adb -s <Pixel-serial> shell am instrument -w -e liveApiContract true -e class com.reporead.android.LiveApiContractTest#scratchNoteExercisesRealReadsMutationsConflictsAndCleanup com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s <Pixel-serial> reverse --remove tcp:8081
adb -s <Pixel-serial> shell am instrument -w -e liveApiContract true -e class com.reporead.android.LiveApiContractTest#anUnreachableBackendKeepsACachedScratchNoteAndItsNewHighlightPending -e scratchDocumentId <reported-id> com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s <Pixel-serial> reverse tcp:8081 tcp:8081
adb -s <Pixel-serial> shell am instrument -w -e liveApiContract true -e class com.reporead.android.LiveApiContractTest#restoringTheBackendSendsTheSameScratchMutationOnceAndThenRemovesOnlyIt -e offlineMutationId <reported-uuid> com.reporead.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

All three instrumentation commands exited **0**:

```text
Time: 10.513
OK (1 test)
Time: 0.148
OK (1 test)
Time: 0.472
OK (1 test)
Live log occurrences of INVALID_RESPONSE: 0
Live log occurrences of Error response is not RepoRead's JSON: 0
Backend tunnel restored; only test-owned scratch annotations/cards and the temporary scratch bookmark removed.
```

Real SessionStore/Api/Sync/Room and GitHub reads: library/repository refresh, available repositories, scratch
content/cache, highlight create/edit/conflict/reattach/list/delete, card confirmation/grade/delete, unchanged diff,
missing-id 404, stored counts, bookmark on/off and repeat open. Offline, the cached page remained readable and the
labelled highlight UNSENT; restoring the tunnel delivered the same id, repeated push yielded one annotation, then
removed only it. Existing user annotations/bookmarks were not swept; backend idempotency tombstones remain owned by
the server. Logs/UI dumps stay private, outside Git. The sequence passed twice, once on the hash-verified APK.

### Exclusions and retained debt

- No live sign-out, account deletion, repository disconnect or GitHub write; destructive routes used isolated fixtures.
- Live reconnect, images, reading-position UI/restore, changed-version diff and whole-repository Save all were not
  repeated here. This is not UI automation of every screen; existing Save all failure/size/second-run coverage stayed.
- No hosted workflow, release APK, VM deployment or post-release smoke check. CI commands ran locally; triggers were reviewed.
- Structured Sync outcomes, credential-refresh races, query timeouts, existing reading-refusal debt, navigation/lifecycle
  foundations and npm advisories remain separate. [Audit step 9](audit-remediation-plan.md#step-9--complete-error-boundaries-and-lifecycle-checks-a11) is partial.
- No phone data reset, DB migration or repair. Unrelated `.gitignore`, foundations plans and `notes/` are preserved.

### Review-branch verification after splitting

Each code-bearing commit was checked in the clean detached worktree `/private/tmp/reporead-api-split-check`,
with only that commit's files present—not later changes from the working workspace:

```sh
# 35c0aa0, producer checks:
DEBUG=false ./gradlew :backend:spotlessCheck :backend:test --no-daemon
# 0fc56ef, complete Android replacement; locked reader dependencies installed first:
DEBUG=false ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :backend:spotlessCheck :backend:test :app:spotlessCheck :app:testDebugUnitTest :app:mergeDebugAssets :app:compileDebugAndroidTestKotlin --no-daemon
# 6446df5, optional live harness compiled without live writes:
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:spotlessCheck :app:compileDebugAndroidTestKotlin --no-daemon
```

All exited **0**, respectively:

```text
BUILD SUCCESSFUL in 28s
8 actionable tasks: 8 executed
BUILD SUCCESSFUL in 53s
49 actionable tasks: 48 executed, 1 up-to-date
BUILD SUCCESSFUL in 12s
32 actionable tasks: 14 executed, 18 up-to-date
```

XML totals remained 292 backend (3 corpus skips) and 83 JVM (1 corpus skip), zero failures/errors. The split did not
change production/test source or remove behavior assertions. Superseded parser tests stay deleted in the replacement
commit; the optional live harness is separate. No phone/device test execution was repeated for history-only changes.
The final documentation commit consolidates this record; `main` and original `a043217` remain unchanged.
