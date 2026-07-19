package app.snapsync.integration

import app.snapsync.feature.push.ApnsPushToken
import app.snapsync.feature.push.PushRegistration
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.world.BackendStore
import app.snapsync.world.miniEdgeClient
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL device-side push registration (`PushRegistration` + `KtorPushHttpClient`) driven against the
 * world's mini-edge, asserting the world outcome: the device config document lands in the backend store,
 * and — because config lives in its own namespace — it is NOT surfaced as a listed file. (The backend
 * notify fan-out is Deno-side logic, covered by `backend/test/app.test.ts`, so it is not re-exercised
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
}
