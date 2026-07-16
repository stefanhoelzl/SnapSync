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
include(":app:desktop:ui")
include(":app:ios")
include(":app:ios:photokit-extension")
include(":app:ios:photokit-discovery")
include(":app:ios:url-session-upload")
include(":capability:upload")
include(":capability:upload-url")
include(":capability:album")
include(":capability:config")
include(":capability:device-id")
include(":capability:attest")
include(":capability:event-creation-ui")
include(":capability:join")
include(":capability:membership")
include(":capability:download")
include(":capability:push")
include(":domain:keychain")
include(":domain:logging")
include(":domain:permission")
include(":domain:gallery")
include(":domain:engine")
include(":domain:download-store")
include(":domain:status")
include(":domain:presentation")
include(":domain:ui")
include(":domain:ui:components")
include(":test:architecture")
include(":test:world")
include(":test:integration")
include(":test:harness-driver")
