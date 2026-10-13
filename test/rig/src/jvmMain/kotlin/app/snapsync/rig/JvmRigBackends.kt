package app.snapsync.rig

import app.snapsync.contracts.Attester
import app.snapsync.contracts.attest.InMemoryKeyAttestation
import app.snapsync.contracts.attest.SoftwareKeyAttestation
import app.snapsync.contracts.attest.SoftwareKeyIntegrity
import app.snapsync.http.KtorHttpTransport
import app.snapsync.jvm.JvmMocks
import app.snapsync.liveedge.LiveEdge
import app.snapsync.mock.BackendOperator
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.HttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody

/**
 * The JVM host's backend (`docs/testing.md`, "The control channel"): the in-memory backend mock — the default — or the
 * real `api/` served locally through `:test:edge`. Everything the host does to its backend over the backend's public
 * surface goes through [reach]; what only the mock's operator can do goes through [operator], which the real backend
 * has none of — a lever it cannot honour answers `409` with [unavailable]'s reason, never a silent no-op.
 */
internal sealed interface RigBackend {
    /** A short name for refusals and logs — `mock` or `deno`. */
    val name: String

    /** The device-facing base, carrying exactly one version prefix, as a real build's baked base does. */
    val base: String

    /**
     * The device-integrity service the app proves with against this backend: the in-memory one the mock verifies, or a
     * software key the local `api/`'s `any` trust accepts.
     */
    fun integrity(mocks: JvmMocks): DeviceIntegrity

    /** The keys a played member attests with against this backend, as [integrity]'s proofs are made. */
    fun attester(): Attester

    /** The network an OS upload crosses to this backend, or `null` for the mocks' own byte route. */
    val network: UploadNetwork?

    /**
     * Whether this backend answers on the requesting lane — the mock, whose held request is parked there — rather than
     * off it, over a network the host's loop must wait on.
     */
    val answersOnTheLoop: Boolean

    /** The device API's transport a launch of the app — or the host's own seeding — talks through. */
    fun port(mocks: JvmMocks): HttpTransport

    /** The mock's operator face, or `null` on a backend that has none. */
    fun operator(mocks: JvmMocks): BackendOperator?

    /** Why [operation] is unavailable on this backend. */
    fun unavailable(
        operation: String,
    ): String = "$operation is unavailable on this backend ($name): $UNAVAILABLE_BECAUSE"

    companion object {
        fun named(name: String): RigBackend = when (name) {
            "mock" -> MockBackend
            "deno" -> DenoBackend
            else -> error("the JVM rig host's backend must be mock|deno, was '$name'")
        }

        private const val UNAVAILABLE_BECAUSE =
            "the real edge keeps its state in its database and serves no route for this, and nothing drives it at runtime"
    }

    /** The in-memory backend mock — the default, and the one every lever and read is honoured on. */
    data object MockBackend : RigBackend {
        override val name = "mock"
        override val base = "https://in-memory.backend/api/v2"
        override fun integrity(mocks: JvmMocks): DeviceIntegrity = mocks.enclave.port()
        override fun attester(): Attester = InMemoryKeyAttestation()
        override val network: UploadNetwork? = null
        override val answersOnTheLoop = true
        override fun port(mocks: JvmMocks): HttpTransport = mocks.backend.port()
        override fun operator(mocks: JvmMocks): BackendOperator = mocks.backend.operator
    }

    /**
     * The real `api/`, as a local loopback process (`:test:edge`'s [LiveEdge]). The app attests as an emulator does, with
     * a software key the local deployment's `any` trust accepts — App Attest needs a device.
     */
    data object DenoBackend : RigBackend {
        /** One engine per JVM, under a fresh client per use: the backend process is the JVM's, not a host's. */
        private val engine: HttpClientEngine by lazy { CIO.create() }

        override val name = "deno"
        override val base: String get() = LiveEdge.base
        override fun integrity(mocks: JvmMocks): DeviceIntegrity = SoftwareKeyIntegrity()
        override fun attester(): Attester = SoftwareKeyAttestation()
        override val answersOnTheLoop = false
        override val network: UploadNetwork = httpNetwork { HttpClient(engine) }
        override fun port(mocks: JvmMocks): HttpTransport = KtorHttpTransport(HttpClient(engine))
        override fun operator(mocks: JvmMocks): BackendOperator? = null
    }
}

/** An OS upload over HTTP: the job's own request, a real `PUT` — no answer is `null`. */
private fun httpNetwork(client: () -> HttpClient) = UploadNetwork { url, headers, bytes ->
    runCatchingCancellable {
        client().put(url) {
            headers { headers.forEach { (name, value) -> append(name, value) } }
            setBody(bytes)
        }.status.value
    }.getOrNull()
}
