plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `inbound` layer (`docs/architecture.md`, "The layers"): how work ENTERS — the `Inbound` kinds every
// event port's handlers are typed as, the `Trigger` naming what started a piece of work, and the structured `Log`
// that reads it. It depends on no module: `ports` depends on it, so an adapter labels its lines the same way.

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The log's severities are Kermit's, so the old zones' Kermit lines and `Log`'s reach the same sinks
            // through one vocabulary.
            api(libs.kermit)
        }
    }
}
