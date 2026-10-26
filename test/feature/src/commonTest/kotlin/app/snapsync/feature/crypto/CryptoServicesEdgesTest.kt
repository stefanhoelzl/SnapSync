package app.snapsync.feature.crypto

import app.snapsync.data.crypto.EncryptionSlots
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.TestEventKeys
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testIdentity
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.TimestampText
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetRef
import app.snapsync.model.EventConfig
import app.snapsync.model.Resource
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.services.crypto.DownloadOpening
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.crypto.Opened
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.crypto.UploadSealing
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The encrypted transfers' edges (the encrypted file format, `docs/architecture.md`): every way the key or the files
 * can be out of reach answers "not now" — never plaintext up, never an unopened file staged — and says why.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CryptoServicesEdgesTest {

    private val crypto = fakeCrypto()
    private val resource = Resource("ASSET1-primary.heic", testAssetId("ASSET1"), "public.heic", emptyMap(), null)
    private val ref = AssetRef(testDeviceId(DEVICE), testAssetId("ASSET1"))

    private fun config(keyId: String?) = EventConfig(
        eventId = testEventId(EVENT),
        name = "Secret",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
        keyId = keyId,
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )

    @Test
    fun a_locked_store_withholds_an_upload_and_a_plain_or_absent_membership_seals_nothing() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val files = RecordingFiles()
        val locked = TestEventKeys(crypto, inMemorySecureStore(unavailable = true))
        val sealing = UploadSealing(
            locked.store,
            FileCipher(crypto, files),
            configService(config(minted.keyId)),
            testIdentity(DEVICE),
        )
        assertIs<UploadSeal.Withheld>(sealing.sealFor(resource))

        val notJoined =
            UploadSealing(locked.store, FileCipher(crypto, files), configService(null), testIdentity(DEVICE))
        assertEquals(UploadSeal.Plain, notJoined.sealFor(resource))

        val damaged = TestEventKeys(
            crypto,
            inMemorySecureStore(
                mutableMapOf(
                    EncryptionSlots.EVENT_KEY to SecureStoreRead.Found("no key", StoredProtection.BACKGROUND_READABLE),
                ),
            ),
        )
        val invalid = UploadSealing(
            damaged.store,
            FileCipher(crypto, files),
            configService(config(minted.keyId)),
            testIdentity(DEVICE),
        )
        assertIs<UploadSeal.Withheld>(invalid.sealFor(resource), "a slot holding no key seals nothing")

        val kept = TestEventKeys(crypto, inMemorySecureStore()).also { it.keep(minted.linkKey) }
        val sealed = UploadSealing(
            kept.store,
            FileCipher(crypto, files),
            configService(config(minted.keyId)),
            testIdentity(DEVICE),
        )
            .sealFor(resource)
        assertEquals("Sealed", sealed.toString(), "a seal never prints its key")
    }

    @Test
    fun a_download_is_opened_only_with_the_events_key_at_hand() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        for ((keys, joined) in listOf(
            TestEventKeys(crypto, inMemorySecureStore(unavailable = true)) to config(minted.keyId),
            TestEventKeys(crypto, inMemorySecureStore()) to config(minted.keyId),
            TestEventKeys(crypto, inMemorySecureStore()) to null,
        )) {
            val files = RecordingFiles()
            files.write(FileArea.SHARED, "in", ByteArray(100))
            val opening = DownloadOpening(keys.store, FileCipher(crypto, files), configService(joined), files)
            assertEquals(joined != null, opening.sealed())
            assertFalse(opening.open(ref, "ASSET1-primary.heic", EVENT, "in", "out"))
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, "in"), "the unopened file is discarded")
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, "out"))
        }
    }

    @Test
    fun a_file_that_never_opens_is_counted_and_reported_once_however_often_it_is_retried() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val keys = TestEventKeys(crypto, inMemorySecureStore()).also { it.keep(minted.linkKey) }
        val files = RecordingFiles()
        val opening = DownloadOpening(
            keys.store,
            FileCipher(crypto, files),
            configService(config(minted.keyId)),
            files,
        )
        repeat(6) {
            files.write(FileArea.SHARED, "in", ByteArray(100))
            assertFalse(opening.open(ref, "ASSET1-primary.heic", EVENT, "in", "out"))
        }
        // A file that does open resets the count.
        val plain = RecordingFiles().also { it.write(FileArea.SHARED, "p", ByteArray(10)) }
        FileCipher(crypto, plain).encrypt(
            keys.current()!!,
            EncryptedFileFormat.associatedData(
                FileBinding(
                    EventId.parse(EVENT)!!,
                    DeviceId.parse(DEVICE)!!,
                    AssetId.parse("ASSET1")!!,
                    ResourceRole.PRIMARY,
                ),
            ),
            FileArea.SHARED,
            "p",
            "s",
        )
        files.write(FileArea.SHARED, "in", (plain.read(FileArea.SHARED, "s") as FileResult.Ok).value)
        files.write(FileArea.SHARED, "again", (plain.read(FileArea.SHARED, "s") as FileResult.Ok).value)
        assertFalse(
            opening.open(ref, "ASSET1-primary.heic", "B0000000-0000-4000-8000-00000000000B", "again", "out"),
            "a transfer fetched for another event is not opened with this one's key",
        )
        assertTrue(opening.open(ref, "ASSET1-primary.heic", EVENT, "in", "out"))
    }

    @Test
    fun a_file_that_cannot_be_read_or_written_is_unreadable_never_damaged() = runTest {
        val key = ByteArray(32) { 1 }
        val ad = EncryptedFileFormat.associatedData(
            FileBinding(
                EventId.parse(EVENT)!!,
                DeviceId.parse(DEVICE)!!,
                AssetId.parse("ASSET1")!!,
                ResourceRole.PRIMARY,
            ),
        )
        val files = RecordingFiles()
        files.write(FileArea.SHARED, "p", ByteArray(200_000))
        val cipher = FileCipher(crypto, files)
        assertEquals(FileResult.Ok(Unit), cipher.encrypt(key, ad, FileArea.SHARED, "p", "s"))
        files.denied += FileArea.SHARED to "o.part"
        assertIs<Opened.Unreadable>(cipher.decrypt(key, ad, FileArea.SHARED, "s", "o"))
        files.denied += FileArea.SHARED to "s"
        assertIs<Opened.Unreadable>(cipher.decrypt(key, ad, FileArea.SHARED, "s", "o"))
        files.denied += FileArea.SHARED to "t.part"
        assertIs<FileResult.Denied>(cipher.encrypt(key, ad, FileArea.SHARED, "p", "t"))
        assertEquals(
            FileResult.NotFound,
            FileCipher(crypto, RecordingFiles()).encrypt(key, ad, FileArea.SHARED, "missing", "t"),
        )
        assertEquals(
            FileResult.AreaUnavailable,
            FileCipher(crypto, inMemoryFiles(shared = null)).encrypt(key, ad, FileArea.SHARED, "p", "t"),
        )
    }

    /** A transfer started before transfers named their event fetched for the event joined then — the one joined now. */
    @Test
    fun a_download_from_before_transfers_named_their_event_opens_with_the_joined_events_key() = runTest {
        val minted = TestEventKeys(crypto, inMemorySecureStore()).mint()
        val keys = TestEventKeys(crypto, inMemorySecureStore()).also { it.keep(minted.linkKey) }
        val files = RecordingFiles()
        files.write(FileArea.SHARED, "p", ByteArray(10))
        FileCipher(crypto, files).encrypt(
            keys.current()!!,
            EncryptedFileFormat.associatedData(
                FileBinding(
                    EventId.parse(EVENT)!!,
                    DeviceId.parse(DEVICE)!!,
                    AssetId.parse("ASSET1")!!,
                    ResourceRole.PRIMARY,
                ),
            ),
            FileArea.SHARED,
            "p",
            "in",
        )
        val opening = DownloadOpening(
            keys.store,
            FileCipher(crypto, files),
            configService(config(minted.keyId)),
            files,
        )
        assertTrue(opening.open(ref, "ASSET1-primary.heic", eventId = "", from = "in", to = "out"))
    }

    private companion object {
        const val EVENT = "E0000000-0000-4000-8000-000000000001"
        const val DEVICE = "A0000000-0000-4000-8000-00000000000A"
    }
}
