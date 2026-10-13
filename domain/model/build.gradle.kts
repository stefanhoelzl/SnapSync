plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The zero coverage gate (`docs/architecture.md`, "Coverage"): no instruction and no branch missed.
    id("snapsync.coverage-zero")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
    // The deployment's values compiled in (`LINK_ORIGIN`).
    id("snapsync.deployment")
}

// The core'"'"'s `model` zone (`docs/architecture.md`, "The module set withholds; packages organize").
// The vocabulary, pure codecs and domain services. References only the domain layer (`:domain:domain`), migrating.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

// The event link's origin, generated from the RESOLVED DEPLOYMENT (`docs/deployment.md`) by `snapsync.deployment`, so
// the app, the backend, the iOS project and the site all derive it from one declared value rather than each holding a
// copy.
deployment {
    generateLinkOrigin(packageName = "app.snapsync.model")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // MIGRATING (`ModuleSetTest`): the domain layer's values, which this zone's types carry until phase 5
            // empties it. The one zone edge this module names.
            implementation(project(":domain:domain"))
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

// Coverage (`docs/architecture.md`): at zero, under `snapsync.coverage-zero`. The crediting edges that let
// `:adapter:generic:mock`'s and `:test:feature`'s tests count toward this module are declared in the ROOT build file,
// not here: `ModuleSetTest` holds this file to the one zone edge it may name (the domain layer), because that is the
// precondition for the platform-free compile error. What the gate excuses here is the serialization plugin's zero-mask
// missing-field check in the `@Serializable` classes whose every field has a default; the task prints each one.
