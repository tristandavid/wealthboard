import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ no longer bundles the Compose compiler with the Kotlin
    // Android plugin; it moved into this separate plugin, version-matched to
    // the Kotlin version declared in the root build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

/**
 * Release signing, read from a *local, untracked* `keystore.properties` file
 * (see `keystore.properties.example` at the repo root for the format).
 *
 * A real key and its passwords must never be committed, so this file is
 * gitignored and simply isn't present in a fresh checkout or in CI. When it's
 * missing, `hasReleaseSigning` is false and the release build type is left
 * unsigned — `assembleDebug`/CI keep working untouched, and a local
 * `bundleRelease` still succeeds (producing an unsigned .aab, which is enough
 * to sanity-check the build) instead of failing outright. Fill in
 * keystore.properties locally to get a signed, Play-uploadable .aab out of
 * `./gradlew bundleRelease` directly.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "ca.tristan.portfolio"
    compileSdk = 36

    defaultConfig {
        applicationId = "ca.tristan.portfolio"
        minSdk = 26
        // Play requires API 36 for new apps and updates as of 31 Aug 2026.
        // Note this is a behaviour change, not just a number: from API 35 up,
        // edge-to-edge is enforced and there is no opt-out at 36, so the app
        // draws under the status and navigation bars. MainActivity opts in
        // explicitly so that's intentional rather than inherited.
        targetSdk = 36
        versionCode = 15
        versionName = "1.10.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        viewBinding = false
        // AGP 8 stops generating BuildConfig unless asked. The Menu screen
        // stamps the running version in its footer and reads VERSION_NAME /
        // VERSION_CODE from here, so the class has to exist.
        buildConfig = true
    }

    // No composeOptions.kotlinCompilerExtensionVersion here anymore — under
    // Kotlin 2.0+ the org.jetbrains.kotlin.plugin.compose plugin (declared
    // above) picks the matching Compose compiler automatically from the
    // Kotlin version, so this property is obsolete and unused.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // TopAppBar, ExposedDropdownMenuBox and a few other Material3
        // components used throughout ui/screens are marked
        // @ExperimentalMaterial3Api. Opting in here (once, project-wide)
        // avoids annotating every screen file individually.
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            // Pager (HorizontalPager / rememberPagerState / PagerState), used by
            // the Upcoming Dividends deck on the Dividends tab. Opted in here
            // rather than annotating the composable, for the same reason as the
            // Material3 line above: the alternative is an @OptIn on every
            // function that touches one, and the next screen to use a pager
            // fails the build again.
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

/**
 * Compile on JDK 21, whatever JDK Gradle itself is running on.
 *
 * Kotlin 1.9.24 bundles a copy of IntelliJ's platform utilities whose
 * Java-version parser predates JDK 24. Run the compiler on a newer JDK and it
 * throws `IllegalArgumentException: <your java version>` out of
 * `JavaVersion.parse` the moment KSP touches its memory-mapped incremental
 * caches, and the build dies with an "Internal compiler error" that names
 * nothing in this project. Current Android Studio ships a JetBrains Runtime
 * 25, so out of the box that is exactly what happens.
 *
 * Setting the Gradle JDK in the IDE fixes it too, but only for whoever set it
 * — and a Gradle/Kotlin daemon started under the old JDK survives the change
 * until it is stopped. Declaring the toolchain here makes the requirement part
 * of the build instead of part of someone's IDE configuration: Gradle launches
 * the Kotlin compile daemon on the right JDK regardless of what started the
 * build.
 *
 * Why 21 and not 17. The usable range is "at least 17" (AGP 8.9's floor) and
 * "below 24" (where Kotlin's parser gives out) — 17 and 21 both qualify, so
 * the question is only which one is likelier to already be on the machine.
 * Android Studio installs a JetBrains Runtime 21 into ~/.jdks as a matter of
 * course and Gradle auto-detects that directory, whereas a 17 usually has to
 * be fetched on purpose. Asking for 17 meant a machine with a perfectly good
 * 21 sitting in ~/.jdks still failed with "cannot find a Java installation
 * matching {languageVersion=17}", and the standard cure for that — the Foojay
 * download resolver — is itself broken on Gradle 9 (see settings.gradle.kts).
 * Asking for what is already there sidesteps all of it.
 *
 * This is only which JDK RUNS the compiler. The bytecode target stays 17, set
 * by `compileOptions` and `kotlinOptions.jvmTarget` above; nothing about the
 * shipped app changes.
 *
 * If Gradle still reports it cannot find a matching installation, add one in
 * Android Studio: Settings → Build, Execution, Deployment → Build Tools →
 * Gradle → Gradle JDK → Download JDK… → 21. Gradle auto-detects the JDKs the
 * IDE downloads, so that one step satisfies this.
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    // OS-level splash (SplashScreen API back-port to API 26+). Shows the
    // brand background + launcher mark immediately at process start, before
    // Compose has anything on screen, then hands off to SplashScreen.kt's
    // animated version — see MainActivity.
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Declared outright rather than leaned on transitively through material3:
    // the Upcoming Dividends deck uses androidx.compose.foundation.pager, and
    // an implicit dependency is not something a UI feature should rest on.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Biometric app lock
    implementation("androidx.biometric:biometric:1.1.0")

    // Google Play Billing — Premium subscriptions
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // Networking for Yahoo Finance quotes
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Image loading for news thumbnails (Apple News-style feed cards)
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.json:json:20240303")

    // Security (encrypted prefs for PIN salt/hash, if used)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // No ad network dependency here. AdMob (play-services-ads) and its GDPR
    // consent SDK (user-messaging-platform) were removed after the AdMob
    // account behind this app was disabled for invalid activity and Google's
    // appeal decision came back final — see ads/AdManager.kt's git history
    // for the removed implementation. Re-add whichever SDK a replacement
    // network needs (e.g. AppLovin MAX) when that integration happens.

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
