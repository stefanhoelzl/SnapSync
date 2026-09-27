package app.snapsync.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The protocol calls that play the operating system — its entry points, its expiry — and read back what it recorded
// of the app: the completion handlers it handed over and got back, the screen, the selection observer, the heartbeat
// requests, the background-time holds, the push registrations and the transfer sessions.

/** What the operating system recorded of the app (`/device/os-record`). */
class OsRecord(json: JsonObject) {
    private val completions = json.getValue("completions").jsonObject
    private val downloads = json.getValue("downloadSession").jsonObject

    /** Completion handlers the OS handed the app, released by it, released a second time, and still held. */
    val handed: Int = completions.int("handed")
    val released: Int = completions.int("released")
    val releasedAgain: Int = completions.int("releasedAgain")
    val held: Int = completions.int("held")

    /** Whether the app has shown the platform's screen anything — a screen exists only once the host is assembled. */
    val screenShown: Boolean = json.getValue("screenShown").jsonPrimitive.boolean

    /** Whether the app has the photo library's selection observer open. */
    val selectionObserved: Boolean = json.getValue("selectionObserved").jsonPrimitive.boolean
    val heartbeatsScheduled: Int = json.int("heartbeatsScheduled")

    /** The background-time holds outstanding, by the name the app began each under. */
    val backgroundTimeHolds: List<String> = json.getValue("backgroundTimeHolds").jsonArray.map { it.jsonPrimitive.content }
    val pushRegistrations: Int = json.int("pushRegistrations")
    val downloadSessionUp: Boolean = downloads.getValue("up").jsonPrimitive.boolean
    val downloadsStarted: Int = downloads.int("started")
    val downloadsInFlight: Int = downloads.int("inFlight")
    val uploadSessionHandbacks: Int = json.int("uploadSessionHandbacks")

    private val text = json.toString()

    override fun toString() = text

    private companion object {
        fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    }
}

suspend fun Rig.osRecord(): OsRecord = OsRecord(deviceJson("os-record"))

/** A receipted entry's answer: what the operating system had recorded at the instant the app released its handler. */
fun osAtRelease(answer: String): OsRecord = OsRecord(Json.parseToJsonElement(answer).jsonObject.getValue("osAtRelease").jsonObject)

/**
 * The person brings the app to the foreground, and the foreground's work finishes: its own work — the membership
 * re-read, the status read, the download reconcile — and its tail, awaited through the background time it holds
 * (the `onForeground` hold ends with its tail). [awaited] `false` returns once the entry is delivered.
 */
suspend fun Rig.foreground(awaited: Boolean = true) {
    val before = osRecord().backgroundTimeHolds.count { it == FOREGROUND_HOLD }
    os("app", "onForeground")
    if (awaited) {
        eventually(read = { osRecord() }) { r -> r.backgroundTimeHolds.count { it == FOREGROUND_HOLD } <= before }
    }
}

/** The operating system says time is up — for every handler it holds and every background-time hold. */
suspend fun Rig.expire() {
    os("app", "onExpiry")
}

/** The next completion handler the operating system hands over arrives with its time already up. */
suspend fun Rig.expireNext() {
    os("app", "onExpiry", "next")
}

/** The label the foreground entry begins its background time under (`EntryHandlers.lifecycleHandlers`). */
private const val FOREGROUND_HOLD = "onForeground"

/** The heartbeat's task identifier, as the operating system hands it to `onBackgroundTask`. */
const val HEARTBEAT_TASK = "app.snapsync.upload.heartbeat"

/** The upload session's identifier, as the operating system hands it to `onBackgroundTransfers`. */
const val UPLOAD_SESSION = "app.snapsync.upload.session"

/** Any transfer channel that is not the upload session's is the download session's. */
const val DOWNLOAD_SESSION = "app.snapsync.download.session"
