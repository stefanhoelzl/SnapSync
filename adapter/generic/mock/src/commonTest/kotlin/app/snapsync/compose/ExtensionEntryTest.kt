package app.snapsync.compose

import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.data.trust.TokenText
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.BackendMock
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ExtensionHostMock
import app.snapsync.mock.PhotoLibraryMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.mock.UploadNetwork
import app.snapsync.mock.UploadQueueMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryCrashReporter
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.testids.testEventId
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.Dispatchers
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
@OptIn(AppProcessOnly::class)
class ExtensionEntryTest {

    @Test
    fun a_token_the_app_renewed_between_invocations_is_the_one_the_next_invocation_sends() = runTest {
        val host = ExtensionHostMock()
        snapSyncExtension(extensionPorts(host), lane = Dispatchers.Default, answerOn = null)
        joinedWithAToken("token-before")

        host.process(Dispatchers.Default)
        assertEquals(
            listOf<String?>("token-before"),
            publishedWith,
            "the first invocation sends the token the app stored",
        )

        // The app renews into the shared item, and a new manifest is due (the app's enrolment invalidates the record).
        attest.setToken(TokenText.parse("token-after"))
        DeviceManifestService(appDatabase).clearLastUploaded()
        host.process(Dispatchers.Default)
        assertEquals(
            listOf<String?>("token-before", "token-after"),
            publishedWith,
            "the copy is re-read — never cached across invocations",
        )
    }

    private val files = inMemoryFiles()
    private val clock = fixedClock(Instant.parse("2026-07-15T00:00:00Z"))
    private val databases = inMemoryDatabases()
    private val secureStore = inMemorySecureStore()
    private val attest = SecureCredentialStore(secureStore)
    private val library = PhotoLibraryMock()

    /** The token each manifest publish carried, in order. */
    private val publishedWith = mutableListOf<String?>()

    private val backend = BackendMock().port().let { mock ->
        HttpTransport { request ->
            if (request.method == Routes.PublishManifest.METHOD && request.url.endsWith("/manifest")) {
                publishedWith += request.headers["Authorization"]?.removePrefix("Bearer ")
                HttpResult.Response(200, emptyMap(), "")
            } else {
                mock.send(request)
            }
        }
    }

    /**
     * The shared database as the APP opens it: its first open copies the records' legacy homes (none here), which is
     * what lets the extension's cycle run rather than pause.
     */
    private val appDatabase by lazy { SharedDatabase(databases, LegacyRecords(files, inMemoryPreferences())) }

    /** What the app leaves behind for the extension: an identity, a token, a membership and the download store. */
    private suspend fun joinedWithAToken(token: String) {
        MintingDeviceIdentity(secureStore, platformDeviceId = { null }).id()
        attest.setToken(TokenText.parse(token))
        // The membership row as a join writes it.
        appDatabase.use {
            it.membershipQueries.saveMembership(
                eventId = testEventId("11111111-1111-4111-8111-111111111111"),
                name = "Anna's Birthday",
                minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-14T18:00:00Z")),
                startsAt = EventStart(TimestampText.parse("2026-07-14T18:00:00Z")),
                endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
                maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-21T18:00:00Z")),
                deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = false,
                membersActive = null,
                membersSettled = null,
                membersWaiting = null,
                keyId = null,
                openUntil = null,
                deleted = false,
            )
        }
        // The app creates and migrates the download store — the extension only ever opens it read-only — and has
        // recognised this membership's received photos, without which the extension shares nothing of it.
        DownloadService(databases).recognition.mark(testEventId("11111111-1111-4111-8111-111111111111"))
    }

    /** The extension's ports over the device's mocks, as its root assembles them. */
    private fun extensionPorts(host: ExtensionHostMock) = ExtensionPorts(
        process = ProcessPorts(
            crashReporter = inMemoryCrashReporter(),
            processMetrics = NoProcessMetrics,
            logSinks = emptyList(),
            files = files,
            clock = clock,
            crypto = fakeCrypto(),
            threadLabel = ThreadLabelMock(),
            build = BuildInfoMock().port(),
        ),
        databases = databases,
        preferences = inMemoryPreferences(),
        secureStore = secureStore,
        platformDeviceId = { null },
        gallery = library.port(),
        uploadQueue = UploadQueueMock(
            UploadNetwork { _, _, _ -> error("no upload is expected") },
            restricted = { false },
        ).port(),
        http = backend,
        host = host.port(),
    )
}
