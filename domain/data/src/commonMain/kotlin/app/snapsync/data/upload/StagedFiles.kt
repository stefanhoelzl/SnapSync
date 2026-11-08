package app.snapsync.data.upload

import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/** Where a file uploader's exported bytes wait, in the shared area — runtime identity: devices hold files under it. */
const val UPLOAD_STAGING_DIR: String = "upload-staging"

/**
 * **The staged files of the app's own session uploads** — a resource's exported (and, for an encrypted event, sealed)
 * bytes at a path that is a pure function of its key, so no process has to remember where it is; beside each, once
 * hashed, its digest ([StagedDigests]), which is what lets a FAILED upload send the very same bytes again from where
 * the backend says they stopped (`docs/architecture.md`, "Resumable uploads").
 */
class StagedFiles(private val files: Files) {
    private val log = Log("StagedFiles")

    /** The staged file of the key spelled [key], relative to the shared area. */
    fun path(key: String): String = "$UPLOAD_STAGING_DIR/$key"

    /** The digest beside [key]'s staged file. */
    fun digestPath(key: String): String = "${path(key)}$DIGEST_SUFFIX"

    /** The key a staged file or digest at [path] belongs to. */
    fun keyOf(path: String): String = path.removePrefix("$UPLOAD_STAGING_DIR/").removeSuffix(DIGEST_SUFFIX)

    /** Where [path] is in the shared area, or that the area cannot hold it now. */
    fun located(path: String): Placement = when (val at = files.locate(FileArea.SHARED, path)) {
        is FileResult.Ok -> Placement.At(at.value)
        FileResult.AreaUnavailable -> Placement.Unplaced
    }

    /** Whether [key]'s staged file is there. */
    fun staged(key: String): Boolean = when (val there = files.exists(FileArea.SHARED, path(key))) {
        is FileResult.Ok -> there.value
        is FileFailure -> false
    }

    /** Delete [key]'s staged file and its digest. */
    fun release(key: String) {
        delete(path(key))
        delete(digestPath(key))
    }

    /** Delete the staged file or digest at [path]; one the area keeps is logged, and the next sweep tries again. */
    fun delete(path: String) = when (val deleted = files.delete(FileArea.SHARED, path)) {
        is FileResult.Ok, FileResult.NotFound -> Unit
        is FileFailure -> log.unlabelled.w("a staged upload file stays on disk", "path" of path, "result" of deleted)
    }

    private companion object {
        const val DIGEST_SUFFIX = ".sha256"
    }
}

/** Where a staged file is in the shared area. */
sealed interface Placement {
    /** At the OS path [path]. */
    class At(val path: String) : Placement

    /** The shared area cannot hold it now (a build without the App-Group entitlement). */
    data object Unplaced : Placement
}
