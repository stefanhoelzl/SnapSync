package app.snapsync.services.identity

import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.DeviceIdResult
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.services.secure.RecordingSecureStore
import app.snapsync.services.secure.SecureSlots
import app.snapsync.testids.testDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308" // errSecInteractionNotAllowed, as the iOS adapter formats it

private val SHARED = SecureSlots.DEVICE_ID

private fun found(value: String, protection: StoredProtection = StoredProtection.BACKGROUND_READABLE) =
    SecureStoreRead.Found(value, protection)

/**
 * The device identity, on the two axes that have actually failed in the field (capability `photo-sharing`):
 * which slot is consulted first and whether the process that must not mint ever does.
 *
 * **Why this is worth a file of its own.** The device id is written **once**, at mint, and never rewritten — the
 * secure item survives app uninstall, so nothing in a device's remaining lifetime will correct a value or a
 * placement that was wrong when it was created. A wrong id orphans that device's `/files/devices/<deviceId>/`
 * partition and makes every photo it already uploaded read back as another member's, which `DownloadController`
 * then re-imports into its owner's own library, one duplicate per photo. That is remotely unfixable, and it
 * shipped: on 2026-07-20 an SE2 ran nine hours with the app on one id and the upload extension on another, **both
 * reads reporting success**.
 *
 * Where the slots live on iOS (service, account, access group) is pinned beside the adapter, in
 * `SecureSlotAddressTest`; this file pins the order and the roles over a recording store.
 */
class PersistedDeviceIdentityTest {

    private val store = RecordingSecureStore()

    private fun identity(
        role: DeviceIdentityRole,
        secure: SecureStore = store,
        platform: PlatformDeviceId = PlatformDeviceId { testDeviceId("minted-id").value },
    ) = PersistedDeviceIdentity(role = role, store = secure, platformDeviceId = platform)

    // ---- the read-only role (the upload extension) ---------------------------------------------

    @Test
    fun `the extension reads the shared slot and returns its value verbatim`() {
        store.answers[SHARED] = found(testDeviceId("device-42").value)
        val identity = identity(DeviceIdentityRole.READ_ONLY)

        assertEquals(DeviceIdResult.Id(testDeviceId("device-42"), DeviceIdResult.Via.READ), identity.resolve())
        assertEquals(testDeviceId("device-42"), identity.deviceId())
        assertTrue(store.untouched(), "a healthy read writes nothing")
    }

    @Test
    fun `a stored id that is no device id is unusable - never handed out and never minted over`() {
        store.answers[SHARED] = found("not-a-device-id")

        val expected = DeviceIdResult.Unavailable("the stored device id is not a device id")
        assertEquals(expected, identity(DeviceIdentityRole.READ_ONLY).resolve())
        assertEquals(expected, identity(DeviceIdentityRole.MINTING).current())
        assertTrue(store.untouched(), "an unusable identity is reported, not replaced")
    }

    /** The extension cannot tell "no identity yet" from "the app's identity is not reachable from here". */
    @Test
    fun `the extension refuses to mint when the shared slot is absent`() {
        var asked = false
        val identity = identity(DeviceIdentityRole.READ_ONLY, platform = {
            asked = true
            testDeviceId("minted-id").value
        })

        assertEquals(DeviceIdResult.AbsentNotMintable, identity.resolve())
        assertFailsWith<DeviceIdentityAbsent> { identity.deviceId() }
        assertEquals(listOf(SHARED), store.reads.distinct(), "the shared slot and nothing else")
        assertTrue(store.writes.isEmpty(), "the extension may not create an identity under any circumstances")
        assertTrue(!asked, "the extension never even asks for an id to mint")
    }

    @Test
    fun `an unreadable shared slot defers the extension rather than inventing an identity`() {
        store.answers[SHARED] = SecureStoreRead.Unavailable(LOCKED)
        val identity = identity(DeviceIdentityRole.READ_ONLY)

        assertEquals(DeviceIdResult.Unavailable(LOCKED), identity.resolve())
        val failure = assertFailsWith<SecureStoreUnavailable> { identity.deviceId() }
        assertEquals(LOCKED, failure.detail, "the adapter's diagnostic must survive to the device log")
        assertTrue(store.untouched())
    }

    // ---- the minting role (the app) -------------------------------------------------------------

    @Test
    fun `the app mints only when the shared slot is absent`() {
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(DeviceIdResult.Id(testDeviceId("minted-id"), DeviceIdResult.Via.MINTED), identity.resolve())
        assertEquals(listOf(SHARED), store.reads, "the shared slot is the only one consulted")
        assertEquals(
            listOf(testDeviceId("minted-id").value),
            store.writesTo(SHARED),
            "a minted id is persisted to the shared slot",
        )
        assertEquals(listOf(SHARED), store.writes.map { it.first }, "nothing else is written")
    }

    /** "I could not look" never mints: a locked device waits for the next launch rather than acquiring a new id. */
    @Test
    fun `an unreadable shared slot blocks the app's mint rather than being treated as absence`() {
        store.answers[SHARED] = SecureStoreRead.Unavailable(LOCKED)
        var minted = false
        val identity = identity(DeviceIdentityRole.MINTING, platform = {
            minted = true
            testDeviceId("minted-id").value
        })

        assertEquals(DeviceIdResult.Unavailable(LOCKED), identity.resolve())
        assertFailsWith<SecureStoreUnavailable> { identity.deviceId() }
        assertTrue(!minted, "a locked device must wait for the next launch, not acquire a new identity")
        assertTrue(store.untouched())
    }

    /**
     * An item filed under another protection is read as it is — reported in the log line, never rewritten: the
     * id is written once, at mint, and a re-mint would orphan the partition and the ledger.
     */
    @Test
    fun `an item under another protection is read verbatim and never rewritten`() {
        store.answers[SHARED] = found(testDeviceId("provisioned-in-june").value, StoredProtection.RESTRICTED)
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(
            DeviceIdResult.Id(testDeviceId("provisioned-in-june"), DeviceIdResult.Via.READ),
            identity.resolve(),
        )
        assertTrue(store.untouched(), "the id is never rewritten")
    }

    @Test
    fun `an item already stored background-readable is left completely alone`() {
        store.answers[SHARED] = found(testDeviceId("device-42").value)
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(testDeviceId("device-42"), identity.deviceId())
        assertTrue(store.untouched(), "an already-correct item costs no write")
    }

    // ---- what a mint is ---------------------------------------------------------------------------

    @Test
    fun `a platform that offers a stable id has that id minted`() {
        val identity = identity(DeviceIdentityRole.MINTING, platform = { testDeviceId("platform-stable-id").value })

        assertEquals(testDeviceId("platform-stable-id"), identity.deviceId())
        assertEquals(listOf(testDeviceId("platform-stable-id").value), store.writesTo(SHARED))
    }

    @Test
    fun `a platform that offers none has a random upper-case UUID minted`() {
        val identity = identity(DeviceIdentityRole.MINTING, platform = { null })

        val id = identity.deviceId().value

        assertEquals(36, id.length, "a canonical UUID string: $id")
        assertEquals(id.uppercase(), id, "the platform's canonical upper-case form, as NSUUID().UUIDString mints")
        assertTrue(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}").matches(id), id)
        assertEquals(listOf(id), store.writesTo(SHARED))
    }

    // ---- a refused persisting write ---------------------------------------------------------------

    /** An id used and not stored would be a different id on the next launch — so it is never handed out. */
    @Test
    fun `a refused persisting write is unavailable and the next resolve succeeds once writes are accepted`() {
        store.refuseWrites = true
        var mints = 0
        val identity = identity(DeviceIdentityRole.MINTING, platform = {
            mints++
            testDeviceId("minted-$mints").value
        })

        assertIs<DeviceIdResult.Unavailable>(identity.resolve(), "an unsaved id must never be used")
        assertFailsWith<SecureStoreUnavailable> { identity.deviceId() }
        assertEquals(SecureStoreRead.Absent, store.read(SHARED))

        store.refuseWrites = false
        val resolved = identity.resolve()

        assertIs<DeviceIdResult.Id>(resolved, "a failure is not kept: the next resolve retries")
        assertEquals(DeviceIdResult.Via.MINTED, resolved.via)
        assertEquals(found(resolved.value.value), store.read(SHARED), "the id handed out is the id stored")
    }

    // ---- caching: a success is kept, a failure never ----------------------------------------------

    /**
     * One resolve per instance. It matters beyond cost: the extension's process is short-lived and a second
     * resolve of an absent-then-present item would let one process report two different ids.
     */
    @Test
    fun `the identity is resolved once and then cached`() {
        var mints = 0
        val identity = identity(DeviceIdentityRole.MINTING, platform = {
            mints++
            testDeviceId("minted-$mints").value
        })

        assertEquals(testDeviceId("minted-1"), identity.deviceId())
        assertEquals(testDeviceId("minted-1"), identity.deviceId())
        assertEquals(1, mints, "a second mint inside one process would be two identities")
        assertEquals(1, store.readsOf(SHARED), "the resolution is not re-run per call")
    }

    /** An unavailable store never mints and is not cached: a resolve after the device unlocks succeeds. */
    @Test
    fun `an unavailable resolution is not cached and a later readable resolve succeeds`() {
        val locked = identity(DeviceIdentityRole.MINTING, secure = inMemorySecureStore(unavailable = true))
        assertIs<DeviceIdResult.Unavailable>(locked.resolve())

        store.answers[SHARED] = SecureStoreRead.Unavailable(LOCKED)
        var mints = 0
        val identity = identity(DeviceIdentityRole.MINTING, platform = {
            mints++
            testDeviceId("minted-id").value
        })

        assertIs<DeviceIdResult.Unavailable>(identity.resolve())
        assertEquals(0, mints, "an unreadable store never mints")

        store.answers[SHARED] = found(testDeviceId("device-42").value)
        assertEquals(DeviceIdResult.Id(testDeviceId("device-42"), DeviceIdResult.Via.READ), identity.resolve())
        assertEquals(0, mints)
    }

    @Test
    fun `an absent extension resolution is not cached and reads the id once the app has minted it`() {
        val items = mutableMapOf<SecureSlot, SecureStoreRead.Found>()
        val shared = inMemorySecureStore(items)
        val extension = identity(DeviceIdentityRole.READ_ONLY, secure = shared)
        val app = identity(
            DeviceIdentityRole.MINTING,
            secure = shared,
            platform = { testDeviceId("the-apps-id").value },
        )

        assertEquals(DeviceIdResult.AbsentNotMintable, extension.resolve())
        assertEquals(testDeviceId("the-apps-id"), app.deviceId())

        assertEquals(
            DeviceIdResult.Id(testDeviceId("the-apps-id"), DeviceIdResult.Via.READ),
            extension.resolve(),
            "both processes resolve ONE id",
        )
    }

    // ---- current(): the report's read, which never creates an identity -------------------------

    @Test
    fun `current never mints or writes when nothing is stored — even in the minting role`() {
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(DeviceIdResult.AbsentNotMintable, identity.current())
        assertTrue(store.untouched(), "a report's read must not create the identity it reports")
    }

    @Test
    fun `current answers the stored id without writing`() {
        store.answers[SHARED] = found(testDeviceId("device-42").value, StoredProtection.RESTRICTED)

        assertEquals(
            DeviceIdResult.Id(testDeviceId("device-42"), DeviceIdResult.Via.READ),
            identity(DeviceIdentityRole.MINTING).current(),
        )
        assertTrue(store.untouched(), "a report's read writes nothing")
    }

    @Test
    fun `current answers this process's own resolution once it has one`() {
        val identity = identity(DeviceIdentityRole.MINTING)
        val minted = identity.resolve()

        assertEquals(minted, identity.current())
    }

    @Test
    fun `current reports a store that cannot be read`() {
        store.answers[SHARED] = SecureStoreRead.Unavailable(LOCKED)

        assertEquals(DeviceIdResult.Unavailable(LOCKED), identity(DeviceIdentityRole.MINTING).current())
    }
}
