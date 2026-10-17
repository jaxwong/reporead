plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.diffplug.spotless")
}

// One formatter owns the app's Kotlin layout, as palantir-java-format does the backend's (Kotlin coding conventions,
// 120 columns to match). CI runs spotlessCheck; spotlessApply fixes it. reader-web's JavaScript is not covered.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.64").kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
}

// The Variant API needs a typed output directory to wire generated assets to their producer.
abstract class BundleReader : Exec() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
}
val bundleReader = tasks.register<BundleReader>("bundleReader") {
    workingDir("reader-web")
    inputs.files(
        "reader-web/reader.js",
        "reader-web/diagrams.js",
        "reader-web/reader.css",
        "reader-web/build.mjs",
        "reader-web/check-restore.mjs",
        "reader-web/package.json",
        "reader-web/package-lock.json",
    )
    doFirst { commandLine("npm", "run", "build", "--", assetDirectory.get().asFile.absolutePath) }
}

/*
 * Release and development builds are signed with one personal key kept outside the repository, so each release installs
 * over the last and keeps the phone's data (see android/README.md). Packaging without it fails; nothing falls back to
 * the debug key.
 */
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

// The server owns the library's size cap (Library.MAX_RESPONSE_BYTES); the app reads at most that, taken from the
// backend's own line, so no second number exists. A line this cannot read fails the build.
val libraryMaxResponseBytes =
    providers.fileContents(rootProject.layout.projectDirectory.file("backend/src/main/java/com/reporead/library/Library.java"))
        .asText
        .map { source ->
            Regex("""MAX_RESPONSE_BYTES = ([0-9 *]+);""").find(source)?.groupValues?.get(1)
                ?: error("Library.java has no line `MAX_RESPONSE_BYTES = <digits and *>;`")
        }
        .get()

val signingStore = providers.gradleProperty("reporead.signing.storeFile").orNull
val signingPasswordFile = providers.gradleProperty("reporead.signing.passwordFile").orNull

android {
    namespace = "com.reporead.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.reporead.android"
        minSdk = 34
        targetSdk = 34
        // The commit count only grows along main, so each release installs over the last (Obtainium, ADR-15).
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "LIBRARY_MAX_RESPONSE_BYTES", libraryMaxResponseBytes)
    }

    signingConfigs {
        if (signingStore != null && signingPasswordFile != null) create("personal") {
            val password = file(signingPasswordFile).readText().trim()
            storeFile = file(signingStore)
            storePassword = password
            keyAlias = "reporead"
            keyPassword = password
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("personal")?.let { signingConfig = it }
            // Development backend on the Mac, reached through `adb reverse tcp:8081 tcp:8081`; see backend/README.md.
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8081\"")
            // A separate app beside the release one (ADR-12): its own id, name, data and sign-in redirect, so testing on
            // the phone never replaces the app in use. A loopback backend has no domain to verify, so the debug app
            // keeps its own scheme (src/debug/AndroidManifest.xml; PendingAppSignIn.appRedirect).
            applicationIdSuffix = ".dev"
            val signInScheme = "reporead-dev"
            manifestPlaceholders["appLabel"] = "RepoRead Dev"
            manifestPlaceholders["signInScheme"] = signInScheme
            buildConfigField("String", "SIGN_IN_REDIRECT", "\"$signInScheme://auth\"")
        }
        release {
            signingConfigs.findByName("personal")?.let { signingConfig = it }
            manifestPlaceholders["appLabel"] = "RepoRead"
            // Hosted backend (ADR-14); HTTPS only. Sign-in ends at its verified App Link (src/release/AndroidManifest.xml).
            val hostedBackend = "reporead.duckdns.org"
            manifestPlaceholders["hostedBackend"] = hostedBackend
            buildConfigField("String", "API_BASE_URL", "\"https://$hostedBackend\"")
            buildConfigField("String", "SIGN_IN_REDIRECT", "\"https://$hostedBackend/app/signed-in\"")
            // Not minified: Room, Compose, and the reader bridge-free WebView are verified unshrunk only.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Migration tests construct the exact previously shipped schema; these assets are test-only.
    sourceSets.getByName("androidTest").assets.directories.add(file("schemas").absolutePath)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    // Exported schemas let each schema change ship a real migration instead of discarding pending local changes.
    arg("room.schemaLocation", file("schemas").absolutePath)
}

// Every task that packages a signed APK or bundle. The package*Resources tasks sign nothing and unit tests need them,
// so CI runs unit tests without the key.
tasks.matching {
    it.name.startsWith("package") && !it.name.endsWith("Resources") && it.name != "packageDebugUnitTestForUnitTest"
}.configureEach {
    doFirst {
        if (signingStore == null || signingPasswordFile == null) {
            throw GradleException("Set reporead.signing.storeFile and reporead.signing.passwordFile in ~/.gradle/gradle.properties (android/README.md).")
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleReader, BundleReader::assetDirectory)
}

dependencies {
    // Offline study extraction needs a DOM parser for the saved, sanitized pages (including loose/nested lists).
    implementation("org.jsoup:jsoup:${rootProject.extra["jsoupVersion"]}")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.16.0")
    // Custom Tabs for GitHub sign-in in the user's browser rather than an embedded WebView.
    implementation("androidx.browser:browser:1.10.0")
    // Offline cache and pending local changes.
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // Sends pending changes once the phone has a network, also after the app is closed (ADR-08 background sync).
    implementation("androidx.work:work-runtime:2.12.0")
    testImplementation("junit:junit:4.13.2")
    // Room needs Android's SQLite, so cache rules are tested on the device.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
