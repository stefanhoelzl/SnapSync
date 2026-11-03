plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core's `services` zone (`docs/architecture.md`, "Zones inside the core"): the shared capabilities
// built over the thin ports — what a store holds, when it is opened, what a failure means. Between `ports`
// and `feature`: it sees the vocabulary and the ports, never a feature.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.
//
// Its tests that need a real SQLite database live beside the `Databases` adapters (`:adapter:generic:app`
// jvmTest, `:adapter:ios:ext-safe` iosTest), because a `:domain:*` build file names no module; the crediting
// edge that counts them here is in the root build file.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:format"))
            // MIGRATING: the backend gateway and the device identity the need-shaped services stand on live in data.
            implementation(project(":domain:data"))
            implementation(project(":domain:ports"))
            // How work enters: the structured log's lines, which the process's writers render onto its sinks.
            implementation(project(":domain:inbound"))
            // The backend's HTTP shapes (`api/openapi.yaml`): the gateway sends its routes and reads its replies.
            implementation(project(":domain:wire"))
            // The per-zone library allowlist (`docs/architecture.md`): coroutines (the stores' `Flow` shapes),
            // the SQLDelight runtime (the stores' transactions), kermit (the stores' own diagnostics),
            // serialization-json.
            api(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kermit)
            // The event-album map's JSON encoding (no generated serializers: the map's builtins only).
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // A date-formatting double formats a `LocalDateTime`, the value `DateFormats` takes.
            implementation(libs.kotlinx.datetime)
        }
    }
}
