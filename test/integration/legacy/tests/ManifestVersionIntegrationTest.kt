package app.snapsync.integration

import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.model.DeviceManifest
import app.snapsync.model.Direction
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.encodeToJson
import app.snapsync.ports.LedgerStore
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Manifest publishes are ordered by the ledger's manifest version** (capabilities `device-manifest`,
 * `api-endpoints`, `sync-ledger`), over the real stack: the composed `uploadCore`, the real
 * `DeviceManifestProducer` and `HttpManifestPublisher`, and the mini-edge modelling the real route's ordering.
 *
 * The race it closes needs two processes — the app and the upload extension, both publishing — and the world
 * holds one. What the world CAN do is deliver the two halves of a crossed pair itself: the older publish that
 * lands last is replayed through the same HTTP adapter the cycle uses, and the stale same-version write is
 * placed on the backend directly. Decision record: `changes/manifest-versions`.
 */
class ManifestVersionIntegrationTest {

    private fun World.held(eventId: String = "E"): List<String> =
        store.manifestOf(eventId, ownDeviceId)!!.assets.map { it.assetId }

    @Test
    fun an_older_publish_landing_last_is_refused_and_the_newer_snapshot_stays() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        // The older process's snapshot, exactly as it went over the wire.
        val older: DeviceManifest = w.store.manifestOf("E", w.ownDeviceId)!!
        assertEquals(listOf("A"), older.assets.map { it.assetId })

        w.addOwnAsset("B")
        w.runUploadCycle()
        assertEquals(listOf("A", "B"), w.held())

        // The older PUT arrives now — after the newer one. Through the real adapter, against the ordered route.
        assertTrue(w.manifestPublisher.publish("E", w.ownDeviceId, older.encodeToJson()), "refused is a 2xx")

        assertEquals(listOf("A", "B"), w.held(), "the backend kept the newer snapshot")
        assertEquals(1, w.store.refusedPublishesOf("E", w.ownDeviceId))
    }

    @Test
    fun a_same_version_crossed_pair_is_republished_by_the_next_cycle() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.addOwnAsset("B")
        w.runUploadCycle()
        w.runUploadCycle() // settle the one republish a changed cycle is followed by (see below)
        val current = w.store.manifestOf("E", w.ownDeviceId)!!
        val version = current.version!!

        // The other process read the SAME version but its rows before B existed, and its PUT lands last: an
        // equal version is accepted, so the backend now holds the stale snapshot while this device's skip
        // record names the current one.
        val stale = DeviceManifest(current.deviceId, current.assets.filter { it.assetId == "A" }, version)
        w.store.putManifestV2Json("E", w.ownDeviceId, stale.encodeToJson())
        assertEquals(listOf("A"), w.held())

        // The change that made the other snapshot stale advanced the counter past `version` — B's row was
        // recorded after that process read its rows. Model it as the next ledger change a cycle sees.
        w.ledgerBackend.bumpManifestVersion()
        w.runUploadCycle()

        assertEquals(listOf("A", "B"), w.held(), "the skip record's version no longer matched, so it republished")
    }

    @Test
    fun the_gate_reads_the_version_before_the_membership() = worldTest {
        // A reconfigure lands at the instant the gate reads the version. Read FIRST, the gate then reads the
        // NEW config, so this very cycle already publishes the narrowed policy; read after the config, it would
        // publish the old policy under a version the reconfigure's bump does not exceed.
        val ledger = VersionReadHook(inMemoryLedgerStore())
        val w = World(this, ledgerBackend = ledger)
        w.provision("E", minPhotoDate = captureCutoff("2026-01-01T00:00:00Z"))
        w.addOwnAsset("EARLY", creationDate = "2026-02-01T10:00:00Z")
        w.addOwnAsset("LATE", creationDate = "2026-06-15T10:00:00Z")
        w.runUploadCycle()
        assertEquals(listOf("EARLY", "LATE"), w.held())

        ledger.onNextRead = {
            w.userCommands.reconfigure(
                "E", Direction.Both, captureCutoff("2026-06-10T00:00:00Z"),
                captureCeiling(World.DEFAULT_FAR_CEILING), false,
            )
        }
        val publishesBefore = w.store.publishesOf("E", w.ownDeviceId)
        w.runUploadCycle()

        assertEquals(publishesBefore + 1, w.store.publishesOf("E", w.ownDeviceId))
        assertEquals(listOf("LATE"), w.held(), "the first publish after the reconfigure carries the new policy")
    }

    @Test
    fun a_cycle_that_changed_the_ledger_is_followed_by_one_republish_and_then_skips() = worldTest {
        // The cost of reading the version first: a cycle's own writes (recording what its walk found) advance
        // the counter after the read, so the next cycle's version no longer matches the skip record and it
        // republishes the unchanged snapshot once. Pinned so the cost stays visible and bounded.
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        val afterFirst = w.store.publishesOf("E", w.ownDeviceId)
        w.runUploadCycle()
        assertEquals(afterFirst + 1, w.store.publishesOf("E", w.ownDeviceId), "one republish under the newer version")
        w.runUploadCycle()
        w.runUploadCycle()
        assertEquals(afterFirst + 1, w.store.publishesOf("E", w.ownDeviceId), "then an unchanged ledger skips")
    }
}

/** A ledger that runs [onNextRead] once, right after answering the next manifest-version read. */
private class VersionReadHook(private val inner: LedgerStore) : LedgerStore by inner {
    var onNextRead: (suspend () -> Unit)? = null

    override suspend fun manifestVersion(): Long {
        val version = inner.manifestVersion()
        onNextRead?.also { onNextRead = null }?.invoke()
        return version
    }
}
