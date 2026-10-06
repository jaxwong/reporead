plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Variant API needs a typed output directory to wire generated assets to their producer.
abstract class BundleReader : Exec() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
}
val bundleReader = tasks.register<BundleReader>("bundleReader") {
    workingDir("../spikes/reader-web")
    inputs.files("../spikes/reader-web/reader.js", "../spikes/reader-web/reader.css", "../spikes/reader-web/build.mjs", "../spikes/reader-web/package-lock.json")
    doFirst { commandLine("npm", "run", "build", "--", assetDirectory.get().asFile.absolutePath) }
}

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
    testImplementation("junit:junit:4.13.2")
}
