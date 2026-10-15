plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core'"'"'s `model` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The vocabulary, pure codecs and domain services. References only the domain layer (`:domain:domain`), migrating.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            // MIGRATING (`ModuleSetTest`): the domain layer's values, which this zone's types carry until phase 5
            // empties it. The one zone edge this module names.
            implementation(project(":domain:domain"))
            implementation(project(":domain:format"))
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
