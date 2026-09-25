package app.snapsync.files

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Files
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.Files as Nio

/**
 * The JVM [Files]: two directories standing for the areas — the file system of the JVM binaries (the desktop
 * harnesses, the JVM rig host), and the live host of the `Files` contract on every `./gradlew build`.
 *
 * A `null` directory is an area this process cannot reach ([FileResult.AreaUnavailable]). Not-found is only a
 * missing file; a present file the process may not read is [FileResult.Denied] — the same split the iOS adapter
 * holds, and the one the contract pins.
 */
class JvmFiles(private val shared: File?, private val private: File?) : Files {

    private fun resolve(area: FileArea, path: String): File? =
        when (area) {
            FileArea.SHARED -> shared
            FileArea.PRIVATE -> private
        }?.let { File(it, path) }

    override fun read(area: FileArea, path: String): FileResult<ByteArray> =
        io(area, path) { FileResult.Ok(Nio.readAllBytes(it.toPath())) }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = io(area, path) { file ->
        if (!file.exists()) throw NoSuchFileException(file.path)
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val take = minOf(size, maxOf(maxBytes, 0).toLong())
            raf.seek(size - take)
            val bytes = ByteArray(take.toInt())
            raf.readFully(bytes)
            FileResult.Ok(FileTail(bytes, cut = take < size))
        }
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = io(area, path) { file ->
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeBytes(bytes)
        Nio.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileResult.Ok(Unit)
    }

    override fun delete(area: FileArea, path: String): FileResult<Unit> =
        io(area, path) { Nio.delete(it.toPath()); FileResult.Ok(Unit) }

    override fun exists(area: FileArea, path: String): FileResult<Boolean> =
        io(area, path) { FileResult.Ok(it.exists()) }

    override fun locate(area: FileArea, path: String): FileResult<String> =
        resolve(area, path)?.let { FileResult.Ok(it.absolutePath) } ?: FileResult.AreaUnavailable

    private fun <T> io(area: FileArea, path: String, op: (File) -> FileResult<T>): FileResult<T> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        return runCatchingCancellable { op(file) }.getOrElse { failure ->
            when (failure) {
                is NoSuchFileException, is java.io.FileNotFoundException ->
                    if (file.exists()) FileResult.Denied("${failure::class.simpleName}: ${failure.message}") else FileResult.NotFound
                is AccessDeniedException, is SecurityException -> FileResult.Denied("${failure::class.simpleName}: ${failure.message}")
                else -> FileResult.Failed("${failure::class.simpleName}: ${failure.message}")
            }
        }
    }
}
