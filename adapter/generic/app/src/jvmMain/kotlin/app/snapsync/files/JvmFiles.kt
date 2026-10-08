package app.snapsync.files

import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files as Nio

/**
 * The JVM [Files]: two directories standing for the areas — the file system of the JVM binaries (the desktop
 * harnesses, the JVM rig host), and the live host of the `Files` contract on every `./gradlew build`.
 *
 * A `null` directory is an area this process cannot reach ([FileResult.AreaUnavailable]). Not-found is only a
 * missing file; a present file the process may not read is [FileResult.Denied] — the same split the iOS and Android
 * adapters hold, and the one the contract pins. So every operation goes through `java.nio.file`, never `java.io`,
 * whose streams answer a file this process may not read with a `FileNotFoundException` — the permission failure
 * dressed as absence — and whose `File.exists` answers `false` when it could not look.
 */
class JvmFiles(private val shared: File?, private val private: File?) : Files {

    /** [path] under [area]'s directory — always a child of it, so a resolved path always has a parent. */
    private fun resolve(area: FileArea, path: String): Path? =
        when (area) {
            FileArea.SHARED -> shared
            FileArea.PRIVATE -> private
        }?.let { File(it, path).toPath() }

    override fun read(area: FileArea, path: String): FileResult<ByteArray> =
        guarded(area, path) { FileResult.Ok(Nio.readAllBytes(it)) }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> =
        guarded(area, path) { file ->
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val take = minOf(size, maxOf(maxBytes, 0).toLong())
                FileResult.Ok(FileTail(channel.readFrom(size - take, take.toInt()), cut = take < size))
            }
        }

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> =
        guarded(area, path) { file ->
            FileChannel.open(file, StandardOpenOption.READ).use {
                FileResult.Ok(it.readFrom(maxOf(offset, 0), maxOf(maxBytes, 0)))
            }
        }

    override fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> = access(area, path) { file ->
        Nio.createDirectories(file.parent)
        Nio.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        FileResult.Ok(Unit)
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> = access(area, path) { file ->
        Nio.createDirectories(file.parent)
        val temp = file.resolveSibling(".${file.fileName}.tmp")
        Nio.write(temp, bytes)
        Nio.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileResult.Ok(Unit)
    }

    override fun delete(area: FileArea, path: String): FileResult<Unit> =
        guarded(area, path) {
            Nio.delete(it)
            FileResult.Ok(Unit)
        }

    // The attributes, never `File.exists`, which answers `false` when it could not look.
    override fun exists(area: FileArea, path: String): FileAccess<Boolean> = access(area, path) { file ->
        try {
            Nio.readAttributes(file, BasicFileAttributes::class.java)
            FileResult.Ok(true)
        } catch (_: NoSuchFileException) {
            FileResult.Ok(false)
        }
    }

    override fun locate(area: FileArea, path: String): FileLocation<String> =
        resolve(area, path)?.let { FileResult.Ok(it.toAbsolutePath().toString()) } ?: FileResult.AreaUnavailable

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> {
        val destination = resolve(area, to) ?: return FileResult.AreaUnavailable
        return guarded(area, from) { source -> moveReplacing(source, destination) }
    }

    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> =
        guarded(area, to) { destination -> moveReplacing(File(osPath).toPath(), destination) }

    override fun list(area: FileArea, directory: String): FileAccess<List<String>> {
        val root = resolve(area, "") ?: return FileResult.AreaUnavailable
        return access(area, directory) { dir ->
            if (!Nio.exists(dir)) return@access FileResult.Ok(emptyList())
            if (!Nio.isDirectory(dir)) throw NotDirectoryException(dir.toString())
            Nio.walk(dir).use { paths ->
                val files = paths.filter { Nio.isRegularFile(it) }.map { root.relativize(it).joinToString("/") }
                FileResult.Ok(files.sorted().toList())
            }
        }
    }

    /** Move [source] to [destination]: parents created, the previous destination replaced; last write wins. */
    private fun moveReplacing(source: Path, destination: Path): FileResult<Unit> {
        // A source that is not there throws `NoSuchFileException` here; one that could not be looked for, its reason.
        Nio.readAttributes(source, BasicFileAttributes::class.java)
        Nio.createDirectories(destination.parent)
        Nio.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return FileResult.Ok(Unit)
    }

    /** At most [count] bytes from [position] on — fewer only at the end of the file. */
    private fun FileChannel.readFrom(position: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        var at = position
        while (buffer.hasRemaining()) {
            val n = read(buffer, at)
            if (n <= 0) break
            at += n
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun <T> guarded(area: FileArea, path: String, op: (Path) -> FileResult<T>): FileResult<T> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        return try {
            op(file)
        } catch (_: NoSuchFileException) {
            FileResult.NotFound
        } catch (e: IOException) {
            e.toAccessFailure()
        }
    }

    /** [guarded], for an operation absence is no outcome of: a file it did not find is a failure, never not-found. */
    private fun <T> access(area: FileArea, path: String, op: (Path) -> FileAccess<T>): FileAccess<T> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        return try {
            op(file)
        } catch (e: IOException) {
            e.toAccessFailure()
        }
    }

    private fun IOException.toAccessFailure(): FileAccess<Nothing> = when (this) {
        is AccessDeniedException -> FileResult.Denied("${this::class.simpleName}: $message")
        else -> FileResult.Failed("${this::class.simpleName}: $message")
    }
}
