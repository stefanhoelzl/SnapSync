package app.snapsync.integration

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.testids.testAssetId

// The fixed values the behaviours share: the default event, the members they play, and the names the operating system
// knows the app's work by — so a step's default and a test's expectation name the same thing.

/** The event a behaviour creates when it does not say otherwise, and the date its photos are taken on. */
object Defaults {
    const val EVENT_NAME = "Anna's Birthday"

    /** The capture date every added photo carries by default — inside the default event window. */
    const val PHOTO_DATE = "2026-06-01T10:00:00Z"

    /**
     * The default event window: 30 days, containing [PHOTO_DATE] and the behaviours' start (`NOW`). Local date-times,
     * as the create form takes them.
     */
    const val WINDOW_START = "2026-05-15T00:00:00"
    const val WINDOW_END = "2026-06-14T00:00:00"
}

/**
 * The fellow member's device most tests play — `foreignDevice(DEV_F, …)`. A device id, as the backend issues every one:
 * the app reads no other, so neither does a fixture.
 */
const val DEV_F = "def0f000-0000-4000-8000-00000000000f"

/** The guest the marketing shots and the full-stack tests play. */
const val GUEST_DEVICE = "9e570000-0000-4000-8000-0000000000a1"

/** The primary upload key of an own photo [assetId] with the default capture name — what the backend lists. */
fun primaryKey(assetId: String, filename: String = "IMG.JPG"): String =
    uploadKey(testAssetId(assetId), ResourceRole.PRIMARY, filename)

/**
 * The OS's upload job for an own photo [assetId]'s primary: named by its destination's last two segments, as the OS
 * keeps nothing else of what a job is for.
 */
fun primaryJob(assetId: String): String = "$assetId/primary"

/** The photo an OS upload job ([primaryJob]) sends. */
fun assetIdOfJob(job: String): AssetId = testAssetId(job.substringBefore('/'))

/** The name the foreground entry begins its background time under (its entry point, `OnForeground`). */
const val FOREGROUND_HOLD = "OnForeground"

/** The name a silent push's delivery begins its background time under. */
const val SILENT_PUSH_HOLD = "OnSilentPush"

/** The heartbeat's task identifier, as the operating system hands it to `os.backgroundTask`. */
const val HEARTBEAT_TASK = "app.snapsync.upload.heartbeat"

/** The idle heartbeat's task identifier — an app refresh on iOS; the same wake as [HEARTBEAT_TASK] to the core. */
const val IDLE_HEARTBEAT_TASK = "app.snapsync.heartbeat.idle"

/** The upload session's identifier, as the operating system hands it to `os.backgroundTransfers`. */
const val UPLOAD_SESSION = "app.snapsync.upload.session"

/** Any transfer channel that is not the upload session's is the download session's. */
const val DOWNLOAD_SESSION = "app.snapsync.download.session"
