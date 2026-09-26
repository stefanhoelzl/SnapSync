package app.snapsync.services.staging

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import co.touchlab.kermit.Logger

/** The download staging directory in the shared area — runtime identity: devices hold files under it. */
const val DOWNLOAD_STAGING_DIR: String = "download-staging"

/**
 * The download staging area (capability `receiving-photos`): [StagingService] over the shared area of [Files], with
 * every staged path **relative** to that area.
 *
 * Relative because an absolute container path is not the device's to keep: the App-Group container's platform
 * path can change under a restore or a migration, and a row that stored the old one would point at nothing — its
 * bytes present, and read as consumed. The download store's schema 4 migration rewrote every absolute staged path
 * this adapter's predecessor had stored.
 *
 * [release] is best-effort (the port's contract): a failure is logged and the file is collected later.
 * [allPresent] answers the fact of existence only; a lookup it could not make is **not** "missing" — that would
 * read as evidence of a submitted import — so it answers `true` there and logs why (the adjudicator then declines
 * to settle on it, the direction that never loses a photo).
 */
class StagingService(
    private val files: Files,
    private val log: Logger = Logger.withTag("stagedBytes"),
) {

    /**
     * The directory staged bytes live under — **relative to the shared area**, like every path this port takes
     * (`docs/architecture.md`, "Paths are area-relative"). A staged path is stored in the download store as it is
     * built from this root, so a device whose container moved (a restore) still finds its staged files.
     *
     * One owner decides where staging lives *and* what may be reclaimed from it, so the two can never name
     * different directories. Not suspend, and no I/O: it names a directory, it does not look one up.
     */
    fun stagingRoot(): String = DOWNLOAD_STAGING_DIR

    /**
     * The platform path of the staged file at [path] — for the one platform API that must be handed a file (the
     * download transport's destination, the photo-library import). Nothing is created or read. **Throws** when
     * the shared area cannot be reached, for the reason [None]'s [stagingRoot] does: a file staged, or imported,
     * from a directory nobody chose is a photo lost without a trace.
     */
    fun locate(path: String): String = when (val located = files.locate(FileArea.SHARED, path)) {
        is FileResult.Ok -> located.value
        else -> error("the shared area cannot hold staged downloads ($located)")
    }

    /**
     * Take over a finished download's bytes the platform left at [tempPath], moving them to [path] (relative) —
     * replacing whatever was there: a re-download is last-write-wins. **Not suspending**, because its caller is the
     * download's finish callback, after which the platform deletes [tempPath]. `false` when the bytes could not be
     * kept; they are then downloaded again by a later reconcile, never lost.
     */
    fun stage(tempPath: String, path: String): Boolean =
        when (val adopted = files.adopt(tempPath, FileArea.SHARED, path)) {
            is FileResult.Ok -> true
            else -> false.also { log.w { "stage: $path was not kept ($adopted) — it is downloaded again later" } }
        }

    /** Delete the files at [paths] (relative). Missing files are not an error; the operation is idempotent. */
    suspend fun release(paths: List<String>) {
        paths.forEach { path ->
            when (val deleted = files.delete(FileArea.SHARED, path)) {
                is FileResult.Ok, FileResult.NotFound -> Unit
                else -> log.w { "release: $path stays on disk ($deleted)" }
            }
        }
    }

    /**
     * Are all of [paths] still on disk?
     *
     * The **fact**, and only the fact. This reports file existence; it does not report consumption,
     * ingestion, or any reading of *why* a file is absent. That inference belongs to the download
     * feature, which owns the knowledge that makes it sound: on a row carrying a created-asset marker
     * nothing else can have removed the file, because [release] runs only after a confirming write or
     * immediately before dropping a row, and a marker-carrying row is never dropped. Putting the
     * inference behind this port would move a load-bearing decision to where `commonTest` cannot reach
     * it.
     *
     * It lives here rather than on a port of its own for the same reason [stagingRoot] does: one owner
     * decides where staging lives, what may be reclaimed from it, and what is still in it, so the three
     * can never disagree about a directory.
     *
     * Why the adjudicator needs it (capability `receiving-photos`): the photo library answers about
     * **committed** state, so it answers *absent* about an asset whose creating transaction is still
     * open — and a commit outlives the process that opened it. The staged bytes are the second,
     * independent oracle. The library takes a resource's file when it ingests it, which it does only as
     * part of creating an asset, so their absence is positive evidence that a creation was submitted at
     * a moment when the library's own answer cannot be acted on.
     *
     * **Any** missing member answers `false`: an asset's resources are ingested individually, so one
     * missing file is as much evidence of a submitted creation as all of them. An **empty** [paths]
     * answers `true`, carrying no evidence either way — the caller distinguishes that case and declines
     * to act on it rather than reading it as "nothing was consumed".
     */
    suspend fun allPresent(paths: List<String>): Boolean = paths.all { path ->
        when (val exists = files.exists(FileArea.SHARED, path)) {
            is FileResult.Ok -> exists.value
            else -> true.also { log.w { "allPresent: could not look for $path ($exists) — not evidence of an import" } }
        }
    }
}
