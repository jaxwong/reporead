# Reader display bundle

The Android reader's display code. It consumes only HTML and canonical block text produced by the backend's Java renderer: it highlights code, renders Mermaid with strict security, and reports render status. It does not parse Markdown or access the network. Mermaid and highlight.js are bundled locally by esbuild with license notices, so the phone makes no CDN requests. Mermaid, with the KaTeX it brings, is its own script (`diagrams.js`, 3.4 MB), which `reader.js` (0.2 MB) loads only for a page with a diagram, a fifth of the notes: measured on the Pixel 8a (2026-10-17), a page without one became ready in about 0.1 s warm instead of 0.2–0.7 s, and a page with one about 0.1–0.2 s later than before, as Mermaid now follows in a second request. Code is highlighted when its fence names one of highlight.js's common languages (36), `http` or `properties`; any other fence shows plain ([ADR-04](../../docs/adrs.md#adr-04--the-server-renders-an-isolated-webview-displays)). `package-lock.json` owns resolved npm dependencies.

Install once from this directory; the Android build (`:app:bundleReader`) then runs the check and build scripts and writes into the app module's ignored build directory:

```sh
npm ci --ignore-scripts
```

## Device check: reading-position restore

Restoring depends on real layout, so it is checked in the debug app on the phone, not in a test runner. Debug builds enable WebView debugging; release builds do not. Open a note in the reader, then from the repository root:

```sh
~/Library/Android/sdk/platform-tools/adb -s <device-serial> forward tcp:9333 localabstract:webview_devtools_remote_$(~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell pidof com.reporead.android.dev | tr -d '\r')
node android/reader-web/check-restore.mjs "$(curl -s http://127.0.0.1:9333/json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["webSocketDebuggerUrl"])')"
```

It uses only the reader's own `window.reporead`: for each block it scrolls the block to the top with `restore` (an anchor with only a block index), saves the position `position()` reports, scrolls away, restores that position, and reads it back. Passes are `exact` and `text` (the restore mode, with the same position read back), `sameRow` (the first cell of the same table row is saved instead), and `collapsed` (the heading of a hidden study section is saved instead). `endOfNote` blocks cannot reach the top of the screen. Any `wrong` or `approximate` entry exits 1.

Audit verified on 2026-10-02: **0 vulnerabilities** after the approved targeted update of transitive lodash-es from 4.17.23 to 4.18.1; Mermaid is pinned to 11.17.2. Commands were `npm update lodash-es --ignore-scripts --no-audit`, `npm ci --ignore-scripts`, `npm audit`, all exit 0. That is an audit result at verification time, not a guarantee against future advisories.

Audit on 2026-10-06: **2 low** — KaTeX [GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7) (prototype pollution bypassing trust restrictions; requires pre-existing pollution) through Mermaid 11.17.2, whose `katex ^0.16.47` range excludes the fixed 0.18.2+. npm's only offered fix is a major downgrade to Mermaid 10.8.0. Not applied; awaiting a decision or a Mermaid release that accepts a fixed KaTeX.

Audit on 2026-10-19: `npm audit` exited **1**, **2 low** (the same KaTeX advisory and Mermaid's transitive finding; no moderate/high/critical findings). npm now offers Mermaid **12.2.0**, a breaking major upgrade, rather than the earlier downgrade. The accepted low-severity risk is retained for this audit: exploitation requires pre-existing prototype pollution or control of renderer-option prototypes; Mermaid still runs in strict mode with HTML labels disabled, and the WebView blocks external resources. These constraints are not a proof the dependency is safe. No override, forced fix or dependency change was made; the upgrade requires separate approval and reader verification. The broader assessment and exact versions are in [feature status](../../docs/feature-list.md#audit-pr5--closure-check-2026-10-19).
