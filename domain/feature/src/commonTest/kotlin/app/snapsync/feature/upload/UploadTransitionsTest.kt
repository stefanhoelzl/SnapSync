package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The membership transitions (capability `upload-lifecycle`, "Membership transitions reconcile the upload
 * mechanisms in one tested place"), over the REAL resolver and fakes for the two mechanisms.
 *
 * Every assertion is on the ORDERED log of mechanism verbs, because order is half of what this class decides:
 * stand-down first, so the ritual's demote never meets a live app transfer and the app engine is never armed over
 * a live extension registration.
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
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
    }

    private class World(osSupported: Boolean = true, var grant: PermissionStatus, var posture: Boolean? = true) {
        val log = mutableListOf<String>()
        var pin: UploadMechanism? = null
        val registration = FakeRegistration(log, { grant })
        private val engine = FakeEngine(log)
        val transitions = UploadTransitions(
            resolve = { resolveUploadMechanism(osSupported, grant, pin) },
            membershipIncludesUpload = { posture },
            permission = { grant },
            registration = registration.takeIf { osSupported },
            appEngine = { engine },
        )

        fun clear() = log.clear()
    }

    // ---- join: forced -----------------------------------------------------------------------------------

    @Test
    fun `a join under a full grant disarms the app engine — then registers through the ritual`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true // a stale record that still reads "enabled": a join repairs it anyway

        w.transitions.onJoin()

        assertEquals(listOf("disarm", "register"), w.log)
    }

    @Test
    fun `a join under a limited grant forces the deregistration — then arms the app engine`() = runTest {
        val w = World(grant = PermissionStatus.LIMITED)

        w.transitions.onJoin()

        assertEquals(listOf("deregister", "arm"), w.log, "the forced write is attempted; its refusal is the platform's")
    }

    @Test
    fun `a download-only join stands everything down`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED, posture = false)
        w.registration.registered = true

        w.transitions.onJoin()

        assertEquals(listOf("disarm", "deregister"), w.log)
        assertEquals(false, w.registration.registered)
    }

    @Test
    fun `a join below iOS 26_1 only arms the app engine`() = runTest {
        val w = World(osSupported = false, grant = PermissionStatus.GRANTED)

        w.transitions.onJoin()

        assertEquals(listOf("arm"), w.log, "no registration exists to touch where its selector does not")
    }

    @Test
    fun `a join without usable access disarms and leaves the registration alone`() = runTest {
        val w = World(grant = PermissionStatus.NOT_DETERMINED)

        w.transitions.onJoin()

        assertEquals(listOf("disarm"), w.log)
    }

    // ---- compared transitions ---------------------------------------------------------------------------

    @Test
    fun `reconfiguring a download-only membership to upload registers the extension it lost at the join`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED, posture = false)
        w.transitions.onJoin()
        w.clear()

        w.posture = true
        w.transitions.onReconfigure()

        assertEquals(listOf("disarm", "register"), w.log, "compared: wanted and absent registers")
        assertEquals(true, w.registration.registered)
    }

    @Test
    fun `a launch with a live registration writes nothing to it`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.registration.registered = true

        w.transitions.onLaunch()

        assertEquals(listOf("disarm"), w.log, "the extension's in-flight jobs survive the launch")
    }

    @Test
    fun `a launch with the registration missing registers through the ritual`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)

        w.transitions.onLaunch()

        assertEquals(listOf("disarm", "register"), w.log)
    }

    @Test
    fun `a launch on the app-driven mechanism arms it`() = runTest {
        val w = World(osSupported = false, grant = PermissionStatus.GRANTED)

        w.transitions.onLaunch()

        assertEquals(listOf("arm"), w.log, "the arm carries the restart signal and the first heartbeat")
    }

    @Test
    fun `limited to full disarms the app engine before registering`() = runTest {
        val w = World(grant = PermissionStatus.LIMITED)
        w.transitions.onJoin()
        w.clear()

        w.grant = PermissionStatus.GRANTED
        w.transitions.onPermissionChanged()

        assertEquals(listOf("disarm", "register"), w.log)
    }

    @Test
    fun `full to limited attempts no registration write and arms the app engine`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.transitions.onJoin()
        w.clear()

        w.grant = PermissionStatus.LIMITED
        w.transitions.onPermissionChanged()

        assertEquals(listOf("arm"), w.log, "compared under a partial grant changes nothing — it would be refused")
        assertEquals(true, w.registration.registered, "the record survives; the extension withholds at its gate")
    }

    @Test
    fun `revocation disarms the app engine and leaves the registration`() = runTest {
        val w = World(osSupported = false, grant = PermissionStatus.GRANTED)
        w.transitions.onJoin()
        w.clear()

        w.grant = PermissionStatus.DENIED
        w.transitions.onPermissionChanged()

        assertEquals(listOf("disarm"), w.log)
    }

    @Test
    fun `a grant with no membership arms nothing and registers nothing`() = runTest {
        val w = World(grant = PermissionStatus.NOT_DETERMINED, posture = null)

        w.grant = PermissionStatus.GRANTED
        w.transitions.onPermissionChanged()

        assertEquals(listOf("disarm"), w.log, "no event, no arm — the join is what brings a mechanism up")
    }

    @Test
    fun `a pin to the app-driven mechanism under a full grant deregisters the extension`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.transitions.onJoin()
        w.clear()

        w.pin = UploadMechanism.URL_SESSION
        w.transitions.onOverrideChanged()

        assertEquals(listOf("deregister", "arm"), w.log, "the extension cannot read the pin; deregistering it can")
        assertEquals(false, w.registration.registered)
    }

    @Test
    fun `a pin to idle under a full grant deregisters the extension too`() = runTest {
        val w = World(grant = PermissionStatus.GRANTED)
        w.transitions.onJoin()
        w.clear()

        w.pin = UploadMechanism.IDLE
        w.transitions.onOverrideChanged()

        assertEquals(listOf("disarm", "deregister"), w.log, "\"run nothing\" must reach the extension, which cannot read it")
    }

    // ---- leave ------------------------------------------------------------------------------------------

    @Test
    fun `a leave stands everything down — whatever the grant`() = runTest {
        for (grant in PermissionStatus.entries) {
            val w = World(grant = grant)
            w.transitions.onLeave()
            assertEquals(listOf("deregister", "disarm"), w.log, "under $grant")
        }
    }
}
