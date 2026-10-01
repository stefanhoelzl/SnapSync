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

rootProject.name = "snapsync"

include(":app:desktop")
include(":app:jvm")
include(":app:ios")
include(":domain:host")
include(":app:ios:extension")
include(":app:android")
include(":adapter:generic:app")
include(":adapter:generic:mock")
include(":adapter:generic:sentry")
include(":adapter:ios:ext-safe")
include(":adapter:ios:app-only")
include(":adapter:ios:ui")
include(":adapter:android")
include(":domain:model")
include(":domain:ports")
include(":domain:services")
include(":domain:feature")
include(":domain:flow")
include(":domain:compose")
include(":domain:presentation")
include(":ui:screens")
include(":ui:components")
include(":test:architecture")
include(":tools:diagrams")
include(":test:contracts")
include(":test:integration")
include(":test:harness-driver")
include(":test:rig")
include(":test:launch-adapters")
include(":test:edge")
include(":test:control")
include(":test:feature")
