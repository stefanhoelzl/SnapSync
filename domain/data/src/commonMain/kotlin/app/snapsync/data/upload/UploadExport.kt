package app.snapsync.data.upload

import app.snapsync.data.crypto.SealOrder
import app.snapsync.data.crypto.SealOutcome
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.FileArea
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.WriteOutcome

/**
 * **A resource's bytes, exported to its staged file** for a session that sends files — and, for an encrypted event,
 * sealed into it: exported beside the staged file and sealed INTO it, the plaintext deleted at once. A failed export or
 * seal leaves no file behind.
 */
class UploadExport(
    private val gallery: GalleryReader,
    private val files: Files,
    private val sealing: UploadSealing,
    private val staged: StagedFiles,
) {
    private val log = Log("UploadExport")

    /** [handle]'s bytes exported to [key]'s staged file, at the OS path [at]. */
    suspend fun plain(key: String, at: String, handle: GalleryResourceHandle): Exported =
        exportedAt(key, staged.path(key), at, handle)

    /** [handle]'s bytes exported beside [key]'s staged file and sealed into it as [order] says. */
    suspend fun sealed(key: String, handle: GalleryResourceHandle, order: SealOrder): Exported =
        when (val placed = staged.located(plaintext(key))) {
            Placement.Unplaced -> unplaced(key)
            is Placement.At -> sealedAfter(key, order, exportedAt(key, plaintext(key), placed.path, handle))
        }

    private suspend fun sealedAfter(key: String, order: SealOrder, export: Exported): Exported = when (export) {
        Exported.DONE -> sealedFrom(key, order)
        Exported.FAILED -> export
    }

    private suspend fun exportedAt(key: String, to: String, at: String, handle: GalleryResourceHandle): Exported {
        // The directory the export writes into: `write` creates parents, and the empty file is replaced by the export.
        files.write(FileArea.SHARED, to, ByteArray(0))
        return written(key, to, gallery.export(handle, at))
    }

    private suspend fun unplaced(key: String): Exported {
        log.w("the shared area cannot hold the export — not creating", "key" of key)
        return Exported.FAILED
    }

    private suspend fun written(key: String, to: String, export: WriteOutcome): Exported = when (export) {
        WriteOutcome.Ok -> Exported.DONE
        is WriteOutcome.Failed -> failed(key, to, "exporting", export.detail)
    }

    private suspend fun sealedFrom(key: String, order: SealOrder): Exported {
        val sealed = sealing.seal(order, plaintext(key), staged.path(key))
        files.delete(FileArea.SHARED, plaintext(key))
        return when (sealed) {
            SealOutcome.Sealed -> Exported.DONE
            is SealOutcome.Failed -> failed(key, plaintext(key), "sealing", "${sealed.result}")
        }
    }

    private suspend fun failed(key: String, written: String, step: String, detail: String): Exported {
        log.w("the staged upload failed — not creating", "step" of step, "key" of key, "detail" of detail)
        files.delete(FileArea.SHARED, written)
        staged.release(key)
        return Exported.FAILED
    }

    private fun plaintext(key: String): String = "${staged.path(key)}$PLAINTEXT_SUFFIX"

    private companion object {
        /** An encrypted event's export, beside its staged file only until it is sealed into it. */
        const val PLAINTEXT_SUFFIX = ".plain"
    }
}

/** Whether a resource's bytes reached its staged file. */
enum class Exported { DONE, FAILED }
