package app.snapsync.world

import app.snapsync.compose.EntryHooks
import app.snapsync.compose.platformEntries
import app.snapsync.model.PermissionStatus
import app.snapsync.model.uploadKey
import app.snapsync.model.ResourceRole
import app.snapsync.ports.AssetRef
import app.snapsync.ports.PlatformEntries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Each OS wake's own work, then the one tail, over the REAL composition and the world (capability `ios-app-shell`,
 * "Each OS wake does its own work, then hands the rest to one opportunistic tail"; decision record
 * `changes/own-work-per-wake`). The inbound port's contract (`PlatformEntriesContract`) pins the release points and
 * the expiry; this pins what the tail runs for each wake that the contract's observations cannot distinguish: the
 * limited-grant tail, a completion's top-up, the import a failed union still gets, and that a background wake
 * assembles no host.
 */
class TailWorldTest {

    private val joined = "22222222-2222-4222-8222-222222222222"

    private class Entries(val entries: PlatformEntries, val hostAssembled: () -> Boolean)

    private fun World.entries(): Entries {
        var assembled = false
        val entries = platformEntries(
            core = { core },
            hooks = EntryHooks(
                markActive = {},
                openUrl = {},
                assembleHost = { assembled = true },
                deliverPushToken = {},
                uploadHeartbeatTaskId = "world.upload.heartbeat",
                uploadTransferChannel = "world.upload.session",
            ),
        )
        return Entries(entries) { assembled }
    }

    /** Push [eventId] and wait until its handler is released and its hold ended — the tail with it. */
    private suspend fun World.push(entries: PlatformEntries, eventId: String) {
        val released = CompletableDeferred<Unit>()
        entries.onSilentPush(mapOf<Any?, Any?>("eventId" to eventId)) { released.complete(Unit) }
        withTimeout(10.seconds) {
            released.await()
            while (backgroundTimeHolds.value.isNotEmpty()) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun a_push_for_the_active_event_runs_the_full_tail_and_assembles_no_host() = worldTest {
        val w = World(this)
        w.provision(joined)
        val e = w.entries()
        w.push(e.entries, joined)

        assertEquals(1, w.operatorEngine.topUps, "② ran")
        assertEquals(1, w.operatorEngine.walks, "and, under a full grant, ③")
        assertEquals(1, w.heartbeatsScheduled, "a push re-arms the heartbeat")
        assertFalse(e.hostAssembled(), "a background wake assembles no host, so installs no grant subscription")
    }

    @Test
    fun under_a_limited_grant_the_push_tail_tops_up_and_walks_nothing() = worldTest {
        val w = World(this)
        w.provision(joined)
        w.permission.set(PermissionStatus.LIMITED)
        val e = w.entries()
        w.push(e.entries, joined)

        assertEquals(1, w.operatorEngine.topUps, "② runs from the selection snapshot")
        assertEquals(0, w.operatorEngine.walks, "③ never runs under a partial grant — no library read follows a push")
    }

    @Test
    fun a_push_for_another_event_runs_no_tail() = worldTest {
        val w = World(this)
        w.provision(joined)
        val e = w.entries()
        w.push(e.entries, "33333333-3333-4333-8333-333333333333")

        assertEquals(0, w.operatorEngine.topUps)
        assertEquals(0, w.heartbeatsScheduled)
    }

    @Test
    fun a_push_whose_union_read_fails_still_imports_what_is_staged() = worldTest {
        val w = World(this)
        w.provision(joined)
        w.addForeignDevice("DEV-F", joined, listOf(World.foreignAsset("FQ")))
        w.downloadController.reconcile(joined) // plans the asset
        // Staged by a wake the process did not survive to import — recorded, with its bytes on disk.
        val ref = AssetRef("DEV-F", "FQ")
        val key = uploadKey("FQ", ResourceRole.PRIMARY, "IMG.HEIC")
        w.stagedFiles += "/staged/FQ"
        w.downloadStore.markStaged(ref, key, "/staged/FQ")
        val before = w.importer.imported.size

        w.backendOffline = true // the push's union read fails fast
        w.push(w.entries().entries, joined)

        assertEquals(before + 1, w.importer.imported.size, "the tail's ① ran whatever the union answered")
    }

    @Test
    fun a_completion_tops_up_alone_and_only_while_the_app_may_create() = worldTest {
        val w = World(this)
        w.provision(joined)
        w.core.tail.uploadEvents.uploadCompleted()
        waitFor { w.operatorEngine.topUps == 1 }
        assertEquals(0, w.operatorEngine.walks, "a freed slot never walks")
        assertEquals(0, w.heartbeatsScheduled, "and re-arms nothing")

        w.permission.set(PermissionStatus.DENIED)
        w.core.tail.uploadEvents.uploadCompleted()
        kotlinx.coroutines.delay(200)
        assertEquals(1, w.operatorEngine.topUps, "a late completion after a revoke requests nothing")
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(10.seconds) {
        while (!condition()) kotlinx.coroutines.delay(10)
    }
}
