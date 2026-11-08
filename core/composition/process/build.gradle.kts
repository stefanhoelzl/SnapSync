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
            implementation(project(":core:domain"))
            implementation(project(":core:inbound"))
            implementation(project(":core:ports"))
            implementation(project(":core:scheduling"))
            implementation(project(":core:data"))
            implementation(project(":core:application"))
            implementation(project(":core:entrypoints"))
        }
    }
}
