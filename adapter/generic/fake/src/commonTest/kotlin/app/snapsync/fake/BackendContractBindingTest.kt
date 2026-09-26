package app.snapsync.fake

import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.PortSetup
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.DeviceFile
import app.snapsync.ports.Backend
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The in-memory backend held to the `Backend` contract the real `api/` satisfies (`docs/testing.md`). Each clause
 * gets a fresh backend at its defaults, entered through its own port ([PortSetup]) — the one public surface it has —
 * with bytes landing in the cell it was given, as the OS's uploader would put them there.
 *
 * [BackendState.VERSION_REFUSED] is entered as the backend it is: one that refuses this build. The build's declared
 * version is the HTTP adapter's wire concern, so here the refusal is the backend's own state — seeded on a serving
 * backend, then asked of one that refuses.
 */
class BackendContractBindingTest {

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
            BackendState.FOREIGN_TOKEN,
        )

        override fun create(state: BackendState, clauseId: String): Entered<EdgeSubject<Backend>> = runBlocking {
            val stored = mutableMapOf<String, MutableSet<DeviceFile>>()
            val serving = inMemoryBackend(storedFiles = stored)
            val setup = PortSetup(serving, stored)
            val seeded = BackendContract.seed(state, clauseId, setup)
            val port = if (state == BackendState.VERSION_REFUSED) {
                inMemoryBackend(storedFiles = stored, minimumAppVersion = DEPLOYED_MINIMUM)
            } else {
                serving
            }
            Entered.Ready(EdgeSubject(port, seeded, setup))
        }
    }

    @Test
    fun `the in-memory backend satisfies the Backend contract`() = verify(BackendContract, backend)

    private companion object {
        /** Any X.Y a refusal can name — the deployed edge's minimum (`api/src/config.ts`). */
        const val DEPLOYED_MINIMUM = "0.4"
    }
}
