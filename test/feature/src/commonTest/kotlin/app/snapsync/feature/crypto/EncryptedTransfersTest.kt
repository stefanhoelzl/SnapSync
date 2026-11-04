package app.snapsync.feature.crypto

import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.Landing
import app.snapsync.data.crypto.OpenAnswer
import app.snapsync.data.crypto.UploadSeal
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.Landed
import app.snapsync.data.receiving.StagingArea
import app.snapsync.data.receiving.TransferTag
import app.snapsync.data.upload.Exported
import app.snapsync.data.upload.StagedFiles
import app.snapsync.data.upload.UploadExport
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.TestEventKeys
import app.snapsync.feature.support.testIdentity
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.EventKeyText
import app.snapsync.format.HeadRead
import app.snapsync.format.Hmac
import app.snapsync.format.TimestampText
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val membership = MembershipRepository(SharedStore().apply { persist(joined(keyId)) }.db)
        val cipher = FileCipher(crypto, files)
        val sealing = UploadSealing(membership, keys.store, testIdentity(id), cipher)
        val opening = DownloadOpening(membership, keys.store, cipher, files)

        /**
         * A finished download of [DEVICE_A]'s resource, as receiving keeps it: its bytes at [temp] landed where the
         * opening says and then opened ([app.snapsync.application.receiving.StageDownload] and `RecordStaged`). Answers
         * the staged path, or `null` when nothing was staged.
         */
        suspend fun received(temp: String): String? {
            val tag = TransferTag.parse("$DEVICE_A\n$ASSET\n$KEY\n$EVENT")!!
            val landing = opening.landing(tag.stagedAt.value)
            assertEquals(Landed.Kept, StagingArea(files).land(temp, StagedPath(landing.stagedAt)))
            val answer = opening.open(
                landing,
                tag.ref.sourceDeviceId,
                tag.ref.sourceAssetId,
                tag.role,
                EventId.parse(EVENT),
            )
            return when (answer) {
                OpenAnswer.Opened -> tag.stagedAt.value
                OpenAnswer.NotOpened -> null
            }
        }
    }

    /** A library whose export writes [photo] where it is told — into the device's in-memory shared area. */
    private fun exporting(files: RecordingFiles): GalleryReader =
        object : GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
            override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
                files.write(FileArea.SHARED, to.removePrefix("mem:/shared/"), photo)
                return WriteOutcome.Ok
            }
        }

    /** The photo library's handle of the one photo these uploads send. */
    private object TestPhoto : GalleryResourceHandle

    /** How [device] seals the one photo's primary for the event. */
    private fun sealOf(device: Device) =
        device.sealing.sealFor(testEventId(EVENT), testAssetId(ASSET), ResourceRole.PRIMARY)

    private fun adFor(device: String) = EncryptedFileFormat.associatedData(
        FileBinding(EventId.parse(EVENT)!!, DeviceId.parse(device)!!, AssetId.parse(ASSET)!!, ResourceRole.PRIMARY),
    )

    @Test
    fun a_sealed_upload_is_what_another_member_opens_and_the_plaintext_never_waits_on_disk() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val sharer = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val staging = StagedFiles(sharer.files)
        val order = assertIs<UploadSeal.Sealed>(sealOf(sharer)).order

        assertEquals(
            Exported.DONE,
            UploadExport(exporting(sharer.files), sharer.files, sharer.sealing, staging).sealed(KEY, TestPhoto, order),
        )
        val sealed = assertIs<FileResult.Ok<ByteArray>>(sharer.files.read(FileArea.SHARED, staging.path(KEY))).value
        assertEquals(FileResult.Ok(false), sharer.files.exists(FileArea.SHARED, "${staging.path(KEY)}.plain"))

        // Another member downloads those bytes and stages them opened.
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        receiver.files.write(FileArea.SHARED, "os-tmp/1", sealed)
        val path = assertNotNull(receiver.received("mem:/shared/os-tmp/1"))
        assertContentEquals(photo, assertIs<FileResult.Ok<ByteArray>>(receiver.files.read(FileArea.SHARED, path)).value)
        assertEquals(FileResult.Ok(false), receiver.files.exists(FileArea.SHARED, "$path.sealed"))
    }

    @Test
    fun a_platform_that_sends_the_librarys_own_bytes_carries_that_one_files_key_for_the_edge() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val device = Device(DEVICE_A, minted.linkKey, minted.keyId)
        val headers = device.sealing.edgeSeal(assertIs<UploadSeal.Sealed>(sealOf(device)).order).headers()
        val head = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(decodeBase64(headers.getValue(EncryptedFileFormat.FILE_HEAD_HEADER))),
        ).head
        val eventKey = EventKeyText.decode(minted.linkKey)!!.bytes()
        assertContentEquals(EncryptedFileFormat.keyIdOf(eventKey, portHmac(crypto)), head.keyId)
        assertContentEquals(
            EncryptedFileFormat.fileKeyOf(eventKey, head.salt, adFor(DEVICE_A), portHmac(crypto)),
            decodeBase64(headers.getValue(EncryptedFileFormat.FILE_KEY_HEADER)),
            "the one file's key, bound to this resource — never the event key",
        )
    }

    @Test
    fun without_the_events_key_nothing_is_sealed() = runTest {
        // A tier makes nothing for a withheld seal (`SessionTierTest`, `OsQueueTest`): never plaintext to the event.
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val other = TestEventKeys(crypto, inMemorySecureStore()).mint()
        for (device in listOf(Device(DEVICE_A, null, minted.keyId), Device(DEVICE_A, other.linkKey, minted.keyId))) {
            assertIs<UploadSeal.Withheld>(sealOf(device))
        }
    }

    @Test
    fun a_download_that_does_not_open_is_never_staged_and_leaves_nothing_behind() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val receiver = Device(DEVICE_B, minted.linkKey, minted.keyId)
        // Plaintext, as a build predating encryption would have stored it — or anything else that is not a file of this key.
        repeat(5) { attempt ->
            receiver.files.write(FileArea.SHARED, "os-tmp/$attempt", photo)
            assertNull(receiver.received("mem:/shared/os-tmp/$attempt"))
        }
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
        repeat(5) { attempt ->
            receiver.files.write(FileArea.SHARED, "os-tmp/$attempt", photo)
            assertEquals(
                OpenAnswer.NotOpened,
                receiver.opening.open(
                    Landing.Sealed("os-tmp/$attempt", "staged/$attempt"),
                    testDeviceId(DEVICE_A),
                    testAssetId(ASSET),
                    ResourceRole.PRIMARY,
                    testEventId(EVENT),
                ),
            )
        }
        assertTrue(receiver.files.operations.none { it.startsWith("write") && it.contains("staged/") })
    }

    private fun decodeBase64(text: String): ByteArray =
        kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).decode(text)

    private companion object {
        const val EVENT = "e0000000-0000-4000-8000-000000000001"
        const val DEVICE_A = "A0000000-0000-4000-8000-00000000000A"
        const val DEVICE_B = "B0000000-0000-4000-8000-00000000000B"
        const val ASSET = "ASSET1"
        const val KEY = "ASSET1-primary.heic"
    }

    @Test
    fun a_plain_events_download_is_staged_as_it_arrived() = runTest {
        val receiver = Device(DEVICE_B, linkKey = null, keyId = null)
        receiver.files.write(FileArea.SHARED, "os-tmp/0", photo)
        val path = assertNotNull(receiver.received("mem:/shared/os-tmp/0"), "nothing to open: the bytes are the photo")
        assertContentEquals(photo, assertIs<FileResult.Ok<ByteArray>>(receiver.files.read(FileArea.SHARED, path)).value)
    }
}

/** The format's HMAC over [crypto]'s. */
private fun portHmac(crypto: Crypto): Hmac = object : Hmac {
    override fun mac(key: ByteArray, message: ByteArray): ByteArray = crypto.hmacSha256(key, message)
}
