package app.snapsync.world

import app.snapsync.model.CycleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The world over the REAL backend (`docs/testing.md`): bytes, joins and unions reach the Deno
 * `api/`, and what only the mini-edge models is refused rather than faked.
 *
 * JVM-only because the backend runs as a local process, which a Kotlin/Native test executable cannot launch
 * (`docs/testing.md`, "Every test runs on every target its module declares" — the genuine
 * forgo). Nothing is lost on the simulator: the world code under test is `commonMain`, and its mini-edge half
 * runs there in `NeutralBackendWorldTest`.
 */
class DenoWorldTest {

    @Test
    fun an_upload_and_a_foreign_device_reach_the_real_backend() = worldTest {
        val w = World(this, backend = DenoBackend())
        val eventId = w.provisionMinted()
        w.addOwnAsset(ASSET)
        assertEquals(CycleResult.COMPLETED, w.runUploadCycle())

        w.platform.completeJob(KEY)
        assertTrue(KEY in w.neutral.objectsOf(w.ownDeviceId).orFail(), "the real backend lists the completed object")

        w.addForeignDeviceMinted(FOREIGN, listOf(World.foreignAsset(FOREIGN_ASSET)), eventId)
        val union = w.neutral.unionOf(eventId).orFail()
        assertTrue(union.any { it.deviceId == FOREIGN && it.assetId == FOREIGN_ASSET }, "the foreign asset is in the union")
        assertEquals(true, w.neutral.isRegistered(eventId).orFail())
    }

    @Test
    fun what_only_the_mini_edge_models_is_refused_not_faked() = worldTest {
        val w = World(this, backend = DenoBackend())
        assertIs<Answer.Unavailable>(w.neutral.setOffline(true))
        assertIs<Answer.Unavailable>(w.neutral.manifestOf("any", w.ownDeviceId))
        assertIs<Answer.Unavailable>(w.neutral.sweepEvent("any"))
        val refused = assertFailsWith<IllegalStateException> { w.store }
        assertTrue("provisionMinted" in refused.message.orEmpty(), "the error names the neutral route")
    }

    private companion object {
        const val ASSET = "0d1f5a9e-7a31-4c55-9b1e-2f0a6b7c8d01"
        const val KEY = "$ASSET-primary.jpg"
        const val FOREIGN = "5b7e0c2a-1d44-4f0e-8a6b-3c2d1e0f9a02"
        const val FOREIGN_ASSET = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c03"
    }
}
