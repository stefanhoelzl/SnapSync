package app.snapsync.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The protocol calls that move photos — uploads, the backend's reads, other members and downloads.

/** One upload cycle, as the OS-driven extension runs it. Answers the cycle's result (`completed`, `processing`, …). */
suspend fun Rig.cycle(): String =
    Json.parseToJsonElement(os("photokit-ext", "processRawValue")).jsonObject.getValue("result").jsonPrimitive.content

/** The operating system's upload jobs: the keys still live, and how many were ever created. */
suspend fun Rig.jobs(): Rig.Jobs = deviceJson("jobs").let { json ->
    Rig.Jobs(
        live = json.getValue("live").jsonArray.map { it.jsonPrimitive.content },
        created = json.getValue("created").jsonPrimitive.content.toInt(),
    )
}

/** The OS finishes every live job (or one), as a transfer landing. */
suspend fun Rig.completeJobs(key: String? = null) {
    if (key == null) device("jobs/complete") else device("jobs/complete", "key" to key)
}

/** Upload everything the cycle picks up: a cycle, every job landing, and a cycle to settle and publish. */
suspend fun Rig.uploadAll() {
    cycle()
    completeJobs()
    cycle()
}

/** The object keys the backend lists for [device] (this device by default) in [event] (the joined one by default). */
suspend fun Rig.objects(device: String? = null, event: String? = null): Set<String> =
    deviceJson(
        "backend/objects",
        *listOfNotNull(device?.let { "device" to it }, event?.let { "event" to it }).toTypedArray(),
    )
        .getValue("objects").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }

/** The asset ids the event's union serves, with the roles each is served with. */
suspend fun Rig.union(event: String? = null): Map<String, Set<String>> =
    deviceJson("backend/union", *listOfNotNull(event?.let { "event" to it }).toTypedArray())
        .getValue("assets").jsonArray.associate { a ->
            val o = a.jsonObject
            o.getValue("asset").jsonPrimitive.content to
                o.getValue("roles").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
        }

/** The asset ids — and each one's declared roles — the backend holds in [device]'s manifest for [event]; null when none. */
suspend fun Rig.manifest(event: String? = null, device: String? = null): Map<String, Set<String>>? {
    val params = listOfNotNull(event?.let { "event" to it }, device?.let { "device" to it }).toTypedArray()
    val manifest = deviceJson("backend/manifest", *params)["manifest"]
    if (manifest == null || manifest is JsonNull) return null
    return manifest.jsonObject.getValue("assets").jsonArray.associate { a ->
        val o = a.jsonObject
        o.getValue("assetId").jsonPrimitive.content to
            o.getValue("resources").jsonArray.mapTo(mutableSetOf()) { it.jsonObject.getValue("role").jsonPrimitive.content }
    }
}

/** A fellow member of [event] (the joined one by default) whose [assets] are complete on the backend. */
suspend fun Rig.foreignDevice(
    device: String,
    vararg assets: String,
    event: String? = null,
    filename: String? = null,
): String {
    val params = listOfNotNull(
        "device" to device,
        "assets" to assets.joinToString(","),
        event?.let { "event" to it },
        filename?.let { "filename" to it },
    ).toTypedArray()
    return deviceJson("foreign-device", *params).getValue("eventId").jsonPrimitive.content
}

/**
 * The foreground's download reconcile — the union read, and the transfers it starts. Only the foreground entry does
 * this, so that is what this plays; everything else the foreground does comes with it.
 */
suspend fun Rig.reconcile() = foreground()

/**
 * The operating system finishes every in-flight download — healthy, or answered [status] with an error body — and,
 * unless [wait] is false, the app's answer settles: each transfer staged and the import its tail runs done. Settled is
 * read off what the outside can see — no tail holding background time, the screen's download row, the staging
 * directory and the library — unchanged across a few reads.
 */
suspend fun Rig.stage(wait: Boolean = true, status: Int? = null) {
    val params = listOfNotNull(
        status?.let { "status" to it.toString() },
        status?.let { "received" to "137" },
    ).toTypedArray()
    device("downloads/stage", *params)
    if (wait) settleDownloads()
}

/** Wait until the downloads' outside-visible effects stop moving (see [stage]). */
suspend fun Rig.settleDownloads() {
    var last: String? = null
    var steady = 0
    eventually(read = {
        val os = osRecord()
        val now = listOf(
            os.backgroundTimeHolds.filter { it.startsWith("tail(") }.toString(),
            state().download.toString(),
            deviceJson("staging").toString(),
            gallery().census.total.toString(),
        ).joinToString("|")
        steady = if (now == last && os.backgroundTimeHolds.none { it.startsWith("tail(") }) steady + 1 else 0
        last = now
        steady
    }) { it >= SETTLED_READS }
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

/** Every album this app created, with the assets placed in it. */
suspend fun Rig.albums(): List<Album> =
    deviceJson("album/contents").getValue("albums").jsonArray.map { a ->
        val o = a.jsonObject
        Album(
            o.getValue("name").jsonPrimitive.content,
            o.getValue("assets").jsonArray.map { it.jsonPrimitive.content },
            o.getValue("id").jsonPrimitive.content,
        )
    }

/** How many push registrations the backend stored for this device — the config is last-write-wins, the count is not. */
suspend fun Rig.registrations(): Int =
    deviceJson("backend/device-config").getValue("writes").jsonPrimitive.content.toInt()

/** The token and environment the backend holds for this device, or null when none was registered. */
suspend fun Rig.deviceConfig(): Pair<String, String>? {
    val config = deviceJson("backend/device-config")
    val token = config["token"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: return null
    return token to config.getValue("env").jsonPrimitive.content
}

/** The pushes the backend sent, as (event, device, token). */
suspend fun Rig.pushesSent(): List<Triple<String, String, String>> =
    deviceJson("backend/pushes").getValue("pushes").jsonArray.map { p ->
        val o = p.jsonObject
        Triple(
            o.getValue("event").jsonPrimitive.content,
            o.getValue("device").jsonPrimitive.content,
            o.getValue("token").jsonPrimitive.content,
        )
    }
