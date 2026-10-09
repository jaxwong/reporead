plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("org.springframework.boot") version "4.1.1" apply false
}

extra["jsoupVersion"] = "1.23.2"
