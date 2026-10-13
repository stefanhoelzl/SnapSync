plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The wire, generated from `api/openapi.yaml` at build time and never committed (`docs/architecture.md`, "The
    // backend boundary"): Fabrikt's DTOs and the routes, replies and limits, both into `commonMain`.
    id("snapsync.wire")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core's `wire` zone: the HTTP shapes of the backend's API — DTOs, typed routes, sealed replies — and no logic
// beyond reading an answer into its reply. References nothing project-internal; seen by the gateway
// (`:domain:services`) and the backend fake (`:adapter:generic:mock`).
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The DTOs are `@Serializable`, and a reply carries them: consumers decode and build them.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
