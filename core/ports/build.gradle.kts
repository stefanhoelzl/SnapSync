plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "The core and its layers").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
}

// The core'"'"'s `ports` zone (`docs/architecture.md`, "The module set: three groups").
// The need-named I/O boundary. Speaks the domain vocabulary and nothing else.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The domain's values a port hands over as they are: the build's store page (`BuildInfo.store`).
            implementation(project(":core:domain"))
            // How work enters: the inbound kinds the event ports' handlers are typed as, and the completion handles.
            implementation(project(":core:inbound"))
            // The per-zone library allowlist (`docs/architecture.md`, "Core purity is closed by
            // default"): coroutines (StateFlow/Flow port shapes), serialization + datetime (the
            // config/manifest vocabulary and cutoff codecs).
            api(libs.coroutines.core)
            // The one SQLDelight surface a port carries: `Databases` opens with a schema and answers a driver.
            // Runtime interfaces only — no driver, no plugin, no generated code (those are `:core:services`'
            // and the adapters').
            api(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage"). Declarations only: what it can count is the event ports' handler
// bundles, measured by the entry mocks' dispatch tests (`EntryDispatchTest`). A member with a default body is logic in
// a declaration layer and moves out.
coverage {
    unit(alsoCoveredBy = listOf(":adapter:generic:mock", ":test:feature"))
    // At the goal since phase 44. What it does not reach is the screens' date formatting (`DateFormatting`, read
    // only by the UI, which this suite renders none of) and the platform answers no member's day gives: a file
    // denied, a VIEW with no URI, a change PhotoKit refuses, a phone without App Attest. No branches: any branch
    // target is met.
    integration()
}
