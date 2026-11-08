plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "The core and its layers").
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
// (`:core:services`) and the backend fake (`:adapter:generic:mock`).
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

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    skipped(
        "generated from api/openapi.yaml (`snapsync.wire`): the routes, replies and limits, and the DTOs; the " +
            "Backend contract runs the real gateway over them",
        packages = listOf("app.snapsync.wire.api", "app.snapsync.wire.models"),
    )
    unit()
    // → 81 in phase 5 (clean-up), when the runtime's helpers moved onto `Wire` (no top-level functions in the
    // core): the same code, measured as one object. No test was lost. Measured 81.90 %.
    integration(instructions = 81, branches = 71)
}
