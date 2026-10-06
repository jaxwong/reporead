# Reader selection spike

This is a **non-shippable Stage 0 experiment**. It displays HTML exported by the real backend's Java Markdown parser, with locally bundled Mermaid and code highlighting. A private GitHub note snapshot can be imported explicitly; the separate synthetic Markdown fixture covers code, tables, Unicode, and an app-owned image. This is not live backend delivery, user authorization, or the production Android app. Obtain approval to remove the spike when a verified product reader replaces it.

**Installed on the Pixel 8a on 2026-10-02:** the targeted lodash-es update cleared the npm audit while preserving Mermaid 11.17.2. The selected private note export was imported and verified byte-for-byte. Its renderer status, automated DOM-range checks, and one prose finger selection passed based on the user's phone result/screenshots and export validation. Diagram legibility, the code/image/Unicode fixture, other touch-selection cases, and repeat cold-launch behavior still need verification. See [bundle status](../reader-web/README.md).

## Run

Install the audited dependencies using the [bundle instructions](../reader-web/README.md), then open the repository root in Android Studio. Select the `reader-spike` run configuration and the paired phone. Alternatively, from the repository root:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :reader-spike:assembleDebug
~/Library/Android/sdk/platform-tools/adb -s <connected-device-serial> install -r spikes/reader-android/build/outputs/apk/debug/reader-spike-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <connected-device-serial> shell am start -n com.reporead.readerspike/.ReaderSpikeActivity
```

The default **Real note** mode expects a private `reader-proof.html` file in the app's private files directory. If absent, it displays an explicit missing-import message; it never substitutes a fixture. Prepare the snapshot with the [read-only GitHub tool](../github-read-proof/README.md), then export it with the [backend CLI](../../backend/README.md). Keep the inputs/exports outside the repository and out of the APK. Import into this debuggable spike's private storage with the commands verified on the paired Pixel:

```sh
~/Library/Android/sdk/platform-tools/adb -s <connected-device-serial> shell -T run-as com.reporead.readerspike mkdir -p files
~/Library/Android/sdk/platform-tools/adb -s <connected-device-serial> shell -T run-as com.reporead.readerspike tee files/reader-proof.html < /private/tmp/reporead-build/reader-proof.html > /dev/null
~/Library/Android/sdk/platform-tools/adb -s <connected-device-serial> shell am start -S -W -n com.reporead.readerspike/.ReaderSpikeActivity
```

The stdout redirection prevents `tee` from printing private note content. The real note is not copied to shared phone storage or bundled in the APK. Import verification succeeded, but this is still a manual development snapshot path, not authenticated live backend delivery.

Choose **Test fixture** deliberately for synthetic content. Tap **Status** after rendering, then **Run checks** for Java/DOM canonical-text equality, whole-block ranges, and cross-block rejection. Those automated DOM checks do not prove touch selection. Long-press a phrase and tap **Capture**, then repeat after a cold launch. Cover formatted prose, code, table cells, Unicode, and an unsupported cross-block range.

The WebView has no credentials or native JavaScript bridge, blocks remote/file/content resources and navigation, and only serves app-owned assets through WebViewAssetLoader. It receives server-exported HTML, never raw credentials. See the [Stage 0 text contract](../../requirements/reporead-stage0-boundaries.md).

`selection-fixture.html` is retained only as the historical static input for the original paragraph experiment. Its old inline selection implementation is removed and the app no longer loads it. The current selection owner is `spikes/reader-web/reader.js`.

## Stage 0 observations (2026-10-01–2026-10-02)

- On a Pixel 8a, the user selected `transaction` in the first paragraph. The app reported `Quote: transaction`, `Block: intro`, and `Offsets: 2–13`, matching the fixture's text. After a verified cold launch on 2026-10-02, the user repeated the selection and reported the same result. The user also reported the expected cross-block rejection: `Select text within one marked block for this spike.` The single-block, repeat-run, and cross-block checks passed based on these user-reported results.
- A separate read-only GitHub App request fetched `core/backend engineering/01-api-boundaries-and-contracts.md` in `jaxwong/zw_obsidian` (blob `f92434ddd08673a76234168f4becc82f04fdcd6c`, 16,318 bytes). It contains prose, tables, and four Mermaid fences, but no Markdown image or non-Mermaid code fence. Its content remains outside the repository/APK; syntax-highlighted code and images use the clearly labeled synthetic Markdown fixture.
- Java renderer tests, backend authentication-boundary tests, and Android compilation pass. See the [verification record](../../requirements/reporead-stage0-boundaries.md#verification-record).
- Real browser GitHub sign-in returning ID/login and user-scoped eligibility for private repository `jaxwong/zw_obsidian` were confirmed by the user on 2026-10-02. This is not Android sign-in or live note delivery; see the exact response in the [verification record](../../requirements/reporead-stage0-boundaries.md#signed-in-repository-eligibility-follow-up-2026-10-02).
- After the user approved continuing with the targeted lodash-es update, the full npm audit reported zero vulnerabilities. The APK build passed and installation returned `Success`. The selected real note exported as 200 canonical blocks and four Mermaid sources; its 75,234-byte HTML was verified byte-for-byte in private app storage. Android reported a successful cold launch. The initial screenshot was black, so no visual renderer success is inferred from the launch/import checks.
- The user then reported real-note status: `state: ready`, `blocks: 200`, `diagrams: 4`, `diagramErrors: 0`, `canonicalText: true`. This is a passing renderer-status check, separate from DOM-range and native touch-selection checks.
- The user's next two screenshots show all three real-note automated DOM checks passed: `empty selection`, `200 canonical blocks`, and `cross-block rejection`, with the same ready renderer status. `touchSelectionVerified: false` correctly distinguishes programmatic DOM ranges from finger selection. The screenshots also show the real note's prose and heading layout.
- The user then supplied screenshots of native selection handles and **Capture** for `untrusted requests`: block `b1`, UTF-16 offsets `[131,149)`, heading `01 — API Boundaries and Contracts`, and the real note's source SHA. An independent check against the private Java HTML export verified the quote/range, heading, and source version. This proves one real-note prose finger selection, not all mixed-content selection behavior.
- Diagram legibility, the separate code/image/Unicode fixture and its DOM checks, remaining mixed-content/manual cross-block touch selections, repeat cold-launch behavior, and live backend note delivery remain unverified. Neither a fixture nor the selected repository in a development snapshot command proves a production integration.
