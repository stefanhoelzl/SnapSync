pluginManagement {
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
include(":app:ios")
include(":app:ios:extension")
// Built only under `-Psnapsync.forge=true`; included unconditionally so the module set is stable and
// `ModuleSetTest` has one answer rather than a property-dependent one.
include(":app:ios:forge")
include(":adapter:generic:app")
include(":adapter:generic:fake")
include(":adapter:ios:ext-safe")
include(":adapter:ios:app-only")
include(":domain")
include(":ui:presentation")
include(":ui:screens")
include(":ui:components")
include(":test:architecture")
include(":tools:diagrams")
include(":test:world")
include(":test:integration")
include(":test:harness-driver")
include(":test:rig")
