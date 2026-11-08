plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `scheduling` layer (`docs/architecture.md` §1): how work RUNS — the entry-point bases every
// delivery goes through, the step runner, single flight, the supervised process scope, the operating system's
// expiry. Pure mechanism: it knows no use case and no trigger. It is the one module of the core that may start a
// coroutine (`CoroutinesInScheduling`).

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:inbound"))
            // The platform's background time, and the per-thread log label a run's lines carry.
            implementation(project(":core:ports"))
            // `InvalidProgramState`: the one failure the runtime's containment passes on, ending the process.
            implementation(project(":core:domain"))
            api(libs.coroutines.core)
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage"). Measured once the polling tests were gone and a behaviour ended quiet
// (3g-b), steady at 95.7/77.4 over five runs; before that, a teardown cut whatever pass a test left running, and
// `SingleFlight`'s withdrawals moved between runs. The branches a member's day never takes are the runtime's rare
// paths, their unit suites' (`SingleFlightTest`, `EntryPointTest`): a waiter withdrawing from a queued pass or from one
// another waits for, a yield chain, a fatal program state ending the process, an expiry whose grace runs out,
// outliving work started outside any entry point. → 93/76 in phase 4.5 (receiving): the runtime's failure paths — a
// step, a run, a delivery or unawaited work that threw — were reached by the old download zone's throws; the layers
// that replaced it
// throw nothing and a port answers a fault as a value, so no member's day reaches them any more: they are the unit
// suites' (`StepsTest`, `EntryEventsTest`). Measured 93.24 / 76.63 %. → 92/74 in phase 4.9 (status and presentation):
// the grant and selection followers became entry points, so no member's day starts unawaited work outside a run any
// more (`Runtime.detached`'s own-run path), and the runtime path a tap's intents took went with the tap tracking: both
// are the unit suites' now. Measured 92.88 / 74.76 %.
coverage {
    unit()
    // → 91/72 in phase 5 (clean-up), when the root scope's handler, the transfer session's drain counter and the
    // membership change's exclusion moved in from model and services: a root's uncaught failure and a sweep that
    // meets a join are the unit suite's (`RootScopeFailuresTest`, `ExclusionTest`). No test was lost. Measured
    // 91.43 / 72.35 %.
    integration(instructions = 91, branches = 72)
}
