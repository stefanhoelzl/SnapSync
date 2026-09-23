package app.snapsync.liveedge

import app.snapsync.attest.HttpAttestClient
import app.snapsync.contracts.AttestClientContract
import app.snapsync.contracts.AttestClientState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceFilesSourceContract
import app.snapsync.contracts.DeviceFilesSourceState
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
import app.snapsync.contracts.Host
import app.snapsync.contracts.LeaveNotifierContract
import app.snapsync.contracts.LeaveNotifierState
import app.snapsync.contracts.ManifestPublisherContract
import app.snapsync.contracts.ManifestPublisherState
import app.snapsync.contracts.PushTokenPublisherContract
import app.snapsync.contracts.PushTokenPublisherState
import app.snapsync.contracts.verify
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.eventcreation.HttpEventRename
import app.snapsync.join.HttpEventDirectory
import app.snapsync.join.HttpEventJoin
import app.snapsync.join.HttpManifestPublisher
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.ports.AttestClient
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.EventCreation
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.EventJoin
import app.snapsync.ports.EventRename
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.LeaveNotifier
import app.snapsync.ports.ManifestPublisher
import app.snapsync.ports.PushTokenPublisher
import app.snapsync.push.HttpPushTokenPublisher
import kotlin.test.Test

/**
 * The backend port contracts against the REAL backend (capability `port-contracts`): the production `Http*`
 * clients and interceptor over a socket to `api/`, served locally by [LiveEdge]. These are the bindings that
 * make every backend clause covered; the mini-edge's bindings in `:test:world` are the `Fake`s held to them.
 *
 * JVM only, and the coverage that forgoes is stated here (capability `testing-architecture`, "Every test runs
 * on every target its module declares"): a Kotlin/Native test executable under `simctl` cannot launch the
 * backend as a process. Nothing is lost by it: the clients are `commonMain` code, identical on every target, and
 * their Kotlin/Native compilation is covered by this module's `commonTest`. What differs on a device is the Ktor
 * ENGINE (Darwin), which no binding here — or anywhere yet — puts in front of a real backend.
 */
class LiveEdgeContractsTest {

    private val directory = object : Binding<EventDirectoryState, EdgeSubject<EventDirectory>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(EventDirectoryState.EVENT_EXISTS, EventDirectoryState.NO_SUCH_EVENT, EventDirectoryState.VERSION_REFUSED, EventDirectoryState.FOREIGN_TOKEN)

        override fun create(state: EventDirectoryState, clauseId: String): Entered<EdgeSubject<EventDirectory>> =
            LiveEdge.enter({ EventDirectoryContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventDirectory(client, base) }
    }

    @Test
    fun `the real edge satisfies the EventDirectory contract`() = verify(EventDirectoryContract, directory)

    private val creation = object : Binding<EventCreationState, EdgeSubject<EventCreation>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(EventCreationState.SERVING)

        override fun create(state: EventCreationState, clauseId: String): Entered<EdgeSubject<EventCreation>> =
            LiveEdge.enter({ EventCreationContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventCreation(client, base) }
    }

    @Test
    fun `the real edge satisfies the EventCreation contract`() = verify(EventCreationContract, creation)

    private val rename = object : Binding<EventRenameState, EdgeSubject<EventRename>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(EventRenameState.EVENT_EXISTS, EventRenameState.NO_SUCH_EVENT)

        override fun create(state: EventRenameState, clauseId: String): Entered<EdgeSubject<EventRename>> =
            LiveEdge.enter({ EventRenameContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventRename(client, base) }
    }

    @Test
    fun `the real edge satisfies the EventRename contract`() = verify(EventRenameContract, rename)

    private val join = object : Binding<EventJoinState, EdgeSubject<EventJoin>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(EventJoinState.EVENT_OPEN, EventJoinState.EVENT_FULL, EventJoinState.NO_SUCH_EVENT, EventJoinState.FOREIGN_TOKEN)

        override fun create(state: EventJoinState, clauseId: String): Entered<EdgeSubject<EventJoin>> =
            LiveEdge.enter({ EventJoinContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventJoin(client, base) }
    }

    @Test
    fun `the real edge satisfies the EventJoin contract`() = verify(EventJoinContract, join)

    private val manifest = object : Binding<ManifestPublisherState, EdgeSubject<ManifestPublisher>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(ManifestPublisherState.MEMBER, ManifestPublisherState.NON_MEMBER, ManifestPublisherState.NO_SUCH_EVENT)

        override fun create(state: ManifestPublisherState, clauseId: String): Entered<EdgeSubject<ManifestPublisher>> =
            LiveEdge.enter({ ManifestPublisherContract.seed(state, clauseId, it) }) { client, base, _ -> HttpManifestPublisher(client, base) }
    }

    @Test
    fun `the real edge satisfies the ManifestPublisher contract`() = verify(ManifestPublisherContract, manifest)

    private val union = object : Binding<EventUnionSourceState, EdgeSubject<EventUnionSource>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(EventUnionSourceState.NO_SUCH_EVENT, EventUnionSourceState.EMPTY_EVENT, EventUnionSourceState.COMPLETE_ASSET, EventUnionSourceState.INCOMPLETE_ASSET)

        override fun create(state: EventUnionSourceState, clauseId: String): Entered<EdgeSubject<EventUnionSource>> =
            LiveEdge.enter({ EventUnionSourceContract.seed(state, clauseId, it) }) { client, base, _ -> HttpEventUnionSource(client, base) }
    }

    @Test
    fun `the real edge satisfies the EventUnionSource contract`() = verify(EventUnionSourceContract, union)

    private val deviceFiles = object : Binding<DeviceFilesSourceState, EdgeSubject<DeviceFilesSource>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DeviceFilesSourceState.NO_UPLOADS, DeviceFilesSourceState.UPLOADED)

        override fun create(state: DeviceFilesSourceState, clauseId: String): Entered<EdgeSubject<DeviceFilesSource>> =
            LiveEdge.enter({ DeviceFilesSourceContract.seed(state, clauseId, it) }) { client, base, _ -> HttpDeviceFilesSource(client, base) }
    }

    @Test
    fun `the real edge satisfies the DeviceFilesSource contract`() = verify(DeviceFilesSourceContract, deviceFiles)

    private val leave = object : Binding<LeaveNotifierState, EdgeSubject<LeaveNotifier>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(LeaveNotifierState.MEMBER, LeaveNotifierState.NO_SUCH_EVENT)

        override fun create(state: LeaveNotifierState, clauseId: String): Entered<EdgeSubject<LeaveNotifier>> =
            LiveEdge.enter({ LeaveNotifierContract.seed(state, clauseId, it) }) { client, base, seeded -> HttpLeaveNotifier(client, base) { seeded.deviceId } }
    }

    @Test
    fun `the real edge satisfies the LeaveNotifier contract`() = verify(LeaveNotifierContract, leave)

    private val attest = object : Binding<AttestClientState, EdgeSubject<AttestClient>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(AttestClientState.SERVING)

        override fun create(state: AttestClientState, clauseId: String): Entered<EdgeSubject<AttestClient>> =
            LiveEdge.enter({ AttestClientContract.seed(state, clauseId, it) }) { client, base, _ -> HttpAttestClient(client, base) }
    }

    @Test
    fun `the real edge satisfies the AttestClient contract`() = verify(AttestClientContract, attest)

    private val pushToken = object : Binding<PushTokenPublisherState, EdgeSubject<PushTokenPublisher>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(PushTokenPublisherState.ENROLLED, PushTokenPublisherState.FOREIGN_TOKEN)

        // ENROLLED needs no setup step: the dev edge enrols the device a token-less push registration names
        // (`api/src/dev/fallback.ts`), because on a host without App Attest nothing else ever could.
        override fun create(state: PushTokenPublisherState, clauseId: String): Entered<EdgeSubject<PushTokenPublisher>> =
            LiveEdge.enter({ PushTokenPublisherContract.seed(state, clauseId, it) }) { client, base, seeded ->
                HttpPushTokenPublisher(client, base) { seeded.deviceId }
            }
    }

    @Test
    fun `the real edge satisfies the PushTokenPublisher contract`() = verify(PushTokenPublisherContract, pushToken)
}
