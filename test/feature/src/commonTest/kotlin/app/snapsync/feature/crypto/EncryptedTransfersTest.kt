package app.snapsync.feature.crypto

import app.snapsync.services.crypto.EventKeyMinting
import app.snapsync.services.upload.TransferRecord
import app.snapsync.model.UploadJobState
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.LedgerState
import app.snapsync.model.LedgerEntry
import app.snapsync.feature.support.RecordingDownload
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testIdentity
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetId
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EventConfig
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.HeadRead
import app.snapsync.model.Hmac
import app.snapsync.model.Resource
import app.snapsync.model.TransferNetwork
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.WriteOutcome
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.decodeEventKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import app.snapsync.services.crypto.DownloadOpening
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.crypto.Opened
import app.snapsync.services.crypto.UploadSealing
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.gallery.Discovery
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.services.staging.StagingService
import app.snapsync.services.upload.UploadTransferService
import app.snapsync.services.upload.uploadStagingPath
import app.snapsync.model.SelectionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An encrypted event's transfers, end to end through the real services (the encrypted file format,
 * `docs/architecture.md`): what a device seals on the way up is what the other members open on the way down, a platform
 * that cannot hand over a file gets the one-file key the edge seals with, and nothing goes up — or is staged — without
 * the event's key. Over the stand-in crypto, which is honest about what opens; the real primitives' bytes are pinned by
 * the format's vectors.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncryptedTransfersTest {

    private val crypto: Crypto = fakeCrypto()
    private val photo = ByteArray(150_000) { (it * 11 + 2).toByte() }

    private fun joined(keyId: String?) = EventConfig(
        eventId = EVENT,
        name = "Secret",
        minPhotoDate = captureCutoff("2026-01-01T00:00:00Z"),
        maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"),
        keyId = keyId,
    )

    /** One member's device: its files, its kept key, its membership. */
    private inner class Device(val id: String, val linkKey: String?, keyId: String?) {
        val files = RecordingFiles()
        val keys = EventKeys(crypto, inMemorySecureStore()).also { k -> linkKey?.let(k::keep) }
        val config = configService(joined(keyId), files)
        val cipher = FileCipher(crypto, files)
        val sealing = UploadSealing(keys, cipher, config, testIdentity(id))
    }

    /** A platform that takes [kind], and remembers what it was asked to send. */
    private class Platform(override val accepts: UploadSourceKind) : Upload {
        val created = mutableListOf<Pair<UploadSource, UploadTarget>>()
        val retried = mutableListOf<UploadTarget>()
        var offered: List<UploadJob> = emptyList()
        override fun listen(handlers: UploadHandlers) = Unit
        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
            created += source to target
            return UploadCreateOutcome.CREATED
        }
        override suspend fun jobs(set: UploadJobSet): List<UploadJob> = if (set == UploadJobSet.RETRY_OFFERED) offered else emptyList()
        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
            retried += target
            return ChangeOutcome.Applied
        }
        override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied
        override suspend fun cancel(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied
    }

    /** A library whose export writes [photo] where it is told — into the device's in-memory shared area. */
    private fun exporting(files: RecordingFiles): GalleryReader =
        object : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
            override suspend fun export(resource: Resource, to: String): WriteOutcome {
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
            LedgerEntry(key = KEY, assetId = AssetId(ASSET), state = LedgerState.REQUESTED, destinationPath = destinationPath)
        override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean = true
    }

    private fun transfer(device: Device, platform: Upload) = UploadTransferService(
        upload = platform,
        record = record,
        resources = noDiscovery,
        gallery = exporting(device.files),
        files = device.files,
        network = { TransferNetwork.ANY },
        sealing = device.sealing,
    )

    private val resource = Resource(KEY, AssetId(ASSET), "public.heic", emptyMap(), Unit)
    private val request = UploadRequest("https://edge.example/api/v2/events/$EVENT/files/devices/$DEVICE_A/$ASSET/primary?filename=IMG.HEIC", mapOf("Content-Type" to "image/heic"), resource)

    private fun adFor(device: String) = EncryptedFileFormat.associatedData(EVENT, device, ASSET, "primary")

    @Test
    fun a_sealed_upload_is_what_another_member_opens_and_the_plaintext_never_waits_on_disk() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val sharer = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val platform = Platform(UploadSourceKind.FILE)

        assertEquals(UploadCreateOutcome.CREATED, transfer(sharer, platform).createJob(request, resource))
        val (source, target) = platform.created.single()
        assertEquals("mem:/shared/${uploadStagingPath(KEY)}", (source as UploadSource.File).path)
        assertTrue(target.headers.keys.none { it.startsWith("x-snapsync-file") }, "a file sealed here needs no edge seal")
        val sealed = assertIs<FileResult.Ok<ByteArray>>(sharer.files.read(FileArea.SHARED, uploadStagingPath(KEY))).value
        assertEquals(FileResult.Ok(false), sharer.files.exists(FileArea.SHARED, "${uploadStagingPath(KEY)}.plain"))

        // Another member downloads those bytes and stages them opened.
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        receiver.files.write(FileArea.SHARED, "os-tmp/1", sealed)
        val staged = mutableListOf<String>()
        val jobs = DownloadJobs(
            scope = this,
            staging = StagingService(receiver.files),
            download = RecordingDownload(),
            onStaged = { _, _, path -> staged += path },
            network = { TransferNetwork.ANY },
            opening = DownloadOpening(receiver.keys, receiver.cipher, receiver.config, receiver.files),
        )
        jobs.onFinished("$DEVICE_A\n$ASSET\n$KEY\n$EVENT", HEALTHY, "mem:/shared/os-tmp/1")
        advanceUntilIdle()
        val path = staged.single()
        assertContentEquals(photo, assertIs<FileResult.Ok<ByteArray>>(receiver.files.read(FileArea.SHARED, path)).value)
        assertEquals(FileResult.Ok(false), receiver.files.exists(FileArea.SHARED, "$path.sealed"))
    }

    @Test
    fun a_platform_that_sends_the_librarys_own_bytes_carries_that_one_files_key_for_the_edge() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val platform = Platform(UploadSourceKind.RESOURCE)
        assertEquals(UploadCreateOutcome.CREATED, transfer(device, platform).createJob(request, resource))
        val (source, target) = platform.created.single()
        assertIs<UploadSource.Resource>(source)
        val head = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(decodeBase64(target.headers.getValue(EncryptedFileFormat.FILE_HEAD_HEADER))),
        ).head
        val eventKey = decodeEventKey(minted.linkKey)!!
        assertContentEquals(EncryptedFileFormat.keyIdOf(eventKey, Hmac(crypto::hmacSha256)), head.keyId)
        assertContentEquals(
            EncryptedFileFormat.fileKeyOf(eventKey, head.salt, adFor(DEVICE_A), Hmac(crypto::hmacSha256)),
            decodeEventKey(target.headers.getValue(EncryptedFileFormat.FILE_KEY_HEADER)),
            "the one file's key, bound to this resource — never the event key",
        )
    }

    @Test
    fun without_the_events_key_nothing_goes_up() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val other = EventKeys(crypto, inMemorySecureStore()).mint()
        for (device in listOf(Device(DEVICE_A, null, minted.keyId), Device(DEVICE_A, other.linkKey, minted.keyId))) {
            for (kind in UploadSourceKind.entries) {
                val platform = Platform(kind)
                assertEquals(UploadCreateOutcome.FAILED, transfer(device, platform).createJob(request, resource))
                assertTrue(platform.created.isEmpty(), "$kind: no job")
            }
        }
    }

    @Test
    fun a_plain_event_uploads_as_before() = runTest {
        val device = Device(DEVICE_A, null, null)
        val platform = Platform(UploadSourceKind.FILE)
        transfer(device, platform).createJob(request, resource)
        assertContentEquals(photo, assertIs<FileResult.Ok<ByteArray>>(device.files.read(FileArea.SHARED, uploadStagingPath(KEY))).value)
    }

    @Test
    fun a_download_that_does_not_open_is_never_staged_and_leaves_nothing_behind() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        val staged = mutableListOf<String>()
        val jobs = DownloadJobs(
            scope = this,
            staging = StagingService(receiver.files),
            download = RecordingDownload(),
            onStaged = { _, _, path -> staged += path },
            network = { TransferNetwork.ANY },
            opening = DownloadOpening(receiver.keys, receiver.cipher, receiver.config, receiver.files),
        )
        // Plaintext, as a build predating encryption would have stored it — or anything else that is not a file of this key.
        repeat(5) { attempt ->
            receiver.files.write(FileArea.SHARED, "os-tmp/$attempt", photo)
            jobs.onFinished("$DEVICE_A\n$ASSET\n$KEY\n$EVENT", HEALTHY, "mem:/shared/os-tmp/$attempt")
        }
        advanceUntilIdle()
        assertTrue(staged.isEmpty())
        assertTrue(receiver.files.operations.none { it.startsWith("write") && !it.contains("os-tmp") && !it.contains("eventconfig") })
    }

    @Test
    fun a_file_opens_only_as_the_resource_it_was_sealed_as() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val key = decodeEventKey(minted.linkKey)!!
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


    private fun offeredJob() = UploadJob(
        handle = "job", tag = null, destinationPath = "/api/v2/events/$EVENT/files/devices/$DEVICE_A/$ASSET/primary",
        contentType = "image/heic", state = UploadJobState.FAILED, error = null, source = null,
    )

    @Test
    fun the_platforms_own_retry_renews_the_edge_seal_and_a_missing_key_withholds_it() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val sealed = Platform(UploadSourceKind.RESOURCE).apply { offered = listOf(offeredJob()) }
        val transfer = transfer(Device(DEVICE_A, minted.linkKey, minted.keyId), sealed)
        transfer.retryJob(transfer.fetchRetryJobs().single(), request)
        assertTrue(EncryptedFileFormat.FILE_KEY_HEADER in sealed.retried.single().headers, "a retry carries a fresh one-file key")

        val withheld = Platform(UploadSourceKind.RESOURCE).apply { offered = listOf(offeredJob()) }
        val keyless = transfer(Device(DEVICE_A, null, minted.keyId), withheld)
        keyless.retryJob(keyless.fetchRetryJobs().single(), request)
        assertTrue(withheld.retried.isEmpty(), "never a retry that would send plaintext")
    }

    @Test
    fun a_seal_that_cannot_be_written_creates_no_job_and_leaves_nothing_staged() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        device.files.denied += FileArea.SHARED to "${uploadStagingPath(KEY)}.part"
        val platform = Platform(UploadSourceKind.FILE)
        assertEquals(UploadCreateOutcome.FAILED, transfer(device, platform).createJob(request, resource))
        assertTrue(platform.created.isEmpty())
        assertEquals(FileResult.Ok(false), device.files.exists(FileArea.SHARED, "${uploadStagingPath(KEY)}.plain"))
    }

    @Test
    fun a_new_event_is_encrypted_only_while_the_builds_control_says_so() {
        val controls = app.snapsync.mock.DevControlsMock()
        val minting = EventKeyMinting(EventKeys(crypto, inMemorySecureStore()), controls.port())
        assertEquals(null, minting.forNewEvent(), "a shipped build creates plain events")
        controls.operator.encryptsNewEvents = true
        val minted = minting.forNewEvent()!!
        assertEquals(16, minted.keyId.length)
    }
}
