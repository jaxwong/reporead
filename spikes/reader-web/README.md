# Isolated reader display bundle

This Stage 0 bundle consumes only HTML and canonical block text exported by the Java backend renderer. It does not parse Markdown or access GitHub. Mermaid and highlight.js are justified by technical-note rendering; esbuild packages them locally with license notices so the phone makes no CDN requests.

From this directory:

```sh
npm ci --ignore-scripts
npm run check
npm run build -- ../reader-android/build/generated/reader-assets
```

The Android build runs the check/build scripts, but dependency installation is explicit. Generated assets live under the Android module's ignored build directory. `package-lock.json` owns resolved npm dependencies.

Audit verified on 2026-10-02: **0 vulnerabilities** after the approved targeted update of transitive lodash-es from 4.17.23 to 4.18.1. Mermaid remains pinned to 11.17.2. Only the lodash-es package entry changed in `package-lock.json`; `package.json` and all other resolved packages are unchanged. The installed reader remains a non-shippable Stage 0 experiment, not a release artifact.

The maintenance commands executed from this directory were:

```sh
npm update lodash-es --ignore-scripts --no-audit
npm ci --ignore-scripts
npm audit
npm run check
npm run build -- ../reader-android/build/generated/reader-assets
```

All exited 0. A clean install from the new lockfile and the explicit audit both reported `found 0 vulnerabilities`. No `npm audit fix`, override, new dependency, or unrelated upgrade was used. This is an audit result at verification time, not a guarantee against future advisories.

`Run checks` in the phone app exercises DOM selections and canonical-text invariants. It is not evidence that native touch handles behave correctly; real touch selection must be tested separately.
