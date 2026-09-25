package app.snapsync.integration

import app.snapsync.control.Reply
import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import app.snapsync.rig.AssetView
import app.snapsync.rig.GalleryView
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

/** The object keys the backend lists for [device] (this device by default). */
suspend fun Rig.objects(device: String? = null): Set<String> =
    deviceJson("backend/objects", *listOfNotNull(device?.let { "device" to it }).toTypedArray())
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
suspend fun Rig.foreignDevice(device: String, vararg assets: String, event: String? = null, filename: String? = null): String {
    val params = listOfNotNull(
        "device" to device,
        "assets" to assets.joinToString(","),
        event?.let { "event" to it },
        filename?.let { "filename" to it },
    ).toTypedArray()
    return deviceJson("foreign-device", *params).getValue("eventId").jsonPrimitive.content
}

/** The download controller reads the union and starts the transfers — the foreground's reconcile. */
suspend fun Rig.reconcile() {
    device("downloads/reconcile")
}

/** The OS delivers every in-flight download; the imports they start are awaited unless [wait] is false. */
suspend fun Rig.stage(wait: Boolean = true) {
    device("downloads/stage", "wait" to wait.toString())
}

/** Download and import every foreign photo the union serves. */
suspend fun Rig.downloadAll() {
    reconcile()
    stage()
}

/** How many photos the library holds, of every origin. */
suspend fun Rig.libraryTotal(): Long = gallery().census.total

/** The foreground's status read, as the operator plays it. */
suspend fun Rig.refresh() {
    device("status/refresh")
}
