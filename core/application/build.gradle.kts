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
    // 95/85 → 87/61 in phase 4.2 (membership): the membership's use cases arrived, and their rare answers — a
    // refusal no screen can produce (a range or a key the join screen already refused), a write another road
    // superseded — are their unit suites' (`:test:data`, zero gate), not the integration surface's. → 86 when the
    // service's faults reached the use cases: each failure's own outcome, told apart there.
    // → 87/65 in phase 31, once the polling tests waited on outcome events instead.
    // → 86/64 in phase 4.3 (encryption): the key's restore arrived, and its rare answers — a key the store refuses,
    // a membership that cannot be read — are its unit suite's (`RestoreEventKeyTest`).
    // → 86/65 in phase 4.4b (uploads): the upload use cases arrived, and their rare answers — a row another
    // uploader took or a walk withdrew since the plan, a listing this build cannot read — are their unit suite's
    // (`UploadUseCasesTest`, zero gate), not the integration surface's.
    // → 88/75 in phase 38 (missing levers and world events). Measured 88.14 / 75.00 %, two runs.
    integration(instructions = 88, branches = 75)
}
