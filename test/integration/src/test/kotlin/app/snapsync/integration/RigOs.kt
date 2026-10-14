package app.snapsync.integration

// The calls that play the operating system — its entry points, its expiry — through the typed `os` and `played-os`
// services, and the waits on what it holds of the app. The completion handlers, the wakes, the background-time holds,
// the push registrations and the transfer sessions are the `played-os`, `wake`, `background-time`, `push`,
// `upload-session` and `download-session` services' reads; the staged files and the databases opened, `files`' and
// `databases'`.

/**
 * The person brings the app to the foreground, and the foreground's work finishes: its own work — the membership
 * re-read, the status read, the download reconcile — and its tail, awaited through the background time it holds
 * (the `onForeground` hold ends with its tail, and is begun before the entry is answered). Awaited until no
 * foreground holds any: counting against the holds before would let an earlier foreground still running stand in
 * for this one, and return while this one's reconcile has not run. [awaited] `false` returns once the entry is
 * delivered.
 */
suspend fun Rig.foreground(awaited: Boolean = true) {
    os.foreground()
    if (awaited) eventually(read = { backgroundTime.holds() }) { FOREGROUND_HOLD !in it }
}

/** Wait until the app holds no background time. */
suspend fun Rig.awaitNoHolds() {
    eventually(read = { backgroundTime.holds() }) { it.isEmpty() }
}

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

/** Wait until the download staging directory holds [count] files. */
suspend fun Rig.awaitStaged(count: Int) {
    eventually(read = { files.stagedFiles() }) { it == count }
}

/**
 * Wait until the operating system's download session is up — a delivery for its events brings it up (`os` service's
 * `backgroundTransfers`).
 */
suspend fun Rig.awaitDownloadSessionUp() {
    eventually(read = { downloadSession.downloads().up }) { it }
}

/**
 * Wait until a download the reconcile started is in flight. [foreground] can return before the entry has even taken its
 * hold, so a test that stages what [reconcile] queued waits for the queue first — or it stages nothing.
 */
suspend fun Rig.awaitDownloadQueued() {
    eventually(read = { downloadSession.downloads().inFlight }) { it.isNotEmpty() }
}
