package app.snapsync.feature.upload

import app.snapsync.model.UploadMechanism

/**
 * Mechanism identity → the instance that runs it (capability `upload-lifecycle`, "The upload mechanism
 * is resolved, never selected") — the second half of resolution, and the half that carries policy.
 *
 * `resolveUploadMechanism` in `model/` answers *which kind*; this answers *which object*, and the
 * mapping is not mechanical: each cell wraps the other mechanism's relinquish, with deliberately
 * asymmetric content (see [RelinquishThenRun]). Giving up the OS-driven mechanism is **deregistration
 * only** — its full `stop()` would wipe ledger rows the incoming mechanism is about to reconcile
 * precisely — while giving up the app-driven one is exactly its ordinary `stop()`.
 *
 * **It lives here rather than in `compose/` because the guard has to be able to drive the real thing.**
 * `ProducerExclusivityTest` used to hold a hand-typed copy of this table, described in its own doc as
 * "mirroring the one `compose/` builds" — a second wiring, which is the failure "One shared composition"
 * names (*"a wiring difference is impossible rather than undetected"*). The copy had already drifted:
 * it keyed the wrap on the OS fact while the composition keyed it on whether the OS-driven mechanism was
 * constructed, two facts nothing forced to agree ([requireConsistent] below now does).
 *
 * **[osDriven] is passed, never constructed here.** Building it would give resolution the side effect of
 * constructing a mechanism it is only asking about, on an OS that may not carry one at all.
 *
 * The returned function is called per transition and MUST return the *same* instance for a kind each
 * time: the app-driven mechanism owns a background `URLSession` whose identifier must stay stable for the
 * OS to re-adopt across launches, and whose invalidation is terminal — an uncatchable `NSException`
 * (`ios-url-session-upload`, "Cancellation never invalidates the background session"). So resolving away
 * and back returns what already exists; "obtain the mechanism for this kind" never means "construct a
 * second one".
 */
fun uploadMechanismTable(
    /** The OS-driven mechanism where this OS carries one; `null` elsewhere. */
    osDriven: UploadMechanismRuntime?,
    /** The app-driven mechanism — always present (it serves iOS 18–26.0 fully, and every OS under a
     *  partial grant). */
    appDriven: UploadMechanismRuntime,
    /** Deregister a surviving OS-driven registration — **deregistration only**, no ledger clear and no
     *  cursor reset. Inert where no such registration can exist. */
    relinquishOsRegistration: suspend () -> Unit,
): (UploadMechanism) -> UploadMechanismRuntime {
    val appDrivenHere =
        if (osDriven == null) appDriven else RelinquishThenRun(relinquishOsRegistration, appDriven)
    val osDrivenHere = osDriven?.let { RelinquishThenRun({ appDriven.stop() }, it) }
    return { kind ->
        when (kind) {
            // On an OS that carries no OS-driven mechanism this answers [IdleUploadMechanism] — "there is
            // no such mechanism here" — where it used to answer `appDrivenHere`, silently naming a
            // DIFFERENT mechanism. The two are behaviourally identical on the one path that reaches this
            // cell, and only one of them is true.
            //
            // THE CELL IS REACHED, ON EVERY DEVICE BELOW iOS 26.1. Not by resolution — resolution clamps
            // PHOTOKIT to what the device can run — but by `UploadArm.stopAll`, which maps the WHOLE enum
            // through this table on leave and on a download-only provision, deliberately: a process that
            // just launched has started nothing while work it never started may still be running on its
            // behalf, so it stops everything nameable rather than what it remembers starting. Asking for
            // a kind this OS cannot run is that enumeration working as intended, and the honest answer to
            // "give me the OS-driven mechanism" on a device that has none is a mechanism that declines and
            // still answers — which is what [IdleUploadMechanism] is for. `stopAll` de-duplicates, so
            // stopping it costs nothing and the app-driven mechanism is still stopped exactly once via
            // its own cell.
            //
            // Substituting the app-driven mechanism here was safe for `stopAll` and for nothing else: had
            // a resolver bug ever let `switchTo` reach this cell, the arm would have STARTED a mechanism
            // other than the one it resolved, unwrapped by its relinquish (`appDrivenHere` is bare when
            // [osDriven] is null) and reported as PHOTOKIT in every log line — a collapse justified for
            // one cause that absorbed a materially different one (`module-architecture`, "Absence is
            // never silent"). Idle keeps `stopAll` working and makes that second cause inert.
            UploadMechanism.PHOTOKIT -> osDrivenHere ?: IdleUploadMechanism
            UploadMechanism.URL_SESSION -> appDrivenHere
            UploadMechanism.IDLE -> IdleUploadMechanism
        }
    }
}

/**
 * The two facts [uploadMechanismTable] depends on agreeing: whether this OS **carries** the OS-driven
 * mechanism (the resolver's input) and whether one was **constructed** (this table's input).
 *
 * They are supplied separately and deliberately — deriving presence from the thunk would construct a
 * mechanism the composition is only asking about — so nothing but this makes them agree. A composition
 * claiming support without a mechanism resolves `PHOTOKIT` and then has nothing to run; one supplying a
 * mechanism while claiming no support has built a mechanism resolution can never name. Both are wiring
 * mistakes, both used to be absorbed silently, and both are unreachable from a running device: it is the
 * *next* composition — a second platform, a harness, a shell refactor — this catches, at assembly, which
 * is the only moment the answer is still cheap.
 */
fun requireConsistent(osSupportsOsDrivenUpload: Boolean, osDriven: UploadMechanismRuntime?) {
    check(osSupportsOsDrivenUpload == (osDriven != null)) {
        "upload mechanism wiring disagrees with itself: osSupportsOsDrivenUpload=" +
            "$osSupportsOsDrivenUpload but the OS-driven mechanism is " +
            (if (osDriven == null) "absent" else "present") +
            " — resolution reads the first and this table holds the second"
    }
}
