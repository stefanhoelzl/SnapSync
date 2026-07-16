package app.snapsync.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The skip-or-leave-or-run gate (capability `event-link`, *An unreadable config is not an absent
 * config*). [CycleGate.NotJoined] runs the leave-side reconciliation, which **clears the
 * `joinedEventId` marker** — so the difference between "unreadable" and "absent" is the difference
 * between a settled join and a false leave on every locked wake.
 */
class CycleGateTest {

    private val host = "https://edge.example"
    private val eventId = "event-1"

    // THE regression. A locked device cannot read the Keychain; that must not clear the join marker.
    @Test
    fun `an unreadable config skips the cycle entirely`() {
        val gate = cycleGate(configReadable = false, eventId = eventId, host = host)

        assertEquals(
            CycleGate.Skip,
            gate,
            "an unreadable config must touch nothing: no reconcile, no marker clear, no jobs",
        )
    }

    @Test
    fun `an unreadable config skips even when no event id is known`() {
        // The eventId is null precisely BECAUSE the config could not be read — inferring "not joined"
        // from that is the false leave.
        assertEquals(CycleGate.Skip, cycleGate(configReadable = false, eventId = null, host = host))
    }

    @Test
    fun `a definitively absent config is NotJoined so the leave side still reconciles`() {
        val gate = cycleGate(configReadable = true, eventId = null, host = host)

        assertEquals(
            CycleGate.NotJoined,
            gate,
            "a real leave must still clear the join marker",
        )
    }

    @Test
    fun `a joined config runs the cycle`() {
        val gate = cycleGate(configReadable = true, eventId = eventId, host = host)

        assertIs<CycleGate.Run>(gate)
        assertEquals(eventId, gate.config.eventId)
        assertEquals(host, gate.config.host)
    }

    @Test
    fun `a missing host is NotJoined as it always has been`() {
        assertEquals(CycleGate.NotJoined, cycleGate(configReadable = true, eventId = eventId, host = null))
        assertEquals(CycleGate.NotJoined, cycleGate(configReadable = true, eventId = eventId, host = ""))
    }

    @Test
    fun `an empty event id is NotJoined`() {
        assertEquals(CycleGate.NotJoined, cycleGate(configReadable = true, eventId = "", host = host))
    }
}
