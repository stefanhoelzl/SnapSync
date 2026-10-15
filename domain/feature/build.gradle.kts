plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core'"'"'s `feature` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The rules. Features are mutually blind; they coordinate via one-writer durable state behind shared services.
// Features see services, never ports: there is no `:domain:ports` edge here, so a port type does not resolve in this
// zone — the compiler holds the law, not a scan. A feature test that needs a port's mock lives in `:test:feature`.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:format"))
            // The shared capabilities features stand on: the storage, gallery, transfer and backend services.
            implementation(project(":domain:services"))
            // MIGRATING: the trust concept the features read — the device identity, the refusal's facts — lives in
            // data.
            implementation(project(":domain:data"))
            // How work runs: an import outlives the drain that started it, and a wake's re-arm reads how its passes
            // ended.
            implementation(project(":domain:scheduling"))
            // The per-zone library allowlist (`docs/architecture.md`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs), kermit (the engine's diagnostics).
            api(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
