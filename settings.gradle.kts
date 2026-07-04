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
include(":capability:config")
include(":capability:device-id")
include(":capability:event-creation-ui")
include(":capability:rejoin")
include(":capability:download")
include(":domain:permission")
include(":domain:gallery")
include(":domain:engine")
include(":domain:download-store")
include(":domain:status")
include(":domain:presentation")
include(":domain:ui")
include(":domain:ui:components")
include(":test:world")
include(":test:integration")
