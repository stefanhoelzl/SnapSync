plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
    // The deployment's product limits, generated as source (`EventWindow`).
    id("snapsync.deployment")
}

// The core's `domain` layer (`docs/architecture.md`, "The core and its zones"): values and pure policy, packaged by
// concept — time, event, sharing, asset, device. It depends on nothing — no project module, no library — so every I/O,
// every port and every format is unresolvable here; `DomainPurity` holds what the standard library still allows. The
// old zones depend on it while they move (MIGRATING, `ModuleSetTest`); it never depends on them.

deployment {
    generateEventWindow(packageName = "app.snapsync.domain.event")
}
