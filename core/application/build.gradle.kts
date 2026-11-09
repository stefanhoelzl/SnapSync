plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `application` layer (`docs/patterns.md`, "Use cases are leaves"): one use case per operation a trigger
// runs, each called only by an entry point (`EntryPointCall`), never by another use case.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // The structured log: an outcome a use case decided is written where it is decided.
            implementation(project(":core:inbound"))
            // A timestamp's text: a bug report spells the membership's window with it.
            implementation(project(":core:format"))
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    // `:test:feature` beside `:test:data` since the join-time load moved here (4.9): its suite runs over the
    // feature tests' ledger and scripted backend.
    unit(alsoCoveredBy = listOf(":test:data", ":test:feature"))
    integration() // at the goal since phase 43 (coverage to the goal)
}
