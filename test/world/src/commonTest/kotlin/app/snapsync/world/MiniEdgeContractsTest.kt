package app.snapsync.world

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceFilesSourceContract
import app.snapsync.contracts.DeviceFilesSourceState
import app.snapsync.contracts.EdgeSetup
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.EventCreationContract
import app.snapsync.contracts.EventCreationState
import app.snapsync.contracts.EventDirectoryContract
import app.snapsync.contracts.EventDirectoryState
import app.snapsync.contracts.EventJoinContract
import app.snapsync.contracts.EventJoinState
import app.snapsync.contracts.EventRenameContract
import app.snapsync.contracts.EventRenameState
import app.snapsync.contracts.EventUnionSourceContract
import app.snapsync.contracts.EventUnionSourceState
import app.snapsync.contracts.GateRecorder
import app.snapsync.contracts.LeaveNotifierContract
import app.snapsync.contracts.LeaveNotifierState
import app.snapsync.contracts.PushTokenPublisherContract
import app.snapsync.contracts.PushTokenPublisherState
import app.snapsync.contracts.ManifestPublisherContract
import app.snapsync.contracts.ManifestPublisherState
import app.snapsync.contracts.Seeded
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.eventcreation.HttpEventRename
import app.snapsync.http.withCredentialInterceptor
import app.snapsync.join.HttpEventDirectory
import app.snapsync.join.HttpEventJoin
import app.snapsync.join.HttpManifestPublisher
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.EventCreation
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.EventJoin
import app.snapsync.ports.EventRename
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.LeaveNotifier
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.push.HttpPushTokenPublisher
import app.snapsync.ports.ManifestPublisher
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The mini-edge held to the backend port contracts, as their `Fake` (capability `port-contracts`; capability
 * `harness-world-model`, "Backend object store with faithful read-models"). The client under contract is the
 * production `Http*` client and interceptor, exactly as in `:adapter:generic:app`'s live bindings; only the edge
 * behind it differs. A clause the real edge passes and this one fails is a mini-edge defect, fixed in the
 * mini-edge — never by declaring its state unreachable here.
 *
 * Every clause gets a fresh [BackendStore], configured as the deployed edge is: its version gate armed at the
 * deployed minimum. States are entered through the edge's public surface with [EdgeSetup], as the live binding
 * enters them. In `commonTest` by the placement rule, so CI also runs it on the simulator; that run adds no
 * coverage of the clients, which are `commonMain` and already run there through their own tests.
 *
 * Phase 11 of the testing-concept sequence deletes `:test:world`, and these bindings with it; every clause keeps
 * its live binding, so nothing depends on them.
 */
class MiniEdgeContractsTest {

    private fun <P> enter(
        seed: suspend (EdgeSetup) -> Seeded,
        port: (client: HttpClient, base: String, seeded: Seeded) -> P,
    ): Entered<EdgeSubject<P>> = runBlocking {
        val store = BackendStore().apply { minAppVersion = DEPLOYED_MINIMUM }
        val seeded = seed(EdgeSetup(miniEdgeClient(store), BASE))
        val gate = GateRecorder()
        val client = miniEdgeClient(store).withCredentialInterceptor(
            token = { seeded.identity.token },
            onRejected = gate::onRejected,
            appVersion = { seeded.identity.appVersion },
            onVersionRefused = gate::onVersionRefused,
        )
        Entered.Ready(EdgeSubject(port(client, BASE, seeded), seeded, gate), dispose = { client.close() })
    }

    private val directory = object : Binding<EventDirectoryState, EdgeSubject<EventDirectory>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(EventDirectoryState.EVENT_EXISTS, EventDirectoryState.NO_SUCH_EVENT, EventDirectoryState.VERSION_REFUSED, EventDirectoryState.FOREIGN_TOKEN)

        override fun create(state: EventDirectoryState, clauseId: String): Entered<EdgeSubject<EventDirectory>> =
            enter({ EventDirectoryContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventDirectory(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the EventDirectory contract`() = verify(EventDirectoryContract, directory)

    private val creation = object : Binding<EventCreationState, EdgeSubject<EventCreation>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(EventCreationState.SERVING)

        override fun create(state: EventCreationState, clauseId: String): Entered<EdgeSubject<EventCreation>> =
            enter({ EventCreationContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventCreation(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the EventCreation contract`() = verify(EventCreationContract, creation)

    private val rename = object : Binding<EventRenameState, EdgeSubject<EventRename>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(EventRenameState.EVENT_EXISTS, EventRenameState.NO_SUCH_EVENT)

        override fun create(state: EventRenameState, clauseId: String): Entered<EdgeSubject<EventRename>> =
            enter({ EventRenameContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventRename(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the EventRename contract`() = verify(EventRenameContract, rename)

    private val join = object : Binding<EventJoinState, EdgeSubject<EventJoin>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(EventJoinState.EVENT_OPEN, EventJoinState.EVENT_FULL, EventJoinState.NO_SUCH_EVENT)

        override fun create(state: EventJoinState, clauseId: String): Entered<EdgeSubject<EventJoin>> =
            if (state == EventJoinState.FOREIGN_TOKEN) {
                Entered.Unreachable("the mini-edge verifies no credential, so it cannot reject one (World.kt: modelling it is not done)")
            } else {
                enter({ EventJoinContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventJoin(client, base) }
            }
    }

    @Test
    fun `the mini-edge satisfies the EventJoin contract`() = verify(EventJoinContract, join)

    private val manifest = object : Binding<ManifestPublisherState, EdgeSubject<ManifestPublisher>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(ManifestPublisherState.MEMBER, ManifestPublisherState.NON_MEMBER, ManifestPublisherState.NO_SUCH_EVENT)

        override fun create(state: ManifestPublisherState, clauseId: String): Entered<EdgeSubject<ManifestPublisher>> =
            enter({ ManifestPublisherContract.seed(state, clauseId, it) }) { client, base, _ -> HttpManifestPublisher(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the ManifestPublisher contract`() = verify(ManifestPublisherContract, manifest)

    private val union = object : Binding<EventUnionSourceState, EdgeSubject<EventUnionSource>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(EventUnionSourceState.NO_SUCH_EVENT, EventUnionSourceState.EMPTY_EVENT, EventUnionSourceState.COMPLETE_ASSET, EventUnionSourceState.INCOMPLETE_ASSET)

        override fun create(state: EventUnionSourceState, clauseId: String): Entered<EdgeSubject<EventUnionSource>> =
            enter({ EventUnionSourceContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventUnionSource(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the EventUnionSource contract`() = verify(EventUnionSourceContract, union)

    private val deviceFiles = object : Binding<DeviceFilesSourceState, EdgeSubject<DeviceFilesSource>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceFilesSourceState.NO_UPLOADS, DeviceFilesSourceState.UPLOADED)

        override fun create(state: DeviceFilesSourceState, clauseId: String): Entered<EdgeSubject<DeviceFilesSource>> =
            enter({ DeviceFilesSourceContract.seed(state, clauseId, it) }) { client, base, _ -> HttpDeviceFilesSource(client, base) }
    }

    @Test
    fun `the mini-edge satisfies the DeviceFilesSource contract`() = verify(DeviceFilesSourceContract, deviceFiles)

    private val leave = object : Binding<LeaveNotifierState, EdgeSubject<LeaveNotifier>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(LeaveNotifierState.MEMBER, LeaveNotifierState.NO_SUCH_EVENT)

        override fun create(state: LeaveNotifierState, clauseId: String): Entered<EdgeSubject<LeaveNotifier>> =
            enter({ LeaveNotifierContract.seed(state, clauseId, it) }) { client, base, seeded -> HttpLeaveNotifier(client, base) { seeded.deviceId } }
    }

    @Test
    fun `the mini-edge satisfies the LeaveNotifier contract`() = verify(LeaveNotifierContract, leave)

    private companion object {
        const val BASE = "https://contract.edge/api/v2"

        /** The deployed edge's minimum (`api/src/config.ts` `MIN_APP_VERSION`); any X.Y a refusal can name. */
        const val DEPLOYED_MINIMUM = "0.4"
    }

    private val pushToken = object : Binding<PushTokenPublisherState, EdgeSubject<PushTokenPublisher>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PushTokenPublisherState.ENROLLED)

        override fun create(state: PushTokenPublisherState, clauseId: String): Entered<EdgeSubject<PushTokenPublisher>> =
            if (state == PushTokenPublisherState.FOREIGN_TOKEN) {
                Entered.Unreachable("the mini-edge verifies no credential, so it cannot reject one (World.kt: modelling it is not done)")
            } else {
                enter({ PushTokenPublisherContract.seed(state, clauseId, it) }) { client, base, seeded ->
                    HttpPushTokenPublisher(client, base) { seeded.deviceId }
                }
            }
    }

    @Test
    fun `the mini-edge satisfies the PushTokenPublisher contract`() = verify(PushTokenPublisherContract, pushToken)
}
