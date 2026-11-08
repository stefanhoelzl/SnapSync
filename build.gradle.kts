buildscript {
    // REQUIRED for `buildHealth` (`docs/architecture.md`). dependency-analysis 3.17.0 pins
    // `kotlin-metadata-jvm` at a version whose reader rejects Kotlin 2.4 metadata outright:
    //
    //     Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0.
    //
    // It is a hard failure of `explodeJar*`, not a degraded result — without this force, `buildHealth`
    // cannot run at all. Upstream: autonomousapps#1661 / #1662, both OPEN. (#1724 "Kotlin 2.4.0 support"
    // is closed, but as a duplicate of those — closed is not fixed.)
    //
    // The reader is the only blocker, so forcing the metadata library to the Kotlin version this repo
    // actually uses resolves it. Verified 2026-07-16 (arch-abandoned-v1): with the force, `buildHealth`
    // analyses every multiplatform module and independently reports the dead
    // `:core:status -> :capability:membership` edge. Drop this force once the plugin's pin catches up.
    configurations.classpath {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

// Plugins are declared here (apply false) so every subproject shares ONE classloader for each — the Kotlin Gradle
// Plugin above all. Without this, each subproject loads KGP on its own classloader; the Apple targets' global build
// services (e.g. SwiftPMLockTaskAggregationBuildService) then fail to cast across classloaders — a configuration error
// that only surfaces once iOS targets exist. Every piece of build logic lives in `build-logic/`, applied through
// `snapsync.conventions` (`docs/architecture.md`, "The build").
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.analysis) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
    id("snapsync.conventions")
}
