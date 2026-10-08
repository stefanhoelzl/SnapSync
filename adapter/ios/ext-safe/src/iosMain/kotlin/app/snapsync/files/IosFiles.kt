package app.snapsync.files

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.objc.ObjCFailure
import app.snapsync.objc.checkedObjC
import app.snapsync.objc.checkedObjCValue
import app.snapsync.ports.Files
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.EACCES
import platform.posix.ENOENT
import platform.posix.EPERM
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.write

/**
 * The iOS [Files]: [FileArea.SHARED] is the **App-Group container** (both processes; readable while locked after
 * first unlock), [FileArea.PRIVATE] this process's **`Documents/`**.
 *
 * The two roots are resolved once — the production constructor looks them up when the adapter is built, a path
 * resolve and not I/O — and a test passes directories it owns, because a bundle-less test binary has no App-Group
 * entitlement. A `null` root is an area this process cannot reach: every operation on it answers
 * [FileResult.AreaUnavailable], never absence.
 *
 * **Error mapping is the load-bearing part** ([isFileAbsence], [isFileDenied]): only a definite not-found answers
 * [FileResult.NotFound]; the permission class answers [FileResult.Denied]; anything else [FileResult.Failed].
 * A read of the config file misclassified as not-found is a false leave, with no error anywhere.
 *
 * Writes are atomic (temp file and rename) under `CompleteUntilFirstUserAuthentication` — the protection class
 * the ledger and download databases carry — and create missing parent directories.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFiles(private val sharedRoot: String?, private val privateRoot: String?) : Files {

    /** Production: the App-Group container and this process's `Documents/` (a secondary constructor, not defaults). */
    constructor() : this(
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String,
    )

    private val fm = NSFileManager.defaultManager

    private fun resolve(area: FileArea, path: String): String? =
        when (area) {
            FileArea.SHARED -> sharedRoot
            FileArea.PRIVATE -> privateRoot
        }?.let { "$it/$path" }

    override fun read(area: FileArea, path: String): FileResult<ByteArray> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        val read =
            checkedObjCValue("dataWithContentsOfFile") { NSData.dataWithContentsOfFile(file, options = 0u, error = it) }
        read.getOrNull()?.let { return FileResult.Ok(it.toByteArray()) }
        return (read.exceptionOrNull() as ObjCFailure).toResult()
    }

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        val fd = open(file, O_RDONLY)
        if (fd < 0) return posixFailure("open")
        try {
            val size = lseek(fd, 0, SEEK_END)
            if (size < 0) return posixFailure("lseek")
            val take = minOf(size, maxOf(maxBytes, 0).toLong())
            if (lseek(fd, size - take, SEEK_SET) < 0) return posixFailure("lseek")
            val buffer = ByteArray(take.toInt())
            var filled = 0
            buffer.usePinned { pinned ->
                while (filled < buffer.size) {
                    val n = read(fd, pinned.addressOf(filled), (buffer.size - filled).convert()).toInt()
                    if (n < 0) return posixFailure("read")
                    if (n == 0) break
                    filled += n
                }
            }
            return FileResult.Ok(FileTail(buffer.copyOf(filled), cut = take < size))
        } finally {
            close(fd)
        }
    }

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        val fd = open(file, O_RDONLY)
        if (fd < 0) return posixFailure("open")
        try {
            if (lseek(fd, maxOf(offset, 0), SEEK_SET) < 0) return posixFailure("lseek")
            val buffer = ByteArray(maxOf(maxBytes, 0))
            var filled = 0
            buffer.usePinned { pinned ->
                while (filled < buffer.size) {
                    val n = read(fd, pinned.addressOf(filled), (buffer.size - filled).convert()).toInt()
                    if (n < 0) return posixFailure("read")
                    if (n == 0) break
                    filled += n
                }
            }
            return FileResult.Ok(if (filled == buffer.size) buffer else buffer.copyOf(filled))
        } finally {
            close(fd)
        }
    }

    /**
     * Created with the container's default protection — `CompleteUntilFirstUserAuthentication`, the entitlement's
     * class — like every other file here, so a piece written by a background wake is readable by the next one.
     */
    override fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        checkedObjC("createDirectoryAtPath") {
            fm.createDirectoryAtPath(
                file.substringBeforeLast('/'),
                withIntermediateDirectories = true,
                attributes = null,
                error = it,
            )
        }.onFailure { return (it as ObjCFailure).toResult().withoutAbsence(path) }
        val fd = open(file, O_WRONLY or O_CREAT or O_APPEND, FILE_MODE)
        if (fd < 0) return posixFailure("open").withoutAbsence(path)
        try {
            var written = 0
            bytes.usePinned { pinned ->
                while (written < bytes.size) {
                    val n = write(fd, pinned.addressOf(written), (bytes.size - written).convert()).toInt()
                    if (n <= 0) return posixFailure("write").withoutAbsence(path)
                    written += n
                }
            }
            return FileResult.Ok(Unit)
        } finally {
            close(fd)
        }
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        checkedObjC("createDirectoryAtPath") {
            fm.createDirectoryAtPath(
                file.substringBeforeLast('/'),
                withIntermediateDirectories = true,
                attributes = null,
                error = it,
            )
        }.onFailure { return (it as ObjCFailure).toResult().withoutAbsence(path) }
        return checkedObjC("writeToFile") {
            bytes.toNSData().writeToFile(
                file,
                options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
                error = it,
            )
        }.fold(onSuccess = { FileResult.Ok(Unit) }, onFailure = { (it as ObjCFailure).toResult().withoutAbsence(path) })
    }

    override fun delete(area: FileArea, path: String): FileResult<Unit> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        // `removeItemAtPath` empties a directory recursively; removing a file never does.
        if (isDirectory(file)) return FileResult.Failed("$path is a directory, not a file")
        return checkedObjC("removeItemAtPath") { fm.removeItemAtPath(file, error = it) }
            .fold(onSuccess = { FileResult.Ok(Unit) }, onFailure = { (it as ObjCFailure).toResult() })
    }

    // `stat`, never `fileExistsAtPath`, which answers `false` when it could not look.
    override fun exists(area: FileArea, path: String): FileAccess<Boolean> {
        val file = resolve(area, path) ?: return FileResult.AreaUnavailable
        return when (val err = statErrno(file)) {
            0 -> FileResult.Ok(true)
            ENOENT -> FileResult.Ok(false)
            else -> errnoFailure("stat", err)
        }
    }

    override fun locate(area: FileArea, path: String): FileLocation<String> =
        resolve(area, path)?.let { FileResult.Ok(it) } ?: FileResult.AreaUnavailable

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> {
        val source = resolve(area, from) ?: return FileResult.AreaUnavailable
        return moveReplacing(source, resolve(area, to) ?: return FileResult.AreaUnavailable)
    }

    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> =
        moveReplacing(osPath, resolve(area, to) ?: return FileResult.AreaUnavailable)

    override fun list(area: FileArea, directory: String): FileAccess<List<String>> {
        val dir = resolve(area, directory) ?: return FileResult.AreaUnavailable
        if (!fm.fileExistsAtPath(dir)) return FileResult.Ok(emptyList())
        if (!isDirectory(dir)) return FileResult.Failed("$directory is a file, not a directory")
        val entries = checkedObjCValue("subpathsOfDirectoryAtPath") { fm.subpathsOfDirectoryAtPath(dir, error = it) }
            .getOrElse { return (it as ObjCFailure).toResult().withoutAbsence(directory) }
        return FileResult.Ok(
            entries.filterIsInstance<String>()
                .filter { relative -> isRegularFile("$dir/$relative") }
                .map { "$directory/$it" }
                .sorted(),
        )
    }

    private fun isRegularFile(path: String): Boolean = memScoped {
        val isDirectory = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDirectory = isDirectory.ptr) && !isDirectory.value
    }

    private fun isDirectory(path: String): Boolean = memScoped {
        val isDirectory = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDirectory = isDirectory.ptr) && isDirectory.value
    }

    /** `stat`'s answer for [path], following links as every read does: 0 when it is there, else its `errno`. */
    private fun statErrno(path: String): Int = memScoped { if (stat(path, alloc<stat>().ptr) == 0) 0 else errno }

    /** Move [source] to [destination]: parents created, the previous destination removed; last write wins. */
    private fun moveReplacing(source: String, destination: String): FileResult<Unit> {
        when (val err = statErrno(source)) {
            0 -> Unit
            ENOENT -> return FileResult.NotFound
            else -> return errnoFailure("stat", err)
        }
        checkedObjC("createDirectoryAtPath") {
            fm.createDirectoryAtPath(
                destination.substringBeforeLast('/'),
                withIntermediateDirectories = true,
                attributes = null,
                error = it,
            )
        }.onFailure { return (it as ObjCFailure).toResult() }
        checkedObjC("removeItemAtPath") { fm.removeItemAtPath(destination, error = it) }
            .onFailure { failure ->
                val result = (failure as ObjCFailure).toResult()
                if (result != FileResult.NotFound) return result
            }
        return checkedObjC("moveItemAtPath") { fm.moveItemAtPath(source, toPath = destination, error = it) }
            .fold(onSuccess = { FileResult.Ok(Unit) }, onFailure = { (it as ObjCFailure).toResult() })
    }

    private fun ObjCFailure.toResult(): FileResult<Nothing> {
        val code = code
        val detail = "$call: ${description ?: "no error description"} (domain=$domain code=$code)"
        return when {
            code != null && isFileAbsence(domain, code) -> FileResult.NotFound
            code != null && isFileDenied(domain, code) -> FileResult.Denied(detail, code)
            else -> FileResult.Failed(detail, code)
        }
    }

    private fun posixFailure(call: String): FileResult<Nothing> {
        val err = errno
        return if (err == ENOENT) FileResult.NotFound else errnoFailure(call, err)
    }

    private fun errnoFailure(call: String, err: Int): FileAccess<Nothing> = when (err) {
        EPERM, EACCES -> FileResult.Denied("$call: errno $err", err.toLong())
        else -> FileResult.Failed("$call: errno $err", err.toLong())
    }

    /** [this] failure where absence is no outcome: a not-found there is the platform failing to say, never absence. */
    private fun FileResult<Nothing>.withoutAbsence(what: String): FileAccess<Nothing> = when (this) {
        is FileAccess -> this
        FileResult.NotFound -> FileResult.Failed("$what: a missing file is no answer here")
    }
}

/** Owner read/write: the container is the process's own, and the platform's protection class guards it. */
private const val FILE_MODE: UShort = 0x180u

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
