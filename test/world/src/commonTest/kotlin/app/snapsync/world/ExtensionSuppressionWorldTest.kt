package app.snapsync.world

import app.snapsync.compose.UploadPorts
import app.snapsync.compose.UploaderProcess
import app.snapsync.compose.uploadCore
import app.snapsync.model.CycleResult
import app.snapsync.model.PauseReason
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.SuppressionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload EXTENSION's cycle over the real shared composition, with its read-only echo-suppression view
 * (capability `receiving-photos`): an admitted cycle pauses on a download store the app has not migrated yet,
 * and a cycle the extension may not run never opens it at all (so a partial grant never answers `Paused`).
 */
class ExtensionSuppressionWorldTest {

    /** The extension's view of the download store, scripted, counting how often it was asked. */
    private class ScriptedSuppression(var readiness: SuppressionReadiness) : SuppressionSource {
        var asked = 0
        override suspend fun readiness(): SuppressionReadiness = readiness.also { asked++ }
        override suspend fun suppressedLocalIds(): Set<String> = emptySet()
    }

    /** The world's own cycle bundle, re-bound as the extension process over [suppression]. */
    private fun World.extensionPorts(grant: GalleryAccess, suppression: SuppressionSource): UploadPorts {
        val app = uploadPorts
        return UploadPorts(
            config = app.config, deviceIdentity = app.deviceIdentity, host = app.host, ledger = app.ledger,
            upload = app.upload, gallery = app.gallery, discovery = app.discovery,
            process = UploaderProcess.Extension { grant }, selectionScope = app.selectionScope,
            manifestStore = app.manifestStore, manifestPublisher = app.manifestPublisher, suppression = suppression,
            albumManager = app.albumManager, albumLookupFailure = app.albumLookupFailure,
            albumCoordinator = app.albumCoordinator, token = app.token, freshToken = app.freshToken,
            appVersion = app.appVersion,
        )
    }

    @Test
    fun an_admitted_extension_pauses_on_an_unmigrated_download_store_and_creates_nothing() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        val suppression = ScriptedSuppression(SuppressionReadiness.OldSchema)

        val result = uploadCore(this, w.extensionProcess(), w.extensionPorts(GalleryAccess.GRANTED, suppression)).run()

        assertEquals(CycleResult.Paused(PauseReason.OLD_SCHEMA), result)
        assertTrue(w.platform.created.isEmpty(), "uploading without suppression would send downloaded photos back")

        suppression.readiness = SuppressionReadiness.Ready
        assertEquals(CycleResult.COMPLETED, uploadCore(this, w.extensionProcess(), w.extensionPorts(GalleryAccess.GRANTED, suppression)).run())
        assertTrue(w.platform.created.any { it.filename == "A-primary.jpg" }, "once the app migrated it, the cycle runs")
    }

    @Test
    fun an_extension_under_a_partial_grant_withholds_without_opening_the_download_store() = worldTest {
        val w = World(this)
        w.provision("E")
        val suppression = ScriptedSuppression(SuppressionReadiness.OldSchema)

        val result = uploadCore(this, w.extensionProcess(), w.extensionPorts(GalleryAccess.LIMITED, suppression)).run()

        assertEquals(CycleResult.SKIPPED, result, "a process that may not create withholds; it never pauses")
        assertEquals(0, suppression.asked, "admission is decided before the download store is opened")
    }

    @Test
    fun an_unopenable_download_store_uploads_nothing_this_run() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")

        val result = uploadCore(
            this,
            w.extensionProcess(),
            w.extensionPorts(GalleryAccess.GRANTED, ScriptedSuppression(SuppressionReadiness.Unavailable("locked"))),
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "\"I could not look\" is a clean skip")
        assertTrue(w.platform.created.isEmpty())
    }
}
