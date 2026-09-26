package app.snapsync.world

import io.ktor.client.HttpClient

/**
 * What a backend-neutral read or lever answers (`docs/testing.md`, "The world's backend is one
 * seam with two implementations"): a value, or a stated reason this backend cannot honour it.
 *
 * There is deliberately no third shape. An operation a backend cannot honour answering an empty set, a `null`,
 * or a silent success would let a test assert against something the backend never did — the absence-collapse
 * this codebase keeps paying for, reintroduced in the test equipment where nobody would look.
 */
sealed interface Answer<out T> {
    data class Available<T>(val value: T) : Answer<T>
    data class Unavailable(val reason: String) : Answer<Nothing>

    companion object {
        /** An operation the mini-edge models and [backend] does not. */
        fun unavailable(backend: WorldBackend, operation: String, why: String): Unavailable =
            Unavailable("$operation is unavailable on this backend (${backend.name}): $why")
    }
}

/** The value, or a stated failure naming why there is none — for a caller that cannot proceed without it. */
fun <T> Answer<T>.orFail(): T = when (this) {
    is Answer.Available -> value
    is Answer.Unavailable -> error(reason)
}

/**
 * The world's backend: ONE seam, two implementations (`docs/testing.md`).
 *
 * - [MiniEdgeBackend] — the in-memory [BackendStore] served by the `MockEngine` mini-edge. The default, and the
 *   only one on every target the world declares.
 * - `DenoBackend` (`jvmMain`) — the real `api/` served as a local, loopback-only process through `:test:edge`.
 *
 * The seam is small on purpose. Everything the world does to its backend that the backend's own public HTTP
 * surface can carry — joins, event creation, manifests, bytes, the listing and union reads — goes over
 * [newClient] through the world's real clients, written once for both. What is left here is what differs: where the
 * backend is, and the operator levers only an in-memory store can pull.
 */
interface WorldBackend {
    /** A short name for refusals and logs — `mini-edge` or `deno`. */
    val name: String

    /** The device-facing base, carrying exactly one version prefix, as a real build's baked base does. */
    val base: String

    /**
     * A NEW bare client for this backend on every call. The world puts one under its `HttpBackend` and hands
     * another to the upload double as the network an OS transfer crosses.
     *
     * A fresh instance per call rather than one shared value: a client is a resource each world closes on its own
     * schedule, and a plugin one world installed into a shared client would reach every other world's requests —
     * which is how a shared client once made the OS's own request, already declaring the app version, reach the
     * backend declaring it twice (the real edge reads that as unparseable and answers `426`).
     */
    fun newClient(): HttpClient
}

/**
 * The mini-edge: [store] served by [miniEdgeClient]. [store] is the mini-edge-only surface — the levers and
 * reads a real backend has no route for — and `World.store` hands it out only on this backend.
 */
class MiniEdgeBackend(
    override val base: String = DEFAULT_BASE,
    val store: BackendStore = BackendStore(),
) : WorldBackend {
    override val name: String = "mini-edge"
    override fun newClient(): HttpClient = miniEdgeClient(store)

    companion object {
        /** A synthetic host the `MockEngine` answers, v2 — what every world has always composed over. */
        const val DEFAULT_BASE: String = "https://world.edge/api/v2"
    }
}
