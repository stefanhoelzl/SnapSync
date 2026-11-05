package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.test.Test

/**
 * **A photo being imported is never shared back, whatever runs while it is imported.** The import and the member's own
 * uploads overlap: a wake runs them together, a staged download imports whenever it lands, and the upload extension is
 * another process altogether. Each test holds one of them at a named point and runs the other through that window. The
 * member's own photo goes out in every one, so none passes because the pass never ran.
 */
class EchoDuringImportIntegrationTest {

    private suspend fun Given.appUploadsOnly() =
        step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})

    /** A foreign photo's import parked, [afterCommit] its asset in the library, or else not yet. */
    private suspend fun Given.importParked(afterCommit: Boolean) {
        downloadStarted(DEV_F, ASSET)
        step(
            "its bytes staged, its import parked",
            act = {
                library.suspendNextImport(afterCommit = afterCommit)
                downloadSession.finish()
            },
            until = {
                event<WorldEvent.LibraryImportParked>()
                settled { true }
            },
        )
    }

    /** The member takes a photo — so a walk really reads the library — and the heartbeat's wake runs. */
    private suspend fun Acts.ownPhotoThenWake() {
        takePhoto(OWN)
        heartbeat()
    }

    /** The member's own photo went out through the app's uploader, and the received one did not. */
    private fun Expectations.onlyTheOwnPhotoLeftTheApp() {
        event<WorldEvent.SessionTransferCreated> { it.key == primaryKey(OWN) }
        event<WorldEvent.ManifestPublished> { OWN in it.added }
        neverTheReceivedPhoto()
    }

    private fun Expectations.neverTheReceivedPhoto() {
        never<WorldEvent.UploadJobCreated> { ASSET in it.job }
        never<WorldEvent.SessionTransferCreated> { ASSET in it.key }
        never<WorldEvent.BytesStored> { ASSET in it.asset }
        never<WorldEvent.ManifestPublished> { published -> published.added.any { ASSET in it } }
        settled { true }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_wake_while_an_import_has_not_committed_shares_only_the_members_own() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            importParked(afterCommit = false)
        }
        whenever { ownPhotoThenWake() }
        then { onlyTheOwnPhotoLeftTheApp() }

        whenever {
            library.resumeImport(succeeded = true)
            heartbeat()
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported(ASSET) }
            neverTheReceivedPhoto()
        }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_wake_while_an_import_is_unconfirmed_shares_only_the_members_own() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            importParked(afterCommit = true)
        }
        whenever { ownPhotoThenWake() }
        then { onlyTheOwnPhotoLeftTheApp() }
    }

    /**
     * The walk read what was received, and an import commits before it reads the library: the walk sees a photo the
     * record it began with does not name.
     */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun an_import_landing_during_a_wakes_walk_is_not_taken_for_the_members_own() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            downloadStarted(DEV_F, ASSET)
            step("the library slow to read", act = { library.holdEnumeration(true) }, until = {})
        }
        whenever { ownPhotoThenWake() }
        then { event<WorldEvent.LibraryEnumerationHeld>() }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported(ASSET) } }

        whenever { library.holdEnumeration(false) }
        then { onlyTheOwnPhotoLeftTheApp() }
    }

    /**
     * The same window from the other process: the extension's invocation is walking when the app's import commits. The
     * invocation plans and publishes in one go, so it is the harm at its most direct — a job for someone else's photo.
     */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun an_import_landing_during_the_extensions_walk_is_not_taken_for_the_members_own() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            downloadStarted(DEV_F, ASSET)
            step("the library slow to read", act = { library.holdEnumeration(true) }, until = {})
        }
        lateinit var invocation: Job
        whenever {
            // The operating system's invocation does not return while its walk is held, so it runs beside the test.
            invocation = CoroutineScope(currentCoroutineContext() + Job()).launch(start = CoroutineStart.UNDISPATCHED) { cycle() }
            takePhoto(OWN)
        }
        then { event<WorldEvent.LibraryEnumerationHeld>() }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported(ASSET) } }

        whenever {
            library.holdEnumeration(false)
            invocation.join()
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == "$OWN/primary" }
            event<WorldEvent.ManifestPublished> { OWN in it.added }
            neverTheReceivedPhoto()
        }
    }

    /** Under a partial grant an import is added to the selection, and the selection is the gallery. */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_selection_holding_an_import_in_flight_shares_only_the_members_own() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            importParked(afterCommit = true)
            photos(OWN)
            photoAccess(PhotoGrant.LIMITED)
            step("the app opened", act = { os.foreground() }, until = { settled { true } })
        }
        whenever {
            library.changeSelection(listOf(OWN, imported(ASSET)))
            heartbeat()
        }
        then { onlyTheOwnPhotoLeftTheApp() }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_relaunch_during_an_import_shares_only_the_members_own() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            importParked(afterCommit = true)
        }
        whenever {
            host.relaunch()
            takePhoto(OWN)
            os.foreground()
        }
        then { onlyTheOwnPhotoLeftTheApp() }
    }

    /**
     * A wake's two pieces of work run together: its own import, stalled in the library, holds nothing back. The refused
     * import leaves the photo staged and no pass running, so it is the wake's own pass that imports it, and parks.
     */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_wakes_own_import_stalling_does_not_hold_its_uploads_back() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            downloadStarted(DEV_F, ASSET)
            step(
                "its bytes staged, its first import refused",
                act = {
                    library.failNextImport()
                    downloadSession.finish()
                },
                until = { settled { true } },
            )
        }
        whenever {
            library.suspendNextImport()
            ownPhotoThenWake()
        }
        then {
            event<WorldEvent.LibraryImportParked>()
            onlyTheOwnPhotoLeftTheApp()
        }
    }

    private companion object {
        const val ASSET = "FQ"
        const val OWN = "P1"
    }
}
