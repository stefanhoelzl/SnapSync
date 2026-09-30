package app.snapsync.integration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// The two uploaders, as the JVM host composes them: both run, as on a phone, and take the same photos — so a test about
// one of them switches the other off, and a test of the app's uploader lands its transfers.

/**
 * Only the extension uploads: the app's uploader is switched off (`/device/uploaders?app=off`). Both run on a phone and
 * the JVM host composes both, so they would take the same photos; a test about the OS-driven cycle — its jobs, its
 * retries, what it admits — states that it drives that cycle alone.
 */
suspend fun Rig.extensionUploadsOnly() {
    device("uploaders", "app" to "off")
}

/**
 * The app's uploader has [expected] transfers in flight — it creates them from its own tail (a selection change, a
 * foreground, a completion), on the app's lane, so a caller waits for them rather than for a call to return.
 */
suspend fun Rig.awaitAppUploads(expected: Int): Rig.Jobs =
    eventually(read = { appUploads() }) { it.live.size >= expected }

/** The app uploader's transfers (its background session): the keys still live, and how many were ever created. */
suspend fun Rig.appUploads(): Rig.Jobs = deviceJson("uploads").let { json ->
    Rig.Jobs(
        live = json.getValue("live").jsonArray.map { it.jsonPrimitive.content },
        created = json.getValue("created").jsonPrimitive.content.toInt(),
    )
}

/** The OS lands every live app-uploader transfer (or one), each reported to the app as it ends. */
suspend fun Rig.completeAppUploads(key: String? = null) {
    if (key == null) device("uploads/complete") else device("uploads/complete", "key" to key)
}
