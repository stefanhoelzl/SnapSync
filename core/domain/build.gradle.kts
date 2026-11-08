plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
    // The deployment's product limits, generated as source (`EventWindow`).
    id("snapsync.deployment")
}

// The core's `domain` layer (`docs/architecture.md`, "The core and its layers"): values and pure policy, packaged by
// concept — time, event, sharing, asset, device. It depends on nothing — no project module, no library — so every I/O,
// every port and every format is unresolvable here; `DomainPurity` holds what the standard library still allows. The
// old zones depend on it while they move (MIGRATING, `ModuleSetTest`); it never depends on them.

deployment {
    generateEventWindow(packageName = "app.snapsync.domain.event")
}

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    unit()
    // Branches 64 → 62 in phase 4.1 (trust): the trust verdicts arrived, and their rare answers — a stale
    // challenge, each refusal reason — are their unit suites', not the integration surface's.
    // → 90/76 in phase 31, once the polling tests waited on outcome events instead.
    // Branches → 75 in phase 4.3 (encryption): the key's verdicts arrived, and their rare answers — a key kept for
    // another event, a restore while nothing is joined — are their unit suite's (`EventKeyTest`). → 86/71 with the
    // seal's and the opening's verdicts: a device that cannot name itself, a locked phone, a download fetched for
    // an event a switch left behind and the fourth failure in a row are their unit suite's (`SealVerdictsTest`).
    // → 92/77 in phase 38 (missing levers and world events). Measured 92.31 / 77.35 %, two runs.
    // Branches → 75 in phase 4.10 (diagnostics): the crash channel's and the process account's rules arrived,
    // and their rare answers — a build that reports nowhere, a report naming the build it describes, every exit
    // and hang a platform tallies, a quantity in an unknown unit — are their unit suites' (`ReportingTest`,
    // `CrashShapingTest`, `ProcessMetricRulesTest`). Measured 75.93 %.
    integration(instructions = 92, branches = 75)
}
