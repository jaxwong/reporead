plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// The Variant API needs a typed output directory to wire generated assets to their producer.
abstract class BundleReader : Exec() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
}
val bundleReader = tasks.register<BundleReader>("bundleReader") {
    workingDir("reader-web")
    inputs.files("reader-web/reader.js", "reader-web/reader.css", "reader-web/build.mjs", "reader-web/package-lock.json")
    doFirst { commandLine("npm", "run", "build", "--", assetDirectory.get().asFile.absolutePath) }
}

/*
 * Release and development builds are signed with one personal key kept outside the repository, so every build installs
 * over the last and keeps the phone's data (see android/README.md). Packaging without it fails; nothing falls back to
 * the debug key.
 */
val signingStore = providers.gradleProperty("reporead.signing.storeFile").orNull
val signingPasswordFile = providers.gradleProperty("reporead.signing.passwordFile").orNull

android {
    namespace = "com.reporead.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.reporead.android"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Development backend reached through `adb reverse tcp:8081 tcp:8081`; see backend/README.md.
        buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8081\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug { signingConfigs.findByName("personal")?.let { signingConfig = it } }
        release {
            signingConfigs.findByName("personal")?.let { signingConfig = it }
            // Not minified: Room, Compose, and the reader bridge-free WebView are verified unshrunk only.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    // Exported schemas let later stages write real migrations instead of discarding pending local changes.
    arg("room.schemaLocation", file("schemas").absolutePath)
}

tasks.matching { it.name.startsWith("package") && it.name != "packageDebugUnitTestForUnitTest" }.configureEach {
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
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.16.0")
    // Custom Tabs for GitHub sign-in in the user's browser rather than an embedded WebView.
    implementation("androidx.browser:browser:1.10.0")
    // Offline cache and pending local changes (spec: Room for local persistence).
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    // Room needs Android's SQLite, so cache rules are tested on the device.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
