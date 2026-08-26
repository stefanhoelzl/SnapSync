@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.rig

import app.snapsync.gallery.photoKitResourceRole
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.roleFromUploadKey
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.uploadTaskWithRequest
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import kotlin.coroutines.resume

/**
 * `POST /device/upload-jobs/perform` — **the OS moving the bytes**, on a host where it will not.
 *
 * The substituted upload-job queue accepts a job and reports what the caller says became of it; this verb
 * is the other half — actually performing one, for real, against the destination and headers the cycle
 * composed. Together they are the operator playing the system's own upload scheduler.
 *
 * **Why the transfer is real rather than declared.** Declaring a job succeeded without moving bytes is what
 * `:test:world` already does, in memory, faster, and on JVM too — it would add nothing here. A real
 * transfer is the entire marginal value of this host: it is the only thing anywhere that demonstrates a URL
 * the edge-URL builder composed is one the backend accepts, carrying the bytes PhotoKit yields, under a
 * real attestation token. That builder is pinned only by `commonMain` tests on string composition.
 *
 * Failure, by contrast, is worth forging: `?fail=…` skips the PUT entirely so a retry chain is deterministic
 * without having to break the backend to get one.
 */

private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

@Serializable
private class PerformRequest(
    /** The ledger key, exactly as `created[].key` reported it. */
    val key: String,
    /** The destination URL, verbatim from `created[].destination`. */
    val destination: String,
    /** The headers, verbatim from `created[].headers`. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The verb.
 *
 * Refuses rather than defaults on every malformed input, for the reason the wipe command already states: a
 * value that silently became something else produces a run the caller did not ask for, and they will read
 * its result as the answer to the scenario they thought they wrote.
 */
fun performUploadJobCommand(log: Logger = Logger.withTag("rig")): RigCommand = RigCommand { params, body ->
    val request = runCatching { body?.let { json.decodeFromString<PerformRequest>(it) } }
        .getOrNull()
        ?: return@RigCommand CommandResult.badRequest(
            "a JSON body is required: {\\\"key\\\":\\\"…\\\",\\\"destination\\\":\\\"…\\\",\\\"headers\\\":{…}} " +
                "— take them verbatim from the created[] entry this job came from",
        )

    // A forged failure moves no bytes at all. Deliberately not "PUT and then lie about the result": the
    // point of forging is to reach the engine's retry chain without a backend that has to misbehave.
    params["fail"]?.let { reason ->
        log.i { "perform ${request.key}: forced failure ($reason) — no request was sent" }
        return@RigCommand CommandResult.ok(
            """{"key":${quote(request.key)},"performed":false,"forcedFailure":${quote(reason)},""" +
                """"note":"no HTTP request was sent; present this job back as failed to drive the retry chain"}""",
        )
    }

    val resource = resourceForKey(request.key)
        ?: return@RigCommand CommandResult.badRequest(
            "no live PHAssetResource for key '${request.key}' — the asset or its resource is gone, so the " +
                "OS could not have uploaded it either",
        )
    val staged = stageResource(resource, request.key)
        ?: return@RigCommand CommandResult.badRequest(
            "could not read the bytes of '${request.key}' from PhotoKit",
        )
    val outcome = put(request, staged, log)
    NSFileManager.defaultManager.removeItemAtURL(staged, error = null)
    CommandResult.ok(outcome)
}

/**
 * Perform the PUT with the request's own URL and headers, **verbatim**.
 *
 * Verbatim is the whole point: any normalisation here would be this verb testing its own idea of the
 * request rather than the one the cycle composed, which is the single thing this host can prove and
 * nothing else can.
 *
 * A **default** `NSURLSession`, necessarily — a background session transfers nothing on a simulator
 * (`nsurlsessiond` rejects the client's bundle identifier as `(null)`). So these transfers die with the
 * process, where the OS's own genuinely survive. That is a divergence from the system being impersonated,
 * and it is stated in the `ios-simulator` skill rather than papered over.
 */
private suspend fun put(request: PerformRequest, file: NSURL, log: Logger): String {
    val url = NSURL.URLWithString(request.destination)
        ?: return """{"key":${quote(request.key)},"performed":false,""" +
            """"error":"the destination is not a URL this platform accepts"}"""
    val http = NSMutableURLRequest.requestWithURL(url)
    http.setHTTPMethod("PUT")
    request.headers.forEach { (name, value) -> http.setValue(value, forHTTPHeaderField = name) }

    val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration)
    val result: Pair<NSHTTPURLResponse?, NSError?> = suspendCancellableCoroutine { cont ->
        val task = session.uploadTaskWithRequest(http, fromFile = file) { _, response, error ->
            cont.resume((response as? NSHTTPURLResponse) to error)
        }
        task.resume()
    }
    val (response, error) = result
    val status = response?.statusCode?.toInt()
    log.i { "perform ${request.key}: status=${status ?: "none"} error=${error?.localizedDescription ?: "none"}" }
    return """{"key":${quote(request.key)},"performed":true,"status":${status ?: "null"},""" +
        """"destination":${quote(request.destination)},""" +
        """"error":${error?.localizedDescription?.let(::quote) ?: "null"},""" +
        """"succeeded":${status != null && status in 200..299}}"""
}

/**
 * Stage the resource's bytes to a file, the way the app-driven tier already does on this very host.
 *
 * `writeDataForAssetResource(_:toFile:)` rather than an in-memory read: it is the technique
 * `IosUrlSessionUploadPlatform.stageResource` uses and the one measured to work on a simulator. The
 * *adapter* is deliberately not reused — that class IS the app-driven tier's mechanism, and routing the
 * OS-driven tier's test transport through it would launder the thing under test through its alternative.
 */
private suspend fun stageResource(resource: PHAssetResource, key: String): NSURL? {
    val dir = NSURL.fileURLWithPath(NSTemporaryDirectory())
    val file = dir.URLByAppendingPathComponent("rig-perform-$key") ?: return null
    NSFileManager.defaultManager.removeItemAtURL(file, error = null)
    val error: NSError? = suspendCancellableCoroutine { cont ->
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(
            resource,
            toFile = file,
            options = null,
        ) { err -> cont.resume(err) }
    }
    return if (error == null) file else null
}

/**
 * Recover the live `PHAssetResource` a ledger key names — the same recovery the substituted queue does, and
 * for the same reason: a job reaches this verb as a plain key, because that is all the caller holds.
 */
private fun resourceForKey(key: String): PHAssetResource? {
    val localId = denormalizeAssetId(assetIdFromUploadKey(key))
    val role = roleFromUploadKey(key)
    val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localId), null).firstObject() as? PHAsset
        ?: return null
    return PHAssetResource.assetResourcesForAsset(asset)
        .filterIsInstance<PHAssetResource>()
        .firstOrNull { photoKitResourceRole(it.type) == role }
}

private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
