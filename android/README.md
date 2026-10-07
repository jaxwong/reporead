# RepoRead Android app

Kotlin/Compose client: GitHub sign-in through the backend, connected repositories, folder/note browsing, the isolated technical-Markdown reader, an offline cache, reading progress with Continue Reading, bookmarks, and highlights with notes, and what changed since the version last read. It talks to the backend only through the HTTP/JSON contract in [backend/README.md](../backend/README.md); it never holds GitHub credentials.

## Run on the phone

Start PostgreSQL and the backend first (see the backend README). Install reader dependencies once with `npm ci --ignore-scripts` in `android/reader-web`. Then, from the repository root:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
~/Library/Android/sdk/platform-tools/adb -s <device-serial> reverse tcp:8081 tcp:8081
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am start -n com.reporead.android/.MainActivity
```

`adb reverse` makes the phone's `127.0.0.1:8081` reach the Mac's loopback backend, so the registered GitHub callback is unchanged. It is reset when the phone disconnects — including when airplane mode or Wi-Fi loss ends wireless debugging. Re-enable wireless debugging and repeat it before syncing. Cleartext HTTP is permitted only to `127.0.0.1` (`res/xml/network_security_config.xml`). The base URL is a `BuildConfig` constant in `build.gradle.kts`.

## Signing and the release APK

Every build — release, debug, and the device-test APK — is signed with one personal key, so each installs over the last and keeps the phone's data. The key is outside the repository; `~/.gradle/gradle.properties` names it (paths only):

```properties
reporead.signing.storeFile=/Users/<you>/.config/reporead/release.jks
reporead.signing.passwordFile=/Users/<you>/.config/reporead/release-keystore-password.txt
```

Both files are mode 0600. It was created with `keytool -genkeypair -keystore … -storetype PKCS12 -alias reporead -keyalg RSA -keysize 4096 -validity 10000 -storepass:file … -keypass:file …` (certificate SHA-256 `20:49:29:9F:…:53:29:3F`). **Back up both files:** without them the next build cannot update the installed app, and installing one signed differently requires uninstalling it, which deletes the phone's saved notes and unsynced changes. Packaging without the properties fails with an explicit message; nothing falls back to the debug key.

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleRelease --no-daemon
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/release/app-release.apk
```

The release build is not debuggable (no WebView DevTools, no `run-as`) and not minified. It uses the same backend address as development, `http://127.0.0.1:8081` through `adb reverse`, so refreshing and syncing need the phone on wireless debugging with the Mac; saved notes, highlights, and progress work offline and sync later. Hosting the backend is a separate decision. To inspect the phone's database, install the debug build over it (same key and app id; data is kept), then the release build again.

## Device tests

These run the Room cache and pending-change rules against an in-memory database on the phone. Install and run them with adb, which keeps the installed app and its data:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r android/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> install -r -t android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
~/Library/Android/sdk/platform-tools/adb -s <device-serial> shell am instrument -w com.reporead.android.test/androidx.test.runner.AndroidJUnitRunner
```

They need the debug build installed; reinstall the release build afterwards. `./gradlew :app:connectedDebugAndroidTest` runs the same tests but **uninstalls the app afterwards**, deleting its session, cache, and any unsynced changes; do not use it on the phone you read on.

## Behavior

- **Sign-in:** a Chrome Custom Tab opens `/app/sign-in` with a PKCE challenge; the `reporead://auth` redirect is exchanged with the verifier for a bearer session, encrypted with an Android Keystore AES-GCM key. A 401 (expired session, or the backend restarted and lost its GitHub token) signs the app out of the network only: saved notes stay readable and the library offers **Sign in with GitHub**. **Sign out** revokes the session and deletes this phone's cached notes, images, and unsynced changes.
- **Offline cache (Room):** repositories, document lists, and the latest complete rendered copy of each opened note, keyed by document and blob SHA. A note whose cached version and path match the document's current version and path opens without a network call. Otherwise the current version is fetched; if that fails, the older saved copy is shown with the reason. Failed refreshes never replace saved lists or notes. There is no eviction yet; every opened note's latest version is kept (decide retention from real use).
- **Reading position:** the reader saves the first block visible at the top of the screen (heading path, 64-character text prefix, block index) and scroll percent 0.7 s after scrolling stops, when the app goes to the background, and when leaving the note. It records the blob SHA actually displayed; refreshing a repository never changes it. Restoring tries heading + text, text, section, block index, then percent, and says when it is approximate or when the note changed since the last read. The system font size is applied to the reader.
- **Continue reading and bookmarks:** the library shows the five most recently read notes and all bookmarks. Saves and bookmark toggles are stored locally as pending and sent when the library opens or **Sync** is tapped; pending changes win over older server state and yield to newer state. There is no background sync.
- **Highlights and notes:** select text within one block and choose **Highlight** or **Add note** from the selection menu. The selection is read through `evaluateJavascript` using the Stage 0 contract (block id, UTF-16 offsets into canonical text); the page still cannot call native code. A new highlight is saved locally as a pending creation with a client mutation id in one Room write, then sent immediately and again on each library open, **Sync**, or note open until the server acknowledges it — the same id every time, so a lost acknowledgement cannot duplicate it. A refusal (e.g. the text no longer matches) is kept and shown, not retried. **Notes** lists the note's highlights with Show, Edit note, and Delete. Editing and deleting acknowledged highlights are online only; a conflicting edit shows both texts and asks which to keep. Each highlight is drawn at the server's current location for it when that location is in the displayed version; a pending creation is drawn where it was made. A highlight the server re-anchored after an edit says what its text is now. An **orphaned** highlight (the note changed and the passage could not be found reliably) shows its original selection with its context and a **Reattach** button: select the passage in the note, then choose **Reattach here** from the selection menu (online; the server verifies the selection). Notes removed from the repository are labelled in Continue reading and Bookmarks; their history and highlights are kept.
- **Changes since last read:** the library marks Continue reading entries "Updated since you read", lists every read note whose current version (in the saved note lists) is not the one last read, and lists the five most recently changed other notes as "Not read yet" or "Read", each with when a refresh saw it change. Opening a note whose last-read version differs from the displayed one shows **Changed since you last read**: changed, new, and removed sections with line counts; tapping a current section scrolls to its heading. The last-read version is captured before the reader saves the displayed one, so the summary appears once per opening; after reading, the new version is the baseline. Online only and not cached; a failure is shown with **Try again**. A note never read shows nothing.
- **Links between notes:** tapping an Obsidian `[[link]]` or a relative `.md` link opens that note, resolved like Obsidian against the repository's saved note list (works offline): an exact path, a name with a folder, or a file name, preferring the linking note's folder; when several notes share the name the app asks which one. A `#Heading` opens the note at that heading instead of the saved position. An unknown name says so. `![[image.png]]` embeds show the image (resolved by the server by name; refresh the repository once so it knows the image files).
- **Images:** repository images referenced by a note are fetched through the backend by native code (the page never sees the token) and cached with that note version. Offline and uncached, an image shows `[Image unavailable: …]`; remote images are blocked.
- **Reader isolation:** backend-sanitized HTML in a WebView with app-bundled reader/Mermaid/highlight assets only, no credentials, no JavaScript bridge, and no network/file/content access. Tapped `http(s)` links open the browser; links between notes are not followed.

`reader-web/` holds the reader's display JavaScript and styles; see its README. `schemas/` holds exported Room schemas; version 2 adds `annotations` through a Room auto-migration, verified on the Pixel against an existing version 1 database; version 3 adds highlight locations, status, and original context, and the `deleted` flag on reading states and bookmarks (auto-migration with defaults); version 4 adds a note's refresh-found change time (`changedAt`, nullable), verified on the Pixel against its existing version 3 database; version 5 adds saved notes' search text; version 6 adds their page format (`renderFormat`), so a saved copy from an older format is fetched once more when opened online.
