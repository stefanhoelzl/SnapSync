package app.snapsync.gallery

import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.withTempDirectory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device manifest's App-Group persistence (capability `device-manifest`).
 *
 * This file used to describe itself as untestable, and that was true only while it resolved the
 * container itself: a Kotlin/Native test binary has no `application-groups` entitlement, so the
 * lookup answers `nil` and every operation degrades to a silent no-op — the exact state a test could
 * not tell apart from a working store. With the container handed in, the real Foundation read/write
 * runs against a real directory.
 *
 * What it buys: this store is the *skip-if-unchanged* memory behind the manifest upload. A store that
 * silently forgot would re-upload the manifest on every cycle (noise, no corruption); one that
 * silently *remembered* a value it never wrote would skip an upload that was needed, and the event
 * union would keep listing photos this device no longer has — which is what the manifest exists to
 * correct.
 */
class IosDeviceManifestStoreTest {

    @Test
    fun `a saved manifest is read back verbatim`() {
        withTempDirectory { dir ->
            val store = IosDeviceManifestStore(containerPath = dir)

            store.saveLastUploaded("""{"assets":[{"id":"a"}]}""")

            assertEquals("""{"assets":[{"id":"a"}]}""", store.loadLastUploaded())
        }
    }

    @Test
    fun `a store that has uploaded nothing believes nothing`() {
        withTempDirectory { dir ->
            assertNull(IosDeviceManifestStore(containerPath = dir).loadLastUploaded())
        }
    }

    @Test
    fun `a second save replaces the previous belief`() {
        withTempDirectory { dir ->
            val store = IosDeviceManifestStore(containerPath = dir)

            store.saveLastUploaded("first")
            store.saveLastUploaded("second")

            assertEquals("second", store.loadLastUploaded())
        }
    }

    /** Absent and "not believed" are the same state, which is what makes the next upload happen. */
    @Test
    fun `clearing returns the store to believing nothing`() {
        withTempDirectory { dir ->
            val store = IosDeviceManifestStore(containerPath = dir)
            store.saveLastUploaded("something")

            store.clearLastUploaded()

            assertNull(store.loadLastUploaded())
        }
    }

    @Test
    fun `clearing a store that never saved is not an error`() {
        withTempDirectory { dir ->
            IosDeviceManifestStore(containerPath = dir).clearLastUploaded()
        }
    }

    /**
     * The on-disk layout is state the installed base holds: both processes address one container, and
     * a moved directory or filename reads as "this device has uploaded no manifest" on every device at
     * once. Both names are pinned exactly-once in production Kotlin by `RuntimeIdentityTest`; this
     * asserts the composition of them that actually reaches the filesystem.
     */
    @Test
    fun `the manifest lands under device-manifest in the container`() {
        withTempDirectory { dir ->
            IosDeviceManifestStore(containerPath = dir).saveLastUploaded("payload")

            assertTrue(
                fileExists("$dir/device-manifest/last-uploaded.json"),
                "the manifest is not where the other process will look for it",
            )
            assertEquals("payload", readTextFile("$dir/device-manifest/last-uploaded.json"))
        }
    }

    @Test
    fun `the containing directory is created rather than assumed`() {
        withTempDirectory { dir ->
            // A fresh install's container holds no `device-manifest/` at all.
            IosDeviceManifestStore(containerPath = dir).saveLastUploaded("payload")

            assertEquals("payload", IosDeviceManifestStore(containerPath = dir).loadLastUploaded())
        }
    }

    /**
     * The degraded path a device would take if the App Group were ever unavailable. It stays a no-op
     * rather than raising, deliberately: the manifest is a cache, so losing it costs one redundant
     * upload, whereas raising here would abort an upload cycle over a cache miss.
     */
    @Test
    fun `a store with no container degrades to a no-op rather than raising`() {
        val store = IosDeviceManifestStore(containerPath = null)

        store.saveLastUploaded("payload")
        store.clearLastUploaded()

        assertNull(store.loadLastUploaded(), "with nowhere to write there is nothing to believe")
    }
}
