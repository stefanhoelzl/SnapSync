package app.snapsync.rig

import app.snapsync.ios.upload.CreatedUploadJob
import app.snapsync.ios.upload.FinishedUploadJob
import app.snapsync.ios.upload.PhotoKitJobState
import app.snapsync.ios.upload.SimulatorJobAction
import app.snapsync.ios.registry.SimulatorExtensionRecord
import app.snapsync.ios.upload.SimulatorUploadJobs
import app.snapsync.model.UploadError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The simulator target: **the caller plays the OS**, so this invocation's job sets arrive in the request
 * body and the jobs the cycle creates leave in the response.
 *
 * The queue holds nothing between invocations, and that is the point rather than a simplification. The real
 * queue's durability lives outside the app process — `photolibraryd` holds it, and `process()` is handed the
 * current sets and hands back new ones — so keeping the book with whoever plays the OS reproduces that
 * topology instead of inventing a second one. It also means a relaunch cannot leave a queue disagreeing
 * with the durable ledger, and there is nothing here to serialize or migrate.
 *
 * The wire vocabulary is the **platform's own**, and it is already pinned: `PhotoKitJobState`'s five cases
 * are held against the Photos klib by `:test:architecture`'s platform-vocabulary pin, so a case Apple adds
 * fails the Kotlin bump rather than reaching a scenario untaught. A caller playing the OS speaks the OS's
 * terms.
 */

private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

/**
 * One job the OS has finished with, as the caller states it.
 *
 * The caller supplies the **retry disposition** — which fetch set the job is presented in — rather than the
 * substitute inferring it from having seen the key fail before. That is what keeps the substitute free of
 * memory between invocations, and it is also more expressive: `retry` and `acknowledge` are the two sets
 * `PHAssetResourceUploadJobAction` actually has, and a caller scripting the free-retry chain needs to say
 * which one this presentation is.
 */
@Serializable
private class FinishedJobRequest(
    /** The ledger key — the destination URL's last path segment, as the real adapter reads it. */
    val key: String,
    /** `retry` or `acknowledge`. */
    val action: String,
    /** `succeeded` · `failed` · `cancelled` · `pending` · `registered`. */
    val state: String,
    /** `network` · `cancelled` · `http` · `unknown`; absent for a success. */
    val error: String? = null,
    /** The status for `error=http`. */
    @SerialName("httpStatus") val httpStatus: Int? = null,
    /** The detail for `error=unknown`. */
    val detail: String? = null,
)

@Serializable
private class CycleRequest(
    val finished: List<FinishedJobRequest> = emptyList(),
    /**
     * The OS's in-flight job cap. `createJob` answers `LIMIT_EXCEEDED` at or above it, which is what drives
     * a cap-truncated cycle: creation stops, the cursor is left un-advanced, and the result is
     * `processing`.
     */
    val jobLimit: Int? = null,
)

internal actual suspend fun beginUploadJobCycle(body: String?): String? {
    val request = runCatching { body?.let { json.decodeFromString<CycleRequest>(it) } ?: CycleRequest() }
        .getOrElse { failure ->
            // Refused, never defaulted. A malformed body that silently became "no finished jobs" would run
            // a cycle the caller did not ask for, and the caller would read its result as the answer to the
            // scenario they thought they wrote.
            return """{"refused":"could not read the job sets","detail":"${failure.message}",""" +
                """"shape":"{\\"finished\\":[{\\"key\\":\\"…\\",\\"action\\":\\"retry|acknowledge\\",""" +
                """\\"state\\":\\"succeeded|failed|cancelled|pending|registered\\",""" +
                """\\"error\\":\\"network|cancelled|http|unknown\\"}],\\"jobLimit\\":N}"}""" + "\n"
        }
    val finished = mutableListOf<FinishedUploadJob>()
    for (job in request.finished) {
        val action = SimulatorJobAction.entries.firstOrNull { it.name.equals(job.action, ignoreCase = true) }
            ?: return refusal("action", job.action, SimulatorJobAction.entries.map { it.name })
        val state = PhotoKitJobState.entries.firstOrNull { it.name.equals(job.state, ignoreCase = true) }
            ?: return refusal("state", job.state, PhotoKitJobState.entries.map { it.name })
        val error = uploadError(job) ?: if (job.error == null) {
            null
        } else {
            return refusal("error", job.error, listOf("network", "cancelled", "http", "unknown"))
        }
        finished += FinishedUploadJob(key = job.key, action = action, state = state, error = error)
    }
    SimulatorUploadJobs.beginCycle(finished, jobLimit = request.jobLimit ?: Int.MAX_VALUE)
    return null
}

/**
 * Every unrecognised token is refused with what would have been accepted, rather than resolved to a
 * default. A `state` that quietly became `succeeded` would record `UPLOADED` for a job the caller meant to
 * fail, and the scenario would pass having asserted the opposite of what it drove.
 */
private fun refusal(field: String, was: String, accepted: List<String>): String =
    """{"refused":"$field must be one of ${accepted.joinToString("|") { it.lowercase() }}, was '$was'"}""" + "\n"

private fun uploadError(job: FinishedJobRequest): UploadError? = when (job.error?.lowercase()) {
    "network" -> UploadError.Network
    "cancelled" -> UploadError.Cancelled
    "http" -> UploadError.Http(job.httpStatus ?: 500)
    "unknown" -> UploadError.Unknown(job.detail ?: "stated by the control channel")
    else -> null
}

/**
 * The jobs this cycle created, **with their destination URL and headers verbatim**.
 *
 * Verbatim rather than summarised, because that is the one thing this host can prove that nothing else
 * can: the exact string the edge-URL builder composed, next to the backend's real answer to it. The
 * builder is pinned by `commonMain` tests on string-building alone, so until now nothing anywhere
 * demonstrated that a URL it composes is one the backend accepts.
 */
internal actual suspend fun endUploadJobCycle(raw: Int): String {
    val created = SimulatorUploadJobs.createdThisCycle()
    return """{"processRawValue":$raw,"result":"${processingResultName(raw)}","queue":"simulated",""" +
        """"jobLimit":${SimulatorUploadJobs.jobLimit()},""" +
        """"created":[${created.joinToString(",") { it.render() }}]}""" + "\n"
}

private fun CreatedUploadJob.render(): String =
    """{"key":${quote(key)},"destination":${quote(destination)},"contentType":${quote(contentType)},""" +
        """"isRetry":$isRetry,"headers":{${headers.entries.joinToString(",") {
            "${quote(it.key)}:${quote(it.value)}"
        }}}}"""

/** JSON string escaping, minimal and explicit — a header value may carry a quote or a backslash. */
private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

/**
 * The other half of playing the OS: actually moving one job's bytes. See `PerformUploadJob.kt`.
 */
internal actual fun uploadJobDeviceCommands(): Map<String, RigCommand> = mapOf(
    "upload-jobs/perform" to performUploadJobCommand(),
    "upload-extension/record" to uploadExtensionRecordCommand(),
)

/**
 * `POST /device/upload-extension/record?registered=true|false&failNextWith=<PHPhotosError code>`
 *
 * The registration levers — what makes the disable→enable ritual's own contract drivable.
 *
 * Two scenarios it exists for, neither reachable anywhere before. **A stale record**: plant one
 * (`registered=true`) and the leading disable reports that a record existed and was removed, which is the
 * evidence the ritual is built on. **A refused enable**: arm `failNextWith=3202` and the enable fails
 * exactly as it does against a record a differently-signed build left behind — after which, on a device,
 * the OS never launches the extension and nothing else reports it.
 *
 * The failure is **one-shot**, because the ritual is a pair: a sticky lever would fail both halves and
 * could not express "the disable succeeded and the enable did not", which is the interesting case.
 */
internal fun uploadExtensionRecordCommand(): RigCommand = RigCommand { params, _ ->
    val registeredRaw = params["registered"]
    val failRaw = params["failNextWith"]
    val registered = registeredRaw?.let { it.toBooleanStrictOrNull() }
    val failWith = failRaw?.let { it.toLongOrNull() }
    when {
        registeredRaw != null && registered == null ->
            CommandResult.badRequest("registered must be true or false, was '$registeredRaw'")
        failRaw != null && failRaw != "none" && failWith == null ->
            CommandResult.badRequest("failNextWith must be a PHPhotosError code, or 'none', was '$failRaw'")
        else -> {
            registered?.let { SimulatorExtensionRecord.setRegistered(it) }
            failRaw?.let { SimulatorExtensionRecord.failNextWith(if (it == "none") null else failWith) }
            CommandResult.ok(
                """{"registered":${SimulatorExtensionRecord.registered},""" +
                    """"failNextWith":${SimulatorExtensionRecord.failNextWith ?: "null"}}""",
            )
        }
    }
}
