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
include(":adapter:fake")
include(":adapter:generic")
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
