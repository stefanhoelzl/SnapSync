package app.snapsync.services.manifest

import app.snapsync.fake.inMemoryFiles
import app.snapsync.model.FileArea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device manifest's skip record (capability `photo-sharing`), over the honest in-memory [app.snapsync.ports.Files].
 *
 * This record is the *skip-if-unchanged* memory behind the manifest upload. A store that silently forgot would
 * re-upload the manifest on every cycle (noise, no corruption); one that silently *remembered* a value it never
 * wrote would skip an upload that was needed, and the event union would keep listing photos this device no longer
 * has. Where the bytes land on a device is the `Files` contract's business; here only the relative path is pinned.
 */
class DeviceManifestServiceTest {

    private val shared = mutableMapOf<String, ByteArray>()
    private val service = DeviceManifestService(inMemoryFiles(shared = shared))

    @Test
    fun `a saved manifest is read back verbatim`() {
        service.saveLastUploaded("""{"assets":[{"id":"a"}]}""")

        assertEquals("""{"assets":[{"id":"a"}]}""", service.loadLastUploaded())
    }

    @Test
    fun `a store that has uploaded nothing believes nothing`() {
        assertNull(service.loadLastUploaded())
    }

    @Test
    fun `a second save replaces the previous belief`() {
        service.saveLastUploaded("first")
        service.saveLastUploaded("second")

        assertEquals("second", service.loadLastUploaded())
    }

    /** Absent and "not believed" are the same state, which is what makes the next upload happen. */
    @Test
    fun `clearing returns the store to believing nothing`() {
        service.saveLastUploaded("something")

        service.clearLastUploaded()

        assertNull(service.loadLastUploaded())
        assertEquals(emptySet(), shared.keys)
    }

    @Test
    fun `clearing a store that never saved is not an error`() {
        service.clearLastUploaded()
    }

    /**
     * Both processes address one shared area, and a moved directory or file name reads as "this device has
     * uploaded no manifest" on every device at once.
     */
    @Test
    fun `the manifest lands at device-manifest last-uploaded json in the shared area`() {
        service.saveLastUploaded("payload")

        assertEquals(setOf("device-manifest/last-uploaded.json"), shared.keys)
        assertEquals("payload", shared.getValue("device-manifest/last-uploaded.json").decodeToString())
    }

    @Test
    fun `a record written by the other process is believed`() {
        shared["device-manifest/last-uploaded.json"] = "from the extension".encodeToByteArray()

        assertEquals("from the extension", service.loadLastUploaded())
    }

    /**
     * The degraded path a device would take if the shared area were ever unavailable. It stays a no-op rather
     * than raising, deliberately: the manifest is a cache, so losing it costs one redundant upload, whereas
     * raising here would abort an upload cycle over a cache miss.
     */
    @Test
    fun `a store with no shared area degrades to a no-op rather than raising`() {
        val degraded = DeviceManifestService(inMemoryFiles(shared = null))

        degraded.saveLastUploaded("payload")
        degraded.clearLastUploaded()

        assertNull(degraded.loadLastUploaded(), "with nowhere to write there is nothing to believe")
    }

    /** An unreadable record is not believed, so the manifest republishes — the safe direction. */
    @Test
    fun `an unreadable record is not believed`() {
        shared["device-manifest/last-uploaded.json"] = "payload".encodeToByteArray()
        val locked = DeviceManifestService(
            inMemoryFiles(shared = shared, denied = setOf(FileArea.SHARED to "device-manifest/last-uploaded.json")),
        )

        assertNull(locked.loadLastUploaded())
    }
}
