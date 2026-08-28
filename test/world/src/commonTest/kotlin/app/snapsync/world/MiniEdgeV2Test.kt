package app.snapsync.world

import app.snapsync.model.DeviceManifest
import app.snapsync.model.encodeToJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mini-edge serves BOTH device-API versions (capability `harness-world-model`).
 *
 * These drive the client raw rather than through a seam, because the seams do not speak v2 yet — that is
 * the whole reason this step is additive. When they move, they run against exactly these routes.
 */
class MiniEdgeV2Test {

    private val v1 = "https://edge.example/api/v1"
    private val v2 = "https://edge.example/api/v2"

    private fun world() = BackendStore().apply { registerEvent("E") }

    // ---- the version split ----------------------------------------------------------------------

    @Test
    fun the_v1_listing_still_answers_its_frozen_shape() = runTest {
        val store = world().apply { deposit("D", "Q-primary.heic") }
        val body = miniEdgeClient(store).get("$v1/files/devices/D").bodyAsText()
        // v1's shape: the object name under `filename`, plus a minted url.
        assertTrue(body.contains("\"filename\":\"Q-primary.heic\""), body)
        assertTrue(body.contains("\"url\""), body)
    }

    @Test
    fun an_unversioned_path_is_served_by_nothing() = runTest {
        // As on the real backend, which mounts `/api/v1` and `/api/v2` and matches neither without a
        // prefix. A mini-edge that defaulted an unversioned path to a version would let a seam built
        // with a prefix-less base pass here and 404 in production.
        val store = world().apply { deposit("D", "Q-primary.heic") }
        assertEquals(404, miniEdgeClient(store).get("https://edge.example/files/devices/D").status.value)
    }

    @Test
    fun the_v2_listing_answers_in_identity_terms_and_mints_no_url() = runTest {
        val store = world().apply { deposit("D", "Q-primary.heic") }
        val body = miniEdgeClient(store).get("$v2/files/devices/D").bodyAsText()
        assertTrue(body.contains("\"assetId\":\"Q\""), body)
        assertTrue(body.contains("\"role\":\"primary\""), body)
        assertFalse(body.contains("\"url\""), body)
    }

    // ---- join is not publish --------------------------------------------------------------------

    @Test
    fun a_bodyless_join_enrols_and_writes_no_manifest() = runTest {
        val store = world()
        val res = miniEdgeClient(store).put("$v2/events/E/devices/D")
        assertEquals(200, res.status.value)
        assertEquals(emptyList(), store.manifestOf("E", "D")?.assets)
    }

    @Test
    fun a_join_leaves_an_existing_contribution_intact() = runTest {
        val store = world()
        val asset = World.foreignAsset("Q")
        store.putManifest("E", "D", foreignManifest("D", listOf(asset)))

        miniEdgeClient(store).put("$v2/events/E/devices/D")

        // The v1 rejoin blanked this; the v2 join must not — there is no window at all.
        assertEquals(1, store.manifestOf("E", "D")?.assets?.size)
    }

    @Test
    fun a_join_for_an_unregistered_event_is_not_found() = runTest {
        val res = miniEdgeClient(BackendStore()).put("$v2/events/NOPE/devices/D")
        assertEquals(404, res.status.value)
    }

    @Test
    fun a_join_past_capacity_is_refused() = runTest {
        val store = world().apply { capacity = 2 }
        val client = miniEdgeClient(store)
        assertEquals(200, client.put("$v2/events/E/devices/D1").status.value)
        assertEquals(200, client.put("$v2/events/E/devices/D2").status.value)
        assertEquals(409, client.put("$v2/events/E/devices/D3").status.value)
    }

    // ---- publish is not join --------------------------------------------------------------------

    @Test
    fun a_v2_manifest_from_a_non_member_is_refused_and_creates_nothing() = runTest {
        val store = world()
        val res = miniEdgeClient(store).put("$v2/events/E/devices/D/manifest") {
            contentType(ContentType.Application.Json)
            setBody(DeviceManifest("D", emptyList()).encodeToJson())
        }
        assertEquals(409, res.status.value)
        assertEquals(null, store.manifestOf("E", "D"))
    }

    @Test
    fun a_v2_manifest_does_not_reactivate_a_departed_member() = runTest {
        val store = world()
        store.putManifest("E", "D", DeviceManifest("D", emptyList()))
        store.leave("E", "D")

        val res = miniEdgeClient(store).put("$v2/events/E/devices/D/manifest") {
            contentType(ContentType.Application.Json)
            setBody(DeviceManifest("D", emptyList()).encodeToJson())
        }

        assertEquals(200, res.status.value)
        assertTrue(store.isDeparted("E", "D"), "the v2 publish must enrol nobody")
    }

    @Test
    fun the_v1_publish_still_enrols_a_departed_member() = runTest {
        val store = world()
        store.putManifest("E", "D", DeviceManifest("D", emptyList()))
        store.leave("E", "D")

        miniEdgeClient(store).put("$v1/events/E/devices/D") {
            contentType(ContentType.Application.Json)
            setBody(DeviceManifest("D", emptyList()).encodeToJson())
        }

        // v1 is frozen: its publish reactivates, and this world keeps modelling that faithfully.
        assertFalse(store.isDeparted("E", "D"))
    }

    // ---- the version gate -----------------------------------------------------------------------

    @Test
    fun the_gate_is_off_until_armed() = runTest {
        val store = world().apply { deposit("D", "Q-primary.heic") }
        assertEquals(200, miniEdgeClient(store).get("$v2/files/devices/D").status.value)
    }

    @Test
    fun an_armed_gate_refuses_a_request_declaring_no_version() = runTest {
        val store = world().apply { minAppVersion = "0.4" }
        val res = miniEdgeClient(store).get("$v2/files/devices/D")
        assertEquals(426, res.status.value)
        assertTrue(res.bodyAsText().contains("\"minAppVersion\":\"0.4\""), res.bodyAsText())
    }

    @Test
    fun an_armed_gate_serves_a_current_build() = runTest {
        val store = world().apply { minAppVersion = "0.4" }
        val res = miniEdgeClient(store).get("$v2/files/devices/D") {
            header("x-snapsync-app-version", "0.4")
        }
        assertEquals(200, res.status.value)
    }

    @Test
    fun the_gate_never_touches_v1() = runTest {
        val store = world().apply { minAppVersion = "0.4"; deposit("D", "Q-primary.heic") }
        // v1 is spoken by builds that predate the header and cannot be updated to send it.
        assertEquals(200, miniEdgeClient(store).get("$v1/files/devices/D").status.value)
    }

    @Test
    fun versions_compare_numerically_not_as_strings() {
        // "0.10" < "0.9" lexicographically; the gate must not invert at the tenth release.
        assertTrue(compareAppVersions("0.10", "0.9") > 0)
        assertTrue(compareAppVersions("0.9", "0.10") < 0)
        assertEquals(0, compareAppVersions("0.4", "0.4"))
        assertTrue(compareAppVersions("nonsense", "0.1") < 0)
    }
}
