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
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // The invite as the screen shows and shares it: the text an invite link takes.
            implementation(project(":core:format"))
            // The structured log: what a lookup answered, labelled with the trigger that asked.
            implementation(project(":core:inbound"))
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    unit(alsoCoveredBy = listOf(":test:data"))
    integration() // at the goal since phase 22 claimed the app specs
}
