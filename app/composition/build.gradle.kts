plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (spec `module-architecture`, "Zones inside the core").
    id("snapsync.targets")
}

// The SHARED HOST COMPOSITION (`docs/architecture.md`, "One shared composition"): `snapSyncHost` composes
// the core (`snapSyncApp`) AND the status host over it, with the host-assembly subscriptions. Every root that
// runs the live app calls it — the iOS shell, and the world (so the control channel's JVM host, the inbound-port
// contract fixtures and every test driving them) — so the host a test drives is the host the phone runs.
//
// Its own module because it is the one join of two sides that must stay blind to each other: the core's
// composition zone may not name presentation, and presentation may not name the composition zone. Only this
// module sees both, so neither gains the other ("The module set withholds; packages organize").
//
// Wiring only: no conditional (it is scanned as an app shell), and no test source set — it is exercised end to
// end by every protocol-driven test, which is where "One shared composition" says the wiring graph is tested.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:compose"))
            api(project(":ui:presentation"))
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:feature"))
            implementation(libs.kermit)
        }
    }
}
