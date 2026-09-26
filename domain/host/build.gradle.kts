plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
}

// The core's host zone (`docs/architecture.md`, "Zones inside the core"; "One shared composition"):
// `snapSyncHost` composes the core (`snapSyncApp`) AND the status host over it, with the host-assembly
// subscriptions. Every root that runs the live app calls it — the iOS shell, and the world (so the control
// channel's JVM host and every world test driving the entry ports) — so the host a test drives
// is the host the phone runs.
//
// The one zone that sees both the composition zone and presentation, which must stay blind to each other: the
// composition zone may not name presentation, and presentation may not name the composition zone. Only the host
// sees both, so neither gains the other. It never sees `flow/` directly.
//
// Every edge is `implementation()`: a consumer that needs the composition or presentation declares it. Of
// `feature/`, only the `readmodel` packages may be named (the read-model import gate).
//
// Wiring only: no conditional (it is scanned as an app shell by detektAppShell), and no test source set — it is
// exercised end to end by every protocol-driven test, which is where "One shared composition" says the wiring
// graph is tested.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:feature"))
            implementation(project(":domain:compose"))
            implementation(project(":domain:presentation"))
            // `StatusContainerHost` is an Orbit `ContainerHost`; presentation does not export Orbit.
            implementation(libs.orbit.core)
            implementation(libs.kermit)
            // The one `CutoffFormatter` is built here, over the display clock and the `TimeZone`.
            implementation(libs.kotlinx.datetime)
            implementation(libs.coroutines.core)
        }
    }
}
