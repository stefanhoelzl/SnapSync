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
