package app.snapsync.liveedge

import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.http.HttpBackend
import app.snapsync.ports.Backend
import kotlin.test.Test

/**
 * The `Backend` port contract against the REAL backend (`docs/testing.md`): the production [HttpBackend] over a
 * socket to `api/`, served locally by [LiveEdge]. This is the binding that makes every backend clause covered; the
 * mini-edge's binding in `:test:world` and the in-memory mock's in `:adapter:generic:fake` are the `Fake`s held to
 * it.
 *
 * JVM only, and the coverage that forgoes is stated here (`docs/testing.md`, "Every test runs on every target its
 * module declares"): a Kotlin/Native test executable under `simctl` cannot launch the backend as a process. Nothing
 * is lost by it: [HttpBackend] is `commonMain` code, identical on every target, and its Kotlin/Native compilation is
 * covered by this module's `commonTest`. What differs on a device is the Ktor ENGINE (Darwin), which no binding here
 * — or anywhere yet — puts in front of a real backend.
 *
 * Every state is reachable here: the dev edge enrols the device a token-less call names (`api/src/dev/fallback.ts`),
 * because on a host without App Attest nothing else ever could, and it verifies every token it is shown.
 */
class LiveEdgeContractsTest {

    private val backend = object : Binding<BackendState, EdgeSubject<Backend>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            BackendState.SERVING,
            BackendState.EVENT_EXISTS,
            BackendState.EVENT_FULL,
            BackendState.NO_SUCH_EVENT,
            BackendState.MEMBER,
            BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
            BackendState.UNION_COMPLETE_ASSET,
            BackendState.UNION_INCOMPLETE_ASSET,
            BackendState.DEVICE_UPLOADED,
            BackendState.VERSION_REFUSED,
            BackendState.FOREIGN_TOKEN,
        )

        override fun create(state: BackendState, clauseId: String): Entered<EdgeSubject<Backend>> =
            LiveEdge.enter({ BackendContract.seed(state, clauseId, it) }) { client, base, seeded ->
                HttpBackend(client, base, seeded.identity.appVersion)
            }
    }

    @Test
    fun `the real edge satisfies the Backend contract`() = verify(BackendContract, backend)
}
