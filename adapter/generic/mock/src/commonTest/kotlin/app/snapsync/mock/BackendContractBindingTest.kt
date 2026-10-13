package app.snapsync.mock

import app.snapsync.contracts.Attester
import app.snapsync.contracts.BackendClient
import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClientIdentity
import app.snapsync.contracts.EdgeSetup
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.levers.BackendFault
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.Proof
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

/**
 * The backend mock held to the `Backend` contract the real `api/` satisfies (`docs/testing.md`): the production
 * gateway ([BackendClient]) over the mock's HTTP face, as over a socket to the real one. Each clause gets a fresh
 * backend at its defaults, entered through that same face ([EdgeSetup]) — the one public surface it has.
 *
 * [BackendState.VERSION_REFUSED] is entered as the backend it is: one that refuses this build — seeded while it
 * serves, then armed with a minimum.
 */
class BackendContractBindingTest {

    private val backend = object : Binding<BackendState, EdgeSubject<BackendClient>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = BackendRouter::class
        override val reaches = setOf(
            BackendState.SERVING,
            BackendState.EVENT_EXISTS,
            BackendState.NO_SUCH_EVENT,
            BackendState.MEMBER,
            BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
            BackendState.UNION_COMPLETE_ASSET,
            BackendState.UNION_INCOMPLETE_ASSET,
            BackendState.DEVICE_UPLOADED,
            BackendState.DEVICE_UPLOAD_BROKE_OFF,
            BackendState.VERSION_REFUSED,
            BackendState.FOREIGN_TOKEN,
            BackendState.ENDED_MEMBER,
            BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
            BackendState.SERVER_FAILING,
            BackendState.IN_MAINTENANCE,
            BackendState.ENDED_FOR_GOOD,
            BackendState.CLOSED_BEFORE_OPEN_UNTIL,
            BackendState.NO_BACKEND,
            BackendState.UNREADABLE_SUCCESS,
            BackendState.ATTESTABLE,
        )

        override fun create(state: BackendState, clauseId: String, log: CallLog): Entered<EdgeSubject<BackendClient>> =
            runBlocking {
                // An in-memory backend always answers: a request that never completes is a socket's, not a double's.
                if (state !in reaches) {
                    return@runBlocking Entered.Unreachable(
                        "an in-memory backend answers what it holds, and attests nothing",
                    )
                }
                val mock = BackendMock()
                val transport = mock.port()
                val setup = EdgeSetup(transport, BASE)
                val seeded = BackendContract.seed(state, clauseId, setup)
                if (state == BackendState.VERSION_REFUSED) mock.operator.minAppVersion(DEPLOYED_MINIMUM)
                faultOf(
                    state,
                )?.let { fault -> OPERATIONS.forEach { mock.operator.fail(it, fault, times = Int.MAX_VALUE) } }
                val client = { identity: ClientIdentity ->
                    BackendClient(transport.recorded(log), BASE, identity, log)
                }
                val attester = InMemoryAttester.takeIf { state == BackendState.ATTESTABLE }
                Entered.Ready(
                    EdgeSubject(client(seeded.identity), seeded, setup, attester = attester, reidentify = client),
                )
            }
    }

    /** The operator's fault that plays [state] — a failing store, a migration window — or `null`. */
    private fun faultOf(state: BackendState): BackendFault? = when (state) {
        BackendState.SERVER_FAILING -> BackendFault.SERVER_ERROR
        BackendState.IN_MAINTENANCE -> BackendFault.MAINTENANCE
        BackendState.NO_BACKEND -> BackendFault.UNREACHABLE
        BackendState.UNREADABLE_SUCCESS -> BackendFault.MALFORMED
        BackendState.ENDED_FOR_GOOD -> BackendFault.ENDED_FOR_GOOD
        BackendState.CLOSED_BEFORE_OPEN_UNTIL -> BackendFault.CLOSED
        else -> null
    }

    @Test
    fun `the backend mock satisfies the Backend contract`() = verify(BackendContract, backend)

    private companion object {
        /** Every operation the app sends, which a played fault answers. */
        val OPERATIONS = listOf(
            Routes.Challenge.ID, Routes.MintToken.ID, Routes.RenewToken.ID, Routes.CreateEvent.ID, Routes.GetEvent.ID,
            Routes.ChangeEvent.ID, Routes.JoinEvent.ID, Routes.LeaveEvent.ID, Routes.PublishManifest.ID,
            Routes.EventFiles.ID, Routes.DeviceFiles.ID, Routes.PutDevice.ID,
        )

        /** Where the mock's device API is addressed — any base: it routes on the path. */
        const val BASE = "https://in-memory.backend/api/v2"

        /** Any X.Y a refusal can name — the deployed edge's minimum (`api/src/config.ts`). */
        const val DEPLOYED_MINIMUM = "0.4"
    }
}

/**
 * A key in the in-memory integrity's own proofs — `attestation:<key>:<challenge>`, `assertion:<key>:<challenge>` — the ones
 * the backend mock verifies, as the device-integrity double it stands beside produces them.
 */
@OptIn(ExperimentalEncodingApi::class)
private object InMemoryAttester : Attester {
    private const val KEY = "contract-key"

    override fun proof(challenge: String): Proof =
        AppleAppAttestProof(KEY, Base64.encode("attestation:$KEY:$challenge".encodeToByteArray()))

    override fun sign(challenge: String): ByteArray = "assertion:$KEY:$challenge".encodeToByteArray()
}
