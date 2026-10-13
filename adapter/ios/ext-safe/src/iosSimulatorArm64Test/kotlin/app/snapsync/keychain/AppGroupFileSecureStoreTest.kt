package app.snapsync.keychain

import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.secure.resolveOrMint
import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.withTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The **simulator target's** file-backed secure store (capability `photo-sharing`; decision record
 * `changes/add-simulator-rig-host` D6).
 *
 * This suite lives in `iosSimulatorArm64Test` rather than the shared `iosTest`, because the store it
 * tests exists only on that target — a device binary contains no route to it, which is the whole
 * containment argument. The directory is injected: an `xctest` host carries no App-Group entitlement,
 * so the production default would only ever exercise the unavailable branch.
 *
 * What matters here is the **three-state read**, not the file format. `resolveOrMint`'s ordering is
 * built on absence and unavailability being different answers — absence may mint, failure may never —
 * and a store that blurred them would reintroduce, on this target, the fault the whole port exists to
 * prevent: a process that mints a second identity because it could not read the first.
 */
class AppGroupFileSecureStoreTest {

    private val slot = SecureSlot(service = "app.snapsync.test", account = "item", shared = true)
    private val deviceId = SecureSlot(service = "app.snapsync.deviceid", account = "deviceid", shared = true)

    @Test
    fun `an unwritten store reports absence so a caller may mint`() = withTempDirectory { dir ->
        val store = AppGroupFileSecureStore(dir)
        assertEquals(SecureStoreRead.Absent, store.read(deviceId))
    }

    @Test
    fun `a written value reads back verbatim and background-readable`() = withTempDirectory { dir ->
        val store = AppGroupFileSecureStore(dir)
        assertEquals(WriteOutcome.Ok, store.write(deviceId, "11111111-0000-4000-8000-000000000002"))

        val read = store.read(deviceId)
        assertIs<SecureStoreRead.Found>(read)
        assertEquals("11111111-0000-4000-8000-000000000002", read.value)
        // The file is written CompleteUntilFirstUserAuthentication like every other App-Group file, so
        // this is the honest answer — and it is what keeps `resolveOrMint` from ever asking for a
        // migration this store cannot perform.
        assertEquals(StoredProtection.BACKGROUND_READABLE, read.protection)
    }

    @Test
    fun `a slot's file is named after its service and account`() = withTempDirectory { dir ->
        AppGroupFileSecureStore(dir).write(deviceId, "an-id")

        assertEquals("an-id", readTextFile("$dir/app.snapsync.deviceid.deviceid.simulator.json"))
    }

    @Test
    fun `each slot is its own file`() = withTempDirectory { dir ->
        val store = AppGroupFileSecureStore(dir)
        store.write(deviceId, "an-id")
        store.write(slot, "another-value")

        assertTrue(fileExists("$dir/app.snapsync.test.item.simulator.json"))
        assertEquals("an-id", (store.read(deviceId) as SecureStoreRead.Found).value)
        assertEquals("another-value", (store.read(slot) as SecureStoreRead.Found).value)
    }

    @Test
    fun `an unreachable container is unavailable rather than absent`() {
        // The shape an UNSIGNED simulator build takes: no entitlement, so no container. Reporting
        // absence here would let a caller mint an id it cannot then persist — the build-297 crash's
        // exact shape, on a different store.
        val store = AppGroupFileSecureStore(directory = null)
        val read = store.read(deviceId)
        assertIs<SecureStoreRead.Unavailable>(read)
        assertTrue(
            "sim-sign" in read.detail,
            "the detail must point at the fix, since the symptom (no container) says nothing about " +
                "signing: ${read.detail}",
        )
        assertIs<WriteOutcome.Failed>(store.write(deviceId, "an-id"), "nowhere to write is a refusal")
    }

    @Test
    fun `a present but empty file is unavailable rather than absent`() = withTempDirectory { dir ->
        val store = AppGroupFileSecureStore(dir)
        store.write(deviceId, "   ")

        // Something wrote this and produced nothing usable. Treating it as absence would mint a SECOND
        // identity for a process that may already have had one — so the caller defers instead.
        assertIs<SecureStoreRead.Unavailable>(store.read(deviceId))
    }

    @Test
    fun `delete returns the store to absence and deleting nothing is not an error`() =
        withTempDirectory { dir ->
            val store = AppGroupFileSecureStore(dir)
            assertEquals(WriteOutcome.Ok, store.delete(deviceId), "absent: a no-op, per the port")
            store.write(deviceId, "an-id")
            assertEquals(WriteOutcome.Ok, store.delete(deviceId))
            assertEquals(SecureStoreRead.Absent, store.read(deviceId))
        }

    @Test
    fun `resolveOrMint mints once over this store and returns the same id thereafter`() =
        withTempDirectory { dir ->
            val store = AppGroupFileSecureStore(dir)
            var minted = 0

            val first = resolveOrMint(store, deviceId) {
                minted++
                "minted-$minted"
            }
            val second = resolveOrMint(store, deviceId) {
                minted++
                "minted-$minted"
            }

            assertEquals("minted-1", first)
            assertEquals(first, second)
            assertEquals(1, minted, "the second resolve must READ, not mint — a second id orphans the ledger")
        }
}
