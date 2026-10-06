# Reader display bundle

The Android reader's display code. It consumes only HTML and canonical block text produced by the backend's Java renderer: it highlights code, renders Mermaid with strict security, and reports render status. It does not parse Markdown or access the network. Mermaid and highlight.js are bundled locally by esbuild with license notices, so the phone makes no CDN requests. `package-lock.json` owns resolved npm dependencies.

Install once from this directory; the Android build (`:app:bundleReader`) then runs the check and build scripts and writes into the app module's ignored build directory:

```sh
npm ci --ignore-scripts
```

Audit verified on 2026-10-02: **0 vulnerabilities** after the approved targeted update of transitive lodash-es from 4.17.23 to 4.18.1; Mermaid is pinned to 11.17.2. Commands were `npm update lodash-es --ignore-scripts --no-audit`, `npm ci --ignore-scripts`, `npm audit`, all exit 0. That is an audit result at verification time, not a guarantee against future advisories.

Audit on 2026-10-06: **2 low** — KaTeX [GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7) (prototype pollution bypassing trust restrictions; requires pre-existing pollution) through Mermaid 11.17.2, whose `katex ^0.16.47` range excludes the fixed 0.18.2+. npm's only offered fix is a major downgrade to Mermaid 10.8.0. Not applied; awaiting a decision or a Mermaid release that accepts a fixed KaTeX.
