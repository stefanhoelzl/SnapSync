pluginManagement {
    // The convention plugins (`snapsync.targets`: the allowed targets, `docs/architecture.md`).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Auto-provisions the JDK toolchain (no manual JDK install needed).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// The build-logic build again, as a build of LIBRARIES: one included only under `pluginManagement` contributes plugins
// only, and every detekt task loads its `:detekt-rules` (`app.snapsync.buildlogic:detekt-rules`, substituted by
// coordinates).
includeBuild("build-logic")

rootProject.name = "snapsync"

include(":app:desktop")
include(":app:jvm")
include(":app:ios")
include(":app:ios:extension")
include(":app:android")
include(":adapter:generic:app")
include(":adapter:generic:mock")
include(":adapter:generic:motionphoto")
include(":adapter:generic:sentry")
include(":adapter:ios:ext-safe")
include(":adapter:ios:app-only")
include(":adapter:ios:ui")
include(":adapter:android")
include(":core:domain")
include(":core:format")
include(":core:data")
include(":core:application")
include(":core:query")
include(":core:inbound")
include(":core:scheduling")
include(":core:entrypoints")
include(":core:ports")
include(":core:wire")
include(":core:composition:process")
include(":core:composition:extension")
include(":core:composition:app")
include(":core:presentation")
include(":ui:screens")
include(":ui:components")
include(":ui:state")
include(":test:architecture")
include(":tools:diagrams")
include(":test:contracts")
include(":test:integration")
include(":test:harness-driver")
include(":test:rig")
include(":test:launch-adapters")
include(":test:edge")
include(":test:client")
include(":test:control")
include(":test:verifies")
include(":test:journeys")
include(":test:cli")
include(":test:levers")
include(":test:feature")
include(":test:data")
include(":test:partial-grant")
include(":test:no-grant")
