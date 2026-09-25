package app.snapsync.integration

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Manifest publishes are ordered by the ledger's manifest version** (capabilities `photo-sharing`,
 * `docs/architecture.md`, `photo-sharing`), over the real stack: the composed `uploadCore`, the real
 * `DeviceManifestProducer` and `HttpManifestPublisher`, and the mini-edge modelling the real route's ordering.
 *
 * The crossed-pair races this ordering closes (an older publish landing last is refused; an equal version is
 * applied) are contracted at the publisher port — `ManifestPublisherContract`'s
 * `AN_OLDER_PUBLISH_LANDING_LAST_CHANGES_NOTHING` / `AN_EQUAL_VERSION_PUBLISH_IS_APPLIED`, bound to the mini-edge
 * and the real `api/`. What stays here is the cost the ordering puts on the cycle, which only the composed stack
 * shows. Decision record: `changes/manifest-versions`.
 */
class ManifestVersionIntegrationTest {

    /** How many manifest publishes the backend APPLIED for this device in the joined event. */
    private suspend fun Rig.publishes(): Int = deviceJson("backend/publishes").getValue("applied").jsonPrimitive.int

    @Test
    fun a_cycle_that_changed_the_ledger_is_followed_by_one_republish_and_then_skips() = rigTest {
        // The cost of reading the version first: a cycle's own writes (recording what its walk found) advance
        // the counter after the read, so the next cycle's version no longer matches the skip record and it
        // republishes the unchanged snapshot once. Pinned so the cost stays visible and bounded.
        createAndJoin()
        addPhoto("A")
        cycle()
        val afterFirst = publishes()
        cycle()
        assertEquals(afterFirst + 1, publishes(), "one republish under the newer version")
        cycle()
        cycle()
        assertEquals(afterFirst + 1, publishes(), "then an unchanged ledger skips")
    }
}
