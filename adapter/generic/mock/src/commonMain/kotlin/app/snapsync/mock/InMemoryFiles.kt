package app.snapsync.mock

import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files

/**
 * The honest in-memory [Files]: one map of path → bytes per area, the maps being the caller's own cells (initial
 * state by constructor, per the fake-honesty rule). An area mapped to `null` is unreachable — every operation
 * answers [FileResult.AreaUnavailable] — and a path in [denied] is a present file this process may not touch,
 * answering [FileResult.Denied] and never absence. A path in [undeletable] is readable and writable, but its delete is
 * [FileResult.Denied] — a file the platform will not remove.
 */
internal class InMemoryFiles(
    private val areas: Map<FileArea, MutableMap<String, ByteArray>?>,
    private val denied: Set<Pair<FileArea, String>>,
    private val undeletable: Set<Pair<FileArea, String>> = emptySet(),
) : Files {

    private fun <T> at(area: FileArea, path: String, op: (MutableMap<String, ByteArray>) -> FileResult<T>): FileResult<T> {
        val files = areas[area] ?: return FileResult.AreaUnavailable
        if ((area to path) in denied) return FileResult.Denied("$area/$path is denied")
        return op(files)
    }

    /** [at], for an operation absence is no outcome of. */
    private fun <T> access(
        area: FileArea,
        path: String,
        op: (MutableMap<String, ByteArray>) -> FileAccess<T>,
    ): FileAccess<T> {
        val files = areas[area] ?: return FileResult.AreaUnavailable
        if ((area to path) in denied) return FileResult.Denied("$area/$path is denied")
        return op(files)
    }

    /** A path that prefixes held ones is a directory: there, so never absent, and no file to read or remove. */
    private fun MutableMap<String, ByteArray>.isDirectory(path: String) = keys.any { it.startsWith("$path/") }

    /** A path beneath a held file runs through a file, so no directory on it can be created. */
    private fun MutableMap<String, ByteArray>.throughAFile(path: String) = keys.any { path.startsWith("$it/") }

    /** A directory (see [list]) is there, so never absent, and not a file to read. */
    override fun read(area: FileArea, path: String): FileResult<ByteArray> = at(area, path) { files ->
        files[path]?.let { FileResult.Ok(it.copyOf()) }
            ?: if (files.keys.any { it.startsWith("$path/") }) FileResult.Failed("$path is a directory") else FileResult.NotFound
    }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = at(area, path) { files ->
        if (files.isDirectory(path)) return@at FileResult.Failed("$path is a directory")
        val bytes = files[path] ?: return@at FileResult.NotFound
        val take = minOf(bytes.size, maxOf(maxBytes, 0))
        FileResult.Ok(FileTail(bytes.copyOfRange(bytes.size - take, bytes.size), cut = take < bytes.size))
    }

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> =
        at(area, path) { files ->
            if (files.isDirectory(path)) return@at FileResult.Failed("$path is a directory")
            val bytes = files[path] ?: return@at FileResult.NotFound
            val start = minOf(maxOf(offset, 0), bytes.size.toLong()).toInt()
            FileResult.Ok(
                bytes.copyOfRange(start, minOf(bytes.size.toLong(), start.toLong() + maxOf(maxBytes, 0)).toInt()),
            )
        }

    override fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> =
        access(area, path) { files ->
            if (files.throughAFile(path)) return@access FileResult.Failed("$path runs through a file")
            files[path] = (files[path] ?: ByteArray(0)) + bytes
            FileResult.Ok(Unit)
        }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> =
        access(area, path) { files ->
            if (files.throughAFile(path)) return@access FileResult.Failed("$path runs through a file")
            files[path] = bytes.copyOf()
            FileResult.Ok(Unit)
        }

    override fun delete(area: FileArea, path: String): FileResult<Unit> = at(area, path) { files ->
        when {
            (area to path) in undeletable -> FileResult.Denied("$area/$path cannot be deleted")
            files.isDirectory(path) -> FileResult.Failed("$path is a directory")
            files.remove(path) != null -> FileResult.Ok(Unit)
            else -> FileResult.NotFound
        }
    }

    /** Existence is not content: a denied file still exists, as a platform `stat` answers for a protected one. */
    override fun exists(area: FileArea, path: String): FileAccess<Boolean> =
        areas[area]?.let { FileResult.Ok(path in it) } ?: FileResult.AreaUnavailable

    override fun locate(area: FileArea, path: String): FileLocation<String> =
        if (areas[area] == null) FileResult.AreaUnavailable else FileResult.Ok("$MEM${area.name.lowercase()}/$path")

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = at(area, to) { files ->
        if (files.throughAFile(to)) return@at FileResult.Failed("$to runs through a file")
        val bytes = files.remove(from) ?: return@at FileResult.NotFound
        files[to] = bytes
        FileResult.Ok(Unit)
    }

    /**
     * The platform paths this double hands out are its own [locate] answers, so a file is adopted from wherever one
     * of them points; any other path names nothing it holds.
     */
    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> {
        if (areas[area] == null) return FileResult.AreaUnavailable
        val source = FileArea.entries.firstOrNull { osPath.startsWith("$MEM${it.name.lowercase()}/") }
            ?: return FileResult.NotFound
        val sourcePath = osPath.removePrefix("$MEM${source.name.lowercase()}/")
        val bytes = areas[source]?.get(sourcePath) ?: return FileResult.NotFound
        return at(area, to) { files ->
            if (files.throughAFile(to)) return@at FileResult.Failed("$to runs through a file")
            areas[source]?.remove(sourcePath)
            files[to] = bytes
            FileResult.Ok(Unit)
        }
    }

    /** No directories are held, only paths: a directory holds what its prefix names. */
    override fun list(area: FileArea, directory: String): FileAccess<List<String>> {
        val files = areas[area] ?: return FileResult.AreaUnavailable
        if (directory in files) return FileResult.Failed("$directory is a file, not a directory")
        return FileResult.Ok(files.keys.toList().filter { it.startsWith("$directory/") }.sorted())
    }

    private companion object {
        const val MEM = "mem:/"
    }
}
