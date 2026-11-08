plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's `inbound` layer (`docs/architecture.md` §1): how work ENTERS — the `Inbound` kinds every
// event port's handlers are typed as, the `Trigger` naming what started a piece of work, and the structured `Log`
// that reads it. It depends on no module: `ports` depends on it, so an adapter labels its lines the same way.

// Coverage (`docs/architecture.md`, "Coverage"). Measured once the polling tests were gone (3g-b): steady at 99/100
// over five runs — the goal. Branches back at the goal in phase 4.5 (receiving): the suite now reaches both of
// `Log.emit`'s branches (a line written under a trigger and one written outside). Measured 100.00 %. Instructions → 99
// in phase 38 (missing levers and world events). Measured 99.25 %, two runs.
coverage {
    unit()
    // → 97 in phase 5 (clean-up), when every adapter moved onto `Log` and it gained `d` for their platform
    // callbacks' debug lines: the JVM world's mocks write none, so the debug path is the unit suite's (`LogTest`)
    // and the devices'. No test was lost. Measured 97.72 %.
    integration(instructions = 97)
}
