package app.snapsync.fake

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files

/**
 * The honest in-memory [Files]: one map of path → bytes per area, the maps being the caller's own cells (initial
 * state by constructor, per the fake-honesty rule). An area mapped to `null` is unreachable — every operation
 * answers [FileResult.AreaUnavailable] — and a path in [denied] is a present file this process may not touch,
 * answering [FileResult.Denied] and never absence.
 */
internal class InMemoryFiles(
    private val areas: Map<FileArea, MutableMap<String, ByteArray>?>,
    private val denied: Set<Pair<FileArea, String>>,
) : Files {

    private fun <T> at(area: FileArea, path: String, op: (MutableMap<String, ByteArray>) -> FileResult<T>): FileResult<T> {
        val files = areas[area] ?: return FileResult.AreaUnavailable
        if ((area to path) in denied) return FileResult.Denied("$area/$path is denied")
        return op(files)
    }

    override fun read(area: FileArea, path: String): FileResult<ByteArray> =
        at(area, path) { files -> files[path]?.let { FileResult.Ok(it.copyOf()) } ?: FileResult.NotFound }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = at(area, path) { files ->
        val bytes = files[path] ?: return@at FileResult.NotFound
        val take = minOf(bytes.size, maxOf(maxBytes, 0))
        FileResult.Ok(FileTail(bytes.copyOfRange(bytes.size - take, bytes.size), cut = take < bytes.size))
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> =
        at(area, path) { files -> files[path] = bytes.copyOf(); FileResult.Ok(Unit) }

    override fun delete(area: FileArea, path: String): FileResult<Unit> =
        at(area, path) { files -> if (files.remove(path) != null) FileResult.Ok(Unit) else FileResult.NotFound }

    /** Existence is not content: a denied file still exists, as a platform `stat` answers for a protected one. */
    override fun exists(area: FileArea, path: String): FileResult<Boolean> =
        areas[area]?.let { FileResult.Ok(path in it) } ?: FileResult.AreaUnavailable

    override fun locate(area: FileArea, path: String): FileResult<String> =
        if (areas[area] == null) FileResult.AreaUnavailable else FileResult.Ok("mem:/${area.name.lowercase()}/$path")
}
