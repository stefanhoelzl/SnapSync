plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `entrypoints` layer (`docs/architecture.md`, "The layers"): one entry point per operating-system trigger,
// named for it (`OnHeartbeat`), each a short list of steps that orders and never decides — the overview of what
// happens on every trigger, in three flat files. It sees how work enters and runs, and — for the arguments its
// deliveries carry — the ports' vocabulary; it calls no port (`EntryPointsCallNoPortTest`).

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:inbound"))
            implementation(project(":domain:scheduling"))
            implementation(project(":domain:model"))
            implementation(project(":domain:domain"))
            // The deliveries' arguments are the ports' vocabulary; no port is called from here.
            implementation(project(":domain:ports"))
            // MIGRATING (`ModuleSetTest`'s permitted edges say so): the steps call today's features and services until
            // phase 4 moves them underneath, and each edge goes when nothing here needs it any more.
            implementation(project(":domain:feature"))
            implementation(project(":domain:services"))
            // MIGRATING: the use cases its steps call, and the credential store the extension re-reads at every
            // invocation (until 4.11 composes the extension's own entry).
            implementation(project(":domain:application"))
            implementation(project(":domain:data"))
            implementation(libs.kermit)
        }
    }
}
