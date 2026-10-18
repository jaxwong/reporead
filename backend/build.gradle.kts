plugins {
    java
    id("org.springframework.boot")
    id("com.diffplug.spotless")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// One formatter owns layout, so reviews and diffs are about content. CI runs spotlessCheck; spotlessApply fixes it.
spotless {
    java {
        palantirJavaFormat("2.100.0").formatJavadoc(true)
    }
}

// META-INF/build-info.properties, served by /actuator/info on the management port: which commit the running jar is.
// No build time, so an unchanged commit builds the same jar. Fails the build outside a Git checkout rather than guess.
springBoot {
    buildInfo {
        excludes.set(setOf("time"))
        properties {
            additional.put("commit", providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() })
        }
    }
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Micrometer metrics and health on a loopback-only management port (see application.properties).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    val commonmarkVersion = "0.30.0"
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmarkVersion")
    implementation("org.jsoup:jsoup:${rootProject.extra["jsoupVersion"]}")
    // Marks the record components that are null on the wire; OpenApiSpecTest publishes them as nullable, all others not.
    implementation("org.jspecify:jspecify")
    // Compile-only schema metadata names optional inputs and dynamic response statuses/media; production does not
    // interpret it. OpenApiSpecTest reads it through springdoc.
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    testImplementation(enforcedPlatform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // Derives openapi.json from the controllers and records (OpenApiSpecTest); never on the production classpath.
    testImplementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
}

tasks.test {
    useJUnitPlatform()
    // OpenApiSpecTest compares the committed spec with the derived one, so editing the file alone must rerun it.
    inputs.file("openapi.json")
    testLogging.events("passed", "failed", "skipped")
}
