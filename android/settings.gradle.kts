pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

/*
 * No toolchain-download plugin here, deliberately.
 *
 * The obvious answer to "Gradle can't find the JDK the build asks for" is to
 * add the Foojay resolver so Gradle downloads one. That was tried and it
 * fails on Gradle 9.x:
 *
 *     Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member
 *     field 'org.gradle.jvm.toolchain.JvmVendorSpec IBM_SEMERU'
 *
 * Gradle 9 removed that constant and the resolver still references it. Rather
 * than chase a plugin version that matches this Gradle, the build now asks for
 * a JDK that Android Studio already ships and installs — see the toolchain
 * note in app/build.gradle.kts. Nothing needs downloading, so nothing needs
 * resolving.
 */
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WealthBoard"
include(":app")
