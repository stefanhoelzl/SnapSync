package app.snapsync.services.identity

import app.snapsync.fake.inMemorySecureStore
import app.snapsync.model.DeviceIdResult
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.services.secure.RecordingSecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308" // errSecInteractionNotAllowed, as the iOS adapter formats it

private val SHARED = SecureSlots.DEVICE_ID
private val LEGACY = SecureSlots.DEVICE_ID_LEGACY

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
        platform: PlatformDeviceId = PlatformDeviceId { "minted-id" },
    ) = PersistedDeviceIdentity(role = role, store = secure, platformDeviceId = platform)

    // ---- the read-only role (the upload extension) ---------------------------------------------

    @Test
    fun `the extension reads the shared slot and returns its value verbatim`() {
        store.answers[SHARED] = found("device-42")
        val identity = identity(DeviceIdentityRole.READ_ONLY)

        assertEquals(DeviceIdResult.Id("device-42", DeviceIdResult.Via.READ), identity.resolve())
        assertEquals("device-42", identity.deviceId())
        assertTrue(store.untouched(), "a healthy read writes nothing")
        assertEquals(0, store.readsOf(LEGACY), "the extension must never consult the unscoped slot")
    }

    /**
     * The branch that closed the split. An unscoped search from the *extension* finds that process's OWN stale
     * item — so adopting here would re-create the second identity rather than heal it.
     */
    @Test
    fun `the extension refuses to mint when the shared slot is absent and consults no legacy slot`() {
        store.answers[LEGACY] = found("the-extensions-own-stale-id")
        var asked = false
        val identity = identity(DeviceIdentityRole.READ_ONLY, platform = { asked = true; "minted-id" })

        assertEquals(DeviceIdResult.AbsentNotMintable, identity.resolve())
        assertFailsWith<DeviceIdentityAbsent> { identity.deviceId() }
        assertEquals(0, store.readsOf(LEGACY), "an unscoped fallback here is what produced two device ids")
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
    fun `the app adopts an out-of-group id instead of minting a second one`() {
        store.answers[LEGACY] = found("provisioned-in-july", StoredProtection.UNREPORTED)
        var minted = false
        val identity = identity(DeviceIdentityRole.MINTING, platform = { minted = true; "minted-id" })

        assertEquals(DeviceIdResult.Id("provisioned-in-july", DeviceIdResult.Via.ADOPTED), identity.resolve())
        assertTrue(!minted, "adopting is the whole repair path; minting here strands the device's partition")
        assertEquals(
            listOf("provisioned-in-july"),
            store.writesTo(SHARED),
            "the adopted value must be re-filed under the shared slot VERBATIM — a re-mint on adoption " +
                "would be the same fault wearing the repair's clothes",
        )
        assertTrue(LEGACY !in store.deletes, "the out-of-group item survives, so a rollback still finds it")
    }

    @Test
    fun `the app mints only when the id exists nowhere it can reach`() {
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(DeviceIdResult.Id("minted-id", DeviceIdResult.Via.MINTED), identity.resolve())
        assertEquals(1, store.readsOf(LEGACY), "the unscoped slot must be consulted BEFORE minting")
        assertEquals(listOf("minted-id"), store.writesTo(SHARED), "a minted id is persisted to the shared slot")
        assertTrue(store.writesTo(LEGACY).isEmpty())
    }

    /**
     * "I could not look" on the legacy read is as disqualifying as on the primary one. This is the arm that is
     * easiest to get wrong, because minting here *works* — it just quietly hands a device that already has an
     * identity a second one.
     */
    @Test
    fun `an unreadable legacy slot blocks the mint rather than being treated as absence`() {
        store.answers[LEGACY] = SecureStoreRead.Unavailable(LOCKED)
        var minted = false
        val identity = identity(DeviceIdentityRole.MINTING, platform = { minted = true; "minted-id" })

        assertEquals(DeviceIdResult.Unavailable(LOCKED), identity.resolve())
        assertFailsWith<SecureStoreUnavailable> { identity.deviceId() }
        assertTrue(!minted, "a locked device must wait for the next launch, not acquire a new identity")
        assertTrue(store.writes.isEmpty())
    }

    /**
     * A device provisioned by a pre-fix build carries the weaker protection. It must be upgraded **in place**,
     * value untouched: re-minting would orphan the partition and the ledger.
     */
    @Test
    fun `a legacy-protection item is upgraded in place with its value untouched`() {
        store.answers[SHARED] = found("provisioned-in-june", StoredProtection.RESTRICTED)
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals(DeviceIdResult.Id("provisioned-in-june", DeviceIdResult.Via.READ), identity.resolve())
        assertEquals(listOf(SHARED), store.migrations, "the item must be upgraded so background wakes can read it")
        assertTrue(store.writes.isEmpty(), "migration supplies no value; the id is never rewritten")
        assertEquals(0, store.readsOf(LEGACY), "a found item ends the resolution — no legacy read")
    }

    @Test
    fun `an item already stored background-readable is left completely alone`() {
        store.answers[SHARED] = found("device-42")
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals("device-42", identity.deviceId())
        assertTrue(store.untouched(), "an already-correct item costs no write")
    }

    // ---- what a mint is ---------------------------------------------------------------------------

    @Test
    fun `a platform that offers a stable id has that id minted`() {
        val identity = identity(DeviceIdentityRole.MINTING, platform = { "platform-stable-id" })

        assertEquals("platform-stable-id", identity.deviceId())
        assertEquals(listOf("platform-stable-id"), store.writesTo(SHARED))
    }

    @Test
    fun `a platform that offers none has a random upper-case UUID minted`() {
        val identity = identity(DeviceIdentityRole.MINTING, platform = { null })

        val id = identity.deviceId()

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
        val identity = identity(DeviceIdentityRole.MINTING, platform = { mints++; "minted-$mints" })

        assertIs<DeviceIdResult.Unavailable>(identity.resolve(), "an unsaved id must never be used")
        assertFailsWith<SecureStoreUnavailable> { identity.deviceId() }
        assertEquals(SecureStoreRead.Absent, store.read(SHARED))

        store.refuseWrites = false
        val resolved = identity.resolve()

        assertIs<DeviceIdResult.Id>(resolved, "a failure is not kept: the next resolve retries")
        assertEquals(DeviceIdResult.Via.MINTED, resolved.via)
        assertEquals(found(resolved.value), store.read(SHARED), "the id handed out is the id stored")
    }

    @Test
    fun `a refused write of an adopted id is unavailable and leaves the legacy item in place`() {
        store.answers[LEGACY] = found("provisioned-in-july")
        store.refuseWrites = true
        val identity = identity(DeviceIdentityRole.MINTING)

        assertIs<DeviceIdResult.Unavailable>(identity.resolve())
        assertEquals(found("provisioned-in-july"), store.read(LEGACY))

        store.refuseWrites = false
        assertEquals(DeviceIdResult.Id("provisioned-in-july", DeviceIdResult.Via.ADOPTED), identity.resolve())
    }

    // ---- caching: a success is kept, a failure never ----------------------------------------------

    /**
     * One resolve per instance. It matters beyond cost: the extension's process is short-lived and a second
     * resolve of an absent-then-present item would let one process report two different ids.
     */
    @Test
    fun `the identity is resolved once and then cached`() {
        var mints = 0
        val identity = identity(DeviceIdentityRole.MINTING, platform = { mints++; "minted-$mints" })

        assertEquals("minted-1", identity.deviceId())
        assertEquals("minted-1", identity.deviceId())
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
        val identity = identity(DeviceIdentityRole.MINTING, platform = { mints++; "minted-id" })

        assertIs<DeviceIdResult.Unavailable>(identity.resolve())
        assertEquals(0, mints, "an unreadable store never mints")

        store.answers[SHARED] = found("device-42")
        assertEquals(DeviceIdResult.Id("device-42", DeviceIdResult.Via.READ), identity.resolve())
        assertEquals(0, mints)
    }

    @Test
    fun `an absent extension resolution is not cached and reads the id once the app has minted it`() {
        val items = mutableMapOf<SecureSlot, SecureStoreRead.Found>()
        val shared = inMemorySecureStore(items)
        val extension = identity(DeviceIdentityRole.READ_ONLY, secure = shared)
        val app = identity(DeviceIdentityRole.MINTING, secure = shared, platform = { "the-apps-id" })

        assertEquals(DeviceIdResult.AbsentNotMintable, extension.resolve())
        assertEquals("the-apps-id", app.deviceId())

        assertEquals(
            DeviceIdResult.Id("the-apps-id", DeviceIdResult.Via.READ),
            extension.resolve(),
            "both processes resolve ONE id",
        )
    }
}
