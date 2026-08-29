package app.snapsync.download

import app.snapsync.testsupport.fileExists
import app.snapsync.testsupport.withTempDirectory
import app.snapsync.testsupport.writeTextFile

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The staging area's release side (capability `download-store`).
 *
 * `release` is the only thing that ever reclaims a downloaded photo's bytes. Every settle path, the
 * leave/switch prune, `ResetDeviceState`'s teardown and the backlog reclaim all end here, so a
 * release that stopped removing files would grow the shared App-Group container without limit — and
 * the first symptom would be an unrelated write failing: the ledger's, or the next download's.
 *
 * Its **idempotence** is the property the callers actually rely on. They pass whatever paths the
 * store recorded, and a row can legitimately name a file that is already gone (a partially-completed
 * release, a file the OS purged, a path recorded before the write finished). Treating that as an
 * error would strand every subsequent path in the same batch.
 */
class IosStagedBytesTest {

    private val staged = IosStagedBytes()

    @Test
    fun `a released file is gone`() {
        withTempDirectory { dir ->
            val path = "$dir/photo-1.heic"
            writeTextFile(path, "bytes")

            runBlocking { staged.release(listOf(path)) }

            assertFalse(fileExists(path), "nothing else ever reclaims a staged photo's bytes")
        }
    }

    @Test
    fun `releasing an absent file is not an error`() {
        withTempDirectory { dir ->
            runBlocking { staged.release(listOf("$dir/never-existed.heic")) }
        }
    }

    /**
     * The mixed batch, which is the realistic one. A release that stopped at the first missing path
     * would leak every file after it, permanently — nothing revisits a released row.
     */
    @Test
    fun `an absent path does not stop the rest of the batch`() {
        withTempDirectory { dir ->
            val first = "$dir/first.heic"
            val last = "$dir/last.heic"
            writeTextFile(first, "bytes")
            writeTextFile(last, "bytes")

            runBlocking { staged.release(listOf(first, "$dir/missing.heic", last)) }

            assertFalse(fileExists(first))
            assertFalse(fileExists(last), "the path after the missing one must still be released")
        }
    }

    @Test
    fun `releasing the same path twice is harmless`() {
        withTempDirectory { dir ->
            val path = "$dir/photo-1.heic"
            writeTextFile(path, "bytes")

            runBlocking {
                staged.release(listOf(path))
                staged.release(listOf(path))
            }

            assertFalse(fileExists(path))
        }
    }

    @Test
    fun `releasing nothing touches nothing`() {
        withTempDirectory { dir ->
            val path = "$dir/photo-1.heic"
            writeTextFile(path, "bytes")

            runBlocking { staged.release(emptyList()) }

            assertTrue(fileExists(path), "an empty batch must not be read as a request to clear staging")
        }
    }

    /**
     * The presence read (capability `download-store`), which the adjudicator uses as its second oracle:
     * the photo library takes a resource's file when it ingests it, and it ingests only as part of
     * creating an asset — so a missing staged file is positive evidence that a creation was submitted,
     * at the one moment the library's own *absent* answer cannot be acted on (capability
     * `photo-download`).
     *
     * Measured on device-shaped hosts: after a SIGKILL mid-commit the staged file is gone at relaunch,
     * and gone *before* the asset becomes visible
     * (`changes/settle-imports-on-consumed-bytes/PROBE-FINDINGS.md`).
     */
    @Test
    fun `intact staged files are all present`() {
        withTempDirectory { dir ->
            val a = "$dir/a.heic"
            val b = "$dir/b.mov"
            writeTextFile(a, "bytes")
            writeTextFile(b, "bytes")

            assertTrue(runBlocking { staged.allPresent(listOf(a, b)) })
        }
    }

    /**
     * ONE missing member is enough. An asset's resources are ingested individually and a process can die
     * between them, so a partially-consumed set is as much evidence of a submitted creation as a fully
     * consumed one. Reading this as "still present" would clear the marker of an asset that exists.
     */
    @Test
    fun `one consumed resource makes the set not all present`() {
        withTempDirectory { dir ->
            val kept = "$dir/kept.heic"
            writeTextFile(kept, "bytes")

            assertFalse(runBlocking { staged.allPresent(listOf(kept, "$dir/taken.mov")) })
        }
    }

    @Test
    fun `a fully consumed set is not all present`() {
        withTempDirectory { dir ->
            assertFalse(runBlocking { staged.allPresent(listOf("$dir/gone-1.heic", "$dir/gone-2.mov")) })
        }
    }

    /**
     * An empty set carries no evidence either way, and answers `true` rather than `false` so it can never
     * be read as "these were consumed". The caller distinguishes the empty case itself and declines to
     * act on it.
     */
    @Test
    fun `an empty set is all present`() {
        withTempDirectory {
            assertTrue(runBlocking { staged.allPresent(emptyList()) })
        }
    }

    /**
     * A missing container is an **error, not an empty answer**. Without it there is nowhere durable to
     * stage, and inventing a path would put every downloaded photo somewhere the release side above
     * cannot find — a leak with no record of itself.
     *
     * A Kotlin/Native test binary holds no `application-groups` entitlement, so this is the one
     * environment where that branch runs at all.
     */
    @Test
    fun `a missing App Group container raises rather than inventing a path`() {
        val failure = assertFailsWith<IllegalStateException> { staged.stagingRoot() }

        assertTrue(
            "group.app.snapsync" in failure.message.orEmpty(),
            "the message must name the container that was unavailable: ${failure.message}",
        )
    }
}
