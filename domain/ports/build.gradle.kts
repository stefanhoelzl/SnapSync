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

// The core'"'"'s `ports` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The need-named I/O boundary. Speaks the domain vocabulary and nothing else.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            // The per-zone library allowlist (`docs/architecture.md`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs), kermit (the engine's diagnostics).
            api(libs.coroutines.core)
            // The one SQLDelight surface a port carries: `Databases` opens with a schema and answers a driver.
            // Runtime interfaces only — no driver, no plugin, no generated code (those are `:domain:services`'
            // and the adapters').
            api(libs.sqldelight.runtime)
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

// Coverage (`docs/architecture.md`, "Coverage"). The report is filtered to this module's OWN classes, so a
// zone is measured on what it contains rather than on its neighbours' test suites. The crediting edges that
// let `:adapter:generic:mock`'s and `:test:feature`'s tests count toward this module are declared in the ROOT
// build file, not here: `ModuleSetTest` asserts a `:domain:*` build file names no module at all, because that
// absence is the precondition for the platform-free compile error.
//
// At ZERO, its floors deleted in the same change. The zone holds declarations only, so what it can count is the
// event ports' handler bundles, measured by the entry mocks' dispatch tests (`EntryDispatchTest`). A member with
// a default body is logic in a declaration zone: it moves to the services, or becomes the adapter's own answer.
