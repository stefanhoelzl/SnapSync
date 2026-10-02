plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement and its floors (`docs/architecture.md`, `snapsync.coverage`). Applied
    // here rather than in a `subprojects {}` block so the instrumented set is readable per module.
    id("snapsync.coverage")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core'"'"'s `flow` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The OS-callback trigger flows. Coordinate, never decide; never reach a port.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:feature"))
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
// zone is measured on what it contains rather than on its neighbours' test suites. The crediting edge
// that lets `:adapter:generic:mock`'s tests count toward this module is declared in the ROOT build
// file, not here: `ModuleSetTest` asserts a `:domain:*` build file names no module at all, because
// that absence is the precondition for the platform-free compile error.
//
// Coverage bounds (`docs/architecture.md`). Each number below is a FLOOR that may only RISE:
// lowering one is a regression and needs a stated forcing proof in the PR. Nothing enforces that — it
// is a ratchet carried by this contract, exactly as `docs/architecture.md` carries its ceilings at the
// opposite polarity.
//
// Seeded from MEASUREMENT, never chosen: the number is what this module measured on the commit that
// set it. ENGINE: Kover's default, not JaCoCo — the two disagree by up to 26 points on a single
// package's denominator, so switching engines means re-seeding in that same change. Bounds are whole
// percentages (`minValue` is an `Int`), so each concedes up to one point of its scope.
//
// One package, so no floor rule. Every trigger flow is covered; the residue is one inert default
// lambda on `Provision`.
coverageFloors {
    aggregate(instruction = 97, branch = 85)
}
