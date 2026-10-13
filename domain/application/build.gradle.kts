plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `application` layer (`docs/patterns.md`, "Use cases are leaves"): one use case per operation a trigger
// runs, each called only by an entry point (`EntryPointCall`), never by another use case.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:data"))
        }
    }
}
