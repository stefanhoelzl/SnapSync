package app.snapsync.ios.urlsession

import app.snapsync.objc.checkedObjC
import app.snapsync.objc.checkedObjCValue
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath

/**
 * The rest of a file, from a byte on, as a file of its own — what a resumed upload sends (`docs/architecture.md`,
 * "Resumable uploads"). A background `URLSession` uploads only a WHOLE file (`uploadTask(with:fromFile:)`; a streamed
 * body is refused in a background session), so the bytes from the resume point are copied out once. Copying 500 MB
 * takes about a second on the SE2's storage.
 *
 * The slices live in the app's Caches, apart from the shared upload staging the core sweeps, one per transfer
 * tag: a new slice for a tag replaces the last, and a transfer's end deletes its own. Every file-handle call is the
 * error-returning form, read through `checkedObjC`: the older ones raise an Objective-C exception no Kotlin catches.
 */
@OptIn(ExperimentalForeignApi::class)
internal object UploadSlices {

    private const val CHUNK = 1_048_576uL
    private val log = Logger.withTag("UploadSlices")

    private val directory: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/upload-slices".also { dir ->
            checkedObjC("createDirectoryAtPath") {
                NSFileManager.defaultManager.createDirectoryAtPath(
                    dir,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = it,
                )
            }.onFailure { log.w { "the slice directory could not be made: ${it.message}" } }
        }
    }

    private fun pathOf(tag: String) = "$directory/${tag.replace('/', '_')}"

    /** [file] from byte [from] on, copied to [tag]'s slice; its path, or `null` when the copy failed. */
    fun cut(file: String, from: Long, tag: String): String? {
        val slice = pathOf(tag)
        release(tag)
        if (!NSFileManager.defaultManager.createFileAtPath(slice, contents = null, attributes = null)) return null
        val reader = NSFileHandle.fileHandleForReadingAtPath(file)
        val writer = NSFileHandle.fileHandleForWritingAtPath(slice)
        val copied = reader != null && writer != null && copy(reader, writer, from)
        reader?.let { r -> checkedObjC("closeAndReturnError") { r.closeAndReturnError(it) } }
        writer?.let { w -> checkedObjC("closeAndReturnError") { w.closeAndReturnError(it) } }
        if (!copied) release(tag)
        return slice.takeIf { copied }
    }

    private fun copy(reader: NSFileHandle, writer: NSFileHandle, from: Long): Boolean {
        val sought = checkedObjC("seekToOffset") { reader.seekToOffset(from.toULong(), error = it) }
        if (sought.isFailure) return false.also { log.w { "seek failed: ${sought.exceptionOrNull()?.message}" } }
        while (true) {
            val read = checkedObjCValue("readDataUpToLength") { reader.readDataUpToLength(CHUNK, error = it) }
            val chunk = read.getOrNull() ?: return false.also { log.w { "read failed: ${read.exceptionOrNull()?.message}" } }
            if (chunk.length == 0uL) return true
            val written = checkedObjC("writeData") { writer.writeData(chunk, error = it) }
            if (written.isFailure) return false.also { log.w { "write failed: ${written.exceptionOrNull()?.message}" } }
        }
    }

    /** Delete [tag]'s slice, if it has one — its transfer ended, or a new one replaces it. */
    fun release(tag: String) {
        val path = pathOf(tag)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        checkedObjC("removeItemAtPath") { NSFileManager.defaultManager.removeItemAtPath(path, error = it) }
            .onFailure { log.w { "the slice for $tag stays: ${it.message}" } }
    }
}
