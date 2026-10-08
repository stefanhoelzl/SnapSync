plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // Coverage at ZERO (`docs/architecture.md`, "Coverage"; `snapsync.coverage-zero`): no instruction or branch may be
    // missed. Applied here rather than in a `subprojects {}` block so the instrumented set is readable per module.
    id("snapsync.coverage-zero")
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
            // The shared capabilities features stand on: the storage, gallery, transfer and backend services.
            implementation(project(":domain:services"))
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

// Coverage (`docs/architecture.md`). The report is filtered to this module's OWN classes, so a
// zone is measured on what it contains rather than on its neighbours' test suites. The crediting edges
// that let `:adapter:generic:mock`'s and `:test:feature`'s tests count toward this module are declared in
// the ROOT build file, not here: `ModuleSetTest` asserts a `:domain:*` build file names no module at all,
// because that absence is the precondition for the platform-free compile error.
