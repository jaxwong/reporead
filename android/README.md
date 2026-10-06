# RepoRead Android app

Kotlin/Compose client for the Stage 1 reading path: GitHub sign-in through the backend, connected repositories, folder/note browsing, and the isolated technical-Markdown reader. It talks to the backend only through the HTTP/JSON contract in [backend/README.md](../backend/README.md); it never holds GitHub credentials.

## Run on the phone

Start PostgreSQL and the backend first (see the backend README). Then, from the repository root, with reader dependencies installed once via `npm ci --ignore-scripts` in `android/reader-web`:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
~/Library/Android/sdk/platform-tools/adb -s <device-serial> reverse tcp:8081 tcp:8081
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am start -n com.reporead.android/.MainActivity
```

`adb reverse` makes the phone's `127.0.0.1:8081` reach the Mac's loopback backend, so the registered GitHub callback is unchanged. It is reset when the phone disconnects; repeat it after reconnecting. Cleartext HTTP is permitted only to `127.0.0.1` (`res/xml/network_security_config.xml`). The base URL is a `BuildConfig` constant in `build.gradle.kts`.

## Behavior

- **Sign-in:** a Chrome Custom Tab opens `/app/sign-in` with a PKCE challenge; the `reporead://auth` redirect is exchanged with the verifier for a bearer session, encrypted with an Android Keystore AES-GCM key. Any 401 returns to the sign-in screen. Restarting the backend clears its in-memory GitHub token, so sign in again after a backend restart.
- **Library:** connected repositories → Add repository (only repositories granted to the GitHub App) → Connect. A new connection must be refreshed explicitly; refresh failures keep the previous list.
- **Reader:** backend-sanitized HTML in a WebView with app-bundled reader/Mermaid/highlight assets only, no credentials, no JavaScript bridge, and no network/file/content access. Tapped `http(s)` links open the browser; links between notes are not followed. Repository images are shown as `Image blocked` until Stage 2 defines authenticated image delivery.
- Loading, empty, access-denied, GitHub-unavailable, backend-unreachable, deleted-note, and unsupported-content states are shown explicitly. Nothing is cached offline yet (Stage 2).

`reader-web/` holds the reader's display JavaScript and styles; see its README.
