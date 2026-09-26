package app.snapsync.world

import app.snapsync.model.CycleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload extension's entry port (`ExtensionHost`) delivering to the handlers its composition registers
 * (capability `background-upload`). These were the `ExtensionEntriesContract` clauses while the extension crossed an
 * inbound port; each invocation is a handler of an event port now, so what it promises is pinned here and the
 * platform's raw answer beside the iOS adapter.
 *
 * `process` answers the operating system with how the cycle ended, so a cycle that did work must say "call me again"
 * while it waits on transfers, and one with nothing to do must let the system rest. `onTerminate` has no test,
 * deliberately: it records a line and changes nothing a test could observe.
 */
class ExtensionEntryWorldTest {

    private val joined = "22222222-2222-4222-8222-222222222222"

    @Test
    fun process_starts_a_new_upload_and_asks_again() = worldTest {
        val w = World(this)
        w.provision(joined)
        w.addOwnAsset("A")
        val result = w.composeExtension().process()
        assertTrue(w.platform.created.isNotEmpty(), "the new photo's upload is started")
        assertEquals(CycleResult.PROCESSING, result, "and the system is asked to call again while it is in flight")
    }

    @Test
    fun process_with_nothing_new_completes() = worldTest {
        val w = World(this)
        w.provision(joined)
        assertEquals(CycleResult.COMPLETED, w.composeExtension().process())
        assertTrue(w.platform.created.isEmpty(), "nothing is uploaded")
    }

    @Test
    fun process_without_a_membership_declines() = worldTest {
        val w = World(this)
        assertEquals(CycleResult.SKIPPED, w.composeExtension().process())
        assertTrue(w.platform.created.isEmpty(), "nothing is uploaded")
    }
}
