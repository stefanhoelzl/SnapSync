package app.snapsync.ports

import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail

/**
 * **The platform's file system, by area** — one external system, and nothing decided here (`docs/architecture.md`,
 * "Ports are one external system each"). What a file holds, and what its absence means, are the services'
 * business (`:domain:services`).
 *
 * Every path is **relative to its [FileArea]**, `/`-separated; the adapter alone resolves an area to a platform
 * directory, so no absolute platform path is stored by the core (a restored or migrated device may place the
 * container elsewhere). The one exit is [locate], for a platform API that must be handed a file (a photo-library
 * import).
 *
 * Every answer is a [FileResult] that keeps "not there" and "could not look" apart. [FileResult.NotFound] is a
 * definite absence and nothing else: an unreadable file is [FileResult.Denied] or [FileResult.Failed], never
 * absent — a config file read as absent is a leave. A member for which absence is no outcome answers the narrower
 * [FileAccess] (or [FileLocation]), so it cannot say [FileResult.NotFound] at all.
 *
 * Writes create missing parent directories, replace atomically, and leave the file readable while the device is
 * locked after its first unlock (the upload extension runs there).
 */
interface Files : Port {

    /** The whole file. */
    fun read(area: FileArea, path: String): FileResult<ByteArray>

    /**
     * At most [maxBytes] from the end of the file, read by seeking rather than by reading it all.
     * [FileResult.NotFound] for a missing file; an empty file answers an empty tail.
     */
    fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail>

    /**
     * At most [maxBytes] of the file from byte [offset] on, read by seeking — how a file too large to hold is read
     * piece by piece. Fewer bytes than asked only at the end of the file; an [offset] at or past the end answers an
     * empty array. [FileResult.NotFound] for a missing file.
     */
    fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray>

    /** Replace the file with [bytes], atomically, creating its parent directories. */
    fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit>

    /**
     * Add [bytes] to the end of the file, creating it — and its parent directories — when missing. NOT atomic: how a
     * file too large to hold is written piece by piece, so a caller builds it under a name of its own and [move]s it
     * into place once it is whole.
     */
    fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit>

    /** Remove the file. [FileResult.NotFound] when there was nothing to remove. */
    fun delete(area: FileArea, path: String): FileResult<Unit>

    /** Whether the file exists. A lookup that could not be made is never `false`. */
    fun exists(area: FileArea, path: String): FileAccess<Boolean>

    /** The platform path of [path] in [area], for a platform API that must be handed a file. Nothing is created. */
    fun locate(area: FileArea, path: String): FileLocation<String>

    /**
     * Move [from] to [to] within [area], replacing whatever [to] held and creating its parent directories.
     * [FileResult.NotFound] when [from] does not exist.
     */
    fun move(area: FileArea, from: String, to: String): FileResult<Unit>

    /**
     * Take over the file the platform handed the process at [osPath] — a finished download's temporary file, which
     * the OS deletes when its callback returns — moving it to [to] in [area], replacing whatever [to] held and
     * creating its parent directories. The one member that takes a platform path: the platform chose it, and the
     * file must leave it before the callback returns. [FileResult.NotFound] when nothing is at [osPath].
     */
    fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit>

    /**
     * Every file under [directory] in [area], at any depth, as paths relative to the area (so each starts with
     * `directory/`), sorted. A directory that does not exist holds no files: an empty list, never
     * [FileResult.NotFound]. A directory that could not be read is never empty.
     */
    fun list(area: FileArea, directory: String): FileAccess<List<String>>
}
