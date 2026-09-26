package app.snapsync.services.staging

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.StagedBytes
import co.touchlab.kermit.Logger

/** The download staging directory in the shared area — runtime identity: devices hold files under it. */
const val DOWNLOAD_STAGING_DIR: String = "download-staging"

/**
 * The download staging area (capability `receiving-photos`): [StagedBytes] over the shared area of [Files], with
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
) : StagedBytes {

    override fun stagingRoot(): String = DOWNLOAD_STAGING_DIR

    override fun locate(path: String): String = when (val located = files.locate(FileArea.SHARED, path)) {
        is FileResult.Ok -> located.value
        else -> error("the shared area cannot hold staged downloads ($located)")
    }

    override fun stage(tempPath: String, path: String): Boolean =
        when (val adopted = files.adopt(tempPath, FileArea.SHARED, path)) {
            is FileResult.Ok -> true
            else -> false.also { log.w { "stage: $path was not kept ($adopted) — it is downloaded again later" } }
        }

    override suspend fun release(paths: List<String>) {
        paths.forEach { path ->
            when (val deleted = files.delete(FileArea.SHARED, path)) {
                is FileResult.Ok, FileResult.NotFound -> Unit
                else -> log.w { "release: $path stays on disk ($deleted)" }
            }
        }
    }

    override suspend fun allPresent(paths: List<String>): Boolean = paths.all { path ->
        when (val exists = files.exists(FileArea.SHARED, path)) {
            is FileResult.Ok -> exists.value
            else -> true.also { log.w { "allPresent: could not look for $path ($exists) — not evidence of an import" } }
        }
    }
}
