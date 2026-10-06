plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

evaluationDependsOn(":backend")
// The Variant API needs typed output directories to wire generated assets to their producer.
abstract class BundleReader : Exec() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
}
abstract class RenderFixture : JavaExec() {
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
}
val bundleReader = tasks.register<BundleReader>("bundleReader") {
    workingDir("../reader-web")
    inputs.files("../reader-web/reader.js", "../reader-web/reader.css", "../reader-web/build.mjs", "../reader-web/package-lock.json")
    doFirst { commandLine("npm", "run", "build", "--", assetDirectory.get().asFile.absolutePath) }
}
val renderFixture = tasks.register<RenderFixture>("renderFixture") {
    val backend = project(":backend")
    dependsOn(backend.tasks.named("classes"))
    classpath = backend.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.reporead.document.RenderNote")
    inputs.file("../reader-web/fixture.md")
    inputs.files(backend.fileTree("src/main/java"))
    doFirst {
        assetDirectory.get().asFile.mkdirs()
        args = listOf("--fixture", file("../reader-web/fixture.md").absolutePath,
            assetDirectory.get().file("fixture-note.html").asFile.absolutePath)
    }
}

android {
    namespace = "com.reporead.readerspike"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.reporead.readerspike"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleReader, BundleReader::assetDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(renderFixture, RenderFixture::assetDirectory)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.16.0")
}
