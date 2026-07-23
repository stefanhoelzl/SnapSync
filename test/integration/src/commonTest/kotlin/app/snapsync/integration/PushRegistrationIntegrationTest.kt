package app.snapsync.integration

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.eventStart
import app.snapsync.model.captureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.feature.push.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
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
        val reg = PushRegistration(KtorPushHttpClient(miniEdgeClient(store)), "https://edge.example", deviceId)

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
     * The REAL `Provision` join flow re-registers the push token (capability `push-registration`): joining
     * fires `registerPush` in addition to the launch/rotation collector, closing the warm-rejoin window
     * the nightly sweep's device-record collection opens (capability `scheduled-cleanup`). Driven over the
     * real composed graph via `core.provisionFlow`, asserting the world's spy counter.
     */
    @Test
    fun the_join_flow_re_registers_the_push_token() = worldTest {
        val event = "33333333-3333-4333-8333-333333333333"
        val startsAt = eventStart("2026-01-01T00:00:00Z")
        val w = World(this)
        w.store.registerEvent(event, "Trip", startsAt.at.iso)
        assertEquals(0, w.registerPushCount)

        // Drive the REAL Provision flow — the join path — NOT the world's config-cell shortcut.
        w.core.provisionFlow.run(
            EventConfig(
                eventId = event,
                name = "Trip",
                minPhotoDate = CaptureCutoff(startsAt.at),
                maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"),
                startsAt = startsAt,
                direction = Direction.Both,
                saveToAlbum = false,
            ),
        )
        // `registerPush` runs on its own escaping launch (a network PUT must never block the join); the
        // world runs on real time, so poll for it to settle.
        withTimeout(2000) { while (w.registerPushCount == 0) yield() }
        assertEquals(1, w.registerPushCount) // fired exactly once, on join
    }
}
