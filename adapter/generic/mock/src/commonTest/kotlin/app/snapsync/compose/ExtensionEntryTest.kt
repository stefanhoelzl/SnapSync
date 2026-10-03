package app.snapsync.compose

import app.snapsync.mock.BackendMock
import app.snapsync.mock.ExtensionHostMock
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.mock.UploadNetwork
import app.snapsync.mock.UploadQueueMock
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryCrashReporter
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventConfig
import app.snapsync.model.Reply
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.Backend
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.identity.AttestState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.manifest.DeviceManifestService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The upload extension's entry port, over the handlers its composition registers (`snapSyncExtension`): the in-memory
 * device token is re-read at **every** operating-system invocation (capability `privacy-security`).
 *
 * The app renews into the protected item both processes share, and the extension's copy cannot see that; the re-read at
 * each `process()` is what bounds the copy's staleness to one invocation. Pinned here, in the composition's zone,
 * because nothing outside the process can see it early: on a device the token's staleness shows only as a refusal much
 * later. What each invocation answers the operating system is `UploadCycleIntegrationTest`'s.
 */
class ExtensionEntryTest {

    @Test
    fun a_token_the_app_renewed_between_invocations_is_the_one_the_next_invocation_sends() = runTest {
        val host = ExtensionHostMock()
        snapSyncExtension(extensionPorts(host))
        joinedWithAToken("token-before")

        host.operator.process()
        assertEquals(listOf<String?>("token-before"), publishedWith, "the first invocation sends the token the app stored")

        // The app renews into the shared item, and a new manifest is due (the app's enrolment invalidates the record).
        attest.setToken("token-after")
        DeviceManifestService(files).clearLastUploaded()
        host.operator.process()
        assertEquals(listOf<String?>("token-before", "token-after"), publishedWith, "the copy is re-read — never cached across invocations")
    }

    private val files = inMemoryFiles()
    private val clock = fixedClock(Instant.parse("2026-07-15T00:00:00Z"))
    private val databases = inMemoryDatabases()
    private val secureStore = inMemorySecureStore()
    private val attest = AttestState(secureStore)
    private val library = PhotoLibraryMock()

    /** The token each manifest publish carried, in order. */
    private val publishedWith = mutableListOf<String?>()

    private val backend: Backend = object : Backend by BackendMock().port() {
        override suspend fun publishManifest(
            token: String?,
            eventId: String,
            deviceId: String,
            manifest: DeviceManifest,
        ): Reply<Unit> {
            publishedWith += token
            return Reply.Ok(Unit)
        }
    }

    /** What the app leaves behind for the extension: an identity, a token, a membership and the download store. */
    private suspend fun joinedWithAToken(token: String) {
        PersistedDeviceIdentity(DeviceIdentityRole.MINTING, secureStore, platformDeviceId = { null }).deviceId()
        attest.setToken(token)
        ConfigService(files, clock).save(
            EventConfig(
                eventId = "11111111-1111-4111-8111-111111111111",
                name = "Anna's Birthday",
                minPhotoDate = captureCutoff("2026-07-14T18:00:00Z"),
                maxPhotoDate = captureCeiling("2026-07-21T18:00:00Z"),
                saveToAlbum = false,
            ),
        )
        // The app creates and migrates the download store; the extension only ever opens it read-only.
        DownloadService(databases).suppressedLocalIds()
    }

    /** The extension's ports over the device's mocks, as its root assembles them. */
    private fun extensionPorts(host: ExtensionHostMock) = ExtensionPorts(
        process = ProcessPorts(
            crashReporter = inMemoryCrashReporter(),
            processMetrics = NoProcessMetrics,
            logSinks = emptyList(),
            files = files,
            clock = clock,
            entryContext = NoEntryContext,
            build = BuildInfoMock().port(),
        ),
        databases = databases,
        preferences = inMemoryPreferences(),
        secureStore = secureStore,
        platformDeviceId = { null },
        gallery = library.port(),
        upload = UploadQueueMock(UploadNetwork { _, _, _ -> error("no upload is expected") }, restricted = { false }).port(),
        backend = backend,
        host = host.port(),
    )
}
