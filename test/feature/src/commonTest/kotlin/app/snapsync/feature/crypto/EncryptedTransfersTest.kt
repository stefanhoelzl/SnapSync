package app.snapsync.feature.crypto

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.upload.LedgerState
import app.snapsync.feature.support.RecordingDownload
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.TestEventKeys
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testIdentity
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.EventKeyText
import app.snapsync.format.HeadRead
import app.snapsync.format.Hmac
import app.snapsync.format.TimestampText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetRef
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferOutcome
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.crypto.DownloadOpening
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.crypto.Opened
import app.snapsync.services.crypto.UploadSealing
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.gallery.Discovery
import app.snapsync.services.gallery.GalleryResourceRef
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.services.staging.StagingService
import app.snapsync.services.upload.OsQueueTransfer
import app.snapsync.services.upload.SessionTransfer
import app.snapsync.services.upload.TransferRecord
import app.snapsync.services.upload.TransferSetup
import app.snapsync.services.upload.uploadStagingPath
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An encrypted event's transfers, end to end through the real services (the encrypted file format,
 * `docs/architecture.md`): what a device seals on the way up is what the other members open on the way down, a platform
 * that cannot hand over a file gets the one-file key the edge seals with, and nothing goes up — or is staged — without
 * the event's key. Over the stand-in crypto, which is honest about what opens; the real primitives' bytes are pinned by
 * the format's vectors.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, KeyMaterial::class)
class EncryptedTransfersTest {

    private val crypto: Crypto = fakeCrypto()
    private val photo = ByteArray(150_000) { (it * 11 + 2).toByte() }

    private fun joined(keyId: String?) = EventConfig(
        eventId = testEventId(EVENT),
        name = "Secret",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
        keyId = keyId,
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )

    /** One member's device: its files, its kept key, its membership. */
    private inner class Device(val id: String, val linkKey: String?, keyId: String?) {
        val files = RecordingFiles()
        val keys = TestEventKeys(crypto, inMemorySecureStore()).also { k -> linkKey?.let(k::keep) }
        val config = configService(joined(keyId))
        val cipher = FileCipher(crypto, files)
        val sealing = UploadSealing(keys.store, cipher, config, testIdentity(id))
    }

    /** The app's session, sending files, remembering what it was asked to send. */
    private class FileSession : TransferSession {
        override val takesResources = false
        val created = mutableListOf<Pair<TransferSource, UploadTarget>>()

        @CompositionOnly
        override fun inbound(handlers: TransferHandlers) = Unit
        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome {
            created += source to target
            return CreateOutcome.CREATED
        }
        override suspend fun live(): List<Transfer> = emptyList()
        override suspend fun cancel(transfer: Transfer) = Unit
    }

    /** The OS's job queue, sending the library's own bytes, remembering what it was asked to send and re-point. */
    private class Queue : OsUploadQueue {
        val created = mutableListOf<Pair<GalleryResourceHandle, UploadTarget>>()
        val retried = mutableListOf<UploadTarget>()
        var offered: List<OsUploadJob> = emptyList()
        override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome {
            created += resource to target
            return CreateOutcome.CREATED
        }
        override suspend fun retryOffered(): List<OsUploadJob> = offered
        override suspend fun terminals(): List<OsUploadJob> = emptyList()
        override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
            retried += target
            return ChangeOutcome.Applied
        }
        override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome = ChangeOutcome.Applied
    }

    /** A library whose export writes [photo] where it is told — into the device's in-memory shared area. */
    private fun exporting(files: RecordingFiles): GalleryReader =
        object : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
            override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
                files.write(FileArea.SHARED, to.removePrefix("mem:/shared/"), photo)
                return WriteOutcome.Ok
            }
        }

    private val noDiscovery = object : UploadDiscovery {
        override suspend fun discover(policy: SelectionPolicy): Discovery = error("not walked here")
        override suspend fun resourcesFor(keys: Set<String>): List<Resource> = emptyList()
    }

    /** The one row a retry resolves its offered job to — by destination, as the ledger does. */
    private val record = object : TransferRecord {
        override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
            LedgerEntry(
                key = KEY,
                assetId = testAssetId(ASSET),
                state = LedgerState.REQUESTED,
                destinationPath = destinationPath,
            )
        override suspend fun entryForKey(key: String): LedgerEntry? = null
        override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean = true
    }

    private fun setup(device: Device) = TransferSetup(
        record = record,
        resources = noDiscovery,
        gallery = exporting(device.files),
        files = device.files,
        network = { TransferNetwork.ANY },
        sealing = device.sealing,
    )

    private fun transfer(device: Device, session: TransferSession) = SessionTransfer(session, setup(device))

    private fun transfer(device: Device, queue: OsUploadQueue) = OsQueueTransfer(queue, setup(device))

    private val resource = Resource(KEY, testAssetId(ASSET), "public.heic", emptyMap(), GalleryResourceRef(TestPhoto))

    /** The photo library's handle of the one photo these transfers send. */
    private object TestPhoto : GalleryResourceHandle
    private val request = UploadRequest(
        "https://edge.example/api/v2/events/$EVENT/files/devices/$DEVICE_A/$ASSET/primary?filename=IMG.HEIC",
        mapOf("Content-Type" to "image/heic"),
        resource,
    )

    private fun adFor(device: String) = EncryptedFileFormat.associatedData(
        FileBinding(EventId.parse(EVENT)!!, DeviceId.parse(device)!!, AssetId.parse(ASSET)!!, ResourceRole.PRIMARY),
    )

    @Test
    fun a_sealed_upload_is_what_another_member_opens_and_the_plaintext_never_waits_on_disk() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val sharer = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val session = FileSession()

        assertEquals(UploadCreateOutcome.CREATED, transfer(sharer, session).createJob(request, resource))
        val (source, target) = session.created.single()
        assertEquals("mem:/shared/${uploadStagingPath(KEY)}", assertIs<TransferSource.File>(source).path)
        assertTrue(
            target.headers.keys.none { it.startsWith("x-snapsync-file") },
            "a file sealed here needs no edge seal",
        )
        val sealed = assertIs<FileResult.Ok<ByteArray>>(
            sharer.files.read(FileArea.SHARED, uploadStagingPath(KEY)),
        ).value
        assertEquals(FileResult.Ok(false), sharer.files.exists(FileArea.SHARED, "${uploadStagingPath(KEY)}.plain"))

        // Another member downloads those bytes and stages them opened.
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        receiver.files.write(FileArea.SHARED, "os-tmp/1", sealed)
        val staged = mutableListOf<String>()
        val jobs = DownloadJobs(
            staging = StagingService(receiver.files),
            download = RecordingDownload(),
            onStaged = { _, _, path -> staged += path },
            network = { TransferNetwork.ANY },
            headers = { emptyMap() },
            opening = DownloadOpening(receiver.keys.store, receiver.cipher, receiver.config, receiver.files),
        )
        jobs.onFinished(
            "$DEVICE_A\n$ASSET\n$KEY\n$EVENT",
            HEALTHY,
            "mem:/shared/os-tmp/1",
        ) { body -> launch { body() } }
        advanceUntilIdle()
        val path = staged.single()
        assertContentEquals(photo, assertIs<FileResult.Ok<ByteArray>>(receiver.files.read(FileArea.SHARED, path)).value)
        assertEquals(FileResult.Ok(false), receiver.files.exists(FileArea.SHARED, "$path.sealed"))
    }

    @Test
    fun a_platform_that_sends_the_librarys_own_bytes_carries_that_one_files_key_for_the_edge() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val queue = Queue()
        assertEquals(UploadCreateOutcome.CREATED, transfer(device, queue).createJob(request, resource))
        val (source, target) = queue.created.single()
        assertEquals<GalleryResourceHandle>(TestPhoto, source)
        val head = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(decodeBase64(target.headers.getValue(EncryptedFileFormat.FILE_HEAD_HEADER))),
        ).head
        val eventKey = EventKeyText.decode(minted.linkKey)!!.bytes()
        assertContentEquals(EncryptedFileFormat.keyIdOf(eventKey, portHmac(crypto)), head.keyId)
        assertContentEquals(
            EncryptedFileFormat.fileKeyOf(eventKey, head.salt, adFor(DEVICE_A), portHmac(crypto)),
            decodeBase64(target.headers.getValue(EncryptedFileFormat.FILE_KEY_HEADER)),
            "the one file's key, bound to this resource — never the event key",
        )
    }

    @Test
    fun without_the_events_key_nothing_goes_up() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val other = TestEventKeys(crypto, inMemorySecureStore()).mint()
        for (device in listOf(Device(DEVICE_A, null, minted.keyId), Device(DEVICE_A, other.linkKey, minted.keyId))) {
            val session = FileSession()
            assertEquals(UploadCreateOutcome.FAILED, transfer(device, session).createJob(request, resource))
            assertTrue(session.created.isEmpty(), "the session: no transfer")
            val queue = Queue()
            assertEquals(UploadCreateOutcome.FAILED, transfer(device, queue).createJob(request, resource))
            assertTrue(queue.created.isEmpty(), "the queue: no job")
        }
    }

    @Test
    fun a_plain_event_uploads_as_before() = runTest {
        val device = Device(DEVICE_A, null, null)
        transfer(device, FileSession()).createJob(request, resource)
        assertContentEquals(
            photo,
            assertIs<FileResult.Ok<ByteArray>>(device.files.read(FileArea.SHARED, uploadStagingPath(KEY))).value,
        )
    }

    @Test
    fun a_download_that_does_not_open_is_never_staged_and_leaves_nothing_behind() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        val staged = mutableListOf<String>()
        val jobs = DownloadJobs(
            staging = StagingService(receiver.files),
            download = RecordingDownload(),
            onStaged = { _, _, path -> staged += path },
            network = { TransferNetwork.ANY },
            headers = { emptyMap() },
            opening = DownloadOpening(receiver.keys.store, receiver.cipher, receiver.config, receiver.files),
        )
        // Plaintext, as a build predating encryption would have stored it — or anything else that is not a file of this key.
        repeat(5) { attempt ->
            receiver.files.write(FileArea.SHARED, "os-tmp/$attempt", photo)
            jobs.onFinished(
                "$DEVICE_A\n$ASSET\n$KEY\n$EVENT",
                HEALTHY,
                "mem:/shared/os-tmp/$attempt",
            ) { body -> launch { body() } }
        }
        advanceUntilIdle()
        assertTrue(staged.isEmpty())
        assertTrue(
            receiver.files.operations.none {
                it.startsWith("write") && !it.contains("os-tmp") && !it.contains("eventconfig")
            },
        )
    }

    @Test
    fun a_download_with_no_key_kept_is_never_staged() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        // The event is encrypted, and this device lost its key: nothing opens, and nothing is staged.
        val receiver = Device(DEVICE_B, linkKey = null, keyId = minted.keyId)
        val opening = DownloadOpening(receiver.keys.store, receiver.cipher, receiver.config, receiver.files)
        repeat(5) { attempt ->
            receiver.files.write(FileArea.SHARED, "os-tmp/$attempt", photo)
            assertFalse(
                opening.open(
                    AssetRef(testDeviceId(DEVICE_A), testAssetId(ASSET)),
                    KEY,
                    EVENT,
                    "os-tmp/$attempt",
                    "staged/$attempt",
                ),
            )
        }
        assertTrue(receiver.files.operations.none { it.startsWith("write") && it.contains("staged/") })
    }

    @Test
    fun a_file_opens_only_as_the_resource_it_was_sealed_as() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val key = EventKeyText.decode(minted.linkKey)!!.bytes()
        device.files.write(FileArea.SHARED, "p", photo)
        device.cipher.encrypt(key, adFor(DEVICE_A), FileArea.SHARED, "p", "s")
        assertIs<Opened.Damaged>(device.cipher.decrypt(key, adFor(DEVICE_B), FileArea.SHARED, "s", "o"))
        assertEquals(Opened.Ok, device.cipher.decrypt(key, adFor(DEVICE_A), FileArea.SHARED, "s", "o"))
    }

    private fun decodeBase64(text: String): ByteArray =
        kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).decode(text)

    private companion object {
        const val EVENT = "e0000000-0000-4000-8000-000000000001"
        const val DEVICE_A = "A0000000-0000-4000-8000-00000000000A"
        const val DEVICE_B = "B0000000-0000-4000-8000-00000000000B"
        const val ASSET = "ASSET1"
        const val KEY = "ASSET1-primary.heic"
        val HEALTHY = TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1L)
    }

    private object OfferedJob : OsUploadJobHandle

    private fun offeredJob() = OsUploadJob(
        handle = OfferedJob,
        destinationUrl = "https://edge.example/api/v2/events/$EVENT/files/devices/$DEVICE_A/$ASSET/primary",
        contentType = "image/heic",
        state = UploadJobState.FAILED,
        failure = null,
        resource = null,
    )

    @Test
    fun the_platforms_own_retry_renews_the_edge_seal_and_a_missing_key_withholds_it() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val sealed = Queue().apply { offered = listOf(offeredJob()) }
        val transfer = transfer(Device(DEVICE_A, minted.linkKey, minted.keyId), sealed)
        transfer.retryJob(transfer.fetchRetryJobs().single(), request)
        assertTrue(
            EncryptedFileFormat.FILE_KEY_HEADER in sealed.retried.single().headers,
            "a retry carries a fresh one-file key",
        )

        val withheld = Queue().apply { offered = listOf(offeredJob()) }
        val keyless = transfer(Device(DEVICE_A, null, minted.keyId), withheld)
        keyless.retryJob(keyless.fetchRetryJobs().single(), request)
        assertTrue(withheld.retried.isEmpty(), "never a retry that would send plaintext")
    }

    @Test
    fun a_seal_that_cannot_be_written_creates_no_job_and_leaves_nothing_staged() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        device.files.denied += FileArea.SHARED to "${uploadStagingPath(KEY)}.part"
        val session = FileSession()
        assertEquals(UploadCreateOutcome.FAILED, transfer(device, session).createJob(request, resource))
        assertTrue(session.created.isEmpty())
        assertEquals(FileResult.Ok(false), device.files.exists(FileArea.SHARED, "${uploadStagingPath(KEY)}.plain"))
    }

    @Test
    fun a_seal_with_nowhere_to_export_the_plaintext_creates_no_job() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        device.files.unlocatable += "${uploadStagingPath(KEY)}.plain"
        val session = FileSession()
        assertEquals(UploadCreateOutcome.FAILED, transfer(device, session).createJob(request, resource))
        assertTrue(session.created.isEmpty())
    }

    @Test
    fun a_plain_events_download_is_staged_as_it_arrived() = runTest {
        val receiver = Device(DEVICE_B, linkKey = null, keyId = null)
        val staged = mutableListOf<String>()
        val jobs = DownloadJobs(
            staging = StagingService(receiver.files),
            download = RecordingDownload(),
            onStaged = { _, _, path -> staged += path },
            network = { TransferNetwork.ANY },
            headers = { emptyMap() },
            opening = DownloadOpening(receiver.keys.store, receiver.cipher, receiver.config, receiver.files),
        )
        receiver.files.write(FileArea.SHARED, "os-tmp/0", photo)
        jobs.onFinished(
            "$DEVICE_A\n$ASSET\n$KEY\n$EVENT",
            HEALTHY,
            "mem:/shared/os-tmp/0",
        ) { body -> launch { body() } }
        advanceUntilIdle()
        assertEquals(1, staged.size, "nothing to open: the bytes are the photo")
    }
}

/** The format's HMAC over [crypto]'s. */
private fun portHmac(crypto: Crypto): Hmac = object : Hmac {
    override fun mac(key: ByteArray, message: ByteArray): ByteArray = crypto.hmacSha256(key, message)
}
