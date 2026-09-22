package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploaderPin
import app.snapsync.model.extensionRegistrable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The membership transitions (capability `upload-lifecycle`, "Membership transitions reconcile the upload
 * mechanisms in one tested place"), over the REAL registration fact and fakes for the registration and the app
 * engine.
 *
 * Every assertion is on the ORDERED log of verbs. The table under test (decision record
 * `changes/both-uploaders-active`, D5): the registration spans the membership and only a leave deregisters or
 * cancels. A re-provision of the joined event never reaches the transitions at all (`ProvisionTest`).
 */
class UploadTransitionsTest {

    /** A registration that refuses every write under a partial grant (3311), as the platform does. */
    private class FakeRegistration(
        private val log: MutableList<String>,
        private val grant: () -> PermissionStatus,
        var registered: Boolean = false,
    ) : ExtensionRegistration {
        override suspend fun register() {
            log += "register"
            if (grant() != PermissionStatus.LIMITED) registered = true
        }

        override suspend fun deregister() {
            log += "deregister"
            if (grant() != PermissionStatus.LIMITED) registered = false
        }

        override fun isRegistered(): Boolean = registered
    }

    private class FakeEngine(private val log: MutableList<String>) : AppUploadEngine {
        override suspend fun arm() { log += "arm" }
        override suspend fun disarm() { log += "disarm" }
        override suspend fun cancelTransfers() { log += "cancel" }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
        override fun onBackgroundTransfers(completion: () -> Unit) = completion()
    }

    private class World(osSupported: Boolean = true, var grant: PermissionStatus, var joined: Boolean = true) {
        val log = mutableListOf<String>()
        var pin: UploaderPin? = null
        val registration = FakeRegistration(log, { grant })
        private val engine = FakeEngine(log)
        val transitions = UploadTransitions(
            joined = { joined },
            permission = { grant },
            extensionRegistrable = { extensionRegistrable(osSupported, grant, pin) },
            registration = registration.takeIf { osSupported },
            appEngine = { engine },
        )

        fun clear() = log.clear()
    }

    // ---- join -------------------------------------------------------------------------------------------

    @Test
    fun `a join under a full grant registers through the toggle and arms the app`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true // a stale record that still reads "enabled": a join repairs it anyway

        w.transitions.onJoin()

        assertEquals(listOf("register", "arm"), w.log)
    }

    @Test
    fun `a join under a limited grant arms the app and attempts no registration write`() = runTest {
        val w = World(grant = PermissionStatus.LIMITED)

        w.transitions.onJoin()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a join below 26_1 arms the app alone`() = runTest {
        val w = World(osSupported = false, grant = PermissionStatus.GRANTED)

        w.transitions.onJoin()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a join without usable access registers nothing and disarms`() = runTest {
        val w = World(grant = PermissionStatus.DENIED)

        w.transitions.onJoin()

        assertEquals(listOf("disarm"), w.log)
    }

    // ---- reconfigure ------------------------------------------------------------------------------------

    @Test
    fun `a reconfigure never touches the registration and arms the app`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true

        w.transitions.onReconfigure()

        assertEquals(listOf("arm"), w.log)
    }

    // ---- permission change and launch: compared, never deregister ----------------------------------------

    @Test
    fun `a permission upgrade registers a missing record and arms the app`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)

        w.transitions.onPermissionChanged()

        assertEquals(listOf("register", "arm"), w.log)
    }

    @Test
    fun `a permission change finding the record present writes nothing`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true

        w.transitions.onPermissionChanged()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a downgrade to limited cancels nothing and writes no registration`() = runTest {
        val w = World(grant = PermissionStatus.LIMITED)
        w.registration.registered = true // survived the downgrade

        w.transitions.onPermissionChanged()

        assertEquals(listOf("arm"), w.log)
        assertTrue(w.registration.registered)
    }

    @Test
    fun `revocation disarms the heartbeat and cancels nothing`() = runTest {
        for (grant in listOf(PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED)) {
            val w = World(grant = grant)
            w.registration.registered = true

            w.transitions.onPermissionChanged()

            assertEquals(listOf("disarm"), w.log, "$grant")
            assertTrue(w.registration.registered, "$grant: the registration is left as it is")
        }
    }

    @Test
    fun `a grant with no event configured arms nothing and registers nothing`() = runTest {
        for (grant in PermissionStatus.entries) {
            val w = World(grant = grant, joined = false)

            w.transitions.onPermissionChanged()
            w.transitions.onLaunch()
            w.transitions.onReconfigure()

            assertEquals(emptyList(), w.log, "$grant")
        }
    }

    @Test
    fun `a launch compares and registers only`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.transitions.onLaunch()
        assertEquals(listOf("register", "arm"), w.log)

        w.clear()
        w.transitions.onLaunch()
        assertEquals(listOf("arm"), w.log, "a present record is left alone — its in-flight jobs survive a launch")
    }

    // ---- the rig switch ---------------------------------------------------------------------------------

    @Test
    fun `switching the extension off deregisters it now and nothing else does`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true
        w.pin = UploaderPin(extension = false)

        w.transitions.onPermissionChanged()
        assertEquals(listOf("arm"), w.log, "a permission change never deregisters, even when switched off")

        w.clear()
        w.transitions.onOverrideChanged()
        assertEquals(listOf("deregister", "arm"), w.log)

        w.clear()
        w.pin = null
        w.transitions.onOverrideChanged()
        assertEquals(listOf("register", "arm"), w.log, "switching it back on registers the missing record")
    }

    // ---- leave ------------------------------------------------------------------------------------------

    @Test
    fun `a leave deregisters then disarms and cancels the app's transfers`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true

        w.transitions.onLeave()

        assertEquals(listOf("deregister", "disarm", "cancel"), w.log)
    }

    @Test
    fun `below 26_1 a leave and a compared reconcile touch only the app engine`() = runTest {
        val w = World(osSupported = false, grant = PermissionStatus.GRANTED)

        w.transitions.onPermissionChanged()
        w.transitions.onLeave()

        assertEquals(listOf("arm", "disarm", "cancel"), w.log)
    }

    @Test
    fun `only a leave cancels transfers`() = runTest {
        for (grant in PermissionStatus.entries) {
            val w = World(grant = grant)
            w.transitions.onJoin()
            w.transitions.onReconfigure()
            w.transitions.onPermissionChanged()
            w.transitions.onLaunch()
            w.transitions.onOverrideChanged()
            assertTrue("cancel" !in w.log, "$grant: ${w.log}")
            assertTrue("deregister" !in w.log, "$grant: ${w.log}")
        }
    }
}
