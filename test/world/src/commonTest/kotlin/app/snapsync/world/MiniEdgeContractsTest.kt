package app.snapsync.world

import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.EdgeSetup
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.http.HttpBackend
import app.snapsync.ports.Backend
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The mini-edge held to the `Backend` port contract, as its `Fake` (`docs/testing.md`, "Backend object store with
 * faithful read-models"). The port under contract is the production [HttpBackend], exactly as in
 * `:adapter:generic:app`'s live binding; only the edge behind it differs. A clause the real edge passes and this one
 * fails is a mini-edge defect, fixed in the mini-edge — never by declaring its state unreachable here.
 *
 * Every clause gets a fresh [BackendStore], configured as the deployed edge is: its version gate armed at the
 * deployed minimum. States are entered through the edge's public surface with [EdgeSetup], as the live binding
 * enters them. In `commonTest` by the placement rule, so CI also runs it on the simulator; that run adds no coverage
 * of `HttpBackend`, which is `commonMain` and already runs there through its own tests.
 *
 * Phase 11 of the testing-concept sequence deletes `:test:world`, and this binding with it; every clause keeps its
 * live binding, so nothing depends on it.
 */
class MiniEdgeContractsTest {

    private val backend = object : Binding<BackendState, EdgeSubject<Backend>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
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
        )

        override fun create(state: BackendState, clauseId: String): Entered<EdgeSubject<Backend>> {
            if (state == BackendState.FOREIGN_TOKEN) {
                return Entered.Unreachable("the mini-edge verifies no credential, so it cannot reject one (World.kt: modelling it is not done)")
            }
            return runBlocking {
                val store = BackendStore().apply { minAppVersion = DEPLOYED_MINIMUM }
                val setup = EdgeSetup(miniEdgeClient(store), BASE)
                val seeded = BackendContract.seed(state, clauseId, setup)
                val client = miniEdgeClient(store)
                Entered.Ready(EdgeSubject(HttpBackend(client, BASE, seeded.identity.appVersion), seeded, setup), dispose = { client.close() })
            }
        }
    }

    @Test
    fun `the mini-edge satisfies the Backend contract`() = verify(BackendContract, backend)

    private companion object {
        const val BASE = "https://contract.edge/api/v2"

        /** The deployed edge's minimum (`api/src/config.ts` `MIN_APP_VERSION`); any X.Y a refusal can name. */
        const val DEPLOYED_MINIMUM = "0.4"
    }
}
