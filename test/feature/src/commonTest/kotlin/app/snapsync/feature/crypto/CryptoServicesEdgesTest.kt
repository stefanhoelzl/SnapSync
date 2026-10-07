package app.snapsync.feature.crypto

import app.snapsync.model.deletesAt

import app.snapsync.model.eventEnd

import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.testIdentity
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EventConfig
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.Resource
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.services.crypto.DownloadOpening
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.crypto.FileCipher
import app.snapsync.services.crypto.Opened
import app.snapsync.services.crypto.UploadSeal
import app.snapsync.services.crypto.UploadSealing
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encrypted transfers' edges (the encrypted file format, `docs/architecture.md`): every way the key or the files
 * can be out of reach answers "not now" — never plaintext up, never an unopened file staged — and says why.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CryptoServicesEdgesTest {

    private val crypto = fakeCrypto()
    private val resource = Resource("ASSET1-primary.heic", AssetId("ASSET1"), "public.heic", emptyMap(), Unit)
    private val ref = AssetRef(DEVICE, AssetId("ASSET1"))

    private fun config(keyId: String?) = EventConfig(
        eventId = EVENT, name = "Secret",
        minPhotoDate = captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"),
        keyId = keyId,
        endsAt = eventEnd("2099-12-31T00:00:00Z"),
        deletesAt = deletesAt("2099-12-31T00:00:00Z"),
    )

    @Test
    fun a_locked_store_withholds_an_upload_and_a_plain_or_absent_membership_seals_nothing() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val files = RecordingFiles()
        val locked = EventKeys(crypto, inMemorySecureStore(unavailable = true))
        val sealing = UploadSealing(locked, FileCipher(crypto, files), configService(config(minted.keyId), files), testIdentity(DEVICE))
        assertIs<UploadSeal.Withheld>(sealing.sealFor(resource))

        val notJoined = UploadSealing(locked, FileCipher(crypto, files), configService(null), testIdentity(DEVICE))
        assertEquals(UploadSeal.Plain, notJoined.sealFor(resource))

        val kept = EventKeys(crypto, inMemorySecureStore()).also { it.keep(minted.linkKey) }
        val sealed = UploadSealing(kept, FileCipher(crypto, files), configService(config(minted.keyId), files), testIdentity(DEVICE))
            .sealFor(resource)
        assertEquals("Sealed", sealed.toString(), "a seal never prints its key")
    }

    @Test
    fun a_download_is_opened_only_with_the_events_key_at_hand() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        for ((keys, joined) in listOf(
            EventKeys(crypto, inMemorySecureStore(unavailable = true)) to config(minted.keyId),
            EventKeys(crypto, inMemorySecureStore()) to config(minted.keyId),
            EventKeys(crypto, inMemorySecureStore()) to null,
        )) {
            val files = RecordingFiles()
            files.write(FileArea.SHARED, "in", ByteArray(100))
            val opening = DownloadOpening(keys, FileCipher(crypto, files), configService(joined, files), files)
            assertEquals(joined != null, opening.sealed())
            assertFalse(opening.open(ref, "ASSET1-primary.heic", EVENT, "in", "out"))
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, "in"), "the unopened file is discarded")
            assertEquals(FileResult.Ok(false), files.exists(FileArea.SHARED, "out"))
        }
    }

    @Test
    fun a_file_that_never_opens_is_counted_and_reported_once_however_often_it_is_retried() = runTest {
        val minted = EventKeys(crypto, inMemorySecureStore()).mint()
        val keys = EventKeys(crypto, inMemorySecureStore()).also { it.keep(minted.linkKey) }
        val files = RecordingFiles()
        val opening = DownloadOpening(keys, FileCipher(crypto, files), configService(config(minted.keyId), files), files)
        repeat(6) {
            files.write(FileArea.SHARED, "in", ByteArray(100))
            assertFalse(opening.open(ref, "ASSET1-primary.heic", EVENT, "in", "out"))
        }
        // A file that does open resets the count.
        val plain = RecordingFiles().also { it.write(FileArea.SHARED, "p", ByteArray(10)) }
        FileCipher(crypto, plain).encrypt(
            keys.current()!!, EncryptedFileFormat.associatedData(EVENT, DEVICE, "ASSET1", "primary"), FileArea.SHARED, "p", "s",
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
        val ad = EncryptedFileFormat.associatedData(EVENT, DEVICE, "ASSET1", "primary")
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
        assertEquals(FileResult.NotFound, FileCipher(crypto, RecordingFiles()).encrypt(key, ad, FileArea.SHARED, "missing", "t"))
    }

    @Test
    fun the_kept_key_reads_back_as_the_link_carries_it_and_a_locked_store_says_so() = runTest {
        val keys = EventKeys(crypto, inMemorySecureStore())
        val minted = keys.mint()
        assertNull(keys.linkKey())
        keys.keep(minted.linkKey)
        assertEquals(minted.linkKey, keys.linkKey())
        assertEquals(minted.keyId, keys.idOf(minted.linkKey))
        assertNull(keys.idOf("not a key"))
        assertFailsWith<SecureStoreUnavailable> { EventKeys(crypto, inMemorySecureStore(unavailable = true)).linkKey() }
    }

    @Test
    fun an_invite_carries_the_kept_key_only_while_the_membership_is_encrypted() = runTest {
        val keys = EventKeys(crypto, inMemorySecureStore())
        val minted = keys.mint()
        keys.keep(minted.linkKey)
        val membership = kotlinx.coroutines.flow.MutableStateFlow<EventConfig?>(null)
        val invite = keys.inviteKeyOf(membership, backgroundScope)
        runCurrent()
        assertNull(invite.value, "no membership, no key")
        membership.value = config(null)
        runCurrent()
        assertNull(invite.value, "a plain event's invite carries none")
        membership.value = config(minted.keyId)
        runCurrent()
        assertEquals(minted.linkKey, invite.value)
        val locked = EventKeys(crypto, inMemorySecureStore(unavailable = true)).inviteKeyOf(membership, backgroundScope)
        runCurrent()
        assertNull(locked.value, "a key that cannot be read now is no key in an invite")
    }

    private companion object {
        const val EVENT = "E0000000-0000-4000-8000-000000000001"
        const val DEVICE = "A0000000-0000-4000-8000-00000000000A"
    }
}
