plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `data` layer (`docs/architecture.md`, "The layers"): the repositories over the ports and the backend's
// protocol over the transport — it TRANSLATES and never decides (`DataShape`): every branch is a domain verdict. The
// old zones depend on it while they move (MIGRATING, `ModuleSetTest`); it never depends on them.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:wire"))
            implementation(project(":domain:inbound"))
            // The read-model cells it keeps (`StateFlow`) and the lock a refresh serializes on.
            api(libs.coroutines.core)
        }
    }
}
