plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `entrypoints` layer (`docs/architecture.md` §1): one entry point per operating-system trigger,
// named for it (`OnHeartbeat`), each a short list of steps that orders and never decides — the overview of what
// happens on every trigger, in three flat files. It sees how work enters and runs, and — for the arguments its
// deliveries carry — the ports' vocabulary; it calls no port (`EntryPointsCallNoPortTest`).

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:inbound"))
            implementation(project(":domain:scheduling"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:query"))
            // The deliveries' arguments are the ports' vocabulary; no port is called from here.
            implementation(project(":domain:ports"))
            // MIGRATING (`ModuleSetTest`'s permitted edges say so): the steps call today's services until phase 4 moves
            // them underneath, and the edge goes when nothing here needs it any more.
            // MIGRATING: the use cases its steps call, and the credential store the extension re-reads at every
            // invocation (`OnProcess`, built by the extension's own composition).
            implementation(project(":domain:application"))
            implementation(project(":domain:data"))
            // Host assembly — a person reached the app — builds the status host and shows its states (`AssembleHost`).
            implementation(project(":domain:presentation"))
            implementation(project(":ui:state"))
            // `StatusContainerHost` is an Orbit `ContainerHost`; presentation does not export Orbit.
            implementation(libs.orbit.core)
        }
    }
}
