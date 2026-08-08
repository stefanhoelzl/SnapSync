package app.snapsync.membership

import app.snapsync.engine.JOINED_EVENT_KEY

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The re-join marker (capability `event-rejoin-reconciliation`).
 *
 * This three-line class is the **join signal** for the process that cannot use ledger-emptiness as
 * one: the upload extension dies between cycles, and a genuinely zero-row join would never settle if
 * emptiness meant "not joined". So the marker is what decides whether an invocation reconciles
 * against storage or treats the device as freshly joined and re-uploads — the difference between a
 * relaunch costing nothing and a relaunch costing the whole post-cutoff library.
 *
 * It is backed by the shared App-Group suite because **both** upload tiers reconcile and both must
 * agree across an OS-version-driven tier switch. That cross-process property is the last test below:
 * two independent readers of one suite name must see one value, which is what "survives process
 * death" reduces to in a single-process test.
 *
 * Each test uses its own suite name, so nothing here can touch the real `group.app.snapsync` suite of
 * a developer's simulator.
 */
class IosJoinedEventMarkerTest {

    private val suite = "app.snapsync.test." + NSUUID().UUIDString()

    private val marker = IosJoinedEventMarker(suiteName = suite)

    @AfterTest
    fun removeSuite() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    @Test
    fun `a device that has reconciled nothing reads as unmarked`() {
        assertNull(marker.read(), "an absent marker is what a fresh install must present")
    }

    @Test
    fun `the marked event is read back verbatim`() {
        marker.set("3f2504e0-4f89-11d3-9a0c-0305e82c3301")

        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", marker.read())
    }

    @Test
    fun `marking a different event replaces the previous one`() {
        marker.set("event-one")
        marker.set("event-two")

        assertEquals("event-two", marker.read(), "a switch must not leave the old event marked")
    }

    @Test
    fun `clearing returns the device to unmarked`() {
        marker.set("event-one")

        marker.clear()

        assertNull(marker.read(), "a leave must read as unjoined on the next invocation")
    }

    /**
     * The property the App-Group suite is chosen for: whoever holds the `LedgerWriter` writes it, and
     * the other process must see it. A marker stored in a process-local place would read as absent in
     * the extension and re-upload the library on the first OS-scheduled cycle after a tier switch.
     */
    @Test
    fun `a second reader over the same suite sees the marker`() {
        marker.set("event-one")

        assertEquals("event-one", IosJoinedEventMarker(suiteName = suite).read())
    }

    /**
     * The key is OS-held state on a device already in the field. Re-valuing it reads as "never
     * reconciled" on every installed device at once, which costs each of them a full re-upload.
     */
    @Test
    fun `the marker is stored under the key the extension persists`() {
        assertEquals("rejoin.joinedEventId", JOINED_EVENT_KEY)

        marker.set("event-one")

        assertEquals("event-one", NSUserDefaults(suiteName = suite).stringForKey(JOINED_EVENT_KEY))
    }
}
