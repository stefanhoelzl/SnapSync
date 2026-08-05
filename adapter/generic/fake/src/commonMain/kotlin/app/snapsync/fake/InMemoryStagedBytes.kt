package app.snapsync.fake

import app.snapsync.ports.StagedBytes

/**
 * The honest in-memory [StagedBytes]: a set of paths standing in for files on disk.
 *
 * State arrives by constructor per the fake-honesty rule — [files] is the "disk", and a test asserts
 * against it directly. That is what lets a test state the property that matters (bytes **survive** a
 * failed, abandoned or unconfirmed import, and vanish only once the row is settled) rather than merely
 * that a release call happened.
 */
class InMemoryStagedBytes(
    val files: MutableSet<String> = mutableSetOf(),
) : StagedBytes {

    override suspend fun release(paths: List<String>) {
        files.removeAll(paths.toSet())
    }
}
