# RepoRead Android app

Kotlin/Compose client: GitHub sign-in through the backend, connected repositories, folder/note browsing, the isolated technical-Markdown reader, an offline cache, reading progress with Continue Reading, and bookmarks. It talks to the backend only through the HTTP/JSON contract in [backend/README.md](../backend/README.md); it never holds GitHub credentials.

## Run on the phone

Start PostgreSQL and the backend first (see the backend README). Install reader dependencies once with `npm ci --ignore-scripts` in `android/reader-web`. Then, from the repository root:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
~/Library/Android/sdk/platform-tools/adb -s <device-serial> reverse tcp:8081 tcp:8081
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am start -n com.reporead.android/.MainActivity
```

`adb reverse` makes the phone's `127.0.0.1:8081` reach the Mac's loopback backend, so the registered GitHub callback is unchanged. It is reset when the phone disconnects — including when airplane mode or Wi-Fi loss ends wireless debugging. Re-enable wireless debugging and repeat it before syncing. Cleartext HTTP is permitted only to `127.0.0.1` (`res/xml/network_security_config.xml`). The base URL is a `BuildConfig` constant in `build.gradle.kts`.

## Device tests

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SERIAL=<device-serial> ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

These run the Room cache and pending-change rules against an in-memory database on the phone. **Gradle uninstalls the app afterwards**, which deletes its session, cache, and any unsynced changes; sync before running them on the phone you read on, then reinstall and sign in.

## Behavior

- **Sign-in:** a Chrome Custom Tab opens `/app/sign-in` with a PKCE challenge; the `reporead://auth` redirect is exchanged with the verifier for a bearer session, encrypted with an Android Keystore AES-GCM key. A 401 (expired session, or the backend restarted and lost its GitHub token) signs the app out of the network only: saved notes stay readable and the library offers **Sign in with GitHub**. **Sign out** revokes the session and deletes this phone's cached notes, images, and unsynced changes.
- **Offline cache (Room):** repositories, document lists, and the latest complete rendered copy of each opened note, keyed by document and blob SHA. A note whose cached version matches the document's current version opens without a network call. Otherwise the current version is fetched; if that fails, the older saved copy is shown with the reason. Failed refreshes never replace saved lists or notes. There is no eviction yet; every opened note's latest version is kept (decide retention from real use).
- **Reading position:** the reader saves the first block visible at the top of the screen (heading path, 64-character text prefix, block index) and scroll percent 0.7 s after scrolling stops, when the app goes to the background, and when leaving the note. It records the blob SHA actually displayed; refreshing a repository never changes it. Restoring tries heading + text, text, section, block index, then percent, and says when it is approximate or when the note changed since the last read. The system font size is applied to the reader.
- **Continue reading and bookmarks:** the library shows the five most recently read notes and all bookmarks. Saves and bookmark toggles are stored locally as pending and sent when the library opens or **Sync** is tapped; pending changes win over older server state and yield to newer state. There is no background sync.
- **Images:** repository images referenced by a note are fetched through the backend by native code (the page never sees the token) and cached with that note version. Offline and uncached, an image shows `[Image unavailable: …]`; remote images are blocked.
- **Reader isolation:** backend-sanitized HTML in a WebView with app-bundled reader/Mermaid/highlight assets only, no credentials, no JavaScript bridge, and no network/file/content access. Tapped `http(s)` links open the browser; links between notes are not followed.

`reader-web/` holds the reader's display JavaScript and styles; see its README. `schemas/` holds exported Room schemas for writing migrations.
