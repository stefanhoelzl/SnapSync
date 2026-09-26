package app.snapsync.feature.upload

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.EventConfig
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.ConfigSource
import app.snapsync.model.MembershipRead
import app.snapsync.model.GalleryAccess
import app.snapsync.model.UploaderPin
import app.snapsync.model.extensionRegistrable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The membership transitions (capability `background-upload`, "Membership transitions reconcile the upload
 * mechanisms in one tested place"), over the REAL registration fact and fakes for the registration and the app
 * engine.
 *
 * Every assertion is on the ORDERED log of verbs. The table under test (decision record
 * `changes/both-uploaders-active`, D5): the registration spans the membership and only a leave deregisters or
 * cancels. A re-provision of the joined event never reaches the transitions at all (`ProvisionTest`).
 */
class UploadTransitionsTest {

    /**
     * A registration that refuses every write under a partial grant (3311), as the platform does — or, where the OS
     * carries no such mechanism ([supported] false), asks the operating system nothing and reads no record, as the
     * port's `Unsupported` answer does.
     */
    private class FakeRegistration(
        private val log: MutableList<String>,
        private val grant: () -> GalleryAccess,
        private val supported: Boolean,
        var registered: Boolean = false,
    ) : ExtensionRegistration {
        override suspend fun register() {
            if (!supported) return
            log += "register"
            if (grant() != GalleryAccess.LIMITED) registered = true
        }

        override suspend fun deregister() {
            if (!supported) return
            log += "deregister"
            if (grant() != GalleryAccess.LIMITED) registered = false
        }

        override fun isRegistered(): Boolean? = registered.takeIf { supported }
    }

    private class FakeEngine(private val log: MutableList<String>) : AppUploadEngine {
        override suspend fun arm() { log += "arm" }
        override suspend fun disarm() { log += "disarm" }
        override suspend fun cancelTransfers() { log += "cancel" }
    }

    private class World(
        osSupported: Boolean = true,
        var grant: GalleryAccess,
        var joined: Boolean = true,
        var unreadable: Boolean = false,
    ) {
        val log = mutableListOf<String>()
        var pin: UploaderPin? = null
        val registration = FakeRegistration(log, { grant }, supported = osSupported)
        private val engine = FakeEngine(log)
        val transitions = UploadTransitions(
            configSource = liveMembership(unreadable = { unreadable }) { "E".takeIf { joined } },
            photoAccess = liveGrant { grant },
            extensionRegistrable = { extensionRegistrable(osSupported, grant, pin) },
            registration = registration,
            appEngine = { engine },
        )

        fun clear() = log.clear()
    }

    // ---- join -------------------------------------------------------------------------------------------

    @Test
    fun `a join under a full grant registers through the toggle and arms the app`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)
        w.registration.registered = true // a stale record that still reads "enabled": a join repairs it anyway

        w.transitions.onJoin()

        assertEquals(listOf("register", "arm"), w.log)
    }

    @Test
    fun `a join under a limited grant arms the app and attempts no registration write`() = runTest {
        val w = World(grant = GalleryAccess.LIMITED)

        w.transitions.onJoin()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a join below 26_1 arms the app alone`() = runTest {
        val w = World(osSupported = false, grant = GalleryAccess.GRANTED)

        w.transitions.onJoin()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a join without usable access registers nothing and disarms`() = runTest {
        val w = World(grant = GalleryAccess.DENIED)

        w.transitions.onJoin()

        assertEquals(listOf("disarm"), w.log)
    }

    // ---- reconfigure ------------------------------------------------------------------------------------

    @Test
    fun `a reconfigure never touches the registration and arms the app`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)
        w.registration.registered = true

        w.transitions.onReconfigure()

        assertEquals(listOf("arm"), w.log)
    }

    // ---- permission change and launch: compared, never deregister ----------------------------------------

    @Test
    fun `a permission upgrade registers a missing record and arms the app`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)

        w.transitions.onPermissionChanged()

        assertEquals(listOf("register", "arm"), w.log)
    }

    @Test
    fun `a permission change finding the record present writes nothing`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)
        w.registration.registered = true

        w.transitions.onPermissionChanged()

        assertEquals(listOf("arm"), w.log)
    }

    @Test
    fun `a downgrade to limited cancels nothing and writes no registration`() = runTest {
        val w = World(grant = GalleryAccess.LIMITED)
        w.registration.registered = true // survived the downgrade

        w.transitions.onPermissionChanged()

        assertEquals(listOf("arm"), w.log)
        assertTrue(w.registration.registered)
    }

    @Test
    fun `revocation disarms the heartbeat and cancels nothing`() = runTest {
        for (grant in listOf(GalleryAccess.DENIED, GalleryAccess.NOT_DETERMINED)) {
            val w = World(grant = grant)
            w.registration.registered = true

            w.transitions.onPermissionChanged()

            assertEquals(listOf("disarm"), w.log, "$grant")
            assertTrue(w.registration.registered, "$grant: the registration is left as it is")
        }
    }

    @Test
    fun `a grant with no event configured arms nothing and registers nothing`() = runTest {
        for (grant in GalleryAccess.entries) {
            val w = World(grant = grant, joined = false)

            w.transitions.onPermissionChanged()
            w.transitions.onLaunch()
            w.transitions.onReconfigure()

            assertEquals(emptyList(), w.log, "$grant")
        }
    }

    @Test
    fun `an unreadable membership defers every compared transition`() = runTest {
        // A locked device's cold background launch cannot read the config yet. That is not "not joined" (a false
        // leave) and not "joined" (armed for an event nobody can name): nothing moves, and the next one reads again.
        for (grant in GalleryAccess.entries) {
            val w = World(grant = grant, unreadable = true)

            w.transitions.onPermissionChanged()
            w.transitions.onLaunch()
            w.transitions.onReconfigure()

            assertEquals(emptyList(), w.log, "$grant")
        }
    }

    @Test
    fun `a launch compares and registers only`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)
        w.transitions.onLaunch()
        assertEquals(listOf("register", "arm"), w.log)

        w.clear()
        w.transitions.onLaunch()
        assertEquals(listOf("arm"), w.log, "a present record is left alone — its in-flight jobs survive a launch")
    }

    // ---- the rig switch ---------------------------------------------------------------------------------

    @Test
    fun `switching the extension off deregisters it now and nothing else does`() = runTest {
        val w = World(grant = GalleryAccess.GRANTED)
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
        val w = World(grant = GalleryAccess.GRANTED)
        w.registration.registered = true

        w.transitions.onLeave()

        assertEquals(listOf("deregister", "disarm", "cancel"), w.log)
    }

    @Test
    fun `below 26_1 a leave and a compared reconcile touch only the app engine`() = runTest {
        val w = World(osSupported = false, grant = GalleryAccess.GRANTED)

        w.transitions.onPermissionChanged()
        w.transitions.onLeave()

        assertEquals(listOf("arm", "disarm", "cancel"), w.log)
    }

    @Test
    fun `only a leave cancels transfers`() = runTest {
        for (grant in GalleryAccess.entries) {
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

/** A membership fake whose answer is read at every access, so a test's `var` drives it live. */
private fun liveMembership(unreadable: () -> Boolean = { false }, eventId: () -> String?): ConfigSource =
    object : ConfigSource {
        override val config: StateFlow<EventConfig?>
            get() = MutableStateFlow(
                eventId()?.let {
                    EventConfig(it, "E", captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"))
                },
            )
        override val membership: MembershipRead
            get() = if (unreadable()) MembershipRead.Unreadable else super.membership
    }

/** A grant fake read at every access, so a test's `var` drives it live. */
private fun liveGrant(grant: () -> GalleryAccess): PhotoAccessStatusSource = object : PhotoAccessStatusSource {
    override val permission: StateFlow<GalleryAccess> get() = MutableStateFlow(grant())
}
