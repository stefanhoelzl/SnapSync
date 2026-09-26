package app.snapsync.fake

import app.snapsync.ports.StagedBytes

/**
 * The honest in-memory [StagedBytes]: a set of paths standing in for files on disk, under a root the
 * constructor names.
 *
 * State arrives by constructor per the fake-honesty rule — [files] is the "disk", and a test asserts
 * against it directly. That is what lets a test state the property that matters (bytes **survive** a
 * failed, abandoned or unconfirmed import, and vanish only once the row is settled) rather than merely
 * that a release call happened.
 *
 * [root] is initial state for the same reason: it is what the real adapter resolves from the App-Group
 * container, and the staged paths a test reads out of [files] are built from it, so the two sides of
 * this port answer consistently here exactly as they must on device.
 */
internal class InMemoryStagedBytes(
    private val files: MutableSet<String> = mutableSetOf(),
    private val root: String = "staged:/",
) : StagedBytes {

    override fun stagingRoot(): String = root

    /**
     * [path] itself: the in-memory disk has no platform root, so a staged path names its own file. The core's
     * relative/located split is measured against the real staging service (`StagedBytesContract` over `Files`),
     * and against a stub that tells the two apart in the download jobs' own test.
     */
    override fun locate(path: String): String = path

    /** The in-memory disk holds no platform temp files: staging a finished body puts [path] on it, and takes. */
    override fun stage(tempPath: String, path: String): Boolean = files.add(path).let { true }

    override suspend fun release(paths: List<String>) {
        files.removeAll(paths.toSet())
    }

    override suspend fun allPresent(paths: List<String>): Boolean = files.containsAll(paths)
}
