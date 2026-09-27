package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.mock.ExtensionHostMock
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.mock.UploadNetwork
import app.snapsync.mock.UploadQueueMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryCrashReporter
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.GalleryAccess
import app.snapsync.model.selectionScope
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The upload extension's entry port, over the handlers its composition registers (`snapSyncExtension`): the in-memory
 * device token is re-read at **every** operating-system invocation (capability `privacy-security`).
 *
 * The app renews into the Keychain item both processes share, and the extension's copy cannot see that; the re-read
 * at each `process()` is what bounds the copy's staleness to one invocation. Pinned here, in the composition's zone,
 * because nothing outside the process can see a re-read: the JVM root's extension is unauthenticated, and on a device
 * the token's staleness shows only as a refusal much later. What each invocation answers the operating system is
 * `UploadCycleIntegrationTest`'s.
 */
class ExtensionEntryTest {

    @Test
    fun every_invocation_rereads_the_credential_even_one_with_nothing_to_do() = runTest {
        val host = ExtensionHostMock()
        var rereads = 0
        val ports = uploadPorts()
        val process = snapSyncProcess(
            ProcessPorts(
                crashReporter = inMemoryCrashReporter(),
                processMetrics = NoProcessMetrics,
                logSinks = emptyList(),
                files = files,
                clock = clock,
                entryContext = NoEntryContext,
                dsn = null,
                bootLines = emptyList(),
                ownsGlobalLogger = false,
            ),
        )
        val cycle by lazy { uploadCore(this, process, ports) }
        snapSyncExtension(host.port(), ports = { ports }, cycle = { cycle }, rereadCredential = { rereads++ })

        host.operator.process() // no membership: nothing to do
        assertEquals(1, rereads, "an invocation with nothing to do still re-reads")

        host.operator.process()
        host.operator.process()
        assertEquals(3, rereads, "once per invocation — never cached across them")
    }

    private val files = inMemoryFiles()
    private val clock = fixedClock(Instant.parse("2026-06-01T00:00:00Z"))
    private val databases = inMemoryDatabases()
    private val secureStore = inMemorySecureStore()
    private val library = PhotoLibraryMock()

    /** The extension's upload ports over the device's mocks, as its root assembles them. */
    private fun uploadPorts(): UploadPorts {
        val albums = GalleryAlbums(library.port())
        return UploadPorts(
            config = ConfigService(files, clock),
            deviceIdentity = PersistedDeviceIdentity(DeviceIdentityRole.READ_ONLY, secureStore, platformDeviceId = { null }),
            host = "https://in-memory.backend/api/v2",
            ledger = LedgerService(databases),
            upload = UploadQueueMock(UploadNetwork { _, _, _ -> error("no upload is expected") }).port(),
            gallery = library.port(),
            discovery = GalleryDiscovery(library.port()),
            process = UploaderProcess.Extension { GalleryAccess.GRANTED },
            selectionScope = { selectionScope(GalleryAccess.GRANTED, null) },
            manifestStore = DeviceManifestService(files),
            manifestPublisher = { _, _, _ -> true },
            suppression = DownloadService(databases),
            albumManager = albums,
            albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
            albumCoordinator = AlbumCoordinator(albums, AlbumMapService(inMemoryPreferences(), secureStore)),
            token = { null },
            freshToken = { null },
            appVersion = "99.0",
        )
    }
}
