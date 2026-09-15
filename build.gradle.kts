// Top-level build file — plugin versions declared here, applied in :app.
//
// Held on the 8.x line of AGP deliberately. 8.13.2 is the last of it and supports API 36 in full,
// which is all the Play Store asks for; AGP 9 is a major version whose migration (built-in Kotlin,
// removed DSL) buys this project nothing it needs. Gradle 8.14.5 is what AGP 8.13 wants, and it
// still runs on Android Studio's bundled JBR 21 — no JDK change.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
