plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's composition layer, its shared half (`docs/architecture.md`, "The composition layer"): what every process
// wires — its ports, its first act, and the upload's layers over it. WIRING ONLY: graph classes of `val`s built by
// constructor calls (`CompositionIsWiringTest`). It sees every layer below presentation, and no old zone.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:inbound"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:scheduling"))
            implementation(project(":domain:data"))
            implementation(project(":domain:application"))
            implementation(project(":domain:entrypoints"))
        }
    }
}
