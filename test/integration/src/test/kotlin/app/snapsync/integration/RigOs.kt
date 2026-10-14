package app.snapsync.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The calls that play the operating system — its entry points, its expiry, through the typed `os` and `played-os`
// services — and the string record of what it recorded of the app for the systems not yet typed services: the screen,
// the selection observer and the transfer sessions. The completion handlers, the wakes, the background-time holds and
// the push registrations are the `played-os`, `wake`, `background-time` and `push` services' reads.

/** What the operating system recorded of the app (`/device/os-record`), for the systems not yet typed services. */
class OsRecord(private val json: JsonObject) {
    // Each field is read when asked: a host reports only the systems it plays (the app host, only its mocked ones),
    // so a missing key fails the reader that needs it, not every reader of the record.
    private val downloads get() = json.getValue("downloadSession").jsonObject

    /** Whether the app has shown the platform's screen anything — a screen exists only once the host is assembled. */
    val screenShown: Boolean get() = json.getValue("screenShown").jsonPrimitive.boolean

    /** Whether the app has the photo library's selection observer open. */
    val selectionObserved: Boolean get() = json.getValue("selectionObserved").jsonPrimitive.boolean

    /** How many times the app presented the limited-library picker. */
    val pickersShown: Int get() = json.int("pickersShown")

    private val systemUi get() = json.getValue("systemUi").jsonObject

    /** Every text the app handed the share sheet, in order. */
    val shared: List<String> get() = systemUi.getValue("shared").jsonArray.map { it.jsonPrimitive.content }

    /** Every URL the app asked the platform to open, in order. */
    val opened: List<String> get() = systemUi.getValue("opened").jsonArray.map { it.jsonPrimitive.content }

    /** How many times the app opened its Settings page. */
    val settingsOpened: Int get() = systemUi.int("settingsOpened")
    val downloadSessionUp: Boolean get() = downloads.getValue("up").jsonPrimitive.boolean
    val downloadsStarted: Int get() = downloads.int("started")
    val downloadsInFlight: Int get() = downloads.int("inFlight")
    val uploadSessionHandbacks: Int get() = json.int("uploadSessionHandbacks")

    /** Every database any process on the device opened, by name, in order. */
    val databasesOpened: List<String> get() = json.getValue(
        "databasesOpened",
    ).jsonArray.map { it.jsonPrimitive.content }

    /** The files in the download staging directory. */
    val stagedFiles: Int get() = json.int("stagedFiles")

    /** The bytes the app's files take, its logs left out — what the device's storage page shows a person. */
    val appStorageBytes: Long get() = json.getValue("appStorageBytes").jsonPrimitive.long

    private val text = json.toString()

    override fun toString() = text

    private companion object {
        fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    }
}

suspend fun Rig.osRecord(): OsRecord = OsRecord(deviceJson("os-record"))

/**
 * A receipted string entry's answer: what the operating system had recorded, of the systems not yet typed services, at
 * the instant the app released its handler — until those systems' events reach the world stream, where a behaviour
 * reads the same instant as an order of events.
 */
fun osAtRelease(answer: String): OsRecord = OsRecord(
    Json.parseToJsonElement(answer).jsonObject.getValue("osAtRelease").jsonObject,
)

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

/** The heartbeat's task identifier, as the operating system hands it to `onBackgroundTask`. */
const val HEARTBEAT_TASK = "app.snapsync.upload.heartbeat"

/** The idle heartbeat's task identifier — an app refresh on iOS; the same wake as [HEARTBEAT_TASK] to the core. */
const val IDLE_HEARTBEAT_TASK = "app.snapsync.heartbeat.idle"

/** The upload session's identifier, as the operating system hands it to `onBackgroundTransfers`. */
const val UPLOAD_SESSION = "app.snapsync.upload.session"

/** Any transfer channel that is not the upload session's is the download session's. */
const val DOWNLOAD_SESSION = "app.snapsync.download.session"

/** Wait until the operating system's record satisfies [until]; answers it. */
suspend fun Rig.awaitOs(until: (OsRecord) -> Boolean): OsRecord = eventually(read = { osRecord() }, until = until)

/**
 * Wait until a download the reconcile started is in flight. [foreground] can return before the entry has even taken its
 * hold, so a test that stages what [reconcile] queued waits for the queue first — or it stages nothing.
 */
suspend fun Rig.awaitDownloadQueued() {
    awaitOs { it.downloadsInFlight > 0 }
}
