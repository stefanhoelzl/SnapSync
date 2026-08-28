package app.snapsync.feature.upload

import app.snapsync.model.UploadMechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * Resolution's **second half** (capability `upload-lifecycle`, "The upload mechanism is resolved, never
 * selected"): `resolveUploadMechanism` answers *which kind*, and this answers *which object* — wrapping
 * each cell in the deliberately asymmetric cross-mechanism relinquish.
 *
 * `ProducerExclusivityTest` drives this table too, over ~93k transition sequences, but it is an
 * architecture guard in another module: it asserts the ARM's exclusivity invariant and reaches the table
 * on the way. These are the table's own cells, asserted directly and where the code lives.
 */
class UploadMechanismTableTest {

    private class Recording(private val name: String, private val log: MutableList<String>) :
        UploadMechanismRuntime {
        override suspend fun start() { log += "$name.start" }
        override suspend fun stop() { log += "$name.stop" }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
    }

    private class Fixture(osSupported: Boolean) {
        val log = mutableListOf<String>()
        val os = Recording("os", log)
        val app = Recording("app", log)
        val table = uploadMechanismTable(
            osDriven = os.takeIf { osSupported },
            appDriven = app,
            // Deregistration ONLY — narrower than the OS-driven mechanism's own `stop()`, which would
            // also wipe ledger rows the incoming mechanism is about to reconcile precisely.
            relinquishOsRegistration = { log += "os.deregister" },
        )
    }

    @Test
    fun `IDLE is a mechanism rather than an absence`() {
        // Every OS trigger carries a completion handler the system waits on, so "no mechanism" must
        // still be something that answers.
        for (osSupported in listOf(true, false)) {
            assertSame(IdleUploadMechanism, Fixture(osSupported).table(UploadMechanism.IDLE))
        }
    }

    @Test
    fun `on an OS carrying both a start relinquishes what the other left`() = runTest {
        // Both mechanisms leave state the OS keeps across process death — a configuration record on one
        // side, in-flight transfers and a submitted background task on the other — so a freshly launched
        // process can be running behind work it never started.
        val f = Fixture(osSupported = true)
        f.table(UploadMechanism.PHOTOKIT).start()
        assertEquals(listOf("app.stop", "os.start"), f.log, "the OS-driven cell stops the app-driven one")

        f.log.clear()
        f.table(UploadMechanism.URL_SESSION).start()
        assertEquals(
            listOf("os.deregister", "app.start"), f.log,
            "the app-driven cell DEREGISTERS rather than running the OS-driven mechanism's full stop",
        )
    }

    @Test
    fun `without an OS-driven mechanism the app-driven cell is unwrapped`() = runTest {
        // There is no registration to give up on iOS 18–26.0, and wrapping it would fire a deregister
        // against an API that does not exist there.
        val f = Fixture(osSupported = false)
        f.table(UploadMechanism.URL_SESSION).start()
        assertEquals(listOf("app.start"), f.log, "no relinquish where nothing was left behind")
    }

    @Test
    fun `PHOTOKIT without an OS-driven mechanism is Idle rather than the app-driven one`() = runTest {
        // THE CELL IS REACHED, ON EVERY DEVICE BELOW iOS 26.1 — not by resolution, which clamps PHOTOKIT
        // to what the device can run, but by `UploadArm.stopAll`, which maps the WHOLE enum through this
        // table on leave and on a download-only provision. Answering with the app-driven mechanism was
        // safe for `stopAll` and for nothing else: had a resolver bug let `switchTo` reach here, the arm
        // would have STARTED a mechanism other than the one it resolved, unwrapped by its relinquish and
        // reported as PHOTOKIT in every log line.
        val f = Fixture(osSupported = false)
        assertSame(IdleUploadMechanism, f.table(UploadMechanism.PHOTOKIT))
        f.table(UploadMechanism.PHOTOKIT).start()
        assertEquals(emptyList(), f.log, "nothing starts, and nothing is silently substituted")
    }

    @Test
    fun `stopping every kind stops each real mechanism exactly once`() = runTest {
        // What `UploadArm.stopAll` actually does: it de-duplicates, so the Idle cell above costs nothing
        // and the app-driven mechanism is still stopped once through its own cell.
        for (osSupported in listOf(true, false)) {
            val f = Fixture(osSupported)
            UploadMechanism.entries.map(f.table).distinct().forEach { it.stop() }
            assertEquals(
                if (osSupported) listOf("os.stop", "app.stop") else listOf("app.stop"),
                f.log,
            )
        }
    }

    @Test
    fun `a kind resolves to the same instance every time`() {
        // A platform requirement, not an optimisation: the app-driven mechanism owns a background
        // `URLSession` whose identifier must stay stable for the OS to re-adopt across launches, and
        // whose invalidation is terminal. Resolving away and back must return what already exists.
        val f = Fixture(osSupported = true)
        for (kind in UploadMechanism.entries) {
            assertSame(f.table(kind), f.table(kind), "$kind must not be rebuilt per resolution")
        }
    }

    @Test
    fun `a composition whose OS fact and mechanism disagree fails at assembly`() {
        // The two facts are supplied separately and deliberately — deriving presence from the thunk would
        // construct a mechanism the composition is only asking about — so nothing but this makes them
        // agree. Claiming support without a mechanism resolves PHOTOKIT with nothing to run; supplying
        // one while claiming no support builds a mechanism resolution can never name.
        val log = mutableListOf<String>()
        assertFailsWith<IllegalStateException>("supported, but no mechanism") {
            requireConsistent(osSupportsOsDrivenUpload = true, osDriven = null)
        }
        assertFailsWith<IllegalStateException>("unsupported, but a mechanism") {
            requireConsistent(osSupportsOsDrivenUpload = false, osDriven = Recording("os", log))
        }
    }

    @Test
    fun `a consistent composition is accepted in both directions`() {
        val log = mutableListOf<String>()
        requireConsistent(osSupportsOsDrivenUpload = true, osDriven = Recording("os", log))
        requireConsistent(osSupportsOsDrivenUpload = false, osDriven = null)
    }
}
