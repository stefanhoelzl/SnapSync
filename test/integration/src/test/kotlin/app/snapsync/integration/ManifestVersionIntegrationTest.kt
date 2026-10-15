package app.snapsync.integration

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **Manifest publishes are ordered by the ledger's manifest version** (`docs/architecture.md`), over the real
 * stack: the composed `uploadCore`, the real
 * `DeviceManifestProducer` and the manifest service, and the backend mock modelling the real route's
 * ordering.
 *
 * The crossed-pair races this ordering closes (an older publish landing last is refused; an equal version is
 * applied) are contracted at the backend port — `BackendContract`'s
 * `MANIFEST_AN_OLDER_PUBLISH_LANDING_LAST_CHANGES_NOTHING` / `MANIFEST_AN_EQUAL_VERSION_PUBLISH_IS_APPLIED`, bound to the backend mock
 * and the real `api/`. What stays here is the cost the ordering puts on the cycle, which only the composed stack
 * shows. Decision record: `changes/manifest-versions`.
 */
class ManifestVersionIntegrationTest {

    @Test
    fun a_cycle_that_changed_the_ledger_is_followed_by_one_republish_and_then_skips() = behaviour {
        // The cost of reading the version first: a cycle's own writes (recording what its walk found) advance
        // the counter after the read, so the next cycle's version no longer matches the skip record and it
        // republishes the unchanged snapshot once. Pinned so the cost stays visible and bounded.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            step("a first cycle", act = { cycle() }, until = {
                event<WorldEvent.ManifestPublished> { "A" in it.added }
                settled { true }
            })
        }

        whenever { cycle() }
        then {
            // One republish under the newer version, of the unchanged set.
            times(1) { event<WorldEvent.ManifestPublished>() }
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() || it.withdrawn.isNotEmpty() }
        }

        // Then an unchanged ledger skips.
        whenever {
            cycle()
            cycle()
        }
        then { never<WorldEvent.ManifestPublished>() }
    }
}
