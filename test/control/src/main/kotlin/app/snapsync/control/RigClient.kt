package app.snapsync.control

import app.snapsync.rig.DeviceAdvertisement
import app.snapsync.rig.GalleryView
import app.snapsync.rig.RigState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a verb answered (`docs/testing.md`, "One control protocol, served by two hosts").
 *
 * A refusal is its own shape and never an exception: a host that cannot honour a verb says so with `409` and a
 * reason, and a caller deciding whether to skip must be able to read that without catching anything.
 */
sealed interface Reply {
    val status: Int
    val body: String

    /** `2xx` — the verb ran (or, for a fire-and-forget entry, was accepted). */
    data class Done(override val status: Int, override val body: String) : Reply

    /** `409` — this host does not honour the verb; [reason] says why. */
    data class Refused(override val body: String, val reason: String) : Reply {
        override val status: Int = HttpStatusCode.Conflict.value
    }

    /** Anything else — a bad request, an unknown verb, a failure inside the host. */
    data class Failed(override val status: Int, override val body: String) : Reply
}

/** The body of a [Reply.Done], or a stated failure naming what the host answered instead. */
fun Reply.done(): String = when (this) {
    is Reply.Done -> body
    is Reply.Refused -> error("the host refused: $reason")
    is Reply.Failed -> error("the host answered $status: $body")
}

/**
 * The typed client of the control protocol, for either host — the JVM host or the app on a device or simulator —
 * at [baseUrl] (for example `http://127.0.0.1:18099`).
 *
 * It is written against the wire types the rig itself encodes ([RigState] embeds the real `UiState`), so there is
 * no second rendering of the state here that could disagree with the screen.
 */
class RigClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient(CIO) {
        // No request timeout by default: a receipted entry point holds its request until the wake's own work is
        // done or the OS's expiry releases it, and a transport timeout would be indistinguishable from
        // an expiry.
        install(HttpTimeout)
        // The CIO engine carries its OWN request timeout, 15 s by default, which `HttpTimeout` left unset does not
        // lift (measured: a 4-photo seed on a freshly booted simulator died at 15 s as a request timeout). 0 disables
        // it, so every bound is the caller's.
        engine { requestTimeout = 0 }
    },
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    /** `GET /health`. */
    suspend fun health(): String = http.get("$base/health").bodyAsText()

    /** `GET /device` — what this host honours and refuses of the shared vocabulary. */
    suspend fun device(): DeviceAdvertisement =
        json.decodeFromString(DeviceAdvertisement.serializer(), http.get("$base/device").bodyAsText())

    /** `GET /device/state` — the real reduced UI state, and the read-models beside it. */
    suspend fun state(): RigState =
        json.decodeFromString(RigState.serializer(), http.get("$base/device/state").bodyAsText())

    /**
     * Poll [state] until [until] holds, or fail after [timeout] naming the last state seen. A user command and a
     * fire-and-forget entry point are answered before their work finishes — exactly as a tap is — so a caller
     * waits on a condition, never on a sleep.
     */
    suspend fun awaitState(timeout: Duration = 10.seconds, until: (RigState) -> Boolean): RigState {
        var last: RigState? = null
        val reached = withTimeoutOrNull(timeout) {
            var state = state().also { last = it }
            while (!until(state)) {
                delay(POLL)
                state = state().also { last = it }
            }
            state
        }
        return reached ?: error("the state did not reach the condition within $timeout; last seen: $last")
    }

    /**
     * `GET /device/gallery` — the photo library as the app's own candidate seam and selection policy read it:
     * every asset, its policy facts and verdict, and (with [resources]) its resources and capture names.
     */
    suspend fun gallery(cutoff: String? = null, resources: Boolean = false, downloadOnly: Boolean = false): GalleryView =
        json.decodeFromString(
            GalleryView.serializer(),
            http.get("$base/device/gallery") {
                cutoff?.let { parameter("cutoff", it) }
                if (resources) parameter("resources", "true")
                if (downloadOnly) parameter("direction", "download")
            }.bodyAsText(),
        )

    /** `GET /device/logs` — the tail of a process's device log (`app` or `extension`). */
    suspend fun logs(process: String = "app", bytes: Int? = null): String =
        http.get("$base/device/logs") {
            parameter("process", process)
            bytes?.let { parameter("bytes", it) }
        }.bodyAsText()

    /** `POST /user/<name>` — a user command at the intent level, as a tap reaches it. */
    suspend fun user(name: String, params: Map<String, String> = emptyMap()): Reply =
        http.post("$base/user/$name") { params.forEach { (k, v) -> parameter(k, v) } }.reply()

    /** `POST /os/<root>/<member>` — invoke a composition root's real entry point, as the platform would. */
    suspend fun os(root: String, member: String, arg: String? = null, body: String? = null): Reply =
        http.post("$base/os/$root/$member") {
            arg?.let { parameter("arg", it) }
            body?.let { setBody(it) }
        }.reply()

    /** `POST /device/<name>` — a device verb: a shared write, or a host-specific one this host may refuse. */
    suspend fun deviceVerb(name: String, params: Map<String, String> = emptyMap(), body: String? = null): Reply =
        http.post("$base/device/$name") {
            params.forEach { (k, v) -> parameter(k, v) }
            body?.let { setBody(it) }
        }.reply()

    /** `GET /contract` — the contracts this host runs in-app, or its refusal. */
    suspend fun contracts(): Reply = http.get("$base/contract").reply()

    /** `POST /contract/<name>` — run a port contract in-app, or read this host's refusal. */
    suspend fun contract(name: String, params: Map<String, String> = emptyMap()): Reply =
        http.post("$base/contract/$name") { params.forEach { (k, v) -> parameter(k, v) } }.reply()

    override fun close() = http.close()

    private suspend fun HttpResponse.reply(): Reply {
        val text = bodyAsText()
        return when {
            status.value in 200..299 -> Reply.Done(status.value, text)
            status == HttpStatusCode.Conflict -> Reply.Refused(text, reasonOf(text))
            else -> Reply.Failed(status.value, text)
        }
    }

    /** The reason a `409` carries: a device verb's `refused` field, or the contract verb's marker line. */
    private fun reasonOf(text: String): String =
        runCatching { json.parseToJsonElement(text).jsonObject["refused"]?.jsonPrimitive?.content }.getOrNull()
            ?: text.trim().removePrefix(CONTRACT_MARKER)

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        val POLL = 50.milliseconds

        /** The contract verb's refusal marker (`docs/architecture.md`). */
        const val CONTRACT_MARKER = "refused: "
    }
}
