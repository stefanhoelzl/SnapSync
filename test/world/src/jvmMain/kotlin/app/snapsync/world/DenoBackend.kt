package app.snapsync.world

import app.snapsync.liveedge.LiveEdge
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * The real backend (capability `harness-world-model`, "The world's backend is one seam with two
 * implementations"): the Deno `api/` served by `:test:edge`'s [LiveEdge] — the production `createApp` over an
 * ephemeral filesystem store, loopback-only, one process per JVM.
 *
 * It holds nothing but where the backend is. Everything the world does to it goes over its public HTTP surface
 * through the world's real clients; the world never reads the process's storage directory, which would pin the
 * backend's storage layout into the tests. What only an in-memory store could answer, the world answers
 * [Answer.Unavailable] for.
 *
 * Requests carry no token: the dev server's fallback bearer serves an unauthenticated request exactly as the
 * simulator is served, and enrols the device its push registration names.
 *
 * Isolation between worlds sharing the process is by address, as the backend contracts' live bindings are
 * isolated: every event a world touches is one the backend minted for it.
 */
class DenoBackend : WorldBackend {
    override val name: String = "deno"
    override val base: String get() = LiveEdge.base
    override fun newClient(): HttpClient = HttpClient(sharedEngine)

    private companion object {
        /**
         * One engine per JVM, under a fresh client per call: the process is shared, and an engine per world
         * would only leak threads. A client built over a passed-in engine does not close it.
         */
        val sharedEngine: HttpClientEngine by lazy { CIO.create() }
    }
}
