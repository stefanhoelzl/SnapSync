package app.snapsync.rig

import app.snapsync.mock.MockedSystem

// What a typed service needs of the world it is served in (`docs/testing.md`, "Typed services"): a service over a system
// this host runs REAL is refused, with that reason, in the advertisement and at the call.

/** What a typed service needs before a host can honour it (`LeverDispatch.kt`). */
internal sealed interface Need {
    /** The system must be one this host's app runs over as a mock. */
    data class Mocked(val system: MockedSystem) : Need

    /** The backend's public surface must be reachable from the channel. */
    data object Reach : Need

    /** The backend must be the mock, whose operator this lever is. */
    data object Operator : Need

    /** The build's declared version must be a cell the channel may change. */
    data object Version : Need

    /** Nothing but a lever may move the clock: no loop owns it. */
    data object ClockUnowned : Need

    /** The app must be composed over the process-metric reports' mock. */
    data object MetricsPlayed : Need
}

/** Why something needing [needs] — named [name] in the refusal — cannot be honoured here, or `null`. */
internal fun MockWorld.refusalFor(needs: List<Need>, name: String): String? = needs.firstNotNullOfOrNull { need ->
    when (need) {
        is Need.Mocked -> refusalFor(need.system)
        Need.Reach -> reachRefusal.takeIf { reach == null }
        Need.Operator -> operatorRefusal(name).takeIf { !isMocked(MockedSystem.BACKEND) }
        Need.Version -> (
            "this build's declared version is baked, not a cell the channel may change; it is one only where the " +
                "backend is the mock, which reads it"
            ).takeIf { version == null }
        Need.ClockUnowned -> clockOwner
        Need.MetricsPlayed -> metricsRefusal
    }
}
