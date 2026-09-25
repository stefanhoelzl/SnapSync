package app.snapsync.services.staging

import app.snapsync.fake.inMemoryFiles
import app.snapsync.model.FileArea

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download staging area (capability `receiving-photos`), over the honest in-memory [app.snapsync.ports.Files].
 *
 * `release` is the only thing that ever reclaims a downloaded photo's bytes, so a release that stopped removing
 * files would grow the shared area without limit. Its **idempotence** is what callers rely on: a recorded row can
 * legitimately name a file that is already gone, and treating that as an error would strand every later path in
 * the batch. `allPresent` is the adjudicator's second oracle — a missing staged file is positive evidence that an
 * import was submitted — so it must never answer "missing" when it merely could not look.
 */
class StagingServiceTest {

    private val shared = mutableMapOf<String, ByteArray>()
    private val staging = StagingService(inMemoryFiles(shared = shared))

    private fun stage(vararg paths: String) = paths.forEach { shared[it] = "bytes".encodeToByteArray() }

    @Test
    fun `staged paths are relative under download-staging`() {
        assertEquals("download-staging", staging.stagingRoot())
    }

    @Test
    fun `locate hands out the platform path of a relative staged path`() {
        assertEquals("mem:/shared/download-staging/a.heic", staging.locate("download-staging/a.heic"))
    }

    /**
     * A missing shared area is an **error, not an empty answer**: inventing a path would put every downloaded
     * photo somewhere the release side cannot find — a leak with no record of itself.
     */
    @Test
    fun `an unavailable shared area raises rather than inventing a path`() {
        assertFailsWith<IllegalStateException> { StagingService(inMemoryFiles(shared = null)).locate("download-staging/a.heic") }
    }

    // ---- release ------------------------------------------------------------------------------

    @Test
    fun `a released file is gone`() = runTest {
        stage("download-staging/photo-1.heic")

        staging.release(listOf("download-staging/photo-1.heic"))

        assertFalse("download-staging/photo-1.heic" in shared, "nothing else ever reclaims a staged photo's bytes")
    }

    @Test
    fun `releasing an absent file is not an error`() = runTest {
        staging.release(listOf("download-staging/never-existed.heic"))
    }

    /** The realistic mixed batch: stopping at the first missing path would leak every file after it, permanently. */
    @Test
    fun `an absent path does not stop the rest of the batch`() = runTest {
        stage("download-staging/first.heic", "download-staging/last.heic")

        staging.release(listOf("download-staging/first.heic", "download-staging/missing.heic", "download-staging/last.heic"))

        assertEquals(emptySet(), shared.keys, "the path after the missing one must still be released")
    }

    @Test
    fun `a file that cannot be released does not stop the rest of the batch`() = runTest {
        stage("download-staging/locked.heic", "download-staging/last.heic")
        val locked = StagingService(inMemoryFiles(shared = shared, denied = setOf(FileArea.SHARED to "download-staging/locked.heic")))

        locked.release(listOf("download-staging/locked.heic", "download-staging/last.heic"))

        assertEquals(setOf("download-staging/locked.heic"), shared.keys)
    }

    @Test
    fun `releasing the same path twice is harmless`() = runTest {
        stage("download-staging/photo-1.heic")

        staging.release(listOf("download-staging/photo-1.heic"))
        staging.release(listOf("download-staging/photo-1.heic"))

        assertFalse("download-staging/photo-1.heic" in shared)
    }

    @Test
    fun `releasing nothing touches nothing`() = runTest {
        stage("download-staging/photo-1.heic")

        staging.release(emptyList())

        assertTrue("download-staging/photo-1.heic" in shared, "an empty batch must not be read as a request to clear staging")
    }

    // ---- allPresent ---------------------------------------------------------------------------

    @Test
    fun `intact staged files are all present`() = runTest {
        stage("download-staging/a.heic", "download-staging/b.mov")

        assertTrue(staging.allPresent(listOf("download-staging/a.heic", "download-staging/b.mov")))
    }

    /**
     * ONE missing member is enough: an asset's resources are ingested individually and a process can die between
     * them, so a partially consumed set is as much evidence of a submitted creation as a fully consumed one.
     */
    @Test
    fun `one consumed resource makes the set not all present`() = runTest {
        stage("download-staging/kept.heic")

        assertFalse(staging.allPresent(listOf("download-staging/kept.heic", "download-staging/taken.mov")))
    }

    @Test
    fun `a fully consumed set is not all present`() = runTest {
        assertFalse(staging.allPresent(listOf("download-staging/gone-1.heic", "download-staging/gone-2.mov")))
    }

    /** An empty set carries no evidence either way, and answers `true` so it can never read as "consumed". */
    @Test
    fun `an empty set is all present`() = runTest {
        assertTrue(staging.allPresent(emptyList()))
    }

    /** "Could not look" is not "missing": reading it as missing would settle an import that may never have run. */
    @Test
    fun `an unreadable lookup is not evidence of an import`() = runTest {
        stage("download-staging/a.heic")
        stage("download-staging/b.mov")
        // A protected file is still there: existence is not content.
        val denied = StagingService(inMemoryFiles(shared = shared, denied = setOf(FileArea.SHARED to "download-staging/b.mov")))
        val unavailable = StagingService(inMemoryFiles(shared = null))

        assertTrue(denied.allPresent(listOf("download-staging/a.heic", "download-staging/b.mov")))
        assertTrue(unavailable.allPresent(listOf("download-staging/a.heic")))
    }
}
