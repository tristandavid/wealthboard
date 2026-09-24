plugins {
    // 8.9.0 is the minimum that can compile against Android 16 (API 36), which
    // Play has required for new apps and updates since 31 Aug 2026. It needs
    // Gradle 8.11.1+ and JDK 17 — the JDK side is already satisfied by the
    // Java 17 compileOptions in app/build.gradle.kts.
    //
    // Deliberately not jumping to the current 9.x line: that's a major version
    // with its own breaking changes, and the goal here is to clear the Play
    // block with the smallest step that does it.
    id("com.android.application") version "8.9.0" apply false
    // Bumped from 1.9.24 alongside the Play Billing Library 8.0.0 update:
    // that artifact's Kotlin metadata is version 2.1.0, which a 1.9.x
    // compiler cannot read ("Module was compiled with an incompatible
    // version of Kotlin"). Kotlin 2.0+ also moves the Compose compiler out
    // of the Kotlin Android plugin and into its own Gradle plugin, so that's
    // added below and composeOptions.kotlinCompilerExtensionVersion in
    // app/build.gradle.kts is no longer used.
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
