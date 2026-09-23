package app.snapsync.integration

import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.eventStart
import app.snapsync.model.captureCeiling
import app.snapsync.feature.push.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
import app.snapsync.model.Direction
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.world.BackendStore
import app.snapsync.world.World
import app.snapsync.world.miniEdgeClient
import app.snapsync.world.worldTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL device-side push registration (`PushRegistration` + `KtorPushHttpClient`) driven against the
 * world's mini-edge, asserting the world outcome: the device config document lands in the backend store,
 * and — because config lives in its own namespace — it is NOT surfaced as a listed file. (The backend
 * notify fan-out is Deno-side logic, covered by `api/test/app.test.ts`, so it is not re-exercised
 * here.) Runs on JVM and `iosSimulatorArm64`.
 */
class PushRegistrationIntegrationTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun registration_writes_the_device_config_over_the_world() = worldTest {
        val store = BackendStore()
        val reg = PushRegistration(KtorPushHttpClient(miniEdgeClient(store)), "https://edge.example/api/v2", identity = { deviceId })

        reg.register(ApnsPushToken("DEADBEEF", "sandbox"))

        assertEquals(
            """{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}""",
            store.deviceConfigOf(deviceId),
        )
        // A rotated token overwrites (last-write-wins), and config never appears in the file listing.
        reg.register(ApnsPushToken("CAFEBABE", "production"))
        assertEquals(
            """{"pushToken":{"kind":"apns","token":"CAFEBABE","env":"production"}}""",
            store.deviceConfigOf(deviceId),
        )
        assertTrue(store.deviceListing(deviceId).isEmpty())
    }

    /**
     * A JOIN re-registers the push token (capability `push-registration`): committing a join through the
     * composed command bundle runs the real `flow/Provision`, whose `registerPush` re-PUTs the delivered token —
     * closing the warm-rejoin window the nightly sweep's device-record collection opens (capability
     * `scheduled-cleanup`).
     *
     * This used to call `core.provisionFlow.run` directly, because a join in the world did NOT run the flow:
     * the world bound `AppPorts.provision` to a body of its own. That seam is gone — the composition builds
     * provision from the core it holds — so the ordinary join path is the one under test.
     */
    @Test
    fun the_join_flow_re_registers_the_push_token() = worldTest {
        val event = "33333333-3333-4333-8333-333333333333"
        val startsAt = eventStart("2026-01-01T00:00:00Z")
        val w = World(this)
        w.store.registerEvent(event, "Trip", startsAt.at.iso)
        w.pushTokens.deliver("DEADBEEF")
        assertEquals(0, w.registerPushCount)

        w.userCommands.commitJoin(
            event, "Trip", startsAt, eventEnd("2026-01-08T00:00:00Z"), deletesAt("2026-02-08T00:00:00Z"),
            CaptureCutoff(startsAt.at), captureCeiling("2026-01-08T00:00:00Z"), Direction.Both, false,
        )
        // `registerPush` runs on its own escaping launch (a network PUT must never block the join); the
        // world runs on real time, so poll for it to settle.
        withTimeout(2000) { while (w.registerPushCount == 0) yield() }
        assertEquals(1, w.registerPushCount) // fired exactly once, on join
        assertEquals(
            """{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}""",
            w.store.deviceConfigOf(w.ownDeviceId),
        )
    }

    /**
     * **The credential arm of `AppCore.installPushRegistration` is wired** (capabilities
     * `push-registration`, `device-attestation`).
     *
     * THE JOIN THIS PINS. The app writes its push registration ONCE per APNs token the OS delivers. A
     * registration refused because the backend rejected the credential is therefore never re-sent on its
     * own, and the device goes silently unregistered — no silent pushes, no download wakes, and none of the
     * wake-driven attestation renewals that depend on them. What saves it is that obtaining a NEW
     * credential re-runs the registration, and that is a join between two features that are blind to each
     * other: the trust feature announces the new token, the push feature consumes the announcement.
     *
     * It lives in `compose/` rather than the shell precisely so this test can exist — assembled in
     * `:app:ios` it would be unreachable, because that module is wiring-only and untested by law and the
     * world composes `snapSyncApp` rather than the root.
     *
     * ISOLATING THE ARM. `PushRegistration.run` merges two sources — token deliveries and credential
     * changes — so a test that simply delivers a token proves nothing about the second. Here the first
     * write is unambiguously the DELIVERY arm, and the second happens with no further delivery: it can only
     * have arrived through the credential announcement.
     *
     * The write is counted at the port rather than asserted on the backend, because registration is
     * last-write-wins: the second write stores exactly what the first did, so the world's stored config
     * cannot tell one from two.
     */
    @Test
    fun a_new_credential_re_registers_the_push_token_with_no_new_delivery() = worldTest {
        // `attests = true` is what lets a credential change happen at all; off (the default) the refresh
        // returns early without attesting, as it does in the extension and on a simulator.
        val w = World(this, attests = true)

        // The composed registration writes through the world's counting push port (see
        // `World.registerPushCount`), counted after each write lands.
        w.core.installPushRegistration()

        // The OS delivers a token: registration #1, through the DELIVERY arm.
        w.pushTokens.deliver("DEADBEEF")
        withTimeout(5_000) { while (w.registerPushCount < 1) yield() }
        assertEquals(
            """{"pushToken":{"kind":"apns","token":"DEADBEEF","env":"sandbox"}}""",
            w.store.deviceConfigOf(w.ownDeviceId),
        )

        // The backend rejects the credential — the 401 the shell routes here. The token is dropped and the
        // next refresh obtains a new one, which ANNOUNCES itself.
        w.core.attestation.onRejected()
        w.core.attestation.refresh()

        // Registration #2, with no second delivery: the credential arm, and nothing else, can have done it.
        withTimeout(5_000) { while (w.registerPushCount < 2) yield() }
        assertEquals(2, w.registerPushCount)
    }
}
