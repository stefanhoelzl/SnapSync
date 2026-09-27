package app.snapsync.world

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.services.gallery.Discovery
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.ports.GalleryReader
import app.snapsync.services.gallery.GalleryDiscovery

/**
 * The world's rigging around the cycle's two library reads: the same `GalleryDiscovery` service the device
 * roots compose, over the world's gallery, plus the operator's lever and the inspection a test uses to tell a
 * cycle that walked from one that enqueued from the ledger.
 *
 * Nothing here answers differently from the service except the one lever, [makeWalkUnreadable], which answers
 * the next walk the way a device answers a library it could not read.
 */
class FakeUploadDiscovery(gallery: GalleryReader) : UploadDiscovery {

    private val honest: UploadDiscovery = GalleryDiscovery(gallery)

    /** Every key ever asked for, counted with repeats (see [resourcesFor]). */
    var resolvedKeyCount = 0

    private var unreadable = false

    /** Inspection: how many times the discovery feed was consumed — 0 proves a cycle enqueued from the ledger. */
    var discoverCalls = 0
        private set

    /** Inspection: every ledger key the cycle asked this fake to resolve. */
    val resolvedKeys = mutableSetOf<String>()

    /**
     * Resolve ledger keys through the honest fake, **observably**: [resolvedKeys] is how a test asserts that
     * a cycle enqueued from the ledger rather than from the discovery feed (capability `photo-sharing`).
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
        resolvedKeys += keys
        // How many keys were resolved in total, not how many distinct ones — the surplus this bound exists
        // to remove is repeated work on rows the platform was never going to take, and a set hides it.
        resolvedKeyCount += keys.size
        return honest.resourcesFor(keys)
    }

    override suspend fun discover(policy: SelectionPolicy): Discovery {
        discoverCalls++
        if (unreadable) {
            unreadable = false
            // What a device answers for a library it could not read: nothing, and NOT authoritative — so the
            // cycle deletes nothing on the strength of an empty answer (capability `photo-sharing`).
            return Discovery(candidates = emptyList(), fullEnumeration = false)
        }
        return honest.discover(policy)
    }

    // ---- operator actions -----------------------------------------------------------------------

    /**
     * Make the next walk unreadable: no candidates, and not authoritative — the case the cycle's deletion gate
     * exists for (`docs/testing.md`).
     */
    fun makeWalkUnreadable() {
        unreadable = true
    }
}
