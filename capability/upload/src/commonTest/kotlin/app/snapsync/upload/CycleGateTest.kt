package app.snapsync.upload

import app.snapsync.gallery.Contribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The skip-or-leave-or-run gate (capability `event-link`, *An unreadable config is not an absent
 * config*). [CycleGate.NotJoined] runs the leave-side reconciliation, which **clears the
 * `joinedEventId` marker** — so the difference between "unreadable" and "absent" is the difference
 * between a settled join and a false leave on every locked wake.
 *
 * The gate is tier-neutral by construction: it takes primitives, so the same decision is reached
 * whether the OS invoked the cycle or the app did. It used to be reached in the OS-invoked tier's
 * composition root, which is why the app-driven tier did not reach it at all.
 */
class CycleGateTest {

    private val host = "https://edge.example"
    private val eventId = "event-1"
    private val cutoff = "2026-07-01T00:00:00Z"

    private fun joined(
        eventId: String = this.eventId,
        contribution: Contribution = Contribution.Since(cutoff),
        saveToAlbum: Boolean = false,
    ) = JoinedMembership(eventId = eventId, contribution = contribution, saveToAlbum = saveToAlbum)

    // THE regression. A locked device cannot read the Keychain; that must not clear the join marker.
    @Test
    fun `an unreadable config skips the cycle entirely`() {
        val gate = cycleGate(configReadable = false, membership = joined(), host = host)

        assertIs<CycleGate.Skip>(
            gate,
            "an unreadable config must touch nothing: no reconcile, no marker clear, no jobs",
        )
    }

    @Test
    fun `an unreadable config skips even when no membership is known`() {
        // The membership is null precisely BECAUSE the config could not be read — inferring "not
        // joined" from that is the false leave.
        assertIs<CycleGate.Skip>(cycleGate(configReadable = false, membership = null, host = host))
    }

    // The identity half of the roll-up. `configReadable` covers EVERY protected read the cycle needs,
    // not only the config: an unresolvable device id is "I could not look", never "no id", and the
    // reconciler and manifest producer both close over it — so even the leave-side branch needs it.
    @Test
    fun `an unresolvable device id skips exactly as an unreadable config does`() {
        // The config read succeeded and found a joined event; only the identity probe failed.
        val configRead = true
        val deviceIdReadable = false

        val gate = cycleGate(
            configReadable = configRead && deviceIdReadable,
            membership = joined(),
            host = host,
        )

        assertIs<CycleGate.Skip>(
            gate,
            "an unresolvable identity must not reach NotJoined — that would clear the marker of a " +
                "device that never left",
        )
    }

    @Test
    fun `the skip carries the root's forensics verbatim`() {
        // The decision is made in shared code that cannot see WHY the read failed; the root supplies it
        // so the device log keeps one line rather than two across two files.
        val detail = "config status=-25308, deviceId readable=false"

        val gate = cycleGate(configReadable = false, membership = null, host = host, skipDetail = detail)

        assertIs<CycleGate.Skip>(gate)
        assertEquals(detail, gate.detail)
    }

    @Test
    fun `a definitively absent config is NotJoined so the leave side still reconciles`() {
        val gate = cycleGate(configReadable = true, membership = null, host = host)

        assertEquals(
            CycleGate.NotJoined,
            gate,
            "a real leave must still clear the join marker",
        )
    }

    @Test
    fun `a joined config runs the cycle and carries the membership through`() {
        val gate = cycleGate(
            configReadable = true,
            membership = joined(contribution = Contribution.Since(cutoff), saveToAlbum = true),
            host = host,
        )

        assertIs<CycleGate.Run>(gate)
        assertEquals(eventId, gate.config.eventId)
        assertEquals(host, gate.config.host)
        // The cycle's selection inputs arrive WITH the decision — there is no second read, and nothing
        // downstream has to invent a cutoff for a membership that may not exist.
        assertEquals(Contribution.Since(cutoff), gate.membership.contribution)
        assertEquals(true, gate.membership.saveToAlbum)
    }

    // A non-contributing membership still RUNS the gate — the direction gate lives one step further in,
    // inside `UploadCycle.run()`, so the cycle can decline it after the read rather than before.
    @Test
    fun `a non-contributing membership is Run and declines later at the direction gate`() {
        val gate = cycleGate(
            configReadable = true,
            membership = joined(contribution = Contribution.None),
            host = host,
        )

        assertIs<CycleGate.Run>(gate)
        assertEquals(Contribution.None, gate.membership.contribution)
    }

    @Test
    fun `a missing host is NotJoined as it always has been`() {
        assertEquals(CycleGate.NotJoined, cycleGate(configReadable = true, membership = joined(), host = null))
        assertEquals(CycleGate.NotJoined, cycleGate(configReadable = true, membership = joined(), host = ""))
    }

    @Test
    fun `an empty event id is NotJoined`() {
        assertEquals(
            CycleGate.NotJoined,
            cycleGate(configReadable = true, membership = joined(eventId = ""), host = host),
        )
    }
}
