package app.snapsync.android.storage

import android.content.Context
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files as Nio

/**
 * The Android [Files]: both areas are **app-private** directories — [FileArea.SHARED] `filesDir/shared`,
 * [FileArea.PRIVATE] `filesDir/private`. Android runs every entry point in the app's one process, so nothing is shared
 * with a second one; the areas stay two directories so a path means in each what it means on iOS, where the same
 * relative path may exist in both.
 *
 * **Error mapping is the load-bearing part**, as on iOS: only a definite not-found answers [FileResult.NotFound]; the
 * permission class answers [FileResult.Denied]; anything else [FileResult.Failed]. A read of the config file
 * misclassified as not-found is a false leave, with no error anywhere. So every operation goes through `java.nio.file`,
 * never `java.io`: `FileInputStream` and `RandomAccessFile` answer a file this process may not read with a
 * `FileNotFoundException` — the permission failure dressed as absence — where NIO throws `AccessDeniedException`.
 *
 * Writes are atomic (a temp file beside the target, then a rename) and create missing parent directories. There is no
 * locked-device read to arrange: the app is not direct-boot aware, so no code of it runs before the first unlock.
 */
class AndroidFiles(private val sharedRoot: File, private val privateRoot: File) : Files {

    /** Production: the two areas under the app's `filesDir` (a secondary constructor, not defaults). */
    constructor(context: Context) : this(File(context.filesDir, "shared"), privateArea(context))

    private fun resolve(area: FileArea, path: String): Path =
        File(
            when (area) {
                FileArea.SHARED -> sharedRoot
                FileArea.PRIVATE -> privateRoot
            },
            path,
        ).toPath()

    override fun read(area: FileArea, path: String): FileResult<ByteArray> =
        guarded { FileResult.Ok(Nio.readAllBytes(resolve(area, path))) }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = guarded {
        FileChannel.open(resolve(area, path), StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val take = minOf(size, maxOf(maxBytes, 0).toLong())
            val buffer = ByteBuffer.allocate(take.toInt())
            var position = size - take
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n <= 0) break
                position += n
            }
            FileResult.Ok(FileTail(buffer.array().copyOf(buffer.position()), cut = take < size))
        }
    }

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> = guarded {
        FileChannel.open(resolve(area, path), StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(maxOf(maxBytes, 0))
            var position = maxOf(offset, 0)
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n <= 0) break
                position += n
            }
            FileResult.Ok(buffer.array().copyOf(buffer.position()))
        }
    }

    override fun append(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = guarded {
        val target = resolve(area, path)
        Nio.createDirectories(target.parent)
        Nio.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        FileResult.Ok(Unit)
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = guarded {
        val target = resolve(area, path)
        Nio.createDirectories(target.parent)
        val temp = Nio.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Nio.write(temp, bytes)
            Nio.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Nio.deleteIfExists(temp)
        }
        FileResult.Ok(Unit)
    }

    override fun delete(area: FileArea, path: String): FileResult<Unit> = guarded {
        Nio.delete(resolve(area, path))
        FileResult.Ok(Unit)
    }

    // `Files.exists` answers `false` when it could not look; reading the attributes keeps the two apart.
    override fun exists(area: FileArea, path: String): FileResult<Boolean> = try {
        Nio.readAttributes(resolve(area, path), "basic:isRegularFile")
        FileResult.Ok(true)
    } catch (_: NoSuchFileException) {
        FileResult.Ok(false)
    } catch (e: IOException) {
        e.toResult()
    }

    override fun locate(area: FileArea, path: String): FileResult<String> = FileResult.Ok(resolve(area, path).toString())

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> =
        moveReplacing(resolve(area, from), resolve(area, to))

    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> =
        moveReplacing(File(osPath).toPath(), resolve(area, to))

    override fun list(area: FileArea, directory: String): FileResult<List<String>> = guarded {
        val root = resolve(area, "")
        val dir = resolve(area, directory)
        if (!Nio.exists(dir)) return@guarded FileResult.Ok(emptyList())
        Nio.walk(dir).use { paths ->
            FileResult.Ok(
                paths.filter { Nio.isRegularFile(it) }
                    .map { root.relativize(it).joinToString("/") }
                    .sorted()
                    .toList(),
            )
        }
    }

    /** Move [source] to [destination]: parents created, the previous destination replaced; last write wins. */
    private fun moveReplacing(source: Path, destination: Path): FileResult<Unit> = guarded {
        if (!Nio.exists(source)) return@guarded FileResult.NotFound
        Nio.createDirectories(destination.parent)
        Nio.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        FileResult.Ok(Unit)
    }

    private inline fun <T> guarded(block: () -> FileResult<T>): FileResult<T> = try {
        block()
    } catch (e: IOException) {
        e.toResult()
    } catch (e: SecurityException) {
        FileResult.Denied("${e::class.simpleName}: ${e.message}")
    }

    private fun IOException.toResult(): FileResult<Nothing> = when (this) {
        is NoSuchFileException -> FileResult.NotFound
        is AccessDeniedException -> FileResult.Denied("${this::class.simpleName}: $message")
        else -> FileResult.Failed("${this::class.simpleName}: $message")
    }

    companion object {
        /** The [FileArea.PRIVATE] directory — also where the process's own log file is written, so the dump can read it. */
        fun privateArea(context: Context): File = File(context.filesDir, "private")
    }
}
