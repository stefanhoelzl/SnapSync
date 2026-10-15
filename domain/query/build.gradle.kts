plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `query` layer: what the screen reads — the membership as held, the join's and the switch's lookups, and
// the request-keyed statuses the member's requests are answered on. It reads and publishes; it writes no store and
// calls no use case.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:data"))
        }
    }
}
