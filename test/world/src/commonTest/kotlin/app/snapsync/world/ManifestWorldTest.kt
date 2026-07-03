package app.snapsync.world


import kotlin.test.Test
import kotlin.test.assertTrue

/** A drained cycle's onDiscovery produces + PUTs a manifest that makes the union report the own asset complete. */
class ManifestWorldTest {

    @Test
    fun cycle_manifest_makes_union_report_own_asset_complete() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addOwnAsset("A")

        w.runUploadCycle() // creates the job + PUTs the manifest (bytes not deposited yet)
        // Manifest exists but the object is not stored yet → the asset is not complete.
        assertTrue(w.store.union(eventId)!!.none { it.assetId == "A" })

        w.platform.completeJob("A-primary.jpg") // deposit the object
        w.runUploadCycle() // acknowledge

        val union = w.store.union(eventId)!!
        assertTrue(union.any { it.deviceId == w.ownDeviceId && it.assetId == "A" })
    }
}
