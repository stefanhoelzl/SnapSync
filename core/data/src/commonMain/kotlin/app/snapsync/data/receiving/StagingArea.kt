package app.snapsync.data.receiving

import app.snapsync.domain.receiving.StagedEvidence
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/**
 * The download staging area: the shared area of [Files], every path in it a [StagedPath] — relative to the area, so
 * a device whose container moved (a restore) still finds its staged files. One owner decides where staging lives,
 * what may be reclaimed from it and what is still in it, so the three can never name different directories.
 */
class StagingArea(private val files: Files) {

    /**
     * Take over a finished download's bytes the platform left at [temp], moving them to [at] — replacing whatever was
     * there: a re-download is last-write-wins. Not suspending: its caller is the download's finish delivery, after
     * which the platform deletes [temp]. Bytes not kept are downloaded again by a later read, never lost.
     */
    fun land(temp: String, at: StagedPath): Landed = when (val adopted = files.adopt(temp, FileArea.SHARED, at.value)) {
        is FileResult.Ok -> Landed.Kept
        is FileFailure -> Landed.NotKept("$adopted")
    }

    /** The platform path of the staged file [at], for the one platform call that must be handed a file. */
    fun locate(at: StagedPath): Located = when (val located = files.locate(FileArea.SHARED, at.value)) {
        is FileResult.Ok -> Located.At(located.value)
        FileResult.AreaUnavailable -> Located.Unreachable("$located")
    }

    /** Delete the staged files at [paths]; a missing one is no error. A file left behind is swept later. */
    suspend fun release(paths: Collection<StagedPath>) {
        paths.forEach { released(it, files.delete(FileArea.SHARED, it.value)) }
    }

    /**
     * Every staged file on disk — or that the directory could not be read, which is never "nothing staged": a sweep
     * that read a failed listing as empty would find nothing to keep.
     */
    fun listed(): Listed = when (val listed = files.list(FileArea.SHARED, StagedPath.ROOT)) {
        is FileResult.Ok -> Listed.Files(listed.value.map(::StagedPath))
        is FileFailure -> Listed.Unreadable("$listed")
    }

    /**
     * What [paths] — one row's recorded staged files — say of an import that never reported: the FACT of their
     * existence, read as evidence by the domain. A file this could not look for counts as present: an inability to look
     * is no evidence that the library took it.
     */
    suspend fun evidence(paths: List<StagedPath>): StagedEvidence =
        StagedEvidence.of(recorded = paths.size, missing = paths.count { !exists(it) })

    private suspend fun exists(path: StagedPath): Boolean = when (
        val exists = files.exists(
            FileArea.SHARED,
            path.value,
        )
    ) {
        is FileResult.Ok -> exists.value
        is FileFailure -> true.also {
            log.w(
                "could not look for a staged file",
                "path" of path.value,
            )
        }
    }

    private suspend fun released(path: StagedPath, deleted: FileResult<Unit>) = when (deleted) {
        is FileResult.Ok, FileResult.NotFound -> Unit
        is FileFailure -> log.w(
            "a staged file stays on disk",
            "path" of path.value,
            "why" of "$deleted",
        )
    }
}

private val log = Log("StagingArea")

/** Whether a finished download's bytes were kept. */
sealed interface Landed {
    data object Kept : Landed

    class NotKept(val detail: String) : Landed
}

/** Where a staged file is on this device, or that the shared area cannot be reached. */
sealed interface Located {
    class At(val platformPath: String) : Located

    class Unreachable(val detail: String) : Located
}

/** The staged files on disk, or that the directory could not be read. */
sealed interface Listed {
    class Files(val paths: List<StagedPath>) : Listed

    class Unreadable(val detail: String) : Listed
}
