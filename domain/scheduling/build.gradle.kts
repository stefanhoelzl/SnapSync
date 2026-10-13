plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `scheduling` layer (`docs/architecture.md`, "The layers"): how work RUNS — the entry-point bases every
// delivery goes through, the step runner, single flight, the supervised process scope, the operating system's
// expiry. Pure mechanism: it knows no use case and no trigger. It is the one module of the core that may start a
// coroutine (`CoroutinesInScheduling`).

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:inbound"))
            // The platform's background time, and the per-thread log label a run's lines carry.
            implementation(project(":domain:ports"))
            api(libs.coroutines.core)
        }
    }
}
