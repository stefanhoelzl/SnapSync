// The build's convention plugins (`docs/architecture.md`, "Zones inside the core": the allowed targets are
// declared once, here, and applied by every core and `:ui:*` module).
plugins {
    // Provisions a JDK for the plugin's compile: the machine's default Java may be a runtime with no compiler.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
