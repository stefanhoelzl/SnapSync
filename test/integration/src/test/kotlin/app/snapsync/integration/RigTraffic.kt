package app.snapsync.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The protocol calls that move photos — uploads, the backend's reads, other members and downloads.

/** One upload cycle, as the OS-driven extension runs it. Answers the cycle's result (`completed`, `processing`, …). */
suspend fun Rig.cycle(): String =
    Json.parseToJsonElement(os("photokit-ext", "processRawValue")).jsonObject.getValue("result").jsonPrimitive.content

/** The operating system's upload jobs: the ones still live (`<assetId>/<role>`), and how many were ever created. */
suspend fun Rig.jobs(): Rig.Jobs = uploadQueue.queue().let { Rig.Jobs(live = it.live, created = it.created.size) }

/** The OS finishes every live job (or the one [job] names, `<assetId>/<role>`), as a transfer landing. */
suspend fun Rig.completeJobs(job: String? = null) {
    uploadQueue.completeJob(job)
}

/** Upload everything the cycle picks up: a cycle, every job landing, and a cycle to settle and publish. */
suspend fun Rig.uploadAll() {
    cycle()
    completeJobs()
    cycle()
}

/** The object keys the backend lists for [device] (this device by default) in [event] (the joined one by default). */
suspend fun Rig.objects(device: String? = null, event: String? = null): Set<String> =
    publicBackend.objects(device, event).toSet()

/** The asset ids the event's union serves, with the roles each is served with. */
suspend fun Rig.union(event: String? = null): Map<String, Set<String>> =
    publicBackend.union(event).associate { it.asset to it.roles.toSet() }

/** The asset ids — and each one's declared roles — the backend holds in [device]'s manifest for [event]; null when none. */
suspend fun Rig.manifest(event: String? = null, device: String? = null): Map<String, Set<String>>? =
    backend.membership(event, device).manifest?.roles

/**
 * A fellow member of [event] (the joined one by default) whose [assets] are complete on the backend — but [uploading]'s,
 * declared and still on its phone; [final] declares its share settled, as a device does once the event's range has
 * ended.
 */
suspend fun Rig.foreignDevice(
    device: String,
    vararg assets: String,
    event: String? = null,
    filename: String? = null,
    final: Boolean = false,
    uploading: List<String> = emptyList(),
): String = publicBackend.foreignDevice(
    device,
    assets.toList(),
    event = event,
    filename = filename,
    final = final,
    uploading = uploading,
)

/**
 * The foreground's download reconcile — the union read, and the transfers it starts. Only the foreground entry does
 * this, so that is what this plays; everything else the foreground does comes with it.
 */
suspend fun Rig.reconcile() = foreground()

/**
 * The operating system finishes every in-flight download — healthy, or answered [status] with an error body — and,
 * unless [wait] is false, the app's answer settles: each transfer staged and the import its staging starts done. Settled is
 * read off what the outside can see — no run holding background time, the screen's download row, the staging
 * directory and the library — unchanged across a few reads.
 */
suspend fun Rig.stage(wait: Boolean = true, status: Int? = null) {
    downloadSession.finish(status = status, received = status?.let { ERROR_BODY_BYTES })
    if (wait) settleDownloads()
}

/** Wait until the downloads' outside-visible effects stop moving (see [stage]). */
suspend fun Rig.settleDownloads() {
    var last: String? = null
    var steady = 0
    eventually(read = {
        val os = osRecord()
        val now = listOf(
            os.backgroundTimeHolds.toString(),
            state().download.toString(),
            os.appStorageBytes.toString(),
            gallery().census.total.toString(),
        ).joinToString("|")
        steady = if (now == last && os.backgroundTimeHolds.isEmpty()) steady + 1 else 0
        last = now
        steady to now
    }) { (reads, _) -> reads >= SETTLED_READS }
}

/** Download and import every foreign photo the union serves. */
suspend fun Rig.downloadAll() {
    reconcile()
    stage()
}

/** How many photos the library holds, of every origin. */
suspend fun Rig.libraryTotal(): Long = gallery().census.total

/** The status read the foreground makes, and everything else the foreground does. */
suspend fun Rig.refresh() = foreground()

/** How many unchanged reads, [Rig.eventually]'s poll apart, count as settled. */
private const val SETTLED_READS = 3

/** An album this app created, and the assets in it, in order. [id] is the library's own, for a person's levers. */
class Album(val name: String, val assets: List<String>, val id: String = "") {
    override fun toString() = "Album($name, $assets)"
}

/** Every album this app created, with the photos it holds now. */
suspend fun Rig.albums(): List<Album> = library.albums().map { Album(it.title, it.photos, it.id) }

/** How many push registrations the backend stored for this device — the config is last-write-wins, the count is not. */
suspend fun Rig.registrations(): Int =
    backend.deviceConfig().writes

/** The token and environment the backend holds for this device, or null when none was registered. */
suspend fun Rig.deviceConfig(): Pair<String, String>? {
    val config = backend.deviceConfig()
    return config.token?.let { it to checkNotNull(config.env) }
}

/** The pushes the backend sent, as (event, device, token). */
suspend fun Rig.pushesSent(): List<Triple<String, String, String>> =
    backend.pushes().map { Triple(it.eventId, it.deviceId, it.token) }

/** What an error answer's body brings: a few bytes of text, not the photo. */
private const val ERROR_BODY_BYTES = 137L
