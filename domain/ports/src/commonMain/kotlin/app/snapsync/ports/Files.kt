package app.snapsync.ports

import app.snapsync.model.FileArea
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
 * absent — a config file read as absent is a leave.
 *
 * Writes create missing parent directories, replace atomically, and leave the file readable while the device is
 * locked after its first unlock (the upload extension runs there).
 */
interface Files {

    /** The whole file. */
    fun read(area: FileArea, path: String): FileResult<ByteArray>

    /**
     * At most [maxBytes] from the end of the file, read by seeking rather than by reading it all.
     * [FileResult.NotFound] for a missing file; an empty file answers an empty tail.
     */
    fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail>

    /** Replace the file with [bytes], atomically, creating its parent directories. */
    fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit>

    /** Remove the file. [FileResult.NotFound] when there was nothing to remove. */
    fun delete(area: FileArea, path: String): FileResult<Unit>

    /** Whether the file exists. A lookup that could not be made is never `false`. */
    fun exists(area: FileArea, path: String): FileResult<Boolean>

    /** The platform path of [path] in [area], for a platform API that must be handed a file. Nothing is created. */
    fun locate(area: FileArea, path: String): FileResult<String>
}
